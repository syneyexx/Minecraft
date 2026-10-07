package com.livingmods.simulation.state;

import com.livingmods.common.id.KingdomId;
import com.livingmods.common.id.SettlementId;
import com.livingmods.common.model.TechnologyDefinition;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Knowledge spread per settlement / faction — not a global unlock table. */
public final class TechnologyState {
    private final Map<SettlementId, Set<String>> knownTechnologies = new HashMap<>();
    private final Map<SettlementId, Map<String, Double>> researchProgress = new HashMap<>();
    private final Map<KingdomId, Set<String>> factionKnowledge = new HashMap<>();
    private final Map<SettlementId, Double> literacy = new HashMap<>();
    private final Map<SettlementId, Integer> schoolCapacity = new HashMap<>();

    public Set<String> known(SettlementId settlement) {
        return knownTechnologies.computeIfAbsent(settlement, s -> {
            Set<String> seed = new HashSet<>();
            seed.add(TechnologyDefinition.BASIC_TOOLS);
            return seed;
        });
    }

    public Map<String, Double> progress(SettlementId settlement) {
        return researchProgress.computeIfAbsent(settlement, s -> new HashMap<>());
    }

    public Set<String> factionKnown(KingdomId kingdom) {
        return factionKnowledge.computeIfAbsent(kingdom, k -> new HashSet<>());
    }

    public double literacy(SettlementId settlement) {
        return literacy.getOrDefault(settlement, 0.1);
    }

    public void setLiteracy(SettlementId settlement, double value) {
        literacy.put(settlement, Math.max(0, Math.min(1, value)));
    }

    public int schoolCapacity(SettlementId settlement) {
        return schoolCapacity.getOrDefault(settlement, 0);
    }

    public void setSchoolCapacity(SettlementId settlement, int capacity) {
        schoolCapacity.put(settlement, Math.max(0, capacity));
    }

    public boolean knows(SettlementId settlement, String tech) {
        return known(settlement).contains(tech);
    }

    public Map<SettlementId, Set<String>> knownTechnologies() { return knownTechnologies; }
    public Map<SettlementId, Map<String, Double>> researchProgress() { return researchProgress; }
    public Map<KingdomId, Set<String>> factionKnowledge() { return factionKnowledge; }
    public Map<SettlementId, Double> literacyMap() { return literacy; }
    public Map<SettlementId, Integer> schoolCapacityMap() { return schoolCapacity; }
}
