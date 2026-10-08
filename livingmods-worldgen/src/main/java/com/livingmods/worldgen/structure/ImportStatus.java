package com.livingmods.worldgen.structure;

/** Lifecycle / validation status for structure assets in the import pipeline. */
public enum ImportStatus {
    DISCOVERED,
    FETCHED,
    VALIDATED,
    IMPORTED_EXACT,
    IMPORTED_SANITIZED,
    AUTHORED,
    DUPLICATE,
    EXACT_DUPLICATE,
    ROTATED_DUPLICATE,
    MIRRORED_DUPLICATE,
    NEAR_DUPLICATE,
    INVALID_SOURCE,
    UNSUPPORTED_BLOCKS,
    WRONG_CATEGORY,
    SOURCE_UNAVAILABLE,
    QUARANTINED
}
