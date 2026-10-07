package com.livingmods.neoforge.entity.ai;

import com.livingmods.common.model.ScheduleState;
import com.livingmods.neoforge.entity.CitizenEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.EnumSet;

/**
 * Pathfinding toward schedule destinations: home, work, market, sleep, guard patrol.
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

    private BlockPos guardPatrol(BlockPos origin) {
        // Simple gate patrol: walk a rectangle around spawn/home anchor.
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

    private static double speedFor(ScheduleState schedule) {
        return switch (schedule) {
            case COMMUTE, GUARD_DUTY, TRAVEL -> 0.9;
            case SLEEP, HOME -> 0.55;
            default -> 0.75;
        };
    }
}
