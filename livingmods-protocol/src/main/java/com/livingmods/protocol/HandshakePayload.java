package com.livingmods.protocol;

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
        String message
) {
    public enum HandshakeStatus { READY, REJECTED, NEEDS_MIGRATION }

    public static HandshakePayload minecraftRequest(UUID worldId) {
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
                "hello"
        );
    }

    public static HandshakePayload sidecarReady(UUID worldId) {
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
                "ready"
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
                reason
        );
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
        return new HandshakePayload(protocolVersion, worldId, modVersion, worldgen, saveSchema,
                side, status, sidecarVersion, canonical, message);
    }

    public boolean isCompatible() {
        return protocolVersion == LivingModsVersions.PROTOCOL_VERSION
                && worldGenerationVersion == LivingModsVersions.WORLDGEN_VERSION
                && saveSchema == LivingModsVersions.CANONICAL_SAVE_SCHEMA;
    }
}
