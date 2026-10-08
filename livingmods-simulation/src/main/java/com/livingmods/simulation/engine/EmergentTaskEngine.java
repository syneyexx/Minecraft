package com.livingmods.simulation.engine;

import com.livingmods.common.id.PlayerId;
import com.livingmods.common.id.ShipmentId;
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
import java.util.Locale;
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

        // Auto-complete only when the underlying problem is resolved by simulation itself
        // (not player claims). Player completions require completeTaskWithEvidence.
        for (EmergentTaskState task : state.emergentTasks().values()) {
            if (!task.open()) continue;
            if (problemResolved(state, task) && task.problemTags().containsKey("autoComplete")) {
                task.setCompleted(true);
                applyCompletionEffects(state, task, null);
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
            if (s.developmentDeficit() > 1.5
                    || state.dynamicPhysical().hasOpenIntent(s.id(),
                    com.livingmods.common.model.PhysicalIntentType.CONSTRUCT_BUILDING)) {
                offer(state, ctx, EmergentTaskType.CONSTRUCTION_RESOURCES, s,
                        "Haul stone and wood to " + s.name(),
                        "Builders cannot finish works in progress.",
                        Map.of("wood", "15", "stone", "15"));
            }
            if (s.security() < 0.25 || s.unrest() > 0.55
                    || state.dynamicPhysical().hasOpenIntent(s.id(),
                    com.livingmods.common.model.PhysicalIntentType.CREATE_BANDIT_CAMP)) {
                String campId = "";
                for (var intent : state.dynamicPhysical().intents().values()) {
                    if (intent.type() == com.livingmods.common.model.PhysicalIntentType.CREATE_BANDIT_CAMP
                            && intent.status().isActive()
                            && intent.settlementId().isPresent()
                            && intent.settlementId().get().equals(s.id())) {
                        campId = intent.id().value().toString();
                        break;
                    }
                }
                offer(state, ctx, EmergentTaskType.BANDIT_REMOVAL, s,
                        "Clear bandits near " + s.name(),
                        "Roads are unsafe and trade falters.",
                        campId.isBlank()
                                ? Map.of("securityTarget", "0.5")
                                : Map.of("securityTarget", "0.5", "campId", campId));
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
                        Map.of("shipment", shipment.id().value().toString()));
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

    /**
     * @deprecated Prefer {@link #completeTaskWithEvidence} — bare completion is not production authority.
     */
    @Deprecated
    public boolean completeTask(CanonicalWorldState state, UUID taskId) {
        return false;
    }

    /**
     * Completes a task only when server-attested physical evidence satisfies the objective.
     */
    public boolean completeTaskWithEvidence(
            CanonicalWorldState state,
            UUID taskId,
            PlayerId player,
            Map<String, String> evidence
    ) {
        EmergentTaskState task = state.emergentTasks().get(taskId);
        if (task == null || !task.open()) return false;
        if (!evidenceSatisfies(state, task, evidence)) {
            return false;
        }
        applyDeliveryEffects(state, task, evidence);
        task.setStatus(com.livingmods.common.model.TaskStatus.COMPLETED);
        applyCompletionEffects(state, task, player);
        return true;
    }

    private static boolean evidenceSatisfies(
            CanonicalWorldState state,
            EmergentTaskState task,
            Map<String, String> evidence
    ) {
        SettlementState s = state.settlements().get(task.settlementId());
        if (s == null) return false;
        return switch (task.type()) {
            case FOOD_DELIVERY, MEDICINE_DELIVERY -> {
                ResourceType required = parseResource(task.problemTags().getOrDefault("resource",
                        task.type() == EmergentTaskType.FOOD_DELIVERY ? "GRAIN" : "MEDICINE"));
                double need = parseDouble(task.problemTags().getOrDefault("amount", "10"), 10);
                ResourceType delivered = parseResource(evidence.get("resource"));
                double amount = parseDouble(evidence.get("amount"), 0);
                boolean settlementMatch = task.settlementId().value().toString()
                        .equals(evidence.getOrDefault("settlementId", task.settlementId().value().toString()))
                        || s.id().toString().equals(evidence.get("settlementId"));
                yield delivered == required && amount + 1e-6 >= need && settlementMatch
                        && "true".equalsIgnoreCase(evidence.getOrDefault("serverVerified", "true"));
            }
            case CONSTRUCTION_RESOURCES -> {
                double wood = parseDouble(evidence.get("wood"), 0);
                double stone = parseDouble(evidence.get("stone"), 0);
                yield wood >= 15 && stone >= 15;
            }
            case BANDIT_REMOVAL -> {
                boolean cleared = "true".equalsIgnoreCase(evidence.get("campCleared"))
                        || "true".equalsIgnoreCase(evidence.get("campDestroyed"));
                String expectedCamp = task.problemTags().get("campId");
                if (expectedCamp != null && !expectedCamp.isBlank()) {
                    String got = evidence.getOrDefault("campId", "");
                    yield cleared && (got.isBlank() || got.equals(expectedCamp));
                }
                yield cleared || s.security() >= 0.45;
            }
            case ESCORT, MISSING_CARAVAN -> {
                String shipmentRaw = task.problemTags().getOrDefault("shipment",
                        evidence.getOrDefault("shipmentId", ""));
                if (shipmentRaw.isBlank()) {
                    yield "true".equalsIgnoreCase(evidence.get("caravanRescued"))
                            || "true".equalsIgnoreCase(evidence.get("caravanArrived"));
                }
                ShipmentState sh = findShipment(state, shipmentRaw);
                yield sh != null && (sh.delivered() || "true".equalsIgnoreCase(evidence.get("caravanRescued"))
                        || "true".equalsIgnoreCase(evidence.get("caravanArrived")));
            }
            case DIPLOMATIC_DELIVERY -> "true".equalsIgnoreCase(evidence.get("delivered"));
        };
    }

    private static void applyDeliveryEffects(
            CanonicalWorldState state,
            EmergentTaskState task,
            Map<String, String> evidence
    ) {
        StockpileState stock = state.stockpiles().get(task.settlementId());
        if (stock == null) {
            stock = new StockpileState(task.settlementId());
            state.stockpiles().put(task.settlementId(), stock);
        }
        switch (task.type()) {
            case FOOD_DELIVERY, MEDICINE_DELIVERY -> {
                ResourceType resource = parseResource(evidence.getOrDefault("resource",
                        task.problemTags().get("resource")));
                double amount = parseDouble(evidence.get("amount"),
                        parseDouble(task.problemTags().get("amount"), 0));
                if (resource != null && amount > 0) {
                    stock.add(resource, amount);
                }
            }
            case CONSTRUCTION_RESOURCES -> {
                stock.add(ResourceType.WOOD, parseDouble(evidence.get("wood"), 15));
                stock.add(ResourceType.STONE, parseDouble(evidence.get("stone"), 15));
            }
            default -> {
            }
        }
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

    private static void applyCompletionEffects(CanonicalWorldState state, EmergentTaskState task, PlayerId player) {
        SettlementState s = state.settlements().get(task.settlementId());
        if (s == null) return;
        switch (task.type()) {
            case FOOD_DELIVERY -> s.setHunger(Math.max(0, s.hunger() - 0.2));
            case MEDICINE_DELIVERY -> {
                // Stock already updated by evidence delivery; epidemic pressure eased by DiseaseEngine.
            }
            case CONSTRUCTION_RESOURCES ->
                    s.setDevelopmentDeficit(Math.max(0, s.developmentDeficit() - 1.0));
            case BANDIT_REMOVAL -> {
                // Security rises only from physical camp clear evidence path; do not invent boosts here.
            }
            case ESCORT -> {
                MarketState m = state.markets().get(s.id());
                if (m != null) m.setCrisisSeverity(Math.max(0, m.crisisSeverity() - 0.25));
            }
            case MISSING_CARAVAN -> {
                // Arrival handled by caravan outcome; no free stockpile gift.
            }
            case DIPLOMATIC_DELIVERY -> s.setLegitimacy(Math.min(100, s.legitimacy() + 2));
        }
        if (player != null) {
            s.ownerKingdom().ifPresent(k ->
                    state.playerReputation().adjust(player, k, 0.08));
        }
    }

    private static ShipmentState findShipment(CanonicalWorldState state, String raw) {
        try {
            String cleaned = raw.startsWith("shipment:") ? raw.substring(9) : raw;
            return state.shipments().get(ShipmentId.of(UUID.fromString(cleaned)));
        } catch (Exception e) {
            return null;
        }
    }

    private static ResourceType parseResource(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return ResourceType.valueOf(raw.toUpperCase(Locale.ROOT));
        } catch (Exception e) {
            return null;
        }
    }

    private static double parseDouble(String raw, double fallback) {
        if (raw == null || raw.isBlank()) return fallback;
        try {
            return Double.parseDouble(raw.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
