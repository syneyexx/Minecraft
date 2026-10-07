package com.livingmods.common.id;

import java.util.Objects;
import java.util.UUID;

/**
 * Stable persistent identifier for a district.
 */
public final class DistrictId implements Comparable<DistrictId> {
    private final UUID value;

    public DistrictId(UUID value) {
        this.value = Objects.requireNonNull(value, "value");
    }

    public static DistrictId of(UUID value) {
        return new DistrictId(value);
    }

    public static DistrictId fromString(String text) {
        return new DistrictId(UUID.fromString(text));
    }

    /** Deterministic ID from seed domain + ordinal (worldgen-safe). */
    public static DistrictId deterministic(long seed, long ordinal) {
        long msb = seed ^ (5038665150860089849L * 0x9E3779B97F4A7C15L);
        long lsb = ordinal ^ 0xBF58476D1CE4E5B9L;
        return new DistrictId(new UUID(msb, lsb));
    }

    public UUID value() {
        return value;
    }

    @Override
    public int compareTo(DistrictId o) {
        return value.compareTo(o.value);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof DistrictId other)) return false;
        return value.equals(other.value);
    }

    @Override
    public int hashCode() {
        return value.hashCode();
    }

    @Override
    public String toString() {
        return "district:" + value;
    }
}
