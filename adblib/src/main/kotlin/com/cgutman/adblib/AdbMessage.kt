package com.cgutman.adblib

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * This class provides an abstraction for the ADB message format.
 * @author Cameron Gutman
 */
class AdbMessage @JvmOverloads constructor(
    command: Int,
    arg0: Int,
    arg1: Int,
    var payload: ByteArray? = null
) {
    private val messageBuffer: ByteBuffer =
        ByteBuffer.allocate(AdbProtocol.ADB_HEADER_LENGTH).order(ByteOrder.LITTLE_ENDIAN)

    init {
        // sets the fields in the command header
        messageBuffer.putInt(0, command)
        messageBuffer.putInt(4, arg0)
        messageBuffer.putInt(8, arg1)
        messageBuffer.putInt(12, payload?.size ?: 0)
        messageBuffer.putInt(16, payload?.let { checksum(it) } ?: 0)
        messageBuffer.putInt(20, command xor -1) // command ^ 0xFFFFFFFF
    }

    /** The command field of the message */
    val command: Int get() = messageBuffer.getInt(0)

    /** The arg0 field of the message */
    val arg0: Int get() = messageBuffer.getInt(4)

    /** The arg1 field of the message */
    val arg1: Int get() = messageBuffer.getInt(8)

    /** The payload length field of the message */
    val payloadLength: Int get() = messageBuffer.getInt(12)

    /** The checksum field of the message */
    val checksum: Int get() = messageBuffer.getInt(16)

    /** The magic field of the message */
    val magic: Int get() = messageBuffer.getInt(20)

    /** The 24-byte message header. */
    val message: ByteArray get() = messageBuffer.array()

    companion object {
        /**
         * This function performs a checksum on the ADB payload data.
         * @param payload Payload to checksum
         * @return The checksum of the payload
         */
        @JvmStatic
        fun checksum(payload: ByteArray): Int {
            var checksum = 0
            for (b in payload) {
                // unsigned byte value
                checksum += b.toInt() and 0xff
            }
            return checksum
        }
    }
}
