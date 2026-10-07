package com.livingmods.common.id;

import java.util.Objects;
import java.util.UUID;

/**
 * Stable persistent identifier for a household.
 */
public final class HouseholdId implements Comparable<HouseholdId> {
    private final UUID value;

    public HouseholdId(UUID value) {
        this.value = Objects.requireNonNull(value, "value");
    }

    public static HouseholdId of(UUID value) {
        return new HouseholdId(value);
    }

    public static HouseholdId fromString(String text) {
        return new HouseholdId(UUID.fromString(text));
    }

    /** Deterministic ID from seed domain + ordinal (worldgen-safe). */
    public static HouseholdId deterministic(long seed, long ordinal) {
        long msb = seed ^ (-1316272255141885691L * 0x9E3779B97F4A7C15L);
        long lsb = ordinal ^ 0xBF58476D1CE4E5B9L;
        return new HouseholdId(new UUID(msb, lsb));
    }

    public UUID value() {
        return value;
    }

    @Override
    public int compareTo(HouseholdId o) {
        return value.compareTo(o.value);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof HouseholdId other)) return false;
        return value.equals(other.value);
    }

    @Override
    public int hashCode() {
        return value.hashCode();
    }

    @Override
    public String toString() {
        return "household:" + value;
    }
}
