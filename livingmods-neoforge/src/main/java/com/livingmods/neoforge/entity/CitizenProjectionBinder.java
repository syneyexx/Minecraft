package com.livingmods.neoforge.entity;

import com.livingmods.common.config.LivingModsConfig;
import com.livingmods.neoforge.LivingModsMod;
import net.minecraft.server.level.ServerLevel;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Spawns a bounded number of citizen entities near settlements. */
public final class CitizenProjectionBinder {
    private final LivingModsConfig config = LivingModsConfig.defaults();
    private final Set<UUID> spawned = new HashSet<>();

    public void project(ServerLevel level, List<ProjectionCandidate> candidates) {
        int cap = config.physicalCitizenProjectionCap();
        int count = 0;
        for (ProjectionCandidate c : candidates) {
            if (count >= cap) break;
            if (!spawned.add(c.citizenId())) continue;
            CitizenEntity entity = new CitizenEntity(LivingModsEntities.CITIZEN.get(), level);
            if (entity == null) continue;
            entity.moveTo(c.x() + 0.5, c.y(), c.z() + 0.5, level.random.nextFloat() * 360f, 0);
            entity.bindCitizen(c.citizenId(), c.displayName());
            level.addFreshEntity(entity);
            count++;
        }
    }

    public record ProjectionCandidate(UUID citizenId, String displayName, int x, int y, int z) {}
}
