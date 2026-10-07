package com.livingmods.simulation;

import com.livingmods.common.culture.CultureDefinition;
import com.livingmods.common.culture.CultureRegistry;
import com.livingmods.common.culture.NameGrammar;
import com.livingmods.common.event.CivilizationEventType;
import com.livingmods.common.event.HistoricalEvent;
import com.livingmods.common.id.ArmyId;
import com.livingmods.common.id.CitizenId;
import com.livingmods.common.id.DynastyId;
import com.livingmods.common.id.HistoricalEventId;
import com.livingmods.common.id.HouseholdId;
import com.livingmods.common.id.KingdomId;
import com.livingmods.common.id.SettlementId;
import com.livingmods.common.id.StructureId;
import com.livingmods.common.model.BuildingRole;
import com.livingmods.common.model.DiplomaticRelation;
import com.livingmods.common.model.FamilyRelationType;
import com.livingmods.common.model.GovernmentType;
import com.livingmods.common.model.Personality;
import com.livingmods.common.model.Profession;
import com.livingmods.common.model.ResourceType;
import com.livingmods.common.model.ScheduleState;
import com.livingmods.common.model.WealthClass;
import com.livingmods.simulation.engine.GovernmentEngine;
import com.livingmods.common.time.SimulationTime;
import com.livingmods.common.util.DeterministicRandom;
import com.livingmods.simulation.engine.ProfessionDemand;
import com.livingmods.simulation.state.ArmyState;
import com.livingmods.simulation.state.CitizenState;
import com.livingmods.simulation.state.DynastyState;
import com.livingmods.simulation.state.FamilyRelationState;
import com.livingmods.simulation.state.HouseholdState;
import com.livingmods.simulation.state.KingdomState;
import com.livingmods.simulation.state.MarketState;
import com.livingmods.simulation.state.SettlementState;
import com.livingmods.simulation.state.StockpileState;
import com.livingmods.worldgen.plan.PlannedBuilding;
import com.livingmods.worldgen.plan.PlannedKingdom;
import com.livingmods.worldgen.plan.PlannedSettlement;
import com.livingmods.worldgen.plan.WorldPlan;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class InitialStateFactory {
    private static final CultureRegistry CULTURES = new CultureRegistry();

    private InitialStateFactory() {}

    public static CanonicalWorldState fromWorldPlan(WorldPlan plan) {
        return fromWorldPlan(plan, SimulationTime.ofTicks(0));
    }

    public static CanonicalWorldState fromWorldPlan(WorldPlan plan, SimulationTime startTime) {
        CanonicalWorldState state = new CanonicalWorldState(plan.seed(), startTime, plan.contentHash());
        DeterministicRandom rng = new DeterministicRandom(plan.seed());
        state.tradeNetwork().loadFromWorldPlan(plan);

        long citizenOrdinal = 0;
        long householdOrdinal = 0;
        long dynastyOrdinal = 0;

        for (PlannedSettlement planned : plan.settlements().values()) {
            int housingCapacity = housingCapacity(planned);
            int workSlots = workSlots(planned);
            SettlementState settlement = new SettlementState(
                    planned.id(),
                    planned.name(),
                    planned.tier(),
                    planned.role(),
                    planned.center(),
                    planned.ownerKingdom(),
                    planned.capital(),
                    null,
                    70.0,
                    planned.tier().typicalPopulation() * 0.1,
                    Math.max(housingCapacity, planned.tier().typicalPopulation() * 0.8),
                    Math.max(1, housingCapacity / 4),
                    Math.max(1, workSlots)
            );
            state.putSettlement(settlement);
            state.stockpiles().put(planned.id(), initialStockpile(planned));
            state.markets().put(planned.id(), new MarketState(planned.id()));

            CultureDefinition culture = CULTURES.get(planned.cultureId())
                    .or(() -> CULTURES.get(planned.cultureKey()))
                    .orElse(null);

            List<PlannedBuilding> homes = residentialBuildings(planned);
            List<PlannedBuilding> workplaces = workplaceBuildings(planned);
            int homeCursor = 0;
            int workCursor = 0;

            int pop = Math.max(1, planned.plannedPopulation());
            int householdSize = 4;
            int households = Math.max(1, (pop + householdSize - 1) / householdSize);
            for (int h = 0; h < households; h++) {
                HouseholdId hhId = HouseholdId.deterministic(plan.seed(), householdOrdinal++);
                HouseholdState hh = new HouseholdState(hhId, planned.id(), 0, 10.0, 1);
                PlannedBuilding home = homes.isEmpty() ? null : homes.get(homeCursor++ % homes.size());
                if (home != null) {
                    hh.setHomeStructureId(home.id());
                    hh.setHousingQuality(Math.max(1, home.residentialSlots()));
                }
                state.putHousehold(hh);

                DynastyId dynastyId = DynastyId.deterministic(plan.seed(), dynastyOrdinal++);
                String familyName = culture == null
                        ? planned.name().split(" ")[0]
                        : NameGrammar.citizenFamily(culture, rng);

                int members = Math.min(householdSize, pop);
                pop -= members;
                List<CitizenState> householdMembers = new ArrayList<>(members);
                CitizenState mother = null;
                CitizenState father = null;

                for (int m = 0; m < members; m++) {
                    CitizenId cid = CitizenId.deterministic(plan.seed(), citizenOrdinal++);
                    boolean female;
                    int age;
                    if (m == 0) {
                        female = true;
                        age = rng.nextInt(22, 40);
                    } else if (m == 1) {
                        female = false;
                        age = rng.nextInt(22, 45);
                    } else {
                        female = rng.nextBoolean();
                        age = rng.nextInt(1, 16);
                    }
                    boolean child = age < 16;
                    Profession profession = ProfessionDemand.pickForSettlement(
                            planned, rng, m == 0 && h == 0 && planned.capital(), child);
                    long birthDay = Math.max(0L, startTime.dayIndex() - age * (long) SimulationTime.DAYS_PER_YEAR);
                    SimulationTime birth = SimulationTime.ofDays(birthDay);
                    String given = culture == null
                            ? (female ? "Bryn" : "Alden")
                            : NameGrammar.citizenGiven(culture, female, rng);
                    CitizenState citizen = new CitizenState(
                            cid,
                            given,
                            familyName,
                            female,
                            birth,
                            planned.cultureId(),
                            planned.id(),
                            hhId,
                            profession,
                            85.0,
                            5.0,
                            true,
                            false
                    );
                    citizen.setDynastyId(dynastyId);
                    citizen.setPersonality(Personality.random(rng));
                    citizen.setNoble(profession == Profession.NOBLE);
                    citizen.setHomeStructureId(home == null ? null : home.id());
                    citizen.setWealthClass(wealthFor(profession));
                    citizen.setMilitaryRole(ProfessionDemand.militaryFor(profession));
                    citizen.setSchedule(child ? ScheduleState.HOME : ScheduleState.WORK);
                    if (!child && !workplaces.isEmpty()) {
                        PlannedBuilding workplace = workplaces.get(workCursor++ % workplaces.size());
                        citizen.setWorkStructureId(workplace.id());
                        Profession fromBuilding = ProfessionDemand.workplaceProfession(workplace.role());
                        if (fromBuilding != Profession.UNEMPLOYED && profession != Profession.NOBLE) {
                            citizen.setProfession(fromBuilding);
                            citizen.setMilitaryRole(ProfessionDemand.militaryFor(fromBuilding));
                        }
                    }
                    if (child) {
                        citizen.setEducationYears(Math.min(6, age / 3));
                    }
                    state.putCitizen(citizen);
                    hh.setMemberCount(hh.memberCount() + 1);
                    householdMembers.add(citizen);
                    if (m == 0) mother = citizen;
                    if (m == 1) father = citizen;
                }

                if (mother != null) {
                    hh.setHeadId(mother.id());
                    if (culture != null) {
                        DynastyState dynasty = new DynastyState(
                                dynastyId,
                                NameGrammar.dynasty(culture, rng),
                                mother.id(),
                                mother.id());
                        for (CitizenState member : householdMembers) {
                            dynasty.members().add(member.id());
                        }
                        state.dynasties().put(dynastyId, dynasty);
                    }
                }
                if (mother != null && father != null) {
                    mother.setSpouseId(father.id());
                    father.setSpouseId(mother.id());
                    state.putFamilyRelation(FamilyRelationState.of(mother.id(), father.id(), FamilyRelationType.SPOUSE));
                    state.putFamilyRelation(FamilyRelationState.of(father.id(), mother.id(), FamilyRelationType.PARTNER));
                }
                for (CitizenState member : householdMembers) {
                    if (member == mother || member == father) continue;
                    if (mother != null) {
                        member.setMotherId(mother.id());
                        state.putFamilyRelation(FamilyRelationState.of(
                                mother.id(), member.id(), FamilyRelationType.PARENT_CHILD));
                    }
                    if (father != null) {
                        member.setFatherId(father.id());
                        state.putFamilyRelation(FamilyRelationState.of(
                                father.id(), member.id(), FamilyRelationType.PARENT_CHILD));
                    }
                    if (member.isChild(startTime)) {
                        member.setGuardianId(mother != null ? mother.id() : father == null ? null : father.id());
                    }
                }
                for (int i = 0; i < householdMembers.size(); i++) {
                    for (int j = i + 1; j < householdMembers.size(); j++) {
                        CitizenState a = householdMembers.get(i);
                        CitizenState b = householdMembers.get(j);
                        if (a == mother || a == father || b == mother || b == father) continue;
                        state.putFamilyRelation(FamilyRelationState.of(a.id(), b.id(), FamilyRelationType.SIBLING));
                        state.putFamilyRelation(FamilyRelationState.of(b.id(), a.id(), FamilyRelationType.SIBLING));
                    }
                }
                if (pop <= 0) break;
            }
        }

        for (PlannedKingdom pk : plan.kingdoms()) {
            CitizenId rulerId = pickRuler(state, pk);
            KingdomState kingdom = new KingdomState(
                    pk.id(),
                    pk.name(),
                    pk.cultureId(),
                    pk.governmentType(),
                    pk.capitalId(),
                    rulerId,
                    new ArrayList<>(pk.settlementIds()),
                    100.0,
                    0.1,
                    75.0
            );
            kingdom.setReligionKey(pk.religionKey() == null ? "none" : pk.religionKey());
            kingdom.adjacentKingdoms().addAll(pk.adjacentKingdomIds());
            state.kingdoms().put(pk.id(), kingdom);

            CitizenState ruler = state.citizens().get(rulerId);
            if (ruler != null) {
                ruler.setProfession(Profession.RULER);
                ruler.setRuler(true);
                ruler.setNoble(true);
                ruler.setMilitaryRole(ProfessionDemand.militaryFor(Profession.RULER));
                ruler.setWealthClass(WealthClass.ROYAL);
                if (ruler.dynastyId() != null) {
                    DynastyState dynasty = state.dynasties().get(ruler.dynastyId());
                    if (dynasty != null) {
                        dynasty.setCurrentHeadId(ruler.id());
                        dynasty.kingdomClaims().add(pk.id());
                        kingdom.setDynastyId(dynasty.id());
                    }
                }
                if (pk.governmentType() == GovernmentType.MONARCHY
                        || pk.governmentType() == GovernmentType.PLAYER_REALM) {
                    GovernmentEngine.ensureDynasty(state, kingdom);
                }
            }
            SettlementState capital = state.settlements().get(pk.capitalId());
            if (capital != null) {
                capital.setRulerId(rulerId);
            }

            ArmyId armyId = ArmyId.deterministic(plan.seed(), state.armies().size());
            ArmyState army = new ArmyState(
                    armyId, pk.id(), pk.capitalCenter(), Math.max(20, pk.settlementIds().size() * 10));
            army.setCommanderId(rulerId);
            state.armies().put(armyId, army);

            for (KingdomId other : pk.adjacentKingdomIds()) {
                state.intelligence().setQuality(pk.id(), other, 0.35);
            }

            state.appendHistory(new HistoricalEvent(
                    HistoricalEventId.deterministic(plan.seed(), state.history().size()),
                    CivilizationEventType.SETTLEMENT_FOUNDED,
                    startTime,
                    "Kingdom founded",
                    pk.name() + " established.",
                    Optional.of(pk.capitalCenter()),
                    Map.of("kingdom", pk.id().toString())
            ));
        }

        List<KingdomId> kingdomIds = new ArrayList<>(state.kingdoms().keySet());
        kingdomIds.sort(KingdomId::compareTo);
        for (int i = 0; i < kingdomIds.size(); i++) {
            for (int j = i + 1; j < kingdomIds.size(); j++) {
                KingdomId a = kingdomIds.get(i);
                KingdomId b = kingdomIds.get(j);
                state.diplomacy().setRelation(a, b, DiplomaticRelation.NEUTRAL);
                state.diplomacy().setScore(a, b, 0);
            }
        }

        state.rebuildIndexes();
        return state;
    }

    /** Rebuild road/bandit graph after loading a canonical snapshot beside a world plan. */
    public static void attachWorldPlan(CanonicalWorldState state, WorldPlan plan) {
        if (state == null || plan == null) return;
        state.tradeNetwork().loadFromWorldPlan(plan);
        for (PlannedKingdom pk : plan.kingdoms()) {
            KingdomState kingdom = state.kingdoms().get(pk.id());
            if (kingdom == null) continue;
            if (kingdom.religionKey() == null || "none".equals(kingdom.religionKey())) {
                kingdom.setReligionKey(pk.religionKey() == null ? "none" : pk.religionKey());
            }
            kingdom.adjacentKingdoms().addAll(pk.adjacentKingdomIds());
        }
    }

    private static int housingCapacity(PlannedSettlement planned) {
        int slots = 0;
        for (PlannedBuilding b : planned.buildings()) {
            slots += Math.max(b.residentialSlots(), residentialFallback(b.role()));
        }
        if (slots <= 0) {
            slots = Math.max(8, planned.plannedPopulation());
        }
        return slots;
    }

    private static int workSlots(PlannedSettlement planned) {
        int slots = 0;
        for (PlannedBuilding b : planned.buildings()) {
            slots += Math.max(b.workSlots(), workFallback(b.role()));
        }
        return Math.max(2, slots);
    }

    private static int residentialFallback(BuildingRole role) {
        return switch (role) {
            case HOUSE, FARMHOUSE -> 4;
            case TOWNHOUSE -> 6;
            case MANOR -> 8;
            case PALACE -> 12;
            default -> 0;
        };
    }

    private static int workFallback(BuildingRole role) {
        return switch (role) {
            case MINE_ENTRANCE, DOCK, SCHOOL, GUARDHOUSE, BARRACKS, SMITHY, WORKSHOP,
                 MARKET_HALL, TEMPLE, CLINIC, SAWMILL, MILL, WAREHOUSE -> 3;
            case MARKET_STALL, SHOP, TAVERN, GATEHOUSE, TOWER -> 1;
            default -> 0;
        };
    }

    private static List<PlannedBuilding> residentialBuildings(PlannedSettlement planned) {
        List<PlannedBuilding> homes = new ArrayList<>();
        for (PlannedBuilding b : planned.buildings()) {
            if (residentialFallback(b.role()) > 0 || b.residentialSlots() > 0) {
                homes.add(b);
            }
        }
        homes.sort(Comparator.comparing(b -> b.id().value()));
        return homes;
    }

    private static List<PlannedBuilding> workplaceBuildings(PlannedSettlement planned) {
        List<PlannedBuilding> work = new ArrayList<>();
        for (PlannedBuilding b : planned.buildings()) {
            if (ProfessionDemand.workplaceProfession(b.role()) != Profession.UNEMPLOYED
                    || b.workSlots() > 0) {
                work.add(b);
            }
        }
        work.sort(Comparator.comparing(b -> b.id().value()));
        return work;
    }

    private static WealthClass wealthFor(Profession profession) {
        return switch (profession) {
            case RULER -> WealthClass.ROYAL;
            case NOBLE -> WealthClass.NOBLE;
            case MERCHANT, TRADER -> WealthClass.COMFORTABLE;
            case GUARD, SOLDIER, TEACHER, SCHOLAR, PRIEST, HEALER -> WealthClass.COMMON;
            case CHILD, UNEMPLOYED -> WealthClass.POOR;
            default -> WealthClass.COMMON;
        };
    }

    private static StockpileState initialStockpile(PlannedSettlement planned) {
        StockpileState sp = new StockpileState(planned.id());
        int pop = Math.max(1, planned.plannedPopulation());
        sp.set(ResourceType.GRAIN, pop * 2.0);
        sp.set(ResourceType.FOOD, pop * 1.0);
        sp.set(ResourceType.LIVESTOCK, pop * 0.2);
        sp.set(ResourceType.FISH, pop * 0.15);
        sp.set(ResourceType.WOOD, pop * 0.5);
        sp.set(ResourceType.STONE, pop * 0.3);
        sp.set(ResourceType.IRON_ORE, pop * 0.15);
        sp.set(ResourceType.IRON, pop * 0.05);
        sp.set(ResourceType.COAL, pop * 0.2);
        sp.set(ResourceType.FUEL, pop * 0.2);
        sp.set(ResourceType.TOOLS, pop * 0.1);
        sp.set(ResourceType.WEAPONS, pop * 0.05);
        sp.set(ResourceType.TEXTILES, pop * 0.1);
        sp.set(ResourceType.CLOTH, pop * 0.1);
        sp.set(ResourceType.MEDICINE, pop * 0.05);
        sp.set(ResourceType.CONSTRUCTION, pop * 0.25);
        sp.set(ResourceType.LUXURY, pop * 0.02);
        return sp;
    }

    private static CitizenId pickRuler(CanonicalWorldState state, PlannedKingdom pk) {
        CitizenState best = null;
        for (SettlementId sid : pk.settlementIds()) {
            if (!sid.equals(pk.capitalId())) continue;
            for (CitizenState c : state.citizens().values()) {
                if (!c.settlementId().equals(sid) || !c.alive() || c.female()) continue;
                if (c.ageYears(state.time()) < 20) continue;
                if (best == null || c.profession() == Profession.NOBLE) {
                    best = c;
                }
            }
        }
        if (best != null) return best.id();
        for (CitizenState c : state.citizens().values()) {
            if (c.alive()) return c.id();
        }
        throw new IllegalStateException("no citizens for ruler");
    }
}
