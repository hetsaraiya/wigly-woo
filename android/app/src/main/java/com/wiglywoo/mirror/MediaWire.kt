package com.wiglywoo.mirror

import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Media datagram shared with core/session/media.go. */
object MediaWire {
    fun write(out: FileOutputStream, kind: Int, flags: Int, ptsNs: Long, data: ByteArray, offset: Int, size: Int) {
        val packet = ByteArray(10 + size)
        packet[0] = kind.toByte()
        packet[1] = flags.toByte()
        ByteBuffer.wrap(packet, 2, 8).order(ByteOrder.BIG_ENDIAN).putLong(ptsNs)
        System.arraycopy(data, offset, packet, 10, size)
        out.write(packet)
    }

    fun write(out: FileOutputStream, kind: Int, flags: Int, ptsNs: Long, buf: ByteBuffer, offset: Int, size: Int) {
        val bytes = ByteArray(size)
        val dup = buf.duplicate()
        dup.position(offset)
        dup.limit(offset + size)
        dup.get(bytes)
        write(out, kind, flags, ptsNs, bytes, 0, size)
    }
}
