package com.livingmods.worldgen.terrain;

public record TerrainSample(
        int x,
        int z,
        double elevation,
        double slope,
        double moisture,
        double temperature,
        boolean water,
        boolean coastal,
        boolean river,
        String biomeHint
) {
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
}
