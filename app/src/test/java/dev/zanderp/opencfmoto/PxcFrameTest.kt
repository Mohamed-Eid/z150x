package dev.zanderp.opencfmoto

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class PxcFrameTest {
    private fun header(cmd: Int, totalLen: Int): ByteArray =
        ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(cmd).putInt(totalLen).putInt(cmd xor totalLen).putInt(0).array()

    @Test fun roundTrip() {
        val out = ByteArrayOutputStream()
        PxcFrame(PxcFrame.CMD_MDNS_RESPOND, "{\"a\":1}".toByteArray()).write(out)
        val back = PxcFrame.read(ByteArrayInputStream(out.toByteArray()))!!
        assertEquals(PxcFrame.CMD_MDNS_RESPOND, back.cmd)
        assertArrayEquals("{\"a\":1}".toByteArray(), back.payload)
    }

    @Test fun endOfStreamIsNull() {
        assertNull(PxcFrame.read(ByteArrayInputStream(ByteArray(0))))
    }

    /** A header with a valid "magic" but an absurd length must not allocate it. */
    @Test fun hugeLengthIsRejected() {
        assertThrows(IllegalStateException::class.java) {
            PxcFrame.read(ByteArrayInputStream(header(0x10, 0x7FFFFFF0)))
        }
        assertThrows(IllegalStateException::class.java) {
            PxcFrame.read(ByteArrayInputStream(header(0x10, PxcFrame.MAX_FRAME_BYTES + 1)))
        }
    }

    @Test fun lengthBelowTheHeaderIsRejected() {
        assertThrows(IllegalStateException::class.java) {
            PxcFrame.read(ByteArrayInputStream(header(0x10, 8)))
        }
        assertThrows(IllegalStateException::class.java) {
            PxcFrame.read(ByteArrayInputStream(header(0x10, -4)))
        }
    }

    @Test fun emptyPayloadIsFine() {
        val f = PxcFrame.read(ByteArrayInputStream(header(0x10, 16)))!!
        assertEquals(0, f.payload.size)
    }
}
