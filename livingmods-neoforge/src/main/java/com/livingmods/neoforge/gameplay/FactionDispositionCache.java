package com.livingmods.neoforge.gameplay;

import com.livingmods.common.model.FactionDisposition;
import com.livingmods.common.model.PlayerLegalStatus;
import com.livingmods.neoforge.LivingModsMod;
import com.livingmods.neoforge.sidecar.SidecarClient;
import com.livingmods.neoforge.sidecar.WorldSessionLifecycle;
import com.livingmods.protocol.MessageType;
import com.livingmods.protocol.PayloadIo;
import com.livingmods.protocol.PlayerActionRequest;
import com.livingmods.protocol.PlayerActionResponse;
import com.livingmods.protocol.PlayerActionType;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bounded async disposition / legal cache for AI predicates.
 * Jurisdiction-aware: wanted/war hostility is keyed by (player, kingdom).
 * Never performs blocking IPC on the server thread or inside AI predicates.
 */
public final class FactionDispositionCache {
    private static final FactionDispositionCache INSTANCE = new FactionDispositionCache();
    private static final int MAX_ENTRIES = 4096;

    /** Immutable cache key for player↔kingdom disposition. */
    public record PlayerKingdomKey(UUID playerId, UUID kingdomId) {
        public PlayerKingdomKey {
            Objects.requireNonNull(playerId);
            Objects.requireNonNull(kingdomId);
        }
    }

    /** Immutable cache key for player↔settlement legal status. */
    public record PlayerSettlementLegalKey(UUID playerId, UUID settlementId) {
        public PlayerSettlementLegalKey {
            Objects.requireNonNull(playerId);
            Objects.requireNonNull(settlementId);
        }
    }

    public record PlayerKingdomDisposition(
            FactionDisposition disposition,
            PlayerLegalStatus legalStatus,
            boolean wanted,
            boolean citizenOrRuler
    ) {
        public static PlayerKingdomDisposition neutral() {
            return new PlayerKingdomDisposition(
                    FactionDisposition.NEUTRAL, PlayerLegalStatus.CLEAR, false, false);
        }

        public boolean hostile() {
            return disposition == FactionDisposition.HOSTILE
                    || disposition == FactionDisposition.AT_WAR
                    || (wanted && (legalStatus == PlayerLegalStatus.WANTED
                    || legalStatus == PlayerLegalStatus.CONVICTED));
        }
    }

    private final ConcurrentHashMap<String, FactionDisposition> kingdomPairs = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<PlayerKingdomKey, PlayerKingdomDisposition> playerKingdom =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<PlayerSettlementLegalKey, PlayerLegalStatus> settlementLegal =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Long> lastPlayerRefresh = new ConcurrentHashMap<>();
    private volatile long lastPairRefreshMs;
    private volatile boolean pairRefreshInFlight;

    public static FactionDispositionCache get() { return INSTANCE; }

    public void clear() {
        kingdomPairs.clear();
        playerKingdom.clear();
        settlementLegal.clear();
        lastPlayerRefresh.clear();
        lastPairRefreshMs = 0;
        pairRefreshInFlight = false;
    }

    public FactionDisposition betweenKingdoms(UUID a, UUID b) {
        if (a == null || b == null) return FactionDisposition.NEUTRAL;
        if (a.equals(b)) return FactionDisposition.ALLIED;
        return kingdomPairs.getOrDefault(pairKey(a, b), FactionDisposition.NEUTRAL);
    }

    public boolean militaryHostile(UUID a, UUID b) {
        FactionDisposition d = betweenKingdoms(a, b);
        return d == FactionDisposition.AT_WAR || d == FactionDisposition.HOSTILE;
    }

    /**
     * Jurisdiction-aware player hostility.
     * @param kingdomOrFaction kingdom UUID for soldiers, or settlement's owning kingdom for guards
     */
    public boolean hostileToPlayer(UUID playerId, UUID kingdomOrFaction) {
        if (playerId == null || kingdomOrFaction == null || isZero(kingdomOrFaction)) return false;
        PlayerKingdomDisposition d = playerKingdom.get(new PlayerKingdomKey(playerId, kingdomOrFaction));
        return d != null && d.hostile();
    }

    /** Guard hostility: prefer settlement legal record, then kingdom disposition. */
    public boolean guardsHostileToPlayer(UUID playerId, UUID settlementId, UUID kingdomId) {
        if (playerId == null) return false;
        if (settlementId != null && !isZero(settlementId)) {
            PlayerLegalStatus legal = settlementLegal.get(new PlayerSettlementLegalKey(playerId, settlementId));
            if (legal == PlayerLegalStatus.WANTED || legal == PlayerLegalStatus.CONVICTED) {
                return true;
            }
            if (legal == PlayerLegalStatus.FINE_OUTSTANDING) {
                // Fine outstanding: approach/detain, not automatic lethal hostility.
                return false;
            }
        }
        return hostileToPlayer(playerId, kingdomId);
    }

    public PlayerKingdomDisposition disposition(UUID playerId, UUID kingdomId) {
        if (playerId == null || kingdomId == null) return PlayerKingdomDisposition.neutral();
        return playerKingdom.getOrDefault(new PlayerKingdomKey(playerId, kingdomId),
                PlayerKingdomDisposition.neutral());
    }

    public String legalStatus(UUID playerId, UUID kingdomId) {
        PlayerKingdomDisposition d = disposition(playerId, kingdomId);
        return d.legalStatus().name();
    }

    /** @deprecated Prefer jurisdiction-aware {@link #legalStatus(UUID, UUID)}. */
    @Deprecated
    public String legalStatus(UUID playerId) {
        if (playerId == null) return "CLEAR";
        for (var e : playerKingdom.entrySet()) {
            if (e.getKey().playerId().equals(playerId) && e.getValue().wanted()) {
                return e.getValue().legalStatus().name();
            }
        }
        return "CLEAR";
    }

    public void putKingdomPair(UUID a, UUID b, FactionDisposition disposition) {
        if (kingdomPairs.size() > MAX_ENTRIES) kingdomPairs.clear();
        kingdomPairs.put(pairKey(a, b), disposition == null ? FactionDisposition.NEUTRAL : disposition);
    }

    public void putPlayerKingdom(
            UUID playerId,
            UUID kingdomId,
            FactionDisposition disposition,
            PlayerLegalStatus legal,
            boolean wanted,
            boolean citizenOrRuler
    ) {
        if (playerId == null || kingdomId == null || isZero(kingdomId)) return;
        if (playerKingdom.size() > MAX_ENTRIES) playerKingdom.clear();
        playerKingdom.put(new PlayerKingdomKey(playerId, kingdomId),
                new PlayerKingdomDisposition(
                        disposition == null ? FactionDisposition.NEUTRAL : disposition,
                        legal == null ? PlayerLegalStatus.CLEAR : legal,
                        wanted,
                        citizenOrRuler));
    }

    public void putSettlementLegal(UUID playerId, UUID settlementId, PlayerLegalStatus status) {
        if (playerId == null || settlementId == null || isZero(settlementId)) return;
        if (settlementLegal.size() > MAX_ENTRIES) settlementLegal.clear();
        settlementLegal.put(new PlayerSettlementLegalKey(playerId, settlementId),
                status == null ? PlayerLegalStatus.CLEAR : status);
    }

    /** Mark local wanted hint for a specific kingdom until async refresh lands. */
    public void markWantedLocal(UUID playerId, UUID kingdomId) {
        if (playerId == null || kingdomId == null) return;
        PlayerKingdomDisposition prev = disposition(playerId, kingdomId);
        putPlayerKingdom(playerId, kingdomId, FactionDisposition.HOSTILE,
                PlayerLegalStatus.WANTED, true, prev.citizenOrRuler());
    }

    public void clearWantedLocal(UUID playerId, UUID kingdomId, PlayerLegalStatus status) {
        if (playerId == null || kingdomId == null) return;
        PlayerKingdomDisposition prev = disposition(playerId, kingdomId);
        boolean wanted = status == PlayerLegalStatus.WANTED || status == PlayerLegalStatus.CONVICTED;
        FactionDisposition d = wanted ? FactionDisposition.HOSTILE
                : (prev.citizenOrRuler() ? FactionDisposition.FRIENDLY : FactionDisposition.NEUTRAL);
        putPlayerKingdom(playerId, kingdomId, d, status, wanted, prev.citizenOrRuler());
    }

    /** Async refresh from sidecar — call from tick (non-blocking). */
    public void tickRefresh(UUID playerId) {
        long now = System.currentTimeMillis();
        refreshKingdomPairs(now);
        if (playerId == null) return;
        Long last = lastPlayerRefresh.get(playerId);
        if (last != null && now - last < 4000) return;
        SidecarClient client = WorldSessionLifecycle.activeClient();
        if (client == null || !client.isReady()) return;
        lastPlayerRefresh.put(playerId, now);
        try {
            PlayerActionRequest req = new PlayerActionRequest(
                    PlayerActionType.QUERY_PLAYER_CONTEXT, playerId,
                    new UUID(0, 0), new UUID(0, 0), new UUID(0, 0), new UUID(0, 0),
                    new UUID(0, 0), 0, 0, 0, 0L, Map.of("dispositionCache", "true"));
            client.sendAsync(MessageType.PLAYER_ACTION, req.encode()).thenAccept(env -> {
                try {
                    PlayerActionResponse resp = PlayerActionResponse.decode(env.payload());
                    applyPlayerContext(playerId, resp.data(), resp.standing());
                } catch (Exception ignored) {
                }
            });
        } catch (Exception e) {
            LivingModsMod.LOG.debug("Disposition player refresh failed: {}", e.toString());
        }
    }

    private void refreshKingdomPairs(long now) {
        if (now - lastPairRefreshMs < 4000 || pairRefreshInFlight) return;
        SidecarClient client = WorldSessionLifecycle.activeClient();
        if (client == null || !client.isReady()) return;
        pairRefreshInFlight = true;
        lastPairRefreshMs = now;
        try {
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
                    pairRefreshInFlight = false;
                }
            }).exceptionally(ex -> {
                pairRefreshInFlight = false;
                return null;
            });
        } catch (Exception e) {
            pairRefreshInFlight = false;
        }
    }

    private void applyPlayerContext(UUID playerId, Map<String, String> data, String standing) {
        if (data == null) return;
        // dispositionByKingdom: kingdomId|disposition|legal|wanted|citizen
        String packed = data.getOrDefault("dispositionByKingdom", "");
        if (!packed.isBlank()) {
            for (String row : packed.split(";")) {
                if (row.isBlank()) continue;
                String[] p = row.split("\\|", -1);
                if (p.length < 3) continue;
                try {
                    UUID kingdomId = UUID.fromString(p[0]);
                    FactionDisposition d = FactionDisposition.valueOf(p[1]);
                    PlayerLegalStatus legal = PlayerLegalStatus.CLEAR;
                    try {
                        legal = PlayerLegalStatus.valueOf(p[2]);
                    } catch (Exception ignored) {
                    }
                    boolean wanted = p.length > 3 && "true".equalsIgnoreCase(p[3]);
                    boolean citizen = p.length > 4 && "true".equalsIgnoreCase(p[4]);
                    if ("RULER".equals(standing) && data.getOrDefault("ruledKingdomId", "").equals(p[0])) {
                        d = FactionDisposition.ALLIED;
                        citizen = true;
                    }
                    putPlayerKingdom(playerId, kingdomId, d, legal, wanted, citizen);
                } catch (Exception ignored) {
                }
            }
        }
        // legalBySettlement: settlementId|status
        String legalPacked = data.getOrDefault("legalBySettlement", "");
        if (!legalPacked.isBlank()) {
            for (String row : legalPacked.split(";")) {
                if (row.isBlank()) continue;
                String[] p = row.split("\\|", -1);
                if (p.length < 2) continue;
                try {
                    putSettlementLegal(playerId, UUID.fromString(p[0]), PlayerLegalStatus.valueOf(p[1]));
                } catch (Exception ignored) {
                }
            }
        }
        String ruled = data.getOrDefault("ruledKingdomId", "");
        if (!ruled.isBlank()) {
            try {
                putPlayerKingdom(playerId, UUID.fromString(ruled), FactionDisposition.ALLIED,
                        PlayerLegalStatus.CLEAR, false, true);
            } catch (Exception ignored) {
            }
        }
    }

    private static String pairKey(UUID a, UUID b) {
        String left = a.toString();
        String right = b.toString();
        return left.compareTo(right) <= 0 ? left + "|" + right : right + "|" + left;
    }

    private static boolean isZero(UUID id) {
        return id.getMostSignificantBits() == 0L && id.getLeastSignificantBits() == 0L;
    }
}
