package com.livingmods.common.model;

public enum RoadClass {
    ROYAL_HIGHWAY(5, "polished_andesite"),
    MAJOR(4, "stone_bricks"),
    REGIONAL(3, "cobblestone"),
    LOCAL(2, "gravel"),
    VILLAGE(2, "dirt_path"),
    TRAIL(1, "dirt");

    private final int width;
    private final String defaultMaterial;

    RoadClass(int width, String defaultMaterial) {
        this.width = width;
        this.defaultMaterial = defaultMaterial;
    }

    public int width() { return width; }
    public String defaultMaterial() { return defaultMaterial; }
}
