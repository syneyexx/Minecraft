package com.livingmods.neoforge.gameplay;

import com.livingmods.common.model.FactionDisposition;
import com.livingmods.neoforge.LivingModsMod;
import com.livingmods.neoforge.sidecar.SidecarClient;
import com.livingmods.neoforge.sidecar.WorldSessionLifecycle;
import com.livingmods.protocol.MessageType;
import com.livingmods.protocol.PayloadIo;
import com.livingmods.protocol.PlayerActionRequest;
import com.livingmods.protocol.PlayerActionResponse;
import com.livingmods.protocol.PlayerActionType;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bounded async disposition / legal cache for AI predicates.
 * Never performs blocking IPC on the server thread.
 */
public final class FactionDispositionCache {
    private static final FactionDispositionCache INSTANCE = new FactionDispositionCache();
    private static final int MAX_ENTRIES = 4096;

    private final ConcurrentHashMap<String, FactionDisposition> kingdomPairs = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, FactionDisposition> playerKingdom = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, String> playerLegal = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Boolean> playerWanted = new ConcurrentHashMap<>();
    private volatile long lastRefreshMs;
    private volatile boolean refreshInFlight;

    public static FactionDispositionCache get() { return INSTANCE; }

    public void clear() {
        kingdomPairs.clear();
        playerKingdom.clear();
        playerLegal.clear();
        playerWanted.clear();
        lastRefreshMs = 0;
        refreshInFlight = false;
    }

    public FactionDisposition betweenKingdoms(UUID a, UUID b) {
        if (a == null || b == null) return FactionDisposition.NEUTRAL;
        if (a.equals(b)) return FactionDisposition.ALLIED;
        String key = pairKey(a, b);
        return kingdomPairs.getOrDefault(key, FactionDisposition.NEUTRAL);
    }

    public boolean militaryHostile(UUID a, UUID b) {
        FactionDisposition d = betweenKingdoms(a, b);
        return d == FactionDisposition.AT_WAR || d == FactionDisposition.HOSTILE;
    }

    public boolean hostileToPlayer(UUID playerId, UUID kingdomOrSettlementFaction) {
        if (playerId == null) return false;
        if (Boolean.TRUE.equals(playerWanted.get(playerId))) return true;
        FactionDisposition d = playerKingdom.get(playerId);
        return d == FactionDisposition.HOSTILE || d == FactionDisposition.AT_WAR;
    }

    public String legalStatus(UUID playerId) {
        return playerLegal.getOrDefault(playerId, "CLEAR");
    }

    public void putKingdomPair(UUID a, UUID b, FactionDisposition disposition) {
        if (kingdomPairs.size() > MAX_ENTRIES) kingdomPairs.clear();
        kingdomPairs.put(pairKey(a, b), disposition == null ? FactionDisposition.NEUTRAL : disposition);
    }

    public void putPlayerDisposition(UUID playerId, FactionDisposition disposition) {
        if (playerId == null) return;
        playerKingdom.put(playerId, disposition == null ? FactionDisposition.NEUTRAL : disposition);
    }

    public void putPlayerLegal(UUID playerId, String status, boolean wanted) {
        if (playerId == null) return;
        playerLegal.put(playerId, status == null ? "CLEAR" : status);
        playerWanted.put(playerId, wanted);
    }

    /** Async refresh from sidecar world summary / player context — call from tick (non-blocking). */
    public void tickRefresh(UUID playerId) {
        long now = System.currentTimeMillis();
        if (now - lastRefreshMs < 4000 || refreshInFlight) return;
        SidecarClient client = WorldSessionLifecycle.activeClient();
        if (client == null || !client.isReady()) return;
        refreshInFlight = true;
        lastRefreshMs = now;
        try {
            // Disposition pairs are packed into world summary by sidecar enrichment.
            client.sendAsync(MessageType.GET_WORLD_SUMMARY, new byte[0]).thenAccept(env -> {
                try {
                    Map<String, String> data = PayloadIo.decodeStrings(env.payload());
                    String packed = data.getOrDefault("dispositionPairs", "");
                    if (!packed.isBlank()) {
                        for (String row : packed.split(";")) {
                            String[] p = row.split("\\|");
                            if (p.length < 3) continue;
                            try {
                                putKingdomPair(UUID.fromString(p[0]), UUID.fromString(p[1]),
                                        FactionDisposition.valueOf(p[2]));
                            } catch (Exception ignored) {
                            }
                        }
                    }
                } catch (Exception e) {
                    LivingModsMod.LOG.debug("Disposition cache refresh failed: {}", e.toString());
                } finally {
                    refreshInFlight = false;
                }
            }).exceptionally(ex -> {
                refreshInFlight = false;
                return null;
            });
            if (playerId != null) {
                PlayerActionRequest req = new PlayerActionRequest(
                        PlayerActionType.QUERY_PLAYER_CONTEXT, playerId,
                        new UUID(0, 0), new UUID(0, 0), new UUID(0, 0), new UUID(0, 0),
                        new UUID(0, 0), 0, 0, 0, 0L, Map.of());
                client.sendAsync(MessageType.PLAYER_ACTION, req.encode()).thenAccept(env -> {
                    try {
                        PlayerActionResponse resp = PlayerActionResponse.decode(env.payload());
                        String legal = resp.data().getOrDefault("legalStatus", "CLEAR");
                        // Aggregate from known kingdoms war flags
                        boolean wanted = legal.contains("WANTED") || legal.contains("CONVICTED");
                        putPlayerLegal(playerId, legal, wanted);
                        String wars = resp.data().getOrDefault("activeWars", "");
                        if (!wars.isBlank()) {
                            putPlayerDisposition(playerId, FactionDisposition.NEUTRAL);
                        }
                        String standing = resp.standing();
                        if ("RULER".equals(standing)) {
                            putPlayerDisposition(playerId, FactionDisposition.ALLIED);
                        }
                    } catch (Exception ignored) {
                    }
                });
            }
        } catch (Exception e) {
            refreshInFlight = false;
        }
    }

    private static String pairKey(UUID a, UUID b) {
        String left = a.toString();
        String right = b.toString();
        return left.compareTo(right) <= 0 ? left + "|" + right : right + "|" + left;
    }
}
