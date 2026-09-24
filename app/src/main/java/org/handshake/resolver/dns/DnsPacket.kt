package org.handshake.resolver.dns

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.net.IDN
import java.net.InetAddress
import java.nio.ByteBuffer

object Punycode {
    private const val BASE = 36
    private const val TMIN = 1
    private const val TMAX = 26
    private const val SKEW = 38
    private const val DAMP = 700
    private const val INITIAL_BIAS = 72
    private const val INITIAL_N = 128
    private const val DELIMITER = '-'

    /**
     * Encodes a single label to RFC 3492 Punycode without Unicode NFKC mapping.
     */
    fun encodeLabel(input: String): String {
        val s = input.trim().lowercase()
        if (s.startsWith("xn--")) return s
        if (s.all { it.code < 128 }) return s

        val codePoints = s.codePoints().toArray()
        var n = INITIAL_N
        var delta = 0
        var bias = INITIAL_BIAS
        val output = StringBuilder("xn--")

        for (cp in codePoints) {
            if (cp < 128) output.append(cp.toChar())
        }

        val b = output.length - 4
        var h = b
        if (h > 0) output.append(DELIMITER)

        while (h < codePoints.size) {
            var m = Int.MAX_VALUE
            for (cp in codePoints) {
                if (cp in n until m) m = cp
            }

            delta += (m - n) * (h + 1)
            n = m

            for (cp in codePoints) {
                if (cp < n) delta++
                if (cp == n) {
                    var q = delta
                    var k = BASE
                    while (true) {
                        val t = when {
                            k <= bias -> TMIN
                            k >= bias + TMAX -> TMAX
                            else -> k - bias
                        }
                        if (q < t) break
                        val digit = t + ((q - t) % (BASE - t))
                        output.append(digitToChar(digit))
                        q = (q - t) / (BASE - t)
                        k += BASE
                    }
                    output.append(digitToChar(q))
                    bias = adapt(delta, h + 1, h == b)
                    delta = 0
                    h++
                }
            }
            delta++
            n++
        }
        return output.toString()
    }

    private fun adapt(delta: Int, numPoints: Int, firstTime: Boolean): Int {
        var d = if (firstTime) delta / DAMP else delta / 2
        d += d / numPoints
        var k = 0
        while (d > ((BASE - TMIN) * TMAX) / 2) {
            d /= (BASE - TMIN)
            k += BASE
        }
        return k + (((BASE - TMIN + 1) * d) / (d + SKEW))
    }

    private fun digitToChar(digit: Int): Char {
        return if (digit < 26) ('a'.code + digit).toChar() else ('0'.code + (digit - 26)).toChar()
    }

    fun toPunycode(domain: String): String {
        return domain.trim().split('.').joinToString(".") { encodeLabel(it) }
    }
}

data class DnsQuestion(
    val name: String,
    val type: Int,
    val clazz: Int
) {
    /**
     * TLD in canonical lowercase ASCII / Punycode format (e.g. "com", "badass", "xn--randm-cka").
     */
    val tld: String
        get() {
            val parts = name.trimEnd('.').split('.')
            val last = parts.lastOrNull()?.lowercase() ?: ""
            return toPunycode(last)
        }

    val unicodeName: String
        get() = try {
            IDN.toUnicode(name)
        } catch (_: Exception) {
            name
        }

    companion object {
        fun toPunycode(domain: String): String = Punycode.toPunycode(domain)
    }
}

data class DnsRecord(
    val name: String,
    val type: Int,
    val clazz: Int,
    val ttl: Long,
    val rdata: ByteArray,
    val rdataOffset: Int = 0
) {
    fun getAsIpv4(): String? {
        if (type == DnsPacket.TYPE_A && rdata.size == 4) {
            return try {
                InetAddress.getByAddress(rdata).hostAddress
            } catch (_: Exception) {
                null
            }
        }
        return null
    }

    fun getAsDomainName(fullPayload: ByteArray): String? {
        if (type == DnsPacket.TYPE_NS || type == DnsPacket.TYPE_CNAME) {
            return try {
                val buf = ByteBuffer.wrap(fullPayload)
                buf.position(rdataOffset)
                DnsPacket.readDomainName(buf, 0)
            } catch (_: Exception) {
                null
            }
        }
        return null
    }
}

class DnsPacket(
    val id: Int,
    val flags: Int,
    val questions: List<DnsQuestion>,
    val answers: List<DnsRecord>,
    val authorities: List<DnsRecord>,
    val additionals: List<DnsRecord>,
    val rawPayload: ByteArray
) {
    val isQuery: Boolean = (flags and 0x8000) == 0
    val rcode: Int = flags and 0x0F
    val isReferral: Boolean = (answers.isEmpty() && authorities.isNotEmpty())

    /**
     * Finds any IPv4 addresses in Additional records (GLUE) for nameservers.
     */
    fun findGlueIpv4(): String? {
        for (record in additionals) {
            val ip = record.getAsIpv4()
            if (ip != null) return ip
        }
        return null
    }

    companion object {
        const val TYPE_A = 1
        const val TYPE_NS = 2
        const val TYPE_CNAME = 5
        const val TYPE_SOA = 6
        const val TYPE_PTR = 12
        const val TYPE_TXT = 16
        const val TYPE_AAAA = 28
        const val TYPE_ANY = 255

        fun parse(data: ByteArray, offset: Int = 0, length: Int = data.size): DnsPacket? {
            if (length < 12) return null
            val buffer = ByteBuffer.wrap(data, offset, length)

            val id = buffer.short.toInt() and 0xFFFF
            val flags = buffer.short.toInt() and 0xFFFF
            val qdCount = buffer.short.toInt() and 0xFFFF
            val anCount = buffer.short.toInt() and 0xFFFF
            val nsCount = buffer.short.toInt() and 0xFFFF
            val arCount = buffer.short.toInt() and 0xFFFF

            val questions = mutableListOf<DnsQuestion>()
            for (i in 0 until qdCount) {
                val qName = readDomainName(buffer, offset) ?: return null
                if (buffer.remaining() < 4) return null
                val qType = buffer.short.toInt() and 0xFFFF
                val qClass = buffer.short.toInt() and 0xFFFF
                questions.add(DnsQuestion(qName, qType, qClass))
            }

            val answers = readRecords(buffer, anCount, offset)
            val authorities = readRecords(buffer, nsCount, offset)
            val additionals = readRecords(buffer, arCount, offset)

            val payload = ByteArray(length)
            System.arraycopy(data, offset, payload, 0, length)
            return DnsPacket(id, flags, questions, answers, authorities, additionals, payload)
        }

        private fun readRecords(buffer: ByteBuffer, count: Int, baseOffset: Int): List<DnsRecord> {
            val records = mutableListOf<DnsRecord>()
            for (i in 0 until count) {
                if (!buffer.hasRemaining()) break
                val name = readDomainName(buffer, baseOffset) ?: break
                if (buffer.remaining() < 10) break
                val type = buffer.short.toInt() and 0xFFFF
                val clazz = buffer.short.toInt() and 0xFFFF
                val ttl = buffer.int.toLong() and 0xFFFFFFFFL
                val rdLength = buffer.short.toInt() and 0xFFFF
                if (buffer.remaining() < rdLength) break
                val rdataOffset = buffer.position()
                val rdata = ByteArray(rdLength)
                buffer.get(rdata)
                records.add(DnsRecord(name, type, clazz, ttl, rdata, rdataOffset))
            }
            return records
        }

        fun readDomainName(buffer: ByteBuffer, baseOffset: Int): String? {
            val sb = StringBuilder()
            var jumped = false
            var originalPos = -1
            var steps = 0

            while (buffer.hasRemaining() && steps < 128) {
                steps++
                val len = buffer.get().toInt() and 0xFF
                if (len == 0) {
                    break
                }
                // Pointer compression (11xxxxxx)
                if ((len and 0xC0) == 0xC0) {
                    if (!buffer.hasRemaining()) return null
                    val b2 = buffer.get().toInt() and 0xFF
                    val pointer = ((len and 0x3F) shl 8) or b2
                    if (!jumped) {
                        originalPos = buffer.position()
                        jumped = true
                    }
                    val targetPos = baseOffset + pointer
                    if (targetPos >= buffer.limit() || targetPos < 0) return null
                    buffer.position(targetPos)
                } else {
                    if (buffer.remaining() < len) return null
                    val labelBytes = ByteArray(len)
                    buffer.get(labelBytes)
                    sb.append(String(labelBytes, Charsets.US_ASCII)).append('.')
                }
            }

            if (jumped && originalPos != -1) {
                buffer.position(originalPos)
            }

            return sb.toString().trimEnd('.')
        }

        fun writeDomainName(dos: DataOutputStream, domain: String) {
            val ascii = Punycode.toPunycode(domain)
            val labels = ascii.trimEnd('.').split('.')
            for (label in labels) {
                if (label.isEmpty()) continue
                dos.writeByte(label.length)
                for (c in label.toCharArray()) {
                    dos.writeByte(c.code)
                }
            }
            dos.writeByte(0) // End label
        }

        fun domainNameToBytes(domain: String): ByteArray {
            val baos = ByteArrayOutputStream()
            val dos = DataOutputStream(baos)
            writeDomainName(dos, domain)
            dos.flush()
            return baos.toByteArray()
        }

        /**
         * Builds a complete recursive DNS response following a CNAME:
         * Answer 1: QNAME CNAME targetDomain
         * Answer 2: targetDomain A targetIp
         * Answer 3: QNAME A targetIp (for stub clients that require direct QNAME answer)
         */
        fun buildCnameResolutionResponse(
            queryId: Int,
            queryName: String,
            cnameTarget: String,
            targetIp: String,
            ttl: Long = 60
        ): ByteArray {
            val out = ByteArrayOutputStream()
            val dos = DataOutputStream(out)

            // DNS Header: ID, Flags (QR, RD, RA, NOERROR), QD=1, AN=3, NS=0, AR=0
            dos.writeShort(queryId)
            dos.writeShort(0x8180)
            dos.writeShort(1) // QDCOUNT
            dos.writeShort(3) // ANCOUNT
            dos.writeShort(0)
            dos.writeShort(0)

            // Question
            writeDomainName(dos, queryName)
            dos.writeShort(TYPE_A)
            dos.writeShort(1) // IN

            // Answer 1: QNAME CNAME targetDomain
            writeDomainName(dos, queryName)
            dos.writeShort(TYPE_CNAME)
            dos.writeShort(1) // IN
            dos.writeInt(ttl.toInt())
            val targetBytes = domainNameToBytes(cnameTarget)
            dos.writeShort(targetBytes.size)
            dos.write(targetBytes)

            val ipParts = targetIp.split(".").map { it.toInt() }

            // Answer 2: targetDomain A targetIp
            writeDomainName(dos, cnameTarget)
            dos.writeShort(TYPE_A)
            dos.writeShort(1) // IN
            dos.writeInt(ttl.toInt())
            dos.writeShort(4)
            for (part in ipParts) dos.writeByte(part)

            // Answer 3: QNAME A targetIp
            writeDomainName(dos, queryName)
            dos.writeShort(TYPE_A)
            dos.writeShort(1) // IN
            dos.writeInt(ttl.toInt())
            dos.writeShort(4)
            for (part in ipParts) dos.writeByte(part)

            dos.flush()
            return out.toByteArray()
        }

        /**
         * Replaces the 2-byte transaction ID in raw DNS payload.
         */
        fun replaceId(payload: ByteArray, newId: Int): ByteArray {
            val copy = payload.clone()
            copy[0] = ((newId shr 8) and 0xFF).toByte()
            copy[1] = (newId and 0xFF).toByte()
            return copy
        }
    }
}
