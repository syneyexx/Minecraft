package com.livingmods.simulation.physical;

import com.livingmods.common.geo.BlockPos2;
import com.livingmods.common.geo.BoundingBox2;
import com.livingmods.common.id.KingdomId;
import com.livingmods.common.id.PhysicalIntentId;
import com.livingmods.common.id.SettlementId;
import com.livingmods.common.id.StructureId;
import com.livingmods.common.model.BuildingRole;
import com.livingmods.common.model.PhysicalIntentStatus;
import com.livingmods.common.model.PhysicalIntentType;
import com.livingmods.common.model.ResourceType;
import com.livingmods.common.time.SimulationTime;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Authoritative, versioned physical change intent.
 * Idempotent, persistent, reload-safe — status is never inferred from absence.
 */
public final class PhysicalIntent {
    public static final int MAX_RETRIES = 5;
    public static final int MAX_DEPENDENCIES = 16;
    public static final int MAX_CHUNK_SLICES = 256;

    private final PhysicalIntentId id;
    private final PhysicalIntentType type;
    private final String authority;
    private final UUID sourceEntityId;
    private final Optional<SettlementId> settlementId;
    private final Optional<KingdomId> kingdomId;
    private final Optional<StructureId> structureId;
    private final Optional<BuildingRole> buildingRole;
    private BlockPos2 targetPosition;
    private BoundingBox2 footprint;
    private int revision;
    private PhysicalIntentStatus status;
    private final SimulationTime createdAt;
    private final Set<PhysicalIntentId> dependencies;
    private int priority;
    private int retryCount;
    private String failureReason;
    private final Map<String, String> provenance;
    private final Map<ResourceType, Double> reservedCosts;
    private final Set<Long> pendingChunkKeys;
    private final Set<Long> appliedChunkKeys;
    private String cultureKey;
    private long lastUpdateTicks;

    public PhysicalIntent(
            PhysicalIntentId id,
            PhysicalIntentType type,
            String authority,
            UUID sourceEntityId,
            Optional<SettlementId> settlementId,
            Optional<KingdomId> kingdomId,
            Optional<StructureId> structureId,
            Optional<BuildingRole> buildingRole,
            BlockPos2 targetPosition,
            BoundingBox2 footprint,
            int revision,
            PhysicalIntentStatus status,
            SimulationTime createdAt,
            int priority,
            Map<String, String> provenance,
            Map<ResourceType, Double> reservedCosts,
            String cultureKey
    ) {
        this.id = id;
        this.type = type;
        this.authority = authority == null ? "canonical" : authority;
        this.sourceEntityId = sourceEntityId;
        this.settlementId = settlementId == null ? Optional.empty() : settlementId;
        this.kingdomId = kingdomId == null ? Optional.empty() : kingdomId;
        this.structureId = structureId == null ? Optional.empty() : structureId;
        this.buildingRole = buildingRole == null ? Optional.empty() : buildingRole;
        this.targetPosition = targetPosition;
        this.footprint = footprint;
        this.revision = revision;
        this.status = status == null ? PhysicalIntentStatus.PLANNED : status;
        this.createdAt = createdAt;
        this.dependencies = new LinkedHashSet<>();
        this.priority = priority;
        this.retryCount = 0;
        this.failureReason = "";
        this.provenance = new LinkedHashMap<>();
        if (provenance != null) {
            this.provenance.putAll(provenance);
        }
        this.reservedCosts = new EnumMap<>(ResourceType.class);
        if (reservedCosts != null) {
            this.reservedCosts.putAll(reservedCosts);
        }
        this.pendingChunkKeys = new LinkedHashSet<>();
        this.appliedChunkKeys = new LinkedHashSet<>();
        this.cultureKey = cultureKey == null ? "" : cultureKey;
        this.lastUpdateTicks = createdAt == null ? 0L : createdAt.absoluteTicks();
    }

    public PhysicalIntentId id() { return id; }
    public PhysicalIntentType type() { return type; }
    public String authority() { return authority; }
    public UUID sourceEntityId() { return sourceEntityId; }
    public Optional<SettlementId> settlementId() { return settlementId; }
    public Optional<KingdomId> kingdomId() { return kingdomId; }
    public Optional<StructureId> structureId() { return structureId; }
    public Optional<BuildingRole> buildingRole() { return buildingRole; }
    public BlockPos2 targetPosition() { return targetPosition; }
    public void setTargetPosition(BlockPos2 targetPosition) { this.targetPosition = targetPosition; }
    public BoundingBox2 footprint() { return footprint; }
    public void setFootprint(BoundingBox2 footprint) { this.footprint = footprint; }
    public int revision() { return revision; }
    public void bumpRevision() { revision++; }
    public PhysicalIntentStatus status() { return status; }
    public SimulationTime createdAt() { return createdAt; }
    public Set<PhysicalIntentId> dependencies() { return dependencies; }
    public int priority() { return priority; }
    public void setPriority(int priority) { this.priority = priority; }
    public int retryCount() { return retryCount; }
    public String failureReason() { return failureReason; }
    public Map<String, String> provenance() { return provenance; }
    public Map<ResourceType, Double> reservedCosts() { return reservedCosts; }
    public Set<Long> pendingChunkKeys() { return pendingChunkKeys; }
    public Set<Long> appliedChunkKeys() { return appliedChunkKeys; }
    public String cultureKey() { return cultureKey; }
    public void setCultureKey(String cultureKey) { this.cultureKey = cultureKey == null ? "" : cultureKey; }
    public long lastUpdateTicks() { return lastUpdateTicks; }

    public boolean transitionTo(PhysicalIntentStatus next, long simTicks, String reason) {
        if (!status.canTransitionTo(next)) {
            return false;
        }
        this.status = next;
        this.lastUpdateTicks = simTicks;
        if (reason != null) {
            this.failureReason = reason;
        }
        if (next == PhysicalIntentStatus.FAILED_RETRYABLE) {
            retryCount++;
            if (retryCount >= MAX_RETRIES) {
                this.status = PhysicalIntentStatus.FAILED_TERMINAL;
            }
        }
        return true;
    }

    /** Persistence restore only — bypasses transition rules. */
    public void restorePersistedState(
            PhysicalIntentStatus status,
            int retryCount,
            String failureReason,
            long lastUpdateTicks
    ) {
        this.status = status == null ? PhysicalIntentStatus.PLANNED : status;
        this.retryCount = Math.max(0, retryCount);
        this.failureReason = failureReason == null ? "" : failureReason;
        this.lastUpdateTicks = lastUpdateTicks;
    }

    public void markChunkPending(int chunkX, int chunkZ) {
        if (pendingChunkKeys.size() >= MAX_CHUNK_SLICES) {
            return;
        }
        pendingChunkKeys.add(chunkKey(chunkX, chunkZ));
    }

    public void markChunkApplied(int chunkX, int chunkZ) {
        long key = chunkKey(chunkX, chunkZ);
        pendingChunkKeys.remove(key);
        appliedChunkKeys.add(key);
    }

    public boolean allSlicesApplied() {
        return pendingChunkKeys.isEmpty() && !appliedChunkKeys.isEmpty();
    }

    public void addDependency(PhysicalIntentId dep) {
        if (dep != null && dependencies.size() < MAX_DEPENDENCIES) {
            dependencies.add(dep);
        }
    }

    public List<Long> orderedPendingChunks() {
        List<Long> keys = new ArrayList<>(pendingChunkKeys);
        keys.sort(Long::compareTo);
        return keys;
    }

    public static long chunkKey(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) ^ (chunkZ & 0xffffffffL);
    }

    public static int chunkX(long key) {
        return (int) (key >> 32);
    }

    public static int chunkZ(long key) {
        return (int) key;
    }
}
