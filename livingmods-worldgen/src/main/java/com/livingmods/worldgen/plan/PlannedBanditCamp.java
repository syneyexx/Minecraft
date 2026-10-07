package com.livingmods.worldgen.plan;

import com.livingmods.common.geo.BlockPos2;

public record PlannedBanditCamp(
        BlockPos2 center,
        int size,
        String reason,
        CampVariant variant
) {
    public enum CampVariant {
        ROAD_CAMP,
        HIDEOUT,
        FOREST,
        RUINED_FORT,
        STRONGHOLD
    }
}
