package com.livingmods.common.model;

public enum SettlementTier {
    CAMP(20, 40, 8),
    HAMLET(60, 80, 12),
    VILLAGE(180, 140, 20),
    TOWN(600, 220, 36),
    CITY(2000, 360, 56),
    METROPOLIS(5000, 520, 72),
    CAPITAL(3500, 480, 80);

    private final int typicalPopulation;
    private final int influenceRadius;
    private final int footprintRadius;

    SettlementTier(int typicalPopulation, int influenceRadius, int footprintRadius) {
        this.typicalPopulation = typicalPopulation;
        this.influenceRadius = influenceRadius;
        this.footprintRadius = footprintRadius;
    }

    public int typicalPopulation() { return typicalPopulation; }
    public int influenceRadius() { return influenceRadius; }
    public int footprintRadius() { return footprintRadius; }

    public boolean isUrban() {
        return this == TOWN || this == CITY || this == METROPOLIS || this == CAPITAL;
    }

    public boolean isCapitalClass() {
        return this == CAPITAL || this == METROPOLIS;
    }
}
