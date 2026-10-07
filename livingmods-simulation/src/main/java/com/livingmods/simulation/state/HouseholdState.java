package com.livingmods.simulation.state;

import com.livingmods.common.id.HouseholdId;
import com.livingmods.common.id.SettlementId;

public final class HouseholdState {
    private final HouseholdId id;
    private final SettlementId settlementId;
    private int memberCount;
    private double foodStores;
    private int housingQuality;

    public HouseholdState(HouseholdId id, SettlementId settlementId, int memberCount, double foodStores, int housingQuality) {
        this.id = id;
        this.settlementId = settlementId;
        this.memberCount = memberCount;
        this.foodStores = foodStores;
        this.housingQuality = housingQuality;
    }

    public HouseholdId id() { return id; }
    public SettlementId settlementId() { return settlementId; }
    public int memberCount() { return memberCount; }
    public void setMemberCount(int memberCount) { this.memberCount = memberCount; }
    public double foodStores() { return foodStores; }
    public void setFoodStores(double foodStores) { this.foodStores = foodStores; }
    public int housingQuality() { return housingQuality; }
    public void setHousingQuality(int housingQuality) { this.housingQuality = housingQuality; }
}
