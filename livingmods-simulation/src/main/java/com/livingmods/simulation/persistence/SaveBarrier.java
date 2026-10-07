package com.livingmods.simulation.persistence;

import com.livingmods.simulation.CanonicalWorldState;

import java.util.concurrent.locks.ReentrantLock;

/** Coordinates save revision bumps so writers observe a stable revision boundary. */
public final class SaveBarrier {
    private final ReentrantLock lock = new ReentrantLock();
    private long lastCommittedRevision = -1;

    public long beginSave(CanonicalWorldState state) {
        lock.lock();
        return state.saveRevision();
    }

    public void completeSave(CanonicalWorldState state, long revisionAtStart) {
        if (state.saveRevision() == revisionAtStart) {
            state.bumpSaveRevision();
        }
        lastCommittedRevision = state.saveRevision();
        lock.unlock();
    }

    public void abortSave() {
        lock.unlock();
    }

    public long lastCommittedRevision() {
        return lastCommittedRevision;
    }
}
