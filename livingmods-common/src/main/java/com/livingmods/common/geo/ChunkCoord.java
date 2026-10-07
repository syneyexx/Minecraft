package com.livingmods.common.geo;

public record ChunkCoord(int x, int z) implements Comparable<ChunkCoord> {
    public static ChunkCoord of(int x, int z) {
        return new ChunkCoord(x, z);
    }

    public BlockPos2 blockOrigin() {
        return new BlockPos2(x << 4, z << 4);
    }

    public RegionCoord toRegion(int regionSizeChunks) {
        int rx = Math.floorDiv(x, regionSizeChunks);
        int rz = Math.floorDiv(z, regionSizeChunks);
        return RegionCoord.of(rx, rz);
    }

    @Override
    public int compareTo(ChunkCoord o) {
        int c = Integer.compare(x, o.x);
        return c != 0 ? c : Integer.compare(z, o.z);
    }
}
