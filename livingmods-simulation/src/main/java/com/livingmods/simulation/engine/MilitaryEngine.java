package com.livingmods.simulation.engine;

import com.livingmods.common.event.CivilizationEventType;
import com.livingmods.common.event.HistoricalEvent;
import com.livingmods.common.geo.BlockPos2;
import com.livingmods.common.id.ArmyId;
import com.livingmods.common.id.HistoricalEventId;
import com.livingmods.common.id.KingdomId;
import com.livingmods.common.id.SettlementId;
import com.livingmods.common.id.SiegeId;
import com.livingmods.common.id.WarId;
import com.livingmods.common.model.ArmyStatus;
import com.livingmods.common.model.CasusBelli;
import com.livingmods.common.model.DiplomaticRelation;
import com.livingmods.common.model.WarObjective;
import com.livingmods.simulation.CanonicalWorldState;
import com.livingmods.simulation.state.ArmyState;
import com.livingmods.simulation.state.DiplomacyState;
import com.livingmods.simulation.state.KingdomState;
import com.livingmods.simulation.state.SettlementState;
import com.livingmods.simulation.state.SiegeState;
import com.livingmods.simulation.state.WarState;
import com.livingmods.simulation.tick.RegionalWork;
import com.livingmods.simulation.tick.SimulationContext;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Armies, casus belli, war objectives, abstract/physical combat split, and sieges.
 * Never double-resolves combat for the same army in one tick.
 */
public final class MilitaryEngine implements SimulationSubsystem {
    @Override
    public String name() { return "military"; }

    @Override
    public void phase1Regional(CanonicalWorldState state, RegionalWork work, SimulationContext ctx) {
        if (!ctx.schedule().runGovernment()) return;

        for (ArmyState army : state.armies().values()) {
            army.setResolvedCombatThisTick(false);
            SettlementState near = nearestSettlement(state, army.position());
            if (near == null || !near.region().equals(work.region())) continue;

            work.enqueueCommit(() -> {
                army.setSupply(clamp(army.supply() - 0.02, 0, 1));
                advanceRoute(army);
                tickSiegeAttachment(state, army);
            });
        }

        for (SiegeState siege : state.sieges().values()) {
            SettlementState target = state.settlements().get(siege.target());
            if (target == null || !target.region().equals(work.region()) || !siege.active()) continue;
            work.enqueueCommit(() -> progressSiege(state, siege));
        }
    }

    @Override
    public void phase2Global(CanonicalWorldState state, SimulationContext ctx) {
        if (!ctx.schedule().runDiplomacyWeekly()) return;

        // Security response to bandit / market risk: reinforce guards via local army morale
        for (SettlementState s : state.settlements().values()) {
            var market = state.markets().get(s.id());
            if (market == null || market.securityResponse() < 0.2) continue;
            for (ArmyState army : state.armies().values()) {
                if (s.ownerKingdom().isPresent() && army.owner().equals(s.ownerKingdom().get())
                        && army.position().distanceTo(s.center()) < 200) {
                    army.setMorale(Math.min(100, army.morale() + 2));
                    army.setStatus(ArmyStatus.DEFENDING);
                }
            }
            market.setSecurityResponse(Math.max(0, market.securityResponse() - 0.05));
        }

        considerWars(state, ctx);
        assignObjectives(state, ctx);
        resolveAbstractBattles(state, ctx);
        tickWarExhaustion(state);
    }

    private static void considerWars(CanonicalWorldState state, SimulationContext ctx) {
        List<KingdomId> ids = new ArrayList<>(state.kingdoms().keySet());
        ids.sort(KingdomId::compareTo);
        DiplomacyState dip = state.diplomacy();

        for (int i = 0; i < ids.size(); i++) {
            for (int j = i + 1; j < ids.size(); j++) {
                KingdomId a = ids.get(i);
                KingdomId b = ids.get(j);
                DiplomaticRelation rel = dip.relation(a, b);
                if (rel != DiplomaticRelation.HOSTILE && dip.score(a, b) > -55) continue;

                boolean already = state.wars().values().stream()
                        .anyMatch(w -> w.active() && w.participants().contains(a) && w.participants().contains(b));
                if (already) continue;

                KingdomState ka = state.kingdoms().get(a);
                KingdomState kb = state.kingdoms().get(b);
                if (ka == null || kb == null) continue;

                // Prefer adjacent pairs; still allow claim/raid driven distant wars at lower chance
                boolean adjacent = ka.adjacentKingdoms().contains(b);
                double intel = state.intelligence().quality(a, b);
                double chance = (adjacent ? 0.08 : 0.015) * (0.5 + intel);
                if (dip.score(a, b) < -70) chance *= 1.5;
                if (!ctx.random().chance(chance)) continue;

                CasusBelli casus = pickCasusBelli(state, ka, kb, dip);
                WarObjective objective = switch (casus) {
                    case DYNASTIC_CLAIM, CONQUEST -> WarObjective.CONQUER_TERRITORY;
                    case TRADE_RAID -> WarObjective.RAID;
                    case REBELLION_SUPPRESSION -> WarObjective.DEFEND;
                    case DEFENSE -> WarObjective.HOLD_BORDER;
                    default -> adjacent ? WarObjective.HOLD_BORDER : WarObjective.CAPTURE_SETTLEMENT;
                };

                WarId warId = WarId.deterministic(state.seed(), state.wars().size());
                WarState war = new WarState(warId, a, b, ctx.time().dayIndex());
                war.setCasusBelli(casus);
                war.setPrimaryObjective(objective);
                SettlementId objSettlement = kb.capitalId();
                if (objective == WarObjective.CAPTURE_SETTLEMENT || objective == WarObjective.CONQUER_TERRITORY) {
                    objSettlement = kb.capitalId();
                } else if (!ka.settlementIds().isEmpty()) {
                    objSettlement = borderSettlement(state, ka, kb);
                }
                war.setObjectiveSettlement(objSettlement);
                state.wars().put(warId, war);
                dip.setRelation(a, b, DiplomaticRelation.AT_WAR);
                dip.addHistoricFriction(a, b, 15);
                dip.setScore(a, b, -80);

                // Break trade treaties
                for (var entry : new ArrayList<>(dip.treaties().entrySet())) {
                    var t = entry.getValue();
                    if (!t.active()) continue;
                    if ((t.a().equals(a) && t.b().equals(b)) || (t.a().equals(b) && t.b().equals(a))) {
                        dip.treaties().put(entry.getKey(), t.withViolated(true).withActive(false));
                        state.appendHistory(new HistoricalEvent(
                                HistoricalEventId.deterministic(state.seed(), state.history().size()),
                                CivilizationEventType.TREATY_BROKEN,
                                ctx.time(),
                                "Treaty broken",
                                "War voids treaty " + t.type(),
                                Optional.empty(),
                                Map.of("treaty", t.id().toString())
                        ));
                    }
                }

                mobilizeForWar(state, war);
                state.appendHistory(new HistoricalEvent(
                        HistoricalEventId.deterministic(state.seed(), state.history().size()),
                        CivilizationEventType.WAR_STARTED,
                        ctx.time(),
                        "War declared",
                        ka.name() + " wars " + kb.name() + " (" + casus.name().toLowerCase() + ")",
                        Optional.empty(),
                        Map.of("war", warId.toString(), "casus", casus.name())
                ));
            }
        }
    }

    private static CasusBelli pickCasusBelli(
            CanonicalWorldState state, KingdomState a, KingdomState b, DiplomacyState dip
    ) {
        if (a.dynastyId() != null) {
            var dynasty = state.dynasties().get(a.dynastyId());
            if (dynasty != null && dynasty.kingdomClaims().contains(b.id())) {
                return CasusBelli.DYNASTIC_CLAIM;
            }
        }
        if (dip.pairFactors(a.id(), b.id()).raidPenalty > 5) return CasusBelli.TRADE_RAID;
        if (!a.religionKey().equals(b.religionKey())) return CasusBelli.RELIGIOUS_SCHISM;
        if (dip.historicFriction(a.id(), b.id()) > 30) return CasusBelli.CONQUEST;
        for (var t : dip.treaties().values()) {
            if (t.violated() && ((t.a().equals(a.id()) && t.b().equals(b.id()))
                    || (t.a().equals(b.id()) && t.b().equals(a.id())))) {
                return CasusBelli.BROKEN_TREATY;
            }
        }
        return a.adjacentKingdoms().contains(b.id()) ? CasusBelli.BORDER_DISPUTE : CasusBelli.CONQUEST;
    }

    private static SettlementId borderSettlement(CanonicalWorldState state, KingdomState a, KingdomState b) {
        SettlementState best = null;
        double bestD = Double.MAX_VALUE;
        for (SettlementId sid : a.settlementIds()) {
            SettlementState sa = state.settlements().get(sid);
            if (sa == null) continue;
            for (SettlementId oid : b.settlementIds()) {
                SettlementState sb = state.settlements().get(oid);
                if (sb == null) continue;
                double d = sa.center().distanceTo(sb.center());
                if (d < bestD) {
                    bestD = d;
                    best = sb;
                }
            }
        }
        return best != null ? best.id() : b.capitalId();
    }

    private static void mobilizeForWar(CanonicalWorldState state, WarState war) {
        for (ArmyState army : state.armies().values()) {
            if (!war.participants().contains(army.owner())) continue;
            army.setWarId(war.id());
            army.setObjective(war.primaryObjective());
            army.setObjectiveSettlement(war.objectiveSettlement());
            if (army.owner().equals(war.aggressor())
                    && (war.primaryObjective() == WarObjective.CAPTURE_SETTLEMENT
                    || war.primaryObjective() == WarObjective.CONQUER_TERRITORY)) {
                army.setStatus(ArmyStatus.MARCHING);
                SettlementState target = state.settlements().get(war.objectiveSettlement());
                if (target != null) {
                    army.route().clear();
                    army.route().add(army.position());
                    army.route().add(target.center());
                    army.setRouteIndex(0);
                    army.setSiegeTarget(target.id());
                }
            } else {
                army.setStatus(ArmyStatus.DEFENDING);
                army.setObjective(WarObjective.DEFEND);
            }
        }
    }

    private static void assignObjectives(CanonicalWorldState state, SimulationContext ctx) {
        for (WarState war : state.wars().values()) {
            if (!war.active()) continue;
            // Start sieges when attacker near objective
            SettlementId targetId = war.objectiveSettlement();
            if (targetId == null) continue;
            SettlementState target = state.settlements().get(targetId);
            if (target == null) continue;
            boolean already = state.sieges().values().stream()
                    .anyMatch(s -> s.active() && s.target().equals(targetId));
            if (already) continue;

            List<ArmyId> attackers = new ArrayList<>();
            List<ArmyId> defenders = new ArrayList<>();
            for (ArmyState army : state.armies().values()) {
                if (!war.participants().contains(army.owner())) continue;
                if (army.position().distanceTo(target.center()) > 120) continue;
                if (army.owner().equals(war.aggressor())) attackers.add(army.id());
                else defenders.add(army.id());
            }
            if (attackers.isEmpty()) continue;
            if (war.primaryObjective() != WarObjective.CAPTURE_SETTLEMENT
                    && war.primaryObjective() != WarObjective.CONQUER_TERRITORY
                    && war.primaryObjective() != WarObjective.RELIEVE_SIEGE) {
                continue;
            }
            SiegeId sid = SiegeId.deterministic(state.seed(), state.sieges().size());
            SiegeState siege = new SiegeState(sid, targetId, war.id());
            siege.attackers().addAll(attackers);
            siege.defenders().addAll(defenders);
            siege.setBlockade(true);
            state.sieges().put(sid, siege);
            for (ArmyId aid : attackers) {
                ArmyState army = state.armies().get(aid);
                if (army != null) {
                    army.setStatus(ArmyStatus.BESIEGING);
                    army.setSiegeTarget(targetId);
                    army.setObjective(WarObjective.CAPTURE_SETTLEMENT);
                }
            }
        }
    }

    private static void resolveAbstractBattles(CanonicalWorldState state, SimulationContext ctx) {
        // Abstract combat for armies far from any settlement center (player region approximated by settlements)
        List<ArmyState> armies = new ArrayList<>(state.armies().values());
        armies.sort(Comparator.comparing(a -> a.id().value()));
        for (int i = 0; i < armies.size(); i++) {
            ArmyState a = armies.get(i);
            if (a.resolvedCombatThisTick() || a.manpower() <= 0) continue;
            SettlementState near = nearestSettlement(state, a.position());
            boolean physical = near != null && a.position().distanceTo(near.center()) < 96;
            if (physical) continue; // physical resolution deferred to Minecraft projection

            for (int j = i + 1; j < armies.size(); j++) {
                ArmyState b = armies.get(j);
                if (b.resolvedCombatThisTick() || b.manpower() <= 0) continue;
                if (a.owner().equals(b.owner())) continue;
                if (a.position().distanceTo(b.position()) > 48) continue;
                DiplomaticRelation rel = state.diplomacy().relation(a.owner(), b.owner());
                if (rel != DiplomaticRelation.AT_WAR && rel != DiplomaticRelation.HOSTILE) continue;

                SettlementState nearB = nearestSettlement(state, b.position());
                boolean physicalB = nearB != null && b.position().distanceTo(nearB.center()) < 96;
                if (physicalB) continue;

                // Single abstract resolution
                double powerA = a.manpower() * (0.5 + a.morale() / 200.0) * (0.5 + a.equipment() / 200.0) * a.supply();
                double powerB = b.manpower() * (0.5 + b.morale() / 200.0) * (0.5 + b.equipment() / 200.0) * b.supply();
                double lossA = Math.min(a.manpower() * 0.25, powerB * 0.15);
                double lossB = Math.min(b.manpower() * 0.25, powerA * 0.15);
                a.setManpower((int) Math.max(0, a.manpower() - lossA));
                b.setManpower((int) Math.max(0, b.manpower() - lossB));
                a.setMorale((int) clamp(a.morale() - 5, 0, 100));
                b.setMorale((int) clamp(b.morale() - 5, 0, 100));
                a.setStatus(ArmyStatus.ENGAGED);
                b.setStatus(ArmyStatus.ENGAGED);
                a.setResolvedCombatThisTick(true);
                b.setResolvedCombatThisTick(true);
                break;
            }
        }
    }

    private static void tickWarExhaustion(CanonicalWorldState state) {
        for (WarState war : state.wars().values()) {
            if (!war.active()) continue;
            war.setWarExhaustionAggressor(war.warExhaustionAggressor() + 0.01);
            war.setWarExhaustionDefender(war.warExhaustionDefender() + 0.012);
            KingdomState ka = state.kingdoms().get(war.aggressor());
            KingdomState kb = state.kingdoms().get(war.defender());
            if (ka != null) ka.setWarExhaustion(clamp(ka.warExhaustion() + 0.008, 0, 1));
            if (kb != null) kb.setWarExhaustion(clamp(kb.warExhaustion() + 0.01, 0, 1));

            if (war.warExhaustionAggressor() > 0.9 || war.warExhaustionDefender() > 0.9) {
                war.setActive(false);
                state.diplomacy().setRelation(war.aggressor(), war.defender(), DiplomaticRelation.TENSE);
            }
        }
    }

    private static void advanceRoute(ArmyState army) {
        if (army.route().isEmpty() || army.status() == ArmyStatus.BESIEGING) return;
        if (army.routeIndex() + 1 < army.route().size()) {
            army.setRouteIndex(army.routeIndex() + 1);
            army.setPosition(army.route().get(army.routeIndex()));
            army.setStatus(ArmyStatus.MARCHING);
        }
    }

    private static void tickSiegeAttachment(CanonicalWorldState state, ArmyState army) {
        if (army.siegeTarget() == null) return;
        army.setSiegeProgress(army.siegeProgress() + 1);
        army.setStatus(ArmyStatus.BESIEGING);
    }

    private static void progressSiege(CanonicalWorldState state, SiegeState siege) {
        double atk = 0;
        double def = 0;
        for (ArmyId id : siege.attackers()) {
            ArmyState a = state.armies().get(id);
            if (a != null) atk += a.manpower() * a.supply();
        }
        for (ArmyId id : siege.defenders()) {
            ArmyState a = state.armies().get(id);
            if (a != null) def += a.manpower() * a.supply();
        }
        SettlementState target = state.settlements().get(siege.target());
        if (target != null) def += target.physicalCapacity() * 0.5;

        siege.setAttackerSupplies(clamp(siege.attackerSupplies() - 0.02, 0, 1));
        siege.setDefenderSupplies(clamp(siege.defenderSupplies() - (siege.blockade() ? 0.04 : 0.01), 0, 1));
        double delta = (atk - def * 0.8) * 0.02 + (siege.blockade() ? 0.3 : 0);
        siege.setProgress(siege.progress() + Math.max(0.1, delta));
        if (siege.progress() > 40 && siege.breaches() == 0) {
            siege.setBreaches(1);
        }
        if (siege.defenderSupplies() < 0.1 || siege.progress() >= 100) {
            siege.setSurrendered(true);
            siege.setActive(false);
            for (ArmyId id : siege.attackers()) {
                ArmyState a = state.armies().get(id);
                if (a != null) {
                    a.setSiegeTarget(null);
                    a.setSiegeProgress(0);
                    a.setStatus(ArmyStatus.IDLE);
                }
            }
        }
    }

    private static SettlementState nearestSettlement(CanonicalWorldState state, BlockPos2 pos) {
        SettlementState best = null;
        double bestD = Double.MAX_VALUE;
        for (SettlementState s : state.settlements().values()) {
            double d = s.center().distanceTo(pos);
            if (d < bestD) {
                bestD = d;
                best = s;
            }
        }
        return best;
    }

    private static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }
}
