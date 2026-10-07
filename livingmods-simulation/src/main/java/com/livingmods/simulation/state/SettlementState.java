package com.livingmods.simulation.state;

import com.livingmods.common.geo.BlockPos2;
import com.livingmods.common.geo.RegionCoord;
import com.livingmods.common.id.KingdomId;
import com.livingmods.common.id.SettlementId;
import com.livingmods.common.model.SettlementRole;
import com.livingmods.common.model.SettlementTier;
import com.livingmods.common.id.CitizenId;

import java.util.Optional;

public final class SettlementState {
    private final SettlementId id;
    private final String name;
    private final SettlementTier tier;
    private final SettlementRole role;
    private final BlockPos2 center;
    private final Optional<KingdomId> ownerKingdom;
    private final boolean capital;
    private CitizenId rulerId;
    private double legitimacy;
    private double developmentDeficit;
    private double physicalCapacity;
    private int housingUnits;
    private int employedSlots;
    private double unrest;
    private double security;
    private double hunger;

    public SettlementState(
            SettlementId id,
            String name,
            SettlementTier tier,
            SettlementRole role,
            BlockPos2 center,
            Optional<KingdomId> ownerKingdom,
            boolean capital,
            CitizenId rulerId,
            double legitimacy,
            double developmentDeficit,
            double physicalCapacity,
            int housingUnits,
            int employedSlots
    ) {
        this.id = id;
        this.name = name;
        this.tier = tier;
        this.role = role;
        this.center = center;
        this.ownerKingdom = ownerKingdom;
        this.capital = capital;
        this.rulerId = rulerId;
        this.legitimacy = legitimacy;
        this.developmentDeficit = developmentDeficit;
        this.physicalCapacity = physicalCapacity;
        this.housingUnits = housingUnits;
        this.employedSlots = employedSlots;
        this.unrest = 0.0;
        this.security = 0.4;
        this.hunger = 0.0;
    }

    public SettlementId id() { return id; }
    public String name() { return name; }
    public SettlementTier tier() { return tier; }
    public SettlementRole role() { return role; }
    public BlockPos2 center() { return center; }
    public Optional<KingdomId> ownerKingdom() { return ownerKingdom; }
    public boolean capital() { return capital; }
    public CitizenId rulerId() { return rulerId; }
    public void setRulerId(CitizenId rulerId) { this.rulerId = rulerId; }
    public double legitimacy() { return legitimacy; }
    public void setLegitimacy(double legitimacy) { this.legitimacy = legitimacy; }
    public double developmentDeficit() { return developmentDeficit; }
    public void setDevelopmentDeficit(double developmentDeficit) { this.developmentDeficit = developmentDeficit; }
    public double physicalCapacity() { return physicalCapacity; }
    public void setPhysicalCapacity(double physicalCapacity) { this.physicalCapacity = physicalCapacity; }
    public int housingUnits() { return housingUnits; }
    public void setHousingUnits(int housingUnits) { this.housingUnits = housingUnits; }
    public int employedSlots() { return employedSlots; }
    public void setEmployedSlots(int employedSlots) { this.employedSlots = employedSlots; }
    public double unrest() { return unrest; }
    public void setUnrest(double unrest) { this.unrest = Math.max(0, Math.min(1, unrest)); }
    public double security() { return security; }
    public void setSecurity(double security) { this.security = Math.max(0, Math.min(1, security)); }
    public double hunger() { return hunger; }
    public void setHunger(double hunger) { this.hunger = Math.max(0, Math.min(1, hunger)); }

    public RegionCoord region() {
        int regionBlocks = RegionCoord.DEFAULT_SIZE_CHUNKS * 16;
        return RegionCoord.of(
                Math.floorDiv(center.x(), regionBlocks),
                Math.floorDiv(center.z(), regionBlocks)
        );
    }
}
