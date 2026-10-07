package com.livingmods.common.id;

import java.util.Objects;
import java.util.UUID;

/**
 * Stable persistent identifier for a kingdom.
 */
public final class KingdomId implements Comparable<KingdomId> {
    private final UUID value;

    public KingdomId(UUID value) {
        this.value = Objects.requireNonNull(value, "value");
    }

    public static KingdomId of(UUID value) {
        return new KingdomId(value);
    }

    public static KingdomId fromString(String text) {
        return new KingdomId(UUID.fromString(text));
    }

    /** Deterministic ID from seed domain + ordinal (worldgen-safe). */
    public static KingdomId deterministic(long seed, long ordinal) {
        long msb = seed ^ (2725503060004099860L * 0x9E3779B97F4A7C15L);
        long lsb = ordinal ^ 0xBF58476D1CE4E5B9L;
        return new KingdomId(new UUID(msb, lsb));
    }

    public UUID value() {
        return value;
    }

    @Override
    public int compareTo(KingdomId o) {
        return value.compareTo(o.value);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof KingdomId other)) return false;
        return value.equals(other.value);
    }

    @Override
    public int hashCode() {
        return value.hashCode();
    }

    @Override
    public String toString() {
        return "kingdom:" + value;
    }
}
