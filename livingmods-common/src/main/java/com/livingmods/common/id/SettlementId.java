package com.livingmods.common.id;

import java.util.Objects;
import java.util.UUID;

/**
 * Stable persistent identifier for a settlement.
 */
public final class SettlementId implements Comparable<SettlementId> {
    private final UUID value;

    public SettlementId(UUID value) {
        this.value = Objects.requireNonNull(value, "value");
    }

    public static SettlementId of(UUID value) {
        return new SettlementId(value);
    }

    public static SettlementId fromString(String text) {
        return new SettlementId(UUID.fromString(text));
    }

    /** Deterministic ID from seed domain + ordinal (worldgen-safe). */
    public static SettlementId deterministic(long seed, long ordinal) {
        long msb = seed ^ (-7939677247436636035L * 0x9E3779B97F4A7C15L);
        long lsb = ordinal ^ 0xBF58476D1CE4E5B9L;
        return new SettlementId(new UUID(msb, lsb));
    }

    public UUID value() {
        return value;
    }

    @Override
    public int compareTo(SettlementId o) {
        return value.compareTo(o.value);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SettlementId other)) return false;
        return value.equals(other.value);
    }

    @Override
    public int hashCode() {
        return value.hashCode();
    }

    @Override
    public String toString() {
        return "settlement:" + value;
    }
}
