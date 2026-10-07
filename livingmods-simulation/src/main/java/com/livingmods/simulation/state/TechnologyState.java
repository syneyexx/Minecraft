package com.livingmods.simulation.state;

import com.livingmods.common.id.SettlementId;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Knowledge spread per settlement — not a global unlock table. */
public final class TechnologyState {
    private final Map<SettlementId, Set<String>> knownTechnologies = new HashMap<>();
    private final Map<SettlementId, Map<String, Double>> researchProgress = new HashMap<>();

    public Set<String> known(SettlementId settlement) {
        return knownTechnologies.computeIfAbsent(settlement, s -> new HashSet<>());
    }

    public Map<String, Double> progress(SettlementId settlement) {
        return researchProgress.computeIfAbsent(settlement, s -> new HashMap<>());
    }

    public Map<SettlementId, Set<String>> knownTechnologies() { return knownTechnologies; }
    public Map<SettlementId, Map<String, Double>> researchProgress() { return researchProgress; }
}
