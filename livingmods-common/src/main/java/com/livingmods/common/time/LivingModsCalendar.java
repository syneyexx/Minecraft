package com.livingmods.common.time;

/**
 * Civilization calendar used for aging, harvest, festivals, history.
 */
public record LivingModsCalendar(int year, int dayOfYear, int hour) {
    public enum Season { SPRING, SUMMER, AUTUMN, WINTER }

    public static LivingModsCalendar from(SimulationTime time) {
        return new LivingModsCalendar(time.year(), time.dayOfYear(), time.hourOfDay());
    }

    public Season season() {
        int d = dayOfYear;
        if (d < 80) return Season.WINTER;
        if (d < 172) return Season.SPRING;
        if (d < 264) return Season.SUMMER;
        if (d < 355) return Season.AUTUMN;
        return Season.WINTER;
    }

    public boolean isHarvestSeason() {
        return season() == Season.AUTUMN;
    }

    public boolean isPlantingSeason() {
        return season() == Season.SPRING;
    }

    @Override
    public String toString() {
        return "Year " + year + ", Day " + (dayOfYear + 1) + " (" + season() + ")";
    }
}
