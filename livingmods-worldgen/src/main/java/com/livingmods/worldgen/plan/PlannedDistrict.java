package com.livingmods.worldgen.plan;

import com.livingmods.common.geo.BoundingBox2;
import com.livingmods.common.id.DistrictId;
import com.livingmods.common.id.SettlementId;
import com.livingmods.common.model.DistrictType;

public record PlannedDistrict(
        DistrictId id,
        SettlementId settlementId,
        DistrictType type,
        BoundingBox2 bounds
) {}
