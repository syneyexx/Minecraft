package com.livingmods.worldgen.plan;

import com.livingmods.common.geo.BlockPos2;
import com.livingmods.common.id.CultureId;
import com.livingmods.common.id.KingdomId;
import com.livingmods.common.id.SettlementId;
import com.livingmods.common.model.GovernmentType;

import java.util.List;

public record PlannedKingdom(
        KingdomId id,
        String name,
        CultureId cultureId,
        String cultureKey,
        GovernmentType governmentType,
        SettlementId capitalId,
        BlockPos2 capitalCenter,
        List<SettlementId> settlementIds,
        List<BlockPos2> territoryPolygon,
        boolean underground,
        String religionKey
) {}
