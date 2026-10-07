package com.livingmods.protocol;

import com.livingmods.common.version.LivingModsVersions;

public final class ProtocolConstants {
    public static final int MAGIC = 0x4C4D4453; // "LMDS"
    public static final int PROTOCOL_VERSION = LivingModsVersions.PROTOCOL_VERSION;
    public static final int HEADER_SIZE = 40; // magic+version+type+flags+msgId+requestId+simTicks+payloadLen

    private ProtocolConstants() {}
}
