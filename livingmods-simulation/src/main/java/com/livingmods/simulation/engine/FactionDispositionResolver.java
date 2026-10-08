package com.livingmods.simulation.engine;

import com.livingmods.common.id.KingdomId;
import com.livingmods.common.id.PlayerId;
import com.livingmods.common.id.SettlementId;
import com.livingmods.common.model.DiplomaticRelation;
import com.livingmods.common.model.FactionDisposition;
import com.livingmods.common.model.FactionStanding;
import com.livingmods.common.model.PlayerLegalStatus;
import com.livingmods.simulation.CanonicalWorldState;
import com.livingmods.simulation.state.PlayerLegalRecord;
import com.livingmods.simulation.state.WarState;

/**
 * Authoritative geopolitical disposition. Entities must not invent hostility from
 * faction-ID inequality alone.
 */
public final class FactionDispositionResolver {

    private FactionDispositionResolver() {}

    public static FactionDisposition betweenKingdoms(CanonicalWorldState state, KingdomId a, KingdomId b) {
        if (a == null || b == null) return FactionDisposition.NEUTRAL;
        if (a.equals(b)) return FactionDisposition.ALLIED;
        for (WarState war : state.wars().values()) {
            if (!war.active()) continue;
            if ((war.aggressor().equals(a) && war.defender().equals(b))
                    || (war.aggressor().equals(b) && war.defender().equals(a))) {
                return FactionDisposition.AT_WAR;
            }
        }
        DiplomaticRelation relation = state.diplomacy().relation(a, b);
        return switch (relation) {
            case ALLIED, OVERLORD, VASSAL -> FactionDisposition.ALLIED;
            case FRIENDLY -> FactionDisposition.FRIENDLY;
            case NEUTRAL -> FactionDisposition.NEUTRAL;
            case TENSE -> FactionDisposition.SUSPICIOUS;
            case HOSTILE -> FactionDisposition.HOSTILE;
            case AT_WAR -> FactionDisposition.AT_WAR;
        };
    }

    public static boolean militaryHostile(CanonicalWorldState state, KingdomId a, KingdomId b) {
        FactionDisposition d = betweenKingdoms(state, a, b);
        return d == FactionDisposition.AT_WAR || d == FactionDisposition.HOSTILE;
    }

    public static FactionDisposition playerTowardKingdom(CanonicalWorldState state, PlayerId player, KingdomId kingdom) {
        if (player == null || kingdom == null) return FactionDisposition.NEUTRAL;
        if (kingdom.equals(state.playerReputation().ruledKingdom(player))) {
            return FactionDisposition.ALLIED;
        }
        PlayerLegalRecord legal = state.playerReputation().legalRecord(player, kingdom);
        if (legal != null && legal.isWantedOrWorse()) {
            return FactionDisposition.HOSTILE;
        }
        FactionStanding standing = state.playerReputation().standing(player, kingdom);
        double rep = state.playerReputation().reputation(player, kingdom);
        if (standing.atLeast(FactionStanding.CITIZEN) || rep >= 0.4) {
            return FactionDisposition.FRIENDLY;
        }
        if (rep <= -0.35) {
            return FactionDisposition.HOSTILE;
        }
        if (rep < -0.1) {
            return FactionDisposition.SUSPICIOUS;
        }
        // If player's ruled kingdom is at war with this kingdom:
        KingdomId ruled = state.playerReputation().ruledKingdom(player);
        if (ruled != null && militaryHostile(state, ruled, kingdom)) {
            return FactionDisposition.AT_WAR;
        }
        // Membership allegiance during war:
        if (standing.atLeast(FactionStanding.CITIZEN)) {
            for (WarState war : state.wars().values()) {
                if (!war.active()) continue;
                if (war.aggressor().equals(kingdom) || war.defender().equals(kingdom)) {
                    KingdomId other = war.aggressor().equals(kingdom) ? war.defender() : war.aggressor();
                    FactionStanding otherStanding = state.playerReputation().standing(player, other);
                    if (otherStanding.atLeast(FactionStanding.CITIZEN)) {
                        return FactionDisposition.AT_WAR;
                    }
                }
            }
        }
        return FactionDisposition.NEUTRAL;
    }

    public static boolean guardsHostileToPlayer(
            CanonicalWorldState state,
            PlayerId player,
            SettlementId settlementId
    ) {
        if (player == null || settlementId == null) return false;
        var settlement = state.settlements().get(settlementId);
        if (settlement == null) return false;
        PlayerLegalRecord bySettlement = state.playerReputation().legalRecordForSettlement(player, settlementId);
        if (bySettlement != null && bySettlement.isWantedOrWorse()) {
            return bySettlement.status() == PlayerLegalStatus.WANTED
                    || bySettlement.status() == PlayerLegalStatus.CONVICTED;
        }
        if (settlement.ownerKingdom().isEmpty()) return false;
        FactionDisposition d = playerTowardKingdom(state, player, settlement.ownerKingdom().get());
        return d == FactionDisposition.HOSTILE || d == FactionDisposition.AT_WAR;
    }

    /** Compact cache key for NeoForge disposition cache. */
    public static String pairKey(KingdomId a, KingdomId b) {
        if (a == null || b == null) return "null";
        String left = a.value().toString();
        String right = b.value().toString();
        return left.compareTo(right) <= 0 ? left + "|" + right : right + "|" + left;
    }
}
