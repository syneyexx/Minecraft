package com.livingmods.simulation.state;

import com.livingmods.common.id.ArmyId;
import com.livingmods.common.id.FactionId;
import com.livingmods.common.id.KingdomId;
import com.livingmods.common.id.SettlementId;

public final class FactionState {
    private final FactionId id;
    private final String name;
    private final SettlementId origin;
    private final KingdomId againstKingdom;
    private final String causeKey;
    private ArmyId armyId;
    private double strength;
    private boolean active;
    private long formedDay;

    public FactionState(
            FactionId id,
            String name,
            SettlementId origin,
            KingdomId againstKingdom,
            String causeKey,
            long formedDay
    ) {
        this.id = id;
        this.name = name;
        this.origin = origin;
        this.againstKingdom = againstKingdom;
        this.causeKey = causeKey;
        this.formedDay = formedDay;
        this.armyId = null;
        this.strength = 0.3;
        this.active = true;
    }

    public FactionId id() { return id; }
    public String name() { return name; }
    public SettlementId origin() { return origin; }
    public KingdomId againstKingdom() { return againstKingdom; }
    public String causeKey() { return causeKey; }
    public ArmyId armyId() { return armyId; }
    public void setArmyId(ArmyId armyId) { this.armyId = armyId; }
    public double strength() { return strength; }
    public void setStrength(double strength) { this.strength = Math.max(0, Math.min(1, strength)); }
    public boolean active() { return active; }
    public void setActive(boolean active) { this.active = active; }
    public long formedDay() { return formedDay; }
}
