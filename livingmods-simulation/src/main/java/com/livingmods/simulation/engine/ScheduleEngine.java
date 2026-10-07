package com.livingmods.simulation.engine;

import com.livingmods.common.model.Profession;
import com.livingmods.common.model.ScheduleState;
import com.livingmods.common.time.SimulationTime;
import com.livingmods.simulation.CanonicalWorldState;
import com.livingmods.simulation.state.CitizenState;
import com.livingmods.simulation.state.SettlementState;
import com.livingmods.simulation.tick.RegionalWork;
import com.livingmods.simulation.tick.SimulationContext;

import java.util.ArrayList;
import java.util.List;

/**
 * Advances canonical high-level schedule states from simulation clock.
 * Physical NPC pathfinding in Minecraft follows these states.
 */
public final class ScheduleEngine implements SimulationSubsystem {
    @Override
    public String name() { return "schedule"; }

    @Override
    public void phase1Regional(CanonicalWorldState state, RegionalWork work, SimulationContext ctx) {
        int hour = hourOfDay(ctx.time());
        List<CitizenState> regional = new ArrayList<>();
        for (CitizenState c : state.citizens().values()) {
            if (!c.alive()) continue;
            SettlementState s = state.settlements().get(c.settlementId());
            if (s != null && s.region().equals(work.region())) {
                regional.add(c);
            }
        }
        work.enqueueCommit(() -> {
            for (CitizenState c : regional) {
                c.setSchedule(resolve(c, hour, ctx.time()));
                c.setProjectionRevision(c.projectionRevision() + 1);
            }
        });
    }

    static ScheduleState resolve(CitizenState c, int hour, SimulationTime now) {
        if (c.incarcerated()) {
            return ScheduleState.HOME;
        }
        if (c.isChild(now)) {
            if (hour >= 22 || hour < 6) return ScheduleState.SLEEP;
            if (hour >= 8 && hour < 15 && c.educationYears() < 6) return ScheduleState.SOCIAL;
            if (hour >= 16 && hour < 18) return ScheduleState.MARKET;
            return ScheduleState.HOME;
        }
        Profession p = c.profession();
        boolean guard = p == Profession.GUARD || p == Profession.SOLDIER
                || c.militaryRole().name().contains("GUARD");
        if (guard) {
            if (hour >= 6 && hour < 14) return ScheduleState.GUARD_DUTY;
            if (hour >= 14 && hour < 18) return ScheduleState.COMMUTE;
            if (hour >= 22 || hour < 6) return ScheduleState.SLEEP;
            return ScheduleState.HOME;
        }
        if (p == Profession.PRIEST && hour >= 9 && hour < 12) {
            return ScheduleState.RELIGIOUS;
        }
        if (p == Profession.TRADER || p == Profession.MERCHANT) {
            if (hour >= 8 && hour < 17) return ScheduleState.MARKET;
            if (hour >= 22 || hour < 6) return ScheduleState.SLEEP;
            return ScheduleState.HOME;
        }
        if (hour >= 22 || hour < 6) return ScheduleState.SLEEP;
        if (hour >= 6 && hour < 8) return ScheduleState.COMMUTE;
        if (hour >= 8 && hour < 17) return ScheduleState.WORK;
        if (hour >= 17 && hour < 19) return ScheduleState.MARKET;
        if (hour >= 19 && hour < 21) return ScheduleState.SOCIAL;
        return ScheduleState.HOME;
    }

    private static int hourOfDay(SimulationTime time) {
        long ticks = time.absoluteTicks();
        long dayTicks = SimulationTime.TICKS_PER_DAY;
        long tod = Math.floorMod(ticks, dayTicks);
        return (int) (tod / (dayTicks / 24L));
    }
}
