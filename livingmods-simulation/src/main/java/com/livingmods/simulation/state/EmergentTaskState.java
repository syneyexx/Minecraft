package com.livingmods.simulation.state;

import com.livingmods.common.id.PlayerId;
import com.livingmods.common.id.SettlementId;
import com.livingmods.common.model.EmergentTaskType;
import com.livingmods.common.model.TaskStatus;
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
    private TaskStatus status;
    private PlayerId acceptedBy;
    private SimulationTime acceptedAt;
    private String progressNote;

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
        this.status = TaskStatus.OPEN;
        this.acceptedBy = null;
        this.acceptedAt = null;
        this.progressNote = "";
    }

    public UUID id() { return id; }
    public EmergentTaskType type() { return type; }
    public SettlementId settlementId() { return settlementId; }
    public String title() { return title; }
    public String description() { return description; }
    public SimulationTime created() { return created; }
    public Map<String, String> problemTags() { return problemTags; }

    public TaskStatus status() { return status; }
    public void setStatus(TaskStatus status) {
        this.status = status == null ? TaskStatus.OPEN : status;
    }

    public PlayerId acceptedBy() { return acceptedBy; }
    public SimulationTime acceptedAt() { return acceptedAt; }
    public String progressNote() { return progressNote == null ? "" : progressNote; }
    public void setProgressNote(String note) {
        this.progressNote = note == null ? "" : (note.length() > 256 ? note.substring(0, 256) : note);
    }

    public void markDiscovered() {
        if (status == TaskStatus.OPEN) {
            status = TaskStatus.DISCOVERED;
        }
    }

    public boolean accept(PlayerId player, SimulationTime when) {
        if (player == null || !available()) return false;
        if (acceptedBy != null && !acceptedBy.equals(player)) return false;
        acceptedBy = player;
        acceptedAt = when;
        status = TaskStatus.ACCEPTED;
        return true;
    }

    public void abandon(PlayerId player) {
        if (acceptedBy == null || !acceptedBy.equals(player)) return;
        if (status != TaskStatus.ACCEPTED) return;
        status = TaskStatus.ABANDONED;
        acceptedBy = null;
        acceptedAt = null;
    }

    /** @deprecated use {@link #status()} == COMPLETED */
    @Deprecated
    public boolean completed() { return status == TaskStatus.COMPLETED; }

    public void setCompleted(boolean completed) {
        if (completed) {
            status = TaskStatus.COMPLETED;
        } else if (status == TaskStatus.COMPLETED) {
            status = acceptedBy == null ? TaskStatus.OPEN : TaskStatus.ACCEPTED;
        }
    }

    /** @deprecated use {@link #status()} == FAILED */
    @Deprecated
    public boolean failed() {
        return status == TaskStatus.FAILED || status == TaskStatus.EXPIRED;
    }

    public void setFailed(boolean failed) {
        if (failed) status = TaskStatus.FAILED;
    }

    public boolean open() {
        return status == TaskStatus.OPEN
                || status == TaskStatus.DISCOVERED
                || status == TaskStatus.ACCEPTED;
    }

    public boolean available() {
        return status == TaskStatus.OPEN || status == TaskStatus.DISCOVERED;
    }

    public boolean assignedTo(PlayerId player) {
        return player != null && acceptedBy != null && acceptedBy.equals(player)
                && status == TaskStatus.ACCEPTED;
    }

    /** Persistence restore — bypasses availability checks. */
    public void restoreAssignment(TaskStatus status, PlayerId acceptedBy, SimulationTime acceptedAt, String progressNote) {
        this.status = status == null ? TaskStatus.OPEN : status;
        this.acceptedBy = acceptedBy;
        this.acceptedAt = acceptedAt;
        setProgressNote(progressNote);
    }
}
