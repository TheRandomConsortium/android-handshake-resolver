package org.handshake.resolver.dns

import android.net.VpnService
import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.nio.ByteBuffer

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
     * Handles both direct Handshake root records and recursive referral following.
     */
    fun routeQuery(rawQuery: ByteArray): ByteArray? {
        val parsed = DnsPacket.parse(rawQuery)
        val question = parsed?.questions?.firstOrNull()
        val tld = question?.tld ?: ""

        val isIcann = engine.isIcannTld(tld)

        if (!isIcann && tld.isNotEmpty()) {
            // 1. Query the local Handshake SPV root nameserver
            val hnsResponseBytes = queryServer(rawQuery, hnsAddress, hnsPort, timeoutMs = 1500)
            if (hnsResponseBytes != null) {
                val hnsParsed = DnsPacket.parse(hnsResponseBytes)
                if (hnsParsed != null) {
                    // Scenario A: Direct answer available from Handshake root (A, AAAA, TXT, CNAME)
                    if (hnsParsed.answers.isNotEmpty() && hnsParsed.rcode == 0) {
                        Log.d(TAG, "Direct answer for '${question?.name}' from Handshake root")
                        return setRecursionAvailable(hnsResponseBytes)
                    }

                    // Scenario B: Referral (NS records in authority section)
                    // We must follow the referral recursively so Android gets the final answer
                    if (hnsParsed.isReferral) {
                        val recursiveAnswer = resolveReferralRecursively(
                            rawQuery, question?.name ?: "", hnsParsed
                        )
                        if (recursiveAnswer != null) {
                            Log.d(TAG, "Recursively resolved '${question?.name}' via Handshake NS referral")
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
     * Recursively queries the authoritative nameservers specified in an NS referral.
     */
    private fun resolveReferralRecursively(
        rawQuery: ByteArray,
        queryName: String,
        referralPacket: DnsPacket
    ): ByteArray? {
        // Step 1: Check for GLUE A record in Additional section
        val glueIp = referralPacket.findGlueIpv4()
        if (glueIp != null) {
            try {
                val glueAddr = InetAddress.getByName(glueIp)
                val response = queryServer(rawQuery, glueAddr, 53, timeoutMs = 2000)
                if (response != null && isValidAnswer(response)) {
                    return response
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed querying GLUE IP $glueIp for $queryName: ${e.message}")
            }
        }

        // Step 2: Extract NS domain name from Authority section and resolve its IP via upstream
        for (nsRecord in referralPacket.authorities) {
            if (nsRecord.type == DnsPacket.TYPE_NS) {
                val nsDomain = nsRecord.getAsDomainName(ByteBuffer.wrap(referralPacket.rawPayload), 0)
                if (!nsDomain.isNullOrEmpty()) {
                    val nsIp = resolveHostIp(nsDomain)
                    if (nsIp != null) {
                        try {
                            val response = queryServer(rawQuery, nsIp, 53, timeoutMs = 2000)
                            if (response != null && isValidAnswer(response)) {
                                return response
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed querying NS $nsDomain ($nsIp) for $queryName: ${e.message}")
                        }
                    }
                }
            }
        }

        return null
    }

    /**
     * Resolves the IP address of an external authoritative nameserver (e.g. ns1.registrar.com).
     */
    private fun resolveHostIp(host: String): InetAddress? {
        return try {
            // Create a simple A record query for the NS host
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

    private fun isValidAnswer(response: ByteArray): Boolean {
        if (response.size < 12) return false
        val flags = ((response[2].toInt() and 0xFF) shl 8) or (response[3].toInt() and 0xFF)
        val rcode = flags and 0x0F
        val anCount = ((response[6].toInt() and 0xFF) shl 8) or (response[7].toInt() and 0xFF)
        return (rcode == 0 && anCount > 0)
    }

    /**
     * Sets the RA (Recursion Available) bit (0x0080 in flags) on DNS response.
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
        val ascii = DnsQuestion.toPunycode(domain)
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
