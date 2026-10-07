package com.livingmods.common.id;

import java.util.Objects;
import java.util.UUID;

/** Stable persistent identifier for a crime case. */
public final class CrimeId implements Comparable<CrimeId> {
    private final UUID value;

    public CrimeId(UUID value) {
        this.value = Objects.requireNonNull(value, "value");
    }

    public static CrimeId of(UUID value) {
        return new CrimeId(value);
    }

    public static CrimeId fromString(String text) {
        return new CrimeId(UUID.fromString(text));
    }

    public static CrimeId deterministic(long seed, long ordinal) {
        long msb = seed ^ (9138475620193847562L * 0x9E3779B97F4A7C15L);
        long lsb = ordinal ^ 0xBF58476D1CE4E5B9L;
        return new CrimeId(new UUID(msb, lsb));
    }

    public UUID value() {
        return value;
    }

    @Override
    public int compareTo(CrimeId o) {
        return value.compareTo(o.value);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof CrimeId other)) return false;
        return value.equals(other.value);
    }

    @Override
    public int hashCode() {
        return value.hashCode();
    }

    @Override
    public String toString() {
        return "crime:" + value;
    }
}
