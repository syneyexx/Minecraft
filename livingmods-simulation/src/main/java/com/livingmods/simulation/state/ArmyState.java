package com.livingmods.simulation.state;

import com.livingmods.common.geo.BlockPos2;
import com.livingmods.common.id.ArmyId;
import com.livingmods.common.id.KingdomId;
import com.livingmods.common.id.SettlementId;

public final class ArmyState {
    private final ArmyId id;
    private final KingdomId owner;
    private BlockPos2 position;
    private int strength;
    private int morale;
    private double supply;
    private SettlementId siegeTarget;
    private int siegeProgress;

    public ArmyState(ArmyId id, KingdomId owner, BlockPos2 position, int strength) {
        this.id = id;
        this.owner = owner;
        this.position = position;
        this.strength = strength;
        this.morale = 80;
        this.supply = 1.0;
        this.siegeTarget = null;
        this.siegeProgress = 0;
    }

    public ArmyId id() { return id; }
    public KingdomId owner() { return owner; }
    public BlockPos2 position() { return position; }
    public void setPosition(BlockPos2 position) { this.position = position; }
    public int strength() { return strength; }
    public void setStrength(int strength) { this.strength = Math.max(0, strength); }
    public int morale() { return morale; }
    public void setMorale(int morale) { this.morale = morale; }
    public double supply() { return supply; }
    public void setSupply(double supply) { this.supply = supply; }
    public SettlementId siegeTarget() { return siegeTarget; }
    public void setSiegeTarget(SettlementId siegeTarget) { this.siegeTarget = siegeTarget; }
    public int siegeProgress() { return siegeProgress; }
    public void setSiegeProgress(int siegeProgress) { this.siegeProgress = siegeProgress; }
}
