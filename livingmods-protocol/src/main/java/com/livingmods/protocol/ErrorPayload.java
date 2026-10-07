package com.livingmods.protocol;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

public record ErrorPayload(int code, String message) {
    public static final int VERSION_MISMATCH = 1;
    public static final int WORLD_MISMATCH = 2;
    public static final int TIMEOUT = 3;
    public static final int INTERNAL = 4;
    public static final int NOT_READY = 5;
    public static final int MALFORMED = 6;

    public byte[] encode() throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(bos);
        dos.writeInt(code);
        BinaryCodec.writeString(dos, message);
        return bos.toByteArray();
    }

    public static ErrorPayload decode(byte[] payload) throws IOException {
        DataInputStream dis = new DataInputStream(new ByteArrayInputStream(payload));
        return new ErrorPayload(dis.readInt(), BinaryCodec.readString(dis));
    }
}
