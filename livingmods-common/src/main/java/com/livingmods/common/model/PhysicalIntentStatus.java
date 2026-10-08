package com.livingmods.common.model;

/**
 * Explicit lifecycle for physical intents. Transitions are controlled —
 * never inferred from missing map entries.
 */
public enum PhysicalIntentStatus {
    PLANNED,
    READY,
    MATERIALIZING,
    MATERIALIZED,
    BLOCKED,
    FAILED_RETRYABLE,
    FAILED_TERMINAL,
    SUPERSEDED,
    REMOVED;

    public boolean isTerminal() {
        return this == MATERIALIZED
                || this == FAILED_TERMINAL
                || this == SUPERSEDED
                || this == REMOVED;
    }

    public boolean isActive() {
        return this == PLANNED
                || this == READY
                || this == MATERIALIZING
                || this == BLOCKED
                || this == FAILED_RETRYABLE;
    }

    public boolean canTransitionTo(PhysicalIntentStatus next) {
        if (next == null || next == this) {
            return false;
        }
        return switch (this) {
            case PLANNED -> next == READY || next == BLOCKED || next == SUPERSEDED || next == REMOVED;
            case READY -> next == MATERIALIZING || next == BLOCKED || next == FAILED_RETRYABLE
                    || next == SUPERSEDED || next == REMOVED;
            case MATERIALIZING -> next == MATERIALIZED || next == BLOCKED || next == FAILED_RETRYABLE
                    || next == FAILED_TERMINAL || next == SUPERSEDED;
            case BLOCKED -> next == READY || next == FAILED_TERMINAL || next == SUPERSEDED || next == REMOVED;
            case FAILED_RETRYABLE -> next == READY || next == FAILED_TERMINAL || next == SUPERSEDED || next == REMOVED;
            case MATERIALIZED, FAILED_TERMINAL, SUPERSEDED, REMOVED -> false;
        };
    }
}
