package com.livingmods.protocol;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.UUID;

/** Bounded request payloads from Minecraft. */
public final class RequestPayloads {
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
                    BinaryCodec.readString(dis),
                    BinaryCodec.readUuid(dis),
                    BinaryCodec.readString(dis)
            );
        }
    }

    public record LocateQuery(String category, String nameFilter, int originX, int originZ, int limit) {
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
                    BinaryCodec.readString(dis),
                    BinaryCodec.readString(dis),
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
}
