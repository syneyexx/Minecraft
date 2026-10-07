package com.livingmods.common.id;

import java.util.Objects;
import java.util.UUID;

/**
 * Stable persistent identifier for a history.
 */
public final class HistoricalEventId implements Comparable<HistoricalEventId> {
    private final UUID value;

    public HistoricalEventId(UUID value) {
        this.value = Objects.requireNonNull(value, "value");
    }

    public static HistoricalEventId of(UUID value) {
        return new HistoricalEventId(value);
    }

    public static HistoricalEventId fromString(String text) {
        return new HistoricalEventId(UUID.fromString(text));
    }

    /** Deterministic ID from seed domain + ordinal (worldgen-safe). */
    public static HistoricalEventId deterministic(long seed, long ordinal) {
        long msb = seed ^ (-5568276467446288573L * 0x9E3779B97F4A7C15L);
        long lsb = ordinal ^ 0xBF58476D1CE4E5B9L;
        return new HistoricalEventId(new UUID(msb, lsb));
    }

    public UUID value() {
        return value;
    }

    @Override
    public int compareTo(HistoricalEventId o) {
        return value.compareTo(o.value);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof HistoricalEventId other)) return false;
        return value.equals(other.value);
    }

    @Override
    public int hashCode() {
        return value.hashCode();
    }

    @Override
    public String toString() {
        return "history:" + value;
    }
}
