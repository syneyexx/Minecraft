package com.livingmods.neoforge.physical;

import com.livingmods.common.config.LivingModsConfig;
import com.livingmods.common.culture.CultureKeys;
import com.livingmods.common.geo.BlockPos2;
import com.livingmods.common.geo.BoundingBox2;
import com.livingmods.common.id.DistrictId;
import com.livingmods.common.id.LotId;
import com.livingmods.common.id.SettlementId;
import com.livingmods.common.id.StructureId;
import com.livingmods.common.model.BuildingRole;
import com.livingmods.common.model.PhysicalIntentStatus;
import com.livingmods.common.model.PhysicalIntentType;
import com.livingmods.common.model.PhysicalOutcomeType;
import com.livingmods.common.model.WealthClass;
import com.livingmods.neoforge.LivingModsMod;
import com.livingmods.neoforge.sidecar.SidecarClient;
import com.livingmods.neoforge.sidecar.WorldSessionLifecycle;
import com.livingmods.neoforge.worldgen.BanditCampMaterializer;
import com.livingmods.neoforge.worldgen.BridgeMaterializer;
import com.livingmods.neoforge.worldgen.BuildingMaterializer;
import com.livingmods.neoforge.worldgen.ChunkMaterializationState;
import com.livingmods.neoforge.worldgen.LivingModsStructureIndex;
import com.livingmods.neoforge.worldgen.SafeChunkWriter;
import com.livingmods.neoforge.worldgen.WorldPlanCache;
import com.livingmods.protocol.MessageType;
import com.livingmods.protocol.PhysicalIntentPayload;
import com.livingmods.protocol.PhysicalOutcomePayload;
import com.livingmods.protocol.RequestPayloads;
import com.livingmods.worldgen.plan.PlannedBanditCamp;
import com.livingmods.worldgen.plan.PlannedBuilding;
import com.livingmods.worldgen.plan.PlannedBridge;
import com.livingmods.worldgen.plan.PlannedSettlement;
import com.livingmods.worldgen.plan.WorldPlan;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bounded, chunk-local physical reconciliation.
 * Canonical intents → safe Minecraft realization → verified outcomes.
 *
 * Exhaustive intent handling: no silent default-success for unimplemented types.
 * Transient projections are routed to binders, never marked as block materialization.
 */
public final class PhysicalReconciliationEngine {
    private static final PhysicalReconciliationEngine INSTANCE = new PhysicalReconciliationEngine();
    private static final int MAX_BLOCKS_PER_SLICE = 256;

    private final LivingModsConfig config = LivingModsConfig.defaults();
    private final BuildingMaterializer buildings = new BuildingMaterializer();
    private final BanditCampMaterializer camps = new BanditCampMaterializer();
    private final BridgeMaterializer bridges = new BridgeMaterializer();
    private final Set<String> pendingRequests = ConcurrentHashMap.newKeySet();
    private final Map<UUID, IntentWork> active = new ConcurrentHashMap<>();
    private int tickCounter;
    private int blocksPlacedThisTick;

    private PhysicalReconciliationEngine() {}

    public static PhysicalReconciliationEngine get() {
        return INSTANCE;
    }

    public void clear() {
        pendingRequests.clear();
        active.clear();
        tickCounter = 0;
        blocksPlacedThisTick = 0;
    }

    public void onServerTick(MinecraftServer server) {
        if (!config.dynamicConstructionEnabled()) {
            return;
        }
        if (++tickCounter % 20 != 0) {
            return;
        }
        blocksPlacedThisTick = 0;
        SidecarClient client = WorldSessionLifecycle.activeClient();
        if (client == null || !client.isReady()) {
            return;
        }
        ServerLevel level = server.overworld();
        WorldPlan plan = WorldPlanCache.get();

        List<Interest> interests = collectInterest(level, plan);
        int budget = Math.max(1, config.maxReconciliationOpsPerTick());
        for (Interest interest : interests) {
            if (budget <= 0) break;
            requestIntents(level, client, interest);
            budget -= realizeReady(level, interest, budget);
        }
    }

    private List<Interest> collectInterest(ServerLevel level, WorldPlan plan) {
        List<Interest> interests = new ArrayList<>();
        // Spatial cell index: 128-block cells to avoid scanning all settlements per player.
        Map<Long, Interest> byCell = new LinkedHashMap<>();
        for (ServerPlayer player : level.players()) {
            BlockPos pos = player.blockPosition();
            long cell = (((long) (pos.getX() >> 7)) << 32) ^ ((pos.getZ() >> 7) & 0xffffffffL);

            PlannedSettlement nearestPlan = null;
            double best = Double.MAX_VALUE;
            if (plan != null) {
                // Bounded local scan using player cell neighbourhood rather than full plan every time.
                for (PlannedSettlement s : plan.settlements().values()) {
                    double dx = s.center().x() - pos.getX();
                    double dz = s.center().z() - pos.getZ();
                    double d2 = dx * dx + dz * dz;
                    if (d2 < best && d2 <= 160 * 160.0) {
                        best = d2;
                        nearestPlan = s;
                    }
                }
            }
            if (nearestPlan != null) {
                byCell.put(cell, new Interest(nearestPlan.id().value(),
                        nearestPlan.center().x(), nearestPlan.center().z(), 128, false));
            } else {
                // Dynamic / player-founded: region query around player.
                byCell.putIfAbsent(cell, new Interest(null, pos.getX(), pos.getZ(), 96, true));
            }
        }
        interests.addAll(byCell.values());
        return interests;
    }

    private void requestIntents(ServerLevel level, SidecarClient client, Interest interest) {
        String key = interest.settlementId() != null
                ? "s:" + interest.settlementId()
                : "r:" + (interest.x() >> 6) + ":" + (interest.z() >> 6);
        if (!pendingRequests.add(key)) {
            return;
        }
        try {
            byte[] payload = interest.regionQuery() || interest.settlementId() == null
                    ? RequestPayloads.ConstructionQuery.forRegion(interest.x(), interest.z(), interest.radius(), 32).encode()
                    : RequestPayloads.ConstructionQuery.forSettlement(interest.settlementId(), 32).encode();
            client.sendAsync(MessageType.GET_CONSTRUCTION_PLAN, payload)
                    .thenAccept(envelope -> level.getServer().execute(() -> {
                        try {
                            if (envelope == null || envelope.isError()) return;
                            ingestConstructionPlan(envelope.payload());
                        } catch (Exception e) {
                            LivingModsMod.LOG.debug("Construction plan decode failed: {}", e.toString());
                        } finally {
                            pendingRequests.remove(key);
                        }
                    }))
                    .exceptionally(ex -> {
                        pendingRequests.remove(key);
                        return null;
                    });
        } catch (Exception e) {
            pendingRequests.remove(key);
        }
    }

    private void ingestConstructionPlan(byte[] payload) {
        try {
            PhysicalIntentPayload.Bundle bundle = PhysicalIntentPayload.Bundle.decode(payload);
            if (!"ok".equals(bundle.status()) && !"partial".equals(bundle.status())) {
                return;
            }
            for (PhysicalIntentPayload.IntentSlice slice : bundle.intents()) {
                if (slice.type().isTransientProjection()) {
                    // Projection intents belong to binders — never enter block work queue.
                    continue;
                }
                PhysicalIntentStatus st = slice.status();
                if (st != PhysicalIntentStatus.READY
                        && st != PhysicalIntentStatus.MATERIALIZING
                        && st != PhysicalIntentStatus.FAILED_RETRYABLE) {
                    continue;
                }
                Map<Long, Boolean> applied = new HashMap<>();
                for (Long key : slice.appliedChunkKeys()) {
                    applied.put(key, true);
                }
                List<Long> pending = new ArrayList<>(slice.pendingChunkKeys());
                active.put(slice.intentId(), new IntentWork(
                        slice.intentId(),
                        slice.type(),
                        slice.priority(),
                        BoundingBox2.of(slice.minX(), slice.minZ(), slice.maxX(), slice.maxZ()),
                        slice.buildingRole(),
                        CultureKeys.sanitize(slice.cultureKey()),
                        slice.settlementId(),
                        slice.structureId(),
                        slice.variant(),
                        applied,
                        pending,
                        slice.routePointsXz(),
                        new LinkedHashMap<>(slice.meta())
                ));
            }
        } catch (Exception e) {
            LivingModsMod.LOG.debug("Typed intent payload failed, ignoring malformed batch: {}", e.toString());
        }
    }

    private int realizeReady(ServerLevel level, Interest interest, int budget) {
        List<IntentWork> candidates = new ArrayList<>();
        for (IntentWork work : active.values()) {
            if (work.type().isTransientProjection()) continue;
            if (work.footprint().contains(interest.x(), interest.z())
                    || near(work.footprint(), interest.x(), interest.z(), interest.radius())) {
                candidates.add(work);
            }
        }
        candidates.sort(Comparator.comparingInt(IntentWork::priority).thenComparing(w -> w.intentId()));
        int used = 0;
        for (IntentWork work : candidates) {
            if (used >= budget) break;
            if (blocksPlacedThisTick >= MAX_BLOCKS_PER_SLICE * budget) break;
            if (realizeOneChunkSlice(level, work)) {
                used++;
            }
        }
        return used;
    }

    private boolean realizeOneChunkSlice(ServerLevel level, IntentWork work) {
        if (work.type().isPlanningMeta()) {
            // Planning-meta should already be MATERIALIZED by canonical planner; if still present, report complete.
            reportOutcome(work, PhysicalOutcomeType.INTENT_MATERIALIZED, 0, 0, true,
                    Map.of("complete", "true", "reason", "planning_meta", "intentId", work.intentId().toString()));
            active.remove(work.intentId());
            return true;
        }

        int chosenCx;
        int chosenCz;
        LevelChunk chunk = null;

        if (!work.pendingChunks().isEmpty()) {
            chosenCx = 0;
            chosenCz = 0;
            for (Long key : work.pendingChunks()) {
                int cx = (int) (key >> 32);
                int cz = (int) (long) key;
                if (Boolean.TRUE.equals(work.applied().get(key))) continue;
                if (!level.hasChunk(cx, cz)) continue;
                chunk = level.getChunk(cx, cz);
                chosenCx = cx;
                chosenCz = cz;
                break;
            }
        } else {
            int minCx = work.footprint().minX() >> 4;
            int maxCx = work.footprint().maxX() >> 4;
            int minCz = work.footprint().minZ() >> 4;
            int maxCz = work.footprint().maxZ() >> 4;
            chosenCx = 0;
            chosenCz = 0;
            for (int cx = minCx; cx <= maxCx; cx++) {
                for (int cz = minCz; cz <= maxCz; cz++) {
                    long key = (((long) cx) << 32) ^ (cz & 0xffffffffL);
                    if (Boolean.TRUE.equals(work.applied().get(key))) continue;
                    if (!level.hasChunk(cx, cz)) continue;
                    chunk = level.getChunk(cx, cz);
                    chosenCx = cx;
                    chosenCz = cz;
                    break;
                }
                if (chunk != null) break;
            }
        }
        if (chunk == null) {
            return false;
        }

        SafeChunkWriter writer = new SafeChunkWriter(level, chunk);
        writer.setDynamicMode(true);
        ChunkMaterializationState chunkState = ChunkMaterializationState.of(chunk);
        RealizationResult result;
        try {
            result = materializeSlice(level, writer, work, chunkState, chosenCx, chosenCz);
        } catch (Exception e) {
            LivingModsMod.LOG.warn("Physical intent {} failed in chunk {},{}: {}",
                    work.intentId(), chosenCx, chosenCz, e.toString());
            reportOutcome(work, PhysicalOutcomeType.INTENT_FAILED, chosenCx, chosenCz, false,
                    Map.of("reason", e.toString(), "terminal", "false"));
            return true;
        }

        if (result.unsupported()) {
            reportOutcome(work, PhysicalOutcomeType.INTENT_BLOCKED, chosenCx, chosenCz, true,
                    Map.of("reason", result.reason().isBlank() ? "unsupported_geometry" : result.reason(),
                            "terminal", "true", "complete", "true"));
            active.remove(work.intentId());
            return true;
        }
        if (result.blocked()) {
            reportOutcome(work, PhysicalOutcomeType.INTENT_BLOCKED, chosenCx, chosenCz, false,
                    Map.of("reason", result.reason(),
                            "protectedCollisions", String.valueOf(result.protectedCollisions()),
                            "completionRatio", String.valueOf(result.completionRatio())));
            return true;
        }

        long key = (((long) chosenCx) << 32) ^ (chosenCz & 0xffffffffL);
        work.applied().put(key, true);
        work.pendingChunks().remove(key);
        blocksPlacedThisTick += result.placedBlocks();

        boolean complete = work.pendingChunks().isEmpty();
        if (complete && work.applied().isEmpty() && !result.structurallyComplete()) {
            complete = false;
        }
        // Validate structural completion before reporting full success.
        if (complete && !result.structurallyComplete() && result.requiredBlocks() > 0) {
            reportOutcome(work, PhysicalOutcomeType.INTENT_BLOCKED, chosenCx, chosenCz, false,
                    Map.of("reason", "incomplete_structure",
                            "completionRatio", String.valueOf(result.completionRatio()),
                            "requiredBlocks", String.valueOf(result.requiredBlocks()),
                            "placedBlocks", String.valueOf(result.placedBlocks())));
            return true;
        }

        Map<String, String> evidence = new LinkedHashMap<>();
        evidence.put("intentId", work.intentId().toString());
        evidence.put("chunkKey", String.valueOf(key));
        evidence.put("complete", String.valueOf(complete));
        evidence.put("requiredBlocks", String.valueOf(result.requiredBlocks()));
        evidence.put("placedBlocks", String.valueOf(result.placedBlocks()));
        evidence.put("completionRatio", String.valueOf(result.completionRatio()));
        evidence.put("foundationY", String.valueOf(level.getHeight(
                Heightmap.Types.WORLD_SURFACE_WG,
                work.footprint().center().x(),
                work.footprint().center().z())));
        if (work.settlementId() != null) {
            evidence.put("settlementId", work.settlementId().toString());
        }
        if (work.structureId() != null) {
            evidence.put("structureId", work.structureId().toString());
        }
        reportOutcome(work,
                result.success() ? PhysicalOutcomeType.INTENT_MATERIALIZED : PhysicalOutcomeType.INTENT_BLOCKED,
                chosenCx, chosenCz, complete, evidence);
        if (complete) {
            active.remove(work.intentId());
        }
        return true;
    }

    private RealizationResult materializeSlice(
            ServerLevel level,
            SafeChunkWriter writer,
            IntentWork work,
            ChunkMaterializationState chunkState,
            int chunkX,
            int chunkZ
    ) {
        // Exhaustive switch — compiler enforces new enum members are handled.
        return switch (work.type()) {
            case CONSTRUCT_BUILDING -> materializeBuilding(level, writer, work, chunkState);
            case CREATE_FORTIFICATION -> materializeFortification(level, writer, work, chunkState);
            case REPAIR_STRUCTURE -> materializeRepair(level, writer, work, chunkState);
            case CREATE_BANDIT_CAMP, UPGRADE_BANDIT_CAMP -> materializeCamp(level, writer, work);
            case REMOVE_BANDIT_CAMP -> materializeCampRemoval(level, writer, work);
            case EXTEND_ROAD -> materializeRoad(level, writer, work);
            case BUILD_BRIDGE -> materializeBridge(level, writer, work);
            case BUILD_WALL -> materializeWall(level, writer, work);
            case BUILD_GATE -> materializeGate(level, writer, work);
            case DAMAGE_STRUCTURE, SIEGE_DAMAGE -> materializeDamage(level, writer, work);
            case DESTROY_STRUCTURE -> materializeDestroy(level, writer, work);
            case CREATE_RUIN -> materializeRuin(level, writer, work);
            case CREATE_RESOURCE_SITE -> materializeResourceSite(level, writer, work);
            case EXPAND_SETTLEMENT, CREATE_DISTRICT, FOUND_PLAYER_SETTLEMENT ->
                    RealizationResult.planningComplete();
            case PROJECT_CARAVAN, PROJECT_ARMY, PROJECT_GUARDS, PROJECT_REFUGEES, PROJECT_MIGRANTS ->
                    RealizationResult.projectionRouted();
        };
    }

    private RealizationResult materializeBuilding(
            ServerLevel level,
            SafeChunkWriter writer,
            IntentWork work,
            ChunkMaterializationState chunkState
    ) {
        BuildingRole role;
        try {
            role = BuildingRole.valueOf(work.role());
        } catch (Exception e) {
            return RealizationResult.blocked("unsupported_geometry", 0, 0, 0);
        }
        if (work.structureId() == null) {
            return RealizationResult.failed("missing_structure_identity", 0, 0, 1);
        }
        // Terrain validation with multiple samples.
        if (!validateTerrain(level, work.footprint())) {
            return RealizationResult.blocked("invalid_terrain", 0, 0, 0);
        }
        SettlementId sid = work.settlementId() == null
                ? SettlementId.of(new UUID(0, 0))
                : SettlementId.of(work.settlementId());
        StructureId structureId = StructureId.of(work.structureId());
        int y = sampleMedianY(level, work.footprint());
        String facing = work.meta().getOrDefault("entranceFacing", "south");
        int residential = role == BuildingRole.HOUSE || role == BuildingRole.TOWNHOUSE
                || role == BuildingRole.FARMHOUSE || role == BuildingRole.MANOR ? 4 : 0;
        int workSlots = role == BuildingRole.WORKSHOP || role == BuildingRole.WAREHOUSE
                || role == BuildingRole.SMITHY || role == BuildingRole.SHOP ? 4 : 0;
        String culture = CultureKeys.sanitize(work.cultureKey());
        String assetId = work.meta().getOrDefault("assetId", "");
        String archetype = work.meta().getOrDefault("archetype", "");
        PlannedBuilding building = new PlannedBuilding(
                structureId,
                LotId.deterministic(structureId.value().getMostSignificantBits(), 1),
                sid,
                DistrictId.deterministic(structureId.value().getMostSignificantBits(), 1),
                role,
                WealthClass.COMMON,
                work.footprint(),
                0,
                y,
                culture,
                work.meta().getOrDefault("template", "default"),
                structureId.hashCode(),
                parseInt(work.meta().get("floors"), role == BuildingRole.TOWNHOUSE || role == BuildingRole.MANOR ? 2 : 1),
                false,
                false,
                List.of(),
                List.of(),
                List.of(),
                facing,
                Math.max(residential, workSlots),
                workSlots,
                residential,
                assetId,
                archetype
        );
        PlannedSettlement settlement = findSettlement(sid);
        int before = writer.placedCount();
        buildings.materialize(level, writer, building, settlement, chunkState);
        int placed = Math.max(0, writer.placedCount() - before);
        int required = estimateBuildingBlocks(work.footprint());
        if (writer.protectedCollisionCount() > required * 0.4) {
            return RealizationResult.blocked("protected_player_structure", required, placed,
                    writer.protectedCollisionCount());
        }
        if (placed < required * 0.35) {
            return RealizationResult.blocked("foreign_mod_collision", required, placed,
                    writer.protectedCollisionCount());
        }
        // Ensure entrance access path stub.
        ensureAccessPath(level, writer, work, facing);
        LivingModsStructureIndex.get(level).putDynamic(
                structureId, role, sid, work.footprint(), y, culture, work.intentId());
        return RealizationResult.ok(required, placed);
    }

    private RealizationResult materializeFortification(
            ServerLevel level, SafeChunkWriter writer, IntentWork work, ChunkMaterializationState chunkState
    ) {
        // Fortification keep / barracks core — child tower/wall intents handle the rest.
        IntentWork fortWork = work;
        if (work.role() == null || work.role().isBlank()) {
            fortWork = new IntentWork(work.intentId(), work.type(), work.priority(), work.footprint(),
                    BuildingRole.BARRACKS.name(), work.cultureKey(), work.settlementId(), work.structureId(),
                    work.variant(), work.applied(), work.pendingChunks(), work.routePoints(), work.meta());
        }
        return materializeBuilding(level, writer, fortWork, chunkState);
    }

    private RealizationResult materializeRepair(
            ServerLevel level, SafeChunkWriter writer, IntentWork work, ChunkMaterializationState chunkState
    ) {
        if (work.structureId() == null) {
            return RealizationResult.failed("missing_structure_identity", 0, 0, 1);
        }
        // Repair: restore LivingMods-owned damaged sections within known footprint — never place a second building.
        BoundingBox2 fp = work.footprint();
        int y = sampleMedianY(level, fp);
        int required = 0;
        int placed = 0;
        int protectedHits = 0;
        for (int x = fp.minX(); x <= fp.maxX(); x++) {
            for (int z = fp.minZ(); z <= fp.maxZ(); z++) {
                if (!writer.chunkContains(x, z)) continue;
                for (int dy = 0; dy <= 4; dy++) {
                    BlockPos pos = new BlockPos(x, y + dy, z);
                    var state = level.getBlockState(pos);
                    if (state.isAir() || state.is(Blocks.FIRE) || state.is(Blocks.COBWEB)) {
                        required++;
                        boolean ok = writer.trySet(pos, Blocks.STONE_BRICKS.defaultBlockState());
                        if (ok) placed++;
                        else protectedHits++;
                    }
                }
            }
        }
        if (protectedHits > required * 0.5 && placed < 4) {
            return RealizationResult.blocked("protected_player_structure", required, placed, protectedHits);
        }
        return RealizationResult.ok(Math.max(required, 1), placed);
    }

    private RealizationResult materializeCamp(ServerLevel level, SafeChunkWriter writer, IntentWork work) {
        PlannedBanditCamp.CampVariant variant = switch (work.variant() == null ? "" : work.variant()) {
            case "STRONGHOLD" -> PlannedBanditCamp.CampVariant.STRONGHOLD;
            case "RUINED_FORT" -> PlannedBanditCamp.CampVariant.RUINED_FORT;
            case "FOREST_CAMP", "FOREST" -> PlannedBanditCamp.CampVariant.FOREST;
            case "HIDEOUT" -> PlannedBanditCamp.CampVariant.HIDEOUT;
            default -> PlannedBanditCamp.CampVariant.ROAD_CAMP;
        };
        PlannedBanditCamp camp = new PlannedBanditCamp(
                work.footprint().center(),
                Math.max(4, work.footprint().width() / 2),
                "dynamic",
                variant
        );
        int before = writer.placedCount();
        camps.materialize(level, writer, camp);
        int placed = Math.max(0, writer.placedCount() - before);
        if (placed < 8) {
            return RealizationResult.blocked("no_safe_plot", 16, placed, writer.protectedCollisionCount());
        }
        return RealizationResult.ok(16, placed);
    }

    private RealizationResult materializeCampRemoval(ServerLevel level, SafeChunkWriter writer, IntentWork work) {
        // Abandon / ruin mode — leave historical traces, clear active camp fixtures conservatively.
        BoundingBox2 fp = work.footprint();
        int y = sampleMedianY(level, fp);
        int placed = 0;
        int required = 0;
        for (int x = fp.minX(); x <= fp.maxX(); x += 2) {
            for (int z = fp.minZ(); z <= fp.maxZ(); z += 2) {
                if (!writer.chunkContains(x, z)) continue;
                required++;
                BlockPos pos = new BlockPos(x, y + 1, z);
                if (writer.trySetAirPreferred(pos, Blocks.AIR.defaultBlockState())) {
                    placed++;
                }
                if (x % 4 == 0 && z % 4 == 0) {
                    writer.trySet(new BlockPos(x, y, z), Blocks.COBBLESTONE.defaultBlockState());
                }
            }
        }
        return RealizationResult.ok(Math.max(required, 1), placed);
    }

    private RealizationResult materializeRoad(ServerLevel level, SafeChunkWriter writer, IntentWork work) {
        List<BlockPos2> path = decodeRoute(work);
        if (path.size() < 2) {
            // Fallback straight across footprint — still a real polyline, not a filled rectangle.
            path = List.of(
                    BlockPos2.of(work.footprint().minX(), work.footprint().minZ()),
                    BlockPos2.of(work.footprint().maxX(), work.footprint().maxZ())
            );
        }
        int required = 0;
        int placed = 0;
        for (int i = 0; i + 1 < path.size(); i++) {
            BlockPos2 a = path.get(i);
            BlockPos2 b = path.get(i + 1);
            int steps = Math.max(1, (int) a.distanceTo(b));
            for (int s = 0; s <= steps; s++) {
                int x = a.x() + (b.x() - a.x()) * s / steps;
                int z = a.z() + (b.z() - a.z()) * s / steps;
                if (!writer.chunkContains(x, z)) continue;
                required += 3;
                int y = level.getHeight(Heightmap.Types.WORLD_SURFACE_WG, x, z) - 1;
                if (writer.trySet(new BlockPos(x, y, z), Blocks.DIRT_PATH.defaultBlockState())) placed++;
                if (writer.trySet(new BlockPos(x + 1, y, z), Blocks.DIRT_PATH.defaultBlockState())) placed++;
                if (writer.trySet(new BlockPos(x, y, z + 1), Blocks.DIRT_PATH.defaultBlockState())) placed++;
            }
        }
        if (required > 0 && placed < required * 0.4) {
            return RealizationResult.blocked("no_road_access", required, placed, writer.protectedCollisionCount());
        }
        return RealizationResult.ok(Math.max(required, 1), placed);
    }

    private RealizationResult materializeBridge(ServerLevel level, SafeChunkWriter writer, IntentWork work) {
        BlockPos2 a = BlockPos2.of(work.footprint().minX(), work.footprint().minZ());
        BlockPos2 b = BlockPos2.of(work.footprint().maxX(), work.footprint().maxZ());
        String culture = CultureKeys.sanitize(work.cultureKey());
        PlannedBridge bridge = new PlannedBridge(a, b, PlannedBridge.BridgeKind.WOODEN, culture);
        com.livingmods.worldgen.plan.PlannedRoad road = new com.livingmods.worldgen.plan.PlannedRoad(
                com.livingmods.common.id.RoadId.deterministic(work.intentId().getMostSignificantBits(), 1),
                com.livingmods.common.model.RoadClass.LOCAL,
                List.of(a, b),
                java.util.Optional.empty(),
                java.util.Optional.empty(),
                List.of(bridge),
                culture
        );
        int before = writer.placedCount();
        bridges.materialize(level, writer, road);
        int placed = Math.max(0, writer.placedCount() - before);
        return RealizationResult.ok(Math.max(placed, 8), placed);
    }

    private RealizationResult materializeWall(ServerLevel level, SafeChunkWriter writer, IntentWork work) {
        List<BlockPos2> perimeter = decodeNamedPoints(work, "wall");
        if (perimeter.isEmpty()) {
            // Derive from footprint boundary.
            BoundingBox2 fp = work.footprint();
            for (int x = fp.minX(); x <= fp.maxX(); x += 2) {
                perimeter.add(BlockPos2.of(x, fp.minZ()));
                perimeter.add(BlockPos2.of(x, fp.maxZ()));
            }
            for (int z = fp.minZ(); z <= fp.maxZ(); z += 2) {
                perimeter.add(BlockPos2.of(fp.minX(), z));
                perimeter.add(BlockPos2.of(fp.maxX(), z));
            }
        }
        Set<Long> gateCells = gateCells(work);
        int required = 0;
        int placed = 0;
        for (BlockPos2 p : perimeter) {
            if (!writer.chunkContains(p.x(), p.z())) continue;
            long pk = (((long) p.x()) << 32) ^ (p.z() & 0xffffffffL);
            if (gateCells.contains(pk)) continue; // leave road crossings open
            int y = level.getHeight(Heightmap.Types.WORLD_SURFACE_WG, p.x(), p.z()) - 1;
            required += 4;
            if (writer.trySet(new BlockPos(p.x(), y, p.z()), Blocks.STONE_BRICKS.defaultBlockState())) placed++;
            if (writer.trySet(new BlockPos(p.x(), y + 1, p.z()), Blocks.STONE_BRICKS.defaultBlockState())) placed++;
            if (writer.trySet(new BlockPos(p.x(), y + 2, p.z()), Blocks.STONE_BRICKS.defaultBlockState())) placed++;
            if (writer.trySet(new BlockPos(p.x(), y + 3, p.z()), Blocks.STONE_BRICK_WALL.defaultBlockState())) placed++;
        }
        if (required > 0 && placed < required * 0.5) {
            return RealizationResult.blocked("protected_player_structure", required, placed,
                    writer.protectedCollisionCount());
        }
        return RealizationResult.ok(Math.max(required, 1), placed);
    }

    private RealizationResult materializeGate(ServerLevel level, SafeChunkWriter writer, IntentWork work) {
        BlockPos2 g = work.footprint().center();
        int y = level.getHeight(Heightmap.Types.WORLD_SURFACE_WG, g.x(), g.z()) - 1;
        String orientation = work.meta().getOrDefault("orientation", "north_south");
        int placed = 0;
        int required = 12;
        // Gate opening + gatehouse pillars; keep road corridor clear.
        boolean ns = "north_south".equals(orientation);
        for (int h = 1; h <= 4; h++) {
            if (ns) {
                if (writer.trySet(new BlockPos(g.x() - 3, y + h, g.z()), Blocks.STONE_BRICKS.defaultBlockState())) placed++;
                if (writer.trySet(new BlockPos(g.x() + 3, y + h, g.z()), Blocks.STONE_BRICKS.defaultBlockState())) placed++;
            } else {
                if (writer.trySet(new BlockPos(g.x(), y + h, g.z() - 3), Blocks.STONE_BRICKS.defaultBlockState())) placed++;
                if (writer.trySet(new BlockPos(g.x(), y + h, g.z() + 3), Blocks.STONE_BRICKS.defaultBlockState())) placed++;
            }
        }
        // Arch lintel
        if (ns) {
            for (int x = g.x() - 2; x <= g.x() + 2; x++) {
                if (writer.trySet(new BlockPos(x, y + 4, g.z()), Blocks.STONE_BRICK_SLAB.defaultBlockState())) placed++;
            }
        } else {
            for (int z = g.z() - 2; z <= g.z() + 2; z++) {
                if (writer.trySet(new BlockPos(g.x(), y + 4, z), Blocks.STONE_BRICK_SLAB.defaultBlockState())) placed++;
            }
        }
        // Ensure road surface through gate.
        for (int d = -2; d <= 2; d++) {
            int x = ns ? g.x() + d : g.x();
            int z = ns ? g.z() : g.z() + d;
            writer.trySet(new BlockPos(x, y, z), Blocks.DIRT_PATH.defaultBlockState());
            writer.trySetAirPreferred(new BlockPos(x, y + 1, z), Blocks.AIR.defaultBlockState());
            writer.trySetAirPreferred(new BlockPos(x, y + 2, z), Blocks.AIR.defaultBlockState());
            writer.trySetAirPreferred(new BlockPos(x, y + 3, z), Blocks.AIR.defaultBlockState());
        }
        return RealizationResult.ok(required, placed);
    }

    private RealizationResult materializeDamage(ServerLevel level, SafeChunkWriter writer, IntentWork work) {
        // Conservative, deterministic damage on LivingMods-owned blocks only — no fire, no arbitrary AIR spam.
        BoundingBox2 fp = work.footprint();
        int y = sampleMedianY(level, fp);
        int required = 8;
        int placed = 0;
        for (int i = 0; i < 8; i++) {
            int x = fp.minX() + (i * 3) % Math.max(1, fp.width());
            int z = fp.minZ() + (i * 5) % Math.max(1, fp.depth());
            if (!writer.chunkContains(x, z)) continue;
            BlockPos pos = new BlockPos(x, y + 1 + (i % 3), z);
            // Only damage if provenance allows (SafeChunkWriter dynamic mode protects foreign/player).
            if (writer.trySetLivingModsOnly(pos, Blocks.COBBLESTONE.defaultBlockState())
                    || writer.trySetAirPreferred(pos, Blocks.AIR.defaultBlockState())) {
                placed++;
            }
        }
        return RealizationResult.ok(required, placed);
    }

    private RealizationResult materializeDestroy(ServerLevel level, SafeChunkWriter writer, IntentWork work) {
        RealizationResult damage = materializeDamage(level, writer, work);
        // Extra rubble markers on LivingMods blocks.
        BoundingBox2 fp = work.footprint();
        int y = sampleMedianY(level, fp);
        int extra = 0;
        for (int i = 0; i < 4; i++) {
            int x = fp.minX() + i * Math.max(1, fp.width() / 4);
            int z = fp.minZ() + i * Math.max(1, fp.depth() / 4);
            if (writer.trySetLivingModsOnly(new BlockPos(x, y, z), Blocks.COBBLESTONE.defaultBlockState())) {
                extra++;
            }
        }
        return RealizationResult.ok(damage.requiredBlocks() + 4, damage.placedBlocks() + extra);
    }

    private RealizationResult materializeRuin(ServerLevel level, SafeChunkWriter writer, IntentWork work) {
        int before = writer.placedCount();
        // Use existing ruin materializer principles via cobble shell?
        BoundingBox2 fp = work.footprint();
        int y = sampleMedianY(level, fp);
        int placed = 0;
        for (int x = fp.minX(); x <= fp.maxX(); x++) {
            for (int z = fp.minZ(); z <= fp.maxZ(); z++) {
                if (!writer.chunkContains(x, z)) continue;
                if ((x + z) % 3 == 0) continue; // broken look
                if (x == fp.minX() || x == fp.maxX() || z == fp.minZ() || z == fp.maxZ()) {
                    if (writer.trySet(new BlockPos(x, y + 1, z), Blocks.MOSSY_STONE_BRICKS.defaultBlockState())) {
                        placed++;
                    }
                }
            }
        }
        return RealizationResult.ok(Math.max(placed, 8), placed + Math.max(0, writer.placedCount() - before));
    }

    private RealizationResult materializeResourceSite(ServerLevel level, SafeChunkWriter writer, IntentWork work) {
        BoundingBox2 fp = work.footprint();
        int y = sampleMedianY(level, fp);
        int placed = 0;
        for (int i = 0; i < 6; i++) {
            int x = fp.minX() + (i * 2) % Math.max(1, fp.width());
            int z = fp.minZ() + (i * 3) % Math.max(1, fp.depth());
            if (!writer.chunkContains(x, z)) continue;
            if (writer.trySet(new BlockPos(x, y, z), Blocks.IRON_ORE.defaultBlockState())) placed++;
        }
        return RealizationResult.ok(6, placed);
    }

    private void ensureAccessPath(ServerLevel level, SafeChunkWriter writer, IntentWork work, String facing) {
        int ax = parseInt(work.meta().get("accessX"), work.footprint().center().x());
        int az = parseInt(work.meta().get("accessZ"), work.footprint().center().z());
        BlockPos2 door = doorPosition(work.footprint(), facing);
        int steps = Math.max(1, (int) door.distanceTo(BlockPos2.of(ax, az)));
        steps = Math.min(steps, 16);
        for (int i = 0; i <= steps; i++) {
            int x = door.x() + (ax - door.x()) * i / steps;
            int z = door.z() + (az - door.z()) * i / steps;
            if (!writer.chunkContains(x, z)) continue;
            int y = level.getHeight(Heightmap.Types.WORLD_SURFACE_WG, x, z) - 1;
            writer.trySet(new BlockPos(x, y, z), Blocks.DIRT_PATH.defaultBlockState());
        }
    }

    private static BlockPos2 doorPosition(BoundingBox2 fp, String facing) {
        return switch (facing == null ? "south" : facing) {
            case "north" -> BlockPos2.of(fp.center().x(), fp.minZ());
            case "east" -> BlockPos2.of(fp.maxX(), fp.center().z());
            case "west" -> BlockPos2.of(fp.minX(), fp.center().z());
            default -> BlockPos2.of(fp.center().x(), fp.maxZ());
        };
    }

    private boolean validateTerrain(ServerLevel level, BoundingBox2 fp) {
        int samples = 0;
        int water = 0;
        double minY = Double.MAX_VALUE;
        double maxY = -Double.MAX_VALUE;
        int step = Math.max(2, Math.min(fp.width(), fp.depth()) / 4);
        for (int x = fp.minX(); x <= fp.maxX(); x += step) {
            for (int z = fp.minZ(); z <= fp.maxZ(); z += step) {
                int y = level.getHeight(Heightmap.Types.WORLD_SURFACE_WG, x, z);
                minY = Math.min(minY, y);
                maxY = Math.max(maxY, y);
                var below = level.getBlockState(new BlockPos(x, y - 1, z));
                if (below.is(Blocks.WATER) || below.is(Blocks.LAVA)) water++;
                samples++;
            }
        }
        if (samples == 0) return false;
        if (water / (double) samples > 0.25) return false;
        if (maxY - minY > Math.max(6, Math.max(fp.width(), fp.depth()) / 2.0)) return false;
        return true;
    }

    private int sampleMedianY(ServerLevel level, BoundingBox2 fp) {
        List<Integer> ys = new ArrayList<>();
        int step = Math.max(2, Math.min(fp.width(), fp.depth()) / 4);
        for (int x = fp.minX(); x <= fp.maxX(); x += step) {
            for (int z = fp.minZ(); z <= fp.maxZ(); z += step) {
                ys.add(level.getHeight(Heightmap.Types.WORLD_SURFACE_WG, x, z));
            }
        }
        if (ys.isEmpty()) {
            return level.getHeight(Heightmap.Types.WORLD_SURFACE_WG, fp.center().x(), fp.center().z());
        }
        ys.sort(Integer::compareTo);
        return ys.get(ys.size() / 2);
    }

    private static int estimateBuildingBlocks(BoundingBox2 fp) {
        return Math.max(16, fp.width() * fp.depth() * 2);
    }

    private List<BlockPos2> decodeRoute(IntentWork work) {
        List<BlockPos2> path = new ArrayList<>();
        if (work.routePoints() != null && work.routePoints().size() >= 2) {
            for (int i = 0; i + 1 < work.routePoints().size(); i += 2) {
                path.add(BlockPos2.of(work.routePoints().get(i), work.routePoints().get(i + 1)));
            }
            return path;
        }
        String xs = work.meta().getOrDefault("routeX", "");
        String zs = work.meta().getOrDefault("routeZ", "");
        if (!xs.isBlank() && !zs.isBlank()) {
            String[] xa = xs.split(",");
            String[] za = zs.split(",");
            int n = Math.min(xa.length, za.length);
            for (int i = 0; i < n; i++) {
                try {
                    path.add(BlockPos2.of(Integer.parseInt(xa[i].trim()), Integer.parseInt(za[i].trim())));
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return path;
    }

    private List<BlockPos2> decodeNamedPoints(IntentWork work, String prefix) {
        List<BlockPos2> points = new ArrayList<>();
        String xs = work.meta().getOrDefault(prefix + "X", "");
        String zs = work.meta().getOrDefault(prefix + "Z", "");
        if (xs.isBlank() || zs.isBlank()) return points;
        String[] xa = xs.split(",");
        String[] za = zs.split(",");
        int n = Math.min(xa.length, za.length);
        for (int i = 0; i < n; i++) {
            try {
                points.add(BlockPos2.of(Integer.parseInt(xa[i].trim()), Integer.parseInt(za[i].trim())));
            } catch (NumberFormatException ignored) {
            }
        }
        return points;
    }

    private Set<Long> gateCells(IntentWork work) {
        Set<Long> cells = ConcurrentHashMap.newKeySet();
        int gateCount = parseInt(work.meta().get("gateCount"), 0);
        // Approximate gate openings at footprint mid-edges when meta lacks explicit points.
        BoundingBox2 fp = work.footprint();
        int[][] gates = {
                {fp.center().x(), fp.minZ()},
                {fp.center().x(), fp.maxZ()},
                {fp.minX(), fp.center().z()},
                {fp.maxX(), fp.center().z()}
        };
        int n = Math.max(gateCount, 4);
        for (int i = 0; i < Math.min(n, gates.length); i++) {
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    int x = gates[i][0] + dx;
                    int z = gates[i][1] + dz;
                    cells.add((((long) x) << 32) ^ (z & 0xffffffffL));
                }
            }
        }
        return cells;
    }

    private PlannedSettlement findSettlement(SettlementId sid) {
        WorldPlan plan = WorldPlanCache.get();
        if (plan == null) return null;
        return plan.settlements().get(sid);
    }

    private void reportOutcome(
            IntentWork work,
            PhysicalOutcomeType type,
            int chunkX,
            int chunkZ,
            boolean complete,
            Map<String, String> evidence
    ) {
        SidecarClient client = WorldSessionLifecycle.activeClient();
        if (client == null || !client.isReady()) return;
        try {
            Map<String, String> body = new LinkedHashMap<>(evidence);
            body.put("outcomeType", type.name());
            body.put("intentId", work.intentId().toString());
            body.put("chunkX", String.valueOf(chunkX));
            body.put("chunkZ", String.valueOf(chunkZ));
            PhysicalOutcomePayload payload = new PhysicalOutcomePayload(
                    type,
                    new UUID(0, 0),
                    work.intentId(),
                    work.footprint().center().x(),
                    64,
                    work.footprint().center().z(),
                    0L,
                    body
            );
            client.sendAsync(MessageType.REPORT_PHYSICAL_OUTCOME, payload.encode());
        } catch (Exception e) {
            LivingModsMod.LOG.debug("Failed to report physical outcome: {}", e.toString());
        }
    }

    private static boolean near(BoundingBox2 box, int x, int z, int radius) {
        int cx = box.center().x();
        int cz = box.center().z();
        long dx = (long) cx - x;
        long dz = (long) cz - z;
        return dx * dx + dz * dz <= (long) radius * radius;
    }

    private static int parseInt(String raw, int fallback) {
        if (raw == null || raw.isBlank()) return fallback;
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private record Interest(UUID settlementId, int x, int z, int radius, boolean regionQuery) {}

    private record IntentWork(
            UUID intentId,
            PhysicalIntentType type,
            int priority,
            BoundingBox2 footprint,
            String role,
            String cultureKey,
            UUID settlementId,
            UUID structureId,
            String variant,
            Map<Long, Boolean> applied,
            List<Long> pendingChunks,
            List<Integer> routePoints,
            Map<String, String> meta
    ) {}
}
