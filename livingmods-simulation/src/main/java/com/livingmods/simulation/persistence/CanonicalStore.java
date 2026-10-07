package com.livingmods.simulation.persistence;

import com.livingmods.simulation.CanonicalWorldState;
import com.livingmods.simulation.InitialStateFactory;
import com.livingmods.simulation.SimulationEngine;
import com.livingmods.worldgen.plan.WorldPlan;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.UUID;

/**
 * Authoritative canonical snapshot store with atomic promotion and revision-correct writes.
 * <p>
 * Layout: {@code canonical.bin}, {@code canonical.bin.prev} backup, {@code canonical.journal} temp.
 */
public final class CanonicalStore {
    private final Path directory;
    private final SaveBarrier barrier = new SaveBarrier();

    public CanonicalStore(Path directory) {
        this.directory = directory;
    }

    public Path directory() { return directory; }
    public Path snapshotPath() { return directory.resolve("canonical.bin"); }
    public Path previousPath() { return directory.resolve("canonical.bin.prev"); }
    public Path journalPath() { return directory.resolve("canonical.journal"); }
    public Path walPath() { return directory.resolve("canonical.wal"); }
    /** @deprecated Prefer {@link #snapshotPath()}. */
    @Deprecated
    public Path legacySnapshotPath() { return directory.resolve("canonical.snapshot"); }

    public SaveBarrier barrier() { return barrier; }

    public void bindEngine(SimulationEngine engine) {
        barrier.bindEngine(engine);
    }

    /**
     * Freeze snapshot, assign next revision, serialize WITH next revision, durable write,
     * atomic promote, acknowledge. Does not bump after writing an old-revision payload.
     */
    public void write(CanonicalWorldState state, UUID worldSessionId) throws IOException {
        long nextRevision = barrier.beginSave(state);
        try {
            Files.createDirectories(directory);
            UUID session = worldSessionId != null ? worldSessionId : state.worldId();
            if (worldSessionId != null) {
                state.setWorldId(worldSessionId);
            }
            byte[] payload = CanonicalSaveFormat.writeSnapshot(state, session);
            CanonicalSaveFormat.SnapshotMeta meta = CanonicalSaveFormat.readSnapshot(payload);
            if (meta.saveRevision() != nextRevision) {
                throw new IOException("serialized revision mismatch: got "
                        + meta.saveRevision() + " expected " + nextRevision);
            }

            Path journal = journalPath();
            writeFully(journal, payload);

            Path temp = directory.resolve("canonical.bin.tmp");
            writeFully(temp, payload);

            Path canonical = snapshotPath();
            if (Files.isRegularFile(canonical)) {
                Files.copy(canonical, previousPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            Files.move(temp, canonical, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            Files.deleteIfExists(journal);

            barrier.completeSave(state, nextRevision);
            appendWal(nextRevision, state.contentHash());
        } catch (IOException | RuntimeException e) {
            barrier.abortSave();
            throw e instanceof IOException io ? io : new IOException(e);
        }
    }

    /** Sidecar-compatible entry point: same atomic revision-correct save. */
    public void saveBarrier(CanonicalWorldState state) throws IOException {
        write(state, state.worldId());
    }

    public CanonicalWorldState loadOrNull() throws IOException {
        recoverIfNeeded();
        Path path = resolveExistingSnapshot();
        if (path == null) {
            return null;
        }
        return CanonicalSaveFormat.readFullState(Files.readAllBytes(path));
    }

    public CanonicalSaveFormat.SnapshotMeta readMetadata() throws IOException {
        recoverIfNeeded();
        Path path = resolveExistingSnapshot();
        if (path == null) {
            throw new IOException("no snapshot");
        }
        return CanonicalSaveFormat.readSnapshot(Files.readAllBytes(path));
    }

    public CanonicalWorldState load(WorldPlan plan) throws IOException {
        CanonicalWorldState loaded = loadOrNull();
        if (loaded == null) {
            return InitialStateFactory.fromWorldPlan(plan);
        }
        return loaded;
    }

    public void appendWal(long revision, long contentHash) throws IOException {
        Files.createDirectories(directory);
        try (var out = new java.io.DataOutputStream(Files.newOutputStream(
                walPath(),
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND,
                StandardOpenOption.WRITE))) {
            out.writeLong(revision);
            out.writeLong(contentHash);
            out.writeLong(System.currentTimeMillis());
        }
    }

    private Path resolveExistingSnapshot() {
        if (Files.isRegularFile(snapshotPath())) {
            return snapshotPath();
        }
        if (Files.isRegularFile(legacySnapshotPath())) {
            return legacySnapshotPath();
        }
        if (Files.isRegularFile(previousPath())) {
            return previousPath();
        }
        return null;
    }

    private void recoverIfNeeded() throws IOException {
        Path journal = journalPath();
        if (!Files.isRegularFile(journal)) {
            return;
        }
        byte[] bytes = Files.readAllBytes(journal);
        if (bytes.length == 0) {
            Files.deleteIfExists(journal);
            return;
        }
        CanonicalSaveFormat.readSnapshot(bytes); // validate header
        Path canonical = snapshotPath();
        if (!Files.isRegularFile(canonical)) {
            writeFully(canonical, bytes);
        }
        Files.deleteIfExists(journal);
    }

    private static void writeFully(Path path, byte[] payload) throws IOException {
        Files.write(path, payload, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.WRITE)) {
            channel.force(true);
        }
    }
}
