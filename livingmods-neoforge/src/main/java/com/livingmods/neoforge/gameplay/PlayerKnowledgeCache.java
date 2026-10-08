package com.livingmods.neoforge.gameplay;

import com.livingmods.neoforge.LivingModsMod;
import com.livingmods.neoforge.sidecar.SidecarClient;
import com.livingmods.neoforge.sidecar.WorldSessionLifecycle;
import com.livingmods.protocol.MessageType;
import com.livingmods.protocol.PlayerActionRequest;
import com.livingmods.protocol.PlayerActionResponse;
import com.livingmods.protocol.PlayerActionType;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bounded async player knowledge cache for knowledge-filtered map payloads.
 * Never blocks the server thread; never used as mutation authority.
 */
public final class PlayerKnowledgeCache {
    private static final PlayerKnowledgeCache INSTANCE = new PlayerKnowledgeCache();
    private static final int MAX_PLAYERS = 64;
    private static final long REFRESH_MS = 3000L;

    public enum Level { UNKNOWN, RUMORED, KNOWN, OBSERVED }

    public record Entry(Level settlementLevel, String name, String kingdom, String tier, int x, int z, boolean capital) {}

    private final ConcurrentHashMap<UUID, ConcurrentHashMap<UUID, Level>> settlementKnowledge = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, ConcurrentHashMap<UUID, Level>> kingdomKnowledge = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Set<UUID>> knownCamps = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Set<UUID>> knownTasks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, UUID> ruledKingdom = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Long> lastRefresh = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Boolean> refreshInFlight = new ConcurrentHashMap<>();

    public static PlayerKnowledgeCache get() { return INSTANCE; }

    public void clear() {
        settlementKnowledge.clear();
        kingdomKnowledge.clear();
        knownCamps.clear();
        knownTasks.clear();
        ruledKingdom.clear();
        lastRefresh.clear();
        refreshInFlight.clear();
    }

    public Level settlementLevel(UUID playerId, UUID settlementId) {
        if (playerId == null || settlementId == null) return Level.UNKNOWN;
        ConcurrentHashMap<UUID, Level> map = settlementKnowledge.get(playerId);
        if (map == null) return Level.UNKNOWN;
        return map.getOrDefault(settlementId, Level.UNKNOWN);
    }

    public Level kingdomLevel(UUID playerId, UUID kingdomId) {
        if (playerId == null || kingdomId == null) return Level.UNKNOWN;
        ConcurrentHashMap<UUID, Level> map = kingdomKnowledge.get(playerId);
        if (map == null) return Level.UNKNOWN;
        return map.getOrDefault(kingdomId, Level.UNKNOWN);
    }

    public boolean knowsCamp(UUID playerId, UUID campId) {
        Set<UUID> set = knownCamps.get(playerId);
        return set != null && campId != null && set.contains(campId);
    }

    public boolean knowsTask(UUID playerId, UUID taskId) {
        Set<UUID> set = knownTasks.get(playerId);
        return set != null && taskId != null && set.contains(taskId);
    }

    public UUID ruledKingdom(UUID playerId) {
        return ruledKingdom.get(playerId);
    }

    public boolean ownsKingdom(UUID playerId, UUID kingdomId) {
        UUID ruled = ruledKingdom.get(playerId);
        return ruled != null && kingdomId != null && ruled.equals(kingdomId);
    }

    public void putSettlement(UUID playerId, UUID settlementId, Level level) {
        if (playerId == null || settlementId == null || level == null || level == Level.UNKNOWN) return;
        ensurePlayer(playerId);
        settlementKnowledge.get(playerId).merge(settlementId, level, PlayerKnowledgeCache::max);
    }

    public void putKingdom(UUID playerId, UUID kingdomId, Level level) {
        if (playerId == null || kingdomId == null || level == null || level == Level.UNKNOWN) return;
        ensurePlayer(playerId);
        kingdomKnowledge.get(playerId).merge(kingdomId, level, PlayerKnowledgeCache::max);
    }

    public void setRuledKingdom(UUID playerId, UUID kingdomId) {
        if (playerId == null) return;
        if (kingdomId == null || isZero(kingdomId)) {
            ruledKingdom.remove(playerId);
        } else {
            ruledKingdom.put(playerId, kingdomId);
            putKingdom(playerId, kingdomId, Level.OBSERVED);
        }
    }

    /** Async refresh from canonical QUERY_PLAYER_CONTEXT — call from tick/map request. */
    public void tickRefresh(UUID playerId, int blockX, int blockY, int blockZ) {
        if (playerId == null) return;
        long now = System.currentTimeMillis();
        Long last = lastRefresh.get(playerId);
        if (last != null && now - last < REFRESH_MS) return;
        if (Boolean.TRUE.equals(refreshInFlight.get(playerId))) return;
        SidecarClient client = WorldSessionLifecycle.activeClient();
        if (client == null || !client.isReady()) return;
        refreshInFlight.put(playerId, true);
        lastRefresh.put(playerId, now);
        try {
            PlayerActionRequest req = new PlayerActionRequest(
                    PlayerActionType.QUERY_PLAYER_CONTEXT, playerId,
                    new UUID(0, 0), new UUID(0, 0), new UUID(0, 0), new UUID(0, 0),
                    new UUID(0, 0), blockX, blockY, blockZ, 0L, Map.of("knowledgeCache", "true"));
            client.sendAsync(MessageType.PLAYER_ACTION, req.encode()).thenAccept(env -> {
                try {
                    PlayerActionResponse resp = PlayerActionResponse.decode(env.payload());
                    applyContext(playerId, resp.data());
                } catch (Exception e) {
                    LivingModsMod.LOG.debug("Knowledge cache refresh failed: {}", e.toString());
                } finally {
                    refreshInFlight.put(playerId, false);
                }
            }).exceptionally(ex -> {
                refreshInFlight.put(playerId, false);
                return null;
            });
        } catch (Exception e) {
            refreshInFlight.put(playerId, false);
        }
    }

    public void applyContext(UUID playerId, Map<String, String> data) {
        if (playerId == null || data == null) return;
        ensurePlayer(playerId);
        String ruled = data.getOrDefault("ruledKingdomId", "");
        if (!ruled.isBlank()) {
            try {
                setRuledKingdom(playerId, UUID.fromString(ruled));
            } catch (Exception ignored) {
            }
        } else {
            ruledKingdom.remove(playerId);
        }
        parseKnowledgeRows(playerId, data.get("knownSettlements"), true);
        parseKnowledgeRows(playerId, data.get("knownKingdoms"), false);
        parseIdSet(playerId, data.get("knownCamps"), knownCamps);
        parseIdSet(playerId, data.get("knownTaskMarkers"), knownTasks);
    }

    private void parseKnowledgeRows(UUID playerId, String packed, boolean settlements) {
        if (packed == null || packed.isBlank()) return;
        for (String row : packed.split(";")) {
            if (row.isBlank()) continue;
            String[] p = row.split("\\|", -1);
            if (p.length < 1) continue;
            try {
                UUID id = UUID.fromString(p[0]);
                Level level = Level.KNOWN;
                if (settlements && p.length >= 9) {
                    level = parseLevel(p[8]);
                } else if (!settlements && p.length >= 1) {
                    level = Level.KNOWN;
                }
                if (settlements) {
                    putSettlement(playerId, id, level);
                    if (p.length >= 8 && !p[7].isBlank()) {
                        try {
                            putKingdom(playerId, UUID.fromString(p[7]), Level.KNOWN);
                        } catch (Exception ignored) {
                        }
                    }
                } else {
                    putKingdom(playerId, id, level);
                }
            } catch (Exception ignored) {
            }
        }
    }

    private static void parseIdSet(UUID playerId, String packed, ConcurrentHashMap<UUID, Set<UUID>> target) {
        if (packed == null || packed.isBlank()) return;
        Set<UUID> set = ConcurrentHashMap.newKeySet();
        for (String part : packed.split(",")) {
            if (part.isBlank()) continue;
            try {
                set.add(UUID.fromString(part.trim()));
            } catch (Exception ignored) {
            }
        }
        target.put(playerId, set);
    }

    private void ensurePlayer(UUID playerId) {
        if (settlementKnowledge.size() > MAX_PLAYERS) {
            settlementKnowledge.clear();
            kingdomKnowledge.clear();
        }
        settlementKnowledge.computeIfAbsent(playerId, k -> new ConcurrentHashMap<>());
        kingdomKnowledge.computeIfAbsent(playerId, k -> new ConcurrentHashMap<>());
    }

    private static Level max(Level a, Level b) {
        return a.ordinal() >= b.ordinal() ? a : b;
    }

    private static Level parseLevel(String raw) {
        if (raw == null || raw.isBlank()) return Level.KNOWN;
        try {
            return Level.valueOf(raw.trim().toUpperCase());
        } catch (Exception e) {
            return Level.KNOWN;
        }
    }

    private static boolean isZero(UUID id) {
        return id.getMostSignificantBits() == 0L && id.getLeastSignificantBits() == 0L;
    }
}
