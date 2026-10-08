package com.livingmods.neoforge.physical;

import com.livingmods.common.config.LivingModsConfig;
import com.livingmods.common.geo.BoundingBox2;
import com.livingmods.common.id.StructureId;
import com.livingmods.common.model.BuildingRole;
import com.livingmods.common.model.PhysicalIntentType;
import com.livingmods.common.model.PhysicalOutcomeType;
import com.livingmods.neoforge.LivingModsMod;
import com.livingmods.neoforge.sidecar.SidecarClient;
import com.livingmods.neoforge.sidecar.WorldSessionLifecycle;
import com.livingmods.neoforge.worldgen.BanditCampMaterializer;
import com.livingmods.neoforge.worldgen.BuildingMaterializer;
import com.livingmods.neoforge.worldgen.ChunkMaterializationState;
import com.livingmods.neoforge.worldgen.LivingModsStructureIndex;
import com.livingmods.neoforge.worldgen.SafeChunkWriter;
import com.livingmods.neoforge.worldgen.WorldPlanCache;
import com.livingmods.protocol.MessageType;
import com.livingmods.protocol.PhysicalOutcomePayload;
import com.livingmods.protocol.PayloadIo;
import com.livingmods.protocol.RequestPayloads;
import com.livingmods.common.id.DistrictId;
import com.livingmods.common.id.LotId;
import com.livingmods.common.id.SettlementId;
import com.livingmods.common.model.WealthClass;
import com.livingmods.worldgen.plan.PlannedBanditCamp;
import com.livingmods.worldgen.plan.PlannedBuilding;
import com.livingmods.worldgen.plan.PlannedSettlement;
import com.livingmods.worldgen.plan.WorldPlan;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
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
 */
public final class PhysicalReconciliationEngine {
    private static final PhysicalReconciliationEngine INSTANCE = new PhysicalReconciliationEngine();

    private final LivingModsConfig config = LivingModsConfig.defaults();
    private final BuildingMaterializer buildings = new BuildingMaterializer();
    private final BanditCampMaterializer camps = new BanditCampMaterializer();
    private final Set<String> pendingSettlementRequests = ConcurrentHashMap.newKeySet();
    private final Map<UUID, IntentWork> active = new ConcurrentHashMap<>();
    private int tickCounter;

    private PhysicalReconciliationEngine() {}

    public static PhysicalReconciliationEngine get() {
        return INSTANCE;
    }

    public void clear() {
        pendingSettlementRequests.clear();
        active.clear();
        tickCounter = 0;
    }

    public void onServerTick(MinecraftServer server) {
        if (!config.dynamicConstructionEnabled()) {
            return;
        }
        if (++tickCounter % 20 != 0) {
            return;
        }
        SidecarClient client = WorldSessionLifecycle.activeClient();
        if (client == null || !client.isReady()) {
            return;
        }
        ServerLevel level = server.overworld();
        WorldPlan plan = WorldPlanCache.get();
        if (plan == null) {
            return;
        }

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
        for (ServerPlayer player : level.players()) {
            BlockPos pos = player.blockPosition();
            PlannedSettlement nearest = null;
            double best = Double.MAX_VALUE;
            for (PlannedSettlement s : plan.settlements().values()) {
                double dx = s.center().x() - pos.getX();
                double dz = s.center().z() - pos.getZ();
                double d2 = dx * dx + dz * dz;
                if (d2 < best && d2 <= 160 * 160.0) {
                    best = d2;
                    nearest = s;
                }
            }
            if (nearest != null) {
                interests.add(new Interest(nearest.id().value(), nearest.center().x(), nearest.center().z(), 128));
            } else {
                // Player-founded / dynamic settlements may not be in WorldPlan.
                interests.add(new Interest(null, pos.getX(), pos.getZ(), 96));
            }
        }
        return interests;
    }

    private void requestIntents(ServerLevel level, SidecarClient client, Interest interest) {
        if (interest.settlementId() == null) {
            return;
        }
        String key = interest.settlementId().toString();
        if (!pendingSettlementRequests.add(key)) {
            return;
        }
        try {
            byte[] payload = new RequestPayloads.SettlementQuery(interest.settlementId()).encode();
            client.sendAsync(MessageType.GET_CONSTRUCTION_PLAN, payload)
                    .thenAccept(envelope -> level.getServer().execute(() -> {
                        try {
                            if (envelope == null || envelope.isError()) return;
                            Map<String, String> data = PayloadIo.decodeStrings(envelope.payload());
                            ingestConstructionPlan(data);
                        } catch (Exception e) {
                            LivingModsMod.LOG.debug("Construction plan decode failed: {}", e.toString());
                        } finally {
                            pendingSettlementRequests.remove(key);
                        }
                    }))
                    .exceptionally(ex -> {
                        pendingSettlementRequests.remove(key);
                        return null;
                    });
        } catch (Exception e) {
            pendingSettlementRequests.remove(key);
        }
    }

    private void ingestConstructionPlan(Map<String, String> data) {
        if (!"ok".equals(data.get("status"))) return;
        String packed = data.getOrDefault("intentsPacked", "");
        if (packed.isBlank()) return;
        for (String row : packed.split(";")) {
            if (row.isBlank()) continue;
            String[] p = row.split("\\|");
            if (p.length < 10) continue;
            try {
                UUID intentId = UUID.fromString(p[0]);
                PhysicalIntentType type = PhysicalIntentType.valueOf(p[1]);
                String status = p[2];
                if (!"READY".equals(status) && !"MATERIALIZING".equals(status) && !"FAILED_RETRYABLE".equals(status)) {
                    continue;
                }
                int priority = Integer.parseInt(p[3]);
                int minX = Integer.parseInt(p[4]);
                int minZ = Integer.parseInt(p[5]);
                int maxX = Integer.parseInt(p[6]);
                int maxZ = Integer.parseInt(p[7]);
                String role = p[8];
                String culture = p.length > 9 ? p[9] : "avalon";
                UUID settlement = p.length > 10 && !p[10].isBlank() ? UUID.fromString(p[10]) : null;
                UUID structure = p.length > 11 && !p[11].isBlank() ? UUID.fromString(p[11]) : intentId;
                String variant = p.length > 12 ? p[12] : "";
                active.put(intentId, new IntentWork(
                        intentId, type, priority,
                        BoundingBox2.of(minX, minZ, maxX, maxZ),
                        role, culture, settlement, structure, variant, new HashMap<>()
                ));
            } catch (Exception e) {
                LivingModsMod.LOG.debug("Bad intent row: {}", e.toString());
            }
        }
    }

    private int realizeReady(ServerLevel level, Interest interest, int budget) {
        List<IntentWork> candidates = new ArrayList<>();
        for (IntentWork work : active.values()) {
            if (work.footprint().contains(interest.x(), interest.z())
                    || near(work.footprint(), interest.x(), interest.z(), interest.radius())) {
                candidates.add(work);
            }
        }
        candidates.sort(Comparator.comparingInt(IntentWork::priority).thenComparing(w -> w.intentId()));
        int used = 0;
        for (IntentWork work : candidates) {
            if (used >= budget) break;
            if (realizeOneChunkSlice(level, work)) {
                used++;
            }
        }
        return used;
    }

    private boolean realizeOneChunkSlice(ServerLevel level, IntentWork work) {
        // Pick one pending chunk that is currently loaded.
        int minCx = work.footprint().minX() >> 4;
        int maxCx = work.footprint().maxX() >> 4;
        int minCz = work.footprint().minZ() >> 4;
        int maxCz = work.footprint().maxZ() >> 4;
        LevelChunk chunk = null;
        int chosenCx = 0;
        int chosenCz = 0;
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
        if (chunk == null) {
            return false; // wait for chunk load — intent stays pending
        }

        SafeChunkWriter writer = new SafeChunkWriter(level, chunk);
        writer.setDynamicMode(true);
        ChunkMaterializationState chunkState = ChunkMaterializationState.of(chunk);
        boolean ok;
        try {
            ok = materializeSlice(level, writer, work, chunkState);
        } catch (Exception e) {
            LivingModsMod.LOG.warn("Physical intent {} failed in chunk {},{}: {}",
                    work.intentId(), chosenCx, chosenCz, e.toString());
            reportOutcome(work, PhysicalOutcomeType.INTENT_FAILED, chosenCx, chosenCz, false,
                    Map.of("reason", e.toString(), "terminal", "false"));
            return true;
        }

        long key = (((long) chosenCx) << 32) ^ (chosenCz & 0xffffffffL);
        work.applied().put(key, true);

        boolean complete = true;
        for (int cx = minCx; cx <= maxCx && complete; cx++) {
            for (int cz = minCz; cz <= maxCz; cz++) {
                long k = (((long) cx) << 32) ^ (cz & 0xffffffffL);
                if (!Boolean.TRUE.equals(work.applied().get(k))) {
                    complete = false;
                    break;
                }
            }
        }

        Map<String, String> evidence = new LinkedHashMap<>();
        evidence.put("intentId", work.intentId().toString());
        evidence.put("chunkKey", String.valueOf(key));
        evidence.put("complete", String.valueOf(complete));
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
        reportOutcome(work, ok ? PhysicalOutcomeType.INTENT_MATERIALIZED : PhysicalOutcomeType.INTENT_BLOCKED,
                chosenCx, chosenCz, complete, evidence);
        if (complete) {
            active.remove(work.intentId());
        }
        return true;
    }

    private boolean materializeSlice(
            ServerLevel level,
            SafeChunkWriter writer,
            IntentWork work,
            ChunkMaterializationState chunkState
    ) {
        return switch (work.type()) {
            case CONSTRUCT_BUILDING, EXPAND_SETTLEMENT, CREATE_DISTRICT, FOUND_PLAYER_SETTLEMENT,
                 CREATE_FORTIFICATION, REPAIR_STRUCTURE -> materializeBuilding(level, writer, work, chunkState);
            case CREATE_BANDIT_CAMP, UPGRADE_BANDIT_CAMP -> materializeCamp(level, writer, work);
            case EXTEND_ROAD, BUILD_BRIDGE -> materializeRoad(level, writer, work);
            case BUILD_WALL, BUILD_GATE -> materializeWall(level, writer, work);
            case DAMAGE_STRUCTURE, SIEGE_DAMAGE, DESTROY_STRUCTURE -> materializeDamage(level, writer, work);
            default -> true;
        };
    }

    private boolean materializeBuilding(
            ServerLevel level,
            SafeChunkWriter writer,
            IntentWork work,
            ChunkMaterializationState chunkState
    ) {
        BuildingRole role;
        try {
            role = BuildingRole.valueOf(work.role());
        } catch (Exception e) {
            role = BuildingRole.HOUSE;
        }
        SettlementId sid = work.settlementId() == null
                ? SettlementId.of(new UUID(0, 0))
                : SettlementId.of(work.settlementId());
        StructureId structureId = StructureId.of(work.structureId() == null ? work.intentId() : work.structureId());
        int y = level.getHeight(Heightmap.Types.WORLD_SURFACE_WG,
                work.footprint().center().x(), work.footprint().center().z());
        int residential = role == BuildingRole.HOUSE || role == BuildingRole.TOWNHOUSE
                || role == BuildingRole.FARMHOUSE || role == BuildingRole.MANOR ? 4 : 0;
        int workSlots = role == BuildingRole.WORKSHOP || role == BuildingRole.WAREHOUSE
                || role == BuildingRole.SMITHY || role == BuildingRole.SHOP ? 4 : 0;
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
                work.cultureKey() == null || work.cultureKey().isBlank() ? "avalon" : work.cultureKey(),
                "default",
                structureId.hashCode(),
                role == BuildingRole.TOWNHOUSE || role == BuildingRole.MANOR ? 2 : 1,
                false,
                false,
                List.of(),
                List.of(),
                List.of(),
                "south",
                Math.max(residential, workSlots),
                workSlots,
                residential
        );
        PlannedSettlement settlement = findSettlement(sid);
        buildings.materialize(level, writer, building, settlement, chunkState);
        LivingModsStructureIndex.get(level).putDynamic(
                structureId, role, sid, work.footprint(), y, work.cultureKey(), work.intentId());
        return true;
    }

    private boolean materializeCamp(ServerLevel level, SafeChunkWriter writer, IntentWork work) {
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
        camps.materialize(level, writer, camp);
        return true;
    }

    private boolean materializeRoad(ServerLevel level, SafeChunkWriter writer, IntentWork work) {
        // Simple beaten path within footprint using existing road materials — no second road engine.
        BlockStatePath.placePath(writer, work.footprint());
        return true;
    }

    private boolean materializeWall(ServerLevel level, SafeChunkWriter writer, IntentWork work) {
        // Perimeter along footprint using stone bricks; skips protected blocks via SafeChunkWriter.
        int y = level.getHeight(Heightmap.Types.WORLD_SURFACE_WG,
                work.footprint().center().x(), work.footprint().center().z());
        BoundingBox2 fp = work.footprint();
        for (int x = fp.minX(); x <= fp.maxX(); x++) {
            trySetWall(writer, x, y, fp.minZ());
            trySetWall(writer, x, y, fp.maxZ());
        }
        for (int z = fp.minZ(); z <= fp.maxZ(); z++) {
            trySetWall(writer, fp.minX(), y, z);
            trySetWall(writer, fp.maxX(), y, z);
        }
        return true;
    }

    private boolean materializeDamage(ServerLevel level, SafeChunkWriter writer, IntentWork work) {
        BoundingBox2 fp = work.footprint();
        int y = level.getHeight(Heightmap.Types.WORLD_SURFACE_WG, fp.center().x(), fp.center().z());
        // Bounded cosmetic damage — not per-tick griefing.
        for (int i = 0; i < 8; i++) {
            int x = fp.minX() + (i * 3) % Math.max(1, fp.width());
            int z = fp.minZ() + (i * 5) % Math.max(1, fp.depth());
            writer.trySetAirPreferred(new BlockPos(x, y + 1 + (i % 3), z),
                    net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
            if (i % 3 == 0) {
                writer.trySetAirPreferred(new BlockPos(x, y + 1, z),
                        net.minecraft.world.level.block.Blocks.FIRE.defaultBlockState());
            }
        }
        return true;
    }

    private static void trySetWall(SafeChunkWriter writer, int x, int y, int z) {
        writer.trySet(new BlockPos(x, y + 1, z),
                net.minecraft.world.level.block.Blocks.STONE_BRICKS.defaultBlockState());
        writer.trySet(new BlockPos(x, y + 2, z),
                net.minecraft.world.level.block.Blocks.STONE_BRICKS.defaultBlockState());
        writer.trySet(new BlockPos(x, y + 3, z),
                net.minecraft.world.level.block.Blocks.STONE_BRICK_WALL.defaultBlockState());
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

    private record Interest(UUID settlementId, int x, int z, int radius) {}

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
            Map<Long, Boolean> applied
    ) {}

    /** Minimal path placer for dynamic road stubs. */
    private static final class BlockStatePath {
        static void placePath(SafeChunkWriter writer, BoundingBox2 fp) {
            int x0 = fp.minX();
            int z0 = fp.minZ();
            int x1 = fp.maxX();
            int z1 = fp.maxZ();
            int steps = Math.max(Math.abs(x1 - x0), Math.abs(z1 - z0));
            for (int i = 0; i <= steps; i++) {
                int x = x0 + (x1 - x0) * i / Math.max(1, steps);
                int z = z0 + (z1 - z0) * i / Math.max(1, steps);
                int y = writer.level().getHeight(Heightmap.Types.WORLD_SURFACE_WG, x, z) - 1;
                writer.trySet(new BlockPos(x, y, z),
                        net.minecraft.world.level.block.Blocks.DIRT_PATH.defaultBlockState());
            }
        }
    }
}
