package com.livingmods.simulation.persistence;

import com.livingmods.simulation.CanonicalWorldState;
import com.livingmods.simulation.InitialStateFactory;
import com.livingmods.worldgen.plan.WorldPlan;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/**
 * Write/read canonical snapshots with WAL journal for crash recovery.
 */
public final class CanonicalStore {
    private final Path directory;
    private final SaveBarrier barrier = new SaveBarrier();

    public CanonicalStore(Path directory) {
        this.directory = directory;
    }

    public Path snapshotPath() { return directory.resolve("canonical.snapshot"); }
    public Path journalPath() { return directory.resolve("canonical.journal"); }

    public void write(CanonicalWorldState state, UUID worldSessionId) throws IOException {
        long revisionAtStart = barrier.beginSave(state);
        try {
            Files.createDirectories(directory);
            byte[] payload = CanonicalSaveFormat.writeSnapshot(state, worldSessionId);
            Files.write(journalPath(), payload);
            Files.write(snapshotPath(), payload);
            barrier.completeSave(state, revisionAtStart);
            Files.deleteIfExists(journalPath());
        } catch (IOException e) {
            barrier.abortSave();
            throw e;
        }
    }

    public CanonicalSaveFormat.Snapshot readMetadata() throws IOException {
        recoverIfNeeded();
        if (!Files.exists(snapshotPath())) {
            throw new IOException("no snapshot");
        }
        return CanonicalSaveFormat.readSnapshot(Files.readAllBytes(snapshotPath()));
    }

    public CanonicalWorldState load(WorldPlan plan) throws IOException {
        recoverIfNeeded();
        if (!Files.exists(snapshotPath())) {
            return InitialStateFactory.fromWorldPlan(plan);
        }
        CanonicalSaveFormat.Snapshot meta = CanonicalSaveFormat.readSnapshot(Files.readAllBytes(snapshotPath()));
        CanonicalWorldState state = InitialStateFactory.fromWorldPlan(plan, com.livingmods.common.time.SimulationTime.ofTicks(meta.timeTicks()));
        for (long i = 0; i < meta.saveRevision(); i++) {
            state.bumpSaveRevision();
        }
        return state;
    }

    private void recoverIfNeeded() throws IOException {
        if (Files.exists(journalPath())) {
            byte[] journal = Files.readAllBytes(journalPath());
            if (journal.length > 0) {
                Files.write(snapshotPath(), journal);
            }
            Files.deleteIfExists(journalPath());
        }
    }

    public SaveBarrier barrier() { return barrier; }
}
