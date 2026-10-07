package com.livingmods.protocol;

import com.livingmods.common.event.CivilizationEventType;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

public record EventPayload(
        CivilizationEventType eventType,
        int regionX,
        int regionZ,
        int blockX,
        int blockZ,
        Map<String, String> data
) {
    public byte[] encode() throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(bos);
        BinaryCodec.writeString(dos, eventType.name());
        dos.writeInt(regionX);
        dos.writeInt(regionZ);
        dos.writeInt(blockX);
        dos.writeInt(blockZ);
        dos.writeInt(data.size());
        for (Map.Entry<String, String> e : data.entrySet()) {
            BinaryCodec.writeString(dos, e.getKey());
            BinaryCodec.writeString(dos, e.getValue());
        }
        return bos.toByteArray();
    }

    public static EventPayload decode(byte[] payload) throws IOException {
        DataInputStream dis = new DataInputStream(new ByteArrayInputStream(payload));
        CivilizationEventType type = CivilizationEventType.valueOf(BinaryCodec.readString(dis));
        int rx = dis.readInt();
        int rz = dis.readInt();
        int bx = dis.readInt();
        int bz = dis.readInt();
        int n = dis.readInt();
        Map<String, String> data = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) {
            data.put(BinaryCodec.readString(dis), BinaryCodec.readString(dis));
        }
        return new EventPayload(type, rx, rz, bx, bz, data);
    }
}
