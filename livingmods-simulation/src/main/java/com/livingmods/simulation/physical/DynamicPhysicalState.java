package com.livingmods.simulation.physical;

import com.livingmods.common.id.PhysicalIntentId;
import com.livingmods.common.id.SettlementId;
import com.livingmods.common.id.StructureId;
import com.livingmods.common.model.PhysicalIntentStatus;
import com.livingmods.common.model.PhysicalIntentType;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Persisted layer for physical changes AFTER immutable WorldPlan generation.
 * Never write geometry back into plan.bin.
 */
public final class DynamicPhysicalState {
    public static final int MAX_INTENTS = 8192;
    public static final int MAX_STRUCTURES = 16384;
    public static final int MAX_SETTLEMENT_GEOMETRIES = 2048;

    private final Map<PhysicalIntentId, PhysicalIntent> intents = new LinkedHashMap<>();
    private final Map<StructureId, DynamicStructureRecord> structures = new LinkedHashMap<>();
    private final Map<SettlementId, DynamicSettlementGeometry> settlementGeometry = new LinkedHashMap<>();
    private long physicalDeltaRevision;

    public Map<PhysicalIntentId, PhysicalIntent> intents() { return intents; }
    public Map<StructureId, DynamicStructureRecord> structures() { return structures; }
    public Map<SettlementId, DynamicSettlementGeometry> settlementGeometry() { return settlementGeometry; }
    public long physicalDeltaRevision() { return physicalDeltaRevision; }
    public void bumpPhysicalDeltaRevision() { physicalDeltaRevision++; }

    public Optional<PhysicalIntent> intent(PhysicalIntentId id) {
        return Optional.ofNullable(intents.get(id));
    }

    public boolean putIntent(PhysicalIntent intent) {
        if (intent == null) return false;
        if (!intents.containsKey(intent.id()) && intents.size() >= MAX_INTENTS) {
            pruneTerminalIntents();
            if (intents.size() >= MAX_INTENTS) {
                return false;
            }
        }
        intents.put(intent.id(), intent);
        bumpPhysicalDeltaRevision();
        return true;
    }

    public void putStructure(DynamicStructureRecord record) {
        if (record == null) return;
        if (!structures.containsKey(record.structureId()) && structures.size() >= MAX_STRUCTURES) {
            return;
        }
        structures.put(record.structureId(), record);
        bumpPhysicalDeltaRevision();
    }

    public void putSettlementGeometry(DynamicSettlementGeometry geometry) {
        if (geometry == null) return;
        if (!settlementGeometry.containsKey(geometry.settlementId())
                && settlementGeometry.size() >= MAX_SETTLEMENT_GEOMETRIES) {
            return;
        }
        settlementGeometry.put(geometry.settlementId(), geometry);
        bumpPhysicalDeltaRevision();
    }

    public List<PhysicalIntent> activeIntentsByPriority() {
        List<PhysicalIntent> active = new ArrayList<>();
        for (PhysicalIntent intent : intents.values()) {
            if (intent.status().isActive()) {
                active.add(intent);
            }
        }
        active.sort(Comparator
                .comparingInt(PhysicalIntent::priority)
                .thenComparing(i -> i.id().value()));
        return active;
    }

    public List<PhysicalIntent> readyForSettlement(SettlementId settlementId) {
        List<PhysicalIntent> result = new ArrayList<>();
        for (PhysicalIntent intent : intents.values()) {
            if (intent.status() != PhysicalIntentStatus.READY
                    && intent.status() != PhysicalIntentStatus.MATERIALIZING
                    && intent.status() != PhysicalIntentStatus.FAILED_RETRYABLE) {
                continue;
            }
            if (intent.settlementId().isPresent() && intent.settlementId().get().equals(settlementId)) {
                result.add(intent);
            }
        }
        result.sort(Comparator.comparingInt(PhysicalIntent::priority).thenComparing(i -> i.id().value()));
        return result;
    }

    public boolean hasOpenIntent(SettlementId settlementId, PhysicalIntentType type) {
        for (PhysicalIntent intent : intents.values()) {
            if (intent.type() == type
                    && intent.status().isActive()
                    && intent.settlementId().isPresent()
                    && intent.settlementId().get().equals(settlementId)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Spatial intent discovery for player-founded / dynamic settlements that may lack
     * immutable WorldPlan identity. Bounded by radius and result limit.
     */
    public List<PhysicalIntent> readyNear(int blockX, int blockZ, int radius, int limit) {
        long r2 = (long) radius * radius;
        List<PhysicalIntent> result = new ArrayList<>();
        for (PhysicalIntent intent : intents.values()) {
            if (intent.status() != PhysicalIntentStatus.READY
                    && intent.status() != PhysicalIntentStatus.MATERIALIZING
                    && intent.status() != PhysicalIntentStatus.FAILED_RETRYABLE) {
                continue;
            }
            if (intent.type().isTransientProjection()) {
                continue;
            }
            var pos = intent.targetPosition();
            if (pos == null) continue;
            long dx = (long) pos.x() - blockX;
            long dz = (long) pos.z() - blockZ;
            if (dx * dx + dz * dz > r2) continue;
            // Also accept footprints that cover the query point.
            if (intent.footprint() != null && intent.footprint().contains(blockX, blockZ)) {
                result.add(intent);
            } else if (dx * dx + dz * dz <= r2) {
                result.add(intent);
            }
            if (result.size() >= limit) break;
        }
        result.sort(Comparator.comparingInt(PhysicalIntent::priority).thenComparing(i -> i.id().value()));
        return result;
    }

    /**
     * Settlements whose dynamic geometry or center falls within radius of (x,z).
     */
    public List<SettlementId> settlementsNear(int blockX, int blockZ, int radius) {
        long r2 = (long) radius * radius;
        List<SettlementId> ids = new ArrayList<>();
        for (DynamicSettlementGeometry geo : settlementGeometry.values()) {
            long dx = (long) geo.center().x() - blockX;
            long dz = (long) geo.center().z() - blockZ;
            if (dx * dx + dz * dz <= r2 || geo.boundary().contains(blockX, blockZ)) {
                ids.add(geo.settlementId());
            }
        }
        return ids;
    }

    /**
     * Central dependency evaluation. An intent is physically executable only when
     * all dependencies have reached an acceptable terminal success state.
     */
    public DependencyResult evaluateDependencies(PhysicalIntent intent) {
        if (intent.dependencies().isEmpty()) {
            return DependencyResult.ready();
        }
        java.util.Set<PhysicalIntentId> visiting = new java.util.LinkedHashSet<>();
        return evaluateDependenciesRecursive(intent, visiting);
    }

    private DependencyResult evaluateDependenciesRecursive(
            PhysicalIntent intent,
            java.util.Set<PhysicalIntentId> visiting
    ) {
        if (!visiting.add(intent.id())) {
            return DependencyResult.blocked("circular_dependencies");
        }
        for (PhysicalIntentId depId : intent.dependencies()) {
            PhysicalIntent dep = intents.get(depId);
            if (dep == null) {
                return DependencyResult.blocked("missing_dependencies");
            }
            if (dep.status() == PhysicalIntentStatus.FAILED_TERMINAL
                    || dep.status() == PhysicalIntentStatus.SUPERSEDED
                    || dep.status() == PhysicalIntentStatus.REMOVED) {
                return DependencyResult.failed("dependency_failed:" + depId);
            }
            if (dep.status() != PhysicalIntentStatus.MATERIALIZED) {
                // Planning-meta deps may be MATERIALIZED after child emission; otherwise wait.
                if (dep.status().isActive()) {
                    DependencyResult nested = evaluateDependenciesRecursive(dep, visiting);
                    if (!nested.satisfied()) {
                        return nested;
                    }
                    return DependencyResult.blocked("dependency_pending");
                }
                return DependencyResult.blocked("dependency_pending");
            }
        }
        visiting.remove(intent.id());
        return DependencyResult.ready();
    }

    public record DependencyResult(boolean satisfied, boolean failed, String reason) {
        public static DependencyResult ready() { return new DependencyResult(true, false, ""); }
        public static DependencyResult blocked(String reason) { return new DependencyResult(false, false, reason); }
        public static DependencyResult failed(String reason) { return new DependencyResult(false, true, reason); }
    }

    public int activeHousingUnits(SettlementId settlementId) {
        int units = 0;
        for (DynamicStructureRecord rec : structures.values()) {
            if (rec.settlementId().equals(settlementId) && rec.contributesHousing()) {
                units += Math.max(1, (int) Math.round(rec.residentialSlots() * rec.integrity()));
            }
        }
        return units;
    }

    public void pruneTerminalIntents() {
        List<PhysicalIntentId> remove = new ArrayList<>();
        for (PhysicalIntent intent : intents.values()) {
            if (intent.status().isTerminal()) {
                remove.add(intent.id());
            }
        }
        // Keep recent terminal intents for idempotency — only prune when over soft cap.
        if (intents.size() - remove.size() > MAX_INTENTS / 2) {
            return;
        }
        int toRemove = Math.max(0, intents.size() - MAX_INTENTS + 64);
        remove.sort(Comparator.comparing(id -> intents.get(id).lastUpdateTicks()));
        for (int i = 0; i < Math.min(toRemove, remove.size()); i++) {
            intents.remove(remove.get(i));
        }
    }
}
