package com.livingmods.simulation.state;

import com.livingmods.common.id.EpidemicId;
import com.livingmods.common.id.SettlementId;
import com.livingmods.common.model.DiseaseDefinition;

import java.util.HashSet;
import java.util.Set;

public final class EpidemicState {
    private final EpidemicId id;
    private final String pathogenKey;
    private final SettlementId origin;
    private final long startedDay;
    private double transmissionRate;
    private double mortalityRate;
    private double severity;
    private double durationDays;
    private boolean active;
    private final Set<SettlementId> affectedSettlements;
    private int remainingHours;

    public EpidemicState(EpidemicId id, String pathogenKey, SettlementId origin, long startedDay) {
        this.id = id;
        this.pathogenKey = pathogenKey;
        this.origin = origin;
        this.startedDay = startedDay;
        DiseaseDefinition def = DiseaseDefinition.byKey(pathogenKey);
        this.transmissionRate = def.infectivity();
        this.mortalityRate = def.mortality();
        this.severity = def.severity();
        this.durationDays = def.durationDays();
        this.active = true;
        this.affectedSettlements = new HashSet<>();
        affectedSettlements.add(origin);
        this.remainingHours = (int) Math.max(24, def.durationDays() * 24);
    }

    public EpidemicId id() { return id; }
    public String pathogenKey() { return pathogenKey; }
    public SettlementId origin() { return origin; }
    public long startedDay() { return startedDay; }
    public double transmissionRate() { return transmissionRate; }
    public void setTransmissionRate(double transmissionRate) { this.transmissionRate = transmissionRate; }
    public double mortalityRate() { return mortalityRate; }
    public void setMortalityRate(double mortalityRate) { this.mortalityRate = mortalityRate; }
    public double severity() { return severity; }
    public void setSeverity(double severity) { this.severity = severity; }
    public double durationDays() { return durationDays; }
    public boolean active() { return active; }
    public void setActive(boolean active) { this.active = active; }
    public Set<SettlementId> affectedSettlements() { return affectedSettlements; }
    public int remainingHours() { return remainingHours; }
    public void setRemainingHours(int remainingHours) { this.remainingHours = remainingHours; }
    public void tickHour() { remainingHours = Math.max(0, remainingHours - 1); }
}
