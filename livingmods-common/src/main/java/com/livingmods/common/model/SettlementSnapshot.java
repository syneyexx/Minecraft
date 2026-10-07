package com.livingmods.common.model;

import com.livingmods.common.geo.BlockPos2;
import com.livingmods.common.geo.BoundingBox2;
import com.livingmods.common.id.CultureId;
import com.livingmods.common.id.KingdomId;
import com.livingmods.common.id.SettlementId;

import java.util.Map;
import java.util.Optional;

public record SettlementSnapshot(
        SettlementId id,
        String name,
        SettlementTier tier,
        SettlementRole role,
        BlockPos2 center,
        BoundingBox2 bounds,
        Optional<KingdomId> ownerKingdom,
        CultureId cultureId,
        int population,
        int housingCapacity,
        Map<ResourceType, Double> stockpile,
        Map<ResourceType, Double> marketPrices,
        double securityLevel,
        double legitimacy,
        boolean hasWalls,
        boolean isCapital,
        boolean underground
) {}
