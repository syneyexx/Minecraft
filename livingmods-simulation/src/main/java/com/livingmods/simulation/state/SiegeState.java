package com.livingmods.simulation.state;

import com.livingmods.common.id.ArmyId;
import com.livingmods.common.id.SettlementId;
import com.livingmods.common.id.SiegeId;
import com.livingmods.common.id.WarId;

import java.util.ArrayList;
import java.util.List;

public final class SiegeState {
    private final SiegeId id;
    private final SettlementId target;
    private final WarId warId;
    private final List<ArmyId> attackers;
    private final List<ArmyId> defenders;
    private double attackerSupplies;
    private double defenderSupplies;
    private boolean blockade;
    private double progress;
    private int breaches;
    private boolean surrendered;
    private boolean active;

    public SiegeState(SiegeId id, SettlementId target, WarId warId) {
        this.id = id;
        this.target = target;
        this.warId = warId;
        this.attackers = new ArrayList<>();
        this.defenders = new ArrayList<>();
        this.attackerSupplies = 1.0;
        this.defenderSupplies = 1.0;
        this.blockade = false;
        this.progress = 0.0;
        this.breaches = 0;
        this.surrendered = false;
        this.active = true;
    }

    public SiegeId id() { return id; }
    public SettlementId target() { return target; }
    public WarId warId() { return warId; }
    public List<ArmyId> attackers() { return attackers; }
    public List<ArmyId> defenders() { return defenders; }
    public double attackerSupplies() { return attackerSupplies; }
    public void setAttackerSupplies(double attackerSupplies) {
        this.attackerSupplies = Math.max(0, Math.min(1, attackerSupplies));
    }
    public double defenderSupplies() { return defenderSupplies; }
    public void setDefenderSupplies(double defenderSupplies) {
        this.defenderSupplies = Math.max(0, Math.min(1, defenderSupplies));
    }
    public boolean blockade() { return blockade; }
    public void setBlockade(boolean blockade) { this.blockade = blockade; }
    public double progress() { return progress; }
    public void setProgress(double progress) { this.progress = Math.max(0, Math.min(100, progress)); }
    public int breaches() { return breaches; }
    public void setBreaches(int breaches) { this.breaches = Math.max(0, breaches); }
    public boolean surrendered() { return surrendered; }
    public void setSurrendered(boolean surrendered) { this.surrendered = surrendered; }
    public boolean active() { return active; }
    public void setActive(boolean active) { this.active = active; }
}
