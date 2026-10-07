package com.livingmods.common.id;

import java.util.Objects;
import java.util.UUID;

/**
 * Stable persistent identifier for a building.
 */
public final class BuildingTemplateId implements Comparable<BuildingTemplateId> {
    private final UUID value;

    public BuildingTemplateId(UUID value) {
        this.value = Objects.requireNonNull(value, "value");
    }

    public static BuildingTemplateId of(UUID value) {
        return new BuildingTemplateId(value);
    }

    public static BuildingTemplateId fromString(String text) {
        return new BuildingTemplateId(UUID.fromString(text));
    }

    /** Deterministic ID from seed domain + ordinal (worldgen-safe). */
    public static BuildingTemplateId deterministic(long seed, long ordinal) {
        long msb = seed ^ (-4450571218725273841L * 0x9E3779B97F4A7C15L);
        long lsb = ordinal ^ 0xBF58476D1CE4E5B9L;
        return new BuildingTemplateId(new UUID(msb, lsb));
    }

    public UUID value() {
        return value;
    }

    @Override
    public int compareTo(BuildingTemplateId o) {
        return value.compareTo(o.value);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof BuildingTemplateId other)) return false;
        return value.equals(other.value);
    }

    @Override
    public int hashCode() {
        return value.hashCode();
    }

    @Override
    public String toString() {
        return "building:" + value;
    }
}
