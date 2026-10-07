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
        /** Approximate border polygon derived from {@link com.livingmods.worldgen.territory.TerritoryMap}. */
        List<BlockPos2> territoryPolygon,
        /** Kingdoms sharing a territory border (persisted adjacency graph). */
        List<KingdomId> adjacentKingdomIds,
        boolean underground,
        String religionKey
) {
    public PlannedKingdom {
        settlementIds = List.copyOf(settlementIds);
        territoryPolygon = List.copyOf(territoryPolygon);
        adjacentKingdomIds = List.copyOf(adjacentKingdomIds);
    }

    public PlannedKingdom withSettlements(List<SettlementId> ids) {
        return new PlannedKingdom(
                id, name, cultureId, cultureKey, governmentType, capitalId, capitalCenter,
                ids, territoryPolygon, adjacentKingdomIds, underground, religionKey
        );
    }

    public PlannedKingdom withTerritory(List<BlockPos2> polygon, List<KingdomId> adjacent) {
        return new PlannedKingdom(
                id, name, cultureId, cultureKey, governmentType, capitalId, capitalCenter,
                settlementIds, polygon, adjacent, underground, religionKey
        );
    }
}
