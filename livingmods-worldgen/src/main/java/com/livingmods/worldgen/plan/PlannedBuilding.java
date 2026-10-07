package com.livingmods.worldgen.plan;

import com.livingmods.common.geo.BoundingBox2;
import com.livingmods.common.id.LotId;
import com.livingmods.common.id.StructureId;
import com.livingmods.common.model.BuildingRole;
import com.livingmods.common.model.WealthClass;

public record PlannedBuilding(
        StructureId id,
        LotId lotId,
        BuildingRole role,
        WealthClass wealthClass,
        BoundingBox2 footprint,
        int rotationY,
        int foundationY,
        String cultureKey,
        String paletteKey,
        int seed
) {}
