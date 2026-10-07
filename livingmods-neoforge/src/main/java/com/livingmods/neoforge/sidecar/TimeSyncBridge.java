package com.livingmods.neoforge.sidecar;

import com.livingmods.neoforge.LivingModsMod;
import com.livingmods.protocol.MessageType;
import com.livingmods.protocol.RequestPayloads;
import net.minecraft.server.level.ServerLevel;

/**
 * Periodic Minecraft → sidecar time sync. Detects jumps (sleep, /time set) and bounds send rate.
 */
public final class TimeSyncBridge {
    private static final long PERIODIC_INTERVAL_MS = 5_000L;
    private static final long JUMP_THRESHOLD_TICKS = 1_000L;

    private long lastSyncedGameTime = -1L;
    private long lastSendWallMillis;
    private boolean pendingJump;

    public void onServerTick(ServerLevel overworld, SidecarClient client) {
        if (client == null || !client.isReady()) {
            return;
        }
        long gameTime = overworld.getGameTime();
        long dayTime = overworld.getDayTime();
        long now = System.currentTimeMillis();

        boolean jumped = false;
        if (lastSyncedGameTime >= 0L) {
            long delta = gameTime - lastSyncedGameTime;
            if (delta < 0 || delta > JUMP_THRESHOLD_TICKS) {
                jumped = true;
                pendingJump = true;
            }
        }

        boolean due = lastSendWallMillis == 0L || (now - lastSendWallMillis) >= PERIODIC_INTERVAL_MS;
        if (!due && !jumped && !pendingJump) {
            return;
        }

        boolean sendJump = jumped || pendingJump;
        pendingJump = false;
        lastSendWallMillis = now;
        lastSyncedGameTime = gameTime;

        try {
            RequestPayloads.TimeSync sync = new RequestPayloads.TimeSync(dayTime, gameTime, sendJump);
            client.sendAsync(MessageType.TIME_SYNC, sync.encode()).exceptionally(ex -> {
                LivingModsMod.LOG.debug("TIME_SYNC failed: {}", ex.toString());
                return null;
            });
        } catch (Exception e) {
            LivingModsMod.LOG.debug("TIME_SYNC encode failed: {}", e.toString());
        }
    }

    public void reset() {
        lastSyncedGameTime = -1L;
        lastSendWallMillis = 0L;
        pendingJump = false;
    }
}
