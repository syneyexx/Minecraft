package com.livingmods.sidecar;

import com.livingmods.common.geo.BlockPos2;
import com.livingmods.common.id.CitizenId;
import com.livingmods.common.id.KingdomId;
import com.livingmods.common.id.PlayerId;
import com.livingmods.common.model.SettlementTier;
import com.livingmods.protocol.BinaryCodec;
import com.livingmods.protocol.Envelope;
import com.livingmods.protocol.ErrorPayload;
import com.livingmods.protocol.EventPayload;
import com.livingmods.protocol.HandshakePayload;
import com.livingmods.protocol.MessageType;
import com.livingmods.protocol.PayloadIo;
import com.livingmods.protocol.ProtocolConstants;
import com.livingmods.protocol.RequestPayloads;
import com.livingmods.common.model.PhysicalOutcomeType;
import com.livingmods.protocol.PhysicalOutcomePayload;
import com.livingmods.simulation.engine.PlayerSystemsEngine;
import com.livingmods.simulation.physical.PhysicalIntent;
import com.livingmods.simulation.physical.PhysicalOutcomeApplier;
import com.livingmods.simulation.state.CitizenState;
import com.livingmods.simulation.state.SettlementState;
import com.livingmods.simulation.tick.SimulationContext;
import com.livingmods.worldgen.plan.PlannedResourceSite;
import com.livingmods.worldgen.plan.PlannedRuin;
import com.livingmods.worldgen.plan.PlannedSettlement;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

public final class SessionHandler implements Runnable {
    private static final Logger LOG = SidecarLogging.logger(SessionHandler.class);

    private final SidecarSimulationHost host;
    private final DiagnosticsExporter diagnostics;
    private final java.net.Socket socket;
    private final AtomicLong messageId = new AtomicLong(1);
    private final PlayerSystemsEngine playerSystems = new PlayerSystemsEngine();
    private final PhysicalOutcomeApplier outcomeApplier = new PhysicalOutcomeApplier();
    private UUID sessionId;
    private volatile boolean handshaken;

    public SessionHandler(SidecarSimulationHost host, DiagnosticsExporter diagnostics, java.net.Socket socket) {
        this.host = host;
        this.diagnostics = diagnostics;
        this.socket = socket;
    }

    @Override
    public void run() {
        try (socket; var in = socket.getInputStream(); var out = socket.getOutputStream()) {
            LOG.info("Session connected from " + socket.getRemoteSocketAddress());
            host.setFrozen(false);
            while (!socket.isClosed()) {
                Envelope envelope = BinaryCodec.readEnvelope(in);
                diagnostics.recordInbound(envelope.payloadLength() + ProtocolConstants.HEADER_SIZE);
                diagnostics.recordStepNanos(host.lastStepNanos());
                diagnostics.setQueuedIpc(host.outboundEventQueueDepth());
                diagnostics.setSubscriptions(host.subscriptionCount());
                handle(envelope, out);
                EventPayload event;
                while ((event = host.pollEvent()) != null) {
                    send(out, MessageType.EVENT, 0, 0, event.encode());
                }
            }
        } catch (IOException e) {
            LOG.info("Session closed: " + e.getMessage());
        } finally {
            host.setFrozen(true);
        }
    }

    private void handle(Envelope envelope, OutputStream out) throws IOException {
        MessageType type = envelope.type();
        if (type != MessageType.HANDSHAKE_REQUEST
                && type != MessageType.HEARTBEAT
                && type != MessageType.SHUTDOWN
                && !handshaken) {
            sendError(out, envelope.requestId(), ErrorPayload.NOT_READY, "Handshake required");
            return;
        }
        if (host.isFailed()) {
            sendError(out, envelope.requestId(), ErrorPayload.INTERNAL, "Sidecar host failed");
            return;
        }

        switch (type) {
            case HANDSHAKE_REQUEST -> onHandshake(envelope, out);
            case HEARTBEAT -> send(out, MessageType.HEARTBEAT, envelope.requestId(),
                    host.state().time().absoluteTicks(), new byte[0]);
            case SAVE_REQUEST -> onSave(envelope, out);
            case SHUTDOWN -> {
                host.finalSaveBarrier();
                send(out, MessageType.SHUTDOWN, envelope.requestId(),
                        host.state().time().absoluteTicks(), new byte[0]);
                socket.close();
            }
            case TIME_SYNC -> onTimeSync(envelope, out);
            case GET_NEARBY_CITIZENS -> onNearby(envelope, out);
            case LOCATE -> onLocate(envelope, out);
            case GET_WORLD_SUMMARY -> onWorldSummary(envelope, out);
            case GET_SETTLEMENT_SNAPSHOT -> onSettlementSnapshot(envelope, out);
            case GET_KINGDOM_SUMMARY -> onKingdomSummary(envelope, out);
            case GET_MARKET_STATE -> onMarketState(envelope, out);
            case GET_DIALOGUE_CONTEXT -> onDialogue(envelope, out);
            case GET_PHYSICAL_PROJECTION_PLAN -> onProjection(envelope, out);
            case GET_CONSTRUCTION_PLAN -> onConstruction(envelope, out);
            case GET_MAP_OVERLAY -> onMapOverlay(envelope, out);
            case SUBSCRIBE_REGION -> onSubscribe(envelope, out, true);
            case UNSUBSCRIBE_REGION -> onSubscribe(envelope, out, false);
            case REPORT_PHYSICAL_OUTCOME -> onPhysicalOutcome(envelope, out);
            case PLAYER_ACTION -> onPlayerAction(envelope, out);
            default -> sendError(out, envelope.requestId(), ErrorPayload.MALFORMED,
                    "Unsupported type " + type);
        }
    }

    private void onPhysicalOutcome(Envelope envelope, OutputStream out) throws IOException {
        if (host.isFrozen()) {
            sendError(out, envelope.requestId(), ErrorPayload.NOT_READY, "Host frozen");
            return;
        }
        PhysicalOutcomeType type;
        Map<String, String> evidence;
        try {
            PhysicalOutcomePayload typed = PhysicalOutcomePayload.decode(envelope.payload());
            type = typed.outcomeType();
            evidence = typed.evidenceWithIdentity();
        } catch (Exception typedFail) {
            // Backward-compatible string-map outcomes during mixed clients.
            try {
                evidence = new LinkedHashMap<>(PayloadIo.decodeStrings(envelope.payload()));
                String raw = firstNonBlank(evidence.get("outcomeType"), evidence.get("type"), evidence.get("outcome"));
                if (raw == null) {
                    sendError(out, envelope.requestId(), ErrorPayload.MALFORMED, "Missing outcomeType");
                    return;
                }
                type = PhysicalOutcomeType.valueOf(raw.toUpperCase(java.util.Locale.ROOT));
            } catch (Exception e) {
                sendError(out, envelope.requestId(), ErrorPayload.MALFORMED, "Invalid physical outcome payload");
                return;
            }
        }
        SimulationContext ctx = new SimulationContext(
                host.state().seed(),
                host.state().time(),
                host.state().saveRevision()
        );
        Map<String, String> response;
        try {
            response = outcomeApplier.apply(host.state(), type, evidence, ctx);
        } catch (Exception e) {
            LOG.warning("Physical outcome apply failed: " + e);
            sendError(out, envelope.requestId(), ErrorPayload.INTERNAL, e.getMessage());
            return;
        }
        sendResponse(out, envelope.requestId(), response);
    }

    private void onHandshake(Envelope envelope, OutputStream out) throws IOException {
        HandshakePayload req = HandshakePayload.decode(envelope.payload());
        if (!req.isCompatible()) {
            HandshakePayload rejected = HandshakePayload.rejected(req.worldId(),
                    "Version mismatch: protocol=" + req.protocolVersion()
                            + " worldgen=" + req.worldGenerationVersion()
                            + " saveSchema=" + req.saveSchema());
            send(out, MessageType.HANDSHAKE_RESPONSE, envelope.requestId(), 0, rejected.encode());
            sendError(out, envelope.requestId(), ErrorPayload.VERSION_MISMATCH, rejected.message());
            socket.close();
            return;
        }
        if (!req.worldId().equals(host.worldId())) {
            HandshakePayload rejected = HandshakePayload.rejected(req.worldId(), "World id mismatch");
            send(out, MessageType.HANDSHAKE_RESPONSE, envelope.requestId(), 0, rejected.encode());
            sendError(out, envelope.requestId(), ErrorPayload.WORLD_MISMATCH, rejected.message());
            socket.close();
            return;
        }
        if (req.worldPlanHash() != 0L && req.worldPlanHash() != host.expectedPlanHash()) {
            HandshakePayload rejected = HandshakePayload.rejected(req.worldId(),
                    "Plan hash mismatch: request=" + req.worldPlanHash()
                            + " host=" + host.expectedPlanHash());
            send(out, MessageType.HANDSHAKE_RESPONSE, envelope.requestId(), 0, rejected.encode());
            sendError(out, envelope.requestId(), ErrorPayload.WORLD_MISMATCH, rejected.message());
            socket.close();
            return;
        }
        if (req.minecraftSeed() != 0L && req.minecraftSeed() != host.seed()) {
            HandshakePayload rejected = HandshakePayload.rejected(req.worldId(),
                    "Seed mismatch: request=" + req.minecraftSeed() + " host=" + host.seed());
            send(out, MessageType.HANDSHAKE_RESPONSE, envelope.requestId(), 0, rejected.encode());
            sendError(out, envelope.requestId(), ErrorPayload.WORLD_MISMATCH, rejected.message());
            socket.close();
            return;
        }
        sessionId = req.worldId();
        handshaken = true;
        host.setFrozen(false);
        HandshakePayload ready = HandshakePayload.sidecarReady(
                sessionId,
                host.seed(),
                host.expectedPlanHash(),
                host.planRevision(),
                host.worldRoot() == null ? null : host.worldRoot().toString()
        );
        send(out, MessageType.HANDSHAKE_RESPONSE, envelope.requestId(),
                host.state().time().absoluteTicks(), ready.encode());
        host.pushEvent(new EventPayload(
                com.livingmods.common.event.CivilizationEventType.SIDECAR_READY,
                0, 0, 0, 0,
                Map.of("world", sessionId.toString())
        ));
    }

    private void onSave(Envelope envelope, OutputStream out) throws IOException {
        if (!host.persistence().beginSaveBarrier()) {
            sendError(out, envelope.requestId(), ErrorPayload.TIMEOUT, "Save already in progress");
            return;
        }
        try {
            host.persistence().completeSaveBarrier(host.state());
            Map<String, String> diag = new LinkedHashMap<>(diagnostics.snapshot());
            diag.put("status", "ok");
            diag.put("revision", String.valueOf(host.state().saveRevision()));
            diag.put("contentHash", String.valueOf(host.state().contentHash()));
            diag.put("simulationTime", String.valueOf(host.state().time().absoluteTicks()));
            send(out, MessageType.SAVE_RESPONSE, envelope.requestId(),
                    host.state().time().absoluteTicks(), PayloadIo.encodeStrings(diag));
        } catch (IOException e) {
            sendError(out, envelope.requestId(), ErrorPayload.INTERNAL, e.getMessage());
        }
    }

    private void onTimeSync(Envelope envelope, OutputStream out) throws IOException {
        RequestPayloads.TimeSync sync = RequestPayloads.TimeSync.decode(envelope.payload());
        host.syncTime(sync.minecraftGameTime(), sync.jumped());
        sendResponse(out, envelope.requestId(), Map.of("synced", "true"));
    }

    private void onPlayerAction(Envelope envelope, OutputStream out) throws IOException {
        if (host.isFrozen()) {
            sendError(out, envelope.requestId(), ErrorPayload.NOT_READY, "Host frozen");
            return;
        }
        Map<String, String> fields;
        try {
            fields = PayloadIo.decodeStrings(envelope.payload());
        } catch (IOException e) {
            sendError(out, envelope.requestId(), ErrorPayload.MALFORMED, "Invalid player action payload");
            return;
        }
        String action = firstNonBlank(fields.get("action"), fields.get("type"), fields.get("outcome"));
        if (action == null) {
            sendError(out, envelope.requestId(), ErrorPayload.MALFORMED, "Missing action type");
            return;
        }
        PlayerId player = parsePlayer(fields);
        KingdomId kingdom = parseKingdom(fields);
        double delta = reputationDeltaFor(action.toUpperCase());
        SimulationContext ctx = new SimulationContext(
                host.state().seed(),
                host.state().time(),
                host.state().saveRevision()
        );
        playerSystems.recordPlayerAction(host.state(), player, kingdom, delta, ctx);
        applyActionSideEffects(action.toUpperCase(), fields);

        Map<String, String> response = new LinkedHashMap<>();
        response.put("status", "accepted");
        response.put("action", action.toUpperCase());
        response.put("reputationDelta", String.valueOf(delta));
        sendResponse(out, envelope.requestId(), response);
    }

    private void applyActionSideEffects(String action, Map<String, String> fields) {
        switch (action) {
            case "CITIZEN_KILLED" -> {
                String citizenRaw = fields.get("citizenId");
                if (citizenRaw != null && !citizenRaw.isBlank()) {
                    try {
                        CitizenId id = CitizenId.of(UUID.fromString(citizenRaw));
                        CitizenState citizen = host.state().citizens().get(id);
                        if (citizen != null) {
                            citizen.setAlive(false);
                        }
                    } catch (Exception ignored) {
                    }
                }
            }
            case "STRUCTURE_DESTROYED", "CARAVAN_ATTACKED", "CRIME_COMMITTED",
                 "TRADE_COMPLETED", "FACTION_JOINED", "POLICY_CHANGED" -> {
                // Reputation adjustment via recordPlayerAction is the primary mutation.
            }
            default -> {
            }
        }
    }

    private static double reputationDeltaFor(String action) {
        return switch (action) {
            case "TRADE_COMPLETED" -> 0.05;
            case "CITIZEN_KILLED" -> -0.35;
            case "CARAVAN_ATTACKED" -> -0.25;
            case "STRUCTURE_DESTROYED" -> -0.40;
            case "FACTION_JOINED" -> 0.20;
            case "POLICY_CHANGED" -> 0.02;
            case "CRIME_COMMITTED" -> -0.15;
            default -> 0.0;
        };
    }

    private PlayerId parsePlayer(Map<String, String> fields) {
        String raw = firstNonBlank(fields.get("playerId"), fields.get("player"));
        if (raw != null) {
            try {
                return PlayerId.of(UUID.fromString(raw));
            } catch (Exception ignored) {
            }
        }
        // Production path requires a real Minecraft player UUID — never silently book demo.
        sendDemoFallbackWarning();
        return PlayerId.of(new UUID(0L, 0L));
    }

    private void sendDemoFallbackWarning() {
        LOG.warning("PLAYER_ACTION missing playerId — rejecting demo fallback; using nil player id");
    }

    private KingdomId parseKingdom(Map<String, String> fields) {
        String raw = firstNonBlank(fields.get("kingdomId"), fields.get("kingdom"));
        if (raw != null) {
            try {
                return KingdomId.of(UUID.fromString(raw));
            } catch (Exception ignored) {
            }
        }
        return host.state().kingdoms().keySet().stream().findFirst()
                .orElse(KingdomId.of(new UUID(0L, 1L)));
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }

    private void onNearby(Envelope envelope, OutputStream out) throws IOException {
        RequestPayloads.NearbyQuery q = RequestPayloads.NearbyQuery.decode(envelope.payload());
        List<String> lines = new ArrayList<>();
        int count = 0;
        for (CitizenState c : host.state().citizens().values()) {
            if (!c.alive()) continue;
            SettlementState s = host.state().settlement(c.settlementId()).orElse(null);
            if (s == null) continue;
            double dx = s.center().x() - q.blockX();
            double dz = s.center().z() - q.blockZ();
            if (dx * dx + dz * dz > (double) q.radius() * q.radius()) continue;
            lines.add(c.id().toString() + "|" + c.givenName() + " " + c.familyName());
            if (++count >= q.limit()) break;
        }
        send(out, MessageType.RESPONSE, envelope.requestId(), host.state().time().absoluteTicks(),
                PayloadIo.encodeStringList(lines));
    }

    private void onLocate(Envelope envelope, OutputStream out) throws IOException {
        RequestPayloads.LocateQuery q = RequestPayloads.LocateQuery.decode(envelope.payload());
        List<LocateHit> hits = new ArrayList<>();
        String cat = q.category().toLowerCase();
        int limit = Math.max(1, q.limit());
        Map<String, String> kingdomNames = new LinkedHashMap<>();
        for (var k : host.worldPlan().kingdoms()) {
            kingdomNames.put(k.id().toString(), k.name());
        }

        for (PlannedSettlement s : host.worldPlan().settlements().values()) {
            if (!matchesSettlementCategory(cat, s)) continue;
            if (!q.nameFilter().isEmpty()
                    && !s.name().toLowerCase().contains(q.nameFilter().toLowerCase())) {
                continue;
            }
            String kingdom = s.ownerKingdom()
                    .map(id -> kingdomNames.getOrDefault(id.toString(), "-"))
                    .orElse("-");
            hits.add(hit(s.name(), s.tier().name(), kingdom,
                    s.center().x(), s.center().z(), q.originX(), q.originZ()));
        }
        if (cat.contains("kingdom")) {
            for (var k : host.worldPlan().kingdoms()) {
                hits.add(hit(k.name(), "KINGDOM", k.name(),
                        k.capitalCenter().x(), k.capitalCenter().z(), q.originX(), q.originZ()));
            }
        }
        if (cat.contains("mine") || cat.contains("port") || cat.contains("ruin")) {
            if (cat.contains("mine")) {
                for (PlannedResourceSite site : host.worldPlan().resourceSites()) {
                    hits.add(hit(site.resource().name(), "MINE", "-",
                            site.center().x(), site.center().z(), q.originX(), q.originZ()));
                }
            }
            if (cat.contains("ruin")) {
                for (PlannedRuin ruin : host.worldPlan().ruins()) {
                    BlockPos2 c = ruin.bounds().center();
                    hits.add(hit(ruin.historicalNote(), "RUIN", "-", c.x(), c.z(),
                            q.originX(), q.originZ()));
                }
            }
        }
        if (cat.contains("wizard")) {
            for (PlannedSettlement s : host.worldPlan().settlements().values()) {
                if (s.role() == com.livingmods.common.model.SettlementRole.WIZARD_TREES) {
                    hits.add(hit(s.name(), "WIZARD", "-",
                            s.center().x(), s.center().z(), q.originX(), q.originZ()));
                }
            }
        }
        hits.sort(Comparator.comparingLong(LocateHit::distSq));
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < Math.min(limit, hits.size()); i++) {
            lines.add(hits.get(i).line());
        }
        send(out, MessageType.RESPONSE, envelope.requestId(), host.state().time().absoluteTicks(),
                PayloadIo.encodeStringList(lines));
    }

    private static LocateHit hit(String name, int x, int z, String kind, int ox, int oz) {
        return hit(name, kind, "-", x, z, ox, oz);
    }

    private static LocateHit hit(String name, String type, String kingdom, int x, int z, int ox, int oz) {
        long dx = (long) x - ox;
        long dz = (long) z - oz;
        long distSq = dx * dx + dz * dz;
        double dist = Math.sqrt(distSq);
        return new LocateHit(formatHit(name, type, kingdom, x, z, dist), distSq);
    }

    private record LocateHit(String line, long distSq) {}

    private void onWorldSummary(Envelope envelope, OutputStream out) throws IOException {
        Map<String, String> summary = new LinkedHashMap<>();
        summary.put("kingdoms", String.valueOf(host.worldPlan().kingdoms().size()));
        summary.put("settlements", String.valueOf(host.worldPlan().settlements().size()));
        summary.put("citizens", String.valueOf(host.state().citizens().size()));
        summary.put("simTicks", String.valueOf(host.state().time().absoluteTicks()));
        summary.put("wars", String.valueOf(host.state().wars().size()));
        summary.put("epidemics", String.valueOf(host.state().epidemics().size()));
        summary.putAll(diagnostics.snapshot());
        send(out, MessageType.RESPONSE, envelope.requestId(), host.state().time().absoluteTicks(),
                PayloadIo.encodeStrings(summary));
    }

    private void onSettlementSnapshot(Envelope envelope, OutputStream out) throws IOException {
        RequestPayloads.SettlementQuery q = RequestPayloads.SettlementQuery.decode(envelope.payload());
        SettlementState s = host.state().settlement(
                com.livingmods.common.id.SettlementId.of(q.settlementId())).orElse(null);
        Map<String, String> data = new LinkedHashMap<>();
        if (s == null) {
            data.put("status", "missing");
        } else {
            data.put("status", "ok");
            data.put("name", s.name());
            data.put("tier", s.tier().name());
            data.put("role", s.role().name());
            data.put("x", String.valueOf(s.center().x()));
            data.put("z", String.valueOf(s.center().z()));
            data.put("capital", String.valueOf(s.capital()));
            data.put("legitimacy", String.format(java.util.Locale.ROOT, "%.3f", s.legitimacy()));
            data.put("housing", String.valueOf(s.housingUnits()));
            data.put("deficit", String.format(java.util.Locale.ROOT, "%.1f", s.developmentDeficit()));
            var market = host.state().markets().get(s.id());
            var stock = host.state().stockpiles().get(s.id());
            if (market != null) {
                data.put("grainPrice", String.format(java.util.Locale.ROOT, "%.3f",
                        market.price(com.livingmods.common.model.ResourceType.GRAIN)));
            }
            if (stock != null) {
                data.put("grainStock", String.format(java.util.Locale.ROOT, "%.1f",
                        stock.get(com.livingmods.common.model.ResourceType.GRAIN)));
            }
        }
        sendResponse(out, envelope.requestId(), data);
    }

    private void onKingdomSummary(Envelope envelope, OutputStream out) throws IOException {
        RequestPayloads.SettlementQuery q = RequestPayloads.SettlementQuery.decode(envelope.payload());
        var kingdom = host.state().kingdom(
                com.livingmods.common.id.KingdomId.of(q.settlementId())).orElse(null);
        Map<String, String> data = new LinkedHashMap<>();
        if (kingdom == null) {
            data.put("status", "missing");
        } else {
            data.put("status", "ok");
            data.put("name", kingdom.name());
            data.put("government", kingdom.governmentType().name());
            data.put("settlements", String.valueOf(kingdom.settlementIds().size()));
            data.put("treasury", String.format(java.util.Locale.ROOT, "%.1f", kingdom.treasury()));
            data.put("taxRate", String.format(java.util.Locale.ROOT, "%.3f", kingdom.taxRate()));
            data.put("legitimacy", String.format(java.util.Locale.ROOT, "%.3f", kingdom.legitimacy()));
            var ruler = host.state().citizens().get(kingdom.rulerId());
            if (ruler != null) {
                data.put("ruler", ruler.givenName() + " " + ruler.familyName());
            }
        }
        sendResponse(out, envelope.requestId(), data);
    }

    private void onMarketState(Envelope envelope, OutputStream out) throws IOException {
        RequestPayloads.SettlementQuery q = RequestPayloads.SettlementQuery.decode(envelope.payload());
        var sid = com.livingmods.common.id.SettlementId.of(q.settlementId());
        var market = host.state().markets().get(sid);
        var stock = host.state().stockpiles().get(sid);
        Map<String, String> data = new LinkedHashMap<>();
        if (market == null) {
            data.put("status", "missing");
        } else {
            data.put("status", "ok");
            data.put("crisis", String.format(java.util.Locale.ROOT, "%.3f", market.crisisSeverity()));
            for (var type : com.livingmods.common.model.ResourceType.values()) {
                data.put("price_" + type.name(),
                        String.format(java.util.Locale.ROOT, "%.3f", market.price(type)));
                if (stock != null) {
                    data.put("stock_" + type.name(),
                            String.format(java.util.Locale.ROOT, "%.1f", stock.get(type)));
                }
            }
        }
        sendResponse(out, envelope.requestId(), data);
    }

    private void onDialogue(Envelope envelope, OutputStream out) throws IOException {
        RequestPayloads.DialogueQuery q = RequestPayloads.DialogueQuery.decode(envelope.payload());
        var engine = new com.livingmods.simulation.engine.DialogueEngine();
        var lines = engine.respond(host.state(),
                com.livingmods.common.id.CitizenId.of(q.citizenId()), q.intent());
        List<String> texts = new ArrayList<>();
        for (var line : lines) {
            texts.add(line.speaker() + ": " + line.text());
        }
        send(out, MessageType.RESPONSE, envelope.requestId(), host.state().time().absoluteTicks(),
                PayloadIo.encodeStringList(texts));
    }

    private void onProjection(Envelope envelope, OutputStream out) throws IOException {
        RequestPayloads.NearbyQuery q = RequestPayloads.NearbyQuery.decode(envelope.payload());
        int limit = Math.max(1, q.limit());
        var plan = com.livingmods.simulation.projection.ProjectionPlan.near(
                host.state(), BlockPos2.of(q.blockX(), q.blockZ()), q.radius(), limit, 1);
        Map<String, String> data = new LinkedHashMap<>();
        data.put("status", "ok");
        data.put("citizens", String.valueOf(plan.projectedCitizens().size()));
        data.put("caravans", String.valueOf(plan.caravans().size()));
        data.put("armies", String.valueOf(plan.armies().size()));
        data.put("revision", String.valueOf(plan.revision()));
        data.put("budget", String.valueOf(plan.budget()));
        List<String> ids = new ArrayList<>();
        List<String> packed = new ArrayList<>();
        for (var projected : plan.projectedCitizens()) {
            var citizen = host.state().citizens().get(projected.citizenId());
            String cultureKey = "avalon";
            if (citizen != null) {
                var settlement = host.worldPlan().settlements().get(citizen.settlementId());
                if (settlement != null && settlement.cultureKey() != null) {
                    cultureKey = settlement.cultureKey();
                }
            }
            String profession = citizen == null ? "FARMER" : citizen.profession().name();
            boolean female = citizen != null && citizen.female();
            int age = citizen == null ? 25 : citizen.ageYears(host.state().time());
            ids.add(projected.citizenId().value().toString());
            packed.add(String.join("|",
                    projected.citizenId().value().toString(),
                    projected.displayName().replace('|', ' ').replace(';', ','),
                    projected.schedule().name(),
                    String.valueOf(projected.x()),
                    String.valueOf(projected.y()),
                    String.valueOf(projected.z()),
                    String.valueOf(projected.projectionRevision()),
                    cultureKey,
                    profession,
                    female ? "1" : "0",
                    String.valueOf(age)
            ));
        }
        data.put("citizenIds", String.join(",", ids));
        data.put("citizensPacked", String.join(";", packed));
        List<String> caravanIds = new ArrayList<>();
        List<String> caravansPacked = new ArrayList<>();
        for (var shId : plan.caravans()) {
            var sh = host.state().shipments().get(shId);
            if (sh == null) continue;
            caravanIds.add(shId.value().toString());
            var pos = sh.currentPosition();
            caravansPacked.add(String.join("|",
                    shId.value().toString(),
                    String.valueOf(pos.x()),
                    String.valueOf(pos.z()),
                    sh.goods().name(),
                    String.format(java.util.Locale.ROOT, "%.0f", sh.quantity()),
                    String.valueOf(sh.guards())));
        }
        List<String> armyIds = new ArrayList<>();
        List<String> armiesPacked = new ArrayList<>();
        for (var armyId : plan.armies()) {
            var army = host.state().armies().get(armyId);
            if (army == null) continue;
            armyIds.add(armyId.value().toString());
            armiesPacked.add(String.join("|",
                    armyId.value().toString(),
                    army.owner().value().toString(),
                    String.valueOf(army.position().x()),
                    String.valueOf(army.position().z()),
                    String.valueOf(army.manpower())));
        }
        data.put("caravanIds", String.join(",", caravanIds));
        data.put("caravansPacked", String.join(";", caravansPacked));
        data.put("armyIds", String.join(",", armyIds));
        data.put("armiesPacked", String.join(";", armiesPacked));
        sendResponse(out, envelope.requestId(), data);
    }

    private void onConstruction(Envelope envelope, OutputStream out) throws IOException {
        RequestPayloads.SettlementQuery q = RequestPayloads.SettlementQuery.decode(envelope.payload());
        SettlementState s = host.state().settlement(
                com.livingmods.common.id.SettlementId.of(q.settlementId())).orElse(null);
        Map<String, String> data = new LinkedHashMap<>();
        if (s == null) {
            data.put("status", "missing");
        } else {
            data.put("status", "ok");
            data.put("housingUnits", String.valueOf(s.housingUnits()));
            data.put("physicalCapacity",
                    String.format(java.util.Locale.ROOT, "%.1f", s.physicalCapacity()));
            data.put("developmentDeficit",
                    String.format(java.util.Locale.ROOT, "%.1f", s.developmentDeficit()));
            data.put("needsConstruction", String.valueOf(s.developmentDeficit() > 0.5));
            data.put("dynamicHousing", String.valueOf(
                    host.state().dynamicPhysical().activeHousingUnits(s.id())));
            List<String> packed = new ArrayList<>();
            for (PhysicalIntent intent : host.state().dynamicPhysical().readyForSettlement(s.id())) {
                packed.add(String.join("|",
                        intent.id().value().toString(),
                        intent.type().name(),
                        intent.status().name(),
                        String.valueOf(intent.priority()),
                        String.valueOf(intent.footprint().minX()),
                        String.valueOf(intent.footprint().minZ()),
                        String.valueOf(intent.footprint().maxX()),
                        String.valueOf(intent.footprint().maxZ()),
                        intent.buildingRole().map(Enum::name).orElse(""),
                        intent.cultureKey() == null ? "" : intent.cultureKey(),
                        s.id().value().toString(),
                        intent.structureId().map(id -> id.value().toString()).orElse(""),
                        intent.provenance().getOrDefault("variant", "")
                ));
            }
            data.put("intentsPacked", String.join(";", packed));
            data.put("intentCount", String.valueOf(packed.size()));
        }
        sendResponse(out, envelope.requestId(), data);
    }

    private void onMapOverlay(Envelope envelope, OutputStream out) throws IOException {
        RequestPayloads.NearbyQuery q;
        try {
            q = RequestPayloads.NearbyQuery.decode(envelope.payload());
        } catch (Exception e) {
            q = new RequestPayloads.NearbyQuery(0, 0, 2048, 64);
        }
        int cx = q.blockX();
        int cz = q.blockZ();
        int radius = Math.max(256, q.radius());
        long r2 = (long) radius * radius;
        Map<String, String> data = new LinkedHashMap<>();
        data.put("status", "ok");
        data.put("kingdoms", String.valueOf(host.worldPlan().kingdoms().size()));
        data.put("settlements", String.valueOf(host.worldPlan().settlements().size()));
        data.put("roads", String.valueOf(host.worldPlan().roads().size()));
        data.put("wars", String.valueOf(host.state().wars().size()));
        data.put("epidemics", String.valueOf(host.state().epidemics().size()));

        List<String> armies = new ArrayList<>();
        for (var army : host.state().armies().values()) {
            long dx = (long) army.position().x() - cx;
            long dz = (long) army.position().z() - cz;
            if (dx * dx + dz * dz > r2) continue;
            armies.add(String.join("|",
                    army.id().value().toString(),
                    army.owner().value().toString(),
                    String.valueOf(army.position().x()),
                    String.valueOf(army.position().z()),
                    String.valueOf(army.manpower())));
            if (armies.size() >= q.limit()) break;
        }
        List<String> caravans = new ArrayList<>();
        for (var sh : host.state().shipments().values()) {
            if (sh.delivered() || sh.looted()) continue;
            var pos = sh.currentPosition();
            long dx = (long) pos.x() - cx;
            long dz = (long) pos.z() - cz;
            if (dx * dx + dz * dz > r2) continue;
            caravans.add(String.join("|",
                    sh.id().value().toString(),
                    String.valueOf(pos.x()),
                    String.valueOf(pos.z()),
                    sh.goods().name(),
                    String.format(java.util.Locale.ROOT, "%.0f", sh.quantity())));
            if (caravans.size() >= q.limit()) break;
        }
        List<String> epidemics = new ArrayList<>();
        for (var ep : host.state().epidemics().values()) {
            if (!ep.active()) continue;
            for (var sid : ep.affectedSettlements()) {
                SettlementState s = host.state().settlements().get(sid);
                if (s == null) continue;
                long dx = (long) s.center().x() - cx;
                long dz = (long) s.center().z() - cz;
                if (dx * dx + dz * dz > r2) continue;
                epidemics.add(String.join("|",
                        ep.pathogenKey() == null ? "disease" : ep.pathogenKey(),
                        String.valueOf(s.center().x()),
                        String.valueOf(s.center().z()),
                        String.format(java.util.Locale.ROOT, "%.2f", ep.severity())));
                if (epidemics.size() >= q.limit()) break;
            }
        }
        List<String> migrations = new ArrayList<>();
        for (var mig : host.state().migrations().values()) {
            var pos = mig.position();
            if (pos == null) continue;
            long dx = (long) pos.x() - cx;
            long dz = (long) pos.z() - cz;
            if (dx * dx + dz * dz > r2) continue;
            migrations.add(String.join("|",
                    mig.reasonKey() == null ? "migration" : mig.reasonKey(),
                    String.valueOf(pos.x()),
                    String.valueOf(pos.z()),
                    String.valueOf(mig.population())));
            if (migrations.size() >= q.limit()) break;
        }
        List<String> construction = new ArrayList<>();
        for (PhysicalIntent intent : host.state().dynamicPhysical().activeIntentsByPriority()) {
            if (!intent.settlementId().isPresent()) continue;
            var pos = intent.targetPosition();
            long dx = (long) pos.x() - cx;
            long dz = (long) pos.z() - cz;
            if (dx * dx + dz * dz > r2) continue;
            construction.add(String.join("|",
                    intent.id().value().toString(),
                    intent.type().name(),
                    String.valueOf(pos.x()),
                    String.valueOf(pos.z()),
                    intent.status().name()));
            if (construction.size() >= q.limit()) break;
        }
        data.put("armiesPacked", String.join(";", armies));
        data.put("caravansPacked", String.join(";", caravans));
        data.put("epidemicsPacked", String.join(";", epidemics));
        data.put("migrationsPacked", String.join(";", migrations));
        data.put("constructionPacked", String.join(";", construction));
        data.put("sieges", String.valueOf(host.state().sieges().values().stream().filter(sg -> sg.active()).count()));
        sendResponse(out, envelope.requestId(), data);
    }

    private void onSubscribe(Envelope envelope, OutputStream out, boolean subscribe) throws IOException {
        RequestPayloads.RegionSubscription sub = RequestPayloads.RegionSubscription.decode(envelope.payload());
        host.setRegionSubscription(sub.regionX(), sub.regionZ(), subscribe ? sub.detailLevel() : -1);
        sendResponse(out, envelope.requestId(), Map.of(
                "status", "ok",
                "subscribed", String.valueOf(subscribe),
                "region", sub.regionX() + "," + sub.regionZ()
        ));
    }

    private static boolean matchesSettlementCategory(String cat, PlannedSettlement s) {
        if (cat.contains("capital") && s.capital()) return true;
        if (cat.contains("settlement")) return true;
        return switch (cat) {
            case "hamlet" -> s.tier() == SettlementTier.HAMLET;
            case "village" -> s.tier() == SettlementTier.VILLAGE;
            case "town" -> s.tier() == SettlementTier.TOWN;
            case "city" -> s.tier() == SettlementTier.CITY;
            default -> cat.contains(s.tier().name().toLowerCase())
                    || cat.contains(s.role().name().toLowerCase());
        };
    }

    private static String formatHit(String name, int x, int z, String kind) {
        return formatHit(name, kind, "-", x, z, 0);
    }

    private static String formatHit(String name, String type, String kingdom, int x, int z, double distance) {
        return String.format(java.util.Locale.ROOT, "%s | %s | %s | %d, %d | %.0fm",
                name, type, kingdom == null || kingdom.isBlank() ? "-" : kingdom, x, z, distance);
    }

    private void sendResponse(OutputStream out, long requestId, Map<String, String> payload)
            throws IOException {
        send(out, MessageType.RESPONSE, requestId, host.state().time().absoluteTicks(),
                PayloadIo.encodeStrings(payload));
    }

    private void sendError(OutputStream out, long requestId, int code, String message) throws IOException {
        ErrorPayload err = new ErrorPayload(code, message);
        send(out, MessageType.ERROR, requestId, host.state().time().absoluteTicks(), err.encode());
    }

    private void send(OutputStream out, MessageType type, long requestId, long simTicks, byte[] payload)
            throws IOException {
        Envelope env = new Envelope(
                ProtocolConstants.PROTOCOL_VERSION,
                type,
                0,
                messageId.getAndIncrement(),
                requestId,
                simTicks,
                sessionId != null ? sessionId : host.worldId(),
                payload
        );
        BinaryCodec.writeEnvelope(out, env);
        diagnostics.recordOutbound(payload.length + ProtocolConstants.HEADER_SIZE);
    }
}
