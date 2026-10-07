package com.livingmods.simulation.state;

import com.livingmods.common.id.KingdomId;
import com.livingmods.common.id.TreatyId;
import com.livingmods.common.model.DiplomaticRelation;
import com.livingmods.common.model.TreatyType;

import java.util.HashMap;
import java.util.Map;

public final class DiplomacyState {
    private final Map<DiplomacyPair, DiplomaticRelation> relations = new HashMap<>();
    private final Map<DiplomacyPair, Double> scores = new HashMap<>();
    private final Map<DiplomacyPair, PairFactors> factors = new HashMap<>();
    private final Map<TreatyId, TreatyRecord> treaties = new HashMap<>();
    private final Map<DiplomacyPair, Double> historicFriction = new HashMap<>();

    public DiplomaticRelation relation(KingdomId a, KingdomId b) {
        if (a.equals(b)) return DiplomaticRelation.ALLIED;
        return relations.getOrDefault(new DiplomacyPair(a, b), DiplomaticRelation.NEUTRAL);
    }

    public void setRelation(KingdomId a, KingdomId b, DiplomaticRelation relation) {
        relations.put(new DiplomacyPair(a, b), relation);
    }

    public double score(KingdomId a, KingdomId b) {
        if (a.equals(b)) return 100.0;
        return scores.getOrDefault(new DiplomacyPair(a, b), 0.0);
    }

    public void setScore(KingdomId a, KingdomId b, double score) {
        scores.put(new DiplomacyPair(a, b), Math.max(-100, Math.min(100, score)));
    }

    public PairFactors pairFactors(KingdomId a, KingdomId b) {
        return factors.computeIfAbsent(new DiplomacyPair(a, b), p -> new PairFactors());
    }

    public double historicFriction(KingdomId a, KingdomId b) {
        return historicFriction.getOrDefault(new DiplomacyPair(a, b), 0.0);
    }

    public void addHistoricFriction(KingdomId a, KingdomId b, double delta) {
        DiplomacyPair pair = new DiplomacyPair(a, b);
        historicFriction.put(pair, Math.max(0, Math.min(100, historicFriction(a, b) + delta)));
    }

    public Map<DiplomacyPair, DiplomaticRelation> relations() { return relations; }
    public Map<DiplomacyPair, Double> scores() { return scores; }
    public Map<DiplomacyPair, PairFactors> factors() { return factors; }
    public Map<DiplomacyPair, Double> historicFriction() { return historicFriction; }
    public Map<TreatyId, TreatyRecord> treaties() { return treaties; }

    public static DiplomaticRelation categoricalFromScore(double score) {
        if (score >= 60) return DiplomaticRelation.ALLIED;
        if (score >= 25) return DiplomaticRelation.FRIENDLY;
        if (score >= -10) return DiplomaticRelation.NEUTRAL;
        if (score >= -35) return DiplomaticRelation.TENSE;
        if (score >= -70) return DiplomaticRelation.HOSTILE;
        return DiplomaticRelation.AT_WAR;
    }

    public static final class PairFactors {
        public double tradeBenefit;
        public double borderFriction;
        public double sharedEnemy;
        public double cultureAffinity;
        public double religionAffinity;
        public double raidPenalty;
        public double treatyBonus;
        public double marriageBonus;
        public double historicalWar;
    }

    public record TreatyRecord(
            TreatyId id,
            KingdomId a,
            KingdomId b,
            TreatyType type,
            long signedDay,
            long expirationDay,
            boolean active,
            String effectsKey,
            boolean violated
    ) {
        public TreatyRecord(TreatyId id, KingdomId a, KingdomId b, TreatyType type, long signedDay) {
            this(id, a, b, type, signedDay, signedDay + 365, true, type.name().toLowerCase(), false);
        }

        public TreatyRecord withActive(boolean active) {
            return new TreatyRecord(id, a, b, type, signedDay, expirationDay, active, effectsKey, violated);
        }

        public TreatyRecord withViolated(boolean violated) {
            return new TreatyRecord(id, a, b, type, signedDay, expirationDay, active, effectsKey, violated);
        }
    }
}
