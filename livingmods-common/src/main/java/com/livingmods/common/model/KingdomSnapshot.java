package com.livingmods.common.model;

import com.livingmods.common.geo.BlockPos2;
import com.livingmods.common.id.CitizenId;
import com.livingmods.common.id.CultureId;
import com.livingmods.common.id.KingdomId;
import com.livingmods.common.id.SettlementId;

import java.util.List;

public record KingdomSnapshot(
        KingdomId id,
        String name,
        CultureId cultureId,
        GovernmentType governmentType,
        SettlementId capitalId,
        CitizenId rulerId,
        BlockPos2 capitalCenter,
        List<SettlementId> settlementIds,
        List<BlockPos2> territoryPolygon,
        double treasury,
        double stability,
        double militaryStrength,
        boolean underground,
        boolean playerFounded
) {}
