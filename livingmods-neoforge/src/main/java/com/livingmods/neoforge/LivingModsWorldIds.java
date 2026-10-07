package com.livingmods.neoforge;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

public final class LivingModsWorldIds {
    private LivingModsWorldIds() {}

    public static UUID fromSeed(long seed) {
        return UUID.nameUUIDFromBytes((Long.toString(seed) + ":livingmods").getBytes(StandardCharsets.UTF_8));
    }

    public static UUID fromLevelName(String levelName) {
        return UUID.nameUUIDFromBytes((levelName + ":livingmods").getBytes(StandardCharsets.UTF_8));
    }
}
