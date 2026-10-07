package com.livingmods.simulation.tick;

import com.livingmods.common.time.SimulationTime;
import com.livingmods.common.util.DeterministicRandom;

public final class SimulationContext {
    private final long seed;
    private final SimulationTime time;
    private final long tickIndex;
    private final DeterministicRandom random;

    public SimulationContext(long seed, SimulationTime time, long tickIndex) {
        this.seed = seed;
        this.time = time;
        this.tickIndex = tickIndex;
        this.random = new DeterministicRandom(HashingCombine(seed, time.absoluteTicks(), tickIndex));
    }

    public long seed() { return seed; }
    public SimulationTime time() { return time; }
    public long tickIndex() { return tickIndex; }
    public DeterministicRandom random() { return random; }

    public DeterministicRandom forkRegion(long regionKey) {
        return random.fork(regionKey);
    }

    private static long HashingCombine(long a, long b, long c) {
        long h = a ^ (b + 0x9E3779B97F4A7C15L);
        h = (h ^ (h >>> 30)) * 0xBF58476D1CE4E5B9L;
        h ^= c;
        return h ^ (h >>> 31);
    }
}
