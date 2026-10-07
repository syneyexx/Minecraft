package com.livingmods.common.util;

/**
 * Explicitly seeded deterministic PRNG. No shared global mutable state.
 * Splitmix64-based; safe to fork by domain.
 */
public final class DeterministicRandom {
    private long state;

    public DeterministicRandom(long seed) {
        this.state = seed == 0 ? 0xDEADBEEFCAFEBABEL : seed;
    }

    public DeterministicRandom fork(String domain) {
        return new DeterministicRandom(state ^ mix(domain.hashCode()));
    }

    public DeterministicRandom fork(long domain) {
        return new DeterministicRandom(state ^ mix(domain));
    }

    public long nextLong() {
        state += 0x9E3779B97F4A7C15L;
        long z = state;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    public int nextInt() {
        return (int) nextLong();
    }

    public int nextInt(int bound) {
        if (bound <= 0) throw new IllegalArgumentException("bound must be positive");
        return (int) (Math.floorMod(nextLong(), bound));
    }

    public int nextInt(int minInclusive, int maxExclusive) {
        if (maxExclusive <= minInclusive) return minInclusive;
        return minInclusive + nextInt(maxExclusive - minInclusive);
    }

    public double nextDouble() {
        return (nextLong() >>> 11) * 0x1.0p-53;
    }

    public boolean nextBoolean() {
        return (nextLong() & 1L) != 0L;
    }

    public boolean chance(double probability) {
        return nextDouble() < probability;
    }

    public double nextGaussian() {
        // Box-Muller
        double u1 = Math.max(1e-12, nextDouble());
        double u2 = nextDouble();
        return Math.sqrt(-2.0 * Math.log(u1)) * Math.cos(2.0 * Math.PI * u2);
    }

    public <T> T pick(java.util.List<T> items) {
        if (items.isEmpty()) throw new IllegalArgumentException("empty");
        return items.get(nextInt(items.size()));
    }

    private static long mix(long x) {
        x = (x ^ (x >>> 30)) * 0xBF58476D1CE4E5B9L;
        x = (x ^ (x >>> 27)) * 0x94D049BB133111EBL;
        return x ^ (x >>> 31);
    }
}
