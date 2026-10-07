package com.livingmods.simulation.state;

import com.livingmods.common.geo.BlockPos2;
import com.livingmods.common.id.ArmyId;
import com.livingmods.common.id.CitizenId;
import com.livingmods.common.id.KingdomId;
import com.livingmods.common.id.SettlementId;
import com.livingmods.common.id.WarId;
import com.livingmods.common.model.ArmyStatus;
import com.livingmods.common.model.WarObjective;

import java.util.ArrayList;
import java.util.List;

public final class ArmyState {
    private final ArmyId id;
    private final KingdomId owner;
    private CitizenId commanderId;
    private BlockPos2 position;
    private int manpower;
    private int infantry;
    private int cavalry;
    private int equipment;
    private int morale;
    private double supply;
    private final List<BlockPos2> route;
    private int routeIndex;
    private WarObjective objective;
    private SettlementId objectiveSettlement;
    private ArmyStatus status;
    private SettlementId siegeTarget;
    private int siegeProgress;
    private WarId warId;
    private boolean resolvedCombatThisTick;

    public ArmyState(ArmyId id, KingdomId owner, BlockPos2 position, int strength) {
        this.id = id;
        this.owner = owner;
        this.commanderId = null;
        this.position = position;
        this.manpower = Math.max(0, strength);
        this.infantry = Math.max(0, (int) (strength * 0.8));
        this.cavalry = Math.max(0, strength - infantry);
        this.equipment = 40;
        this.morale = 80;
        this.supply = 1.0;
        this.route = new ArrayList<>();
        this.routeIndex = 0;
        this.objective = WarObjective.DEFEND;
        this.objectiveSettlement = null;
        this.status = ArmyStatus.IDLE;
        this.siegeTarget = null;
        this.siegeProgress = 0;
        this.warId = null;
        this.resolvedCombatThisTick = false;
    }

    public ArmyId id() { return id; }
    public KingdomId owner() { return owner; }
    public CitizenId commanderId() { return commanderId; }
    public void setCommanderId(CitizenId commanderId) { this.commanderId = commanderId; }
    public BlockPos2 position() { return position; }
    public void setPosition(BlockPos2 position) { this.position = position; }

    /** Legacy alias for manpower. */
    public int strength() { return manpower; }
    public void setStrength(int strength) {
        this.manpower = Math.max(0, strength);
        this.infantry = Math.max(0, (int) (manpower * 0.8));
        this.cavalry = Math.max(0, manpower - infantry);
    }

    public int manpower() { return manpower; }
    public void setManpower(int manpower) { setStrength(manpower); }
    public int infantry() { return infantry; }
    public void setInfantry(int infantry) { this.infantry = Math.max(0, infantry); }
    public int cavalry() { return cavalry; }
    public void setCavalry(int cavalry) { this.cavalry = Math.max(0, cavalry); }
    public int equipment() { return equipment; }
    public void setEquipment(int equipment) { this.equipment = Math.max(0, Math.min(100, equipment)); }
    public int morale() { return morale; }
    public void setMorale(int morale) { this.morale = Math.max(0, Math.min(100, morale)); }
    public double supply() { return supply; }
    public void setSupply(double supply) { this.supply = Math.max(0, Math.min(1, supply)); }
    public List<BlockPos2> route() { return route; }
    public int routeIndex() { return routeIndex; }
    public void setRouteIndex(int routeIndex) { this.routeIndex = Math.max(0, routeIndex); }
    public WarObjective objective() { return objective; }
    public void setObjective(WarObjective objective) { this.objective = objective; }
    public SettlementId objectiveSettlement() { return objectiveSettlement; }
    public void setObjectiveSettlement(SettlementId objectiveSettlement) {
        this.objectiveSettlement = objectiveSettlement;
    }
    public ArmyStatus status() { return status; }
    public void setStatus(ArmyStatus status) { this.status = status; }
    public SettlementId siegeTarget() { return siegeTarget; }
    public void setSiegeTarget(SettlementId siegeTarget) { this.siegeTarget = siegeTarget; }
    public int siegeProgress() { return siegeProgress; }
    public void setSiegeProgress(int siegeProgress) { this.siegeProgress = Math.max(0, siegeProgress); }
    public WarId warId() { return warId; }
    public void setWarId(WarId warId) { this.warId = warId; }
    public boolean resolvedCombatThisTick() { return resolvedCombatThisTick; }
    public void setResolvedCombatThisTick(boolean resolvedCombatThisTick) {
        this.resolvedCombatThisTick = resolvedCombatThisTick;
    }
}
