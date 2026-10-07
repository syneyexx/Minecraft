package com.livingmods.simulation.state;

import com.livingmods.common.id.KingdomId;
import com.livingmods.common.id.SettlementId;
import com.livingmods.common.id.WarId;
import com.livingmods.common.model.CasusBelli;
import com.livingmods.common.model.WarObjective;

import java.util.HashSet;
import java.util.Set;

public final class WarState {
    private final WarId id;
    private final KingdomId aggressor;
    private final KingdomId defender;
    private final long startedDay;
    private boolean active;
    private final Set<KingdomId> participants;
    private CasusBelli casusBelli;
    private WarObjective primaryObjective;
    private SettlementId objectiveSettlement;
    private double warExhaustionAggressor;
    private double warExhaustionDefender;

    public WarState(WarId id, KingdomId aggressor, KingdomId defender, long startedDay) {
        this.id = id;
        this.aggressor = aggressor;
        this.defender = defender;
        this.startedDay = startedDay;
        this.active = true;
        this.participants = new HashSet<>();
        participants.add(aggressor);
        participants.add(defender);
        this.casusBelli = CasusBelli.BORDER_DISPUTE;
        this.primaryObjective = WarObjective.HOLD_BORDER;
        this.objectiveSettlement = null;
        this.warExhaustionAggressor = 0.0;
        this.warExhaustionDefender = 0.0;
    }

    public WarId id() { return id; }
    public KingdomId aggressor() { return aggressor; }
    public KingdomId defender() { return defender; }
    public long startedDay() { return startedDay; }
    public boolean active() { return active; }
    public void setActive(boolean active) { this.active = active; }
    public Set<KingdomId> participants() { return participants; }
    public CasusBelli casusBelli() { return casusBelli; }
    public void setCasusBelli(CasusBelli casusBelli) { this.casusBelli = casusBelli; }
    public WarObjective primaryObjective() { return primaryObjective; }
    public void setPrimaryObjective(WarObjective primaryObjective) { this.primaryObjective = primaryObjective; }
    public SettlementId objectiveSettlement() { return objectiveSettlement; }
    public void setObjectiveSettlement(SettlementId objectiveSettlement) {
        this.objectiveSettlement = objectiveSettlement;
    }
    public double warExhaustionAggressor() { return warExhaustionAggressor; }
    public void setWarExhaustionAggressor(double v) {
        this.warExhaustionAggressor = Math.max(0, Math.min(1, v));
    }
    public double warExhaustionDefender() { return warExhaustionDefender; }
    public void setWarExhaustionDefender(double v) {
        this.warExhaustionDefender = Math.max(0, Math.min(1, v));
    }
}
