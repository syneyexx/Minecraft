package com.livingmods.simulation.engine;

import com.livingmods.common.id.CitizenId;
import com.livingmods.common.id.CrimeId;
import com.livingmods.common.id.SettlementId;
import com.livingmods.common.model.CrimeStatus;
import com.livingmods.common.model.CrimeType;
import com.livingmods.common.model.CrimeVerdict;
import com.livingmods.common.model.Personality;
import com.livingmods.common.model.Profession;
import com.livingmods.common.util.DeterministicRandom;
import com.livingmods.simulation.CanonicalWorldState;
import com.livingmods.simulation.state.CitizenState;
import com.livingmods.simulation.state.CrimeState;
import com.livingmods.simulation.state.MarketState;
import com.livingmods.simulation.state.SettlementState;
import com.livingmods.simulation.state.WarState;
import com.livingmods.simulation.tick.RegionalWork;
import com.livingmods.simulation.tick.SimulationContext;

import java.util.ArrayList;
import java.util.List;

/**
 * Crime probability from poverty, hunger, personality, security, war, opportunity.
 * Cases record suspects, victims, witnesses/evidence, jurisdiction, verdict, sentence.
 * Incarcerated citizens cannot work; sentences expire by day.
 */
public final class CrimeJusticeEngine implements SimulationSubsystem {
    @Override
    public String name() { return "crime_justice"; }

    @Override
    public void phase1Regional(CanonicalWorldState state, RegionalWork work, SimulationContext ctx) {
        if (!ctx.schedule().runGovernment()) return;

        DeterministicRandom rng = ctx.forkRegion(work.region().x() * 31L + work.region().z());
        List<Runnable> commits = new ArrayList<>();
        long day = ctx.time().dayIndex();

        // Release prisoners whose sentences ended
        for (CitizenState c : state.citizens().values()) {
            if (!c.incarcerated()) continue;
            SettlementState s = state.settlements().get(c.settlementId());
            if (s == null || !s.region().equals(work.region())) continue;
            if (c.sentenceEndsDay() > 0 && day >= c.sentenceEndsDay()) {
                commits.add(() -> {
                    c.setIncarcerated(false);
                    c.setSentenceEndsDay(0);
                });
            }
        }

        // Advance open cases toward trial/verdict
        for (CrimeState crime : state.crimes().values()) {
            SettlementState s = state.settlements().get(crime.jurisdiction());
            if (s == null || !s.region().equals(work.region())) continue;
            if (crime.status() == CrimeStatus.CLOSED
                    || crime.status() == CrimeStatus.ACQUITTED
                    || crime.status() == CrimeStatus.CONVICTED) {
                continue;
            }
            commits.add(() -> advanceCase(state, crime, day, rng));
        }

        // New offenses
        for (CitizenState c : state.citizens().values()) {
            if (!c.alive() || c.incarcerated()) continue;
            SettlementState s = state.settlements().get(c.settlementId());
            if (s == null || !s.region().equals(work.region())) continue;

            double p = crimeProbability(state, c, s);
            if (!rng.chance(p)) continue;

            CitizenState victim = pickVictim(state, s, c, rng);
            CrimeType type = pickType(c, s, rng);
            CrimeId id = CrimeId.deterministic(state.seed(), state.crimes().size() + commits.size());
            CitizenId victimId = victim != null ? victim.id() : c.id();
            commits.add(() -> {
                CrimeState crime = new CrimeState(id, type, c.id(), victimId, s.id(), day);
                crime.setEvidence(0.2 + rng.nextDouble() * 0.5);
                // Witnesses among locals
                int witnessBudget = 1 + rng.nextInt(0, 3);
                for (CitizenState w : state.citizens().values()) {
                    if (witnessBudget <= 0) break;
                    if (!w.alive() || w.id().equals(c.id()) || !w.settlementId().equals(s.id())) continue;
                    if (rng.chance(0.15)) {
                        crime.witnesses().add(w.id());
                        witnessBudget--;
                    }
                }
                crime.setStatus(CrimeStatus.INVESTIGATING);
                state.crimes().put(id, crime);
                c.setCrimeStrikes(c.crimeStrikes() + 1);
                if (type == CrimeType.THEFT || type == CrimeType.BANDITRY) {
                    c.setWealth(c.wealth() + 2);
                    if (victim != null) victim.setWealth(Math.max(0, victim.wealth() - 2));
                }
                s.setSecurity(Math.max(0, s.security() - 0.02));
            });
        }

        // Security response from markets after bandit pressure
        for (SettlementState s : state.settlements().values()) {
            if (!s.region().equals(work.region())) continue;
            MarketState m = state.markets().get(s.id());
            if (m != null && m.securityResponse() > 0.1) {
                commits.add(() -> s.setSecurity(Math.min(1.0, s.security() + 0.05)));
            }
        }

        work.enqueueCommit(() -> {
            for (Runnable r : commits) r.run();
        });
    }

    private static double crimeProbability(CanonicalWorldState state, CitizenState c, SettlementState s) {
        double poverty = c.wealth() < 3 ? 0.4 : c.wealth() < 8 ? 0.15 : 0.02;
        double hunger = s.hunger() * 0.35;
        Personality p = c.personality();
        double personality = p == null ? 0.05 : (p.greed() * 0.12 + p.treachery() * 0.1 + p.aggression() * 0.05);
        double security = (1.0 - s.security()) * 0.2;
        double war = 0;
        if (s.ownerKingdom().isPresent()) {
            for (WarState w : state.wars().values()) {
                if (w.active() && w.participants().contains(s.ownerKingdom().get())) {
                    war = 0.12;
                    break;
                }
            }
        }
        double opportunity = c.profession() == Profession.UNEMPLOYED ? 0.1 : 0.02;
        double guards = 0;
        for (CitizenState other : state.citizens().values()) {
            if (other.alive() && other.settlementId().equals(s.id())
                    && (other.profession() == Profession.GUARD || other.profession() == Profession.SOLDIER)) {
                guards += 0.02;
            }
        }
        return Math.max(0.0002, Math.min(0.08, poverty + hunger + personality + security + war + opportunity - guards));
    }

    private static CrimeType pickType(CitizenState c, SettlementState s, DeterministicRandom rng) {
        Personality p = c.personality();
        if (s.hunger() > 0.5 && rng.chance(0.4)) return CrimeType.THEFT;
        if (p != null && p.aggression() > 0.7 && rng.chance(0.25)) return CrimeType.ASSAULT;
        if (p != null && p.treachery() > 0.5 && rng.chance(0.1)) return CrimeType.POLITICAL;
        if (rng.chance(0.15)) return CrimeType.SMUGGLING;
        if (rng.chance(0.08)) return CrimeType.BANDITRY;
        if (rng.chance(0.02)) return CrimeType.MURDER;
        return CrimeType.THEFT;
    }

    private static CitizenState pickVictim(
            CanonicalWorldState state, SettlementState s, CitizenState offender, DeterministicRandom rng
    ) {
        List<CitizenState> locals = new ArrayList<>();
        for (CitizenState c : state.citizens().values()) {
            if (c.alive() && !c.id().equals(offender.id()) && c.settlementId().equals(s.id())) {
                locals.add(c);
            }
        }
        if (locals.isEmpty()) return null;
        locals.sort((a, b) -> a.id().compareTo(b.id()));
        return locals.get(rng.nextInt(0, locals.size()));
    }

    private static void advanceCase(CanonicalWorldState state, CrimeState crime, long day, DeterministicRandom rng) {
        switch (crime.status()) {
            case REPORTED, INVESTIGATING -> {
                crime.setEvidence(Math.min(1.0, crime.evidence() + 0.1 + crime.witnesses().size() * 0.05));
                crime.setStatus(CrimeStatus.CHARGED);
            }
            case CHARGED -> crime.setStatus(CrimeStatus.TRIAL);
            case TRIAL -> {
                boolean guilty = crime.evidence() + crime.witnesses().size() * 0.08 > 0.55
                        || rng.chance(crime.evidence());
                CitizenState suspect = state.citizens().get(crime.suspectId());
                if (guilty) {
                    crime.setVerdict(CrimeVerdict.GUILTY);
                    crime.setStatus(CrimeStatus.CONVICTED);
                    int sentence = switch (crime.type()) {
                        case THEFT, SMUGGLING -> 20;
                        case ASSAULT, BANDITRY -> 45;
                        case POLITICAL -> 60;
                        case MURDER -> 120;
                    };
                    crime.setSentenceDays(sentence);
                    crime.setSentenceEndsDay(day + sentence);
                    if (suspect != null) {
                        suspect.setIncarcerated(true);
                        suspect.setSentenceEndsDay(day + sentence);
                        suspect.setWealth(Math.max(0, suspect.wealth() - 3));
                        // Prison projection: keep them in the jurisdiction settlement
                        suspect.setSettlementId(crime.jurisdiction());
                    }
                } else {
                    crime.setVerdict(CrimeVerdict.NOT_GUILTY);
                    crime.setStatus(CrimeStatus.ACQUITTED);
                }
            }
            default -> {}
        }
    }
}
