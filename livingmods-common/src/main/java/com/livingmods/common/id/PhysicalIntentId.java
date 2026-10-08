package com.livingmods.common.id;

import java.util.Objects;
import java.util.UUID;

/** Stable persistent identifier for a dynamic physical intent. */
public final class PhysicalIntentId implements Comparable<PhysicalIntentId> {
    private final UUID value;

    public PhysicalIntentId(UUID value) {
        this.value = Objects.requireNonNull(value, "value");
    }

    public static PhysicalIntentId of(UUID value) {
        return new PhysicalIntentId(value);
    }

    public static PhysicalIntentId fromString(String text) {
        return new PhysicalIntentId(UUID.fromString(text));
    }

    public static PhysicalIntentId deterministic(long seed, long ordinal) {
        long msb = seed ^ (0x5048595349L * 0x9E3779B97F4A7C15L);
        long lsb = ordinal ^ 0xBF58476D1CE4E5B9L;
        return new PhysicalIntentId(new UUID(msb, lsb));
    }

    public UUID value() {
        return value;
    }

    @Override
    public int compareTo(PhysicalIntentId o) {
        return value.compareTo(o.value);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PhysicalIntentId other)) return false;
        return value.equals(other.value);
    }

    @Override
    public int hashCode() {
        return value.hashCode();
    }

    @Override
    public String toString() {
        return "intent:" + value;
    }
}
