package com.livingmods.simulation.persist;

import com.livingmods.simulation.CanonicalWorldState;
import com.livingmods.simulation.persistence.CanonicalSaveFormat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

/**
 * Canonical save + write-ahead log for sidecar persistence.
 * Delegates full graph encode/decode to CanonicalSaveFormat (schema v1).
 */
public final class CanonicalStore {
    private final Path saveDir;
    private final Path canonicalFile;
    private final Path walFile;
    private final Path journalFile;

    public CanonicalStore(Path saveDir) {
        this.saveDir = saveDir;
        this.canonicalFile = saveDir.resolve("canonical.bin");
        this.walFile = saveDir.resolve("canonical.wal");
        this.journalFile = saveDir.resolve("canonical.journal");
    }

    public CanonicalWorldState loadOrNull() throws IOException {
        recoverJournalIfNeeded();
        if (!Files.isRegularFile(canonicalFile)) {
            return null;
        }
        byte[] bytes = Files.readAllBytes(canonicalFile);
        return CanonicalSaveFormat.readFullState(bytes);
    }

    public void appendWal(long revision, long contentHash) throws IOException {
        Files.createDirectories(saveDir);
        try (var out = new java.io.DataOutputStream(Files.newOutputStream(walFile,
                Files.exists(walFile)
                        ? java.nio.file.StandardOpenOption.APPEND
                        : java.nio.file.StandardOpenOption.CREATE))) {
            out.writeLong(revision);
            out.writeLong(contentHash);
            out.writeLong(System.currentTimeMillis());
        }
    }

    public void saveBarrier(CanonicalWorldState state) throws IOException {
        Files.createDirectories(saveDir);
        UUID session = new UUID(state.seed(), state.planContentHash());
        byte[] payload = CanonicalSaveFormat.writeSnapshot(state, session);

        // WAL/journal: write journal first, then promote to canonical.
        Files.write(journalFile, payload);
        Path temp = saveDir.resolve("canonical.bin.tmp");
        Files.write(temp, payload);
        Files.move(temp, canonicalFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        Files.deleteIfExists(journalFile);

        state.bumpSaveRevision();
        appendWal(state.saveRevision(), state.contentHash());
    }

    private void recoverJournalIfNeeded() throws IOException {
        if (!Files.isRegularFile(journalFile)) {
            return;
        }
        // Incomplete transaction: promote journal if canonical missing/older.
        byte[] journal = Files.readAllBytes(journalFile);
        CanonicalSaveFormat.readSnapshot(journal); // validate
        if (!Files.isRegularFile(canonicalFile)) {
            Files.write(canonicalFile, journal);
        }
        Files.deleteIfExists(journalFile);
    }
}
