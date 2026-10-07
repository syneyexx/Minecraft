package com.livingmods.common.id;

import java.util.Objects;
import java.util.UUID;

/** Stable persistent identifier for a siege. */
public final class SiegeId implements Comparable<SiegeId> {
    private final UUID value;

    public SiegeId(UUID value) {
        this.value = Objects.requireNonNull(value, "value");
    }

    public static SiegeId of(UUID value) {
        return new SiegeId(value);
    }

    public static SiegeId fromString(String text) {
        return new SiegeId(UUID.fromString(text));
    }

    public static SiegeId deterministic(long seed, long ordinal) {
        long msb = seed ^ (6172938450172938461L * 0x9E3779B97F4A7C15L);
        long lsb = ordinal ^ 0xBF58476D1CE4E5B9L;
        return new SiegeId(new UUID(msb, lsb));
    }

    public UUID value() {
        return value;
    }

    @Override
    public int compareTo(SiegeId o) {
        return value.compareTo(o.value);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SiegeId other)) return false;
        return value.equals(other.value);
    }

    @Override
    public int hashCode() {
        return value.hashCode();
    }

    @Override
    public String toString() {
        return "siege:" + value;
    }
}
