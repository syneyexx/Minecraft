package com.livingmods.common.id;

import java.util.Objects;
import java.util.UUID;

/**
 * Stable persistent identifier for a migration.
 */
public final class MigrationGroupId implements Comparable<MigrationGroupId> {
    private final UUID value;

    public MigrationGroupId(UUID value) {
        this.value = Objects.requireNonNull(value, "value");
    }

    public static MigrationGroupId of(UUID value) {
        return new MigrationGroupId(value);
    }

    public static MigrationGroupId fromString(String text) {
        return new MigrationGroupId(UUID.fromString(text));
    }

    /** Deterministic ID from seed domain + ordinal (worldgen-safe). */
    public static MigrationGroupId deterministic(long seed, long ordinal) {
        long msb = seed ^ (-3748794028590796902L * 0x9E3779B97F4A7C15L);
        long lsb = ordinal ^ 0xBF58476D1CE4E5B9L;
        return new MigrationGroupId(new UUID(msb, lsb));
    }

    public UUID value() {
        return value;
    }

    @Override
    public int compareTo(MigrationGroupId o) {
        return value.compareTo(o.value);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof MigrationGroupId other)) return false;
        return value.equals(other.value);
    }

    @Override
    public int hashCode() {
        return value.hashCode();
    }

    @Override
    public String toString() {
        return "migration:" + value;
    }
}
