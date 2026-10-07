package com.livingmods.common.geo;

/** Horizontal block coordinate (X/Z). Immutable. */
public record BlockPos2(int x, int z) implements Comparable<BlockPos2> {
    public static BlockPos2 of(int x, int z) {
        return new BlockPos2(x, z);
    }

    public BlockPos2 add(int dx, int dz) {
        return new BlockPos2(x + dx, z + dz);
    }

    public long packed() {
        return ((long) x << 32) | (z & 0xffffffffL);
    }

    public double distanceTo(BlockPos2 other) {
        double dx = x - other.x;
        double dz = z - other.z;
        return Math.hypot(dx, dz);
    }

    public ChunkCoord toChunk() {
        return ChunkCoord.of(x >> 4, z >> 4);
    }

    @Override
    public int compareTo(BlockPos2 o) {
        int c = Integer.compare(x, o.x);
        return c != 0 ? c : Integer.compare(z, o.z);
    }
}
