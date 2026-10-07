package com.livingmods.simulation.engine;

import com.livingmods.common.id.CitizenId;
import com.livingmods.common.id.HouseholdId;
import com.livingmods.common.model.Profession;
import com.livingmods.common.time.SimulationTime;
import com.livingmods.common.util.DeterministicRandom;
import com.livingmods.simulation.CanonicalWorldState;
import com.livingmods.simulation.state.CitizenState;
import com.livingmods.simulation.state.HouseholdState;
import com.livingmods.simulation.state.SettlementState;
import com.livingmods.simulation.tick.RegionalWork;
import com.livingmods.simulation.tick.SimulationContext;

import java.util.ArrayList;
import java.util.List;

public final class DemographyEngine implements SimulationSubsystem {
    @Override
    public String name() { return "demography"; }

    @Override
    public void phase1Regional(CanonicalWorldState state, RegionalWork work, SimulationContext ctx) {
        if (!ctx.schedule().runDemography()) return;

        List<CitizenState> regional = new ArrayList<>();
        for (CitizenState c : state.citizens().values()) {
            SettlementState s = state.settlements().get(c.settlementId());
            if (s != null && s.region().equals(work.region())) {
                regional.add(c);
            }
        }

        DeterministicRandom rng = ctx.forkRegion(work.region().x() * 10000L + work.region().z());
        List<DeathRecord> deaths = new ArrayList<>();
        List<BirthRecord> births = new ArrayList<>();

        for (CitizenState c : regional) {
            if (!c.alive()) continue;
            int age = c.ageYears(ctx.time());
            double mortality = baseMortality(age, c.health());
            if (rng.chance(mortality)) {
                deaths.add(new DeathRecord(c.id()));
            } else {
                double healthDelta = agingHealthDelta(age);
                work.enqueueCommit(() -> c.setHealth(clamp(c.health() + healthDelta, 0, 100)));
            }
        }

        for (CitizenState c : regional) {
            if (!c.alive() || c.female()) continue;
            int age = c.ageYears(ctx.time());
            if (age < 20 || age > 45) continue;
            HouseholdState hh = state.households().get(c.householdId());
            if (hh == null || hh.memberCount() >= 6) continue;
            if (rng.chance(0.02)) {
                births.add(new BirthRecord(c.settlementId(), c.householdId(), c.cultureId(), c.familyName()));
            }
        }

        work.enqueueCommit(() -> {
            for (DeathRecord d : deaths) {
                CitizenState c = state.citizens().get(d.id);
                if (c != null) {
                    c.setAlive(false);
                    if (c.ruler()) {
                        c.setRuler(false);
                    }
                    HouseholdState hh = state.households().get(c.householdId());
                    if (hh != null) {
                        hh.setMemberCount(Math.max(0, hh.memberCount() - 1));
                    }
                }
            }
            long ordinal = state.citizens().size();
            for (BirthRecord b : births) {
                CitizenId childId = CitizenId.deterministic(state.seed(), ordinal++);
                CitizenState child = new CitizenState(
                        childId,
                        rng.chance(0.5) ? "Child" : "Infant",
                        b.familyName,
                        rng.nextBoolean(),
                        ctx.time(),
                        b.cultureId,
                        b.settlementId,
                        b.householdId,
                        Profession.CHILD,
                        90.0,
                        0.0,
                        true,
                        false
                );
                state.putCitizen(child);
                HouseholdState hh = state.households().get(b.householdId);
                if (hh != null) {
                    hh.setMemberCount(hh.memberCount() + 1);
                }
            }
        });
    }

    private static double baseMortality(int age, double health) {
        double ageFactor = age > 60 ? 0.08 : age > 40 ? 0.02 : 0.005;
        double healthFactor = health < 30 ? 0.05 : 0.0;
        return ageFactor + healthFactor;
    }

    private static double agingHealthDelta(int age) {
        if (age > 70) return -0.5;
        if (age > 50) return -0.1;
        return 0.05;
    }

    private static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }

    private record DeathRecord(CitizenId id) {}
    private record BirthRecord(
            com.livingmods.common.id.SettlementId settlementId,
            HouseholdId householdId,
            com.livingmods.common.id.CultureId cultureId,
            String familyName
    ) {}
}
