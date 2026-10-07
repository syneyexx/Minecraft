package com.livingmods.worldgen.plan;

import com.livingmods.common.geo.BlockPos2;
import com.livingmods.common.geo.BoundingBox2;
import com.livingmods.common.id.CultureId;
import com.livingmods.common.id.KingdomId;
import com.livingmods.common.id.SettlementId;
import com.livingmods.common.model.SettlementRole;
import com.livingmods.common.model.SettlementTier;

import java.util.List;
import java.util.Optional;

public record PlannedSettlement(
        SettlementId id,
        String name,
        SettlementTier tier,
        SettlementRole role,
        BlockPos2 center,
        BoundingBox2 bounds,
        Optional<KingdomId> ownerKingdom,
        CultureId cultureId,
        String cultureKey,
        boolean capital,
        boolean walls,
        boolean underground,
        int plannedPopulation,
        List<PlannedDistrict> districts,
        List<PlannedLot> lots,
        List<PlannedBuilding> buildings,
        List<BlockPos2> streetNetwork,
        List<BlockPos2> wallPath,
        List<BlockPos2> gatePositions
) {}
