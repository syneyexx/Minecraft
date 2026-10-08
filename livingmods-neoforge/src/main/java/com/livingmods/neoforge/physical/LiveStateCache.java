package com.livingmods.neoforge.physical;

import com.livingmods.neoforge.LivingModsMod;
import com.livingmods.neoforge.sidecar.SidecarClient;
import com.livingmods.neoforge.sidecar.WorldSessionLifecycle;
import com.livingmods.neoforge.worldgen.WorldPlanCache;
import com.livingmods.protocol.MessageType;
import com.livingmods.protocol.PayloadIo;
import com.livingmods.protocol.RequestPayloads;
import com.livingmods.worldgen.plan.WorldPlan;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Async live-summary + terrain tile cache.
 * Map/dashboard reads never block the server thread on sidecar IPC.
 */
public final class LiveStateCache {
    private static final LiveStateCache INSTANCE = new LiveStateCache();

    private final AtomicReference<Map<String, String>> worldSummary = new AtomicReference<>(Map.of());
    private final AtomicReference<Map<String, String>> mapOverlay = new AtomicReference<>(Map.of());
    private final Map<Long, Byte> terrainTiles = new ConcurrentHashMap<>();
    private final List<DynamicMarker> dynamicSettlements = new ArrayList<>();
    private final List<DynamicMarker> dynamicRoads = new ArrayList<>();
    private final List<DynamicMarker> dynamicCamps = new ArrayList<>();
    private volatile long lastRefreshMs;
    private int tick;
    private boolean refreshInFlight;

    public record DynamicMarker(String id, String label, int x, int z, String kind) {}

    private LiveStateCache() {}

    public static LiveStateCache get() { return INSTANCE; }

    public void clear() {
        worldSummary.set(Map.of());
        mapOverlay.set(Map.of());
        terrainTiles.clear();
        synchronized (this) {
            dynamicSettlements.clear();
            dynamicRoads.clear();
            dynamicCamps.clear();
        }
        refreshInFlight = false;
        tick = 0;
    }

    public void onServerTick(MinecraftServer server) {
        if (++tick % 40 != 0) return;
        SidecarClient client = WorldSessionLifecycle.activeClient();
        if (client == null || !client.isReady() || refreshInFlight) return;
        ServerLevel level = server.overworld();
        ServerPlayer focus = level.players().isEmpty() ? null : level.players().get(0);
        int cx = focus == null ? 0 : (int) focus.getX();
        int cz = focus == null ? 0 : (int) focus.getZ();
        refreshInFlight = true;
        try {
            client.sendAsync(MessageType.GET_WORLD_SUMMARY, new byte[0])
                    .thenAccept(env -> {
                        try {
                            if (env != null && !env.isError()) {
                                worldSummary.set(PayloadIo.decodeStrings(env.payload()));
                            }
                        } catch (Exception e) {
                            LivingModsMod.LOG.debug("Live summary decode failed: {}", e.toString());
                        }
                    });
            byte[] overlayReq = new RequestPayloads.NearbyQuery(cx, cz, 2048, 64).encode();
            client.sendAsync(MessageType.GET_MAP_OVERLAY, overlayReq)
                    .thenAccept(env -> level.getServer().execute(() -> {
                        try {
                            if (env != null && !env.isError()) {
                                Map<String, String> data = PayloadIo.decodeStrings(env.payload());
                                mapOverlay.set(data);
                                ingestDynamicGeometry(data);
                            }
                        } catch (Exception e) {
                            LivingModsMod.LOG.debug("Live overlay decode failed: {}", e.toString());
                        } finally {
                            refreshInFlight = false;
                            lastRefreshMs = System.currentTimeMillis();
                        }
                    }))
                    .exceptionally(ex -> {
                        refreshInFlight = false;
                        return null;
                    });
            // Incremental real terrain samples near players (bounded).
            if (focus != null) {
                sampleTerrainAround(level, cx, cz, 16);
            }
        } catch (Exception e) {
            refreshInFlight = false;
        }
    }

    private void ingestDynamicGeometry(Map<String, String> data) {
        synchronized (this) {
            dynamicSettlements.clear();
            dynamicRoads.clear();
            dynamicCamps.clear();
            String settlements = data.getOrDefault("dynamicSettlementsPacked", "");
            for (String row : settlements.split(";")) {
                if (row.isBlank()) continue;
                String[] p = row.split("\\|");
                if (p.length < 4) continue;
                dynamicSettlements.add(new DynamicMarker(p[0], p[1], Integer.parseInt(p[2]), Integer.parseInt(p[3]), "settlement"));
            }
            String roads = data.getOrDefault("dynamicRoadsPacked", "");
            for (String row : roads.split(";")) {
                if (row.isBlank()) continue;
                String[] p = row.split("\\|");
                if (p.length < 5) continue;
                dynamicRoads.add(new DynamicMarker(p[0], "road", Integer.parseInt(p[1]), Integer.parseInt(p[2]), "road"));
            }
            String camps = data.getOrDefault("banditsPacked", "");
            for (String row : camps.split(";")) {
                if (row.isBlank()) continue;
                String[] p = row.split("\\|");
                if (p.length < 3) continue;
                dynamicCamps.add(new DynamicMarker(p[0], "camp", Integer.parseInt(p[1]), Integer.parseInt(p[2]), "camp"));
            }
        }
    }

    private void sampleTerrainAround(ServerLevel level, int cx, int cz, int radiusTiles) {
        int tile = 64;
        int sampled = 0;
        for (int dz = -radiusTiles; dz <= radiusTiles && sampled < 64; dz++) {
            for (int dx = -radiusTiles; dx <= radiusTiles && sampled < 64; dx++) {
                int wx = cx + dx * tile;
                int wz = cz + dz * tile;
                long key = (((long) (wx >> 6)) << 32) ^ ((wz >> 6) & 0xffffffffL);
                if (terrainTiles.containsKey(key)) continue;
                if (!level.hasChunk(wx >> 4, wz >> 4)) continue;
                int y = level.getHeight(Heightmap.Types.WORLD_SURFACE_WG, wx, wz);
                var block = level.getBlockState(new net.minecraft.core.BlockPos(wx, y - 1, wz));
                byte cls;
                if (block.getFluidState().isSource() || block.is(net.minecraft.world.level.block.Blocks.WATER)) {
                    cls = 1; // water
                } else if (y > 90) {
                    cls = 3; // highland
                } else if (y < 62) {
                    cls = 2; // lowland
                } else {
                    cls = 0; // plains
                }
                terrainTiles.put(key, cls);
                sampled++;
            }
        }
    }

    public Map<String, String> worldSummary() {
        return worldSummary.get();
    }

    public Map<String, String> mapOverlay() {
        return mapOverlay.get();
    }

    public byte terrainClass(int worldX, int worldZ) {
        long key = (((long) (worldX >> 6)) << 32) ^ ((worldZ >> 6) & 0xffffffffL);
        Byte cached = terrainTiles.get(key);
        if (cached != null) return cached;
        // Fallback synthetic only when uncached — prefer real samples when available.
        WorldPlan plan = WorldPlanCache.get();
        if (plan == null) return 0;
        long h = ((long) worldX * 734287L) ^ ((long) worldZ * 912391L) ^ plan.contentHash();
        return (byte) (Math.floorMod(h, 4));
    }

    public synchronized List<DynamicMarker> dynamicSettlements() {
        return List.copyOf(dynamicSettlements);
    }

    public synchronized List<DynamicMarker> dynamicRoads() {
        return List.copyOf(dynamicRoads);
    }

    public synchronized List<DynamicMarker> dynamicCamps() {
        return List.copyOf(dynamicCamps);
    }

    public long lastRefreshMs() {
        return lastRefreshMs;
    }
}
