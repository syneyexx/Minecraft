package com.livingmods.neoforge;

import com.livingmods.neoforge.client.DashboardScreen;
import com.livingmods.neoforge.client.LivingModsKeyMappings;
import com.livingmods.neoforge.client.MapScreen;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;

@EventBusSubscriber(modid = LivingModsMod.MOD_ID, value = Dist.CLIENT)
public final class LivingModsClientEvents {
    private LivingModsClientEvents() {}

    @SubscribeEvent
    public static void registerKeys(RegisterKeyMappingsEvent event) {
        LivingModsKeyMappings.register(event);
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
    }
}
