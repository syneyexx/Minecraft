package com.livingmods.common.model;

/** Lifecycle for tracked physical structures after initial worldgen. */
public enum StructureIntegrityStatus {
    ACTIVE,
    DAMAGED,
    DESTROYED,
    RUIN,
    CLEARED;

    public boolean contributesCapacity() {
        return this == ACTIVE || this == DAMAGED;
    }
}
