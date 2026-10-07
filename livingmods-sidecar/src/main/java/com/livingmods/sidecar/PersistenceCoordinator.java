package com.livingmods.sidecar;

import com.livingmods.simulation.CanonicalWorldState;
import com.livingmods.simulation.persist.CanonicalStore;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

public final class PersistenceCoordinator {
    private static final Logger LOG = SidecarLogging.logger(PersistenceCoordinator.class);

    private final CanonicalStore store;
    private final AtomicBoolean saveInProgress = new AtomicBoolean(false);

    public PersistenceCoordinator(Path saveDir) {
        this.store = new CanonicalStore(saveDir);
    }

    public CanonicalWorldState loadOrNull() throws IOException {
        return store.loadOrNull();
    }

    public boolean beginSaveBarrier() {
        return saveInProgress.compareAndSet(false, true);
    }

    public void completeSaveBarrier(CanonicalWorldState state) throws IOException {
        try {
            store.saveBarrier(state);
            LOG.info("Canonical save barrier complete revision=" + state.saveRevision());
        } finally {
            saveInProgress.set(false);
        }
    }

    public void walAppend(CanonicalWorldState state) throws IOException {
        store.appendWal(state.saveRevision(), state.contentHash());
    }

    public boolean saveInProgress() {
        return saveInProgress.get();
    }
}
