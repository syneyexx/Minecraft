package com.livingmods.worldgen.plan;

import com.livingmods.common.geo.BoundingBox2;
import com.livingmods.common.id.DistrictId;
import com.livingmods.common.id.LotId;
import com.livingmods.common.id.SettlementId;
import com.livingmods.common.model.BuildingRole;
import com.livingmods.common.model.DistrictType;

import java.util.EnumSet;

public record PlannedLot(
        LotId id,
        SettlementId settlementId,
        DistrictId districtId,
        DistrictType districtType,
        BoundingBox2 bounds,
        int entranceDirection, // 0=N 1=E 2=S 3=W
        double slope,
        EnumSet<BuildingRole> allowedRoles,
        boolean occupied
) {}
