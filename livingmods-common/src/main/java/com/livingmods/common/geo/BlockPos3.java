package com.livingmods.common.geo;

public record BlockPos3(int x, int y, int z) implements Comparable<BlockPos3> {
    public static BlockPos3 of(int x, int y, int z) {
        return new BlockPos3(x, y, z);
    }

    public BlockPos2 horizontal() {
        return new BlockPos2(x, z);
    }

    @Override
    public int compareTo(BlockPos3 o) {
        int c = Integer.compare(x, o.x);
        if (c != 0) return c;
        c = Integer.compare(y, o.y);
        return c != 0 ? c : Integer.compare(z, o.z);
    }
}
