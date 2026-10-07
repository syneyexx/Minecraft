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
    public static final int MAX_MAP_ENTRIES = 4096;
    public static final int MAX_LIST_ENTRIES = 8192;

    private PayloadIo() {}

    public static byte[] encodeStrings(Map<String, String> map) throws IOException {
        if (map.size() > MAX_MAP_ENTRIES) {
            throw new IOException("string map too large: " + map.size());
        }
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
        int n = readBoundedCount(dis, MAX_MAP_ENTRIES, "string map");
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) {
            map.put(BinaryCodec.readString(dis), BinaryCodec.readString(dis));
        }
        return map;
    }

    public static byte[] encodeStringList(List<String> list) throws IOException {
        if (list.size() > MAX_LIST_ENTRIES) {
            throw new IOException("string list too large: " + list.size());
        }
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
        int n = readBoundedCount(dis, MAX_LIST_ENTRIES, "string list");
        List<String> list = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            list.add(BinaryCodec.readString(dis));
        }
        return list;
    }

    public static <T> byte[] encodeList(List<T> items, IoWriter<T> writer) throws IOException {
        if (items.size() > MAX_LIST_ENTRIES) {
            throw new IOException("list too large: " + items.size());
        }
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
        int n = readBoundedCount(dis, MAX_LIST_ENTRIES, "list");
        List<T> list = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            list.add(reader.read(dis));
        }
        return list;
    }

    public static int readBoundedCount(DataInputStream dis, int max, String label) throws IOException {
        int n = dis.readInt();
        if (n < 0 || n > max) {
            throw new IOException("invalid " + label + " count: " + n + " (max " + max + ")");
        }
        return n;
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
