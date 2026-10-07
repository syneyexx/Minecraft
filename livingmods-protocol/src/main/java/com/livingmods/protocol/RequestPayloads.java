package com.livingmods.protocol;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.UUID;

/** Bounded request payloads from Minecraft. */
public final class RequestPayloads {
    public static final int MAX_QUERY_RADIUS = 512;
    public static final int MAX_QUERY_LIMIT = 256;
    public static final int MAX_LOCATE_LIMIT = 64;
    public static final int MAX_INTENT_LENGTH = 128;
    public static final int MAX_CONTEXT_LENGTH = 512;

    private RequestPayloads() {}

    public record SettlementQuery(UUID settlementId) {
        public byte[] encode() throws IOException {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            DataOutputStream dos = new DataOutputStream(bos);
            BinaryCodec.writeUuid(dos, settlementId);
            return bos.toByteArray();
        }
        public static SettlementQuery decode(byte[] p) throws IOException {
            return new SettlementQuery(BinaryCodec.readUuid(new DataInputStream(new ByteArrayInputStream(p))));
        }
    }

    public record NearbyQuery(int blockX, int blockZ, int radius, int limit) {
        public NearbyQuery {
            radius = clamp(radius, 0, MAX_QUERY_RADIUS);
            limit = clamp(limit, 1, MAX_QUERY_LIMIT);
        }

        public byte[] encode() throws IOException {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            DataOutputStream dos = new DataOutputStream(bos);
            dos.writeInt(blockX);
            dos.writeInt(blockZ);
            dos.writeInt(radius);
            dos.writeInt(limit);
            return bos.toByteArray();
        }
        public static NearbyQuery decode(byte[] p) throws IOException {
            DataInputStream dis = new DataInputStream(new ByteArrayInputStream(p));
            return new NearbyQuery(dis.readInt(), dis.readInt(), dis.readInt(), dis.readInt());
        }
    }

    public record DialogueQuery(UUID citizenId, UUID playerId, String intent, UUID settlementId, String recentContext) {
        public DialogueQuery {
            if (intent != null && intent.length() > MAX_INTENT_LENGTH) {
                intent = intent.substring(0, MAX_INTENT_LENGTH);
            }
            if (recentContext != null && recentContext.length() > MAX_CONTEXT_LENGTH) {
                recentContext = recentContext.substring(0, MAX_CONTEXT_LENGTH);
            }
        }

        public byte[] encode() throws IOException {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            DataOutputStream dos = new DataOutputStream(bos);
            BinaryCodec.writeUuid(dos, citizenId);
            BinaryCodec.writeUuid(dos, playerId);
            BinaryCodec.writeString(dos, intent);
            BinaryCodec.writeUuid(dos, settlementId);
            BinaryCodec.writeString(dos, recentContext);
            return bos.toByteArray();
        }
        public static DialogueQuery decode(byte[] p) throws IOException {
            DataInputStream dis = new DataInputStream(new ByteArrayInputStream(p));
            return new DialogueQuery(
                    BinaryCodec.readUuid(dis),
                    BinaryCodec.readUuid(dis),
                    BinaryCodec.readString(dis, MAX_INTENT_LENGTH),
                    BinaryCodec.readUuid(dis),
                    BinaryCodec.readString(dis, MAX_CONTEXT_LENGTH)
            );
        }
    }

    public record LocateQuery(String category, String nameFilter, int originX, int originZ, int limit) {
        public LocateQuery {
            limit = clamp(limit, 1, MAX_LOCATE_LIMIT);
            if (category != null && category.length() > MAX_INTENT_LENGTH) {
                category = category.substring(0, MAX_INTENT_LENGTH);
            }
            if (nameFilter != null && nameFilter.length() > MAX_CONTEXT_LENGTH) {
                nameFilter = nameFilter.substring(0, MAX_CONTEXT_LENGTH);
            }
        }

        public byte[] encode() throws IOException {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            DataOutputStream dos = new DataOutputStream(bos);
            BinaryCodec.writeString(dos, category);
            BinaryCodec.writeString(dos, nameFilter);
            dos.writeInt(originX);
            dos.writeInt(originZ);
            dos.writeInt(limit);
            return bos.toByteArray();
        }
        public static LocateQuery decode(byte[] p) throws IOException {
            DataInputStream dis = new DataInputStream(new ByteArrayInputStream(p));
            return new LocateQuery(
                    BinaryCodec.readString(dis, MAX_INTENT_LENGTH),
                    BinaryCodec.readString(dis, MAX_CONTEXT_LENGTH),
                    dis.readInt(), dis.readInt(), dis.readInt()
            );
        }
    }

    public record TimeSync(long minecraftDayTime, long minecraftGameTime, boolean jumped) {
        public byte[] encode() throws IOException {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            DataOutputStream dos = new DataOutputStream(bos);
            dos.writeLong(minecraftDayTime);
            dos.writeLong(minecraftGameTime);
            dos.writeBoolean(jumped);
            return bos.toByteArray();
        }
        public static TimeSync decode(byte[] p) throws IOException {
            DataInputStream dis = new DataInputStream(new ByteArrayInputStream(p));
            return new TimeSync(dis.readLong(), dis.readLong(), dis.readBoolean());
        }
    }

    public record RegionSubscription(int regionX, int regionZ, int detailLevel) {
        public RegionSubscription {
            detailLevel = clamp(detailLevel, -1, 3);
        }

        public byte[] encode() throws IOException {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            DataOutputStream dos = new DataOutputStream(bos);
            dos.writeInt(regionX);
            dos.writeInt(regionZ);
            dos.writeInt(detailLevel);
            return bos.toByteArray();
        }
        public static RegionSubscription decode(byte[] p) throws IOException {
            DataInputStream dis = new DataInputStream(new ByteArrayInputStream(p));
            return new RegionSubscription(dis.readInt(), dis.readInt(), dis.readInt());
        }
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }
}
