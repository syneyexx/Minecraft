package com.livingmods.common.model;

/** Player-facing emergent task lifecycle. Ordinals wire/save stable — append only. */
public enum TaskStatus {
    OPEN,
    DISCOVERED,
    ACCEPTED,
    COMPLETED,
    FAILED,
    ABANDONED,
    EXPIRED
}
