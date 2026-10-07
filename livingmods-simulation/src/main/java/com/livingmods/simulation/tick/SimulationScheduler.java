package com.livingmods.simulation.tick;

import com.livingmods.common.time.SimulationTime;

/**
 * Tracks subsystem cadence: markets hourly, government/demography daily,
 * diplomacy daily/weekly, technology slow.
 */
public final class SimulationScheduler {
    public static final long TICKS_PER_HOUR = SimulationTime.TICKS_PER_DAY / 24L;

    private long lastMarketHour = -1;
    private long lastGovernmentDay = -1;
    private long lastDiplomacyDay = -1;
    private long lastDiplomacyWeek = -1;
    private long lastDemographyDay = -1;
    private long lastTechDay = -1;

    public boolean shouldRunMarkets(SimulationTime time) {
        long hour = time.absoluteTicks() / TICKS_PER_HOUR;
        if (hour != lastMarketHour) {
            lastMarketHour = hour;
            return true;
        }
        return false;
    }

    public boolean shouldRunGovernment(SimulationTime time) {
        long day = time.dayIndex();
        if (day != lastGovernmentDay) {
            lastGovernmentDay = day;
            return true;
        }
        return false;
    }

    public boolean shouldRunDemography(SimulationTime time) {
        long day = time.dayIndex();
        if (day != lastDemographyDay) {
            lastDemographyDay = day;
            return true;
        }
        return false;
    }

    public boolean shouldRunDiplomacyDaily(SimulationTime time) {
        long day = time.dayIndex();
        if (day != lastDiplomacyDay) {
            lastDiplomacyDay = day;
            return true;
        }
        return false;
    }

    public boolean shouldRunDiplomacyWeekly(SimulationTime time) {
        long week = time.dayIndex() / 7L;
        if (week != lastDiplomacyWeek) {
            lastDiplomacyWeek = week;
            return true;
        }
        return false;
    }

    public boolean shouldRunTechnology(SimulationTime time) {
        long day = time.dayIndex();
        if (day % 7 == 0 && day != lastTechDay) {
            lastTechDay = day;
            return true;
        }
        return false;
    }

    public void reset() {
        lastMarketHour = -1;
        lastGovernmentDay = -1;
        lastDiplomacyDay = -1;
        lastDiplomacyWeek = -1;
        lastDemographyDay = -1;
        lastTechDay = -1;
    }
}
