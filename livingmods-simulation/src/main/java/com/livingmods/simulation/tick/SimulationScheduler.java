package com.livingmods.simulation.tick;

import com.livingmods.common.time.SimulationTime;

/**
 * Tracks subsystem cadence: markets hourly, government/demography daily,
 * diplomacy daily/weekly, technology slow.
 * <p>
 * Cadence flags for a step must be computed once via {@link #planStep(SimulationTime)}
 * so concurrent regional workers do not race check-and-set markers.
 */
public final class SimulationScheduler {
    public static final long TICKS_PER_HOUR = SimulationTime.TICKS_PER_DAY / 24L;

    public record SimulationStepSchedule(
            boolean runMarkets,
            boolean runDemography,
            boolean runGovernment,
            boolean runDiplomacyDaily,
            boolean runDiplomacyWeekly,
            boolean runTechnology
    ) {
        public static final SimulationStepSchedule NONE = new SimulationStepSchedule(
                false, false, false, false, false, false);
    }

    private long lastMarketHour = -1;
    private long lastGovernmentDay = -1;
    private long lastDiplomacyDay = -1;
    private long lastDiplomacyWeek = -1;
    private long lastDemographyDay = -1;
    private long lastTechDay = -1;

    private final ThreadLocal<SimulationStepSchedule> currentSchedule =
            ThreadLocal.withInitial(() -> SimulationStepSchedule.NONE);

    /**
     * Compute all cadence flags once and update last* markers once.
     * Call from the simulation thread before dispatching regional work.
     */
    public SimulationStepSchedule planStep(SimulationTime time) {
        long hour = time.absoluteTicks() / TICKS_PER_HOUR;
        long day = time.dayIndex();
        long week = day / 7L;

        boolean runMarkets = hour != lastMarketHour;
        boolean runGovernment = day != lastGovernmentDay;
        boolean runDemography = day != lastDemographyDay;
        boolean runDiplomacyDaily = day != lastDiplomacyDay;
        boolean runDiplomacyWeekly = week != lastDiplomacyWeek;
        boolean runTechnology = day % 7 == 0 && day != lastTechDay;

        if (runMarkets) {
            lastMarketHour = hour;
        }
        if (runGovernment) {
            lastGovernmentDay = day;
        }
        if (runDemography) {
            lastDemographyDay = day;
        }
        if (runDiplomacyDaily) {
            lastDiplomacyDay = day;
        }
        if (runDiplomacyWeekly) {
            lastDiplomacyWeek = week;
        }
        if (runTechnology) {
            lastTechDay = day;
        }

        SimulationStepSchedule schedule = new SimulationStepSchedule(
                runMarkets,
                runDemography,
                runGovernment,
                runDiplomacyDaily,
                runDiplomacyWeekly,
                runTechnology
        );
        currentSchedule.set(schedule);
        return schedule;
    }

    public void setCurrentSchedule(SimulationStepSchedule schedule) {
        currentSchedule.set(schedule == null ? SimulationStepSchedule.NONE : schedule);
    }

    public SimulationStepSchedule currentSchedule() {
        return currentSchedule.get();
    }

    /** @deprecated Use {@link SimulationContext#schedule()} / {@link #planStep(SimulationTime)}. */
    @Deprecated
    public boolean shouldRunMarkets(SimulationTime time) {
        return currentSchedule.get().runMarkets();
    }

    /** @deprecated Use {@link SimulationContext#schedule()} / {@link #planStep(SimulationTime)}. */
    @Deprecated
    public boolean shouldRunGovernment(SimulationTime time) {
        return currentSchedule.get().runGovernment();
    }

    /** @deprecated Use {@link SimulationContext#schedule()} / {@link #planStep(SimulationTime)}. */
    @Deprecated
    public boolean shouldRunDemography(SimulationTime time) {
        return currentSchedule.get().runDemography();
    }

    /** @deprecated Use {@link SimulationContext#schedule()} / {@link #planStep(SimulationTime)}. */
    @Deprecated
    public boolean shouldRunDiplomacyDaily(SimulationTime time) {
        return currentSchedule.get().runDiplomacyDaily();
    }

    /** @deprecated Use {@link SimulationContext#schedule()} / {@link #planStep(SimulationTime)}. */
    @Deprecated
    public boolean shouldRunDiplomacyWeekly(SimulationTime time) {
        return currentSchedule.get().runDiplomacyWeekly();
    }

    /** @deprecated Use {@link SimulationContext#schedule()} / {@link #planStep(SimulationTime)}. */
    @Deprecated
    public boolean shouldRunTechnology(SimulationTime time) {
        return currentSchedule.get().runTechnology();
    }

    public void reset() {
        lastMarketHour = -1;
        lastGovernmentDay = -1;
        lastDiplomacyDay = -1;
        lastDiplomacyWeek = -1;
        lastDemographyDay = -1;
        lastTechDay = -1;
        currentSchedule.set(SimulationStepSchedule.NONE);
    }
}
