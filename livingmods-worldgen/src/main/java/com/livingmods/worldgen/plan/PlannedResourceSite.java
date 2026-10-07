package com.livingmods.worldgen.plan;

import com.livingmods.common.geo.BlockPos2;
import com.livingmods.common.id.KingdomId;
import com.livingmods.common.model.ResourceType;

import java.util.Optional;

public record PlannedResourceSite(
        BlockPos2 center,
        ResourceType resource,
        double richness,
        Optional<KingdomId> claimedBy
) {}
