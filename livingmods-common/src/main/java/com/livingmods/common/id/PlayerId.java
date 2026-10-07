package com.livingmods.common.id;

import java.util.Objects;
import java.util.UUID;

/**
 * Stable persistent identifier for a player.
 */
public final class PlayerId implements Comparable<PlayerId> {
    private final UUID value;

    public PlayerId(UUID value) {
        this.value = Objects.requireNonNull(value, "value");
    }

    public static PlayerId of(UUID value) {
        return new PlayerId(value);
    }

    public static PlayerId fromString(String text) {
        return new PlayerId(UUID.fromString(text));
    }

    /** Deterministic ID from seed domain + ordinal (worldgen-safe). */
    public static PlayerId deterministic(long seed, long ordinal) {
        long msb = seed ^ (-2945139338107168300L * 0x9E3779B97F4A7C15L);
        long lsb = ordinal ^ 0xBF58476D1CE4E5B9L;
        return new PlayerId(new UUID(msb, lsb));
    }

    public UUID value() {
        return value;
    }

    @Override
    public int compareTo(PlayerId o) {
        return value.compareTo(o.value);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PlayerId other)) return false;
        return value.equals(other.value);
    }

    @Override
    public int hashCode() {
        return value.hashCode();
    }

    @Override
    public String toString() {
        return "player:" + value;
    }
}
