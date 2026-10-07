package com.livingmods.protocol;

import com.livingmods.common.model.WorldIdentityContract;
import com.livingmods.common.version.LivingModsVersions;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.UUID;

public record HandshakePayload(
        int protocolVersion,
        UUID worldId,
        String modVersion,
        int worldGenerationVersion,
        int saveSchema,
        String side, // "MINECRAFT" or "SIDECAR"
        HandshakeStatus status,
        String sidecarVersion,
        int canonicalSaveSchema,
        String message,
        long minecraftSeed,
        long worldPlanHash,
        int worldPlanRevision,
        String worldRoot
) {
    public enum HandshakeStatus { READY, REJECTED, NEEDS_MIGRATION }

    public static HandshakePayload minecraftRequest(UUID worldId) {
        return minecraftRequest(worldId, 0L, 0L, 0, null);
    }

    public static HandshakePayload minecraftRequest(WorldIdentityContract identity) {
        return minecraftRequest(identity, null);
    }

    public static HandshakePayload minecraftRequest(WorldIdentityContract identity, String worldRoot) {
        return minecraftRequest(
                identity.worldId(),
                identity.minecraftSeed(),
                identity.worldPlanHash(),
                identity.worldPlanRevision(),
                worldRoot
        );
    }

    public static HandshakePayload minecraftRequest(
            UUID worldId,
            long minecraftSeed,
            long worldPlanHash,
            int worldPlanRevision,
            String worldRoot
    ) {
        return new HandshakePayload(
                LivingModsVersions.PROTOCOL_VERSION,
                worldId,
                LivingModsVersions.MOD_VERSION,
                LivingModsVersions.WORLDGEN_VERSION,
                LivingModsVersions.CANONICAL_SAVE_SCHEMA,
                "MINECRAFT",
                HandshakeStatus.READY,
                "",
                LivingModsVersions.CANONICAL_SAVE_SCHEMA,
                "hello",
                minecraftSeed,
                worldPlanHash,
                worldPlanRevision,
                worldRoot
        );
    }

    public static HandshakePayload sidecarReady(UUID worldId) {
        return sidecarReady(worldId, 0L, 0L, 0, null);
    }

    public static HandshakePayload sidecarReady(
            UUID worldId,
            long minecraftSeed,
            long worldPlanHash,
            int worldPlanRevision,
            String worldRoot
    ) {
        return new HandshakePayload(
                LivingModsVersions.PROTOCOL_VERSION,
                worldId,
                LivingModsVersions.MOD_VERSION,
                LivingModsVersions.WORLDGEN_VERSION,
                LivingModsVersions.CANONICAL_SAVE_SCHEMA,
                "SIDECAR",
                HandshakeStatus.READY,
                LivingModsVersions.SIDECAR_VERSION,
                LivingModsVersions.CANONICAL_SAVE_SCHEMA,
                "ready",
                minecraftSeed,
                worldPlanHash,
                worldPlanRevision,
                worldRoot
        );
    }

    public static HandshakePayload rejected(UUID worldId, String reason) {
        return new HandshakePayload(
                LivingModsVersions.PROTOCOL_VERSION,
                worldId,
                LivingModsVersions.MOD_VERSION,
                LivingModsVersions.WORLDGEN_VERSION,
                LivingModsVersions.CANONICAL_SAVE_SCHEMA,
                "SIDECAR",
                HandshakeStatus.REJECTED,
                LivingModsVersions.SIDECAR_VERSION,
                LivingModsVersions.CANONICAL_SAVE_SCHEMA,
                reason,
                0L,
                0L,
                0,
                null
        );
    }

    public WorldIdentityContract toIdentityContract() {
        return WorldIdentityContract.of(worldId, minecraftSeed, worldPlanHash, worldPlanRevision);
    }

    public byte[] encode() throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(bos);
        dos.writeInt(protocolVersion);
        BinaryCodec.writeUuid(dos, worldId);
        BinaryCodec.writeString(dos, modVersion);
        dos.writeInt(worldGenerationVersion);
        dos.writeInt(saveSchema);
        BinaryCodec.writeString(dos, side);
        dos.writeInt(status.ordinal());
        BinaryCodec.writeString(dos, sidecarVersion);
        dos.writeInt(canonicalSaveSchema);
        BinaryCodec.writeString(dos, message);
        // Appended for backward-compatible decode of older peers.
        dos.writeLong(minecraftSeed);
        dos.writeLong(worldPlanHash);
        dos.writeInt(worldPlanRevision);
        BinaryCodec.writeString(dos, worldRoot);
        return bos.toByteArray();
    }

    public static HandshakePayload decode(byte[] payload) throws IOException {
        DataInputStream dis = new DataInputStream(new ByteArrayInputStream(payload));
        int protocolVersion = dis.readInt();
        UUID worldId = BinaryCodec.readUuid(dis);
        String modVersion = BinaryCodec.readString(dis);
        int worldgen = dis.readInt();
        int saveSchema = dis.readInt();
        String side = BinaryCodec.readString(dis);
        HandshakeStatus status = HandshakeStatus.values()[dis.readInt()];
        String sidecarVersion = BinaryCodec.readString(dis);
        int canonical = dis.readInt();
        String message = BinaryCodec.readString(dis);
        long minecraftSeed = 0L;
        long worldPlanHash = 0L;
        int worldPlanRevision = 0;
        String worldRoot = null;
        if (dis.available() >= 20) {
            minecraftSeed = dis.readLong();
            worldPlanHash = dis.readLong();
            worldPlanRevision = dis.readInt();
            if (dis.available() > 0) {
                worldRoot = BinaryCodec.readString(dis);
            }
        }
        return new HandshakePayload(protocolVersion, worldId, modVersion, worldgen, saveSchema,
                side, status, sidecarVersion, canonical, message,
                minecraftSeed, worldPlanHash, worldPlanRevision, worldRoot);
    }

    public boolean isCompatible() {
        return protocolVersion == LivingModsVersions.PROTOCOL_VERSION
                && worldGenerationVersion == LivingModsVersions.WORLDGEN_VERSION
                && saveSchema == LivingModsVersions.CANONICAL_SAVE_SCHEMA;
    }
}
