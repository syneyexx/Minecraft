package com.livingmods.protocol;

import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

/**
 * Versioned IPC envelope. Compact binary — not human-readable JSON.
 */
public final class Envelope {
    private final int protocolVersion;
    private final MessageType type;
    private final int flags;
    private final long messageId;
    private final long requestId;
    private final long simulationTicks;
    private final UUID worldSessionId;
    private final byte[] payload;

    public Envelope(
            int protocolVersion,
            MessageType type,
            int flags,
            long messageId,
            long requestId,
            long simulationTicks,
            UUID worldSessionId,
            byte[] payload
    ) {
        this.protocolVersion = protocolVersion;
        this.type = Objects.requireNonNull(type);
        this.flags = flags;
        this.messageId = messageId;
        this.requestId = requestId;
        this.simulationTicks = simulationTicks;
        this.worldSessionId = worldSessionId;
        this.payload = payload == null ? new byte[0] : Arrays.copyOf(payload, payload.length);
    }

    public int protocolVersion() { return protocolVersion; }
    public MessageType type() { return type; }
    public int flags() { return flags; }
    public long messageId() { return messageId; }
    public long requestId() { return requestId; }
    public long simulationTicks() { return simulationTicks; }
    public UUID worldSessionId() { return worldSessionId; }
    public byte[] payload() { return Arrays.copyOf(payload, payload.length); }
    public int payloadLength() { return payload.length; }

    public boolean isError() {
        return type == MessageType.ERROR || (flags & 0x1) != 0;
    }

    public boolean isEvent() {
        return type == MessageType.EVENT;
    }
}
