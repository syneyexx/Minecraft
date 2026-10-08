package com.livingmods.neoforge.entity;

import com.livingmods.common.config.LivingModsConfig;
import com.livingmods.common.model.ScheduleState;
import com.livingmods.neoforge.LivingModsMod;
import com.livingmods.neoforge.sidecar.SidecarClient;
import com.livingmods.neoforge.sidecar.WorldSessionLifecycle;
import com.livingmods.neoforge.worldgen.WorldPlanCache;
import com.livingmods.protocol.Envelope;
import com.livingmods.protocol.MessageType;
import com.livingmods.protocol.PayloadIo;
import com.livingmods.protocol.RequestPayloads;
import com.livingmods.worldgen.plan.PlannedSettlement;
import com.livingmods.worldgen.plan.WorldPlan;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Interest-based citizen projection lifecycle:
 * player near settlement → IPC GET_PHYSICAL_PROJECTION_PLAN → spawn/bind → maintain → report → despawn.
 * Never 1:1 with canonical population.
 */
public final class CitizenProjectionBinder {
    private static final CitizenProjectionBinder INSTANCE = new CitizenProjectionBinder();

    private final LivingModsConfig config = LivingModsConfig.defaults();
    /** citizenId → entity UUID */
    private final Map<UUID, UUID> projected = new ConcurrentHashMap<>();
    private final Set<String> pendingRequests = ConcurrentHashMap.newKeySet();
    private int tickCounter;
    private volatile long lastPlanRevision;

    private CitizenProjectionBinder() {}

    public static CitizenProjectionBinder get() {
        return INSTANCE;
    }

    public void clear() {
        projected.clear();
        pendingRequests.clear();
        lastPlanRevision = 0L;
        tickCounter = 0;
    }

    public void onServerTick(MinecraftServer server) {
        if (++tickCounter % 40 != 0) {
            return;
        }
        ServerLevel level = server.overworld();
        SidecarClient client = WorldSessionLifecycle.activeClient();
        if (client == null || !client.isReady()) {
            return;
        }
        WorldPlan plan = WorldPlanCache.get();
        if (plan == null) {
            return;
        }

        List<InterestPoint> interests = collectInterest(level, plan);
        if (interests.isEmpty()) {
            despawnOutside(level, List.of());
            return;
        }

        int players = Math.max(1, level.players().size());
        int budget = Math.max(8, Math.min(config.physicalCitizenProjectionCap(), 8 + players * 6));
        int perPoint = Math.max(4, budget / Math.max(1, interests.size()));

        for (InterestPoint interest : interests) {
            requestPlan(level, client, interest, perPoint);
        }
        despawnOutside(level, interests);
    }

    private List<InterestPoint> collectInterest(ServerLevel level, WorldPlan plan) {
        List<InterestPoint> interests = new ArrayList<>();
        int radius = 96;
        for (ServerPlayer player : level.players()) {
            BlockPos pos = player.blockPosition();
            PlannedSettlement nearest = null;
            double best = Double.MAX_VALUE;
            for (PlannedSettlement s : plan.settlements().values()) {
                double dx = s.center().x() - pos.getX();
                double dz = s.center().z() - pos.getZ();
                double d2 = dx * dx + dz * dz;
                if (d2 < best && d2 <= (double) radius * radius) {
                    best = d2;
                    nearest = s;
                }
            }
            if (nearest != null) {
                interests.add(new InterestPoint(
                        nearest.center().x(), nearest.center().z(), radius, playersNear(level, nearest)));
            } else {
                interests.add(new InterestPoint(pos.getX(), pos.getZ(), 48, 1));
            }
        }
        return interests;
    }

    private static int playersNear(ServerLevel level, PlannedSettlement settlement) {
        int n = 0;
        for (ServerPlayer p : level.players()) {
            double dx = p.getX() - settlement.center().x();
            double dz = p.getZ() - settlement.center().z();
            if (dx * dx + dz * dz < 128 * 128) n++;
        }
        return Math.max(1, n);
    }

    private void requestPlan(ServerLevel level, SidecarClient client, InterestPoint interest, int limit) {
        String key = interest.x() + ":" + interest.z();
        if (!pendingRequests.add(key)) {
            return;
        }
        try {
            byte[] payload = new RequestPayloads.NearbyQuery(
                    interest.x(), interest.z(), interest.radius(), limit).encode();
            client.sendAsync(MessageType.GET_PHYSICAL_PROJECTION_PLAN, payload)
                    .thenAccept(envelope -> level.getServer().execute(() -> {
                        try {
                            applyPlan(level, envelope, interest);
                        } finally {
                            pendingRequests.remove(key);
                        }
                    }))
                    .exceptionally(ex -> {
                        pendingRequests.remove(key);
                        LivingModsMod.LOG.debug("Projection plan request failed: {}", ex.toString());
                        return null;
                    });
        } catch (Exception e) {
            pendingRequests.remove(key);
            LivingModsMod.LOG.debug("Projection encode failed: {}", e.toString());
        }
    }

    private void applyPlan(ServerLevel level, Envelope envelope, InterestPoint interest) {
        if (envelope == null || envelope.isError()) {
            return;
        }
        try {
            Map<String, String> data = PayloadIo.decodeStrings(envelope.payload());
            if (!"ok".equals(data.get("status"))) {
                return;
            }
            lastPlanRevision = parseLong(data.get("revision"), lastPlanRevision);
            String packed = data.getOrDefault("citizensPacked", "");
            if (!packed.isBlank()) {
                for (String row : packed.split(";")) {
                    if (row.isBlank()) continue;
                    String[] p = row.split("\\|");
                    if (p.length < 4) continue;
                    UUID cid = UUID.fromString(p[0]);
                    String name = p[1];
                    ScheduleState schedule = parseSchedule(p[2]);
                    int x = Integer.parseInt(p[3]);
                    int y = p.length > 4 ? Integer.parseInt(p[4]) : 64;
                    int z = p.length > 5 ? Integer.parseInt(p[5]) : interest.z();
                    long rev = p.length > 6 ? parseLong(p[6], lastPlanRevision) : lastPlanRevision;
                    String culture = p.length > 7 ? p[7] : "avalon";
                    String profession = p.length > 8 ? p[8] : "FARMER";
                    boolean female = p.length > 9 && "1".equals(p[9]);
                    int age = p.length > 10 ? Integer.parseInt(p[10]) : 25;
                    ensureSpawned(level, new ProjectionCandidate(
                            cid, name, schedule.name(), x, y, z, rev, culture, profession, female, age));
                }
            } else {
                String ids = data.getOrDefault("citizenIds", "");
                int i = 0;
                for (String idStr : ids.split(",")) {
                    if (idStr.isBlank()) continue;
                    String cleaned = idStr.trim();
                    if (cleaned.startsWith("citizen:")) {
                        cleaned = cleaned.substring("citizen:".length());
                    }
                    UUID cid = UUID.fromString(cleaned);
                    ensureSpawned(level, new ProjectionCandidate(
                            cid, "Citizen", ScheduleState.HOME.name(),
                            interest.x() + (i % 5) * 2, -1, interest.z() + (i / 5) * 2,
                            lastPlanRevision, "avalon", "FARMER", false, 25));
                    i++;
                }
            }
            reportPhysical();
        } catch (Exception e) {
            LivingModsMod.LOG.debug("Failed to apply projection plan: {}", e.toString());
        }
    }

    private void ensureSpawned(ServerLevel level, ProjectionCandidate c) {
        UUID existingEntity = projected.get(c.citizenId());
        if (existingEntity != null) {
            Entity entity = level.getEntity(existingEntity);
            if (entity instanceof CitizenEntity citizen) {
                citizen.setProjectionRevision(c.revision());
                citizen.setSchedule(parseSchedule(c.schedule()));
                citizen.setNavigationTarget(c.x() + 0.5, c.y(), c.z() + 0.5);
                return;
            }
            projected.remove(c.citizenId());
        }
        for (CitizenEntity existing : level.getEntitiesOfClass(CitizenEntity.class,
                new AABB(c.x() - 64, 0, c.z() - 64, c.x() + 64, 320, c.z() + 64))) {
            if (c.citizenId().equals(existing.citizenIdOrNull())) {
                projected.put(c.citizenId(), existing.getUUID());
                existing.setProjectionRevision(c.revision());
                existing.setSchedule(parseSchedule(c.schedule()));
                existing.setNavigationTarget(c.x() + 0.5, c.y(), c.z() + 0.5);
                return;
            }
        }
        if (projected.size() >= config.physicalCitizenProjectionCap()) {
            return;
        }
        int y = resolveSafeY(level, c.x(), c.y(), c.z());
        CitizenEntity entity = LivingModsEntities.CITIZEN.get().create(level);
        if (entity == null) {
            return;
        }
        entity.moveTo(c.x() + 0.5, y, c.z() + 0.5, level.random.nextFloat() * 360f, 0);
        entity.bindCitizen(
                c.citizenId(),
                c.displayName(),
                c.revision(),
                parseSchedule(c.schedule()),
                c.cultureKey(),
                c.professionKey(),
                c.female(),
                c.ageYears()
        );
        entity.setNavigationTarget(c.x() + 0.5, y, c.z() + 0.5);
        level.addFreshEntity(entity);
        projected.put(c.citizenId(), entity.getUUID());
    }

    private void despawnOutside(ServerLevel level, List<InterestPoint> interests) {
        List<UUID> remove = new ArrayList<>();
        for (Map.Entry<UUID, UUID> e : projected.entrySet()) {
            Entity entity = level.getEntity(e.getValue());
            if (!(entity instanceof CitizenEntity citizen)) {
                remove.add(e.getKey());
                continue;
            }
            if (insideAny(citizen, interests)) {
                continue;
            }
            reportDespawn(citizen);
            citizen.discard();
            remove.add(e.getKey());
        }
        for (UUID id : remove) {
            projected.remove(id);
        }
    }

    private static boolean insideAny(CitizenEntity citizen, List<InterestPoint> interests) {
        if (interests.isEmpty()) return false;
        for (InterestPoint p : interests) {
            double dx = citizen.getX() - p.x();
            double dz = citizen.getZ() - p.z();
            double r = p.radius() + 24;
            if (dx * dx + dz * dz <= r * r) {
                return true;
            }
        }
        return false;
    }

    private void reportPhysical() {
        SidecarClient client = WorldSessionLifecycle.activeClient();
        if (client == null || !client.isReady()) return;
        try {
            Map<String, String> payload = new HashMap<>();
            payload.put("status", "ok");
            payload.put("projected", String.valueOf(projected.size()));
            payload.put("revision", String.valueOf(lastPlanRevision));
            client.sendAsync(MessageType.REPORT_PHYSICAL_OUTCOME, PayloadIo.encodeStrings(payload));
        } catch (Exception e) {
            LivingModsMod.LOG.debug("REPORT_PHYSICAL_OUTCOME failed: {}", e.toString());
        }
    }

    private void reportDespawn(CitizenEntity citizen) {
        SidecarClient client = WorldSessionLifecycle.activeClient();
        if (client == null || !client.isReady() || citizen.citizenIdOrNull() == null) return;
        try {
            Map<String, String> payload = new HashMap<>();
            payload.put("status", "despawn");
            payload.put("citizenId", citizen.citizenIdOrNull().toString());
            payload.put("revision", String.valueOf(citizen.projectionRevision()));
            client.sendAsync(MessageType.REPORT_PHYSICAL_OUTCOME, PayloadIo.encodeStrings(payload));
        } catch (Exception ignored) {
        }
    }

    /**
     * Resolve a safe standing Y from terrain / building floors.
     * Canonical Y=-1 (or invalid) means Minecraft must decide locally.
     */
    static int resolveSafeY(ServerLevel level, int x, int suggestedY, int z) {
        int surface = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        if (suggestedY <= 0 || suggestedY >= level.getMaxBuildHeight()) {
            return Math.max(level.getMinBuildHeight() + 1, surface);
        }
        // Prefer suggested floor when it is near surface and not inside solid blocks.
        BlockPos feet = new BlockPos(x, suggestedY, z);
        if (level.getBlockState(feet).isAir()
                && level.getBlockState(feet.above()).isAir()
                && !level.getBlockState(feet.below()).isAir()) {
            return suggestedY;
        }
        // Scan downward from suggested toward surface for a standable spot.
        int from = Math.max(suggestedY, surface + 4);
        int to = Math.min(suggestedY, surface) - 8;
        for (int y = from; y >= Math.max(level.getMinBuildHeight() + 1, to); y--) {
            BlockPos p = new BlockPos(x, y, z);
            if (level.getBlockState(p).isAir()
                    && level.getBlockState(p.above()).isAir()
                    && !level.getBlockState(p.below()).isAir()
                    && !level.getBlockState(p.below()).liquid()) {
                return y;
            }
        }
        return Math.max(level.getMinBuildHeight() + 1, surface);
    }

    private static ScheduleState parseSchedule(String raw) {
        try {
            return ScheduleState.valueOf(raw);
        } catch (Exception e) {
            return ScheduleState.HOME;
        }
    }

    private static long parseLong(String raw, long fallback) {
        if (raw == null || raw.isBlank()) return fallback;
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    public record ProjectionCandidate(
            UUID citizenId,
            String displayName,
            String schedule,
            int x,
            int y,
            int z,
            long revision,
            String cultureKey,
            String professionKey,
            boolean female,
            int ageYears
    ) {}

    private record InterestPoint(int x, int z, int radius, int playerDensity) {}
}
