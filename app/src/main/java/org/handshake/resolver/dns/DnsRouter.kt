package org.handshake.resolver.dns

import android.net.VpnService
import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException

class DnsRouter(
    private val vpnService: VpnService,
    private val engine: org.handshake.resolver.engine.HnsEngine,
    private val hnsPort: Int = 5349,
    private val upstreamDnsIp: String = "1.1.1.1",
    private val upstreamPort: Int = 53
) {

    private val hnsAddress: InetAddress by lazy { InetAddress.getByName("127.0.0.1") }
    private val upstreamAddress: InetAddress by lazy { InetAddress.getByName(upstreamDnsIp) }

    /**
     * Resolves a DNS query payload and returns the DNS response payload.
     * Handles both direct Handshake root records, CNAME following, and recursive referral following.
     */
    fun routeQuery(rawQuery: ByteArray): ByteArray? {
        val parsed = DnsPacket.parse(rawQuery)
        val question = parsed?.questions?.firstOrNull()
        val queryName = question?.name ?: ""
        val tld = question?.tld ?: ""

        val isIcann = engine.isIcannTld(tld)

        if (!isIcann && tld.isNotEmpty()) {
            // 1. Query the local Handshake SPV root nameserver
            val hnsResponseBytes = queryServer(rawQuery, hnsAddress, hnsPort, timeoutMs = 1500)
            if (hnsResponseBytes != null) {
                val hnsParsed = DnsPacket.parse(hnsResponseBytes)
                if (hnsParsed != null) {
                    // Scenario A: Direct answer available from Handshake root
                    if (hnsParsed.answers.isNotEmpty() && hnsParsed.rcode == 0) {
                        val hasIp = hnsParsed.answers.any { it.type == DnsPacket.TYPE_A || it.type == DnsPacket.TYPE_AAAA }
                        if (hasIp) {
                            Log.d(TAG, "Direct IP answer for '$queryName' from Handshake root")
                            return setRecursionAvailable(hnsResponseBytes)
                        }

                        // Check if direct answer was a CNAME without A record
                        val cnameRecord = hnsParsed.answers.firstOrNull { it.type == DnsPacket.TYPE_CNAME }
                        if (cnameRecord != null) {
                            val cnameTarget = cnameRecord.getAsDomainName(hnsResponseBytes)
                            if (!cnameTarget.isNullOrEmpty()) {
                                val targetIp = resolveHostIp(cnameTarget)
                                if (targetIp != null) {
                                    Log.d(TAG, "Followed root CNAME '$cnameTarget' -> ${targetIp.hostAddress}")
                                    return DnsPacket.buildCnameResolutionResponse(
                                        hnsParsed.id, queryName, cnameTarget, targetIp.hostAddress ?: ""
                                    )
                                }
                            }
                        }
                    }

                    // Scenario B: Referral (NS records in authority section)
                    if (hnsParsed.isReferral) {
                        val recursiveAnswer = resolveReferralRecursively(rawQuery, queryName, hnsParsed)
                        if (recursiveAnswer != null) {
                            Log.d(TAG, "Recursively resolved '$queryName' via Handshake NS referral")
                            return setRecursionAvailable(recursiveAnswer)
                        }
                    }
                }
            }
        }

        // 2. Standard ICANN or Fallback: forward to upstream recursive resolver (e.g. Cloudflare 1.1.1.1)
        val upstreamResponse = queryServer(rawQuery, upstreamAddress, upstreamPort, timeoutMs = 2500)
        return upstreamResponse
    }

    /**
     * Recursively queries the authoritative nameservers specified in an NS referral
     * and follows any out-of-zone CNAME records to return complete A/AAAA answers.
     */
    private fun resolveReferralRecursively(
        rawQuery: ByteArray,
        queryName: String,
        referralPacket: DnsPacket
    ): ByteArray? {
        val candidateIps = mutableListOf<InetAddress>()

        // 1. Check for GLUE A record in Additional section
        val glueIp = referralPacket.findGlueIpv4()
        if (glueIp != null) {
            try {
                candidateIps.add(InetAddress.getByName(glueIp))
            } catch (_: Exception) {}
        }

        // 2. Extract NS domain names from Authority section using full payload decompression
        for (nsRecord in referralPacket.authorities) {
            if (nsRecord.type == DnsPacket.TYPE_NS) {
                val nsDomain = nsRecord.getAsDomainName(referralPacket.rawPayload)
                if (!nsDomain.isNullOrEmpty()) {
                    val nsIp = resolveHostIp(nsDomain)
                    if (nsIp != null && !candidateIps.contains(nsIp)) {
                        candidateIps.add(nsIp)
                    }
                }
            }
        }

        // 3. Query authoritative nameservers
        for (nsIp in candidateIps) {
            val responseBytes = queryServer(rawQuery, nsIp, 53, timeoutMs = 2000) ?: continue
            val responsePacket = DnsPacket.parse(responseBytes) ?: continue

            // If response has direct IP answer, return it
            val hasIp = responsePacket.answers.any { it.type == DnsPacket.TYPE_A || it.type == DnsPacket.TYPE_AAAA }
            if (hasIp) {
                return responseBytes
            }

            // If response has CNAME without A record (e.g. selfpublish.randºm -> calories.tplinkdns.com)
            val cnameRecord = responsePacket.answers.firstOrNull { it.type == DnsPacket.TYPE_CNAME }
            if (cnameRecord != null) {
                val cnameTarget = cnameRecord.getAsDomainName(responseBytes)
                if (!cnameTarget.isNullOrEmpty()) {
                    val targetIp = resolveHostIp(cnameTarget)
                    if (targetIp != null) {
                        Log.d(TAG, "Followed CNAME '$cnameTarget' -> ${targetIp.hostAddress}")
                        return DnsPacket.buildCnameResolutionResponse(
                            responsePacket.id, queryName, cnameTarget, targetIp.hostAddress ?: ""
                        )
                    }
                }
            }
        }

        return null
    }

    /**
     * Resolves the IP address of a hostname (e.g. ns1.registrar.com or a CNAME target).
     */
    private fun resolveHostIp(host: String): InetAddress? {
        return try {
            val queryBytes = buildSimpleAQuery(host)
            val response = queryServer(queryBytes, upstreamAddress, upstreamPort, timeoutMs = 2000)
            if (response != null) {
                val parsed = DnsPacket.parse(response)
                val ip = parsed?.answers?.firstOrNull { it.type == DnsPacket.TYPE_A }?.getAsIpv4()
                if (ip != null) InetAddress.getByName(ip) else null
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Sets the RA (Recursion Available) bit on DNS response flags.
     */
    private fun setRecursionAvailable(response: ByteArray): ByteArray {
        if (response.size < 4) return response
        val copy = response.clone()
        copy[3] = (copy[3].toInt() or 0x80).toByte()
        return copy
    }

    private fun queryServer(
        query: ByteArray,
        serverIp: InetAddress,
        serverPort: Int,
        timeoutMs: Int
    ): ByteArray? {
        var socket: DatagramSocket? = null
        return try {
            socket = DatagramSocket()
            vpnService.protect(socket)
            socket.soTimeout = timeoutMs

            val sendPacket = DatagramPacket(query, query.size, serverIp, serverPort)
            socket.send(sendPacket)

            val buffer = ByteArray(4096)
            val receivePacket = DatagramPacket(buffer, buffer.size)
            socket.receive(receivePacket)

            val result = ByteArray(receivePacket.length)
            System.arraycopy(receivePacket.data, 0, result, 0, receivePacket.length)
            result
        } catch (_: SocketTimeoutException) {
            null
        } catch (e: Exception) {
            Log.w(TAG, "DNS query to $serverIp:$serverPort failed: ${e.message}")
            null
        } finally {
            socket?.close()
        }
    }

    private fun buildSimpleAQuery(domain: String): ByteArray {
        val ascii = Punycode.toPunycode(domain)
        val labels = ascii.trimEnd('.').split('.')
        val output = mutableListOf<Byte>()

        // ID
        output.add(0x43)
        output.add(0x21)
        // Flags (Standard query, Recursion Desired)
        output.add(0x01)
        output.add(0x00)
        // QDCOUNT = 1, others 0
        output.add(0x00)
        output.add(0x01)
        repeat(6) { output.add(0x00) }

        for (label in labels) {
            output.add(label.length.toByte())
            for (c in label.toCharArray()) {
                output.add(c.code.toByte())
            }
        }
        output.add(0x00) // End of name
        // QTYPE = A (1), QCLASS = IN (1)
        output.add(0x00)
        output.add(0x01)
        output.add(0x00)
        output.add(0x01)

        return output.toByteArray()
    }

    companion object {
        private const val TAG = "DnsRouter"
    }
}
