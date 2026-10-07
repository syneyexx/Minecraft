package com.livingmods.common.id;

import java.util.Objects;
import java.util.UUID;

/**
 * Stable persistent identifier for a road.
 */
public final class RoadId implements Comparable<RoadId> {
    private final UUID value;

    public RoadId(UUID value) {
        this.value = Objects.requireNonNull(value, "value");
    }

    public static RoadId of(UUID value) {
        return new RoadId(value);
    }

    public static RoadId fromString(String text) {
        return new RoadId(UUID.fromString(text));
    }

    /** Deterministic ID from seed domain + ordinal (worldgen-safe). */
    public static RoadId deterministic(long seed, long ordinal) {
        long msb = seed ^ (7847307535710039450L * 0x9E3779B97F4A7C15L);
        long lsb = ordinal ^ 0xBF58476D1CE4E5B9L;
        return new RoadId(new UUID(msb, lsb));
    }

    public UUID value() {
        return value;
    }

    @Override
    public int compareTo(RoadId o) {
        return value.compareTo(o.value);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof RoadId other)) return false;
        return value.equals(other.value);
    }

    @Override
    public int hashCode() {
        return value.hashCode();
    }

    @Override
    public String toString() {
        return "road:" + value;
    }
}
