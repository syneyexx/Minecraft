package com.livingmods.common.id;

import java.util.Objects;
import java.util.UUID;

/**
 * Stable persistent identifier for a faction.
 */
public final class FactionId implements Comparable<FactionId> {
    private final UUID value;

    public FactionId(UUID value) {
        this.value = Objects.requireNonNull(value, "value");
    }

    public static FactionId of(UUID value) {
        return new FactionId(value);
    }

    public static FactionId fromString(String text) {
        return new FactionId(UUID.fromString(text));
    }

    /** Deterministic ID from seed domain + ordinal (worldgen-safe). */
    public static FactionId deterministic(long seed, long ordinal) {
        long msb = seed ^ (1751508067019856503L * 0x9E3779B97F4A7C15L);
        long lsb = ordinal ^ 0xBF58476D1CE4E5B9L;
        return new FactionId(new UUID(msb, lsb));
    }

    public UUID value() {
        return value;
    }

    @Override
    public int compareTo(FactionId o) {
        return value.compareTo(o.value);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof FactionId other)) return false;
        return value.equals(other.value);
    }

    @Override
    public int hashCode() {
        return value.hashCode();
    }

    @Override
    public String toString() {
        return "faction:" + value;
    }
}
