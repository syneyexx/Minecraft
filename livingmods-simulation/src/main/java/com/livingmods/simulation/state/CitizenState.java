package com.livingmods.simulation.state;

import com.livingmods.common.id.CitizenId;
import com.livingmods.common.id.CultureId;
import com.livingmods.common.id.DynastyId;
import com.livingmods.common.id.HouseholdId;
import com.livingmods.common.id.SettlementId;
import com.livingmods.common.id.StructureId;
import com.livingmods.common.model.MilitaryRole;
import com.livingmods.common.model.Personality;
import com.livingmods.common.model.Profession;
import com.livingmods.common.model.ScheduleState;
import com.livingmods.common.model.WealthClass;
import com.livingmods.common.time.SimulationTime;

public final class CitizenState {
    private final CitizenId id;
    private final String givenName;
    private final String familyName;
    private final boolean female;
    private final SimulationTime birthDate;
    private final CultureId cultureId;
    private SettlementId settlementId;
    private HouseholdId householdId;
    private Profession profession;
    private double health;
    private double wealth;
    private boolean alive;
    private boolean ruler;
    private int crimeStrikes;
    private boolean incarcerated;
    private long sentenceEndsDay;
    private Personality personality;
    private boolean noble;
    private boolean heir;

    private CitizenId motherId;
    private CitizenId fatherId;
    private CitizenId spouseId;
    private CitizenId guardianId;
    private boolean adoptive;
    private DynastyId dynastyId;
    private StructureId homeStructureId;
    private StructureId workStructureId;
    private ScheduleState schedule = ScheduleState.HOME;
    private WealthClass wealthClass = WealthClass.COMMON;
    private MilitaryRole militaryRole = MilitaryRole.NONE;
    private int educationYears;
    private boolean orphanageResident;
    private long projectionRevision;
    private long lastBirthDay = -1L;
    private double literacy;
    private double skill;
    private String knownDiseaseKey;
    private long immunityUntilDay;
    public static final int MAX_KNOWN_RUMORS = 64;
    private final java.util.Set<String> knownRumorIds = new java.util.LinkedHashSet<>();

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
        this.health = clampHealth(health);
        this.wealth = wealth;
        this.alive = alive;
        this.ruler = ruler;
    }

    public CitizenId id() { return id; }
    public String givenName() { return givenName; }
    public String familyName() { return familyName; }
    public String displayName() { return givenName + " " + familyName; }
    public boolean female() { return female; }
    public SimulationTime birthDate() { return birthDate; }
    public CultureId cultureId() { return cultureId; }
    public SettlementId settlementId() { return settlementId; }
    public void setSettlementId(SettlementId settlementId) { this.settlementId = settlementId; }
    public HouseholdId householdId() { return householdId; }
    public void setHouseholdId(HouseholdId householdId) { this.householdId = householdId; }
    public Profession profession() { return profession; }
    public void setProfession(Profession profession) { this.profession = profession; }
    public double health() { return health; }
    public void setHealth(double health) { this.health = clampHealth(health); }
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
    public long sentenceEndsDay() { return sentenceEndsDay; }
    public void setSentenceEndsDay(long sentenceEndsDay) { this.sentenceEndsDay = sentenceEndsDay; }
    public Personality personality() { return personality; }
    public void setPersonality(Personality personality) { this.personality = personality; }
    public boolean noble() { return noble; }
    public void setNoble(boolean noble) { this.noble = noble; }
    public boolean heir() { return heir; }
    public void setHeir(boolean heir) { this.heir = heir; }

    public CitizenId motherId() { return motherId; }
    public void setMotherId(CitizenId motherId) { this.motherId = motherId; }
    public CitizenId fatherId() { return fatherId; }
    public void setFatherId(CitizenId fatherId) { this.fatherId = fatherId; }
    public CitizenId spouseId() { return spouseId; }
    public void setSpouseId(CitizenId spouseId) { this.spouseId = spouseId; }
    public CitizenId guardianId() { return guardianId; }
    public void setGuardianId(CitizenId guardianId) { this.guardianId = guardianId; }
    public boolean adoptive() { return adoptive; }
    public void setAdoptive(boolean adoptive) { this.adoptive = adoptive; }
    public DynastyId dynastyId() { return dynastyId; }
    public void setDynastyId(DynastyId dynastyId) { this.dynastyId = dynastyId; }
    public StructureId homeStructureId() { return homeStructureId; }
    public void setHomeStructureId(StructureId homeStructureId) { this.homeStructureId = homeStructureId; }
    public StructureId workStructureId() { return workStructureId; }
    public void setWorkStructureId(StructureId workStructureId) { this.workStructureId = workStructureId; }
    public ScheduleState schedule() { return schedule; }
    public void setSchedule(ScheduleState schedule) {
        this.schedule = schedule == null ? ScheduleState.HOME : schedule;
    }
    public WealthClass wealthClass() { return wealthClass; }
    public void setWealthClass(WealthClass wealthClass) {
        this.wealthClass = wealthClass == null ? WealthClass.COMMON : wealthClass;
    }
    public MilitaryRole militaryRole() { return militaryRole; }
    public void setMilitaryRole(MilitaryRole militaryRole) {
        this.militaryRole = militaryRole == null ? MilitaryRole.NONE : militaryRole;
    }
    public int educationYears() { return educationYears; }
    public void setEducationYears(int educationYears) { this.educationYears = Math.max(0, educationYears); }
    public boolean orphanageResident() { return orphanageResident; }
    public void setOrphanageResident(boolean orphanageResident) { this.orphanageResident = orphanageResident; }
    public long projectionRevision() { return projectionRevision; }
    public void setProjectionRevision(long projectionRevision) { this.projectionRevision = projectionRevision; }

    public int ageYears(SimulationTime now) {
        long days = now.dayIndex() - birthDate.dayIndex();
        return (int) Math.max(0, days / SimulationTime.DAYS_PER_YEAR);
    }

    public boolean reproductiveAge(SimulationTime now) {
        int age = ageYears(now);
        return female && age >= 18 && age <= 42;
    }

    public boolean isChild(SimulationTime now) {
        return ageYears(now) < 16;
    }

    public boolean isAdult(SimulationTime now) {
        return ageYears(now) >= 16;
    }

    /** Alive, free, non-child citizens with enough health can work production jobs. */
    public boolean canWork() {
        return alive && !incarcerated && profession != Profession.CHILD && health >= 20.0;
    }

    public long lastBirthDay() { return lastBirthDay; }
    public void setLastBirthDay(long lastBirthDay) { this.lastBirthDay = lastBirthDay; }
    public double literacy() { return literacy; }
    public void setLiteracy(double literacy) { this.literacy = Math.max(0, Math.min(1, literacy)); }
    public double skill() { return skill; }
    public void setSkill(double skill) { this.skill = Math.max(0, Math.min(1, skill)); }
    public String knownDiseaseKey() { return knownDiseaseKey; }
    public void setKnownDiseaseKey(String knownDiseaseKey) { this.knownDiseaseKey = knownDiseaseKey; }
    public long immunityUntilDay() { return immunityUntilDay; }
    public void setImmunityUntilDay(long immunityUntilDay) { this.immunityUntilDay = immunityUntilDay; }
    public java.util.Set<String> knownRumorIds() { return knownRumorIds; }

    /** Learn a rumor, dropping the oldest when the per-citizen cap is hit. */
    public boolean learnRumor(String rumorId) {
        if (rumorId == null || rumorId.isBlank() || knownRumorIds.contains(rumorId)) {
            return false;
        }
        knownRumorIds.add(rumorId);
        while (knownRumorIds.size() > MAX_KNOWN_RUMORS) {
            String oldest = knownRumorIds.iterator().next();
            knownRumorIds.remove(oldest);
        }
        return true;
    }

    public boolean immuneTo(String diseaseKey, long dayIndex) {
        return diseaseKey != null
                && diseaseKey.equals(knownDiseaseKey)
                && dayIndex < immunityUntilDay;
    }

    private static double clampHealth(double value) {
        return Math.max(0.0, Math.min(100.0, value));
    }
}
