package com.livingmods.simulation.state;

import com.livingmods.common.geo.BlockPos2;
import com.livingmods.common.id.CitizenId;
import com.livingmods.common.id.HouseholdId;
import com.livingmods.common.id.MigrationGroupId;
import com.livingmods.common.id.SettlementId;

import java.util.ArrayList;
import java.util.List;

public final class MigrationGroupState {
    public enum Outcome {
        IN_TRANSIT,
        ABSORBED,
        TEMP_CAMP,
        RETURNING,
        FOUNDING
    }

    private final MigrationGroupId id;
    private final SettlementId source;
    private SettlementId destination;
    private final int population;
    private BlockPos2 position;
    private final String reasonKey;
    private boolean arrived;
    private Outcome outcome;
    private final List<CitizenId> citizenIds;
    private final List<HouseholdId> householdIds;
    private boolean refugee;

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
        this.outcome = Outcome.IN_TRANSIT;
        this.citizenIds = new ArrayList<>();
        this.householdIds = new ArrayList<>();
        this.refugee = reasonKey != null && (reasonKey.contains("war") || reasonKey.contains("famine")
                || reasonKey.contains("refugee"));
    }

    public MigrationGroupId id() { return id; }
    public SettlementId source() { return source; }
    public SettlementId destination() { return destination; }
    public void setDestination(SettlementId destination) { this.destination = destination; }
    public int population() { return population; }
    public BlockPos2 position() { return position; }
    public void setPosition(BlockPos2 position) { this.position = position; }
    public String reasonKey() { return reasonKey; }
    public boolean arrived() { return arrived; }
    public void setArrived(boolean arrived) { this.arrived = arrived; }
    public Outcome outcome() { return outcome; }
    public void setOutcome(Outcome outcome) { this.outcome = outcome; }
    public List<CitizenId> citizenIds() { return citizenIds; }
    public List<HouseholdId> householdIds() { return householdIds; }
    public boolean refugee() { return refugee; }
    public void setRefugee(boolean refugee) { this.refugee = refugee; }
}
