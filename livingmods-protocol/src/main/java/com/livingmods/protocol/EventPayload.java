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
    public static final int MAX_DATA_ENTRIES = 64;

    public EventPayload {
        LinkedHashMap<String, String> copy = new LinkedHashMap<>();
        if (data != null) {
            int i = 0;
            for (Map.Entry<String, String> e : data.entrySet()) {
                if (i++ >= MAX_DATA_ENTRIES) break;
                copy.put(e.getKey(), e.getValue());
            }
        }
        data = copy;
    }

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
        String typeName = BinaryCodec.readString(dis, 128);
        CivilizationEventType type;
        try {
            type = CivilizationEventType.valueOf(typeName);
        } catch (RuntimeException e) {
            throw new IOException("invalid CivilizationEventType: " + typeName);
        }
        int rx = dis.readInt();
        int rz = dis.readInt();
        int bx = dis.readInt();
        int bz = dis.readInt();
        int n = PayloadIo.readBoundedCount(dis, MAX_DATA_ENTRIES, "event data");
        Map<String, String> data = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) {
            data.put(BinaryCodec.readString(dis), BinaryCodec.readString(dis));
        }
        return new EventPayload(type, rx, rz, bx, bz, data);
    }
}
