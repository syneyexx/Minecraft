package com.livingmods.worldgen.plan;

import com.livingmods.common.geo.BoundingBox2;
import com.livingmods.common.model.BuildingRole;

public record PlannedRuin(
        BoundingBox2 bounds,
        BuildingRole originalRole,
        String cultureKey,
        int decaySeed,
        String historicalNote
) {}
