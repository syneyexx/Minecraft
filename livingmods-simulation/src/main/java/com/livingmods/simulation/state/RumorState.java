package com.livingmods.simulation.state;

import com.livingmods.common.geo.BlockPos2;
import com.livingmods.common.id.HistoricalEventId;
import com.livingmods.common.id.SettlementId;
import com.livingmods.common.time.SimulationTime;

import java.util.Optional;

/** Citizen-learnable rumor derived from a meaningful historical event. */
public final class RumorState {
    private final String id;
    private final HistoricalEventId sourceEvent;
    private final String subject;
    private final SettlementId origin;
    private final Optional<BlockPos2> location;
    private double confidence;
    private final SimulationTime born;
    private int ageHours;

    public RumorState(
            String id,
            HistoricalEventId sourceEvent,
            String subject,
            SettlementId origin,
            Optional<BlockPos2> location,
            double confidence,
            SimulationTime born
    ) {
        this.id = id;
        this.sourceEvent = sourceEvent;
        this.subject = subject;
        this.origin = origin;
        this.location = location;
        this.confidence = Math.max(0, Math.min(1, confidence));
        this.born = born;
        this.ageHours = 0;
    }

    public String id() { return id; }
    public HistoricalEventId sourceEvent() { return sourceEvent; }
    public String subject() { return subject; }
    public SettlementId origin() { return origin; }
    public Optional<BlockPos2> location() { return location; }
    public double confidence() { return confidence; }
    public void setConfidence(double confidence) { this.confidence = Math.max(0, Math.min(1, confidence)); }
    public SimulationTime born() { return born; }
    public int ageHours() { return ageHours; }
    public void tickAge() { ageHours++; }
}
