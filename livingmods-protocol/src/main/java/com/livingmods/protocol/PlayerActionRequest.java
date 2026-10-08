package com.livingmods.protocol;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Versioned typed player-action request (PROTOCOL_VERSION 4+).
 * Server derives identity/position; client-supplied UUID is advisory only for non-authoritative fields.
 */
public record PlayerActionRequest(
        PlayerActionType action,
        UUID playerId,
        UUID kingdomId,
        UUID settlementId,
        UUID citizenId,
        UUID targetId,
        UUID sessionId,
        int blockX,
        int blockY,
        int blockZ,
        long revision,
        Map<String, String> meta
) {
    /** Wire magic so typed payloads are never confused with legacy string-maps. */
    public static final int MAGIC = 0x50414D34; // PAM4
    public static final int FORMAT_VERSION = 1;
    public static final int MAX_META = 64;
    public static final int MAX_META_KEY = 64;
    public static final int MAX_META_VALUE = 256;
    public static final int MAX_AMOUNT = 64;

    public PlayerActionRequest {
        if (meta == null) {
            meta = Map.of();
        } else if (meta.size() > MAX_META) {
            Map<String, String> capped = new LinkedHashMap<>();
            int i = 0;
            for (Map.Entry<String, String> e : meta.entrySet()) {
                if (i++ >= MAX_META) break;
                capped.put(clampKey(e.getKey()), clampValue(e.getValue()));
            }
            meta = Map.copyOf(capped);
        } else {
            Map<String, String> cleaned = new LinkedHashMap<>();
            for (Map.Entry<String, String> e : meta.entrySet()) {
                cleaned.put(clampKey(e.getKey()), clampValue(e.getValue()));
            }
            meta = Map.copyOf(cleaned);
        }
        if (playerId == null) playerId = new UUID(0, 0);
        if (kingdomId == null) kingdomId = new UUID(0, 0);
        if (settlementId == null) settlementId = new UUID(0, 0);
        if (citizenId == null) citizenId = new UUID(0, 0);
        if (targetId == null) targetId = new UUID(0, 0);
        if (sessionId == null) sessionId = new UUID(0, 0);
    }

    public static PlayerActionRequest of(
            PlayerActionType action,
            UUID playerId,
            Map<String, String> meta
    ) {
        return new PlayerActionRequest(
                action, playerId, new UUID(0, 0), new UUID(0, 0), new UUID(0, 0), new UUID(0, 0),
                new UUID(0, 0), 0, 0, 0, 0L, meta == null ? Map.of() : meta);
    }

    public byte[] encode() throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(bos);
        dos.writeInt(MAGIC);
        dos.writeInt(FORMAT_VERSION);
        dos.writeInt(action.ordinal());
        BinaryCodec.writeUuid(dos, playerId);
        BinaryCodec.writeUuid(dos, kingdomId);
        BinaryCodec.writeUuid(dos, settlementId);
        BinaryCodec.writeUuid(dos, citizenId);
        BinaryCodec.writeUuid(dos, targetId);
        BinaryCodec.writeUuid(dos, sessionId);
        dos.writeInt(blockX);
        dos.writeInt(blockY);
        dos.writeInt(blockZ);
        dos.writeLong(revision);
        dos.writeInt(meta.size());
        for (Map.Entry<String, String> e : meta.entrySet()) {
            BinaryCodec.writeString(dos, e.getKey());
            BinaryCodec.writeString(dos, e.getValue());
        }
        return bos.toByteArray();
    }

    public static PlayerActionRequest decode(byte[] payload) throws IOException {
        DataInputStream dis = new DataInputStream(new ByteArrayInputStream(payload));
        int magic = dis.readInt();
        if (magic != MAGIC) {
            throw new IOException("not a typed PlayerActionRequest (bad magic)");
        }
        int version = dis.readInt();
        if (version != FORMAT_VERSION) {
            throw new IOException("unsupported PlayerActionRequest version: " + version);
        }
        int ordinal = dis.readInt();
        PlayerActionType[] values = PlayerActionType.values();
        if (ordinal < 0 || ordinal >= values.length) {
            throw new IOException("invalid PlayerActionType ordinal: " + ordinal);
        }
        UUID playerId = BinaryCodec.readUuid(dis);
        UUID kingdomId = BinaryCodec.readUuid(dis);
        UUID settlementId = BinaryCodec.readUuid(dis);
        UUID citizenId = BinaryCodec.readUuid(dis);
        UUID targetId = BinaryCodec.readUuid(dis);
        UUID sessionId = BinaryCodec.readUuid(dis);
        int blockX = dis.readInt();
        int blockY = dis.readInt();
        int blockZ = dis.readInt();
        long revision = dis.readLong();
        int n = PayloadIo.readBoundedCount(dis, MAX_META, "playerAction.meta");
        Map<String, String> meta = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) {
            meta.put(
                    BinaryCodec.readString(dis, MAX_META_KEY),
                    BinaryCodec.readString(dis, MAX_META_VALUE));
        }
        return new PlayerActionRequest(
                values[ordinal], playerId, kingdomId, settlementId, citizenId, targetId,
                sessionId, blockX, blockY, blockZ, revision, meta);
    }

    public String meta(String key) {
        return meta.getOrDefault(key, "");
    }

    public int metaInt(String key, int fallback) {
        try {
            return Integer.parseInt(meta.getOrDefault(key, String.valueOf(fallback)));
        } catch (Exception e) {
            return fallback;
        }
    }

    public double metaDouble(String key, double fallback) {
        try {
            return Double.parseDouble(meta.getOrDefault(key, String.valueOf(fallback)));
        } catch (Exception e) {
            return fallback;
        }
    }

    private static String clampKey(String s) {
        if (s == null) return "";
        return s.length() > MAX_META_KEY ? s.substring(0, MAX_META_KEY) : s;
    }

    private static String clampValue(String s) {
        if (s == null) return "";
        return s.length() > MAX_META_VALUE ? s.substring(0, MAX_META_VALUE) : s;
    }
}
