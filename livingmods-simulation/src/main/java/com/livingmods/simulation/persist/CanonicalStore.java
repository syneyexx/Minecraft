package com.livingmods.simulation.persist;

import com.livingmods.common.version.LivingModsVersions;
import com.livingmods.simulation.CanonicalWorldState;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Canonical save + write-ahead log for sidecar persistence.
 */
public final class CanonicalStore {
    private static final int MAGIC = 0x4C4D4353; // LMCS

    private final Path saveDir;
    private final Path canonicalFile;
    private final Path walFile;

    public CanonicalStore(Path saveDir) {
        this.saveDir = saveDir;
        this.canonicalFile = saveDir.resolve("canonical.bin");
        this.walFile = saveDir.resolve("canonical.wal");
    }

    public CanonicalWorldState loadOrNull() throws IOException {
        if (!Files.isRegularFile(canonicalFile)) {
            return null;
        }
        try (DataInputStream in = new DataInputStream(Files.newInputStream(canonicalFile))) {
            int magic = in.readInt();
            if (magic != MAGIC) {
                throw new IOException("Bad canonical magic");
            }
            int schema = in.readInt();
            if (schema != LivingModsVersions.CANONICAL_SAVE_SCHEMA) {
                throw new IOException("Unsupported save schema: " + schema);
            }
            long seed = in.readLong();
            long ticks = in.readLong();
            long planHash = in.readLong();
            long revision = in.readLong();
            CanonicalWorldState state = new CanonicalWorldState(
                    seed,
                    com.livingmods.common.time.SimulationTime.ofTicks(ticks),
                    planHash
            );
            // Full entity graph restore is incremental — revision fingerprint only for now.
            while (state.saveRevision() < revision) {
                state.bumpSaveRevision();
            }
            return state;
        }
    }

    public void appendWal(long revision, long contentHash) throws IOException {
        Files.createDirectories(saveDir);
        try (DataOutputStream out = new DataOutputStream(Files.newOutputStream(walFile,
                Files.exists(walFile) ? java.nio.file.StandardOpenOption.APPEND : java.nio.file.StandardOpenOption.CREATE))) {
            out.writeLong(revision);
            out.writeLong(contentHash);
            out.writeLong(System.currentTimeMillis());
        }
    }

    public void saveBarrier(CanonicalWorldState state) throws IOException {
        Files.createDirectories(saveDir);
        Path temp = saveDir.resolve("canonical.bin.tmp");
        try (DataOutputStream out = new DataOutputStream(Files.newOutputStream(temp))) {
            out.writeInt(MAGIC);
            out.writeInt(LivingModsVersions.CANONICAL_SAVE_SCHEMA);
            out.writeLong(state.seed());
            out.writeLong(state.time().absoluteTicks());
            out.writeLong(state.planContentHash());
            out.writeLong(state.saveRevision() + 1);
            out.writeLong(state.contentHash());
        }
        Files.move(temp, canonicalFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        state.bumpSaveRevision();
        appendWal(state.saveRevision(), state.contentHash());
    }
}
