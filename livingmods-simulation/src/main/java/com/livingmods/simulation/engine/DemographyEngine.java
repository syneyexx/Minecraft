package com.livingmods.simulation.engine;

import com.livingmods.common.culture.CultureDefinition;
import com.livingmods.common.culture.CultureRegistry;
import com.livingmods.common.culture.NameGrammar;
import com.livingmods.common.id.CitizenId;
import com.livingmods.common.id.HouseholdId;
import com.livingmods.common.model.FamilyRelationType;
import com.livingmods.common.model.Profession;
import com.livingmods.common.time.SimulationTime;
import com.livingmods.common.util.DeterministicRandom;
import com.livingmods.simulation.CanonicalWorldState;
import com.livingmods.simulation.state.CitizenState;
import com.livingmods.simulation.state.FamilyRelationState;
import com.livingmods.simulation.state.HouseholdState;
import com.livingmods.simulation.state.SettlementState;
import com.livingmods.simulation.tick.RegionalWork;
import com.livingmods.simulation.tick.SimulationContext;

import java.util.ArrayList;
import java.util.List;

/**
 * Reproductive demography calibrated for Minecraft-time scale (hourly/daily ticks).
 * Handles parents, birth spacing, fertility, mortality, carrying capacity, child
 * development, and orphan placement.
 */
public final class DemographyEngine implements SimulationSubsystem {
    /** Minimum days between births for the same mother (~Minecraft-year spacing). */
    private static final int MIN_BIRTH_SPACING_DAYS = 280;
    private static final double BASE_FERTILITY_PER_CYCLE = 0.012;
    private static final int CHILD_TO_ADULT_AGE = 16;
    private static final CultureRegistry CULTURES = new CultureRegistry();

    @Override
    public String name() { return "demography"; }

    @Override
    public void phase1Regional(CanonicalWorldState state, RegionalWork work, SimulationContext ctx) {
        if (!ctx.schedule().runDemography()) return;

        List<CitizenState> regional = new ArrayList<>();
        for (SettlementState s : state.settlements().values()) {
            if (!s.region().equals(work.region())) continue;
            for (CitizenId cid : state.citizensInSettlement(s.id())) {
                CitizenState c = state.citizens().get(cid);
                if (c != null) {
                    regional.add(c);
                }
            }
        }

        DeterministicRandom rng = ctx.forkRegion(work.region().x() * 10000L + work.region().z());
        List<DeathRecord> deaths = new ArrayList<>();
        List<BirthRecord> births = new ArrayList<>();
        List<CitizenId> graduating = new ArrayList<>();
        List<OrphanRecord> orphans = new ArrayList<>();

        for (CitizenState c : regional) {
            if (!c.alive()) continue;
            int age = c.ageYears(ctx.time());
            SettlementState settlement = state.settlements().get(c.settlementId());
            double carryingPressure = carryingPressure(state, settlement);
            double mortality = baseMortality(age, c.health()) * (1.0 + carryingPressure * 0.5);
            if (rng.chance(mortality)) {
                deaths.add(new DeathRecord(c.id()));
            } else {
                double healthDelta = agingHealthDelta(age) - carryingPressure * 0.2;
                work.enqueueCommit(() -> c.setHealth(c.health() + healthDelta));
            }
            if (c.isChild(ctx.time()) && age >= CHILD_TO_ADULT_AGE - 1
                    && c.profession() == Profession.CHILD
                    && rng.chance(0.08)) {
                graduating.add(c.id());
            }
        }

        for (CitizenState mother : regional) {
            if (!mother.alive() || !mother.reproductiveAge(ctx.time())) continue;
            HouseholdState hh = state.households().get(mother.householdId());
            if (hh == null || hh.memberCount() >= 6) continue;
            SettlementState settlement = state.settlements().get(mother.settlementId());
            if (settlement == null) continue;
            if (carryingPressure(state, settlement) > 0.85) continue;
            if (mother.lastBirthDay() >= 0
                    && ctx.time().dayIndex() - mother.lastBirthDay() < MIN_BIRTH_SPACING_DAYS) {
                continue;
            }
            // Births require a plausible partner in the household (not mother-only).
            CitizenState father = findFather(state, mother, hh);
            if (father == null) continue;
            double fertility = BASE_FERTILITY_PER_CYCLE
                    * fertilityByAge(mother.ageYears(ctx.time()))
                    * (1.0 - carryingPressure(state, settlement) * 0.6)
                    * (mother.health() / 100.0)
                    * 1.15;
            if (rng.chance(fertility)) {
                births.add(new BirthRecord(
                        mother.settlementId(),
                        mother.householdId(),
                        mother.cultureId(),
                        mother.familyName(),
                        mother.id(),
                        father.id()
                ));
            }
        }

        for (CitizenState child : regional) {
            if (!child.alive() || !child.isChild(ctx.time())) continue;
            boolean motherDead = child.motherId() == null
                    || !alive(state, child.motherId());
            boolean fatherDead = child.fatherId() == null
                    || !alive(state, child.fatherId());
            boolean guardianDead = child.guardianId() == null
                    || !alive(state, child.guardianId());
            if (motherDead && fatherDead && guardianDead) {
                orphans.add(new OrphanRecord(child.id(), child.settlementId()));
            }
        }

        work.enqueueCommit(() -> {
            for (DeathRecord d : deaths) {
                CitizenState c = state.citizens().get(d.id);
                if (c == null) continue;
                c.setAlive(false);
                if (c.ruler()) {
                    c.setRuler(false);
                }
                HouseholdState hh = state.households().get(c.householdId());
                if (hh != null) {
                    hh.setMemberCount(Math.max(0, hh.memberCount() - 1));
                }
            }
            for (CitizenId id : graduating) {
                CitizenState c = state.citizens().get(id);
                if (c == null || !c.alive()) continue;
                CitizenState caregiver = null;
                if (c.guardianId() != null) caregiver = state.citizens().get(c.guardianId());
                if (caregiver == null && c.motherId() != null) caregiver = state.citizens().get(c.motherId());
                if (caregiver == null && c.fatherId() != null) caregiver = state.citizens().get(c.fatherId());
                Profession apprenticed = ProfessionDemand.apprenticeFromCaregiver(
                        caregiver == null ? null : caregiver.profession(), rng);
                if (c.educationYears() >= 4 && rng.chance(0.3)) {
                    apprenticed = rng.pick(List.of(Profession.SCHOLAR, Profession.TEACHER, Profession.HEALER));
                }
                c.setProfession(apprenticed);
                c.setMilitaryRole(ProfessionDemand.militaryFor(apprenticed));
                if (caregiver != null && caregiver.workStructureId() != null) {
                    c.setWorkStructureId(caregiver.workStructureId());
                }
                c.setSkill(Math.min(1.0, c.skill() + 0.2));
                c.setLiteracy(Math.min(1.0, c.literacy() + 0.1 * c.educationYears()));
                c.setOrphanageResident(false);
                c.setGuardianId(null);
            }
            long ordinal = state.citizens().size();
            for (BirthRecord b : births) {
                CitizenId childId = CitizenId.deterministic(state.seed(), ordinal++);
                boolean female = rng.nextBoolean();
                CultureDefinition culture = CULTURES.get(b.cultureId).orElse(null);
                String given = culture == null
                        ? (female ? "Ava" : "Jon")
                        : NameGrammar.childGiven(culture, female, rng);
                CitizenState child = new CitizenState(
                        childId,
                        given,
                        b.familyName,
                        female,
                        ctx.time(),
                        b.cultureId,
                        b.settlementId,
                        b.householdId,
                        Profession.CHILD,
                        92.0,
                        0.0,
                        true,
                        false
                );
                child.setMotherId(b.motherId);
                child.setFatherId(b.fatherId);
                CitizenState mother = state.citizens().get(b.motherId);
                if (mother != null) {
                    child.setHomeStructureId(mother.homeStructureId());
                    child.setDynastyId(mother.dynastyId());
                    child.setWealthClass(mother.wealthClass());
                    mother.setLastBirthDay(ctx.time().dayIndex());
                }
                state.putCitizen(child);
                HouseholdState hh = state.households().get(b.householdId);
                if (hh != null) {
                    hh.setMemberCount(hh.memberCount() + 1);
                }
                state.putFamilyRelation(FamilyRelationState.of(b.motherId, childId, FamilyRelationType.PARENT_CHILD));
                if (b.fatherId != null) {
                    state.putFamilyRelation(FamilyRelationState.of(b.fatherId, childId, FamilyRelationType.PARENT_CHILD));
                }
            }
            for (OrphanRecord orphan : orphans) {
                placeOrphan(state, orphan, ctx.time(), rng);
            }
        });
    }

    private static void placeOrphan(
            CanonicalWorldState state,
            OrphanRecord orphan,
            SimulationTime time,
            DeterministicRandom rng
    ) {
        CitizenState child = state.citizens().get(orphan.childId);
        if (child == null || !child.alive()) return;
        // Surviving relative guardian before open adoption.
        CitizenState relative = findRelativeGuardian(state, child);
        if (relative != null) {
            HouseholdState hh = state.households().get(relative.householdId());
            if (hh != null && hh.memberCount() < 6) {
                state.transferCitizen(child, relative.settlementId(), relative.householdId());
                child.setGuardianId(relative.id());
                child.setOrphanageResident(false);
                hh.setMemberCount(hh.memberCount() + 1);
                state.putFamilyRelation(FamilyRelationState.of(
                        relative.id(), child.id(), FamilyRelationType.GUARDIAN));
                return;
            }
        }
        HouseholdId adoptive = findAdoptiveHousehold(state, orphan.settlementId, rng);
        if (adoptive != null) {
            HouseholdState hh = state.households().get(adoptive);
            if (hh != null && hh.memberCount() < 6) {
                state.transferCitizen(child, orphan.settlementId, adoptive);
                child.setAdoptive(true);
                child.setGuardianId(hh.headId());
                child.setOrphanageResident(false);
                hh.setMemberCount(hh.memberCount() + 1);
                if (hh.headId() != null) {
                    state.putFamilyRelation(FamilyRelationState.of(
                            hh.headId(), child.id(), FamilyRelationType.ADOPTIVE_PARENT));
                }
                return;
            }
        }
        HouseholdId orphanageId = findOrphanage(state, orphan.settlementId);
        if (orphanageId == null) {
            orphanageId = HouseholdId.deterministic(state.seed(), state.households().size() + 9000L);
            HouseholdState orphanage = new HouseholdState(orphanageId, orphan.settlementId, 0, 8.0, 1);
            orphanage.setInstitutionKind(HouseholdState.KIND_ORPHANAGE);
            state.putHousehold(orphanage);
            orphanageId = orphanage.id();
        }
        HouseholdState orphanage = state.households().get(orphanageId);
        state.transferCitizen(child, orphan.settlementId, orphanageId);
        child.setOrphanageResident(true);
        if (orphanage != null) {
            orphanage.setMemberCount(orphanage.memberCount() + 1);
        }
        child.setSkill(Math.max(child.skill(), 0.05));
        child.setLiteracy(Math.max(child.literacy(), 0.05));
        // Quietly age education while institutionalized.
        if (child.isChild(time)) {
            child.setEducationYears(child.educationYears() + 1);
        }
    }

    private static HouseholdId findOrphanage(CanonicalWorldState state, com.livingmods.common.id.SettlementId sid) {
        for (HouseholdState hh : state.households().values()) {
            if (hh.settlementId().equals(sid) && hh.orphanage()) {
                return hh.id();
            }
        }
        return null;
    }

    private static HouseholdId findAdoptiveHousehold(
            CanonicalWorldState state,
            com.livingmods.common.id.SettlementId sid,
            DeterministicRandom rng
    ) {
        List<HouseholdId> candidates = new ArrayList<>();
        for (HouseholdState hh : state.households().values()) {
            if (!hh.settlementId().equals(sid) || hh.orphanage()) continue;
            if (hh.memberCount() > 0 && hh.memberCount() < 5) {
                candidates.add(hh.id());
            }
        }
        if (candidates.isEmpty()) return null;
        return candidates.get(rng.nextInt(candidates.size()));
    }

    private static CitizenState findFather(
            CanonicalWorldState state,
            CitizenState mother,
            HouseholdState hh
    ) {
        if (mother.spouseId() != null) {
            CitizenState spouse = state.citizens().get(mother.spouseId());
            if (spouse != null && spouse.alive() && !spouse.female()) {
                return spouse;
            }
        }
        for (CitizenId id : state.citizensByHousehold().getOrDefault(hh.id(), java.util.Set.of())) {
            CitizenState c = state.citizens().get(id);
            if (c != null && c.alive() && !c.female() && c.isAdult(state.time())
                    && !c.id().equals(mother.id())) {
                return c;
            }
        }
        return null;
    }

    private static boolean alive(CanonicalWorldState state, CitizenId id) {
        CitizenState c = state.citizens().get(id);
        return c != null && c.alive();
    }

    private static double carryingPressure(CanonicalWorldState state, SettlementState settlement) {
        if (settlement == null) return 0.0;
        int pop = state.citizensInSettlement(settlement.id()).size();
        double capacity = Math.max(1.0, settlement.physicalCapacity());
        return Math.max(0.0, (pop - capacity) / capacity);
    }

    private static double fertilityByAge(int age) {
        if (age < 20) return 0.7;
        if (age <= 30) return 1.0;
        if (age <= 35) return 0.75;
        return 0.45;
    }

    private static double baseMortality(int age, double health) {
        // Per demography cycle (~day-ish): keep rates small for Minecraft-time scale.
        double ageFactor = age > 70 ? 0.04 : age > 55 ? 0.015 : age > 40 ? 0.006 : age < 2 ? 0.01 : 0.002;
        double healthFactor = health < 25 ? 0.04 : health < 40 ? 0.015 : 0.0;
        return ageFactor + healthFactor;
    }

    private static double agingHealthDelta(int age) {
        if (age > 70) return -0.4;
        if (age > 50) return -0.08;
        if (age < 16) return 0.15;
        return 0.04;
    }

    private static CitizenState findRelativeGuardian(CanonicalWorldState state, CitizenState child) {
        for (FamilyRelationState rel : state.familyRelations().values()) {
            if (!rel.active() || rel.type() != FamilyRelationType.SIBLING) continue;
            CitizenId otherId = rel.from().equals(child.id()) ? rel.to()
                    : rel.to().equals(child.id()) ? rel.from() : null;
            if (otherId == null) continue;
            CitizenState other = state.citizens().get(otherId);
            if (other != null && other.alive() && other.isAdult(state.time())
                    && other.settlementId().equals(child.settlementId())) {
                return other;
            }
        }
        for (CitizenId cid : state.citizensInSettlement(child.settlementId())) {
            CitizenState c = state.citizens().get(cid);
            if (c == null || !c.alive() || !c.isAdult(state.time())) continue;
            if (c.id().equals(child.id())) continue;
            if (c.familyName().equals(child.familyName())) {
                return c;
            }
        }
        return null;
    }

    private record DeathRecord(CitizenId id) {}
    private record BirthRecord(
            com.livingmods.common.id.SettlementId settlementId,
            HouseholdId householdId,
            com.livingmods.common.id.CultureId cultureId,
            String familyName,
            CitizenId motherId,
            CitizenId fatherId
    ) {}
    private record OrphanRecord(CitizenId childId, com.livingmods.common.id.SettlementId settlementId) {}
}
