package com.livingmods.common.model;

/**
 * Ownership provenance for physical blocks / footprints.
 * Unknown or protected content must never be destructively overwritten.
 */
public enum BlockProvenance {
    /** Original LivingMods worldgen / plan materialization. */
    LIVINGMODS_ORIGINAL,
    /** LivingMods dynamic reconciliation after initial generation. */
    LIVINGMODS_DYNAMIC,
    /** Vanilla / natural terrain generation. */
    VANILLA_NATURAL,
    /** Foreign mod-owned content. */
    FOREIGN_MOD,
    /** Player-modified or player-built content. */
    PLAYER_MODIFIED,
    /** Ownership cannot be established safely — treat as protected. */
    UNKNOWN_PROTECTED;

    public boolean allowsDestructiveReplace() {
        return this == LIVINGMODS_ORIGINAL
                || this == LIVINGMODS_DYNAMIC
                || this == VANILLA_NATURAL;
    }

    public boolean isProtected() {
        return this == FOREIGN_MOD
                || this == PLAYER_MODIFIED
                || this == UNKNOWN_PROTECTED;
    }
}
