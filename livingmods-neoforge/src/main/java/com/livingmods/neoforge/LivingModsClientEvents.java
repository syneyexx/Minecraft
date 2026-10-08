package com.livingmods.neoforge;

import com.livingmods.neoforge.client.ClientMapCache;
import com.livingmods.neoforge.client.DashboardScreen;
import com.livingmods.neoforge.client.LivingModsKeyMappings;
import com.livingmods.neoforge.client.MapScreen;
import com.livingmods.neoforge.network.CitizenInteractionPayloads;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.network.PacketDistributor;

@EventBusSubscriber(modid = LivingModsMod.MOD_ID, value = Dist.CLIENT)
public final class LivingModsClientEvents {
    private LivingModsClientEvents() {}

    @SubscribeEvent
    public static void registerKeys(RegisterKeyMappingsEvent event) {
        LivingModsKeyMappings.register(event);
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        ClientMapCache.clear();
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.screen != null) {
            return;
        }
        while (LivingModsKeyMappings.OPEN_MAP.consumeClick()) {
            mc.setScreen(new MapScreen());
        }
        while (LivingModsKeyMappings.OPEN_DASHBOARD.consumeClick()) {
            mc.setScreen(new DashboardScreen());
        }
        while (LivingModsKeyMappings.OPEN_JOURNAL.consumeClick()) {
            // C2S journal request → server queries canonical → S2C opens/updates journal.
            PacketDistributor.sendToServer(new CitizenInteractionPayloads.JournalRequest());
        }
    }
}
