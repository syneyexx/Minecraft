package com.livingmods.simulation.state;

import com.livingmods.common.id.KingdomId;

import java.util.HashMap;
import java.util.Map;

/**
 * Faction-specific intelligence quality in [0,1]. Never perfect global knowledge.
 */
public final class IntelligenceState {
    private final Map<KingdomId, Map<KingdomId, Double>> knowledge = new HashMap<>();

    public Map<KingdomId, Map<KingdomId, Double>> knowledge() { return knowledge; }

    public double quality(KingdomId observer, KingdomId subject) {
        if (observer.equals(subject)) return 1.0;
        Map<KingdomId, Double> row = knowledge.get(observer);
        if (row == null) return 0.15;
        return Math.max(0.0, Math.min(0.95, row.getOrDefault(subject, 0.15)));
    }

    public void setQuality(KingdomId observer, KingdomId subject, double quality) {
        if (observer.equals(subject)) return;
        knowledge.computeIfAbsent(observer, id -> new HashMap<>())
                .put(subject, Math.max(0.0, Math.min(0.95, quality)));
    }

    public void improve(KingdomId observer, KingdomId subject, double delta) {
        setQuality(observer, subject, quality(observer, subject) + delta);
    }
}
