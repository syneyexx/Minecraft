package com.livingmods.common.time;

/**
 * Canonical civilization clock. Independent of Minecraft tick rate.
 * One simulation day = 24000 Minecraft ticks when synchronized.
 */
public final class SimulationTime implements Comparable<SimulationTime> {
    public static final long TICKS_PER_DAY = 24_000L;
    public static final int DAYS_PER_YEAR = 365;

    private final long absoluteTicks;

    public SimulationTime(long absoluteTicks) {
        if (absoluteTicks < 0) throw new IllegalArgumentException("negative time");
        this.absoluteTicks = absoluteTicks;
    }

    public static SimulationTime ofTicks(long ticks) {
        return new SimulationTime(ticks);
    }

    public static SimulationTime ofDays(long days) {
        return new SimulationTime(days * TICKS_PER_DAY);
    }

    public long absoluteTicks() { return absoluteTicks; }
    public long dayIndex() { return absoluteTicks / TICKS_PER_DAY; }
    public long tickOfDay() { return absoluteTicks % TICKS_PER_DAY; }
    public int year() { return (int) (dayIndex() / DAYS_PER_YEAR); }
    public int dayOfYear() { return (int) (dayIndex() % DAYS_PER_YEAR); }
    public int hourOfDay() { return (int) ((tickOfDay() * 24) / TICKS_PER_DAY); }

    public SimulationTime plusTicks(long ticks) {
        return new SimulationTime(absoluteTicks + ticks);
    }

    public SimulationTime plusDays(long days) {
        return plusTicks(days * TICKS_PER_DAY);
    }

    public long ticksUntil(SimulationTime other) {
        return other.absoluteTicks - absoluteTicks;
    }

    public LivingModsCalendar toCalendar() {
        return LivingModsCalendar.from(this);
    }

    @Override
    public int compareTo(SimulationTime o) {
        return Long.compare(absoluteTicks, o.absoluteTicks);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof SimulationTime t && t.absoluteTicks == absoluteTicks;
    }

    @Override
    public int hashCode() {
        return Long.hashCode(absoluteTicks);
    }

    @Override
    public String toString() {
        return "Y" + year() + "D" + dayOfYear() + "H" + hourOfDay();
    }
}
