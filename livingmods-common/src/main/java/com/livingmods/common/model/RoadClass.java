package com.livingmods.common.model;

public enum RoadClass {
    /** Physical width target 5–7. */
    ROYAL_HIGHWAY(6, "polished_andesite"),
    /** Physical width target 4–5. */
    MAJOR(5, "stone_bricks"),
    /** Physical width target 3. */
    REGIONAL(3, "cobblestone"),
    /** Physical width target 2–3. */
    LOCAL(3, "gravel"),
    /** Physical width target 2–3. */
    VILLAGE(2, "dirt_path"),
    /** Physical width target 1–2. */
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
