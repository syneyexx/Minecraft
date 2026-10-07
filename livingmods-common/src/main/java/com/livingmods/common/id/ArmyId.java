package com.livingmods.common.id;

import java.util.Objects;
import java.util.UUID;

/**
 * Stable persistent identifier for a army.
 */
public final class ArmyId implements Comparable<ArmyId> {
    private final UUID value;

    public ArmyId(UUID value) {
        this.value = Objects.requireNonNull(value, "value");
    }

    public static ArmyId of(UUID value) {
        return new ArmyId(value);
    }

    public static ArmyId fromString(String text) {
        return new ArmyId(UUID.fromString(text));
    }

    /** Deterministic ID from seed domain + ordinal (worldgen-safe). */
    public static ArmyId deterministic(long seed, long ordinal) {
        long msb = seed ^ (-8652584817563191732L * 0x9E3779B97F4A7C15L);
        long lsb = ordinal ^ 0xBF58476D1CE4E5B9L;
        return new ArmyId(new UUID(msb, lsb));
    }

    public UUID value() {
        return value;
    }

    @Override
    public int compareTo(ArmyId o) {
        return value.compareTo(o.value);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ArmyId other)) return false;
        return value.equals(other.value);
    }

    @Override
    public int hashCode() {
        return value.hashCode();
    }

    @Override
    public String toString() {
        return "army:" + value;
    }
}
