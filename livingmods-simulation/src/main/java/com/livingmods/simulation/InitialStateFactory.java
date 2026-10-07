package com.livingmods.simulation;

import com.livingmods.common.event.CivilizationEventType;
import com.livingmods.common.event.HistoricalEvent;
import com.livingmods.common.id.ArmyId;
import com.livingmods.common.id.CitizenId;
import com.livingmods.common.id.HistoricalEventId;
import com.livingmods.common.id.HouseholdId;
import com.livingmods.common.id.KingdomId;
import com.livingmods.common.id.SettlementId;
import com.livingmods.common.model.GovernmentType;
import com.livingmods.common.model.Profession;
import com.livingmods.common.model.ResourceType;
import com.livingmods.common.model.DiplomaticRelation;
import com.livingmods.common.time.SimulationTime;
import com.livingmods.common.util.DeterministicRandom;
import com.livingmods.simulation.state.ArmyState;
import com.livingmods.simulation.state.CitizenState;
import com.livingmods.simulation.state.HouseholdState;
import com.livingmods.simulation.state.KingdomState;
import com.livingmods.simulation.state.MarketState;
import com.livingmods.simulation.state.SettlementState;
import com.livingmods.simulation.state.StockpileState;
import com.livingmods.worldgen.plan.PlannedKingdom;
import com.livingmods.worldgen.plan.PlannedSettlement;
import com.livingmods.worldgen.plan.WorldPlan;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class InitialStateFactory {
    private InitialStateFactory() {}

    public static CanonicalWorldState fromWorldPlan(WorldPlan plan) {
        return fromWorldPlan(plan, SimulationTime.ofTicks(0));
    }

    public static CanonicalWorldState fromWorldPlan(WorldPlan plan, SimulationTime startTime) {
        CanonicalWorldState state = new CanonicalWorldState(plan.seed(), startTime, plan.contentHash());
        DeterministicRandom rng = new DeterministicRandom(plan.seed());

        long citizenOrdinal = 0;
        long householdOrdinal = 0;

        for (PlannedSettlement planned : plan.settlements().values()) {
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
                    planned.tier().typicalPopulation() * 1.2,
                    Math.max(4, planned.plannedPopulation() / 4),
                    Math.max(2, planned.plannedPopulation() / 6)
            );
            state.putSettlement(settlement);
            state.stockpiles().put(planned.id(), initialStockpile(planned));
            state.markets().put(planned.id(), new MarketState(planned.id()));

            int pop = Math.max(1, planned.plannedPopulation());
            int householdSize = 4;
            int households = Math.max(1, (pop + householdSize - 1) / householdSize);
            for (int h = 0; h < households; h++) {
                HouseholdId hhId = HouseholdId.deterministic(plan.seed(), householdOrdinal++);
                state.putHousehold(new HouseholdState(hhId, planned.id(), 0, 10.0, 1));

                int members = Math.min(householdSize, pop);
                pop -= members;
                for (int m = 0; m < members; m++) {
                    CitizenId cid = CitizenId.deterministic(plan.seed(), citizenOrdinal++);
                    boolean female = rng.nextBoolean();
                    Profession profession = pickProfession(rng, m == 0 && h == 0);
                    int age = rng.nextInt(8, 55);
                    long birthDay = Math.max(0L, startTime.dayIndex() - age * (long) SimulationTime.DAYS_PER_YEAR);
                    SimulationTime birth = SimulationTime.ofDays(birthDay);
                    CitizenState citizen = new CitizenState(
                            cid,
                            rng.chance(0.5) ? "Alden" : "Bryn",
                            planned.name().split(" ")[0],
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
                    state.putCitizen(citizen);
                    state.households().get(hhId).setMemberCount(
                            state.households().get(hhId).memberCount() + 1);
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
            state.kingdoms().put(pk.id(), kingdom);

            CitizenState ruler = state.citizens().get(rulerId);
            if (ruler != null) {
                ruler.setProfession(Profession.RULER);
                ruler.setRuler(true);
            }
            SettlementState capital = state.settlements().get(pk.capitalId());
            if (capital != null) {
                capital.setRulerId(rulerId);
            }

            ArmyId armyId = ArmyId.deterministic(plan.seed(), state.armies().size());
            state.armies().put(armyId, new ArmyState(
                    armyId, pk.id(), pk.capitalCenter(), Math.max(20, pk.settlementIds().size() * 10)));

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
                state.diplomacy().setRelation(kingdomIds.get(i), kingdomIds.get(j), DiplomaticRelation.NEUTRAL);
            }
        }

        state.rebuildIndexes();
        return state;
    }

    private static StockpileState initialStockpile(PlannedSettlement planned) {
        StockpileState sp = new StockpileState(planned.id());
        int pop = Math.max(1, planned.plannedPopulation());
        sp.set(ResourceType.GRAIN, pop * 2.0);
        sp.set(ResourceType.WOOD, pop * 0.5);
        sp.set(ResourceType.TOOLS, pop * 0.1);
        return sp;
    }

    private static Profession pickProfession(DeterministicRandom rng, boolean leaderCandidate) {
        if (leaderCandidate) return Profession.NOBLE;
        return rng.pick(List.of(
                Profession.FARMER, Profession.FARMER, Profession.TRADER, Profession.GUARD, Profession.BUILDER));
    }

    private static CitizenId pickRuler(CanonicalWorldState state, PlannedKingdom pk) {
        for (SettlementId sid : pk.settlementIds()) {
            if (!sid.equals(pk.capitalId())) continue;
            for (CitizenState c : state.citizens().values()) {
                if (c.settlementId().equals(sid) && c.alive()) {
                    return c.id();
                }
            }
        }
        return state.citizens().keySet().iterator().next();
    }
}
