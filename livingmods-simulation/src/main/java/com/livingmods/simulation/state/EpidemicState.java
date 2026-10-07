package com.livingmods.simulation.state;

import com.livingmods.common.id.EpidemicId;
import com.livingmods.common.id.SettlementId;

import java.util.HashSet;
import java.util.Set;

public final class EpidemicState {
    private final EpidemicId id;
    private final String pathogenKey;
    private final SettlementId origin;
    private final long startedDay;
    private double transmissionRate;
    private double mortalityRate;
    private boolean active;
    private final Set<SettlementId> affectedSettlements;

    public EpidemicState(EpidemicId id, String pathogenKey, SettlementId origin, long startedDay) {
        this.id = id;
        this.pathogenKey = pathogenKey;
        this.origin = origin;
        this.startedDay = startedDay;
        this.transmissionRate = 0.15;
        this.mortalityRate = 0.02;
        this.active = true;
        this.affectedSettlements = new HashSet<>();
        affectedSettlements.add(origin);
    }

    public EpidemicId id() { return id; }
    public String pathogenKey() { return pathogenKey; }
    public SettlementId origin() { return origin; }
    public long startedDay() { return startedDay; }
    public double transmissionRate() { return transmissionRate; }
    public void setTransmissionRate(double transmissionRate) { this.transmissionRate = transmissionRate; }
    public double mortalityRate() { return mortalityRate; }
    public void setMortalityRate(double mortalityRate) { this.mortalityRate = mortalityRate; }
    public boolean active() { return active; }
    public void setActive(boolean active) { this.active = active; }
    public Set<SettlementId> affectedSettlements() { return affectedSettlements; }
}
