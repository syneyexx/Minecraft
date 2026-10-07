package com.livingmods.common.model;

import com.livingmods.common.version.LivingModsVersions;

import java.util.Objects;
import java.util.UUID;

/**
 * Handshake / process-launch identity contract. Prefer this over deriving world identity from seed alone.
 */
public record WorldIdentityContract(
        UUID worldId,
        long minecraftSeed,
        int worldgenVersion,
        long worldPlanHash,
        int worldPlanRevision,
        int canonicalSchemaVersion,
        int protocolVersion
) {
    public WorldIdentityContract {
        Objects.requireNonNull(worldId, "worldId");
    }

    public static WorldIdentityContract of(
            UUID worldId,
            long minecraftSeed,
            long worldPlanHash,
            int worldPlanRevision
    ) {
        return new WorldIdentityContract(
                worldId,
                minecraftSeed,
                LivingModsVersions.WORLDGEN_VERSION,
                worldPlanHash,
                worldPlanRevision,
                LivingModsVersions.CANONICAL_SAVE_SCHEMA,
                LivingModsVersions.PROTOCOL_VERSION
        );
    }
}
