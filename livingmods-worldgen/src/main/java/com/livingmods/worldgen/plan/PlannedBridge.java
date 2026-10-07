package com.livingmods.worldgen.plan;

import com.livingmods.common.geo.BlockPos2;

public record PlannedBridge(
        BlockPos2 start,
        BlockPos2 end,
        BridgeKind kind,
        String cultureKey
) {
    public enum BridgeKind { FORD, WOODEN, STONE, MAJOR }
}
