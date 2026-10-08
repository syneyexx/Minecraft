package com.livingmods.protocol;

import com.livingmods.common.model.PhysicalOutcomeType;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Typed REPORT_PHYSICAL_OUTCOME contract.
 * Evidence map carries verified server fields; never trust client reputation deltas as authority.
 */
public record PhysicalOutcomePayload(
        PhysicalOutcomeType outcomeType,
        UUID playerId,
        UUID targetId,
        int blockX,
        int blockY,
        int blockZ,
        long simulationRevision,
        Map<String, String> evidence
) {
    public PhysicalOutcomePayload {
        evidence = evidence == null ? Map.of() : Map.copyOf(evidence);
    }

    public byte[] encode() throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bos);
        out.writeInt(outcomeType.ordinal());
        BinaryCodec.writeUuid(out, playerId);
        BinaryCodec.writeUuid(out, targetId);
        out.writeInt(blockX);
        out.writeInt(blockY);
        out.writeInt(blockZ);
        out.writeLong(simulationRevision);
        out.writeInt(evidence.size());
        for (Map.Entry<String, String> e : evidence.entrySet()) {
            BinaryCodec.writeString(out, e.getKey());
            BinaryCodec.writeString(out, e.getValue());
        }
        out.flush();
        return bos.toByteArray();
    }

    public static PhysicalOutcomePayload decode(byte[] bytes) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes));
        int ordinal = in.readInt();
        PhysicalOutcomeType[] values = PhysicalOutcomeType.values();
        if (ordinal < 0 || ordinal >= values.length) {
            throw new IOException("invalid PhysicalOutcomeType ordinal: " + ordinal);
        }
        UUID playerId = BinaryCodec.readUuid(in);
        UUID targetId = BinaryCodec.readUuid(in);
        int x = in.readInt();
        int y = in.readInt();
        int z = in.readInt();
        long revision = in.readLong();
        int n = in.readInt();
        if (n < 0 || n > 256) {
            throw new IOException("invalid evidence count: " + n);
        }
        Map<String, String> evidence = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) {
            evidence.put(BinaryCodec.readString(in), BinaryCodec.readString(in));
        }
        return new PhysicalOutcomePayload(values[ordinal], playerId, targetId, x, y, z, revision, evidence);
    }

    /** Merge typed fields into evidence map for applier consumption. */
    public Map<String, String> evidenceWithIdentity() {
        Map<String, String> map = new LinkedHashMap<>(evidence);
        map.putIfAbsent("outcomeType", outcomeType.name());
        if (playerId != null) {
            map.putIfAbsent("playerId", playerId.toString());
        }
        if (targetId != null) {
            map.putIfAbsent("targetId", targetId.toString());
        }
        map.putIfAbsent("x", String.valueOf(blockX));
        map.putIfAbsent("y", String.valueOf(blockY));
        map.putIfAbsent("z", String.valueOf(blockZ));
        map.putIfAbsent("simulationRevision", String.valueOf(simulationRevision));
        return map;
    }
}
