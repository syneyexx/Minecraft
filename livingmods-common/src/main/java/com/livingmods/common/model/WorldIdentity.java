package com.livingmods.common.model;

import java.util.Objects;
import java.util.UUID;

/**
 * Uniquely identifies a Minecraft world so two worlds never share simulation state.
 */
public record WorldIdentity(
        UUID worldUuid,
        String folderName,
        long minecraftSeed
) {
    public WorldIdentity {
        Objects.requireNonNull(worldUuid, "worldUuid");
        Objects.requireNonNull(folderName, "folderName");
    }

    public String storageKey() {
        return worldUuid.toString();
    }
}
