package com.livingmods.simulation.engine;

import com.livingmods.common.model.EmergentTaskType;
import com.livingmods.common.model.ResourceType;
import com.livingmods.simulation.CanonicalWorldState;
import com.livingmods.simulation.state.EmergentTaskState;
import com.livingmods.simulation.state.MarketState;
import com.livingmods.simulation.state.SettlementState;
import com.livingmods.simulation.state.ShipmentState;
import com.livingmods.simulation.state.StockpileState;
import com.livingmods.simulation.tick.RegionalWork;
import com.livingmods.simulation.tick.SimulationContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Spawns and resolves player tasks from canonical problems — not a random quest pool. */
public final class EmergentTaskEngine implements SimulationSubsystem {
    @Override
    public String name() { return "emergent_tasks"; }

    @Override
    public void phase1Regional(CanonicalWorldState state, RegionalWork work, SimulationContext ctx) {}

    @Override
    public void phase2Global(CanonicalWorldState state, SimulationContext ctx) {
        if (!ctx.schedule().runGovernment()) return;

        // Complete tasks whose underlying problem is already resolved.
        for (EmergentTaskState task : state.emergentTasks().values()) {
            if (!task.open()) continue;
            if (problemResolved(state, task)) {
                task.setCompleted(true);
                applyCompletionEffects(state, task);
            }
        }

        long open = state.emergentTasks().values().stream().filter(EmergentTaskState::open).count();
        if (open >= 8) return;

        List<SettlementState> settlements = new ArrayList<>(state.settlements().values());
        for (SettlementState s : settlements) {
            StockpileState stock = state.stockpiles().get(s.id());
            MarketState market = state.markets().get(s.id());

            if (stock != null && stock.get(ResourceType.GRAIN) < 8) {
                offer(state, ctx, EmergentTaskType.FOOD_DELIVERY, s,
                        "Deliver grain to " + s.name(),
                        "Stores are nearly empty.",
                        Map.of("resource", "GRAIN", "amount", "20"));
            }
            if (stock != null && stock.get(ResourceType.MEDICINE) < 2
                    && state.epidemics().values().stream().anyMatch(e ->
                    e.active() && e.affectedSettlements().contains(s.id()))) {
                offer(state, ctx, EmergentTaskType.MEDICINE_DELIVERY, s,
                        "Bring medicine to " + s.name(),
                        "Healers lack supplies during the outbreak.",
                        Map.of("resource", "MEDICINE", "amount", "10"));
            }
            if (s.developmentDeficit() > 1.5) {
                offer(state, ctx, EmergentTaskType.CONSTRUCTION_RESOURCES, s,
                        "Haul stone and wood to " + s.name(),
                        "Builders cannot finish works in progress.",
                        Map.of("wood", "15", "stone", "15"));
            }
            if (s.security() < 0.25 || s.unrest() > 0.55) {
                offer(state, ctx, EmergentTaskType.BANDIT_REMOVAL, s,
                        "Clear bandits near " + s.name(),
                        "Roads are unsafe and trade falters.",
                        Map.of("securityTarget", "0.5"));
            }
            if (market != null && market.crisisSeverity() > 0.4) {
                offer(state, ctx, EmergentTaskType.ESCORT, s,
                        "Escort a relief caravan to " + s.name(),
                        "Merchants will not travel alone.",
                        Map.of("crisis", String.valueOf(market.crisisSeverity())));
            }
        }

        for (ShipmentState shipment : state.shipments().values()) {
            if (shipment.looted() || (!shipment.delivered() && shipment.risk() > 0.55)) {
                SettlementState dest = state.settlements().get(shipment.destination());
                if (dest == null) continue;
                offer(state, ctx, EmergentTaskType.MISSING_CARAVAN, dest,
                        "Find the missing caravan",
                        "A shipment bound for " + dest.name() + " never arrived.",
                        Map.of("shipment", shipment.id().toString()));
            }
        }

        if (state.wars().values().stream().anyMatch(w -> w.active())) {
            for (SettlementState s : settlements) {
                if (s.capital()) {
                    offer(state, ctx, EmergentTaskType.DIPLOMATIC_DELIVERY, s,
                            "Deliver sealed letters to " + s.name(),
                            "Envoys need a trusted courier.",
                            Map.of("diplomatic", "true"));
                    break;
                }
            }
        }
    }

    public boolean completeTask(CanonicalWorldState state, UUID taskId) {
        EmergentTaskState task = state.emergentTasks().get(taskId);
        if (task == null || !task.open()) return false;
        task.setCompleted(true);
        applyCompletionEffects(state, task);
        return true;
    }

    private static void offer(
            CanonicalWorldState state,
            SimulationContext ctx,
            EmergentTaskType type,
            SettlementState settlement,
            String title,
            String description,
            Map<String, String> tags
    ) {
        for (EmergentTaskState existing : state.emergentTasks().values()) {
            if (existing.open()
                    && existing.type() == type
                    && existing.settlementId().equals(settlement.id())) {
                return;
            }
        }
        UUID id = new UUID(state.seed() ^ type.ordinal() * 31L, settlement.id().value().getLeastSignificantBits()
                ^ state.emergentTasks().size());
        state.emergentTasks().put(id, new EmergentTaskState(
                id, type, settlement.id(), title, description, ctx.time(), tags));
    }

    private static boolean problemResolved(CanonicalWorldState state, EmergentTaskState task) {
        SettlementState s = state.settlements().get(task.settlementId());
        if (s == null) return true;
        StockpileState stock = state.stockpiles().get(s.id());
        return switch (task.type()) {
            case FOOD_DELIVERY -> stock != null && stock.get(ResourceType.GRAIN) >= 20;
            case MEDICINE_DELIVERY -> stock != null && stock.get(ResourceType.MEDICINE) >= 8;
            case CONSTRUCTION_RESOURCES -> s.developmentDeficit() < 0.5;
            case BANDIT_REMOVAL -> s.security() >= 0.45;
            case ESCORT -> {
                MarketState m = state.markets().get(s.id());
                yield m == null || m.crisisSeverity() < 0.2;
            }
            case MISSING_CARAVAN -> state.shipments().values().stream()
                    .noneMatch(sh -> sh.destination().equals(s.id()) && (sh.looted() || (!sh.delivered() && sh.risk() > 0.55)));
            case DIPLOMATIC_DELIVERY -> state.wars().values().stream().noneMatch(w -> w.active());
        };
    }

    private static void applyCompletionEffects(CanonicalWorldState state, EmergentTaskState task) {
        SettlementState s = state.settlements().get(task.settlementId());
        if (s == null) return;
        StockpileState stock = state.stockpiles().get(s.id());
        switch (task.type()) {
            case FOOD_DELIVERY -> {
                if (stock != null) stock.add(ResourceType.GRAIN, 20);
                s.setHunger(Math.max(0, s.hunger() - 0.2));
            }
            case MEDICINE_DELIVERY -> {
                if (stock != null) stock.add(ResourceType.MEDICINE, 10);
            }
            case CONSTRUCTION_RESOURCES -> {
                if (stock != null) {
                    stock.add(ResourceType.WOOD, 15);
                    stock.add(ResourceType.STONE, 15);
                }
                s.setDevelopmentDeficit(Math.max(0, s.developmentDeficit() - 1.0));
            }
            case BANDIT_REMOVAL -> {
                s.setSecurity(Math.min(1.0, s.security() + 0.25));
                s.setUnrest(Math.max(0, s.unrest() - 0.15));
            }
            case ESCORT -> {
                MarketState m = state.markets().get(s.id());
                if (m != null) m.setCrisisSeverity(Math.max(0, m.crisisSeverity() - 0.25));
            }
            case MISSING_CARAVAN -> {
                if (stock != null) stock.add(ResourceType.GRAIN, 8);
            }
            case DIPLOMATIC_DELIVERY -> s.setLegitimacy(Math.min(100, s.legitimacy() + 2));
        }
        s.ownerKingdom().ifPresent(k ->
                state.playerReputation().adjust(PlayerSystemsEngine.demoPlayer(), k, 0.08));
    }
}
