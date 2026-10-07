package com.livingmods.simulation.state;

import com.livingmods.common.id.CitizenId;
import com.livingmods.common.id.CultureId;
import com.livingmods.common.id.HouseholdId;
import com.livingmods.common.id.SettlementId;
import com.livingmods.common.model.Profession;
import com.livingmods.common.time.SimulationTime;

public final class CitizenState {
    private final CitizenId id;
    private final String givenName;
    private final String familyName;
    private final boolean female;
    private final SimulationTime birthDate;
    private final CultureId cultureId;
    private final SettlementId settlementId;
    private final HouseholdId householdId;
    private Profession profession;
    private double health;
    private double wealth;
    private boolean alive;
    private boolean ruler;
    private int crimeStrikes;
    private boolean incarcerated;

    public CitizenState(
            CitizenId id,
            String givenName,
            String familyName,
            boolean female,
            SimulationTime birthDate,
            CultureId cultureId,
            SettlementId settlementId,
            HouseholdId householdId,
            Profession profession,
            double health,
            double wealth,
            boolean alive,
            boolean ruler
    ) {
        this.id = id;
        this.givenName = givenName;
        this.familyName = familyName;
        this.female = female;
        this.birthDate = birthDate;
        this.cultureId = cultureId;
        this.settlementId = settlementId;
        this.householdId = householdId;
        this.profession = profession;
        this.health = health;
        this.wealth = wealth;
        this.alive = alive;
        this.ruler = ruler;
    }

    public CitizenId id() { return id; }
    public String givenName() { return givenName; }
    public String familyName() { return familyName; }
    public boolean female() { return female; }
    public SimulationTime birthDate() { return birthDate; }
    public CultureId cultureId() { return cultureId; }
    public SettlementId settlementId() { return settlementId; }
    public HouseholdId householdId() { return householdId; }
    public Profession profession() { return profession; }
    public void setProfession(Profession profession) { this.profession = profession; }
    public double health() { return health; }
    public void setHealth(double health) { this.health = health; }
    public double wealth() { return wealth; }
    public void setWealth(double wealth) { this.wealth = wealth; }
    public boolean alive() { return alive; }
    public void setAlive(boolean alive) { this.alive = alive; }
    public boolean ruler() { return ruler; }
    public void setRuler(boolean ruler) { this.ruler = ruler; }
    public int crimeStrikes() { return crimeStrikes; }
    public void setCrimeStrikes(int crimeStrikes) { this.crimeStrikes = crimeStrikes; }
    public boolean incarcerated() { return incarcerated; }
    public void setIncarcerated(boolean incarcerated) { this.incarcerated = incarcerated; }

    public int ageYears(SimulationTime now) {
        long days = now.dayIndex() - birthDate.dayIndex();
        return (int) Math.max(0, days / SimulationTime.DAYS_PER_YEAR);
    }
}
