package com.livingmods.protocol;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Compact versioned binary codec. No Java object serialization.
 */
public final class BinaryCodec {
    private BinaryCodec() {}

    public static void writeEnvelope(OutputStream out, Envelope envelope) throws IOException {
        DataOutputStream dos = new DataOutputStream(out);
        dos.writeInt(ProtocolConstants.MAGIC);
        dos.writeInt(envelope.protocolVersion());
        dos.writeInt(envelope.type().code());
        dos.writeInt(envelope.flags());
        dos.writeLong(envelope.messageId());
        dos.writeLong(envelope.requestId());
        dos.writeLong(envelope.simulationTicks());
        writeUuid(dos, envelope.worldSessionId());
        byte[] payload = envelope.payload();
        dos.writeInt(payload.length);
        dos.write(payload);
        dos.flush();
    }

    public static Envelope readEnvelope(InputStream in) throws IOException {
        DataInputStream dis = new DataInputStream(in);
        int magic = dis.readInt();
        if (magic != ProtocolConstants.MAGIC) {
            throw new IOException("Bad magic: 0x" + Integer.toHexString(magic));
        }
        int version = dis.readInt();
        if (version != ProtocolConstants.PROTOCOL_VERSION) {
            throw new IOException("Incompatible protocol version: " + version
                    + " (expected " + ProtocolConstants.PROTOCOL_VERSION + ")");
        }
        MessageType type = MessageType.fromCode(dis.readInt());
        int flags = dis.readInt();
        long messageId = dis.readLong();
        long requestId = dis.readLong();
        long simTicks = dis.readLong();
        UUID worldSessionId = readUuid(dis);
        int len = dis.readInt();
        if (len < 0 || len > 16 * 1024 * 1024) {
            throw new IOException("Invalid payload length: " + len);
        }
        byte[] payload = dis.readNBytes(len);
        if (payload.length != len) {
            throw new IOException("Truncated payload");
        }
        return new Envelope(version, type, flags, messageId, requestId, simTicks, worldSessionId, payload);
    }

    public static byte[] encodeEnvelope(Envelope envelope) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(64 + envelope.payloadLength());
        writeEnvelope(bos, envelope);
        return bos.toByteArray();
    }

    public static Envelope decodeEnvelope(byte[] bytes) throws IOException {
        return readEnvelope(new ByteArrayInputStream(bytes));
    }

    public static void writeString(DataOutputStream dos, String s) throws IOException {
        if (s == null) {
            dos.writeInt(-1);
            return;
        }
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        dos.writeInt(b.length);
        dos.write(b);
    }

    public static String readString(DataInputStream dis) throws IOException {
        int len = dis.readInt();
        if (len < 0) return null;
        byte[] b = dis.readNBytes(len);
        return new String(b, StandardCharsets.UTF_8);
    }

    public static void writeUuid(DataOutputStream dos, UUID uuid) throws IOException {
        if (uuid == null) {
            dos.writeLong(0);
            dos.writeLong(0);
            return;
        }
        dos.writeLong(uuid.getMostSignificantBits());
        dos.writeLong(uuid.getLeastSignificantBits());
    }

    public static UUID readUuid(DataInputStream dis) throws IOException {
        long msb = dis.readLong();
        long lsb = dis.readLong();
        if (msb == 0 && lsb == 0) return null;
        return new UUID(msb, lsb);
    }
}
