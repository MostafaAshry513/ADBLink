package com.cgutman.adblib

import java.nio.ByteBuffer

/**
 * This class provides useful functions and fields for ADB protocol details.
 * @author Cameron Gutman
 */
object AdbProtocol {

    /** The length of the ADB message header */
    const val ADB_HEADER_LENGTH = 24

    const val CMD_SYNC = 0x434e5953

    /** CNXN is the connect message. No messages (except AUTH)
     * are valid before this message is received. */
    const val CMD_CNXN = 0x4e584e43

    /** The current version of the ADB protocol */
    const val CONNECT_VERSION = 0x01000000

    /** The maximum data payload supported by the ADB implementation */
    const val CONNECT_MAXDATA = 4096

    /** The payload sent with the connect message */
    @JvmField
    var CONNECT_PAYLOAD: ByteArray = "host::\u0000".toByteArray(Charsets.UTF_8)

    /** AUTH is the authentication message. It is part of the
     * RSA public key authentication added in Android 4.2.2. */
    const val CMD_AUTH = 0x48545541

    /** This authentication type represents a SHA1 hash to sign */
    const val AUTH_TYPE_TOKEN = 1

    /** This authentication type represents the signed SHA1 hash */
    const val AUTH_TYPE_SIGNATURE = 2

    /** This authentication type represents a RSA public key */
    const val AUTH_TYPE_RSA_PUBLIC = 3

    /** OPEN is the open stream message. It is sent to open
     * a new stream on the target device. */
    const val CMD_OPEN = 0x4e45504f

    /** OKAY is a success message. It is sent when a write is
     * processed successfully. */
    const val CMD_OKAY = 0x59414b4f

    /** CLSE is the close stream message. It it sent to close an
     * existing stream on the target device. */
    const val CMD_CLSE = 0x45534c43

    /** WRTE is the write stream message. It is sent with a payload
     * that is the data to write to the stream. */
    const val CMD_WRTE = 0x45545257

    /**
     * This function validate the ADB message by checking
     * its command, magic, and payload checksum.
     * @param msg ADB message to validate
     * @return True if the message was valid, false otherwise
     */
    @JvmStatic
    fun validateMessage(msg: AdbMessage): Boolean {
        /* Magic is cmd ^ 0xFFFFFFFF */
        if (msg.command != (msg.magic xor -1)) return false

        if (msg.payloadLength != 0) {
            val payload = msg.payload ?: return false
            if (AdbMessage.checksum(payload) != msg.checksum) return false
        }
        return true
    }

    /**
     * This function generates an ADB message given the fields.
     * @param cmd Command identifier
     * @param arg0 First argument
     * @param arg1 Second argument
     * @param payload Data payload
     * @return AdbMessage
     */
    @JvmStatic
    fun generateMessage(cmd: Int, arg0: Int, arg1: Int, payload: ByteArray?): AdbMessage {
        /* struct message {
         *      unsigned command;       // command identifier constant
         *      unsigned arg0;          // first argument
         *      unsigned arg1;          // second argument
         *      unsigned data_length;   // length of payload (0 is allowed)
         *      unsigned data_check;    // checksum of data payload
         *      unsigned magic;         // command ^ 0xffffffff
         * };
         */
        return AdbMessage(cmd, arg0, arg1, payload)
    }

    /**
     * Generates a connect message with default parameters.
     * @return AdbMessage
     */
    @JvmStatic
    fun generateConnect(): AdbMessage =
        generateMessage(CMD_CNXN, CONNECT_VERSION, CONNECT_MAXDATA, CONNECT_PAYLOAD)

    /**
     * Generates an auth message with the specified type and payload.
     * @param type Authentication type (see AUTH_TYPE_* constants)
     * @param data The payload for the message
     * @return AdbMessage
     */
    @JvmStatic
    fun generateAuth(type: Int, data: ByteArray?): AdbMessage =
        generateMessage(CMD_AUTH, type, 0, data)

    /**
     * Generates an open stream message with the specified local ID and destination.
     * @param localId A unique local ID identifying the stream
     * @param dest The destination of the stream on the target
     * @return AdbMessage
     */
    @JvmStatic
    fun generateOpen(localId: Int, dest: String): AdbMessage {
        val bbuf = ByteBuffer.allocate(dest.length + 1)
        bbuf.put(dest.toByteArray(Charsets.UTF_8))
        bbuf.put(0.toByte())
        return generateMessage(CMD_OPEN, localId, 0, bbuf.array())
    }

    /**
     * Generates a write stream message with the specified IDs and payload.
     * @param localId The unique local ID of the stream
     * @param remoteId The unique remote ID of the stream
     * @param data The data to provide as the write payload
     * @return AdbMessage
     */
    @JvmStatic
    fun generateWrite(localId: Int, remoteId: Int, data: ByteArray?): AdbMessage =
        generateMessage(CMD_WRTE, localId, remoteId, data)

    /**
     * Generates a close stream message with the specified IDs.
     * @param localId The unique local ID of the stream
     * @param remoteId The unique remote ID of the stream
     * @return AdbMessage
     */
    @JvmStatic
    fun generateClose(localId: Int, remoteId: Int): AdbMessage =
        generateMessage(CMD_CLSE, localId, remoteId, null)

    /**
     * Generates an okay message with the specified IDs.
     * @param localId The unique local ID of the stream
     * @param remoteId The unique remote ID of the stream
     * @return AdbMessage
     */
    @JvmStatic
    fun generateReady(localId: Int, remoteId: Int): AdbMessage =
        generateMessage(CMD_OKAY, localId, remoteId, null)
}
