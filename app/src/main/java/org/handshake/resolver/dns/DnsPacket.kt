package org.handshake.resolver.dns

import java.net.IDN
import java.net.InetAddress
import java.nio.ByteBuffer

data class DnsQuestion(
    val name: String,
    val type: Int,
    val clazz: Int
) {
    /**
     * TLD in canonical lowercase ASCII / Punycode format (e.g. "com", "badass", "xn--53h").
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
        fun toPunycode(domain: String): String = try {
            IDN.toASCII(domain.trim(), IDN.ALLOW_UNASSIGNED).lowercase()
        } catch (_: Exception) {
            domain.lowercase()
        }
    }
}

data class DnsRecord(
    val name: String,
    val type: Int,
    val clazz: Int,
    val ttl: Long,
    val rdata: ByteArray
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

    fun getAsDomainName(rawBuffer: ByteBuffer, baseOffset: Int): String? {
        if (type == DnsPacket.TYPE_NS || type == DnsPacket.TYPE_CNAME) {
            return try {
                val buf = ByteBuffer.wrap(rdata)
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
     * Finds any IPv4 addresses in Additional records (GLUE) for a given nameserver.
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
                val rdata = ByteArray(rdLength)
                buffer.get(rdata)
                records.add(DnsRecord(name, type, clazz, ttl, rdata))
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
