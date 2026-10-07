package com.livingmods.common.id;

import java.util.Objects;
import java.util.UUID;

public final class DynastyId implements Comparable<DynastyId> {
    private final UUID value;

    public DynastyId(UUID value) {
        this.value = Objects.requireNonNull(value, "value");
    }

    public static DynastyId of(UUID value) {
        return new DynastyId(value);
    }

    public static DynastyId deterministic(long seed, long ordinal) {
        long msb = seed ^ (0xD1A57C0000000000L + ordinal);
        long lsb = ordinal ^ 0xC6A4A7935BD1E995L;
        return new DynastyId(new UUID(msb, lsb));
    }

    public UUID value() {
        return value;
    }

    @Override
    public int compareTo(DynastyId o) {
        return value.compareTo(o.value);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof DynastyId other)) return false;
        return value.equals(other.value);
    }

    @Override
    public int hashCode() {
        return value.hashCode();
    }

    @Override
    public String toString() {
        return "dynasty:" + value;
    }
}
