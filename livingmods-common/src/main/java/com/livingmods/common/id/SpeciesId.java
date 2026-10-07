package com.livingmods.common.id;

import java.util.Objects;
import java.util.UUID;

/**
 * Stable persistent identifier for a species.
 */
public final class SpeciesId implements Comparable<SpeciesId> {
    private final UUID value;

    public SpeciesId(UUID value) {
        this.value = Objects.requireNonNull(value, "value");
    }

    public static SpeciesId of(UUID value) {
        return new SpeciesId(value);
    }

    public static SpeciesId fromString(String text) {
        return new SpeciesId(UUID.fromString(text));
    }

    /** Deterministic ID from seed domain + ordinal (worldgen-safe). */
    public static SpeciesId deterministic(long seed, long ordinal) {
        long msb = seed ^ (-8012826782542885571L * 0x9E3779B97F4A7C15L);
        long lsb = ordinal ^ 0xBF58476D1CE4E5B9L;
        return new SpeciesId(new UUID(msb, lsb));
    }

    public UUID value() {
        return value;
    }

    @Override
    public int compareTo(SpeciesId o) {
        return value.compareTo(o.value);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SpeciesId other)) return false;
        return value.equals(other.value);
    }

    @Override
    public int hashCode() {
        return value.hashCode();
    }

    @Override
    public String toString() {
        return "species:" + value;
    }
}
