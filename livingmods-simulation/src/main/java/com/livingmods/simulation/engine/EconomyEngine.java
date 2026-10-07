package com.livingmods.simulation.engine;

import com.livingmods.common.id.CitizenId;
import com.livingmods.common.model.Profession;
import com.livingmods.common.model.ResourceType;
import com.livingmods.simulation.CanonicalWorldState;
import com.livingmods.simulation.state.CitizenState;
import com.livingmods.simulation.state.KingdomState;
import com.livingmods.simulation.state.MarketState;
import com.livingmods.simulation.state.SettlementState;
import com.livingmods.simulation.state.StockpileState;
import com.livingmods.simulation.state.WarState;
import com.livingmods.simulation.tick.RegionalWork;
import com.livingmods.simulation.tick.SimulationContext;

import java.util.EnumMap;
import java.util.Map;

/**
 * Multi-category production, consumption, and market pricing.
 * Chains: iron ore → smelting/blacksmith → tools/weapons; grain → mill/bakery → food.
 */
public final class EconomyEngine implements SimulationSubsystem {
    private static final double FOOD_CONSUMPTION = 0.12;
    private static final double GRAIN_CONSUMPTION = 0.05;

    @Override
    public String name() { return "economy"; }

    @Override
    public void phase1Regional(CanonicalWorldState state, RegionalWork work, SimulationContext ctx) {
        if (!ctx.schedule().runMarkets()) return;

        for (SettlementState settlement : state.settlements().values()) {
            if (!settlement.region().equals(work.region())) continue;

            StockpileState stock = state.stockpiles().get(settlement.id());
            MarketState market = state.markets().get(settlement.id());
            if (stock == null || market == null) continue;

            int population = 0;
            int farmers = 0;
            int miners = 0;
            int lumberjacks = 0;
            int fishers = 0;
            int blacksmiths = 0;
            int artisans = 0;
            int healers = 0;
            int builders = 0;
            int workers = 0;

            for (CitizenId cid : state.citizensInSettlement(settlement.id())) {
                CitizenState c = state.citizens().get(cid);
                if (c == null || !c.alive()) continue;
                population++;
                if (!c.canWork()) continue;
                workers++;
                switch (c.profession()) {
                    case FARMER -> farmers++;
                    case MINER -> miners++;
                    case LUMBERJACK -> lumberjacks++;
                    case FISHER -> fishers++;
                    case BLACKSMITH -> blacksmiths++;
                    case ARTISAN, BUTCHER -> artisans++;
                    case HEALER -> healers++;
                    case BUILDER -> builders++;
                    default -> {}
                }
            }

            Map<ResourceType, Double> prod = new EnumMap<>(ResourceType.class);
            Map<ResourceType, Double> cons = new EnumMap<>(ResourceType.class);
            for (ResourceType t : ResourceType.values()) {
                prod.put(t, 0.0);
                cons.put(t, 0.0);
            }

            // Primary extraction (technology multipliers apply to grain / livestock)
            double agriTech = TechnologyEngine.productionMultiplier(state, settlement, ResourceType.GRAIN);
            prod.put(ResourceType.GRAIN, farmers * 2.0 * agriTech);
            prod.put(ResourceType.LIVESTOCK, farmers * 0.15);
            prod.put(ResourceType.FISH, fishers * 1.5);
            prod.put(ResourceType.WOOD, lumberjacks * 1.8);
            prod.put(ResourceType.STONE, miners * 0.8);
            prod.put(ResourceType.IRON_ORE, miners * 1.0);
            prod.put(ResourceType.COAL, miners * 0.6);
            prod.put(ResourceType.FUEL, miners * 0.5);
            prod.put(ResourceType.TEXTILES, artisans * 0.4);
            prod.put(ResourceType.CLOTH, artisans * 0.3);
            prod.put(ResourceType.MEDICINE, healers * 0.5);
            prod.put(ResourceType.CONSTRUCTION, builders * 0.7 + lumberjacks * 0.2 + miners * 0.2);
            prod.put(ResourceType.LUXURY, artisans * 0.05);

            // Grain → mill/bakery → food (artisans act as millers/bakers)
            double grainAvailable = stock.get(ResourceType.GRAIN) + prod.get(ResourceType.GRAIN);
            double millCapacity = artisans * 1.2;
            double milled = Math.min(grainAvailable * 0.4, millCapacity);
            prod.put(ResourceType.FOOD, milled * 0.9);
            cons.put(ResourceType.GRAIN, cons.get(ResourceType.GRAIN) + milled);

            // Iron ore → smelting (needs fuel) → iron → tools/weapons
            double oreAvail = stock.get(ResourceType.IRON_ORE) + prod.get(ResourceType.IRON_ORE);
            double fuelAvail = stock.get(ResourceType.FUEL) + stock.get(ResourceType.COAL)
                    + prod.get(ResourceType.FUEL) + prod.get(ResourceType.COAL);
            double smelt = Math.min(oreAvail, Math.min(fuelAvail * 0.5, blacksmiths * 1.0));
            prod.put(ResourceType.IRON, smelt * 0.8);
            cons.put(ResourceType.IRON_ORE, cons.get(ResourceType.IRON_ORE) + smelt);
            cons.put(ResourceType.FUEL, cons.get(ResourceType.FUEL) + smelt * 0.4);

            double ironAvail = stock.get(ResourceType.IRON) + prod.get(ResourceType.IRON);
            double forge = Math.min(ironAvail, blacksmiths * 0.8);
            double toolsOut = forge * 0.55;
            double weaponsOut = forge * 0.35;
            prod.put(ResourceType.TOOLS, toolsOut);
            prod.put(ResourceType.WEAPONS, weaponsOut);
            cons.put(ResourceType.IRON, cons.get(ResourceType.IRON) + forge);

            // Population consumption
            double foodNeed = population * FOOD_CONSUMPTION;
            double grainNeed = population * GRAIN_CONSUMPTION;
            cons.put(ResourceType.FOOD, cons.get(ResourceType.FOOD) + foodNeed);
            cons.put(ResourceType.GRAIN, cons.get(ResourceType.GRAIN) + grainNeed);
            cons.put(ResourceType.FISH, cons.get(ResourceType.FISH) + population * 0.02);
            cons.put(ResourceType.MEAT, cons.get(ResourceType.MEAT) + population * 0.02);
            cons.put(ResourceType.WOOD, cons.get(ResourceType.WOOD) + population * 0.01);
            cons.put(ResourceType.MEDICINE, cons.get(ResourceType.MEDICINE) + population * 0.005);

            KingdomState kingdom = settlement.ownerKingdom()
                    .map(id -> state.kingdoms().get(id))
                    .orElse(null);
            double tax = kingdom != null ? kingdom.taxRate() : 0.1;
            double war = warPressure(state, settlement);
            double disease = market.diseasePressure();
            if (disease <= 0) {
                disease = state.epidemics().values().stream()
                        .anyMatch(e -> e.active() && e.affectedSettlements().contains(settlement.id()))
                        ? 0.4 : 0.0;
            }

            final int pop = population;
            final int workerCount = workers;
            final double taxPressure = tax;
            final double warPressure = war;
            final double diseasePressure = disease;
            final Map<ResourceType, Double> prodFinal = new EnumMap<>(prod);
            final Map<ResourceType, Double> consFinal = new EnumMap<>(cons);

            work.enqueueCommit(() -> {
                double hunger = 0;
                for (ResourceType t : ResourceType.values()) {
                    double p = prodFinal.get(t);
                    double c = consFinal.get(t);
                    double next = stock.get(t) + p - c;
                    if (t == ResourceType.FOOD || t == ResourceType.GRAIN) {
                        if (next < 0) {
                            hunger = Math.max(hunger, Math.min(1.0, -next / Math.max(1, pop * 0.2)));
                            next = 0;
                        }
                    }
                    stock.set(t, next);

                    double stockLevel = stock.get(t);
                    double scarcity = stockLevel < pop * 0.3
                            ? (pop * 0.3 - stockLevel) / Math.max(1, pop)
                            : 0.0;
                    double flowImbalance = (c - p) / Math.max(1.0, p + c + 1.0);
                    double importExport = (market.exports().getOrDefault(t, 0.0)
                            - market.imports().getOrDefault(t, 0.0)) / Math.max(1.0, pop);
                    double price = MarketState.basePrice(t)
                            * (1.0 + scarcity * 2.2 + flowImbalance * 0.8)
                            * market.transportCostFactor()
                            * (1.0 + taxPressure * 0.5)
                            * (1.0 + market.riskPressure() * 0.4)
                            * (1.0 + warPressure * 0.5)
                            * (1.0 + diseasePressure * 0.35)
                            * (1.0 + Math.max(0, importExport) * 0.2);
                    market.setPrice(t, price);
                    market.setFlow(t, p, c,
                            market.imports().getOrDefault(t, 0.0) * 0.5,
                            market.exports().getOrDefault(t, 0.0) * 0.5);
                }

                double foodStock = stock.get(ResourceType.FOOD) + stock.get(ResourceType.GRAIN);
                double crisis = foodStock < pop * 0.5
                        ? Math.min(1.0, (pop * 0.5 - foodStock) / Math.max(1, pop))
                        : 0.0;
                market.setCrisisSeverity(crisis);
                market.setTaxPressure(taxPressure);
                market.setWarPressure(warPressure);
                market.setDiseasePressure(diseasePressure);
                settlement.setHunger(Math.max(hunger, crisis));
                if (workerCount > 0) {
                    settlement.setEmployedSlots(workerCount);
                }
            });
        }
    }

    private static double warPressure(CanonicalWorldState state, SettlementState settlement) {
        if (settlement.ownerKingdom().isEmpty()) return 0;
        var kid = settlement.ownerKingdom().get();
        for (WarState w : state.wars().values()) {
            if (w.active() && w.participants().contains(kid)) return 0.55;
        }
        return 0;
    }
}
