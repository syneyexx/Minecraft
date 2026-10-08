package com.livingmods.simulation.engine;

import com.livingmods.common.id.KingdomId;
import com.livingmods.common.id.PlayerId;
import com.livingmods.common.model.DiplomaticRelation;
import com.livingmods.common.model.TreatyType;
import com.livingmods.simulation.CanonicalWorldState;
import com.livingmods.simulation.state.KingdomState;
import com.livingmods.simulation.state.WarState;

/**
 * Deterministic NPC evaluation of player diplomatic proposals.
 * No Math.random() — outcome is a pure function of canonical state.
 */
public final class DiplomacyProposalEvaluator {

    public enum Decision { ACCEPTED, REJECTED, INVALID }

    public record Evaluation(Decision decision, String reason, TreatyType treatyType) {}

    private DiplomacyProposalEvaluator() {}

    public static Evaluation evaluate(
            CanonicalWorldState state,
            PlayerId player,
            KingdomId proposer,
            KingdomId target,
            String proposalKind
    ) {
        if (state == null || proposer == null || target == null || proposer.equals(target)) {
            return new Evaluation(Decision.INVALID, "Invalid diplomacy parties", null);
        }
        KingdomState self = state.kingdoms().get(proposer);
        KingdomState other = state.kingdoms().get(target);
        if (self == null || other == null) {
            return new Evaluation(Decision.INVALID, "Unknown kingdom", null);
        }
        String kind = proposalKind == null ? "" : proposalKind.toUpperCase();
        DiplomaticRelation relation = state.diplomacy().relation(proposer, target);
        double score = state.diplomacy().score(proposer, target);
        double playerRep = state.playerReputation().reputation(player, target);
        boolean atWar = relation == DiplomaticRelation.AT_WAR || activeWar(state, proposer, target) != null;
        double militaryBalance = militaryBalance(state, proposer, target);
        double otherTreasury = other.treasury();
        double warExhaustion = warExhaustion(state, proposer, target);

        return switch (kind) {
            case "PEACE", "REQUEST_PEACE" -> evaluatePeace(
                    atWar, score, playerRep, militaryBalance, warExhaustion, other);
            case "TRADE", "TREATY" -> evaluateTreaty(
                    TreatyType.TRADE, atWar, score, playerRep, otherTreasury, relation);
            case "NON_AGGRESSION" -> evaluateTreaty(
                    TreatyType.NON_AGGRESSION, atWar, score, playerRep, otherTreasury, relation);
            case "ALLIANCE" -> evaluateAlliance(atWar, score, playerRep, militaryBalance, relation, other);
            case "BREAK", "BREAK_TREATY" -> {
                // Breaking is unilateral (player chooses); NPC does not veto — but still validate.
                if (!hasActiveTreaty(state, proposer, target)) {
                    yield new Evaluation(Decision.INVALID, "No active treaty to break", null);
                }
                yield new Evaluation(Decision.ACCEPTED, "Treaty break recorded", null);
            }
            case "IMPROVE" -> {
                if (atWar) {
                    yield new Evaluation(Decision.REJECTED, "Cannot improve relations during war", null);
                }
                if (score < -40 && playerRep < -0.2) {
                    yield new Evaluation(Decision.REJECTED,
                            other.name() + " refuses gifts from a despised ruler", null);
                }
                yield new Evaluation(Decision.ACCEPTED, "Diplomatic gift accepted", null);
            }
            default -> new Evaluation(Decision.INVALID, "Unsupported diplomacy type: " + kind, null);
        };
    }

    private static Evaluation evaluatePeace(
            boolean atWar,
            double score,
            double playerRep,
            double militaryBalance,
            double warExhaustion,
            KingdomState other
    ) {
        if (!atWar) {
            return new Evaluation(Decision.INVALID, "Not at war with " + other.name(), null);
        }
        // Accept peace when exhausted, losing, or relations not utterly toxic.
        int acceptScore = 0;
        if (warExhaustion >= 0.45) acceptScore += 3;
        if (militaryBalance < -0.15) acceptScore += 2; // target is weaker → more willing
        if (militaryBalance > 0.35) acceptScore -= 2;  // target stronger → harder peace
        if (score > -30) acceptScore += 1;
        if (playerRep >= 0) acceptScore += 1;
        if (playerRep <= -0.4) acceptScore -= 2;
        if (acceptScore >= 2) {
            return new Evaluation(Decision.ACCEPTED, other.name() + " accepts peace terms", null);
        }
        return new Evaluation(Decision.REJECTED,
                other.name() + " rejects peace — still willing to fight", null);
    }

    private static Evaluation evaluateTreaty(
            TreatyType type,
            boolean atWar,
            double score,
            double playerRep,
            double otherTreasury,
            DiplomaticRelation relation
    ) {
        if (atWar) {
            return new Evaluation(Decision.REJECTED, "Cannot treat while at war — seek peace first", type);
        }
        if (relation == DiplomaticRelation.HOSTILE && score < -25) {
            return new Evaluation(Decision.REJECTED, "Relations too hostile for a treaty", type);
        }
        int accept = 0;
        if (score >= 0) accept += 2;
        if (score >= 15) accept += 1;
        if (playerRep >= 0.1) accept += 1;
        if (playerRep <= -0.25) accept -= 2;
        if (otherTreasury < 5 && type == TreatyType.TRADE) accept -= 1;
        if (type == TreatyType.NON_AGGRESSION && score >= -10) accept += 1;
        if (accept >= 2) {
            return new Evaluation(Decision.ACCEPTED, "Treaty accepted", type);
        }
        return new Evaluation(Decision.REJECTED, "Treaty rejected", type);
    }

    private static Evaluation evaluateAlliance(
            boolean atWar,
            double score,
            double playerRep,
            double militaryBalance,
            DiplomaticRelation relation,
            KingdomState other
    ) {
        if (atWar) {
            return new Evaluation(Decision.REJECTED, "Cannot ally while at war", TreatyType.ALLIANCE);
        }
        if (relation == DiplomaticRelation.HOSTILE) {
            return new Evaluation(Decision.REJECTED, other.name() + " will not ally a hostile realm",
                    TreatyType.ALLIANCE);
        }
        // Alliances require strong positive score and reputation.
        if (score >= 20 && playerRep >= 0.15 && militaryBalance > -0.5) {
            return new Evaluation(Decision.ACCEPTED, other.name() + " accepts an alliance",
                    TreatyType.ALLIANCE);
        }
        return new Evaluation(Decision.REJECTED,
                other.name() + " declines alliance — trust insufficient", TreatyType.ALLIANCE);
    }

    private static WarState activeWar(CanonicalWorldState state, KingdomId a, KingdomId b) {
        for (WarState war : state.wars().values()) {
            if (!war.active()) continue;
            if ((war.aggressor().equals(a) && war.defender().equals(b))
                    || (war.aggressor().equals(b) && war.defender().equals(a))) {
                return war;
            }
        }
        return null;
    }

    private static boolean hasActiveTreaty(CanonicalWorldState state, KingdomId a, KingdomId b) {
        for (var t : state.diplomacy().treaties().values()) {
            if (!t.active()) continue;
            if ((t.a().equals(a) && t.b().equals(b)) || (t.a().equals(b) && t.b().equals(a))) {
                return true;
            }
        }
        return false;
    }

    /** Positive = proposer stronger than target. Deterministic from army sizes if present. */
    private static double militaryBalance(CanonicalWorldState state, KingdomId proposer, KingdomId target) {
        double a = armyStrength(state, proposer);
        double b = armyStrength(state, target);
        double sum = a + b;
        if (sum < 1e-6) return 0;
        return (a - b) / sum;
    }

    private static double armyStrength(CanonicalWorldState state, KingdomId kingdom) {
        double total = 0;
        for (var army : state.armies().values()) {
            if (kingdom.equals(army.owner())) {
                total += Math.max(1, army.strength());
            }
        }
        KingdomState k = state.kingdoms().get(kingdom);
        if (k != null) total += k.treasury() * 0.01;
        return total;
    }

    private static double warExhaustion(CanonicalWorldState state, KingdomId a, KingdomId b) {
        WarState war = activeWar(state, a, b);
        if (war == null) return 0;
        // Prefer explicit fields when present; fall back to duration proxy.
        try {
            // WarState may expose losses / dayStarted depending on schema.
            long duration = Math.max(0, state.time().dayIndex() - war.startedDay());
            return Math.min(1.0, duration / 120.0);
        } catch (Exception e) {
            return 0.3;
        }
    }
}
