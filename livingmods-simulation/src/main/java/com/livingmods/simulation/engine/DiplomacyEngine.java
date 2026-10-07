package com.livingmods.simulation.engine;

import com.livingmods.common.event.CivilizationEventType;
import com.livingmods.common.event.HistoricalEvent;
import com.livingmods.common.id.HistoricalEventId;
import com.livingmods.common.id.KingdomId;
import com.livingmods.common.id.TreatyId;
import com.livingmods.common.model.DiplomaticRelation;
import com.livingmods.common.model.TreatyType;
import com.livingmods.simulation.CanonicalWorldState;
import com.livingmods.simulation.state.DiplomacyState;
import com.livingmods.simulation.state.KingdomState;
import com.livingmods.simulation.state.ShipmentState;
import com.livingmods.simulation.state.WarState;
import com.livingmods.simulation.tick.RegionalWork;
import com.livingmods.simulation.tick.SimulationContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Pairwise diplomacy over the full kingdom graph (adjacency, trade, culture, religion,
 * marriages, treaties, wars) — not limited to kingdom indices 0 and 1.
 */
public final class DiplomacyEngine implements SimulationSubsystem {
    @Override
    public String name() { return "diplomacy"; }

    @Override
    public void phase1Regional(CanonicalWorldState state, RegionalWork work, SimulationContext ctx) {}

    @Override
    public void phase2Global(CanonicalWorldState state, SimulationContext ctx) {
        if (!ctx.schedule().runDiplomacyDaily()) return;

        List<KingdomId> ids = new ArrayList<>(state.kingdoms().keySet());
        ids.sort(KingdomId::compareTo);
        DiplomacyState dip = state.diplomacy();

        for (int i = 0; i < ids.size(); i++) {
            for (int j = i + 1; j < ids.size(); j++) {
                KingdomId a = ids.get(i);
                KingdomId b = ids.get(j);
                updatePair(state, dip, a, b, ctx);
            }
        }

        expireTreaties(state, dip, ctx);

        if (ctx.schedule().runDiplomacyWeekly()) {
            considerTreaties(state, dip, ids, ctx);
            evolveEspionage(state, ids, ctx);
        }
    }

    private static void updatePair(
            CanonicalWorldState state,
            DiplomacyState dip,
            KingdomId a,
            KingdomId b,
            SimulationContext ctx
    ) {
        KingdomState ka = state.kingdoms().get(a);
        KingdomState kb = state.kingdoms().get(b);
        if (ka == null || kb == null) return;

        DiplomacyState.PairFactors f = dip.pairFactors(a, b);
        f.borderFriction = ka.adjacentKingdoms().contains(b) ? 8.0 : 1.0;
        f.cultureAffinity = ka.cultureId().equals(kb.cultureId()) ? 12.0 : -4.0;
        f.religionAffinity = ka.religionKey().equals(kb.religionKey()) ? 10.0 : -6.0;
        f.tradeBenefit = tradeBenefit(state, a, b);
        f.sharedEnemy = sharedEnemyBonus(state, a, b);
        f.historicalWar = dip.historicFriction(a, b);
        f.treatyBonus = treatyBonus(dip, a, b);
        // marriageBonus / raidPenalty accumulated by other systems

        double score = f.tradeBenefit
                - f.borderFriction
                + f.sharedEnemy
                + f.cultureAffinity
                + f.religionAffinity
                - f.raidPenalty
                + f.treatyBonus
                + f.marriageBonus
                - f.historicalWar
                + (ka.publicOpinion() + kb.publicOpinion()) * 0.02;

        // At-war pairs stay categorical until peace
        DiplomaticRelation current = dip.relation(a, b);
        if (current == DiplomaticRelation.AT_WAR) {
            dip.setScore(a, b, Math.min(score, -75));
            return;
        }
        if (current == DiplomaticRelation.VASSAL || current == DiplomaticRelation.OVERLORD) {
            dip.setScore(a, b, score);
            return;
        }

        DiplomaticRelation next = DiplomacyState.categoricalFromScore(score);
        if (next == DiplomaticRelation.AT_WAR) {
            next = DiplomaticRelation.HOSTILE; // wars declared by MilitaryEngine with casus belli
        }
        dip.setScore(a, b, score);
        dip.setRelation(a, b, next);

        // Soft thaw
        if (next == DiplomaticRelation.HOSTILE && ctx.random().chance(0.01)) {
            dip.setRelation(a, b, DiplomaticRelation.TENSE);
        }
    }

    private static double tradeBenefit(CanonicalWorldState state, KingdomId a, KingdomId b) {
        double benefit = 0;
        for (ShipmentState sh : state.shipments().values()) {
            if (sh.looted()) continue;
            var src = state.settlements().get(sh.source());
            var dst = state.settlements().get(sh.destination());
            if (src == null || dst == null) continue;
            if (src.ownerKingdom().isEmpty() || dst.ownerKingdom().isEmpty()) continue;
            KingdomId so = src.ownerKingdom().get();
            KingdomId de = dst.ownerKingdom().get();
            if ((so.equals(a) && de.equals(b)) || (so.equals(b) && de.equals(a))) {
                benefit += sh.delivered() ? 4.0 : 1.5;
            }
        }
        return Math.min(25, benefit);
    }

    private static double sharedEnemyBonus(CanonicalWorldState state, KingdomId a, KingdomId b) {
        for (WarState w : state.wars().values()) {
            if (!w.active()) continue;
            boolean aIn = w.participants().contains(a);
            boolean bIn = w.participants().contains(b);
            if (aIn && bIn) {
                boolean sameSide = (w.aggressor().equals(a) || w.aggressor().equals(b))
                        && (w.defender().equals(a) || w.defender().equals(b));
                if (!sameSide) return -20; // fighting each other
                return 15; // co-belligerents (simplified)
            }
            if (aIn ^ bIn) {
                KingdomId enemy = w.aggressor().equals(a) || w.aggressor().equals(b)
                        ? w.defender() : w.aggressor();
                if (state.diplomacy().relation(aIn ? b : a, enemy) == DiplomaticRelation.HOSTILE
                        || state.diplomacy().relation(aIn ? b : a, enemy) == DiplomaticRelation.AT_WAR) {
                    return 10;
                }
            }
        }
        return 0;
    }

    private static double treatyBonus(DiplomacyState dip, KingdomId a, KingdomId b) {
        double bonus = 0;
        for (DiplomacyState.TreatyRecord t : dip.treaties().values()) {
            if (!t.active() || t.violated()) continue;
            if (!(t.a().equals(a) && t.b().equals(b) || t.a().equals(b) && t.b().equals(a))) continue;
            bonus += switch (t.type()) {
                case ALLIANCE -> 25;
                case TRADE -> 12;
                case NON_AGGRESSION, PEACE -> 8;
                case TRIBUTE -> 5;
                case MILITARY_ACCESS -> 6;
            };
        }
        return bonus;
    }

    private static void expireTreaties(CanonicalWorldState state, DiplomacyState dip, SimulationContext ctx) {
        long day = ctx.time().dayIndex();
        List<TreatyId> ids = new ArrayList<>(dip.treaties().keySet());
        for (TreatyId id : ids) {
            DiplomacyState.TreatyRecord t = dip.treaties().get(id);
            if (t == null || !t.active()) continue;
            if (day > t.expirationDay()) {
                dip.treaties().put(id, t.withActive(false));
            }
        }
    }

    private static void considerTreaties(
            CanonicalWorldState state,
            DiplomacyState dip,
            List<KingdomId> ids,
            SimulationContext ctx
    ) {
        for (int i = 0; i < ids.size(); i++) {
            for (int j = i + 1; j < ids.size(); j++) {
                KingdomId a = ids.get(i);
                KingdomId b = ids.get(j);
                KingdomState ka = state.kingdoms().get(a);
                if (ka == null) continue;
                // Prefer adjacent or trading pairs — still every pair can evolve
                boolean adjacent = ka.adjacentKingdoms().contains(b);
                double score = dip.score(a, b);
                DiplomaticRelation rel = dip.relation(a, b);
                if (rel == DiplomaticRelation.AT_WAR || rel == DiplomaticRelation.HOSTILE) continue;
                if (hasActiveTreaty(dip, a, b, TreatyType.TRADE)) continue;
                double chance = adjacent ? 0.12 : 0.03;
                if (score > 5 && ctx.random().chance(chance)) {
                    TreatyId tid = TreatyId.deterministic(state.seed(), dip.treaties().size());
                    long day = ctx.time().dayIndex();
                    TreatyType type = score > 40 ? TreatyType.ALLIANCE : TreatyType.TRADE;
                    dip.treaties().put(tid, new DiplomacyState.TreatyRecord(
                            tid, a, b, type, day, day + 400, true, type.name().toLowerCase(), false));
                    dip.setRelation(a, b, type == TreatyType.ALLIANCE
                            ? DiplomaticRelation.ALLIED : DiplomaticRelation.FRIENDLY);
                    state.appendHistory(new HistoricalEvent(
                            HistoricalEventId.deterministic(state.seed(), state.history().size()),
                            CivilizationEventType.TREATY_SIGNED,
                            ctx.time(),
                            type.name() + " treaty",
                            ka.name() + " signs " + type.name().toLowerCase() + " with neighbor.",
                            Optional.empty(),
                            Map.of("treaty", tid.toString(), "a", a.toString(), "b", b.toString())
                    ));
                }
            }
        }
    }

    private static boolean hasActiveTreaty(DiplomacyState dip, KingdomId a, KingdomId b, TreatyType type) {
        for (DiplomacyState.TreatyRecord t : dip.treaties().values()) {
            if (!t.active()) continue;
            if (t.type() != type) continue;
            if ((t.a().equals(a) && t.b().equals(b)) || (t.a().equals(b) && t.b().equals(a))) return true;
        }
        return false;
    }

    private static void evolveEspionage(CanonicalWorldState state, List<KingdomId> ids, SimulationContext ctx) {
        for (KingdomId observer : ids) {
            for (KingdomId subject : ids) {
                if (observer.equals(subject)) continue;
                double current = state.intelligence().quality(observer, subject);
                boolean adjacent = false;
                KingdomState ko = state.kingdoms().get(observer);
                if (ko != null) adjacent = ko.adjacentKingdoms().contains(subject);
                double delta = (adjacent ? 0.04 : 0.01) + (ctx.random().nextDouble() - 0.45) * 0.02;
                // Cap: never perfect global knowledge
                state.intelligence().setQuality(observer, subject, Math.min(0.92, current + delta));
            }
        }
    }
}
