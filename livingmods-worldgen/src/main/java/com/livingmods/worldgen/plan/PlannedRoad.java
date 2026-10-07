package com.livingmods.worldgen.plan;

import com.livingmods.common.geo.BlockPos2;
import com.livingmods.common.id.RoadId;
import com.livingmods.common.id.SettlementId;
import com.livingmods.common.model.RoadClass;

import java.util.List;
import java.util.Optional;

public record PlannedRoad(
        RoadId id,
        RoadClass roadClass,
        List<BlockPos2> path,
        Optional<SettlementId> fromSettlement,
        Optional<SettlementId> toSettlement,
        List<PlannedBridge> bridges,
        String cultureKey
) {}
