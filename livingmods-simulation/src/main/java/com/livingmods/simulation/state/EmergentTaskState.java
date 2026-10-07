package com.livingmods.simulation.state;

import com.livingmods.common.id.SettlementId;
import com.livingmods.common.model.EmergentTaskType;
import com.livingmods.common.time.SimulationTime;

import java.util.Map;
import java.util.UUID;

/** Player-facing task spawned from a real canonical problem. */
public final class EmergentTaskState {
    private final UUID id;
    private final EmergentTaskType type;
    private final SettlementId settlementId;
    private final String title;
    private final String description;
    private final SimulationTime created;
    private final Map<String, String> problemTags;
    private boolean completed;
    private boolean failed;

    public EmergentTaskState(
            UUID id,
            EmergentTaskType type,
            SettlementId settlementId,
            String title,
            String description,
            SimulationTime created,
            Map<String, String> problemTags
    ) {
        this.id = id;
        this.type = type;
        this.settlementId = settlementId;
        this.title = title;
        this.description = description;
        this.created = created;
        this.problemTags = Map.copyOf(problemTags);
        this.completed = false;
        this.failed = false;
    }

    public UUID id() { return id; }
    public EmergentTaskType type() { return type; }
    public SettlementId settlementId() { return settlementId; }
    public String title() { return title; }
    public String description() { return description; }
    public SimulationTime created() { return created; }
    public Map<String, String> problemTags() { return problemTags; }
    public boolean completed() { return completed; }
    public void setCompleted(boolean completed) { this.completed = completed; }
    public boolean failed() { return failed; }
    public void setFailed(boolean failed) { this.failed = failed; }
    public boolean open() { return !completed && !failed; }
}
