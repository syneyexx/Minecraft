package com.livingmods.worldgen.plan;

import com.livingmods.common.geo.ChunkCoord;

import java.util.List;

public record ChunkCivilizationSlice(
        ChunkCoord chunk,
        List<PlannedSettlement> settlements,
        List<PlannedRoad> roads,
        List<PlannedRuin> ruins,
        List<PlannedResourceSite> resourceSites,
        List<PlannedBanditCamp> banditCamps
) {
    public boolean isEmpty() {
        return settlements.isEmpty() && roads.isEmpty() && ruins.isEmpty()
                && resourceSites.isEmpty() && banditCamps.isEmpty();
    }
}
