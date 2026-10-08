package com.livingmods.neoforge.network;

import com.livingmods.neoforge.LivingModsMod;
import com.livingmods.neoforge.gameplay.PlayerGameplayBridge;
import com.livingmods.protocol.PlayerActionResponse;
import com.livingmods.protocol.PlayerActionType;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Typed C↔S payloads for citizen dialogue, market, tasks, and realm UI. */
public final class CitizenInteractionPayloads {
    private CitizenInteractionPayloads() {}

    public record OpenScreen(
            UUID sessionId,
            String citizenName,
            String profession,
            String settlementName,
            String kingdomName,
            String attitude,
            String standing,
            String legalStatus,
            List<String> lines,
            List<String> actions,
            Map<String, String> data
    ) implements CustomPacketPayload {
        public static final Type<OpenScreen> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(
                LivingModsMod.MOD_ID, "citizen_open"));
        public static final StreamCodec<RegistryFriendlyByteBuf, OpenScreen> STREAM_CODEC =
                StreamCodec.of(OpenScreen::encode, OpenScreen::decode);

        @Override
        public Type<? extends CustomPacketPayload> type() { return TYPE; }

        public static OpenScreen fromResponse(UUID sessionId, PlayerActionResponse response) {
            return new OpenScreen(
                    sessionId,
                    response.data().getOrDefault("citizenName", "Citizen"),
                    response.data().getOrDefault("profession", ""),
                    response.data().getOrDefault("settlementName", ""),
                    response.data().getOrDefault("kingdomName", ""),
                    response.data().getOrDefault("attitude", "Neutral"),
                    response.standing(),
                    response.data().getOrDefault("legalStatus", "CLEAR"),
                    List.copyOf(response.dialogueLines()),
                    List.copyOf(response.availableActions()),
                    Map.copyOf(response.data())
            );
        }

        private static void encode(RegistryFriendlyByteBuf buf, OpenScreen p) {
            buf.writeUUID(p.sessionId);
            buf.writeUtf(p.citizenName, 64);
            buf.writeUtf(p.profession, 32);
            buf.writeUtf(p.settlementName, 64);
            buf.writeUtf(p.kingdomName, 64);
            buf.writeUtf(p.attitude, 32);
            buf.writeUtf(p.standing, 32);
            buf.writeUtf(p.legalStatus, 32);
            writeStrings(buf, p.lines, 64);
            writeStrings(buf, p.actions, 32);
            writeMap(buf, p.data, 64);
        }

        private static OpenScreen decode(RegistryFriendlyByteBuf buf) {
            return new OpenScreen(
                    buf.readUUID(),
                    buf.readUtf(64),
                    buf.readUtf(32),
                    buf.readUtf(64),
                    buf.readUtf(64),
                    buf.readUtf(32),
                    buf.readUtf(32),
                    buf.readUtf(32),
                    readStrings(buf, 64),
                    readStrings(buf, 32),
                    readMap(buf, 64)
            );
        }
    }

    public record DialogueChoice(UUID sessionId, String choice) implements CustomPacketPayload {
        public static final Type<DialogueChoice> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(
                LivingModsMod.MOD_ID, "dialogue_choice"));
        public static final StreamCodec<RegistryFriendlyByteBuf, DialogueChoice> STREAM_CODEC =
                StreamCodec.of((buf, p) -> {
                    buf.writeUUID(p.sessionId);
                    buf.writeUtf(p.choice == null ? "" : p.choice, 128);
                }, buf -> new DialogueChoice(buf.readUUID(), buf.readUtf(128)));

        @Override
        public Type<? extends CustomPacketPayload> type() { return TYPE; }

        public static void handle(DialogueChoice payload, IPayloadContext context) {
            context.enqueueWork(() -> {
                if (context.player() instanceof ServerPlayer player) {
                    PlayerGameplayBridge.handleDialogueChoice(player, payload.sessionId(), payload.choice());
                }
            });
        }
    }

    public record DialogueUpdate(List<String> lines, Map<String, String> data) implements CustomPacketPayload {
        public static final Type<DialogueUpdate> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(
                LivingModsMod.MOD_ID, "dialogue_update"));
        public static final StreamCodec<RegistryFriendlyByteBuf, DialogueUpdate> STREAM_CODEC =
                StreamCodec.of((buf, p) -> {
                    writeStrings(buf, p.lines, 64);
                    writeMap(buf, p.data, 32);
                }, buf -> new DialogueUpdate(readStrings(buf, 64), readMap(buf, 32)));

        @Override
        public Type<? extends CustomPacketPayload> type() { return TYPE; }

        public static DialogueUpdate fromResponse(PlayerActionResponse response) {
            return new DialogueUpdate(List.copyOf(response.dialogueLines()), Map.copyOf(response.data()));
        }
    }

    public record MarketScreenData(
            UUID sessionId,
            UUID settlementId,
            Map<String, String> market
    ) implements CustomPacketPayload {
        public static final Type<MarketScreenData> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(
                LivingModsMod.MOD_ID, "market_screen"));
        public static final StreamCodec<RegistryFriendlyByteBuf, MarketScreenData> STREAM_CODEC =
                StreamCodec.of((buf, p) -> {
                    buf.writeUUID(p.sessionId);
                    buf.writeUUID(p.settlementId == null ? new UUID(0, 0) : p.settlementId);
                    writeMap(buf, p.market, 128);
                }, buf -> new MarketScreenData(buf.readUUID(), buf.readUUID(), readMap(buf, 128)));

        @Override
        public Type<? extends CustomPacketPayload> type() { return TYPE; }

        public static MarketScreenData of(UUID sessionId, UUID settlementId, Map<String, String> market) {
            return new MarketScreenData(sessionId, settlementId, market);
        }
    }

    public record MarketTransaction(
            UUID sessionId,
            UUID settlementId,
            String resource,
            int amount,
            boolean buy
    ) implements CustomPacketPayload {
        public static final Type<MarketTransaction> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(
                LivingModsMod.MOD_ID, "market_tx"));
        public static final StreamCodec<RegistryFriendlyByteBuf, MarketTransaction> STREAM_CODEC =
                StreamCodec.of((buf, p) -> {
                    buf.writeUUID(p.sessionId == null ? new UUID(0, 0) : p.sessionId);
                    buf.writeUUID(p.settlementId == null ? new UUID(0, 0) : p.settlementId);
                    buf.writeUtf(p.resource == null ? "" : p.resource, 32);
                    buf.writeVarInt(Math.max(1, Math.min(64, p.amount)));
                    buf.writeBoolean(p.buy);
                }, buf -> new MarketTransaction(buf.readUUID(), buf.readUUID(), buf.readUtf(32),
                        buf.readVarInt(), buf.readBoolean()));

        @Override
        public Type<? extends CustomPacketPayload> type() { return TYPE; }

        public static void handle(MarketTransaction payload, IPayloadContext context) {
            context.enqueueWork(() -> {
                if (!(context.player() instanceof ServerPlayer player)) return;
                PlayerGameplayBridge.handleMarketTransaction(player, payload.sessionId(),
                        payload.settlementId(), payload.resource(), payload.amount(), payload.buy());
            });
        }
    }

    /** Typed/bounded player-facing task summary for journal UI. */
    public record TaskEntry(
            UUID taskId,
            String status,
            String type,
            String title,
            String description,
            UUID settlementId,
            String settlementName,
            String resource,
            int amount,
            int wood,
            int stone,
            String campId,
            String shipmentId,
            boolean accepted,
            String progress
    ) {
        public static TaskEntry parsePacked(String row) {
            String[] p = (row == null ? "" : row).split("\\|", -1);
            return new TaskEntry(
                    parseUuid(p, 0),
                    at(p, 1),
                    at(p, 2),
                    at(p, 3),
                    at(p, 4),
                    parseUuid(p, 5),
                    at(p, 6),
                    at(p, 7),
                    parseInt(p, 8),
                    parseInt(p, 9),
                    parseInt(p, 10),
                    at(p, 11),
                    at(p, 12),
                    "true".equalsIgnoreCase(at(p, 13)),
                    at(p, 14)
            );
        }

        private static String at(String[] p, int i) {
            return i < p.length && p[i] != null ? p[i] : "";
        }

        private static UUID parseUuid(String[] p, int i) {
            try { return UUID.fromString(at(p, i)); } catch (Exception e) { return new UUID(0, 0); }
        }

        private static int parseInt(String[] p, int i) {
            try { return Integer.parseInt(at(p, i)); } catch (Exception e) { return 0; }
        }
    }

    public record TaskJournalUpdate(List<TaskEntry> tasks, String message) implements CustomPacketPayload {
        public static final Type<TaskJournalUpdate> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(
                LivingModsMod.MOD_ID, "task_journal"));
        public static final StreamCodec<RegistryFriendlyByteBuf, TaskJournalUpdate> STREAM_CODEC =
                StreamCodec.of(TaskJournalUpdate::encode, TaskJournalUpdate::decode);

        @Override
        public Type<? extends CustomPacketPayload> type() { return TYPE; }

        private static void encode(RegistryFriendlyByteBuf buf, TaskJournalUpdate p) {
            int n = Math.min(p.tasks == null ? 0 : p.tasks.size(), 32);
            buf.writeVarInt(n);
            for (int i = 0; i < n; i++) {
                TaskEntry t = p.tasks.get(i);
                buf.writeUUID(t.taskId() == null ? new UUID(0, 0) : t.taskId());
                buf.writeUtf(nz(t.status()), 24);
                buf.writeUtf(nz(t.type()), 32);
                buf.writeUtf(nz(t.title()), 96);
                buf.writeUtf(nz(t.description()), 192);
                buf.writeUUID(t.settlementId() == null ? new UUID(0, 0) : t.settlementId());
                buf.writeUtf(nz(t.settlementName()), 64);
                buf.writeUtf(nz(t.resource()), 24);
                buf.writeVarInt(t.amount());
                buf.writeVarInt(t.wood());
                buf.writeVarInt(t.stone());
                buf.writeUtf(nz(t.campId()), 48);
                buf.writeUtf(nz(t.shipmentId()), 48);
                buf.writeBoolean(t.accepted());
                buf.writeUtf(nz(t.progress()), 128);
            }
            buf.writeUtf(p.message == null ? "" : p.message, 256);
        }

        private static TaskJournalUpdate decode(RegistryFriendlyByteBuf buf) {
            int n = Math.min(buf.readVarInt(), 32);
            List<TaskEntry> tasks = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                tasks.add(new TaskEntry(
                        buf.readUUID(), buf.readUtf(24), buf.readUtf(32), buf.readUtf(96), buf.readUtf(192),
                        buf.readUUID(), buf.readUtf(64), buf.readUtf(24),
                        buf.readVarInt(), buf.readVarInt(), buf.readVarInt(),
                        buf.readUtf(48), buf.readUtf(48), buf.readBoolean(), buf.readUtf(128)));
            }
            return new TaskJournalUpdate(tasks, buf.readUtf(256));
        }

        private static String nz(String s) { return s == null ? "" : s; }

        public static TaskJournalUpdate fromResponse(PlayerActionResponse resp) {
            List<TaskEntry> tasks = new ArrayList<>();
            String packed = resp.data().getOrDefault("tasks", "");
            if (!packed.isBlank()) {
                for (String row : packed.split(";")) {
                    if (row.isBlank()) continue;
                    tasks.add(TaskEntry.parsePacked(row));
                }
            } else if (resp.data().containsKey("taskId")) {
                // Single accept response
                tasks.add(new TaskEntry(
                        parseUuid(resp.data().get("taskId")),
                        resp.data().getOrDefault("status", "ACCEPTED"),
                        resp.data().getOrDefault("taskType", ""),
                        resp.data().getOrDefault("title", ""),
                        resp.data().getOrDefault("description", ""),
                        parseUuid(resp.data().get("settlementId")),
                        resp.data().getOrDefault("settlementName", ""),
                        resp.data().getOrDefault("resource", ""),
                        parseInt(resp.data().get("amount")),
                        parseInt(resp.data().get("wood")),
                        parseInt(resp.data().get("stone")),
                        resp.data().getOrDefault("campId", ""),
                        resp.data().getOrDefault("shipment", ""),
                        true,
                        ""
                ));
            }
            return new TaskJournalUpdate(tasks, resp.message());
        }

        private static UUID parseUuid(String raw) {
            try { return UUID.fromString(raw); } catch (Exception e) { return new UUID(0, 0); }
        }

        private static int parseInt(String raw) {
            try { return Integer.parseInt(raw); } catch (Exception e) { return 0; }
        }
    }

    public record TaskAction(UUID taskId, String action, String resource, int amount, UUID settlementId)
            implements CustomPacketPayload {
        public static final Type<TaskAction> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(
                LivingModsMod.MOD_ID, "task_action"));
        public static final StreamCodec<RegistryFriendlyByteBuf, TaskAction> STREAM_CODEC =
                StreamCodec.of((buf, p) -> {
                    buf.writeUUID(p.taskId == null ? new UUID(0, 0) : p.taskId);
                    buf.writeUtf(p.action == null ? "" : p.action, 32);
                    buf.writeUtf(p.resource == null ? "" : p.resource, 32);
                    buf.writeVarInt(Math.max(0, Math.min(64, p.amount)));
                    buf.writeUUID(p.settlementId == null ? new UUID(0, 0) : p.settlementId);
                }, buf -> new TaskAction(buf.readUUID(), buf.readUtf(32), buf.readUtf(32),
                        buf.readVarInt(), buf.readUUID()));

        @Override
        public Type<? extends CustomPacketPayload> type() { return TYPE; }

        public static void handle(TaskAction payload, IPayloadContext context) {
            context.enqueueWork(() -> {
                if (!(context.player() instanceof ServerPlayer player)) return;
                String action = payload.action() == null ? "" : payload.action().toUpperCase();
                switch (action) {
                    case "DELIVER" -> PlayerGameplayBridge.handleTaskDelivery(
                            player, payload.taskId(), payload.settlementId(),
                            payload.resource(), payload.amount());
                    case "ABANDON" -> PlayerGameplayBridge.handleAbandonTask(player, payload.taskId());
                    default -> {
                    }
                }
            });
        }
    }

    public record RealmPanel(String panel, Map<String, String> data) implements CustomPacketPayload {
        public static final Type<RealmPanel> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(
                LivingModsMod.MOD_ID, "realm_panel"));
        public static final StreamCodec<RegistryFriendlyByteBuf, RealmPanel> STREAM_CODEC =
                StreamCodec.of((buf, p) -> {
                    buf.writeUtf(p.panel == null ? "" : p.panel, 32);
                    writeMap(buf, p.data, 128);
                }, buf -> new RealmPanel(buf.readUtf(32), readMap(buf, 128)));

        @Override
        public Type<? extends CustomPacketPayload> type() { return TYPE; }

        public static RealmPanel fromResponse(PlayerActionResponse resp, String panel) {
            return new RealmPanel(panel, Map.copyOf(resp.data()));
        }
    }

    public record RealmAction(String action, String value, String culture, int x, int z)
            implements CustomPacketPayload {
        public static final Type<RealmAction> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(
                LivingModsMod.MOD_ID, "realm_action"));
        public static final StreamCodec<RegistryFriendlyByteBuf, RealmAction> STREAM_CODEC =
                StreamCodec.of((buf, p) -> {
                    buf.writeUtf(p.action == null ? "" : p.action, 48);
                    buf.writeUtf(p.value == null ? "" : p.value, 64);
                    buf.writeUtf(p.culture == null ? "" : p.culture, 32);
                    buf.writeInt(p.x);
                    buf.writeInt(p.z);
                }, buf -> new RealmAction(buf.readUtf(48), buf.readUtf(64), buf.readUtf(32),
                        buf.readInt(), buf.readInt()));

        @Override
        public Type<? extends CustomPacketPayload> type() { return TYPE; }

        public static void handle(RealmAction payload, IPayloadContext context) {
            context.enqueueWork(() -> {
                if (!(context.player() instanceof ServerPlayer player)) return;
                String action = payload.action() == null ? "" : payload.action().toUpperCase();
                switch (action) {
                    case "FOUND_REALM" -> PlayerGameplayBridge.handleFoundRealm(
                            player, payload.value(), payload.culture());
                    case "SET_TAX_POLICY" -> PlayerGameplayBridge.handleRealmAction(
                            player, PlayerActionType.SET_TAX_POLICY, Map.of("value", payload.value()));
                    case "SET_DEFENSE_POLICY" -> PlayerGameplayBridge.handleRealmAction(
                            player, PlayerActionType.SET_DEFENSE_POLICY, Map.of("value", payload.value()));
                    case "SET_FOOD_POLICY" -> PlayerGameplayBridge.handleRealmAction(
                            player, PlayerActionType.SET_FOOD_POLICY, Map.of("value", payload.value()));
                    case "SET_CONSTRUCTION_POLICY" -> PlayerGameplayBridge.handleRealmAction(
                            player, PlayerActionType.SET_CONSTRUCTION_POLICY, Map.of("value", payload.value()));
                    case "SET_MIGRATION_POLICY" -> PlayerGameplayBridge.handleRealmAction(
                            player, PlayerActionType.SET_MIGRATION_POLICY, Map.of("value", payload.value()));
                    case "REQUEST_DIPLOMATIC_ACTION" -> PlayerGameplayBridge.handleRealmAction(
                            player, PlayerActionType.REQUEST_DIPLOMATIC_ACTION,
                            Map.of("diplomacyType", payload.value(), "targetKingdomId", payload.culture()));
                    case "DECLARE_SUPPORT_IN_WAR" -> PlayerGameplayBridge.handleRealmAction(
                            player, PlayerActionType.DECLARE_SUPPORT_IN_WAR,
                            Map.of("sideKingdomId", payload.value()));
                    case "DISCOVER_NEARBY" -> PlayerGameplayBridge.discoverNearbySettlement(player);
                    case "OPEN_REALM" -> PlayerGameplayBridge.requestRealmPanel(player, payload.value());
                    case "QUERY_JOURNAL" -> PlayerGameplayBridge.requestTaskJournal(player);
                    default -> {
                    }
                }
            });
        }
    }

    public record DashboardRequest() implements CustomPacketPayload {
        public static final Type<DashboardRequest> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(
                LivingModsMod.MOD_ID, "dashboard_request"));
        public static final StreamCodec<RegistryFriendlyByteBuf, DashboardRequest> STREAM_CODEC =
                StreamCodec.of((buf, p) -> {}, buf -> new DashboardRequest());

        @Override
        public Type<? extends CustomPacketPayload> type() { return TYPE; }

        public static void handle(DashboardRequest payload, IPayloadContext context) {
            context.enqueueWork(() -> {
                if (context.player() instanceof ServerPlayer player) {
                    PlayerGameplayBridge.requestDashboard(player);
                }
            });
        }
    }

    public record DashboardContext(Map<String, String> data) implements CustomPacketPayload {
        public static final Type<DashboardContext> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(
                LivingModsMod.MOD_ID, "dashboard_context"));
        public static final StreamCodec<RegistryFriendlyByteBuf, DashboardContext> STREAM_CODEC =
                StreamCodec.of((buf, p) -> writeMap(buf, p.data, 128),
                        buf -> new DashboardContext(readMap(buf, 128)));

        @Override
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record JournalRequest() implements CustomPacketPayload {
        public static final Type<JournalRequest> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(
                LivingModsMod.MOD_ID, "journal_request"));
        public static final StreamCodec<RegistryFriendlyByteBuf, JournalRequest> STREAM_CODEC =
                StreamCodec.of((buf, p) -> {}, buf -> new JournalRequest());

        @Override
        public Type<? extends CustomPacketPayload> type() { return TYPE; }

        public static void handle(JournalRequest payload, IPayloadContext context) {
            context.enqueueWork(() -> {
                if (context.player() instanceof ServerPlayer player) {
                    PlayerGameplayBridge.requestTaskJournal(player);
                }
            });
        }
    }

    private static void writeStrings(RegistryFriendlyByteBuf buf, List<String> list, int max) {
        int n = Math.min(list == null ? 0 : list.size(), max);
        buf.writeVarInt(n);
        for (int i = 0; i < n; i++) {
            buf.writeUtf(list.get(i) == null ? "" : list.get(i), 512);
        }
    }

    private static List<String> readStrings(RegistryFriendlyByteBuf buf, int max) {
        int n = Math.min(buf.readVarInt(), max);
        List<String> list = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            list.add(buf.readUtf(512));
        }
        return list;
    }

    private static void writeMap(RegistryFriendlyByteBuf buf, Map<String, String> map, int max) {
        int n = Math.min(map == null ? 0 : map.size(), max);
        buf.writeVarInt(n);
        int i = 0;
        if (map != null) {
            for (var e : map.entrySet()) {
                if (i++ >= n) break;
                buf.writeUtf(e.getKey() == null ? "" : e.getKey(), 64);
                buf.writeUtf(e.getValue() == null ? "" : e.getValue(), 512);
            }
        }
    }

    private static Map<String, String> readMap(RegistryFriendlyByteBuf buf, int max) {
        int n = Math.min(buf.readVarInt(), max);
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) {
            map.put(buf.readUtf(64), buf.readUtf(512));
        }
        return map;
    }
}
