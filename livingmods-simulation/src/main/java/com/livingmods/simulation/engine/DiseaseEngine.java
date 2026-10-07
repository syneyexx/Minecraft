package com.livingmods.simulation.engine;

import com.livingmods.common.event.CivilizationEventType;
import com.livingmods.common.event.HistoricalEvent;
import com.livingmods.common.id.EpidemicId;
import com.livingmods.common.id.HistoricalEventId;
import com.livingmods.common.id.SettlementId;
import com.livingmods.common.model.DiseaseDefinition;
import com.livingmods.common.model.Profession;
import com.livingmods.common.model.ResourceType;
import com.livingmods.simulation.CanonicalWorldState;
import com.livingmods.simulation.state.CitizenState;
import com.livingmods.simulation.state.EpidemicState;
import com.livingmods.simulation.state.MarketState;
import com.livingmods.simulation.state.SettlementState;
import com.livingmods.simulation.state.StockpileState;
import com.livingmods.simulation.tick.RegionalWork;
import com.livingmods.simulation.tick.SimulationContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Disease with infectivity, severity, mortality, duration, immunity.
 * Risk rises with density, poor sanitation proxies, and trade; healers/clinics
 * and medicine stock reduce impact. Health always clamped 0..100 via CitizenState.
 */
public final class DiseaseEngine implements SimulationSubsystem {
    @Override
    public String name() { return "disease"; }

    @Override
    public void phase1Regional(CanonicalWorldState state, RegionalWork work, SimulationContext ctx) {
        if (!ctx.schedule().runDemography()) return;

        List<InfectRecord> infections = new ArrayList<>();
        List<HealRecord> heals = new ArrayList<>();

        for (EpidemicState ep : state.epidemics().values()) {
            if (!ep.active()) continue;
            DiseaseDefinition def = DiseaseDefinition.byKey(ep.pathogenKey());
            for (SettlementId sid : ep.affectedSettlements()) {
                SettlementState settlement = state.settlements().get(sid);
                if (settlement == null || !settlement.region().equals(work.region())) continue;

                int pop = 0;
                int healers = 0;
                int infected = 0;
                for (CitizenState c : state.citizens().values()) {
                    if (!c.alive() || !c.settlementId().equals(sid)) continue;
                    pop++;
                    if (c.profession() == Profession.HEALER) healers++;
                    if (def.key().equals(c.knownDiseaseKey()) && c.immunityUntilDay() <= ctx.time().dayIndex()) {
                        infected++;
                    }
                }
                double density = pop / Math.max(1.0, settlement.physicalCapacity());
                StockpileState stock = state.stockpiles().get(sid);
                double medicine = stock == null ? 0.0 : stock.get(ResourceType.MEDICINE);
                double sanitation = 1.0 - Math.min(1.0, settlement.hunger() * 0.5 + settlement.unrest() * 0.3);
                boolean tradeExposed = !state.shipments().isEmpty();
                double healthcare = Math.min(1.0, healers * 0.15 + Math.min(1.0, medicine / 20.0) * 0.5);
                double risk = def.infectivity()
                        * (1.0 + density * def.densitySensitivity())
                        * (1.0 + (1.0 - sanitation) * def.sanitationSensitivity())
                        * (tradeExposed ? 1.0 + def.tradeSensitivity() : 1.0)
                        * (1.0 - healthcare * def.healthcareMitigation());

                for (CitizenState c : state.citizens().values()) {
                    if (!c.alive() || !c.settlementId().equals(sid)) continue;
                    if (c.immuneTo(def.key(), ctx.time().dayIndex())) continue;
                    boolean currentlySick = def.key().equals(c.knownDiseaseKey())
                            && c.immunityUntilDay() > ctx.time().dayIndex();
                    // Use knownDiseaseKey + temporary negative-immunity window as "sick" marker:
                    // sick citizens have knownDiseaseKey set and immunityUntilDay in the near future
                    // while health is still dropping; after recovery immunityUntilDay is extended.
                    if (c.knownDiseaseKey() != null && c.knownDiseaseKey().equals(def.key())
                            && c.health() < 85 && !c.immuneTo(def.key(), ctx.time().dayIndex() + 1)) {
                        // Treat as active infection if health depressed and disease tagged.
                        infections.add(new InfectRecord(c.id(), def.key(), true, ep.mortalityRate(), def.severity(), medicine > 0));
                    } else if (ctx.random().chance(risk * 0.08)) {
                        infections.add(new InfectRecord(c.id(), def.key(), false, ep.mortalityRate(), def.severity(), false));
                    }
                }

                if (healers > 0 || medicine > 0) {
                    heals.add(new HealRecord(sid, healers, Math.min(2.0, medicine * 0.1)));
                }

                final int finalInfected = infected;
                work.enqueueCommit(() -> {
                    MarketState market = state.markets().get(sid);
                    if (market != null && finalInfected > 0) {
                        market.setCrisisSeverity(Math.min(1.0, market.crisisSeverity() + 0.02 * def.severity()));
                    }
                });
            }
        }

        work.enqueueCommit(() -> {
            for (InfectRecord rec : infections) {
                CitizenState c = state.citizens().get(rec.citizenId);
                if (c == null || !c.alive()) continue;
                if (!rec.alreadySick) {
                    c.setKnownDiseaseKey(rec.diseaseKey);
                    // Pending recovery window until health recovers; immunity applied on recovery.
                    c.setImmunityUntilDay(0);
                }
                DiseaseDefinition def = DiseaseDefinition.byKey(rec.diseaseKey);
                double damage = 3.0 + rec.severity * 8.0;
                if (rec.medicineAvailable) {
                    damage *= 0.55;
                }
                c.setHealth(c.health() - damage);
                if (ctx.random().chance(rec.mortality * (1.0 + (100 - c.health()) / 200.0))) {
                    c.setAlive(false);
                    continue;
                }
                if (c.health() > 70 || ctx.random().chance(0.15)) {
                    // Recover with immunity.
                    c.setImmunityUntilDay(ctx.time().dayIndex() + (long) def.immunityDays());
                    c.setKnownDiseaseKey(rec.diseaseKey);
                    c.setHealth(Math.min(100, c.health() + 5));
                }
            }
            for (HealRecord heal : heals) {
                StockpileState stock = state.stockpiles().get(heal.settlementId);
                if (stock != null && heal.medicineUsed > 0) {
                    stock.add(ResourceType.MEDICINE, -heal.medicineUsed);
                }
                for (CitizenState c : state.citizens().values()) {
                    if (!c.alive() || !c.settlementId().equals(heal.settlementId)) continue;
                    if (c.health() < 90) {
                        c.setHealth(c.health() + 0.5 * heal.healers + heal.medicineUsed);
                    }
                }
            }
            for (EpidemicState ep : state.epidemics().values()) {
                if (!ep.active()) continue;
                ep.tickHour();
                if (ep.remainingHours() <= 0) {
                    ep.setActive(false);
                    state.appendHistory(new HistoricalEvent(
                            HistoricalEventId.deterministic(state.seed(), state.history().size()),
                            CivilizationEventType.EPIDEMIC_ENDED,
                            ctx.time(),
                            "Epidemic ended",
                            ep.pathogenKey() + " fades",
                            Optional.empty(),
                            Map.of("epidemic", ep.id().toString())
                    ));
                }
            }
        });
    }

    @Override
    public void phase2Global(CanonicalWorldState state, SimulationContext ctx) {
        if (!ctx.schedule().runDemography()) return;

        // Spread along trade / proximity.
        for (EpidemicState ep : List.copyOf(state.epidemics().values())) {
            if (!ep.active()) continue;
            for (SettlementState s : state.settlements().values()) {
                if (ep.affectedSettlements().contains(s.id())) continue;
                boolean near = false;
                for (SettlementId sid : ep.affectedSettlements()) {
                    SettlementState origin = state.settlements().get(sid);
                    if (origin != null && origin.center().distanceTo(s.center()) < 512) {
                        near = true;
                        break;
                    }
                }
                if (near && ctx.random().chance(0.03)) {
                    ep.affectedSettlements().add(s.id());
                }
            }
        }

        long active = state.epidemics().values().stream().filter(EpidemicState::active).count();
        if (active > 2 || state.settlements().isEmpty()) return;
        if (!ctx.random().chance(0.0008)) return;

        List<SettlementState> settlements = new ArrayList<>(state.settlements().values());
        SettlementState origin = settlements.get(ctx.random().nextInt(settlements.size()));
        List<DiseaseDefinition> catalog = DiseaseDefinition.catalog();
        DiseaseDefinition def = catalog.get(ctx.random().nextInt(catalog.size()));
        EpidemicId id = EpidemicId.deterministic(state.seed(), state.epidemics().size());
        EpidemicState ep = new EpidemicState(id, def.key(), origin.id(), ctx.time().dayIndex());
        state.epidemics().put(id, ep);
        state.appendHistory(new HistoricalEvent(
                HistoricalEventId.deterministic(state.seed(), state.history().size()),
                CivilizationEventType.EPIDEMIC_STARTED,
                ctx.time(),
                "Epidemic",
                def.displayName() + " outbreak in " + origin.name(),
                Optional.of(origin.center()),
                Map.of("epidemic", id.toString(), "pathogen", def.key())
        ));
    }

    private record InfectRecord(
            com.livingmods.common.id.CitizenId citizenId,
            String diseaseKey,
            boolean alreadySick,
            double mortality,
            double severity,
            boolean medicineAvailable
    ) {}

    private record HealRecord(SettlementId settlementId, int healers, double medicineUsed) {}
}
