package com.livingmods.neoforge.sidecar;

/** Sidecar IPC connection lifecycle states. */
public enum ConnectionState {
    STARTING,
    CONNECTING,
    READY,
    DEGRADED,
    RECONNECTING,
    STOPPING,
    STOPPED,
    FAILED
}
