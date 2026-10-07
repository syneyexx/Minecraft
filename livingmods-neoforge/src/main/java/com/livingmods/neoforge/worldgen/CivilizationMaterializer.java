package com.livingmods.neoforge.worldgen;

import com.livingmods.common.geo.BoundingBox2;
import com.livingmods.worldgen.plan.ChunkCivilizationSlice;
import com.livingmods.worldgen.plan.PlannedBuilding;
import com.livingmods.worldgen.plan.PlannedRoad;
import com.livingmods.worldgen.plan.PlannedSettlement;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * Orchestrates physical materialization of a chunk slice. Chunk-order independent:
 * each structure places only the local slice that intersects the target chunk.
 * Must run on the server thread.
 */
public final class CivilizationMaterializer {
    private final RoadMaterializer roads = new RoadMaterializer();
    private final BridgeMaterializer bridges = new BridgeMaterializer();
    private final BuildingMaterializer buildings = new BuildingMaterializer();
    private final WallMaterializer walls = new WallMaterializer();
    private final ResourceSiteMaterializer resources = new ResourceSiteMaterializer();
    private final BanditCampMaterializer bandits = new BanditCampMaterializer();
    private final RuinMaterializer ruins = new RuinMaterializer();
    private final WizardTreesMaterializer wizardTrees = new WizardTreesMaterializer();

    public void materializeChunk(
            ServerLevel level,
            LevelChunk chunk,
            ChunkCivilizationSlice slice,
            ChunkMaterializationState state
    ) {
        if (!level.getServer().isSameThread()) {
            throw new IllegalStateException("LivingMods materialization must run on the server thread");
        }

        SafeChunkWriter writer = new SafeChunkWriter(level, chunk);
        BoundingBox2 chunkBox = BoundingBox2.of(
                chunk.getPos().x << 4, chunk.getPos().z << 4,
                (chunk.getPos().x << 4) + 15, (chunk.getPos().z << 4) + 15
        );

        for (PlannedRoad road : slice.roads()) {
            roads.materialize(level, writer, road);
            bridges.materialize(level, writer, road);
        }

        for (PlannedSettlement settlement : slice.settlements()) {
            if (settlement.underground()) {
                wizardTrees.materialize(level, writer, settlement);
                continue;
            }
            roads.materializeStreetNetwork(level, writer, settlement.streetNetwork(), settlement.cultureKey());
            for (PlannedBuilding building : settlement.buildings()) {
                if (!building.footprint().intersects(chunkBox)) {
                    continue;
                }
                buildings.materialize(level, writer, building, settlement, state);
            }
            if (settlement.walls()) {
                walls.materialize(level, writer, settlement);
            }
        }

        for (var ruin : slice.ruins()) {
            ruins.materialize(level, writer, ruin);
        }
        for (var site : slice.resourceSites()) {
            resources.materialize(level, writer, site);
        }
        for (var camp : slice.banditCamps()) {
            bandits.materialize(level, writer, camp);
        }
    }
}
