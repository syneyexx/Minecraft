package com.livingmods.simulation.persistence;

import com.livingmods.protocol.BinaryCodec;
import com.livingmods.simulation.CanonicalWorldState;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.UUID;

/**
 * Schema version 1 snapshot encoding for canonical world state.
 */
public final class CanonicalSaveFormat {
    public static final int SCHEMA_VERSION = 1;
    public static final int MAGIC = 0x4C4D4353; // LMCS

    private CanonicalSaveFormat() {}

    public record Snapshot(
            int schemaVersion,
            long seed,
            long timeTicks,
            long saveRevision,
            long planContentHash,
            long contentHash,
            UUID worldSessionId
    ) {}

    public static byte[] writeSnapshot(CanonicalWorldState state, UUID worldSessionId) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bos);
        out.writeInt(MAGIC);
        out.writeInt(SCHEMA_VERSION);
        out.writeLong(state.seed());
        out.writeLong(state.time().absoluteTicks());
        out.writeLong(state.saveRevision());
        out.writeLong(state.planContentHash());
        out.writeLong(state.contentHash());
        BinaryCodec.writeUuid(out, worldSessionId);
        out.writeInt(state.kingdoms().size());
        out.writeInt(state.settlements().size());
        out.writeInt(state.citizens().size());
        out.flush();
        return bos.toByteArray();
    }

    public static Snapshot readSnapshot(byte[] bytes) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes));
        int magic = in.readInt();
        if (magic != MAGIC) {
            throw new IOException("bad save magic");
        }
        int version = in.readInt();
        if (version != SCHEMA_VERSION) {
            throw new IOException("unsupported schema: " + version);
        }
        long seed = in.readLong();
        long ticks = in.readLong();
        long revision = in.readLong();
        long planHash = in.readLong();
        long contentHash = in.readLong();
        UUID session = BinaryCodec.readUuid(in);
        in.readInt();
        in.readInt();
        in.readInt();
        return new Snapshot(version, seed, ticks, revision, planHash, contentHash, session);
    }
}
