package com.livingmods.simulation.physical;

import com.livingmods.common.geo.BlockPos2;
import com.livingmods.common.geo.BoundingBox2;
import com.livingmods.common.id.SettlementId;

import java.util.ArrayList;
import java.util.List;

/**
 * Dynamic settlement footprint after initial WorldPlan generation
 * (player-founded realms and organic expansion).
 */
public final class DynamicSettlementGeometry {
    private final SettlementId settlementId;
    private BlockPos2 center;
    private BoundingBox2 boundary;
    private final List<BlockPos2> expansionAnchors;
    private final List<BlockPos2> roadAnchors;
    private boolean playerFounded;
    private int expansionGeneration;

    public DynamicSettlementGeometry(
            SettlementId settlementId,
            BlockPos2 center,
            BoundingBox2 boundary,
            boolean playerFounded
    ) {
        this.settlementId = settlementId;
        this.center = center;
        this.boundary = boundary;
        this.expansionAnchors = new ArrayList<>();
        this.roadAnchors = new ArrayList<>();
        this.playerFounded = playerFounded;
        this.expansionGeneration = 0;
    }

    public SettlementId settlementId() { return settlementId; }
    public BlockPos2 center() { return center; }
    public void setCenter(BlockPos2 center) { this.center = center; }
    public BoundingBox2 boundary() { return boundary; }
    public void setBoundary(BoundingBox2 boundary) { this.boundary = boundary; }
    public List<BlockPos2> expansionAnchors() { return expansionAnchors; }
    public List<BlockPos2> roadAnchors() { return roadAnchors; }
    public boolean playerFounded() { return playerFounded; }
    public void setPlayerFounded(boolean playerFounded) { this.playerFounded = playerFounded; }
    public int expansionGeneration() { return expansionGeneration; }
    public void bumpExpansionGeneration() { expansionGeneration++; }
}
