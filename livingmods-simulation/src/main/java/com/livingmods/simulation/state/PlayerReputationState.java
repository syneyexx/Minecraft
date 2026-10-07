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

    public KingdomId ruledKingdom(PlayerId player) {
        return ruledKingdoms.get(player);
    }

    public void setRuledKingdom(PlayerId player, KingdomId kingdom) {
        ruledKingdoms.put(player, kingdom);
        setStanding(player, kingdom, FactionStanding.RULER);
    }

    private void refreshStanding(PlayerId player, KingdomId kingdom) {
        if (standing(player, kingdom) == FactionStanding.RULER) {
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
        setStanding(player, kingdom, next);
    }

    public Map<PlayerId, Map<KingdomId, Double>> reputationByPlayer() { return reputationByPlayer; }
    public Map<PlayerId, Map<SettlementId, Double>> settlementReputation() { return settlementReputation; }
    public Map<PlayerId, Map<KingdomId, FactionStanding>> factionStanding() { return factionStanding; }
    public Map<PlayerId, KingdomId> ruledKingdoms() { return ruledKingdoms; }
}
