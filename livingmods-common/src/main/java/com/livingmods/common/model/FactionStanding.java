package com.livingmods.common.model;

/** Player membership / trust ladder within a kingdom or settlement faction. */
public enum FactionStanding {
    NEUTRAL,
    CITIZEN,
    TRUSTED,
    OFFICIAL,
    RULER;

    public boolean atLeast(FactionStanding other) {
        return ordinal() >= other.ordinal();
    }
}
