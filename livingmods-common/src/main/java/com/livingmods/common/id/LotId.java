package com.livingmods.common.id;

import java.util.Objects;
import java.util.UUID;

/**
 * Stable persistent identifier for a lot.
 */
public final class LotId implements Comparable<LotId> {
    private final UUID value;

    public LotId(UUID value) {
        this.value = Objects.requireNonNull(value, "value");
    }

    public static LotId of(UUID value) {
        return new LotId(value);
    }

    public static LotId fromString(String text) {
        return new LotId(UUID.fromString(text));
    }

    /** Deterministic ID from seed domain + ordinal (worldgen-safe). */
    public static LotId deterministic(long seed, long ordinal) {
        long msb = seed ^ (4303816494607997832L * 0x9E3779B97F4A7C15L);
        long lsb = ordinal ^ 0xBF58476D1CE4E5B9L;
        return new LotId(new UUID(msb, lsb));
    }

    public UUID value() {
        return value;
    }

    @Override
    public int compareTo(LotId o) {
        return value.compareTo(o.value);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof LotId other)) return false;
        return value.equals(other.value);
    }

    @Override
    public int hashCode() {
        return value.hashCode();
    }

    @Override
    public String toString() {
        return "lot:" + value;
    }
}
