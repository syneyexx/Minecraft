package com.livingmods.simulation.engine;

import com.livingmods.common.event.CivilizationEventType;
import com.livingmods.common.event.HistoricalEvent;
import com.livingmods.common.geo.BlockPos2;
import com.livingmods.common.id.CitizenId;
import com.livingmods.common.id.HistoricalEventId;
import com.livingmods.common.id.HouseholdId;
import com.livingmods.common.id.MigrationGroupId;
import com.livingmods.common.id.SettlementId;
import com.livingmods.common.model.Profession;
import com.livingmods.common.model.ResourceType;
import com.livingmods.common.model.SettlementRole;
import com.livingmods.common.model.SettlementTier;
import com.livingmods.simulation.CanonicalWorldState;
import com.livingmods.simulation.state.CitizenState;
import com.livingmods.simulation.state.HouseholdState;
import com.livingmods.simulation.state.MarketState;
import com.livingmods.simulation.state.MigrationGroupState;
import com.livingmods.simulation.state.SettlementState;
import com.livingmods.simulation.state.StockpileState;
import com.livingmods.simulation.tick.RegionalWork;
import com.livingmods.simulation.tick.SimulationContext;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Migrates actual households/citizens. On arrival: settlement assignment, housing/job demand.
 * Refugees may be absorbed, camp temporarily, return, or found a settlement.
 */
public final class MigrationEngine implements SimulationSubsystem {
    @Override
    public String name() { return "migration"; }

    @Override
    public void phase1Regional(CanonicalWorldState state, RegionalWork work, SimulationContext ctx) {
        if (!ctx.schedule().runDemography()) return;

        List<MigrationGroupState> groups = new ArrayList<>();
        for (MigrationGroupState g : state.migrations().values()) {
            if (g.arrived() || g.outcome() != MigrationGroupState.Outcome.IN_TRANSIT) continue;
            SettlementState s = state.settlements().get(g.source());
            if (s != null && s.region().equals(work.region())) {
                groups.add(g);
            } else if (s == null) {
                groups.add(g);
            }
        }
        groups.sort(Comparator.comparing(g -> g.id().value()));

        for (MigrationGroupState g : groups) {
            SettlementState dest = state.settlements().get(g.destination());
            BlockPos2 target = dest != null ? dest.center() : g.position().add(64, 0);
            BlockPos2 cur = g.position();
            int stepX = Integer.compare(target.x(), cur.x());
            int stepZ = Integer.compare(target.z(), cur.z());
            work.enqueueCommit(() -> {
                g.setPosition(cur.add(stepX * 24, stepZ * 24));
                if (g.position().distanceTo(target) < 40) {
                    resolveArrival(state, g, ctx);
                }
            });
        }
    }

    @Override
    public void phase2Global(CanonicalWorldState state, SimulationContext ctx) {
        if (!ctx.schedule().runDemography()) return;

        List<SettlementState> settlements = new ArrayList<>(state.settlements().values());
        settlements.sort(Comparator.comparing(s -> s.id().value()));

        for (SettlementState source : settlements) {
            StockpileState sp = state.stockpiles().get(source.id());
            MarketState m = state.markets().get(source.id());
            boolean famine = (sp != null && sp.get(ResourceType.GRAIN) < 5)
                    || (m != null && m.crisisSeverity() > 0.55)
                    || source.hunger() > 0.55;
            boolean warZone = state.wars().values().stream().anyMatch(war ->
                    war.active() && source.ownerKingdom().map(k -> war.participants().contains(k)).orElse(false));
            int living = 0;
            for (CitizenId cid : state.citizensInSettlement(source.id())) {
                CitizenState c = state.citizens().get(cid);
                if (c != null && c.alive()) living++;
            }
            boolean overcrowded = living > source.physicalCapacity() * 1.05;
            if (overcrowded) {
                source.setDevelopmentDeficit(source.developmentDeficit() + 0.15);
                source.setHunger(Math.min(1.0, source.hunger() + 0.05));
            }
            if (!famine && !warZone && !overcrowded) continue;
            if (ctx.random().chance(overcrowded ? 0.7 : 0.85)) continue;

            SettlementState dest = settlements.stream()
                    .filter(s -> !s.id().equals(source.id()))
                    .filter(s -> {
                        StockpileState dsp = state.stockpiles().get(s.id());
                        if (dsp == null || dsp.get(ResourceType.GRAIN) <= 20 || s.hunger() >= 0.3) {
                            return false;
                        }
                        // Player realm migrationOpen policy gates acceptance attractiveness.
                        return migrationOpenFor(state, s);
                    })
                    .min(Comparator.comparingDouble(s -> s.center().distanceTo(source.center())))
                    .orElse(null);
            if (dest == null && warZone) {
                // Founding path when nowhere safe.
                dest = null;
            } else if (dest == null) {
                continue;
            }

            List<HouseholdId> movingHouseholds = pickHouseholds(state, source.id(), 2);
            if (movingHouseholds.isEmpty()) continue;

            MigrationGroupId id = MigrationGroupId.deterministic(state.seed(), state.migrations().size());
            String reason = famine ? "famine" : overcrowded ? "overcrowding" : "war_refugees";
            SettlementId destinationId = dest != null ? dest.id() : SettlementId.deterministic(
                    state.seed(), 50_000L + state.migrations().size());
            MigrationGroupState group = new MigrationGroupState(
                    id, source.id(), destinationId, 0, source.center(), reason);
            group.setRefugee(true);
            int pop = 0;
            for (HouseholdId hhId : movingHouseholds) {
                group.householdIds().add(hhId);
                for (CitizenId cid : state.citizensByHousehold().getOrDefault(hhId, java.util.Set.of())) {
                    CitizenState c = state.citizens().get(cid);
                    if (c != null && c.alive()) {
                        group.citizenIds().add(cid);
                        pop++;
                    }
                }
            }
            if (pop == 0) continue;
            // Rebuild with population via reflection-free approach: population is final — store via citizens size.
            MigrationGroupState tracked = new MigrationGroupState(
                    id, source.id(), destinationId, pop, source.center(), reason);
            tracked.setRefugee(true);
            tracked.householdIds().addAll(group.householdIds());
            tracked.citizenIds().addAll(group.citizenIds());
            if (dest == null) {
                tracked.setOutcome(MigrationGroupState.Outcome.FOUNDING);
            }
            state.migrations().put(id, tracked);
            state.appendHistory(new HistoricalEvent(
                    HistoricalEventId.deterministic(state.seed(), state.history().size()),
                    CivilizationEventType.MIGRATION_STARTED,
                    ctx.time(),
                    "Migration",
                    pop + " people leave " + source.name() + " (" + reason + ")",
                    Optional.of(source.center()),
                    Map.of("source", source.id().toString(), "reason", reason)
            ));
            state.appendHistory(new HistoricalEvent(
                    HistoricalEventId.deterministic(state.seed(), state.history().size()),
                    CivilizationEventType.REFUGEE_GROUP_ENTERING_REGION,
                    ctx.time(),
                    "Refugees",
                    "Refugee column on the road from " + source.name(),
                    Optional.of(source.center()),
                    Map.of("migration", id.toString())
            ));
            break;
        }
    }

    private static void resolveArrival(CanonicalWorldState state, MigrationGroupState g, SimulationContext ctx) {
        SettlementState dest = state.settlements().get(g.destination());
        MigrationGroupState.Outcome outcome;
        if (g.outcome() == MigrationGroupState.Outcome.FOUNDING || dest == null) {
            outcome = MigrationGroupState.Outcome.FOUNDING;
        } else if (dest.housingUnits() < 2 || dest.hunger() > 0.5) {
            outcome = ctx.random().chance(0.4)
                    ? MigrationGroupState.Outcome.TEMP_CAMP
                    : MigrationGroupState.Outcome.RETURNING;
        } else {
            outcome = MigrationGroupState.Outcome.ABSORBED;
        }

        g.setArrived(true);
        g.setOutcome(outcome);

        switch (outcome) {
            case ABSORBED -> absorb(state, g, dest);
            case TEMP_CAMP -> {
                if (dest != null) {
                    dest.setHousingUnits(dest.housingUnits() + 1);
                    dest.setUnrest(dest.unrest() + 0.05);
                    // Soft absorb into camp housing.
                    absorb(state, g, dest);
                }
            }
            case RETURNING -> {
                SettlementState source = state.settlements().get(g.source());
                if (source != null) {
                    g.setDestination(source.id());
                    g.setArrived(false);
                    g.setOutcome(MigrationGroupState.Outcome.IN_TRANSIT);
                    g.setRefugee(false);
                }
            }
            case FOUNDING -> foundSettlement(state, g, ctx);
            default -> {
            }
        }
    }

    private static void absorb(CanonicalWorldState state, MigrationGroupState g, SettlementState dest) {
        if (dest == null) return;
        dest.setHousingUnits(dest.housingUnits() + Math.max(1, g.population() / 4));
        dest.setEmployedSlots(dest.employedSlots() + Math.max(1, g.population() / 5));
        for (HouseholdId hhId : g.householdIds()) {
            HouseholdState hh = state.households().get(hhId);
            if (hh != null) {
                hh.setSettlementId(dest.id());
            }
        }
        for (CitizenId cid : g.citizenIds()) {
            CitizenState c = state.citizens().get(cid);
            if (c == null || !c.alive()) continue;
            HouseholdId hh = c.householdId();
            state.transferCitizen(c, dest.id(), hh);
            if (c.profession() == Profession.UNEMPLOYED) {
                c.setProfession(Profession.FARMER);
            }
        }
    }

    private static void foundSettlement(CanonicalWorldState state, MigrationGroupState g, SimulationContext ctx) {
        SettlementId newId = g.destination();
        if (state.settlements().containsKey(newId)) {
            absorb(state, g, state.settlements().get(newId));
            return;
        }
        SettlementState founded = new SettlementState(
                newId,
                "Newhaven",
                SettlementTier.HAMLET,
                SettlementRole.FARMING,
                g.position(),
                Optional.empty(),
                false,
                g.citizenIds().isEmpty() ? null : g.citizenIds().getFirst(),
                55.0,
                2.0,
                Math.max(8, g.population() * 1.5),
                Math.max(2, g.population() / 3),
                Math.max(1, g.population() / 4)
        );
        state.putSettlement(founded);
        state.stockpiles().put(newId, new StockpileState(newId));
        state.markets().put(newId, new MarketState(newId));
        StockpileState stock = state.stockpiles().get(newId);
        if (stock != null) {
            stock.set(ResourceType.GRAIN, 15);
            stock.set(ResourceType.WOOD, 10);
        }
        absorb(state, g, founded);
        state.appendHistory(new HistoricalEvent(
                HistoricalEventId.deterministic(state.seed(), state.history().size()),
                CivilizationEventType.SETTLEMENT_FOUNDED,
                ctx.time(),
                "Settlement founded",
                "Refugees found " + founded.name(),
                Optional.of(founded.center()),
                Map.of("settlement", newId.toString())
        ));
    }

    private static List<HouseholdId> pickHouseholds(CanonicalWorldState state, SettlementId sid, int limit) {
        List<HouseholdId> list = new ArrayList<>();
        for (HouseholdState hh : state.households().values()) {
            if (!hh.settlementId().equals(sid) || hh.orphanage()) continue;
            if (hh.memberCount() <= 0) continue;
            list.add(hh.id());
            if (list.size() >= limit) break;
        }
        return list;
    }

    /** Player migrationOpen policy — closed borders reduce destination attractiveness. */
    private static boolean migrationOpenFor(CanonicalWorldState state, SettlementState settlement) {
        if (settlement.ownerKingdom().isEmpty()) return true;
        var kingdomId = settlement.ownerKingdom().get();
        for (var e : state.playerReputation().ruledKingdoms().entrySet()) {
            if (kingdomId.equals(e.getValue())) {
                return state.playerReputation().policy(e.getKey(), "migrationOpen", 1.0) >= 0.5;
            }
        }
        return true;
    }
}
