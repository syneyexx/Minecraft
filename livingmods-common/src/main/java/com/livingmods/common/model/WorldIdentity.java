package com.livingmods.common.model;

import java.util.Objects;
import java.util.UUID;

/**
 * Persisted world identity (stable UUID + folder + seed). Handshake uses {@link WorldIdentityContract}.
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

    public WorldIdentityContract toContract(long worldPlanHash, int worldPlanRevision) {
        return WorldIdentityContract.of(worldUuid, minecraftSeed, worldPlanHash, worldPlanRevision);
    }
}
