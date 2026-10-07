package com.livingmods.common.id;

import java.util.Objects;
import java.util.UUID;

/**
 * Stable persistent identifier for a epidemic.
 */
public final class EpidemicId implements Comparable<EpidemicId> {
    private final UUID value;

    public EpidemicId(UUID value) {
        this.value = Objects.requireNonNull(value, "value");
    }

    public static EpidemicId of(UUID value) {
        return new EpidemicId(value);
    }

    public static EpidemicId fromString(String text) {
        return new EpidemicId(UUID.fromString(text));
    }

    /** Deterministic ID from seed domain + ordinal (worldgen-safe). */
    public static EpidemicId deterministic(long seed, long ordinal) {
        long msb = seed ^ (-6404675278833525046L * 0x9E3779B97F4A7C15L);
        long lsb = ordinal ^ 0xBF58476D1CE4E5B9L;
        return new EpidemicId(new UUID(msb, lsb));
    }

    public UUID value() {
        return value;
    }

    @Override
    public int compareTo(EpidemicId o) {
        return value.compareTo(o.value);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof EpidemicId other)) return false;
        return value.equals(other.value);
    }

    @Override
    public int hashCode() {
        return value.hashCode();
    }

    @Override
    public String toString() {
        return "epidemic:" + value;
    }
}
