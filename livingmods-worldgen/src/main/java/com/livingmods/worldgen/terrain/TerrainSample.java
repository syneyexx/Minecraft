package com.livingmods.worldgen.terrain;

/**
 * Point terrain snapshot used by planners.
 * elevation ≈ surface height; oceanFloor is solid ground under water.
 */
public record TerrainSample(
        int x,
        int z,
        double elevation,
        double oceanFloor,
        double slope,
        double roughness,
        double moisture,
        double temperature,
        boolean water,
        boolean coastal,
        boolean river,
        String biomeHint
) {
    public TerrainSample(
            int x, int z, double elevation, double slope, double moisture, double temperature,
            boolean water, boolean coastal, boolean river, String biomeHint
    ) {
        this(x, z, elevation, water ? Math.min(elevation, 60) : elevation, slope, slope,
                moisture, temperature, water, coastal, river, biomeHint);
    }

    public boolean buildable() {
        return !water && slope < 0.45 && elevation > 58 && elevation < 110;
    }

    public boolean goodFarmland() {
        return buildable() && moisture > 0.35 && temperature > 0.3 && slope < 0.25;
    }

    public boolean goodPort() {
        return coastal && !water && slope < 0.35;
    }

    public boolean defensive() {
        return !water && slope > 0.2 && elevation > 70;
    }

    public boolean navigableWater() {
        return water || river || coastal;
    }

    public boolean forested() {
        String b = biomeHint == null ? "" : biomeHint.toLowerCase();
        return b.contains("forest") || b.contains("taiga") || b.contains("jungle") || b.contains("grove");
    }

    public boolean mineralContext() {
        return elevation > 90 || slope > 0.28 || roughness > 0.35
                || (biomeHint != null && (biomeHint.contains("peaks") || biomeHint.contains("hills")
                || biomeHint.contains("badlands") || biomeHint.contains("stony")));
    }

    public double waterDepth() {
        return Math.max(0.0, elevation - oceanFloor);
    }

    public double buildableScore() {
        if (water) return 0;
        double score = 0;
        if (buildable()) score += 2.0;
        score += (1.0 - slope) * 1.5;
        score += (1.0 - roughness) * 0.5;
        if (elevation > 62 && elevation < 100) score += 0.5;
        if (river) score += 0.3;
        return score;
    }
}
