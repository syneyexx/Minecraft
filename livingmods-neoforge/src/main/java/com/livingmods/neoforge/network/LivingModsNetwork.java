package com.livingmods.neoforge.network;

import com.livingmods.neoforge.LivingModsMod;
import com.livingmods.neoforge.sidecar.SidecarClient;
import com.livingmods.neoforge.sidecar.SidecarProcessManager;
import com.livingmods.neoforge.sidecar.WorldSessionLifecycle;
import com.livingmods.neoforge.worldgen.WorldPlanCache;
import com.livingmods.worldgen.plan.PlannedKingdom;
import com.livingmods.worldgen.plan.PlannedRoad;
import com.livingmods.worldgen.plan.PlannedSettlement;
import com.livingmods.worldgen.plan.WorldPlan;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@EventBusSubscriber(modid = LivingModsMod.MOD_ID, bus = EventBusSubscriber.Bus.MOD)
public final class LivingModsNetwork {
    private LivingModsNetwork() {}

    @SubscribeEvent
    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1");
        registrar.playToServer(MapDataRequestPayload.TYPE, MapDataRequestPayload.STREAM_CODEC,
                LivingModsNetwork::handleMapRequest);
        registrar.playToClient(MapDataPayload.TYPE, MapDataPayload.STREAM_CODEC,
                LivingModsNetwork::handleMapDataClient);
        registrar.playToClient(DashboardDataPayload.TYPE, DashboardDataPayload.STREAM_CODEC,
                LivingModsNetwork::handleDashboardClient);
    }

    private static void handleMapRequest(MapDataRequestPayload request, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }
            player.connection.send(buildMapPayload(player, request));
        });
    }

    private static void handleMapDataClient(MapDataPayload payload, IPayloadContext context) {
        if (!FMLEnvironment.dist.isClient()) {
            return;
        }
        context.enqueueWork(() -> ClientNetworkBridge.acceptMap(payload));
    }

    private static void handleDashboardClient(DashboardDataPayload payload, IPayloadContext context) {
        if (!FMLEnvironment.dist.isClient()) {
            return;
        }
        context.enqueueWork(() -> ClientNetworkBridge.acceptDashboard(payload.metrics()));
    }

    public static void sendDashboardTo(ServerPlayer player) {
        player.connection.send(new DashboardDataPayload(collectDashboardMetrics()));
    }

    public static Map<String, String> collectDashboardMetrics() {
        Map<String, String> metrics = new LinkedHashMap<>();
        SidecarClient client = WorldSessionLifecycle.activeClient();
        if (client != null) {
            metrics.putAll(client.diagnostics());
            metrics.put("connected", String.valueOf(client.isReady()));
            metrics.put("simLag", String.valueOf(client.roundTripLatency()));
            metrics.put("pendingRequests", String.valueOf(client.diagnostics().getOrDefault("pendingRequests", "0")));
        } else {
            metrics.put("connected", "false");
            metrics.put("pendingRequests", "0");
            metrics.put("simLag", "-1");
        }
        metrics.put("pid", String.valueOf(SidecarProcessManager.pid()));
        WorldPlan plan = WorldPlanCache.get();
        if (plan != null) {
            metrics.put("planKingdoms", String.valueOf(plan.kingdoms().size()));
            metrics.put("planSettlements", String.valueOf(plan.settlements().size()));
            metrics.put("revision", String.valueOf(plan.contentHash()));
        }
        // Ensure no hardcoded queueDepth=0 when sidecar has not reported — mark unknown.
        metrics.putIfAbsent("queueDepth", "unknown");
        return metrics;
    }

    private static MapDataPayload buildMapPayload(ServerPlayer player, MapDataRequestPayload request) {
        WorldPlan plan = WorldPlanCache.get();
        int centerX = request.centerX();
        int centerZ = request.centerZ();
        int radius = Math.max(256, Math.min(8192, request.radiusBlocks()));
        int tileSize = request.detailLevel() >= 2 ? 32 : request.detailLevel() == 1 ? 64 : 128;
        int width = Math.max(8, (radius * 2) / tileSize);
        int height = width;
        int originX = centerX - (width * tileSize) / 2;
        int originZ = centerZ - (height * tileSize) / 2;
        byte[] tiles = new byte[width * height];
        for (int z = 0; z < height; z++) {
            for (int x = 0; x < width; x++) {
                int wx = originX + x * tileSize + tileSize / 2;
                int wz = originZ + z * tileSize + tileSize / 2;
                tiles[z * width + x] = classifyTerrain(plan, wx, wz);
            }
        }

        List<MapDataPayload.SettlementMarker> settlements = new ArrayList<>();
        List<MapDataPayload.KingdomOverlay> kingdoms = new ArrayList<>();
        List<MapDataPayload.RoadSegment> roads = new ArrayList<>();
        if (plan != null) {
            Map<String, String> kingdomNames = new LinkedHashMap<>();
            int colorIdx = 0;
            for (PlannedKingdom k : plan.kingdoms()) {
                kingdomNames.put(k.id().toString(), k.name());
                List<Integer> border = new ArrayList<>();
                if (k.territoryPolygon() != null) {
                    for (var p : k.territoryPolygon()) {
                        border.add(p.x());
                        border.add(p.z());
                    }
                }
                int color = switch (colorIdx++ % 6) {
                    case 0 -> 0xC45C26;
                    case 1 -> 0x2F6F4E;
                    case 2 -> 0x3A5F8A;
                    case 3 -> 0x8A3A4A;
                    case 4 -> 0x6B5B2E;
                    default -> 0x4A4A6A;
                };
                kingdoms.add(new MapDataPayload.KingdomOverlay(
                        k.id().toString(),
                        k.name(),
                        color,
                        k.capitalCenter().x(),
                        k.capitalCenter().z(),
                        border
                ));
            }
            for (PlannedSettlement s : plan.settlements().values()) {
                String kingdom = s.ownerKingdom()
                        .map(id -> kingdomNames.getOrDefault(id.toString(), ""))
                        .orElse("");
                settlements.add(new MapDataPayload.SettlementMarker(
                        s.id().toString(),
                        s.name(),
                        kingdom,
                        s.tier().name(),
                        s.center().x(),
                        s.center().z(),
                        s.capital()
                ));
            }
            for (PlannedRoad road : plan.roads()) {
                var pts = road.path();
                if (pts == null || pts.size() < 2) continue;
                int stride = Math.max(1, pts.size() / 10);
                for (int i = 0; i + 1 < pts.size(); i += stride) {
                    var a = pts.get(i);
                    var b = pts.get(Math.min(pts.size() - 1, i + stride));
                    roads.add(new MapDataPayload.RoadSegment(
                            a.x(), a.z(), b.x(), b.z(),
                            road.roadClass() == null ? "ROAD" : road.roadClass().name()));
                }
            }
        }

        return new MapDataPayload(
                originX, originZ, tileSize, width, height, tiles,
                settlements, roads, kingdoms, List.of(), List.of(), List.of(),
                (int) player.getX(), (int) player.getZ(),
                plan == null ? 0L : plan.contentHash()
        );
    }

    private static byte classifyTerrain(WorldPlan plan, int x, int z) {
        if (plan == null) {
            return 0;
        }
        for (PlannedSettlement s : plan.settlements().values()) {
            if (Math.abs(s.center().x() - x) < 48 && Math.abs(s.center().z() - z) < 48) {
                return 4;
            }
        }
        int h = Long.hashCode((((long) x) * 734287L) ^ (((long) z) * 912371L) ^ plan.seed());
        int n = h & 0xFF;
        if (n < 20) return 2;
        if (n > 220) return 3;
        return 1;
    }
}
