package com.livingmods.common.id;

import java.util.Objects;
import java.util.UUID;

/**
 * Stable persistent identifier for a citizen.
 */
public final class CitizenId implements Comparable<CitizenId> {
    private final UUID value;

    public CitizenId(UUID value) {
        this.value = Objects.requireNonNull(value, "value");
    }

    public static CitizenId of(UUID value) {
        return new CitizenId(value);
    }

    public static CitizenId fromString(String text) {
        return new CitizenId(UUID.fromString(text));
    }

    /** Deterministic ID from seed domain + ordinal (worldgen-safe). */
    public static CitizenId deterministic(long seed, long ordinal) {
        long msb = seed ^ (3327873165085911133L * 0x9E3779B97F4A7C15L);
        long lsb = ordinal ^ 0xBF58476D1CE4E5B9L;
        return new CitizenId(new UUID(msb, lsb));
    }

    public UUID value() {
        return value;
    }

    @Override
    public int compareTo(CitizenId o) {
        return value.compareTo(o.value);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof CitizenId other)) return false;
        return value.equals(other.value);
    }

    @Override
    public int hashCode() {
        return value.hashCode();
    }

    @Override
    public String toString() {
        return "citizen:" + value;
    }
}
