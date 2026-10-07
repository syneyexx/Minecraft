package com.livingmods.worldgen.plan;

import com.livingmods.common.geo.BoundingBox2;
import com.livingmods.common.id.DistrictId;
import com.livingmods.common.id.LotId;
import com.livingmods.common.id.SettlementId;
import com.livingmods.common.id.StructureId;
import com.livingmods.common.model.BuildingRole;
import com.livingmods.common.model.WealthClass;

import java.util.List;

/**
 * Planned building with architecture-grammar metadata preserved for physical materialization.
 */
public record PlannedBuilding(
        StructureId id,
        LotId lotId,
        SettlementId settlementId,
        DistrictId districtId,
        BuildingRole role,
        WealthClass wealthClass,
        BoundingBox2 footprint,
        int rotationY,
        int foundationY,
        String cultureKey,
        String paletteKey,
        int seed,
        int floorCount,
        boolean hasBasement,
        boolean hasAttic,
        List<String> roomTags,
        List<String> wallSegments,
        List<String> windowPositions,
        String entranceFacing,
        int capacity,
        int workSlots,
        int residentialSlots
) {
    public PlannedBuilding {
        roomTags = List.copyOf(roomTags);
        wallSegments = List.copyOf(wallSegments);
        windowPositions = List.copyOf(windowPositions);
        if (entranceFacing == null || entranceFacing.isBlank()) {
            entranceFacing = "south";
        }
        floorCount = Math.max(1, floorCount);
        capacity = Math.max(0, capacity);
        workSlots = Math.max(0, workSlots);
        residentialSlots = Math.max(0, residentialSlots);
    }
}
