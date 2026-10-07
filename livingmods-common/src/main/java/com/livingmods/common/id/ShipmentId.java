package com.livingmods.common.id;

import java.util.Objects;
import java.util.UUID;

/**
 * Stable persistent identifier for a shipment.
 */
public final class ShipmentId implements Comparable<ShipmentId> {
    private final UUID value;

    public ShipmentId(UUID value) {
        this.value = Objects.requireNonNull(value, "value");
    }

    public static ShipmentId of(UUID value) {
        return new ShipmentId(value);
    }

    public static ShipmentId fromString(String text) {
        return new ShipmentId(UUID.fromString(text));
    }

    /** Deterministic ID from seed domain + ordinal (worldgen-safe). */
    public static ShipmentId deterministic(long seed, long ordinal) {
        long msb = seed ^ (207533780748264412L * 0x9E3779B97F4A7C15L);
        long lsb = ordinal ^ 0xBF58476D1CE4E5B9L;
        return new ShipmentId(new UUID(msb, lsb));
    }

    public UUID value() {
        return value;
    }

    @Override
    public int compareTo(ShipmentId o) {
        return value.compareTo(o.value);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ShipmentId other)) return false;
        return value.equals(other.value);
    }

    @Override
    public int hashCode() {
        return value.hashCode();
    }

    @Override
    public String toString() {
        return "shipment:" + value;
    }
}
