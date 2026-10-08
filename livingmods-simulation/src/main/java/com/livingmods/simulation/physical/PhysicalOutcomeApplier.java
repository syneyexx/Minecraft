package com.livingmods.simulation.physical;

import com.livingmods.common.event.CivilizationEventType;
import com.livingmods.common.event.HistoricalEvent;
import com.livingmods.common.geo.BlockPos2;
import com.livingmods.common.geo.BoundingBox2;
import com.livingmods.common.id.ArmyId;
import com.livingmods.common.id.CitizenId;
import com.livingmods.common.id.HistoricalEventId;
import com.livingmods.common.id.PhysicalIntentId;
import com.livingmods.common.id.PlayerId;
import com.livingmods.common.id.SettlementId;
import com.livingmods.common.id.ShipmentId;
import com.livingmods.common.id.StructureId;
import com.livingmods.common.model.BuildingRole;
import com.livingmods.common.model.PhysicalIntentStatus;
import com.livingmods.common.model.PhysicalIntentType;
import com.livingmods.common.model.PhysicalOutcomeType;
import com.livingmods.common.model.ResourceType;
import com.livingmods.common.model.StructureIntegrityStatus;
import com.livingmods.simulation.CanonicalWorldState;
import com.livingmods.simulation.engine.EmergentTaskEngine;
import com.livingmods.simulation.engine.PlayerSystemsEngine;
import com.livingmods.simulation.state.ArmyState;
import com.livingmods.simulation.state.CitizenState;
import com.livingmods.simulation.state.EmergentTaskState;
import com.livingmods.simulation.state.SettlementState;
import com.livingmods.simulation.state.ShipmentState;
import com.livingmods.simulation.state.StockpileState;
import com.livingmods.simulation.tick.SimulationContext;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Applies verified physical outcomes from Minecraft into canonical state.
 * Client-supplied reputation deltas / arbitrary completions are never trusted —
 * only typed outcomes with server-attested evidence fields.
 */
public final class PhysicalOutcomeApplier {
    private final PlayerSystemsEngine playerSystems = new PlayerSystemsEngine();
    private final EmergentTaskEngine tasks = new EmergentTaskEngine();

    public Map<String, String> apply(
            CanonicalWorldState state,
            PhysicalOutcomeType type,
            Map<String, String> evidence,
            SimulationContext ctx
    ) {
        return switch (type) {
            case ENTITY_KILLED -> applyEntityKilled(state, evidence, ctx);
            case ENTITY_DESPAWNED -> Map.of("status", "ok", "note", "despawn_not_death");
            case STRUCTURE_COMPLETED, INTENT_MATERIALIZED -> applyIntentMaterialized(state, evidence, ctx);
            case INTENT_BLOCKED -> applyIntentStatus(state, evidence, PhysicalIntentStatus.BLOCKED, ctx);
            case INTENT_FAILED -> applyIntentFailed(state, evidence, ctx);
            case STRUCTURE_DAMAGED -> applyStructureDamaged(state, evidence, ctx);
            case STRUCTURE_DESTROYED -> applyStructureDestroyed(state, evidence, ctx);
            case CARAVAN_ARRIVED -> applyCaravanArrived(state, evidence, ctx);
            case CARAVAN_DAMAGED, CARAVAN_ATTACKED -> applyCaravanDamaged(state, evidence, ctx);
            case CAMP_DESTROYED, CAMP_CLEARED -> applyCampCleared(state, evidence, ctx);
            case SIEGE_BREACH -> applySiegeBreach(state, evidence, ctx);
            case ARMY_CASUALTIES -> applyArmyCasualties(state, evidence, ctx);
            case TASK_ITEM_DELIVERED, TASK_OBJECTIVE_EVIDENCE -> applyTaskEvidence(state, evidence, ctx);
            case TRADE_COMPLETED -> applyTradeCompleted(state, evidence, ctx);
            case SETTLEMENT_FOUNDED -> applySettlementFoundedMarker(state, evidence, ctx);
            case GUARD_ATTACKED, CRIME_COMMITTED, PLAYER_REPUTATION_ACTION ->
                    applyPlayerAction(state, evidence, type, ctx);
            case PROJECTION_SYNC -> Map.of("status", "ok", "projected",
                    evidence.getOrDefault("projected", "0"));
        };
    }

    private Map<String, String> applyEntityKilled(
            CanonicalWorldState state,
            Map<String, String> evidence,
            SimulationContext ctx
    ) {
        String kind = evidence.getOrDefault("entityKind", "CITIZEN").toUpperCase(Locale.ROOT);
        PlayerId player = parsePlayer(evidence);
        return switch (kind) {
            case "CITIZEN", "GUARD" -> {
                CitizenId id = parseCitizen(evidence.get("citizenId"));
                if (id == null) yield Map.of("status", "rejected", "reason", "missing_citizen");
                CitizenState citizen = state.citizens().get(id);
                if (citizen == null || !citizen.alive()) {
                    yield Map.of("status", "ok", "note", "already_dead");
                }
                citizen.setAlive(false);
                SettlementState settlement = state.settlements().get(citizen.settlementId());
                if (settlement != null) {
                    settlement.setUnrest(Math.min(1.0, settlement.unrest() + 0.08));
                    if ("GUARD".equals(kind)) {
                        settlement.setSecurity(Math.max(0, settlement.security() - 0.05));
                    }
                    settlement.ownerKingdom().ifPresent(k ->
                            playerSystems.recordPlayerAction(state, player, k, -0.35, ctx));
                }
                state.appendHistory(new HistoricalEvent(
                        HistoricalEventId.deterministic(state.seed(), state.history().size()),
                        CivilizationEventType.PLAYER_REPUTATION_CHANGED,
                        ctx.time(),
                        "Citizen killed",
                        citizen.displayName() + " slain",
                        Optional.ofNullable(settlement).map(SettlementState::center),
                        Map.of("citizen", id.toString(), "player", player.toString())
                ));
                yield Map.of("status", "ok", "citizenId", id.toString(), "alive", "false");
            }
            case "BANDIT" -> {
                SettlementId sid = parseSettlement(evidence.get("settlementId"));
                if (sid != null) {
                    SettlementState s = state.settlements().get(sid);
                    if (s != null) {
                        s.setSecurity(Math.min(1.0, s.security() + 0.03));
                        s.ownerKingdom().ifPresent(k ->
                                playerSystems.recordPlayerAction(state, player, k, 0.04, ctx));
                    }
                }
                yield Map.of("status", "ok", "kind", "BANDIT");
            }
            case "SOLDIER" -> applyArmyCasualties(state, Map.of(
                    "armyId", evidence.getOrDefault("armyId", ""),
                    "casualties", evidence.getOrDefault("casualties", "1"),
                    "playerId", player.toString()
            ), ctx);
            default -> Map.of("status", "ok", "kind", kind);
        };
    }

    private Map<String, String> applyIntentMaterialized(
            CanonicalWorldState state,
            Map<String, String> evidence,
            SimulationContext ctx
    ) {
        PhysicalIntentId intentId = parseIntent(evidence.get("intentId"));
        if (intentId == null) {
            return Map.of("status", "rejected", "reason", "missing_intent");
        }
        PhysicalIntent intent = state.dynamicPhysical().intents().get(intentId);
        if (intent == null) {
            return Map.of("status", "rejected", "reason", "unknown_intent");
        }
        if (intent.status() == PhysicalIntentStatus.MATERIALIZED) {
            return Map.of("status", "ok", "note", "idempotent", "intentId", intentId.toString());
        }

        String chunkRaw = evidence.get("chunkKey");
        if (chunkRaw != null && !chunkRaw.isBlank()) {
            try {
                long key = Long.parseLong(chunkRaw);
                intent.markChunkApplied(PhysicalIntent.chunkX(key), PhysicalIntent.chunkZ(key));
            } catch (NumberFormatException ignored) {
            }
        }
        // Permit bulk completion when Minecraft reports full realization.
        if ("true".equalsIgnoreCase(evidence.get("complete")) || intent.allSlicesApplied()
                || intent.pendingChunkKeys().isEmpty()) {
            intent.pendingChunkKeys().clear();
            if (!intent.transitionTo(PhysicalIntentStatus.MATERIALIZED, ctx.time().absoluteTicks(), null)
                    && intent.status() != PhysicalIntentStatus.MATERIALIZING) {
                // Force terminal if stuck in READY after verified completion.
                if (intent.status() == PhysicalIntentStatus.READY
                        || intent.status() == PhysicalIntentStatus.FAILED_RETRYABLE) {
                    intent.transitionTo(PhysicalIntentStatus.MATERIALIZING, ctx.time().absoluteTicks(), null);
                    intent.transitionTo(PhysicalIntentStatus.MATERIALIZED, ctx.time().absoluteTicks(), null);
                }
            } else if (intent.status() == PhysicalIntentStatus.MATERIALIZING) {
                intent.transitionTo(PhysicalIntentStatus.MATERIALIZED, ctx.time().absoluteTicks(), null);
            } else if (intent.status() == PhysicalIntentStatus.READY) {
                intent.transitionTo(PhysicalIntentStatus.MATERIALIZING, ctx.time().absoluteTicks(), null);
                intent.transitionTo(PhysicalIntentStatus.MATERIALIZED, ctx.time().absoluteTicks(), null);
            }
            onStructureCompleted(state, intent, evidence, ctx);
        } else {
            if (intent.status() == PhysicalIntentStatus.READY) {
                intent.transitionTo(PhysicalIntentStatus.MATERIALIZING, ctx.time().absoluteTicks(), null);
            }
        }
        state.dynamicPhysical().bumpPhysicalDeltaRevision();
        return Map.of(
                "status", "ok",
                "intentId", intentId.toString(),
                "intentStatus", intent.status().name(),
                "pendingChunks", String.valueOf(intent.pendingChunkKeys().size())
        );
    }

    private void onStructureCompleted(
            CanonicalWorldState state,
            PhysicalIntent intent,
            Map<String, String> evidence,
            SimulationContext ctx
    ) {
        SettlementId sid = intent.settlementId().orElse(null);
        SettlementState settlement = sid == null ? null : state.settlements().get(sid);
        BuildingRole role = intent.buildingRole().orElse(BuildingRole.HOUSE);
        StructureId structureId = intent.structureId().orElseGet(() ->
                StructureId.of(intent.sourceEntityId() != null
                        ? intent.sourceEntityId()
                        : UUID.randomUUID()));

        int foundationY = parseInt(evidence.get("foundationY"), 64);
        int residential = switch (role) {
            case HOUSE, FARMHOUSE -> 4;
            case TOWNHOUSE -> 6;
            case MANOR -> 10;
            default -> role.name().contains("HOUSE") ? 4 : 0;
        };
        int work = switch (role) {
            case WORKSHOP, SMITHY, WAREHOUSE, MARKET_STALL, MARKET_HALL, SHOP, BARRACKS, GUARDHOUSE -> 4;
            case TEMPLE, SCHOOL, CLINIC -> 3;
            default -> 0;
        };

        DynamicStructureRecord record = new DynamicStructureRecord(
                structureId,
                sid != null ? sid : SettlementId.of(new UUID(0, 0)),
                role,
                intent.footprint() != null ? intent.footprint() : BoundingBox2.around(intent.targetPosition(), 4),
                intent.id(),
                StructureIntegrityStatus.ACTIVE,
                1.0,
                residential,
                work,
                intent.revision(),
                intent.cultureKey(),
                foundationY
        );
        state.dynamicPhysical().putStructure(record);

        if (settlement != null) {
            if (residential > 0) {
                settlement.setHousingUnits(settlement.housingUnits() + residential);
            }
            if (work > 0) {
                settlement.setEmployedSlots(settlement.employedSlots() + work);
            }
            settlement.setPhysicalCapacity(settlement.physicalCapacity() + 2.0 + residential * 0.5);
            settlement.setDevelopmentDeficit(Math.max(0, settlement.developmentDeficit() - 0.35));
            if (intent.type() == PhysicalIntentType.BUILD_WALL
                    || intent.type() == PhysicalIntentType.CREATE_FORTIFICATION) {
                settlement.setSecurity(Math.min(1.0, settlement.security() + 0.08));
            }
            if (intent.type() == PhysicalIntentType.REPAIR_STRUCTURE) {
                DynamicStructureRecord existing = state.dynamicPhysical().structures().get(structureId);
                if (existing != null) {
                    existing.setIntegrity(1.0);
                    existing.setStatus(StructureIntegrityStatus.ACTIVE);
                }
            }
        }

        // Bind homeless households when housing completes.
        if (residential > 0 && sid != null) {
            for (var hh : state.households().values()) {
                if (hh.settlementId().equals(sid) && hh.homeStructureId() == null) {
                    hh.setHomeStructureId(structureId);
                    for (CitizenId cid : state.citizensInHousehold(hh.id())) {
                        CitizenState c = state.citizens().get(cid);
                        if (c != null) c.setHomeStructureId(structureId);
                    }
                    break;
                }
            }
        }

        state.appendHistory(new HistoricalEvent(
                HistoricalEventId.deterministic(state.seed(), state.history().size()),
                CivilizationEventType.CONSTRUCTION_REQUIRED,
                ctx.time(),
                "Structure completed",
                role.name() + " realized physically",
                Optional.ofNullable(intent.targetPosition()),
                Map.of(
                        "intent", intent.id().toString(),
                        "structure", structureId.toString(),
                        "settlement", sid == null ? "" : sid.toString()
                )
        ));
    }

    private Map<String, String> applyIntentStatus(
            CanonicalWorldState state,
            Map<String, String> evidence,
            PhysicalIntentStatus status,
            SimulationContext ctx
    ) {
        PhysicalIntentId intentId = parseIntent(evidence.get("intentId"));
        if (intentId == null) return Map.of("status", "rejected", "reason", "missing_intent");
        PhysicalIntent intent = state.dynamicPhysical().intents().get(intentId);
        if (intent == null) return Map.of("status", "rejected", "reason", "unknown_intent");
        intent.transitionTo(status, ctx.time().absoluteTicks(), evidence.getOrDefault("reason", status.name()));
        return Map.of("status", "ok", "intentStatus", intent.status().name());
    }

    private Map<String, String> applyIntentFailed(
            CanonicalWorldState state,
            Map<String, String> evidence,
            SimulationContext ctx
    ) {
        PhysicalIntentId intentId = parseIntent(evidence.get("intentId"));
        if (intentId == null) return Map.of("status", "rejected", "reason", "missing_intent");
        PhysicalIntent intent = state.dynamicPhysical().intents().get(intentId);
        if (intent == null) return Map.of("status", "rejected", "reason", "unknown_intent");
        boolean terminal = "true".equalsIgnoreCase(evidence.get("terminal"));
        intent.transitionTo(
                terminal ? PhysicalIntentStatus.FAILED_TERMINAL : PhysicalIntentStatus.FAILED_RETRYABLE,
                ctx.time().absoluteTicks(),
                evidence.getOrDefault("reason", "failed")
        );
        // Refund reserved resources on terminal failure.
        if (intent.status() == PhysicalIntentStatus.FAILED_TERMINAL && intent.settlementId().isPresent()) {
            StockpileState stock = state.stockpiles().get(intent.settlementId().get());
            if (stock != null) {
                for (var e : intent.reservedCosts().entrySet()) {
                    stock.add(e.getKey(), e.getValue() * 0.75);
                }
            }
        }
        return Map.of("status", "ok", "intentStatus", intent.status().name());
    }

    private Map<String, String> applyStructureDamaged(
            CanonicalWorldState state,
            Map<String, String> evidence,
            SimulationContext ctx
    ) {
        StructureId sid = parseStructure(evidence.get("structureId"));
        if (sid == null) return Map.of("status", "rejected", "reason", "missing_structure");
        DynamicStructureRecord rec = state.dynamicPhysical().structures().get(sid);
        double delta = parseDouble(evidence.get("integrityDelta"), 0.15);
        if (rec != null) {
            rec.setIntegrity(rec.integrity() - Math.abs(delta));
            SettlementState settlement = state.settlements().get(rec.settlementId());
            if (settlement != null && !rec.contributesHousing() && rec.residentialSlots() > 0) {
                settlement.setHousingUnits(Math.max(0, settlement.housingUnits() - rec.residentialSlots()));
            }
        }
        // Also create damage intent for war visibility.
        if (rec != null) {
            PhysicalIntentId intentId = PhysicalIntentId.deterministic(
                    state.seed(), state.dynamicPhysical().intents().size() + 200_000L);
            PhysicalIntent damage = new PhysicalIntent(
                    intentId,
                    PhysicalIntentType.DAMAGE_STRUCTURE,
                    "war",
                    sid.value(),
                    Optional.of(rec.settlementId()),
                    Optional.empty(),
                    Optional.of(sid),
                    Optional.of(rec.role()),
                    rec.footprint().center(),
                    rec.footprint(),
                    rec.physicalRevision() + 1,
                    PhysicalIntentStatus.MATERIALIZED,
                    ctx.time(),
                    2,
                    Map.of("cause", evidence.getOrDefault("cause", "combat")),
                    Map.of(),
                    rec.cultureKey()
            );
            state.dynamicPhysical().putIntent(damage);
        }
        return Map.of("status", "ok", "structureId", sid.toString());
    }

    private Map<String, String> applyStructureDestroyed(
            CanonicalWorldState state,
            Map<String, String> evidence,
            SimulationContext ctx
    ) {
        Map<String, String> damaged = applyStructureDamaged(state, Map.of(
                "structureId", evidence.getOrDefault("structureId", ""),
                "integrityDelta", "1.0",
                "cause", evidence.getOrDefault("cause", "destroyed")
        ), ctx);
        StructureId sid = parseStructure(evidence.get("structureId"));
        if (sid != null) {
            DynamicStructureRecord rec = state.dynamicPhysical().structures().get(sid);
            if (rec != null) {
                rec.setStatus(StructureIntegrityStatus.DESTROYED);
                rec.setIntegrity(0);
                SettlementState settlement = state.settlements().get(rec.settlementId());
                if (settlement != null && rec.residentialSlots() > 0) {
                    settlement.setHousingUnits(Math.max(0, settlement.housingUnits() - rec.residentialSlots()));
                    settlement.setPhysicalCapacity(Math.max(1, settlement.physicalCapacity() - 2));
                }
            }
        }
        return damaged;
    }

    private Map<String, String> applyCaravanArrived(
            CanonicalWorldState state,
            Map<String, String> evidence,
            SimulationContext ctx
    ) {
        ShipmentId id = parseShipment(evidence.get("shipmentId"));
        if (id == null) return Map.of("status", "rejected", "reason", "missing_shipment");
        ShipmentState sh = state.shipments().get(id);
        if (sh == null) return Map.of("status", "rejected", "reason", "unknown_shipment");
        if (!sh.delivered() && !sh.looted()) {
            StockpileState dest = state.stockpiles().get(sh.destination());
            if (dest != null) {
                dest.add(sh.goods(), sh.quantity());
            }
            sh.setDelivered(true);
        }
        PlayerId player = parsePlayer(evidence);
        SettlementState destSettlement = state.settlements().get(sh.destination());
        if (destSettlement != null && "true".equalsIgnoreCase(evidence.get("playerEscorted"))) {
            destSettlement.ownerKingdom().ifPresent(k ->
                    playerSystems.recordPlayerAction(state, player, k, 0.12, ctx));
        }
        return Map.of("status", "ok", "shipmentId", id.toString(), "delivered", "true");
    }

    private Map<String, String> applyCaravanDamaged(
            CanonicalWorldState state,
            Map<String, String> evidence,
            SimulationContext ctx
    ) {
        ShipmentId id = parseShipment(evidence.get("shipmentId"));
        if (id == null) return Map.of("status", "rejected", "reason", "missing_shipment");
        ShipmentState sh = state.shipments().get(id);
        if (sh == null) return Map.of("status", "rejected", "reason", "unknown_shipment");
        double lossFraction = Math.min(1.0, Math.max(0.1, parseDouble(evidence.get("lossFraction"), 0.35)));
        double lost = sh.quantity() * lossFraction;
        sh.setQuantity(sh.quantity() - lost);
        if (sh.quantity() < 1.0 || "true".equalsIgnoreCase(evidence.get("destroyed"))) {
            sh.setLooted(true);
            sh.setQuantity(0);
        }
        SettlementState dest = state.settlements().get(sh.destination());
        if (dest != null) {
            dest.setHunger(Math.min(1.0, dest.hunger() + 0.05));
            var market = state.markets().get(dest.id());
            if (market != null) {
                market.setCrisisSeverity(Math.min(1.0, market.crisisSeverity() + 0.1));
                market.setPrice(sh.goods(), market.price(sh.goods()) * (1.0 + lossFraction * 0.2));
            }
        }
        PlayerId player = parsePlayer(evidence);
        if ("true".equalsIgnoreCase(evidence.get("playerAttacked")) && dest != null) {
            dest.ownerKingdom().ifPresent(k ->
                    playerSystems.recordPlayerAction(state, player, k, -0.25, ctx));
        }
        state.appendHistory(new HistoricalEvent(
                HistoricalEventId.deterministic(state.seed(), state.history().size()),
                CivilizationEventType.BANDIT_ACTIVITY_CHANGED,
                ctx.time(),
                "Caravan attacked",
                "Shipment lost " + String.format(Locale.ROOT, "%.0f", lost) + " " + sh.goods(),
                Optional.of(sh.currentPosition()),
                Map.of("shipment", id.toString())
        ));
        return Map.of(
                "status", "ok",
                "shipmentId", id.toString(),
                "quantity", String.valueOf(sh.quantity()),
                "looted", String.valueOf(sh.looted())
        );
    }

    private Map<String, String> applyCampCleared(
            CanonicalWorldState state,
            Map<String, String> evidence,
            SimulationContext ctx
    ) {
        SettlementId sid = parseSettlement(evidence.get("settlementId"));
        PlayerId player = parsePlayer(evidence);
        if (sid != null) {
            SettlementState s = state.settlements().get(sid);
            if (s != null) {
                s.setSecurity(Math.min(1.0, s.security() + 0.2));
                s.setUnrest(Math.max(0, s.unrest() - 0.1));
                s.ownerKingdom().ifPresent(k ->
                        playerSystems.recordPlayerAction(state, player, k, 0.15, ctx));
            }
        }
        // Mark matching camp intents removed.
        String campId = evidence.get("campId");
        for (PhysicalIntent intent : state.dynamicPhysical().intents().values()) {
            if ((intent.type() == PhysicalIntentType.CREATE_BANDIT_CAMP
                    || intent.type() == PhysicalIntentType.UPGRADE_BANDIT_CAMP)
                    && intent.status().isActive()) {
                if (campId == null || campId.equals(intent.provenance().get("campId"))
                        || (intent.sourceEntityId() != null && intent.sourceEntityId().toString().equals(campId))) {
                    intent.transitionTo(PhysicalIntentStatus.REMOVED, ctx.time().absoluteTicks(), "cleared");
                }
            }
        }
        // Complete BANDIT_REMOVAL tasks with verified evidence.
        for (EmergentTaskState task : state.emergentTasks().values()) {
            if (task.open()
                    && task.type() == com.livingmods.common.model.EmergentTaskType.BANDIT_REMOVAL
                    && (sid == null || task.settlementId().equals(sid))) {
                tasks.completeTaskWithEvidence(state, task.id(), player, evidence);
            }
        }
        return Map.of("status", "ok", "campCleared", "true");
    }

    private Map<String, String> applySiegeBreach(
            CanonicalWorldState state,
            Map<String, String> evidence,
            SimulationContext ctx
    ) {
        SettlementId sid = parseSettlement(evidence.get("settlementId"));
        if (sid == null) return Map.of("status", "rejected", "reason", "missing_settlement");
        SettlementState s = state.settlements().get(sid);
        if (s != null) {
            s.setSecurity(Math.max(0, s.security() - 0.2));
            s.setUnrest(Math.min(1.0, s.unrest() + 0.15));
            s.setPhysicalCapacity(Math.max(5, s.physicalCapacity() * 0.9));
        }
        for (var siege : state.sieges().values()) {
            if (siege.active() && siege.target().equals(sid)) {
                siege.setProgress(Math.min(100, siege.progress() + 20));
            }
        }
        // Damage wall structures near settlement.
        for (DynamicStructureRecord rec : state.dynamicPhysical().structures().values()) {
            if (rec.settlementId().equals(sid)
                    && (rec.role() == BuildingRole.WALL_SEGMENT || rec.role() == BuildingRole.GATEHOUSE)) {
                rec.setIntegrity(Math.max(0.1, rec.integrity() - 0.35));
            }
        }
        state.appendHistory(new HistoricalEvent(
                HistoricalEventId.deterministic(state.seed(), state.history().size()),
                CivilizationEventType.WAR_STARTED,
                ctx.time(),
                "Siege breach",
                "Walls breached at " + (s == null ? sid.toString() : s.name()),
                Optional.ofNullable(s).map(SettlementState::center),
                Map.of("settlement", sid.toString())
        ));
        return Map.of("status", "ok", "settlementId", sid.toString());
    }

    private Map<String, String> applyArmyCasualties(
            CanonicalWorldState state,
            Map<String, String> evidence,
            SimulationContext ctx
    ) {
        ArmyId armyId = parseArmy(evidence.get("armyId"));
        if (armyId == null) return Map.of("status", "rejected", "reason", "missing_army");
        ArmyState army = state.armies().get(armyId);
        if (army == null) return Map.of("status", "rejected", "reason", "unknown_army");
        int physicalKills = Math.max(0, parseInt(evidence.get("casualties"), 1));
        // Aggregate mapping: physical squad kills → scaled canonical casualties.
        int canonicalLoss = Math.max(1, physicalKills * Math.max(1, army.manpower() / Math.max(1,
                parseInt(evidence.get("projectedSquads"), 8))));
        canonicalLoss = Math.min(army.manpower() / 4, canonicalLoss); // never wipe from one skirmish
        army.setManpower(Math.max(0, army.manpower() - canonicalLoss));
        army.setMorale(Math.max(0, army.morale() - physicalKills));
        return Map.of(
                "status", "ok",
                "armyId", armyId.toString(),
                "canonicalLoss", String.valueOf(canonicalLoss),
                "manpower", String.valueOf(army.manpower())
        );
    }

    private Map<String, String> applyTaskEvidence(
            CanonicalWorldState state,
            Map<String, String> evidence,
            SimulationContext ctx
    ) {
        UUID taskId;
        try {
            taskId = UUID.fromString(evidence.getOrDefault("taskId", ""));
        } catch (Exception e) {
            return Map.of("status", "rejected", "reason", "missing_task");
        }
        PlayerId player = parsePlayer(evidence);
        boolean ok = tasks.completeTaskWithEvidence(state, taskId, player, evidence);
        return Map.of("status", ok ? "ok" : "rejected", "taskId", taskId.toString());
    }

    private Map<String, String> applyTradeCompleted(
            CanonicalWorldState state,
            Map<String, String> evidence,
            SimulationContext ctx
    ) {
        PlayerId player = parsePlayer(evidence);
        SettlementId sid = parseSettlement(evidence.get("settlementId"));
        ResourceType resource = parseResource(evidence.get("resource"));
        double amount = parseDouble(evidence.get("amount"), 0);
        if (sid == null || resource == null || amount <= 0) {
            return Map.of("status", "rejected", "reason", "invalid_trade");
        }
        StockpileState stock = state.stockpiles().get(sid);
        if (stock == null) {
            stock = new StockpileState(sid);
            state.stockpiles().put(sid, stock);
        }
        stock.add(resource, amount);
        SettlementState s = state.settlements().get(sid);
        if (s != null) {
            s.ownerKingdom().ifPresent(k ->
                    playerSystems.recordPlayerAction(state, player, k, 0.05, ctx));
            if (resource == ResourceType.GRAIN || resource == ResourceType.FOOD) {
                s.setHunger(Math.max(0, s.hunger() - Math.min(0.3, amount / 40.0)));
            }
        }
        return Map.of("status", "ok", "stock", String.valueOf(stock.get(resource)));
    }

    private Map<String, String> applySettlementFoundedMarker(
            CanonicalWorldState state,
            Map<String, String> evidence,
            SimulationContext ctx
    ) {
        SettlementId sid = parseSettlement(evidence.get("settlementId"));
        if (sid == null) return Map.of("status", "ok");
        // Founding construction intents should already exist; mark geometry player-founded.
        DynamicSettlementGeometry geo = state.dynamicPhysical().settlementGeometry().get(sid);
        if (geo != null) {
            geo.setPlayerFounded(true);
        }
        return Map.of("status", "ok", "settlementId", sid.toString());
    }

    private Map<String, String> applyPlayerAction(
            CanonicalWorldState state,
            Map<String, String> evidence,
            PhysicalOutcomeType type,
            SimulationContext ctx
    ) {
        PlayerId player = parsePlayer(evidence);
        SettlementId sid = parseSettlement(evidence.get("settlementId"));
        double rawDelta = switch (type) {
            case GUARD_ATTACKED -> -0.2;
            case CRIME_COMMITTED -> -0.15;
            default -> parseDouble(evidence.get("reputationDelta"), 0.0);
        };
        // Cap client-suggested deltas; server chooses magnitude from type.
        final double delta = type == PhysicalOutcomeType.PLAYER_REPUTATION_ACTION
                ? Math.max(-0.5, Math.min(0.5, rawDelta))
                : rawDelta;
        SettlementState s = sid == null ? null : state.settlements().get(sid);
        if (s != null) {
            s.ownerKingdom().ifPresent(k ->
                    playerSystems.recordPlayerAction(state, player, k, delta, ctx));
        }
        return Map.of("status", "ok", "reputationDelta", String.valueOf(delta));
    }

    private static PlayerId parsePlayer(Map<String, String> evidence) {
        String raw = evidence.get("playerId");
        if (raw == null || raw.isBlank()) {
            // Reject demo fallback — callers must supply a real Minecraft player UUID
            // for reputation-affecting outcomes. Neutral system events may omit it.
            return PlayerId.of(new UUID(0L, 0L));
        }
        try {
            return PlayerId.of(UUID.fromString(raw.trim()));
        } catch (Exception e) {
            return PlayerId.of(new UUID(0L, 0L));
        }
    }

    private static CitizenId parseCitizen(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            String cleaned = raw.startsWith("citizen:") ? raw.substring(8) : raw;
            return CitizenId.of(UUID.fromString(cleaned));
        } catch (Exception e) {
            return null;
        }
    }

    private static PhysicalIntentId parseIntent(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            String cleaned = raw.startsWith("intent:") ? raw.substring(7) : raw;
            return PhysicalIntentId.of(UUID.fromString(cleaned));
        } catch (Exception e) {
            return null;
        }
    }

    private static StructureId parseStructure(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            String cleaned = raw.startsWith("structure:") ? raw.substring(10) : raw;
            return StructureId.of(UUID.fromString(cleaned));
        } catch (Exception e) {
            return null;
        }
    }

    private static SettlementId parseSettlement(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            String cleaned = raw.startsWith("settlement:") ? raw.substring(11) : raw;
            return SettlementId.of(UUID.fromString(cleaned));
        } catch (Exception e) {
            return null;
        }
    }

    private static ShipmentId parseShipment(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            String cleaned = raw.startsWith("shipment:") ? raw.substring(9) : raw;
            return ShipmentId.of(UUID.fromString(cleaned));
        } catch (Exception e) {
            return null;
        }
    }

    private static ArmyId parseArmy(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            String cleaned = raw.startsWith("army:") ? raw.substring(5) : raw;
            return ArmyId.of(UUID.fromString(cleaned));
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

    private static int parseInt(String raw, int fallback) {
        if (raw == null || raw.isBlank()) return fallback;
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return fallback;
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
