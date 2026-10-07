package com.livingmods.simulation.engine;

import com.livingmods.common.event.CivilizationEventType;
import com.livingmods.common.event.HistoricalEvent;
import com.livingmods.common.id.ArmyId;
import com.livingmods.common.id.CitizenId;
import com.livingmods.common.id.DynastyId;
import com.livingmods.common.id.FactionId;
import com.livingmods.common.id.HistoricalEventId;
import com.livingmods.common.id.KingdomId;
import com.livingmods.common.id.SettlementId;
import com.livingmods.common.model.ArmyStatus;
import com.livingmods.common.model.GovernmentType;
import com.livingmods.common.model.Profession;
import com.livingmods.common.model.WarObjective;
import com.livingmods.simulation.CanonicalWorldState;
import com.livingmods.simulation.state.ArmyState;
import com.livingmods.simulation.state.CitizenState;
import com.livingmods.simulation.state.DynastyState;
import com.livingmods.simulation.state.FactionState;
import com.livingmods.simulation.state.KingdomState;
import com.livingmods.simulation.state.MarketState;
import com.livingmods.simulation.state.SettlementState;
import com.livingmods.simulation.tick.RegionalWork;
import com.livingmods.simulation.tick.SimulationContext;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Government-type succession, dynasties, political marriage effects, propaganda, and rebellion.
 */
public final class GovernmentEngine implements SimulationSubsystem {
    @Override
    public String name() { return "government"; }

    @Override
    public void phase1Regional(CanonicalWorldState state, RegionalWork work, SimulationContext ctx) {}

    @Override
    public void phase2Global(CanonicalWorldState state, SimulationContext ctx) {
        if (!ctx.schedule().runGovernment()) return;

        for (KingdomState kingdom : state.kingdoms().values()) {
            double taxIncome = 0.0;
            double hungerSum = 0.0;
            int settlementCount = 0;
            for (SettlementId sid : kingdom.settlementIds()) {
                SettlementState s = state.settlements().get(sid);
                if (s == null) continue;
                settlementCount++;
                int pop = countAlive(state, sid);
                taxIncome += pop * kingdom.taxRate() * 0.1;
                hungerSum += s.hunger();
            }
            kingdom.setTreasury(kingdom.treasury() + taxIncome);

            // Propaganda slowly lifts opinion / legitimacy, bounded
            double propaganda = kingdom.propaganda();
            kingdom.setLegitimacy(clamp(kingdom.legitimacy() + 0.01 + propaganda * 0.05, 0, 100));
            kingdom.setPublicOpinion(clamp(kingdom.publicOpinion() + propaganda * 0.4 - kingdom.taxRate() * 8, 0, 100));

            double avgHunger = settlementCount == 0 ? 0 : hungerSum / settlementCount;
            double unrest = clamp(
                    avgHunger * 0.35
                            + kingdom.taxRate() * 0.9
                            + (1.0 - kingdom.legitimacy() / 100.0) * 0.35
                            + kingdom.warExhaustion() * 0.25
                            + cultureReligionFriction(state, kingdom) * 0.15
                            - propaganda * 0.2
                            - kingdom.publicOpinion() / 400.0,
                    0, 1);
            kingdom.setUnrest(unrest);

            for (SettlementId sid : kingdom.settlementIds()) {
                SettlementState s = state.settlements().get(sid);
                if (s != null) {
                    s.setUnrest(clamp(s.unrest() * 0.7 + unrest * 0.3, 0, 1));
                }
            }

            maybePoliticalMarriage(state, kingdom, ctx);
            maybePropaganda(state, kingdom, ctx);
            maybeRebellion(state, kingdom, ctx, unrest);

            CitizenState ruler = state.citizens().get(kingdom.rulerId());
            if (ruler == null || !ruler.alive()) {
                resolveSuccession(state, kingdom, ctx);
            } else {
                markHeir(state, kingdom, ruler);
            }
        }
    }

    public static void resolveSuccession(CanonicalWorldState state, KingdomState kingdom, SimulationContext ctx) {
        CitizenState successor = switch (kingdom.governmentType()) {
            case MONARCHY, PLAYER_REALM -> dynasticSuccessor(state, kingdom);
            case THEOCRACY -> religiousSuccessor(state, kingdom);
            case OLIGARCHY, TRIBAL_COUNCIL, REPUBLIC -> eliteSuccessor(state, kingdom);
            case MILITARY_DICTATORSHIP -> militarySuccessor(state, kingdom);
        };
        if (successor == null) {
            successor = anyAliveInKingdom(state, kingdom);
        }
        if (successor == null) return;

        for (SettlementId sid : kingdom.settlementIds()) {
            for (CitizenId cid : state.citizensInSettlement(sid)) {
                CitizenState c = state.citizens().get(cid);
                if (c == null) continue;
                if (c.ruler()) c.setRuler(false);
                if (c.heir()) c.setHeir(false);
            }
        }
        successor.setRuler(true);
        successor.setProfession(Profession.RULER);
        successor.setNoble(true);
        kingdom.setRulerId(successor.id());

        DynastyState dynasty = kingdom.dynastyId() != null ? state.dynasties().get(kingdom.dynastyId()) : null;
        if (dynasty != null) {
            dynasty.setCurrentHeadId(successor.id());
            dynasty.members().add(successor.id());
            successor.setDynastyId(dynasty.id());
            if (kingdom.governmentType() == GovernmentType.MONARCHY) {
                kingdom.setLegitimacy(clamp(kingdom.legitimacy() + dynasty.prestige() * 0.1, 0, 100));
            }
        } else if (kingdom.governmentType() == GovernmentType.MONARCHY) {
            kingdom.setLegitimacy(clamp(kingdom.legitimacy() - 8, 0, 100));
        }

        SettlementState capital = state.settlements().get(kingdom.capitalId());
        if (capital != null) {
            capital.setRulerId(successor.id());
        }

        state.appendHistory(new HistoricalEvent(
                HistoricalEventId.deterministic(state.seed(), state.history().size()),
                CivilizationEventType.SUCCESSION_RESOLVED,
                ctx.time(),
                "Succession in " + kingdom.name(),
                successor.givenName() + " " + successor.familyName() + " ascends ("
                        + kingdom.governmentType().name().toLowerCase() + ").",
                capital != null ? Optional.of(capital.center()) : Optional.empty(),
                Map.of("kingdom", kingdom.id().toString(), "government", kingdom.governmentType().name())
        ));
    }

    private static CitizenState dynasticSuccessor(CanonicalWorldState state, KingdomState kingdom) {
        DynastyState dynasty = kingdom.dynastyId() != null ? state.dynasties().get(kingdom.dynastyId()) : null;
        CitizenState previous = state.citizens().get(kingdom.rulerId());
        List<CitizenState> children = new ArrayList<>();
        if (previous != null) {
            for (CitizenState c : state.citizens().values()) {
                if (!c.alive()) continue;
                if (previous.id().equals(c.motherId()) || previous.id().equals(c.fatherId())) {
                    children.add(c);
                }
            }
        }
        children.sort(Comparator
                .comparing((CitizenState c) -> !c.heir())
                .thenComparingInt(c -> c.female() ? 1 : 0)
                .thenComparing(c -> c.birthDate().absoluteTicks())
                .thenComparing(c -> c.id().value()));
        if (!children.isEmpty()) return children.getFirst();

        if (dynasty != null) {
            List<CitizenState> members = new ArrayList<>();
            for (CitizenId id : dynasty.members()) {
                CitizenState c = state.citizens().get(id);
                if (c != null && c.alive()) members.add(c);
            }
            members.sort(Comparator.comparing((CitizenState c) -> !c.noble())
                    .thenComparing(c -> c.id().value()));
            if (!members.isEmpty()) return members.getFirst();
        }
        return eliteSuccessor(state, kingdom);
    }

    private static CitizenState religiousSuccessor(CanonicalWorldState state, KingdomState kingdom) {
        List<CitizenState> priests = candidates(state, kingdom, Profession.PRIEST, Profession.SCHOLAR);
        priests.sort(Comparator.comparing((CitizenState c) -> -c.wealth()).thenComparing(c -> c.id().value()));
        return priests.isEmpty() ? null : priests.getFirst();
    }

    private static CitizenState eliteSuccessor(CanonicalWorldState state, KingdomState kingdom) {
        List<CitizenState> elites = candidates(state, kingdom, Profession.NOBLE, Profession.GOVERNMENT_OFFICIAL, Profession.MERCHANT);
        elites.sort(Comparator
                .comparingInt((CitizenState c) -> c.profession() == Profession.NOBLE ? 0 : 1)
                .thenComparing((CitizenState c) -> -c.wealth())
                .thenComparing(c -> c.id().value()));
        return elites.isEmpty() ? null : elites.getFirst();
    }

    private static CitizenState militarySuccessor(CanonicalWorldState state, KingdomState kingdom) {
        List<CitizenState> soldiers = candidates(state, kingdom, Profession.SOLDIER, Profession.GUARD, Profession.NOBLE);
        soldiers.sort(Comparator.comparing((CitizenState c) -> -c.wealth()).thenComparing(c -> c.id().value()));
        return soldiers.isEmpty() ? null : soldiers.getFirst();
    }

    private static List<CitizenState> candidates(CanonicalWorldState state, KingdomState kingdom, Profession... professions) {
        List<CitizenState> out = new ArrayList<>();
        for (SettlementId sid : kingdom.settlementIds()) {
            for (CitizenId cid : state.citizensInSettlement(sid)) {
                CitizenState c = state.citizens().get(cid);
                if (c == null || !c.alive() || c.incarcerated()) continue;
                for (Profession p : professions) {
                    if (c.profession() == p) {
                        out.add(c);
                        break;
                    }
                }
            }
        }
        return out;
    }

    private static CitizenState anyAliveInKingdom(CanonicalWorldState state, KingdomState kingdom) {
        for (SettlementId sid : kingdom.settlementIds()) {
            for (CitizenId cid : state.citizensInSettlement(sid)) {
                CitizenState c = state.citizens().get(cid);
                if (c != null && c.alive()) {
                    return c;
                }
            }
        }
        return null;
    }

    private static void markHeir(CanonicalWorldState state, KingdomState kingdom, CitizenState ruler) {
        if (kingdom.governmentType() != GovernmentType.MONARCHY
                && kingdom.governmentType() != GovernmentType.PLAYER_REALM) {
            return;
        }
        CitizenState best = null;
        for (CitizenState c : state.citizens().values()) {
            if (!c.alive()) continue;
            if (ruler.id().equals(c.motherId()) || ruler.id().equals(c.fatherId())) {
                if (best == null
                        || c.birthDate().absoluteTicks() < best.birthDate().absoluteTicks()
                        || (c.birthDate().absoluteTicks() == best.birthDate().absoluteTicks()
                        && c.id().compareTo(best.id()) < 0)) {
                    best = c;
                }
            }
            c.setHeir(false);
        }
        if (best != null) {
            best.setHeir(true);
            best.setNoble(true);
        }
    }

    private static void maybePoliticalMarriage(CanonicalWorldState state, KingdomState kingdom, SimulationContext ctx) {
        if (!ctx.schedule().runDiplomacyWeekly()) return;
        if (!ctx.random().chance(0.08)) return;

        CitizenState local = null;
        for (SettlementId sid : kingdom.settlementIds()) {
            for (CitizenId cid : state.citizensInSettlement(sid)) {
                CitizenState c = state.citizens().get(cid);
                if (c == null || !c.alive() || c.spouseId() != null) continue;
                if (!(c.ruler() || c.heir() || c.noble())) continue;
                if (c.ageYears(ctx.time()) < 16) continue;
                local = c;
                break;
            }
            if (local != null) break;
        }
        if (local == null) return;

        for (KingdomId otherId : kingdom.adjacentKingdoms()) {
            KingdomState other = state.kingdoms().get(otherId);
            if (other == null) continue;
            CitizenState foreign = null;
            for (SettlementId sid : other.settlementIds()) {
                for (CitizenId cid : state.citizensInSettlement(sid)) {
                    CitizenState c = state.citizens().get(cid);
                    if (c == null || !c.alive() || c.spouseId() != null || c.female() == local.female()) continue;
                    if (!(c.ruler() || c.heir() || c.noble())) continue;
                    if (c.ageYears(ctx.time()) < 16) continue;
                    foreign = c;
                    break;
                }
                if (foreign != null) break;
            }
            if (foreign == null) continue;

            local.setSpouseId(foreign.id());
            foreign.setSpouseId(local.id());
            if (local.dynastyId() != null && foreign.dynastyId() == null) {
                foreign.setDynastyId(local.dynastyId());
                DynastyState d = state.dynasties().get(local.dynastyId());
                if (d != null) {
                    d.members().add(foreign.id());
                    d.setPrestige(d.prestige() + 3);
                    d.kingdomClaims().add(otherId);
                }
            }
            state.diplomacy().setScore(kingdom.id(), otherId, state.diplomacy().score(kingdom.id(), otherId) + 12);
            state.diplomacy().pairFactors(kingdom.id(), otherId).marriageBonus += 10;
            kingdom.setLegitimacy(clamp(kingdom.legitimacy() + 2, 0, 100));
            other.setLegitimacy(clamp(other.legitimacy() + 2, 0, 100));
            break;
        }
    }

    private static void maybePropaganda(CanonicalWorldState state, KingdomState kingdom, SimulationContext ctx) {
        if (kingdom.unrest() > 0.35 || kingdom.legitimacy() < 45) {
            kingdom.setPropaganda(clamp(kingdom.propaganda() + 0.05, 0, 1));
        } else {
            kingdom.setPropaganda(clamp(kingdom.propaganda() - 0.01, 0, 1));
        }
        for (ArmyState army : state.armies().values()) {
            if (army.owner().equals(kingdom.id())) {
                army.setMorale((int) clamp(army.morale() + kingdom.propaganda() * 2 - kingdom.warExhaustion() * 3, 0, 100));
            }
        }
    }

    private static void maybeRebellion(CanonicalWorldState state, KingdomState kingdom, SimulationContext ctx, double unrest) {
        if (unrest < 0.65) return;
        boolean existing = state.factions().values().stream()
                .anyMatch(f -> f.active() && kingdom.id().equals(f.againstKingdom()));
        if (existing) return;
        if (!ctx.random().chance(unrest * 0.08)) return;

        SettlementId origin = kingdom.settlementIds().isEmpty() ? kingdom.capitalId() : kingdom.settlementIds().getFirst();
        SettlementState s = state.settlements().get(origin);
        MarketState market = state.markets().get(origin);
        String cause = "tax";
        if (s != null && s.hunger() > 0.4) cause = "famine";
        else if (kingdom.legitimacy() < 40) cause = "legitimacy";
        else if (kingdom.warExhaustion() > 0.5) cause = "war_exhaustion";
        else if (market != null && market.crisisSeverity() > 0.4) cause = "market_crisis";

        FactionId fid = FactionId.deterministic(state.seed(), state.factions().size());
        FactionState faction = new FactionState(
                fid, kingdom.name() + " Rebels", origin, kingdom.id(), cause, ctx.time().dayIndex());
        ArmyId armyId = ArmyId.deterministic(state.seed(), state.armies().size() + 17);
        ArmyState rebelArmy = new ArmyState(
                armyId, kingdom.id(),
                s != null ? s.center() : state.settlements().values().iterator().next().center(),
                Math.max(15, (int) (unrest * 40)));
        rebelArmy.setStatus(ArmyStatus.RAIDING);
        rebelArmy.setObjective(WarObjective.RAID);
        rebelArmy.setObjectiveSettlement(origin);
        faction.setArmyId(armyId);
        faction.setStrength(unrest);
        state.factions().put(fid, faction);
        state.armies().put(armyId, rebelArmy);
        kingdom.setLegitimacy(clamp(kingdom.legitimacy() - 12, 0, 100));

        state.appendHistory(new HistoricalEvent(
                HistoricalEventId.deterministic(state.seed(), state.history().size()),
                CivilizationEventType.REBELLION_STARTED,
                ctx.time(),
                "Rebellion in " + kingdom.name(),
                "Unrest (" + cause + ") forms an armed faction.",
                s != null ? Optional.of(s.center()) : Optional.empty(),
                Map.of("faction", fid.toString(), "cause", cause)
        ));
    }

    private static double cultureReligionFriction(CanonicalWorldState state, KingdomState kingdom) {
        double friction = 0;
        int n = 0;
        for (SettlementId sid : kingdom.settlementIds()) {
            for (CitizenId cid : state.citizensInSettlement(sid)) {
                CitizenState c = state.citizens().get(cid);
                if (c == null || !c.alive()) continue;
                n++;
                if (!c.cultureId().equals(kingdom.cultureId())) friction += 1;
            }
        }
        return n == 0 ? 0 : friction / n;
    }

    private static int countAlive(CanonicalWorldState state, SettlementId id) {
        int n = 0;
        for (CitizenId cid : state.citizensInSettlement(id)) {
            CitizenState c = state.citizens().get(cid);
            if (c != null && c.alive()) n++;
        }
        return n;
    }

    private static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }

    /** Ensure a founding dynasty exists for monarchies. */
    public static DynastyId ensureDynasty(CanonicalWorldState state, KingdomState kingdom) {
        if (kingdom.dynastyId() != null && state.dynasties().containsKey(kingdom.dynastyId())) {
            return kingdom.dynastyId();
        }
        CitizenState ruler = state.citizens().get(kingdom.rulerId());
        DynastyId id = DynastyId.deterministic(state.seed(), state.dynasties().size());
        String name = (ruler != null ? ruler.familyName() : kingdom.name()) + " Dynasty";
        CitizenId founder = ruler != null ? ruler.id() : kingdom.rulerId();
        DynastyState dynasty = new DynastyState(id, name, founder, founder);
        dynasty.kingdomClaims().add(kingdom.id());
        dynasty.setPrestige(55);
        state.dynasties().put(id, dynasty);
        kingdom.setDynastyId(id);
        if (ruler != null) {
            ruler.setDynastyId(id);
            ruler.setNoble(true);
        }
        return id;
    }
}
