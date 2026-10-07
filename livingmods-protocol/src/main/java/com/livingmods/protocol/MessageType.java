package com.livingmods.protocol;

public enum MessageType {
    // Control
    HANDSHAKE_REQUEST(1),
    HANDSHAKE_RESPONSE(2),
    HEARTBEAT(3),
    ERROR(4),
    SAVE_REQUEST(5),
    SAVE_RESPONSE(6),
    SHUTDOWN(7),
    TIME_SYNC(8),

    // Requests from Minecraft
    GET_SETTLEMENT_SNAPSHOT(20),
    GET_NEARBY_CITIZENS(21),
    GET_PHYSICAL_PROJECTION_PLAN(22),
    GET_MAP_OVERLAY(23),
    GET_DIALOGUE_CONTEXT(24),
    GET_MARKET_STATE(25),
    GET_KINGDOM_SUMMARY(26),
    GET_CONSTRUCTION_PLAN(27),
    GET_WORLD_SUMMARY(28),
    LOCATE(29),
    REPORT_PHYSICAL_OUTCOME(30),
    SUBSCRIBE_REGION(31),
    UNSUBSCRIBE_REGION(32),
    PLAYER_ACTION(33),

    // Responses
    RESPONSE(50),

    // Events from sidecar
    EVENT(100);

    private final int code;

    MessageType(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }

    public static MessageType fromCode(int code) {
        for (MessageType t : values()) {
            if (t.code == code) return t;
        }
        throw new IllegalArgumentException("Unknown message type: " + code);
    }
}
