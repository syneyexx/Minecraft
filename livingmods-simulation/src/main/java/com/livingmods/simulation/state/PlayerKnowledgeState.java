package com.livingmods.simulation.state;

import com.livingmods.common.id.KingdomId;
import com.livingmods.common.id.PlayerId;
import com.livingmods.common.id.SettlementId;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Lightweight player cartography / discovery layer.
 * Distinguishes known vs unknown settlements/kingdoms/camps — not a full fog-of-war engine.
 */
public final class PlayerKnowledgeState {
    public enum KnowledgeLevel {
        UNKNOWN,
        RUMORED,
        KNOWN,
        OBSERVED
    }

    private final Map<PlayerId, Map<SettlementId, KnowledgeLevel>> settlements = new HashMap<>();
    private final Map<PlayerId, Map<KingdomId, KnowledgeLevel>> kingdoms = new HashMap<>();
    private final Map<PlayerId, Set<UUID>> knownCamps = new HashMap<>();
    private final Map<PlayerId, Set<UUID>> knownTaskMarkers = new HashMap<>();

    public KnowledgeLevel settlementKnowledge(PlayerId player, SettlementId settlement) {
        return settlements.getOrDefault(player, Map.of()).getOrDefault(settlement, KnowledgeLevel.UNKNOWN);
    }

    public KnowledgeLevel kingdomKnowledge(PlayerId player, KingdomId kingdom) {
        return kingdoms.getOrDefault(player, Map.of()).getOrDefault(kingdom, KnowledgeLevel.UNKNOWN);
    }

    public void discoverSettlement(PlayerId player, SettlementId settlement, KnowledgeLevel level) {
        if (player == null || settlement == null || level == null || level == KnowledgeLevel.UNKNOWN) return;
        Map<SettlementId, KnowledgeLevel> map = settlements.computeIfAbsent(player, p -> new HashMap<>());
        KnowledgeLevel current = map.getOrDefault(settlement, KnowledgeLevel.UNKNOWN);
        if (level.ordinal() > current.ordinal()) {
            map.put(settlement, level);
        }
    }

    public void discoverKingdom(PlayerId player, KingdomId kingdom, KnowledgeLevel level) {
        if (player == null || kingdom == null || level == null || level == KnowledgeLevel.UNKNOWN) return;
        Map<KingdomId, KnowledgeLevel> map = kingdoms.computeIfAbsent(player, p -> new HashMap<>());
        KnowledgeLevel current = map.getOrDefault(kingdom, KnowledgeLevel.UNKNOWN);
        if (level.ordinal() > current.ordinal()) {
            map.put(kingdom, level);
        }
    }

    public void discoverCamp(PlayerId player, UUID campId) {
        if (player == null || campId == null) return;
        knownCamps.computeIfAbsent(player, p -> new HashSet<>()).add(campId);
    }

    public boolean knowsCamp(PlayerId player, UUID campId) {
        return knownCamps.getOrDefault(player, Set.of()).contains(campId);
    }

    public void markTask(PlayerId player, UUID taskId) {
        if (player == null || taskId == null) return;
        knownTaskMarkers.computeIfAbsent(player, p -> new HashSet<>()).add(taskId);
    }

    public Set<UUID> taskMarkers(PlayerId player) {
        return Set.copyOf(knownTaskMarkers.getOrDefault(player, Set.of()));
    }

    public Map<PlayerId, Map<SettlementId, KnowledgeLevel>> settlements() { return settlements; }
    public Map<PlayerId, Map<KingdomId, KnowledgeLevel>> kingdoms() { return kingdoms; }
    public Map<PlayerId, Set<UUID>> knownCamps() { return knownCamps; }
    public Map<PlayerId, Set<UUID>> knownTaskMarkers() { return knownTaskMarkers; }
}
