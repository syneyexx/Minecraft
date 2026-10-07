package com.livingmods.simulation.engine;

import com.livingmods.common.geo.RegionCoord;
import com.livingmods.common.id.CitizenId;
import com.livingmods.common.model.Profession;
import com.livingmods.common.model.ResourceType;
import com.livingmods.common.model.SpeciesArchetype;
import com.livingmods.common.model.SpeciesDefinition;
import com.livingmods.simulation.CanonicalWorldState;
import com.livingmods.simulation.state.CitizenState;
import com.livingmods.simulation.state.EcologyState;
import com.livingmods.simulation.state.SettlementState;
import com.livingmods.simulation.state.StockpileState;
import com.livingmods.simulation.tick.RegionalWork;
import com.livingmods.simulation.tick.SimulationContext;

import java.util.HashMap;
import java.util.Map;

/**
 * Data-driven multi-species ecology replacing the 2-species Lotka–Volterra toy.
 * Human agriculture/hunting/settlement pressure wildlife; wildlife feeds hunting and danger.
 */
public final class EcologyEngine implements SimulationSubsystem {
    @Override
    public String name() { return "ecology"; }

    @Override
    public void phase1Regional(CanonicalWorldState state, RegionalWork work, SimulationContext ctx) {
        if (!ctx.schedule().runDemography()) return;

        RegionCoord region = work.region();
        EcologyState ecology = state.ecology();

        int hunters = 0;
        int farmers = 0;
        int settlementsInRegion = 0;
        double grainDemand = 0.0;
        for (SettlementState s : state.settlements().values()) {
            if (!s.region().equals(region)) continue;
            settlementsInRegion++;
            for (CitizenId cid : state.citizensInSettlement(s.id())) {
                CitizenState c = state.citizens().get(cid);
                if (c == null || !c.alive()) continue;
                if (c.profession() == Profession.HUNTER) hunters++;
                if (c.profession() == Profession.FARMER) farmers++;
            }
        }
        double huntPressure = hunters * 0.08 + settlementsInRegion * 0.02;
        double agriPressure = farmers * 0.05 + settlementsInRegion * 0.03;
        ecology.setHuntingPressure(region, huntPressure);
        ecology.setAgriculturePressure(region, agriPressure);

        Map<String, Double> nextPop = new HashMap<>();
        Map<String, EcologyState.SpeciesCohort> cohorts = new HashMap<>();
        for (SpeciesDefinition def : SpeciesDefinition.catalog()) {
            EcologyState.SpeciesCohort cohort = ecology.cohort(region, def.id());
            cohorts.put(def.key(), cohort);
            nextPop.put(def.key(), cohort.population());
        }

        for (SpeciesDefinition def : SpeciesDefinition.catalog()) {
            double pop = nextPop.get(def.key());
            double capacity = def.carryingCapacity() * (1.0 - Math.min(0.7, agriPressure * 0.4));
            if (def.archetype() == SpeciesArchetype.LIVESTOCK) {
                capacity = def.carryingCapacity() * (1.0 + farmers * 0.05);
            }
            if (def.archetype() == SpeciesArchetype.AQUATIC && settlementsInRegion > 2) {
                capacity *= 0.9;
            }

            double growth = pop * def.reproductionRate() * (1.0 - pop / Math.max(1.0, capacity));
            double naturalDeath = pop * def.mortalityRate();

            double predationLoss = 0.0;
            for (String predatorKey : def.predatorKeys()) {
                double predators = nextPop.getOrDefault(predatorKey, 0.0);
                predationLoss += predators * 0.04;
            }
            double huntingLoss = 0.0;
            if (def.archetype() != SpeciesArchetype.LIVESTOCK || ctx.random().chance(0.2)) {
                huntingLoss = huntPressure * (def.archetype() == SpeciesArchetype.SMALL_GAME ? 1.4
                        : def.archetype() == SpeciesArchetype.HERBIVORE ? 1.0
                        : def.archetype() == SpeciesArchetype.BIRD ? 0.6
                        : 0.3);
            }

            // Predators gain from prey.
            double predationGain = 0.0;
            if (def.archetype() == SpeciesArchetype.PREDATOR) {
                for (String preyKey : def.preyKeys()) {
                    predationGain += nextPop.getOrDefault(preyKey, 0.0) * 0.01;
                }
            }

            double migrated = 0.0;
            if (def.migrates() && pop > capacity * 0.9) {
                migrated = pop * 0.02;
            }

            double updated = Math.max(0, pop + growth - naturalDeath - predationLoss - huntingLoss + predationGain - migrated);
            nextPop.put(def.key(), updated);
            grainDemand += huntingLoss * meatYield(def);
        }

        final double meatFromHunt = grainDemand;
        final int hunterCount = hunters;
        final int settlementsInRegionFinal = settlementsInRegion;
        work.enqueueCommit(() -> {
            for (SpeciesDefinition def : SpeciesDefinition.catalog()) {
                EcologyState.SpeciesCohort cohort = cohorts.get(def.key());
                double pop = nextPop.get(def.key());
                cohort.setPopulation(pop);
                cohort.setBiomass(pop * biomassPerCapita(def));
            }
            // Hunting yields food into regional settlement stockpiles.
            if (meatFromHunt > 0 && hunterCount > 0) {
                for (SettlementState s : state.settlements().values()) {
                    if (!s.region().equals(region)) continue;
                    StockpileState stock = state.stockpiles().get(s.id());
                    if (stock != null) {
                        stock.add(ResourceType.MEAT, meatFromHunt / Math.max(1, settlementsInRegionFinal));
                    }
                    // Predators / dense wildlife increase danger → slight unrest / security hit.
                    EcologyState.SpeciesCohort wolves = ecology.cohort(region,
                            SpeciesDefinition.byKey("wolf").id());
                    if (wolves.population() > 25) {
                        s.setSecurity(s.security() - 0.002);
                    }
                }
            }
        });
    }

    private static double meatYield(SpeciesDefinition def) {
        return switch (def.archetype()) {
            case HERBIVORE -> 0.35;
            case SMALL_GAME -> 0.12;
            case BIRD -> 0.08;
            case AQUATIC -> 0.2;
            case LIVESTOCK -> 0.4;
            case PREDATOR -> 0.05;
        };
    }

    private static double biomassPerCapita(SpeciesDefinition def) {
        return switch (def.archetype()) {
            case PREDATOR -> 2.0;
            case LIVESTOCK -> 2.5;
            case HERBIVORE -> 1.5;
            case AQUATIC -> 0.8;
            case BIRD -> 0.5;
            case SMALL_GAME -> 0.35;
        };
    }
}
