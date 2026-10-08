package com.livingmods.neoforge.entity.ai;

import com.livingmods.common.model.BuildingRole;
import com.livingmods.common.model.ScheduleState;
import com.livingmods.neoforge.entity.CitizenEntity;
import com.livingmods.neoforge.worldgen.LivingModsStructureIndex;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.EnumSet;

/**
 * Pathfinding toward schedule destinations using structure index POIs when available.
 */
public final class CitizenScheduleGoal extends Goal {
    private final CitizenEntity citizen;
    private int recalcCooldown;

    public CitizenScheduleGoal(CitizenEntity citizen) {
        this.citizen = citizen;
        setFlags(EnumSet.of(Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        return citizen.isBound() && !citizen.isPassenger();
    }

    @Override
    public void tick() {
        if (--recalcCooldown > 0) {
            return;
        }
        recalcCooldown = 40;
        BlockPos dest = destination();
        if (dest == null) {
            return;
        }
        PathNavigation nav = citizen.getNavigation();
        if (nav.isDone() || citizen.distanceToSqr(dest.getX() + 0.5, dest.getY(), dest.getZ() + 0.5) > 4.0) {
            nav.moveTo(dest.getX() + 0.5, dest.getY(), dest.getZ() + 0.5, speedFor(citizen.schedule()));
        }
    }

    private BlockPos destination() {
        if (citizen.hasNavigationTarget()) {
            return BlockPos.containing(citizen.targetX(), citizen.targetY(), citizen.targetZ());
        }
        BlockPos origin = citizen.blockPosition();
        ScheduleState schedule = citizen.schedule();
        BlockPos poi = poiFor(schedule, origin);
        if (poi != null) {
            return poi;
        }
        // Last-resort local offsets only when no structure metadata is available.
        return switch (schedule) {
            case HOME, SLEEP -> offset(origin, 0, 0);
            case WORK, COMMUTE -> offset(origin, 8, 4);
            case MARKET -> offset(origin, -6, 10);
            case SOCIAL -> offset(origin, 4, -8);
            case RELIGIOUS -> offset(origin, -10, -4);
            case GUARD_DUTY -> guardPatrol(origin);
            case TRAVEL -> offset(origin, 16, 0);
        };
    }

    private BlockPos poiFor(ScheduleState schedule, BlockPos origin) {
        if (!(citizen.level() instanceof ServerLevel level)) {
            return null;
        }
        LivingModsStructureIndex index = LivingModsStructureIndex.get(level);
        BuildingRole role = switch (schedule) {
            case HOME, SLEEP -> BuildingRole.HOUSE;
            case WORK, COMMUTE -> BuildingRole.WORKSHOP;
            case MARKET -> BuildingRole.MARKET_STALL;
            case SOCIAL -> BuildingRole.TAVERN;
            case RELIGIOUS -> BuildingRole.TEMPLE;
            case GUARD_DUTY -> BuildingRole.GUARDHOUSE;
            case TRAVEL -> BuildingRole.GATEHOUSE;
        };
        return index.findNearest(role, origin.getX(), origin.getZ(), 96)
                .map(e -> {
                    int x = (e.minX() + e.maxX()) / 2;
                    int z = (e.minZ() + e.maxZ()) / 2;
                    int y = e.foundationY() > 0 ? e.foundationY() + 1
                            : level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                    return new BlockPos(x, y, z);
                })
                .orElseGet(() -> {
                    if (schedule == ScheduleState.HOME || schedule == ScheduleState.SLEEP) {
                        return index.findNearest(BuildingRole.TOWNHOUSE, origin.getX(), origin.getZ(), 96)
                                .map(e -> new BlockPos(
                                        (e.minX() + e.maxX()) / 2,
                                        e.foundationY() > 0 ? e.foundationY() + 1
                                                : level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                                                (e.minX() + e.maxX()) / 2, (e.minZ() + e.maxZ()) / 2),
                                        (e.minZ() + e.maxZ()) / 2))
                                .orElse(null);
                    }
                    if (schedule == ScheduleState.MARKET) {
                        return index.findNearest(BuildingRole.MARKET_HALL, origin.getX(), origin.getZ(), 128)
                                .map(e -> new BlockPos(
                                        (e.minX() + e.maxX()) / 2,
                                        e.foundationY() > 0 ? e.foundationY() + 1
                                                : level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                                                (e.minX() + e.maxX()) / 2, (e.minZ() + e.maxZ()) / 2),
                                        (e.minZ() + e.maxZ()) / 2))
                                .orElse(null);
                    }
                    return null;
                });
    }

    private BlockPos guardPatrol(BlockPos origin) {
        if (citizen.level() instanceof ServerLevel level) {
            var gate = LivingModsStructureIndex.get(level)
                    .findNearest(BuildingRole.GATEHOUSE, origin.getX(), origin.getZ(), 128);
            if (gate.isPresent()) {
                var e = gate.get();
                int cx = (e.minX() + e.maxX()) / 2;
                int cz = (e.minZ() + e.maxZ()) / 2;
                long t = citizen.level().getGameTime() / 80;
                int phase = (int) (t % 4);
                return switch (phase) {
                    case 0 -> safe(level, cx + 6, cz);
                    case 1 -> safe(level, cx + 6, cz + 6);
                    case 2 -> safe(level, cx, cz + 6);
                    default -> safe(level, cx, cz);
                };
            }
        }
        long t = citizen.level().getGameTime() / 80;
        int phase = (int) (t % 4);
        return switch (phase) {
            case 0 -> offset(origin, 12, 0);
            case 1 -> offset(origin, 12, 12);
            case 2 -> offset(origin, 0, 12);
            default -> offset(origin, 0, 0);
        };
    }

    private BlockPos offset(BlockPos origin, int dx, int dz) {
        int x = origin.getX() + dx;
        int z = origin.getZ() + dz;
        int y = citizen.level().getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        return new BlockPos(x, y, z);
    }

    private static BlockPos safe(ServerLevel level, int x, int z) {
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        return new BlockPos(x, y, z);
    }

    private static double speedFor(ScheduleState schedule) {
        return switch (schedule) {
            case COMMUTE, GUARD_DUTY, TRAVEL -> 0.9;
            case SLEEP, HOME -> 0.55;
            default -> 0.75;
        };
    }
}
