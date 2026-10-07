package com.livingmods.common.geo;

/** Macro planning region larger than a chunk. */
public record RegionCoord(int x, int z) implements Comparable<RegionCoord> {
    public static final int DEFAULT_SIZE_CHUNKS = 64; // 1024 blocks

    public static RegionCoord of(int x, int z) {
        return new RegionCoord(x, z);
    }

    public ChunkCoord chunkOrigin(int regionSizeChunks) {
        return ChunkCoord.of(x * regionSizeChunks, z * regionSizeChunks);
    }

    public BlockPos2 blockOrigin(int regionSizeChunks) {
        return chunkOrigin(regionSizeChunks).blockOrigin();
    }

    @Override
    public int compareTo(RegionCoord o) {
        int c = Integer.compare(x, o.x);
        return c != 0 ? c : Integer.compare(z, o.z);
    }
}
