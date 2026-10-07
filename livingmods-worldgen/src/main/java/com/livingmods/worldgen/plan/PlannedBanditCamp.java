package com.livingmods.worldgen.plan;

import com.livingmods.common.geo.BlockPos2;

public record PlannedBanditCamp(
        BlockPos2 center,
        int size,
        String reason
) {}
