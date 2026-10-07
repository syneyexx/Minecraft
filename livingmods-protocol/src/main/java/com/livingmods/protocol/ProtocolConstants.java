package com.livingmods.protocol;

import com.livingmods.common.version.LivingModsVersions;

public final class ProtocolConstants {
    public static final int MAGIC = 0x4C4D4453; // "LMDS"
    public static final int PROTOCOL_VERSION = LivingModsVersions.PROTOCOL_VERSION;
    /**
     * Fixed header size before payload bytes:
     * 4×int (magic, version, type, flags) + 3×long (msgId, requestId, simTicks) + UUID(16) + payloadLen(int)
     * = 16 + 24 + 16 + 4 = 60.
     */
    public static final int HEADER_SIZE = 60;
    public static final int MAX_STRING_LENGTH = 65_536;

    private ProtocolConstants() {}
}
