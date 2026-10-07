package com.livingmods.simulation.persistence;

import com.livingmods.simulation.CanonicalWorldState;
import com.livingmods.simulation.SimulationEngine;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Coordinates save revision assignment and optional simulation pause so writers
 * observe a stable frozen snapshot at a phase boundary.
 * <p>
 * Revision protocol: freeze → assign next revision on state → serialize with that
 * revision → durable write → acknowledge. Never bump after writing a payload that
 * still claims the old revision.
 */
public final class SaveBarrier {
    private final ReentrantLock lock = new ReentrantLock();
    private final AtomicBoolean saveInProgress = new AtomicBoolean(false);
    private long lastCommittedRevision = -1;
    private SimulationEngine engine;

    public void bindEngine(SimulationEngine engine) {
        this.engine = engine;
    }

    /**
     * Pause simulation (if bound), lock the barrier, and assign {@code nextRevision}
     * on {@code state} before serialization.
     *
     * @return the next revision that must appear in the serialized payload
     */
    public long beginSave(CanonicalWorldState state) {
        if (!saveInProgress.compareAndSet(false, true)) {
            throw new IllegalStateException("save already in progress");
        }
        SimulationEngine eng = engine;
        if (eng != null) {
            eng.pauseForSave();
        }
        lock.lock();
        long nextRevision = state.saveRevision() + 1L;
        state.setSaveRevision(nextRevision);
        return nextRevision;
    }

    public void completeSave(CanonicalWorldState state, long revisionWritten) {
        try {
            if (state.saveRevision() != revisionWritten) {
                state.setSaveRevision(revisionWritten);
            }
            lastCommittedRevision = revisionWritten;
        } finally {
            unlockAndResume();
        }
    }

    public void abortSave() {
        unlockAndResume();
    }

    private void unlockAndResume() {
        try {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        } finally {
            saveInProgress.set(false);
            SimulationEngine eng = engine;
            if (eng != null) {
                eng.resumeAfterSave();
            }
        }
    }

    public boolean saveInProgress() {
        return saveInProgress.get();
    }

    public long lastCommittedRevision() {
        return lastCommittedRevision;
    }
}
