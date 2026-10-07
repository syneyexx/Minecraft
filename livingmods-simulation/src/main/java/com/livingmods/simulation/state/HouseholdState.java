package com.livingmods.simulation.state;

import com.livingmods.common.id.CitizenId;
import com.livingmods.common.id.HouseholdId;
import com.livingmods.common.id.SettlementId;
import com.livingmods.common.id.StructureId;

public final class HouseholdState {
    public static final String KIND_FAMILY = "family";
    public static final String KIND_ORPHANAGE = "orphanage";

    private final HouseholdId id;
    private SettlementId settlementId;
    private int memberCount;
    private double foodStores;
    private int housingQuality;
    private StructureId homeStructureId;
    private CitizenId headId;
    private String institutionKind = KIND_FAMILY;

    public HouseholdState(HouseholdId id, SettlementId settlementId, int memberCount, double foodStores, int housingQuality) {
        this.id = id;
        this.settlementId = settlementId;
        this.memberCount = memberCount;
        this.foodStores = foodStores;
        this.housingQuality = housingQuality;
    }

    public HouseholdId id() { return id; }
    public SettlementId settlementId() { return settlementId; }
    public void setSettlementId(SettlementId settlementId) { this.settlementId = settlementId; }
    public int memberCount() { return memberCount; }
    public void setMemberCount(int memberCount) { this.memberCount = memberCount; }
    public double foodStores() { return foodStores; }
    public void setFoodStores(double foodStores) { this.foodStores = foodStores; }
    public int housingQuality() { return housingQuality; }
    public void setHousingQuality(int housingQuality) { this.housingQuality = housingQuality; }
    public StructureId homeStructureId() { return homeStructureId; }
    public void setHomeStructureId(StructureId homeStructureId) { this.homeStructureId = homeStructureId; }
    public CitizenId headId() { return headId; }
    public void setHeadId(CitizenId headId) { this.headId = headId; }
    public String institutionKind() { return institutionKind; }
    public void setInstitutionKind(String institutionKind) {
        this.institutionKind = institutionKind == null ? KIND_FAMILY : institutionKind;
    }
    public boolean orphanage() { return KIND_ORPHANAGE.equals(institutionKind); }
}
