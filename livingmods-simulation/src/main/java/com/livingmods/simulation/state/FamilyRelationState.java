package com.livingmods.simulation.state;

import com.livingmods.common.id.CitizenId;
import com.livingmods.common.model.FamilyRelationType;

import java.util.Objects;
import java.util.UUID;

/** Persisted directed family-graph edge. */
public final class FamilyRelationState {
    private final UUID id;
    private final CitizenId from;
    private final CitizenId to;
    private final FamilyRelationType type;
    private boolean active;

    public FamilyRelationState(UUID id, CitizenId from, CitizenId to, FamilyRelationType type, boolean active) {
        this.id = Objects.requireNonNull(id);
        this.from = Objects.requireNonNull(from);
        this.to = Objects.requireNonNull(to);
        this.type = Objects.requireNonNull(type);
        this.active = active;
    }

    public static FamilyRelationState of(CitizenId from, CitizenId to, FamilyRelationType type) {
        UUID id = new UUID(
                from.value().getMostSignificantBits() ^ to.value().getMostSignificantBits() ^ type.ordinal(),
                from.value().getLeastSignificantBits() ^ to.value().getLeastSignificantBits() ^ (type.ordinal() * 31L));
        return new FamilyRelationState(id, from, to, type, true);
    }

    public UUID id() { return id; }
    public CitizenId from() { return from; }
    public CitizenId to() { return to; }
    public FamilyRelationType type() { return type; }
    public boolean active() { return active; }
    public void setActive(boolean active) { this.active = active; }
}
