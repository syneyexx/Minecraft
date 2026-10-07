package com.livingmods.worldgen.terrain;

import com.livingmods.common.geo.RegionCoord;

public record RegionTerrainSummary(
        RegionCoord region,
        double averageElevation,
        double averageSlope,
        double averageMoisture,
        double averageTemperature,
        double waterFraction,
        double buildableFraction,
        String dominantBiome
) {
    public double kingdomSuitability() {
        double score = buildableFraction * 2.0;
        score += (1.0 - averageSlope) * 0.8;
        score += (1.0 - Math.abs(waterFraction - 0.15)) * 0.5;
        score += (averageElevation > 62 && averageElevation < 100) ? 0.5 : 0.0;
        return score;
    }
}
