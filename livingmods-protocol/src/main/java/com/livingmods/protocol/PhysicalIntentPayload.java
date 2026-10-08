package com.livingmods.protocol;

import com.livingmods.common.model.PhysicalIntentStatus;
import com.livingmods.common.model.PhysicalIntentType;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Typed, versioned construction / physical-intent list for reconciliation.
 * Replaces brittle {@code split(";")} / {@code split("|")} packing.
 */
public final class PhysicalIntentPayload {
    public static final int FORMAT_VERSION = 1;
    public static final int MAX_INTENTS = 64;
    public static final int MAX_GEOMETRY_POINTS = 128;
    public static final int MAX_CHUNK_KEYS = 256;
    public static final int MAX_META_ENTRIES = 32;
    public static final int MAX_STRING = 128;

    private PhysicalIntentPayload() {}

    public record IntentSlice(
            UUID intentId,
            PhysicalIntentType type,
            PhysicalIntentStatus status,
            int priority,
            int minX,
            int minZ,
            int maxX,
            int maxZ,
            String buildingRole,
            String cultureKey,
            UUID settlementId,
            UUID structureId,
            String variant,
            List<Long> pendingChunkKeys,
            List<Long> appliedChunkKeys,
            List<Integer> routePointsXz,
            Map<String, String> meta
    ) {
        public IntentSlice {
            buildingRole = buildingRole == null ? "" : truncate(buildingRole, MAX_STRING);
            cultureKey = cultureKey == null ? "" : truncate(cultureKey, MAX_STRING);
            variant = variant == null ? "" : truncate(variant, MAX_STRING);
            pendingChunkKeys = pendingChunkKeys == null ? List.of() : List.copyOf(pendingChunkKeys);
            appliedChunkKeys = appliedChunkKeys == null ? List.of() : List.copyOf(appliedChunkKeys);
            routePointsXz = routePointsXz == null ? List.of() : List.copyOf(routePointsXz);
            meta = meta == null ? Map.of() : Map.copyOf(meta);
        }
    }

    public record Bundle(String status, int intentCount, List<IntentSlice> intents) {
        public Bundle {
            status = status == null ? "ok" : status;
            intents = intents == null ? List.of() : List.copyOf(intents);
            intentCount = intents.size();
        }

        public byte[] encode() throws IOException {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bos);
            out.writeInt(FORMAT_VERSION);
            BinaryCodec.writeString(out, status);
            out.writeInt(Math.min(intents.size(), MAX_INTENTS));
            int written = 0;
            for (IntentSlice slice : intents) {
                if (written >= MAX_INTENTS) break;
                writeSlice(out, slice);
                written++;
            }
            out.flush();
            return bos.toByteArray();
        }

        public static Bundle decode(byte[] bytes) throws IOException {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes));
            int version = in.readInt();
            if (version != FORMAT_VERSION) {
                throw new IOException("unsupported PhysicalIntentPayload version: " + version);
            }
            String status = BinaryCodec.readString(in, MAX_STRING);
            int count = in.readInt();
            if (count < 0 || count > MAX_INTENTS) {
                throw new IOException("invalid intent count: " + count);
            }
            List<IntentSlice> intents = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                intents.add(readSlice(in));
            }
            return new Bundle(status, count, intents);
        }
    }

    private static void writeSlice(DataOutputStream out, IntentSlice s) throws IOException {
        BinaryCodec.writeUuid(out, s.intentId());
        out.writeInt(s.type().ordinal());
        out.writeInt(s.status().ordinal());
        out.writeInt(s.priority());
        out.writeInt(s.minX());
        out.writeInt(s.minZ());
        out.writeInt(s.maxX());
        out.writeInt(s.maxZ());
        BinaryCodec.writeString(out, s.buildingRole());
        BinaryCodec.writeString(out, s.cultureKey());
        BinaryCodec.writeUuid(out, s.settlementId() == null ? new UUID(0, 0) : s.settlementId());
        BinaryCodec.writeUuid(out, s.structureId() == null ? new UUID(0, 0) : s.structureId());
        BinaryCodec.writeString(out, s.variant());
        writeLongList(out, s.pendingChunkKeys(), MAX_CHUNK_KEYS);
        writeLongList(out, s.appliedChunkKeys(), MAX_CHUNK_KEYS);
        writeIntList(out, s.routePointsXz(), MAX_GEOMETRY_POINTS * 2);
        out.writeInt(Math.min(s.meta().size(), MAX_META_ENTRIES));
        int metaWritten = 0;
        for (Map.Entry<String, String> e : s.meta().entrySet()) {
            if (metaWritten >= MAX_META_ENTRIES) break;
            BinaryCodec.writeString(out, truncate(e.getKey(), MAX_STRING));
            BinaryCodec.writeString(out, truncate(e.getValue(), MAX_STRING));
            metaWritten++;
        }
    }

    private static IntentSlice readSlice(DataInputStream in) throws IOException {
        UUID intentId = BinaryCodec.readUuid(in);
        PhysicalIntentType type = ordinal(PhysicalIntentType.values(), in.readInt(), "PhysicalIntentType");
        PhysicalIntentStatus status = ordinal(PhysicalIntentStatus.values(), in.readInt(), "PhysicalIntentStatus");
        int priority = in.readInt();
        int minX = in.readInt();
        int minZ = in.readInt();
        int maxX = in.readInt();
        int maxZ = in.readInt();
        String role = BinaryCodec.readString(in, MAX_STRING);
        String culture = BinaryCodec.readString(in, MAX_STRING);
        UUID settlement = BinaryCodec.readUuid(in);
        UUID structure = BinaryCodec.readUuid(in);
        if (settlement.getMostSignificantBits() == 0 && settlement.getLeastSignificantBits() == 0) {
            settlement = null;
        }
        if (structure.getMostSignificantBits() == 0 && structure.getLeastSignificantBits() == 0) {
            structure = null;
        }
        String variant = BinaryCodec.readString(in, MAX_STRING);
        List<Long> pending = readLongList(in, MAX_CHUNK_KEYS);
        List<Long> applied = readLongList(in, MAX_CHUNK_KEYS);
        List<Integer> route = readIntList(in, MAX_GEOMETRY_POINTS * 2);
        int metaCount = in.readInt();
        if (metaCount < 0 || metaCount > MAX_META_ENTRIES) {
            throw new IOException("invalid meta count: " + metaCount);
        }
        Map<String, String> meta = new LinkedHashMap<>();
        for (int i = 0; i < metaCount; i++) {
            meta.put(BinaryCodec.readString(in, MAX_STRING), BinaryCodec.readString(in, MAX_STRING));
        }
        return new IntentSlice(
                intentId, type, status, priority, minX, minZ, maxX, maxZ,
                role, culture, settlement, structure, variant, pending, applied, route, meta
        );
    }

    private static void writeLongList(DataOutputStream out, List<Long> list, int max) throws IOException {
        int n = Math.min(list.size(), max);
        out.writeInt(n);
        for (int i = 0; i < n; i++) {
            out.writeLong(list.get(i));
        }
    }

    private static List<Long> readLongList(DataInputStream in, int max) throws IOException {
        int n = in.readInt();
        if (n < 0 || n > max) throw new IOException("invalid long list: " + n);
        List<Long> list = new ArrayList<>(n);
        for (int i = 0; i < n; i++) list.add(in.readLong());
        return list;
    }

    private static void writeIntList(DataOutputStream out, List<Integer> list, int max) throws IOException {
        int n = Math.min(list.size(), max);
        out.writeInt(n);
        for (int i = 0; i < n; i++) {
            out.writeInt(list.get(i));
        }
    }

    private static List<Integer> readIntList(DataInputStream in, int max) throws IOException {
        int n = in.readInt();
        if (n < 0 || n > max) throw new IOException("invalid int list: " + n);
        List<Integer> list = new ArrayList<>(n);
        for (int i = 0; i < n; i++) list.add(in.readInt());
        return list;
    }

    private static <E extends Enum<E>> E ordinal(E[] values, int ordinal, String name) throws IOException {
        if (ordinal < 0 || ordinal >= values.length) {
            throw new IOException("invalid " + name + " ordinal: " + ordinal);
        }
        return values[ordinal];
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max);
    }
}
