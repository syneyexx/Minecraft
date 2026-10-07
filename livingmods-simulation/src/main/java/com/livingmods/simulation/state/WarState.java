package com.livingmods.simulation.state;

import com.livingmods.common.id.KingdomId;
import com.livingmods.common.id.WarId;

import java.util.HashSet;
import java.util.Set;

public final class WarState {
    private final WarId id;
    private final KingdomId aggressor;
    private final KingdomId defender;
    private final long startedDay;
    private boolean active;
    private final Set<KingdomId> participants;

    public WarState(WarId id, KingdomId aggressor, KingdomId defender, long startedDay) {
        this.id = id;
        this.aggressor = aggressor;
        this.defender = defender;
        this.startedDay = startedDay;
        this.active = true;
        this.participants = new HashSet<>();
        participants.add(aggressor);
        participants.add(defender);
    }

    public WarId id() { return id; }
    public KingdomId aggressor() { return aggressor; }
    public KingdomId defender() { return defender; }
    public long startedDay() { return startedDay; }
    public boolean active() { return active; }
    public void setActive(boolean active) { this.active = active; }
    public Set<KingdomId> participants() { return participants; }
}
