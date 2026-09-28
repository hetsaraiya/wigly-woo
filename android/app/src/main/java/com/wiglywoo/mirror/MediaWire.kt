package com.wiglywoo.mirror

import java.io.EOFException
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Session sockets are streams of records (length u32be, then payload); see
 * core/session/fd_unix.go. Media payloads carry the header from
 * core/session/media.go: kind u8, flags u8, pts u64be.
 */
object MediaWire {
    fun write(out: FileOutputStream, kind: Int, flags: Int, ptsNs: Long, buf: ByteBuffer, offset: Int, size: Int) {
        val packet = ByteArray(10 + size)
        packet[0] = kind.toByte()
        packet[1] = flags.toByte()
        ByteBuffer.wrap(packet, 2, 8).order(ByteOrder.BIG_ENDIAN).putLong(ptsNs)
        val dup = buf.duplicate()
        dup.position(offset)
        dup.limit(offset + size)
        dup.get(packet, 10, size)
        Records.write(out, packet)
    }
}

object Records {
    private const val MAX = 8 shl 20

    /** One record in one write, so concurrent writers never interleave. */
    fun write(out: FileOutputStream, payload: ByteArray) {
        val record = ByteArray(4 + payload.size)
        ByteBuffer.wrap(record).order(ByteOrder.BIG_ENDIAN).putInt(payload.size)
        System.arraycopy(payload, 0, record, 4, payload.size)
        synchronized(out) { out.write(record) }
    }

    /** Reassembles records from a socket that has a receive timeout. */
    class Reader(private val input: FileInputStream) {
        private var buf = ByteArray(64 * 1024)
        private var len = 0

        /** The next record, or null when none has arrived yet. Throws when the socket closes. */
        fun next(): ByteArray? {
            while (true) {
                take()?.let { return it }
                if (len == buf.size) buf = buf.copyOf(buf.size * 2)
                val n = input.read(buf, len, buf.size - len)
                if (n < 0) throw EOFException()
                if (n == 0) return null
                len += n
            }
        }

        private fun take(): ByteArray? {
            if (len < 4) return null
            val size = ByteBuffer.wrap(buf, 0, 4).order(ByteOrder.BIG_ENDIAN).int
            if (size < 0 || size > MAX) throw IOException("record of $size bytes")
            if (len < 4 + size) {
                if (buf.size < 4 + size) buf = buf.copyOf(4 + size)
                return null
            }
            val out = buf.copyOfRange(4, 4 + size)
            System.arraycopy(buf, 4 + size, buf, 0, len - 4 - size)
            len -= 4 + size
            return out
        }
    }
}
