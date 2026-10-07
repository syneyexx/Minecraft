package com.livingmods.simulation.state;

import com.livingmods.common.id.KingdomId;
import com.livingmods.common.id.PlayerId;

import java.util.HashMap;
import java.util.Map;

public final class PlayerReputationState {
    private final Map<PlayerId, Map<KingdomId, Double>> reputationByPlayer = new HashMap<>();

    public double reputation(PlayerId player, KingdomId kingdom) {
        return reputationByPlayer
                .getOrDefault(player, Map.of())
                .getOrDefault(kingdom, 0.0);
    }

    public void adjust(PlayerId player, KingdomId kingdom, double delta) {
        reputationByPlayer
                .computeIfAbsent(player, p -> new HashMap<>())
                .merge(kingdom, delta, Double::sum);
    }

    public Map<PlayerId, Map<KingdomId, Double>> reputationByPlayer() { return reputationByPlayer; }
}
