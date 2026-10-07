package com.livingmods.simulation.persist;

import com.livingmods.simulation.CanonicalWorldState;
import com.livingmods.simulation.SimulationEngine;

import java.io.IOException;
import java.nio.file.Path;
import java.util.UUID;

/**
 * @deprecated Use {@link com.livingmods.simulation.persistence.CanonicalStore}.
 * Thin delegate kept for transitional sidecar imports.
 */
@Deprecated
public final class CanonicalStore {
    private final com.livingmods.simulation.persistence.CanonicalStore delegate;

    public CanonicalStore(Path saveDir) {
        this.delegate = new com.livingmods.simulation.persistence.CanonicalStore(saveDir);
    }

    public com.livingmods.simulation.persistence.CanonicalStore delegate() {
        return delegate;
    }

    public void bindEngine(SimulationEngine engine) {
        delegate.bindEngine(engine);
    }

    public CanonicalWorldState loadOrNull() throws IOException {
        return delegate.loadOrNull();
    }

    public void appendWal(long revision, long contentHash) throws IOException {
        delegate.appendWal(revision, contentHash);
    }

    public void saveBarrier(CanonicalWorldState state) throws IOException {
        delegate.saveBarrier(state);
    }

    public void write(CanonicalWorldState state, UUID worldSessionId) throws IOException {
        delegate.write(state, worldSessionId);
    }
}
