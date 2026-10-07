package com.livingmods.simulation.state;

import com.livingmods.common.geo.BlockPos2;
import com.livingmods.common.id.MigrationGroupId;
import com.livingmods.common.id.SettlementId;

public final class MigrationGroupState {
    private final MigrationGroupId id;
    private final SettlementId source;
    private final SettlementId destination;
    private final int population;
    private BlockPos2 position;
    private final String reasonKey;
    private boolean arrived;

    public MigrationGroupState(
            MigrationGroupId id,
            SettlementId source,
            SettlementId destination,
            int population,
            BlockPos2 position,
            String reasonKey
    ) {
        this.id = id;
        this.source = source;
        this.destination = destination;
        this.population = population;
        this.position = position;
        this.reasonKey = reasonKey;
        this.arrived = false;
    }

    public MigrationGroupId id() { return id; }
    public SettlementId source() { return source; }
    public SettlementId destination() { return destination; }
    public int population() { return population; }
    public BlockPos2 position() { return position; }
    public void setPosition(BlockPos2 position) { this.position = position; }
    public String reasonKey() { return reasonKey; }
    public boolean arrived() { return arrived; }
    public void setArrived(boolean arrived) { this.arrived = arrived; }
}
