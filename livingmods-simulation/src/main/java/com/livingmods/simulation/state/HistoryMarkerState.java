package com.livingmods.simulation.state;

import com.livingmods.common.geo.BlockPos2;
import com.livingmods.common.id.HistoricalEventId;

/** Physicalization hook for meaningful history (ruins, monuments, battle sites, renamed places). */
public final class HistoryMarkerState {
    public enum Kind {
        RUIN,
        MONUMENT,
        RENAMED_PLACE,
        ABANDONED_ROAD,
        BATTLE_SITE
    }

    private final String id;
    private final HistoricalEventId sourceEvent;
    private final Kind kind;
    private final BlockPos2 location;
    private final String label;
    private boolean physicalized;

    public HistoryMarkerState(
            String id,
            HistoricalEventId sourceEvent,
            Kind kind,
            BlockPos2 location,
            String label
    ) {
        this.id = id;
        this.sourceEvent = sourceEvent;
        this.kind = kind;
        this.location = location;
        this.label = label;
        this.physicalized = false;
    }

    public String id() { return id; }
    public HistoricalEventId sourceEvent() { return sourceEvent; }
    public Kind kind() { return kind; }
    public BlockPos2 location() { return location; }
    public String label() { return label; }
    public boolean physicalized() { return physicalized; }
    public void setPhysicalized(boolean physicalized) { this.physicalized = physicalized; }
}
