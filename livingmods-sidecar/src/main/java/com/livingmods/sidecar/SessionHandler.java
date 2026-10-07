package com.livingmods.sidecar;

import com.livingmods.common.geo.BlockPos2;
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
import com.livingmods.simulation.state.CitizenState;
import com.livingmods.simulation.state.SettlementState;
import com.livingmods.worldgen.plan.PlannedResourceSite;
import com.livingmods.worldgen.plan.PlannedRuin;
import com.livingmods.worldgen.plan.PlannedSettlement;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
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
            while (!socket.isClosed()) {
                Envelope envelope = BinaryCodec.readEnvelope(in);
                diagnostics.recordInbound(envelope.payloadLength() + ProtocolConstants.HEADER_SIZE);
                handle(envelope, out);
                EventPayload event;
                while ((event = host.pollEvent()) != null) {
                    send(out, MessageType.EVENT, 0, 0, event.encode());
                }
            }
        } catch (IOException e) {
            LOG.info("Session closed: " + e.getMessage());
        }
    }

    private void handle(Envelope envelope, OutputStream out) throws IOException {
        switch (envelope.type()) {
            case HANDSHAKE_REQUEST -> onHandshake(envelope, out);
            case HEARTBEAT -> send(out, MessageType.HEARTBEAT, envelope.requestId(), envelope.simulationTicks(), new byte[0]);
            case SAVE_REQUEST -> onSave(envelope, out);
            case SHUTDOWN -> {
                send(out, MessageType.SHUTDOWN, envelope.requestId(), host.state().time().absoluteTicks(), new byte[0]);
                socket.close();
            }
            case TIME_SYNC -> onTimeSync(envelope, out);
            case GET_NEARBY_CITIZENS -> onNearby(envelope, out);
            case LOCATE -> onLocate(envelope, out);
            case GET_WORLD_SUMMARY -> onWorldSummary(envelope, out);
            case GET_SETTLEMENT_SNAPSHOT, GET_KINGDOM_SUMMARY, GET_MAP_OVERLAY,
                 GET_DIALOGUE_CONTEXT, GET_MARKET_STATE, GET_CONSTRUCTION_PLAN,
                 GET_PHYSICAL_PROJECTION_PLAN, REPORT_PHYSICAL_OUTCOME,
                 SUBSCRIBE_REGION, UNSUBSCRIBE_REGION, PLAYER_ACTION ->
                    sendResponse(out, envelope.requestId(), Map.of("status", "not_implemented"));
            default -> sendError(out, envelope.requestId(), ErrorPayload.MALFORMED, "Unsupported type " + envelope.type());
        }
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
        sessionId = req.worldId();
        handshaken = true;
        HandshakePayload ready = HandshakePayload.sidecarReady(sessionId);
        send(out, MessageType.HANDSHAKE_RESPONSE, envelope.requestId(), host.state().time().absoluteTicks(), ready.encode());
        host.pushEvent(new EventPayload(
                com.livingmods.common.event.CivilizationEventType.SIDECAR_READY,
                0, 0, 0, 0,
                Map.of("world", sessionId.toString())
        ));
    }

    private void onSave(Envelope envelope, OutputStream out) throws IOException {
        if (!handshaken) {
            sendError(out, envelope.requestId(), ErrorPayload.NOT_READY, "Handshake required");
            return;
        }
        if (!host.persistence().beginSaveBarrier()) {
            sendError(out, envelope.requestId(), ErrorPayload.TIMEOUT, "Save already in progress");
            return;
        }
        try {
            host.persistence().completeSaveBarrier(host.state());
            Map<String, String> diag = diagnostics.snapshot();
            diag.put("status", "ok");
            send(out, MessageType.SAVE_RESPONSE, envelope.requestId(), host.state().time().absoluteTicks(), PayloadIo.encodeStrings(diag));
        } catch (IOException e) {
            sendError(out, envelope.requestId(), ErrorPayload.INTERNAL, e.getMessage());
        }
    }

    private void onTimeSync(Envelope envelope, OutputStream out) throws IOException {
        RequestPayloads.TimeSync sync = RequestPayloads.TimeSync.decode(envelope.payload());
        long target = sync.minecraftGameTime();
        host.syncTime(target, sync.jumped());
        sendResponse(out, envelope.requestId(), Map.of("synced", "true"));
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
            if (dx * dx + dz * dz > q.radius() * q.radius()) continue;
            lines.add(c.id().toString() + "|" + c.givenName() + " " + c.familyName());
            if (++count >= q.limit()) break;
        }
        send(out, MessageType.RESPONSE, envelope.requestId(), host.state().time().absoluteTicks(), PayloadIo.encodeStringList(lines));
    }

    private void onLocate(Envelope envelope, OutputStream out) throws IOException {
        RequestPayloads.LocateQuery q = RequestPayloads.LocateQuery.decode(envelope.payload());
        List<String> hits = new ArrayList<>();
        String cat = q.category().toLowerCase();
        int limit = Math.max(1, q.limit());

        for (PlannedSettlement s : host.worldPlan().settlements().values()) {
            if (!matchesSettlementCategory(cat, s)) continue;
            if (!q.nameFilter().isEmpty() && !s.name().toLowerCase().contains(q.nameFilter().toLowerCase())) continue;
            hits.add(formatHit(s.name(), s.center().x(), s.center().z(), s.tier().name()));
            if (hits.size() >= limit) break;
        }
        if (cat.contains("kingdom")) {
            for (var k : host.worldPlan().kingdoms()) {
                hits.add(formatHit(k.name(), k.capitalCenter().x(), k.capitalCenter().z(), "KINGDOM"));
                if (hits.size() >= limit) break;
            }
        }
        if (cat.contains("mine") || cat.contains("port") || cat.contains("ruin")) {
            if (cat.contains("mine")) {
                for (PlannedResourceSite site : host.worldPlan().resourceSites()) {
                    hits.add(formatHit(site.resource().name(), site.center().x(), site.center().z(), "MINE"));
                    if (hits.size() >= limit) break;
                }
            }
            if (cat.contains("ruin")) {
                for (PlannedRuin ruin : host.worldPlan().ruins()) {
                    BlockPos2 c = ruin.bounds().center();
                    hits.add(formatHit(ruin.historicalNote(), c.x(), c.z(), "RUIN"));
                    if (hits.size() >= limit) break;
                }
            }
        }
        if (cat.contains("wizard")) {
            for (PlannedSettlement s : host.worldPlan().settlements().values()) {
                if (s.role() == com.livingmods.common.model.SettlementRole.WIZARD_TREES) {
                    hits.add(formatHit(s.name(), s.center().x(), s.center().z(), "WIZARD"));
                    if (hits.size() >= limit) break;
                }
            }
        }
        send(out, MessageType.RESPONSE, envelope.requestId(), host.state().time().absoluteTicks(), PayloadIo.encodeStringList(hits));
    }

    private void onWorldSummary(Envelope envelope, OutputStream out) throws IOException {
        Map<String, String> summary = new LinkedHashMap<>();
        summary.put("kingdoms", String.valueOf(host.worldPlan().kingdoms().size()));
        summary.put("settlements", String.valueOf(host.worldPlan().settlements().size()));
        summary.put("citizens", String.valueOf(host.state().citizens().size()));
        summary.put("simTicks", String.valueOf(host.state().time().absoluteTicks()));
        summary.putAll(diagnostics.snapshot());
        send(out, MessageType.RESPONSE, envelope.requestId(), host.state().time().absoluteTicks(), PayloadIo.encodeStrings(summary));
    }

    private static boolean matchesSettlementCategory(String cat, PlannedSettlement s) {
        if (cat.contains("capital") && s.capital()) return true;
        if (cat.contains("settlement")) return true;
        return switch (cat) {
            case "hamlet" -> s.tier() == SettlementTier.HAMLET;
            case "village" -> s.tier() == SettlementTier.VILLAGE;
            case "town" -> s.tier() == SettlementTier.TOWN;
            case "city" -> s.tier() == SettlementTier.CITY;
            default -> cat.contains(s.tier().name().toLowerCase()) || cat.contains(s.role().name().toLowerCase());
        };
    }

    private static String formatHit(String name, int x, int z, String kind) {
        return name + "@" + x + "," + z + " [" + kind + "]";
    }

    private void sendResponse(OutputStream out, long requestId, Map<String, String> payload) throws IOException {
        send(out, MessageType.RESPONSE, requestId, host.state().time().absoluteTicks(), PayloadIo.encodeStrings(payload));
    }

    private void sendError(OutputStream out, long requestId, int code, String message) throws IOException {
        ErrorPayload err = new ErrorPayload(code, message);
        send(out, MessageType.ERROR, requestId, host.state().time().absoluteTicks(), err.encode());
    }

    private void send(OutputStream out, MessageType type, long requestId, long simTicks, byte[] payload) throws IOException {
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
