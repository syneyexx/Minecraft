package com.livingmods.simulation.state;

import com.livingmods.common.id.KingdomId;
import com.livingmods.common.id.PlayerId;
import com.livingmods.common.id.SettlementId;
import com.livingmods.common.model.FactionStanding;

import java.util.HashMap;
import java.util.Map;

public final class PlayerReputationState {
    private final Map<PlayerId, Map<KingdomId, Double>> reputationByPlayer = new HashMap<>();
    private final Map<PlayerId, Map<SettlementId, Double>> settlementReputation = new HashMap<>();
    private final Map<PlayerId, Map<KingdomId, FactionStanding>> factionStanding = new HashMap<>();
    private final Map<PlayerId, KingdomId> ruledKingdoms = new HashMap<>();
    private final Map<PlayerId, Map<KingdomId, PlayerLegalRecord>> legalByKingdom = new HashMap<>();
    private final Map<PlayerId, Map<SettlementId, PlayerLegalRecord>> legalBySettlement = new HashMap<>();
    private final PlayerKnowledgeState knowledge = new PlayerKnowledgeState();
    /** Soft policy preferences persisted for player realms (do not directly set outcomes). */
    private final Map<PlayerId, Map<String, Double>> realmPolicies = new HashMap<>();

    public double reputation(PlayerId player, KingdomId kingdom) {
        return reputationByPlayer
                .getOrDefault(player, Map.of())
                .getOrDefault(kingdom, 0.0);
    }

    public double settlementReputation(PlayerId player, SettlementId settlement) {
        return settlementReputation
                .getOrDefault(player, Map.of())
                .getOrDefault(settlement, 0.0);
    }

    public void adjust(PlayerId player, KingdomId kingdom, double delta) {
        reputationByPlayer
                .computeIfAbsent(player, p -> new HashMap<>())
                .merge(kingdom, delta, Double::sum);
        refreshStanding(player, kingdom);
    }

    public void adjustSettlement(PlayerId player, SettlementId settlement, double delta) {
        settlementReputation
                .computeIfAbsent(player, p -> new HashMap<>())
                .merge(settlement, delta, Double::sum);
    }

    public FactionStanding standing(PlayerId player, KingdomId kingdom) {
        return factionStanding
                .getOrDefault(player, Map.of())
                .getOrDefault(kingdom, FactionStanding.NEUTRAL);
    }

    public void setStanding(PlayerId player, KingdomId kingdom, FactionStanding standing) {
        factionStanding.computeIfAbsent(player, p -> new HashMap<>()).put(kingdom, standing);
        if (standing == FactionStanding.RULER) {
            ruledKingdoms.put(player, kingdom);
        }
    }

    public void clearStanding(PlayerId player, KingdomId kingdom) {
        Map<KingdomId, FactionStanding> map = factionStanding.get(player);
        if (map != null) {
            map.remove(kingdom);
        }
        if (kingdom.equals(ruledKingdoms.get(player))) {
            ruledKingdoms.remove(player);
        }
    }

    public KingdomId ruledKingdom(PlayerId player) {
        return ruledKingdoms.get(player);
    }

    public void setRuledKingdom(PlayerId player, KingdomId kingdom) {
        ruledKingdoms.put(player, kingdom);
        setStanding(player, kingdom, FactionStanding.RULER);
    }

    public PlayerLegalRecord legalRecord(PlayerId player, KingdomId kingdom) {
        return legalByKingdom.getOrDefault(player, Map.of()).get(kingdom);
    }

    public PlayerLegalRecord legalRecordForSettlement(PlayerId player, SettlementId settlement) {
        return legalBySettlement.getOrDefault(player, Map.of()).get(settlement);
    }

    public PlayerLegalRecord ensureLegal(PlayerId player, KingdomId kingdom, SettlementId settlement) {
        if (kingdom != null) {
            return legalByKingdom
                    .computeIfAbsent(player, p -> new HashMap<>())
                    .computeIfAbsent(kingdom, k -> new PlayerLegalRecord(kingdom, settlement));
        }
        if (settlement != null) {
            return legalBySettlement
                    .computeIfAbsent(player, p -> new HashMap<>())
                    .computeIfAbsent(settlement, s -> new PlayerLegalRecord(null, settlement));
        }
        return null;
    }

    public Map<PlayerId, Map<KingdomId, PlayerLegalRecord>> legalByKingdom() { return legalByKingdom; }
    public Map<PlayerId, Map<SettlementId, PlayerLegalRecord>> legalBySettlement() { return legalBySettlement; }

    public PlayerKnowledgeState knowledge() { return knowledge; }

    public void setPolicy(PlayerId player, String key, double value) {
        if (player == null || key == null || key.isBlank()) return;
        realmPolicies.computeIfAbsent(player, p -> new HashMap<>()).put(key, value);
    }

    public double policy(PlayerId player, String key, double fallback) {
        return realmPolicies.getOrDefault(player, Map.of()).getOrDefault(key, fallback);
    }

    public Map<PlayerId, Map<String, Double>> realmPolicies() { return realmPolicies; }

    /** Attitude label for UI — derived from standing/reputation, not a second number. */
    public String attitudeLabel(PlayerId player, KingdomId kingdom) {
        if (player == null || kingdom == null) return "Neutral";
        PlayerLegalRecord legal = legalRecord(player, kingdom);
        if (legal != null && legal.isWantedOrWorse()) return "Hostile";
        FactionStanding standing = standing(player, kingdom);
        double rep = reputation(player, kingdom);
        if (standing == FactionStanding.RULER || standing.atLeast(FactionStanding.TRUSTED) || rep >= 0.5) {
            return "Trusted";
        }
        if (standing.atLeast(FactionStanding.CITIZEN) || rep >= 0.2) {
            return "Friendly";
        }
        if (rep <= -0.35) return "Hostile";
        if (rep < -0.05) return "Suspicious";
        return "Neutral";
    }

    private void refreshStanding(PlayerId player, KingdomId kingdom) {
        if (standing(player, kingdom) == FactionStanding.RULER) {
            return;
        }
        // Do not auto-demote explicit OFFICIAL membership from reputation alone when already set higher.
        FactionStanding current = standing(player, kingdom);
        if (current == FactionStanding.OFFICIAL) {
            return;
        }
        double rep = reputation(player, kingdom);
        FactionStanding next;
        if (rep >= 0.75) {
            next = FactionStanding.OFFICIAL;
        } else if (rep >= 0.4) {
            next = FactionStanding.TRUSTED;
        } else if (rep >= 0.15) {
            next = FactionStanding.CITIZEN;
        } else {
            next = FactionStanding.NEUTRAL;
        }
        // Preserve explicit CITIZEN membership even if reputation drifts slightly.
        if (current == FactionStanding.CITIZEN && next == FactionStanding.NEUTRAL && rep >= 0.05) {
            return;
        }
        setStanding(player, kingdom, next);
    }

    public Map<PlayerId, Map<KingdomId, Double>> reputationByPlayer() { return reputationByPlayer; }
    public Map<PlayerId, Map<SettlementId, Double>> settlementReputation() { return settlementReputation; }
    public Map<PlayerId, Map<KingdomId, FactionStanding>> factionStanding() { return factionStanding; }
    public Map<PlayerId, KingdomId> ruledKingdoms() { return ruledKingdoms; }
}
