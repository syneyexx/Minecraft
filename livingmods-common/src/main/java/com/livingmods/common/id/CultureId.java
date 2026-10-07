package com.livingmods.common.id;

import java.util.Objects;
import java.util.UUID;

/**
 * Stable persistent identifier for a culture.
 */
public final class CultureId implements Comparable<CultureId> {
    private final UUID value;

    public CultureId(UUID value) {
        this.value = Objects.requireNonNull(value, "value");
    }

    public static CultureId of(UUID value) {
        return new CultureId(value);
    }

    public static CultureId fromString(String text) {
        return new CultureId(UUID.fromString(text));
    }

    /** Deterministic ID from seed domain + ordinal (worldgen-safe). */
    public static CultureId deterministic(long seed, long ordinal) {
        long msb = seed ^ (401146131320273558L * 0x9E3779B97F4A7C15L);
        long lsb = ordinal ^ 0xBF58476D1CE4E5B9L;
        return new CultureId(new UUID(msb, lsb));
    }

    public UUID value() {
        return value;
    }

    @Override
    public int compareTo(CultureId o) {
        return value.compareTo(o.value);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof CultureId other)) return false;
        return value.equals(other.value);
    }

    @Override
    public int hashCode() {
        return value.hashCode();
    }

    @Override
    public String toString() {
        return "culture:" + value;
    }
}
