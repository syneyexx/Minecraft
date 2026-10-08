package com.livingmods.neoforge.gameplay;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lightweight server-side interaction sessions: player + target + revision + expiry.
 * Prevents remote UUID injection and stale UI actions.
 */
public final class PlayerInteractionSessions {
    private static final PlayerInteractionSessions INSTANCE = new PlayerInteractionSessions();
    public static final double MAX_DISTANCE = 8.0;
    public static final long TTL_MS = 120_000L;

    public record Session(
            UUID sessionId,
            UUID playerId,
            UUID targetEntityId,
            UUID citizenId,
            UUID settlementId,
            UUID kingdomId,
            long createdMs,
            long revision,
            Map<String, String> context
    ) {
        public boolean expired() {
            return System.currentTimeMillis() - createdMs > TTL_MS;
        }
    }

    private final ConcurrentHashMap<UUID, Session> byPlayer = new ConcurrentHashMap<>();

    public static PlayerInteractionSessions get() { return INSTANCE; }

    public void clear() {
        byPlayer.clear();
    }

    public Session open(
            ServerPlayer player,
            Entity target,
            UUID citizenId,
            UUID settlementId,
            UUID kingdomId,
            long revision,
            Map<String, String> context
    ) {
        if (player == null || target == null) return null;
        if (player.distanceTo(target) > MAX_DISTANCE) return null;
        UUID sessionId = UUID.nameUUIDFromBytes(
                (player.getUUID() + "|" + citizenId + "|" + revision + "|" + System.currentTimeMillis())
                        .getBytes());
        Session session = new Session(
                sessionId, player.getUUID(), target.getUUID(), citizenId, settlementId, kingdomId,
                System.currentTimeMillis(), revision,
                context == null ? Map.of() : Map.copyOf(context));
        byPlayer.put(player.getUUID(), session);
        return session;
    }

    public Session get(UUID playerId) {
        Session s = byPlayer.get(playerId);
        if (s == null) return null;
        if (s.expired()) {
            byPlayer.remove(playerId, s);
            return null;
        }
        return s;
    }

    public Session require(ServerPlayer player, UUID sessionId) {
        Session s = get(player.getUUID());
        if (s == null) return null;
        if (sessionId != null && !sessionId.equals(new UUID(0, 0)) && !s.sessionId().equals(sessionId)) {
            return null;
        }
        return s;
    }

    public boolean stillInRange(ServerPlayer player, Entity target) {
        return player != null && target != null && player.distanceTo(target) <= MAX_DISTANCE;
    }

    public void close(UUID playerId) {
        if (playerId != null) byPlayer.remove(playerId);
    }
}
