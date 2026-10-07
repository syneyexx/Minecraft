package com.livingmods.protocol;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Helpers for bounded request/response payloads. */
public final class PayloadIo {
    private PayloadIo() {}

    public static byte[] encodeStrings(Map<String, String> map) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(bos);
        dos.writeInt(map.size());
        for (Map.Entry<String, String> e : map.entrySet()) {
            BinaryCodec.writeString(dos, e.getKey());
            BinaryCodec.writeString(dos, e.getValue());
        }
        return bos.toByteArray();
    }

    public static Map<String, String> decodeStrings(byte[] payload) throws IOException {
        DataInputStream dis = new DataInputStream(new ByteArrayInputStream(payload));
        int n = dis.readInt();
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) {
            map.put(BinaryCodec.readString(dis), BinaryCodec.readString(dis));
        }
        return map;
    }

    public static byte[] encodeStringList(List<String> list) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(bos);
        dos.writeInt(list.size());
        for (String s : list) {
            BinaryCodec.writeString(dos, s);
        }
        return bos.toByteArray();
    }

    public static List<String> decodeStringList(byte[] payload) throws IOException {
        DataInputStream dis = new DataInputStream(new ByteArrayInputStream(payload));
        int n = dis.readInt();
        List<String> list = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            list.add(BinaryCodec.readString(dis));
        }
        return list;
    }

    public static <T> byte[] encodeList(List<T> items, IoWriter<T> writer) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(bos);
        dos.writeInt(items.size());
        for (T item : items) {
            writer.write(dos, item);
        }
        return bos.toByteArray();
    }

    public static <T> List<T> decodeList(byte[] payload, IoReader<T> reader) throws IOException {
        DataInputStream dis = new DataInputStream(new ByteArrayInputStream(payload));
        int n = dis.readInt();
        List<T> list = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            list.add(reader.read(dis));
        }
        return list;
    }

    @FunctionalInterface
    public interface IoWriter<T> {
        void write(DataOutputStream dos, T value) throws IOException;
    }

    @FunctionalInterface
    public interface IoReader<T> {
        T read(DataInputStream dis) throws IOException;
    }
}
