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

        registrar.playToClient(CitizenInteractionPayloads.OpenScreen.TYPE,
                CitizenInteractionPayloads.OpenScreen.STREAM_CODEC,
                (payload, ctx) -> {
                    if (!FMLEnvironment.dist.isClient()) return;
                    ctx.enqueueWork(() -> ClientNetworkBridge.openCitizen(payload));
                });
        registrar.playToServer(CitizenInteractionPayloads.DialogueChoice.TYPE,
                CitizenInteractionPayloads.DialogueChoice.STREAM_CODEC,
                CitizenInteractionPayloads.DialogueChoice::handle);
        registrar.playToClient(CitizenInteractionPayloads.DialogueUpdate.TYPE,
                CitizenInteractionPayloads.DialogueUpdate.STREAM_CODEC,
                (payload, ctx) -> {
                    if (!FMLEnvironment.dist.isClient()) return;
                    ctx.enqueueWork(() -> ClientNetworkBridge.updateDialogue(payload));
                });
        registrar.playToClient(CitizenInteractionPayloads.MarketScreenData.TYPE,
                CitizenInteractionPayloads.MarketScreenData.STREAM_CODEC,
                (payload, ctx) -> {
                    if (!FMLEnvironment.dist.isClient()) return;
                    ctx.enqueueWork(() -> ClientNetworkBridge.openMarket(payload));
                });
        registrar.playToServer(CitizenInteractionPayloads.MarketTransaction.TYPE,
                CitizenInteractionPayloads.MarketTransaction.STREAM_CODEC,
                CitizenInteractionPayloads.MarketTransaction::handle);
        registrar.playToClient(CitizenInteractionPayloads.TaskJournalUpdate.TYPE,
                CitizenInteractionPayloads.TaskJournalUpdate.STREAM_CODEC,
                (payload, ctx) -> {
                    if (!FMLEnvironment.dist.isClient()) return;
                    ctx.enqueueWork(() -> ClientNetworkBridge.mergeTasks(payload));
                });
        registrar.playToServer(CitizenInteractionPayloads.TaskAction.TYPE,
                CitizenInteractionPayloads.TaskAction.STREAM_CODEC,
                CitizenInteractionPayloads.TaskAction::handle);
        registrar.playToClient(CitizenInteractionPayloads.RealmPanel.TYPE,
                CitizenInteractionPayloads.RealmPanel.STREAM_CODEC,
                (payload, ctx) -> {
                    if (!FMLEnvironment.dist.isClient()) return;
                    ctx.enqueueWork(() -> ClientNetworkBridge.openRealm(payload));
                });
        registrar.playToServer(CitizenInteractionPayloads.RealmAction.TYPE,
                CitizenInteractionPayloads.RealmAction.STREAM_CODEC,
                CitizenInteractionPayloads.RealmAction::handle);
        registrar.playToServer(CitizenInteractionPayloads.DashboardRequest.TYPE,
                CitizenInteractionPayloads.DashboardRequest.STREAM_CODEC,
                CitizenInteractionPayloads.DashboardRequest::handle);
        registrar.playToClient(CitizenInteractionPayloads.DashboardContext.TYPE,
                CitizenInteractionPayloads.DashboardContext.STREAM_CODEC,
                (payload, ctx) -> {
                    if (!FMLEnvironment.dist.isClient()) return;
                    ctx.enqueueWork(() -> ClientNetworkBridge.acceptDashboardContext(payload.data()));
                });
        registrar.playToServer(CitizenInteractionPayloads.JournalRequest.TYPE,
                CitizenInteractionPayloads.JournalRequest.STREAM_CODEC,
                CitizenInteractionPayloads.JournalRequest::handle);
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
            enrichDashboardFromSidecar(client, metrics);
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

    private static void enrichDashboardFromSidecar(SidecarClient client, Map<String, String> metrics) {
        // Never block the server thread on sidecar IPC — read async LiveStateCache only.
        Map<String, String> summary = com.livingmods.neoforge.physical.LiveStateCache.get().worldSummary();
        Map<String, String> overlay = com.livingmods.neoforge.physical.LiveStateCache.get().mapOverlay();
        if (!summary.isEmpty()) {
            metrics.put("warActive", summary.getOrDefault("wars", "0"));
            metrics.put("epidemicActive", summary.getOrDefault("epidemics", "0"));
            metrics.put("citizens", summary.getOrDefault("citizens", "0"));
            metrics.put("simTicks", summary.getOrDefault("simTicks", "0"));
            metrics.put("kingdomsLive", summary.getOrDefault("kingdoms", "0"));
            metrics.put("settlementsLive", summary.getOrDefault("settlements", "0"));
            metrics.put("treasury", summary.getOrDefault("treasury", "0"));
            metrics.put("legitimacy", summary.getOrDefault("legitimacy", "0"));
            metrics.put("shipments", summary.getOrDefault("shipments", "0"));
            metrics.put("treaties", summary.getOrDefault("treaties", "0"));
            metrics.put("historyRecent", summary.getOrDefault("historyRecent", ""));
        } else {
            metrics.putIfAbsent("warActive", "pending");
            metrics.putIfAbsent("epidemicActive", "pending");
        }
        if (!overlay.isEmpty()) {
            metrics.put("armiesLive", String.valueOf(overlay.getOrDefault("armiesPacked", "").split(";").length));
            metrics.put("constructionLive", overlay.getOrDefault("constructionPacked", ""));
            metrics.put("siegesLive", overlay.getOrDefault("sieges", "0"));
        }
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
        // Refresh knowledge async; filter with current cache (never send hidden metadata).
        com.livingmods.neoforge.gameplay.PlayerKnowledgeCache knowledge =
                com.livingmods.neoforge.gameplay.PlayerKnowledgeCache.get();
        knowledge.tickRefresh(player.getUUID(), (int) player.getX(), (int) player.getY(), (int) player.getZ());
        int playerX = (int) player.getX();
        int playerZ = (int) player.getZ();
        if (plan != null) {
            Map<String, String> kingdomNames = new LinkedHashMap<>();
            int colorIdx = 0;
            for (PlannedKingdom k : plan.kingdoms()) {
                kingdomNames.put(k.id().toString(), k.name());
                java.util.UUID kid = k.id().value();
                var kLevel = knowledge.kingdomLevel(player.getUUID(), kid);
                boolean owns = knowledge.ownsKingdom(player.getUUID(), kid);
                if (!owns && kLevel == com.livingmods.neoforge.gameplay.PlayerKnowledgeCache.Level.UNKNOWN) {
                    colorIdx++;
                    continue; // no political border / name for unknown kingdoms
                }
                List<Integer> border = new ArrayList<>();
                // Precise borders only for KNOWN/OBSERVED or owned; RUMORED gets capital hint only.
                if (owns || kLevel.ordinal() >= com.livingmods.neoforge.gameplay.PlayerKnowledgeCache.Level.KNOWN.ordinal()) {
                    if (k.territoryPolygon() != null) {
                        for (var p : k.territoryPolygon()) {
                            border.add(p.x());
                            border.add(p.z());
                        }
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
                String displayName = kLevel == com.livingmods.neoforge.gameplay.PlayerKnowledgeCache.Level.RUMORED
                        ? "Rumored realm" : k.name();
                kingdoms.add(new MapDataPayload.KingdomOverlay(
                        k.id().toString(),
                        owns ? k.name() : displayName,
                        color,
                        k.capitalCenter().x(),
                        k.capitalCenter().z(),
                        border,
                        owns ? "OBSERVED" : kLevel.name()
                ));
            }
            for (PlannedSettlement s : plan.settlements().values()) {
                java.util.UUID sid = s.id().value();
                var sLevel = knowledge.settlementLevel(player.getUUID(), sid);
                // Nearby observation from server player position — treat as OBSERVED for this payload.
                int dx = s.center().x() - playerX;
                int dz = s.center().z() - playerZ;
                boolean nearby = dx * dx + dz * dz <= 48 * 48;
                if (nearby && sLevel == com.livingmods.neoforge.gameplay.PlayerKnowledgeCache.Level.UNKNOWN) {
                    sLevel = com.livingmods.neoforge.gameplay.PlayerKnowledgeCache.Level.OBSERVED;
                    knowledge.putSettlement(player.getUUID(), sid, sLevel);
                    s.ownerKingdom().ifPresent(oid ->
                            knowledge.putKingdom(player.getUUID(), oid.value(),
                                    com.livingmods.neoforge.gameplay.PlayerKnowledgeCache.Level.KNOWN));
                }
                if (sLevel == com.livingmods.neoforge.gameplay.PlayerKnowledgeCache.Level.UNKNOWN) {
                    continue; // do not leak settlement metadata
                }
                boolean ownedRealm = s.ownerKingdom().map(oid ->
                        knowledge.ownsKingdom(player.getUUID(), oid.value())).orElse(false);
                String kingdom = "";
                if (ownedRealm || sLevel.ordinal() >= com.livingmods.neoforge.gameplay.PlayerKnowledgeCache.Level.KNOWN.ordinal()) {
                    kingdom = s.ownerKingdom()
                            .map(id -> kingdomNames.getOrDefault(id.toString(), ""))
                            .orElse("");
                }
                String name = sLevel == com.livingmods.neoforge.gameplay.PlayerKnowledgeCache.Level.RUMORED
                        ? "Settlement?" : s.name();
                String tier = sLevel == com.livingmods.neoforge.gameplay.PlayerKnowledgeCache.Level.RUMORED
                        ? "UNKNOWN" : s.tier().name();
                // Approximate coords for RUMORED (±32 quantization).
                int mx = s.center().x();
                int mz = s.center().z();
                if (sLevel == com.livingmods.neoforge.gameplay.PlayerKnowledgeCache.Level.RUMORED) {
                    mx = (mx / 32) * 32;
                    mz = (mz / 32) * 32;
                }
                settlements.add(new MapDataPayload.SettlementMarker(
                        s.id().toString(),
                        name,
                        kingdom,
                        tier,
                        mx,
                        mz,
                        ownedRealm || sLevel.ordinal() >= com.livingmods.neoforge.gameplay.PlayerKnowledgeCache.Level.KNOWN.ordinal()
                                && s.capital(),
                        ownedRealm ? "OBSERVED" : sLevel.name()
                ));
            }
            // Overlay dynamic / player-founded settlements from live cache (never mutate WorldPlan).
            for (var dyn : com.livingmods.neoforge.physical.LiveStateCache.get().dynamicSettlements()) {
                java.util.UUID dynId;
                try {
                    dynId = java.util.UUID.fromString(dyn.id());
                } catch (Exception e) {
                    continue;
                }
                var dLevel = knowledge.settlementLevel(player.getUUID(), dynId);
                int dx = dyn.x() - playerX;
                int dz = dyn.z() - playerZ;
                if (dx * dx + dz * dz <= 48 * 48) {
                    dLevel = com.livingmods.neoforge.gameplay.PlayerKnowledgeCache.Level.OBSERVED;
                    knowledge.putSettlement(player.getUUID(), dynId, dLevel);
                }
                if (dLevel == com.livingmods.neoforge.gameplay.PlayerKnowledgeCache.Level.UNKNOWN) continue;
                boolean exists = settlements.stream().anyMatch(s -> s.id().equals(dyn.id()));
                if (!exists) {
                    settlements.add(new MapDataPayload.SettlementMarker(
                            dyn.id(),
                            dLevel == com.livingmods.neoforge.gameplay.PlayerKnowledgeCache.Level.RUMORED
                                    ? "Settlement?" : dyn.label(),
                            "", "VILLAGE", dyn.x(), dyn.z(), false, dLevel.name()));
                }
            }
            for (var road : com.livingmods.neoforge.physical.LiveStateCache.get().dynamicRoads()) {
                roads.add(new MapDataPayload.RoadSegment(
                        road.x(), road.z(), road.x() + 8, road.z(), "DYNAMIC"));
            }
            // Roads only when player has some civilization knowledge (not full fog, but reduce leakage).
            boolean anyKnown = !settlements.isEmpty() || !kingdoms.isEmpty();
            if (anyKnown) {
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
        }

        List<MapDataPayload.ArmyMarker> armies = new ArrayList<>();
        List<MapDataPayload.EpidemicMarker> epidemics = new ArrayList<>();
        List<MapDataPayload.MigrationMarker> migrations = new ArrayList<>();
        fillLiveOverlays(player, centerX, centerZ, radius, armies, epidemics, migrations);

        return new MapDataPayload(
                originX, originZ, tileSize, width, height, tiles,
                settlements, roads, kingdoms, armies, epidemics, migrations,
                (int) player.getX(), (int) player.getZ(),
                plan == null ? 0L : plan.contentHash()
        );
    }

    private static void fillLiveOverlays(
            ServerPlayer player,
            int centerX,
            int centerZ,
            int radius,
            List<MapDataPayload.ArmyMarker> armies,
            List<MapDataPayload.EpidemicMarker> epidemics,
            List<MapDataPayload.MigrationMarker> migrations
    ) {
        // Async cache only — no server-thread .get(timeout) IPC.
        Map<String, String> data = com.livingmods.neoforge.physical.LiveStateCache.get().mapOverlay();
        if (data.isEmpty()) return;
        try {
            String armyPacked = data.getOrDefault("armiesPacked", "");
            if (!armyPacked.isBlank()) {
                for (String row : armyPacked.split(";")) {
                    if (row.isBlank()) continue;
                    String[] p = row.split("\\|");
                    if (p.length < 5) continue;
                    armies.add(new MapDataPayload.ArmyMarker(
                            p[0], p[1], Integer.parseInt(p[2]), Integer.parseInt(p[3]), Integer.parseInt(p[4])));
                }
            }
            String epiPacked = data.getOrDefault("epidemicsPacked", "");
            if (!epiPacked.isBlank()) {
                for (String row : epiPacked.split(";")) {
                    if (row.isBlank()) continue;
                    String[] p = row.split("\\|");
                    if (p.length < 4) continue;
                    epidemics.add(new MapDataPayload.EpidemicMarker(
                            p[0], Integer.parseInt(p[1]), Integer.parseInt(p[2]), Double.parseDouble(p[3])));
                }
            }
            String migPacked = data.getOrDefault("migrationsPacked", "");
            if (!migPacked.isBlank()) {
                for (String row : migPacked.split(";")) {
                    if (row.isBlank()) continue;
                    String[] p = row.split("\\|");
                    if (p.length < 4) continue;
                    migrations.add(new MapDataPayload.MigrationMarker(
                            p[0], Integer.parseInt(p[1]), Integer.parseInt(p[2]), Integer.parseInt(p[3])));
                }
            }
        } catch (Exception e) {
            LivingModsMod.LOG.debug("Live map overlay cache read failed: {}", e.toString());
        }
    }

    private static byte classifyTerrain(WorldPlan plan, int x, int z) {
        byte cached = com.livingmods.neoforge.physical.LiveStateCache.get().terrainClass(x, z);
        if (plan != null) {
            for (PlannedSettlement s : plan.settlements().values()) {
                if (Math.abs(s.center().x() - x) < 48 && Math.abs(s.center().z() - z) < 48) {
                    return 4;
                }
            }
        }
        return cached;
    }
}
