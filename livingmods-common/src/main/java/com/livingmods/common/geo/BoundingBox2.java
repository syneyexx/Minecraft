package com.livingmods.common.geo;

public record BoundingBox2(int minX, int minZ, int maxX, int maxZ) {
    public BoundingBox2 {
        if (maxX < minX || maxZ < minZ) {
            throw new IllegalArgumentException("invalid bounds");
        }
    }

    public static BoundingBox2 of(int minX, int minZ, int maxX, int maxZ) {
        return new BoundingBox2(minX, minZ, maxX, maxZ);
    }

    public static BoundingBox2 around(BlockPos2 center, int radius) {
        return new BoundingBox2(center.x() - radius, center.z() - radius, center.x() + radius, center.z() + radius);
    }

    public boolean contains(int x, int z) {
        return x >= minX && x <= maxX && z >= minZ && z <= maxZ;
    }

    public boolean contains(BlockPos2 p) {
        return contains(p.x(), p.z());
    }

    public boolean intersects(BoundingBox2 o) {
        return minX <= o.maxX && maxX >= o.minX && minZ <= o.maxZ && maxZ >= o.minZ;
    }

    public int width() { return maxX - minX + 1; }
    public int depth() { return maxZ - minZ + 1; }

    public BlockPos2 center() {
        return new BlockPos2((minX + maxX) / 2, (minZ + maxZ) / 2);
    }

    public BoundingBox2 expand(int amount) {
        return new BoundingBox2(minX - amount, minZ - amount, maxX + amount, maxZ + amount);
    }
}
