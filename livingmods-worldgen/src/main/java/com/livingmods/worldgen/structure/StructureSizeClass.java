package com.livingmods.worldgen.structure;

/** Footprint size class used during planning selection. */
public enum StructureSizeClass {
    TINY,
    SMALL,
    MEDIUM,
    LARGE,
    MONUMENTAL;

    public static StructureSizeClass fromDimensions(int width, int depth) {
        int max = Math.max(width, depth);
        int min = Math.min(width, depth);
        int score = max + min / 2;
        if (score <= 10) return TINY;
        if (score <= 18) return SMALL;
        if (score <= 32) return MEDIUM;
        if (score <= 48) return LARGE;
        return MONUMENTAL;
    }
}
