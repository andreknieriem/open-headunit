package com.andrerinas.openheadunit.utils.adb;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.Charset;

public class AdbProtocol {
    // StandardCharsets is unavailable before API 19; keep the ADB wire encoding shared.
    // Never substitute the device's default charset: payload lengths and checksums must describe
    // the same UTF-8 bytes on every supported Android version.
    static final Charset UTF_8 = Charset.forName("UTF-8");

    public static final int ADB_HEADER_LENGTH = 24;
    public static final int AUTH_TYPE_TOKEN = 1;
    public static final int AUTH_TYPE_SIGNATURE = 2;
    public static final int AUTH_TYPE_RSA_PUBLIC = 3;

    public static final int CMD_AUTH = 0x48545541; // "AUTH"
    public static final int CMD_CNXN = 0x4e584e43; // "CNXN"
    public static final int CMD_OPEN = 0x4e45504f; // "OPEN"
    public static final int CMD_OKAY = 0x59414b4f; // "OKAY"
    public static final int CMD_CLSE = 0x45534c43; // "CLSE"
    public static final int CMD_WRTE = 0x45545257; // "WRTE"

    public static final int CONNECT_VERSION = 0x01000000;
    public static final int CONNECT_MAXDATA = 4096;
    public static final byte[] CONNECT_PAYLOAD = "host::\u0000".getBytes(UTF_8);

    public static final class AdbMessage {
        public int command;
        public int arg0;
        public int arg1;
        public int payloadLength;
        public int checksum;
        public int magic;
        public byte[] payload;

        public static AdbMessage parseAdbMessage(InputStream inputStream) throws IOException {
            AdbMessage adbMessage = new AdbMessage();
            ByteBuffer header = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);
            int readBytes = 0;
            while (readBytes < 24) {
                int count = inputStream.read(header.array(), readBytes, 24 - readBytes);
                if (count < 0) {
                    throw new IOException("Stream closed");
                }
                readBytes += count;
            }

            adbMessage.command = header.getInt();
            adbMessage.arg0 = header.getInt();
            adbMessage.arg1 = header.getInt();
            adbMessage.payloadLength = header.getInt();
            adbMessage.checksum = header.getInt();
            adbMessage.magic = header.getInt();

            if (adbMessage.payloadLength > 0) {
                adbMessage.payload = new byte[adbMessage.payloadLength];
                int payloadRead = 0;
                while (payloadRead < adbMessage.payloadLength) {
                    int count = inputStream.read(adbMessage.payload, payloadRead, adbMessage.payloadLength - payloadRead);
                    if (count < 0) {
                        throw new IOException("Stream closed");
                    }
                    payloadRead += count;
                }
            }
            return adbMessage;
        }
    }

    public static byte[] generateMessage(int cmd, int arg0, int arg1, byte[] payload) {
        int length = (payload != null) ? payload.length : 0;
        ByteBuffer buffer = ByteBuffer.allocate(24 + length).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(cmd);
        buffer.putInt(arg0);
        buffer.putInt(arg1);
        buffer.putInt(length);
        buffer.putInt(payload != null ? getPayloadChecksum(payload) : 0);
        buffer.putInt(~cmd);
        if (payload != null) {
            buffer.put(payload);
        }
        return buffer.array();
    }

    public static byte[] generateConnect() {
        return generateMessage(CMD_CNXN, CONNECT_VERSION, CONNECT_MAXDATA, CONNECT_PAYLOAD);
    }

    public static byte[] generateAuth(int type, byte[] data) {
        return generateMessage(CMD_AUTH, type, 0, data);
    }

    public static byte[] generateOpen(int localId, String destination) {
        byte[] destBytes = (destination != null ? destination : "").getBytes(UTF_8);
        ByteBuffer destBuf = ByteBuffer.allocate(destBytes.length + 1);
        destBuf.put(destBytes);
        destBuf.put((byte) 0);
        return generateMessage(CMD_OPEN, localId, 0, destBuf.array());
    }

    public static byte[] generateReady(int localId, int remoteId) {
        return generateMessage(CMD_OKAY, localId, remoteId, null);
    }

    public static byte[] generateWrite(int localId, int remoteId, byte[] data) {
        return generateMessage(CMD_WRTE, localId, remoteId, data);
    }

    public static byte[] generateClose(int localId, int remoteId) {
        return generateMessage(CMD_CLSE, localId, remoteId, null);
    }

    private static int getPayloadChecksum(byte[] data) {
        int checksum = 0;
        for (byte b : data) {
            checksum += (b & 0xFF);
        }
        return checksum;
    }

    public static boolean validateMessage(AdbMessage msg) {
        if (msg.command != (~msg.magic)) {
            return false;
        }
        if (msg.payloadLength > 0 && msg.payload != null) {
            return getPayloadChecksum(msg.payload) == msg.checksum;
        }
        return true;
    }
}
