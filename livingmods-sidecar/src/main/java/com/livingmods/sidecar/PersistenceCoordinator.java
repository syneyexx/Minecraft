package com.livingmods.sidecar;

import com.livingmods.simulation.CanonicalWorldState;
import com.livingmods.simulation.SimulationEngine;
import com.livingmods.simulation.persistence.CanonicalStore;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

/**
 * Sidecar persistence façade over authoritative {@link CanonicalStore}.
 * Save barrier pauses the simulation engine (when bound) at a phase boundary.
 */
public final class PersistenceCoordinator {
    private static final Logger LOG = SidecarLogging.logger(PersistenceCoordinator.class);

    private final CanonicalStore store;
    private final AtomicBoolean saveReserved = new AtomicBoolean(false);

    public PersistenceCoordinator(Path saveDir) {
        this.store = new CanonicalStore(saveDir);
    }

    public void bindEngine(SimulationEngine engine) {
        store.bindEngine(engine);
    }

    public CanonicalWorldState loadOrNull() throws IOException {
        return store.loadOrNull();
    }

    /** Reserve exclusive save; actual freeze/revision occurs in {@link #completeSaveBarrier}. */
    public boolean beginSaveBarrier() {
        if (store.barrier().saveInProgress()) {
            return false;
        }
        return saveReserved.compareAndSet(false, true);
    }

    public void completeSaveBarrier(CanonicalWorldState state) throws IOException {
        try {
            store.saveBarrier(state);
            LOG.info("Canonical save barrier complete revision=" + state.saveRevision());
        } finally {
            saveReserved.set(false);
        }
    }

    public void walAppend(CanonicalWorldState state) throws IOException {
        store.appendWal(state.saveRevision(), state.contentHash());
    }

    public boolean saveInProgress() {
        return saveReserved.get() || store.barrier().saveInProgress();
    }

    public CanonicalStore store() {
        return store;
    }
}
