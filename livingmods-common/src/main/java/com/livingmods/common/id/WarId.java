package com.livingmods.common.id;

import java.util.Objects;
import java.util.UUID;

/**
 * Stable persistent identifier for a war.
 */
public final class WarId implements Comparable<WarId> {
    private final UUID value;

    public WarId(UUID value) {
        this.value = Objects.requireNonNull(value, "value");
    }

    public static WarId of(UUID value) {
        return new WarId(value);
    }

    public static WarId fromString(String text) {
        return new WarId(UUID.fromString(text));
    }

    /** Deterministic ID from seed domain + ordinal (worldgen-safe). */
    public static WarId deterministic(long seed, long ordinal) {
        long msb = seed ^ (9038771713034902711L * 0x9E3779B97F4A7C15L);
        long lsb = ordinal ^ 0xBF58476D1CE4E5B9L;
        return new WarId(new UUID(msb, lsb));
    }

    public UUID value() {
        return value;
    }

    @Override
    public int compareTo(WarId o) {
        return value.compareTo(o.value);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof WarId other)) return false;
        return value.equals(other.value);
    }

    @Override
    public int hashCode() {
        return value.hashCode();
    }

    @Override
    public String toString() {
        return "war:" + value;
    }
}
