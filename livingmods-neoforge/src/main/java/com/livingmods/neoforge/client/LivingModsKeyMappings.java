package com.livingmods.neoforge.client;

import com.livingmods.neoforge.LivingModsMod;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import org.lwjgl.glfw.GLFW;

public final class LivingModsKeyMappings {
    public static final String CATEGORY = "key.categories.livingmods";
    public static KeyMapping OPEN_MAP;
    public static KeyMapping OPEN_DASHBOARD;

    private LivingModsKeyMappings() {}

    public static void register(RegisterKeyMappingsEvent event) {
        OPEN_MAP = new KeyMapping(
                "key.livingmods.map",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_M,
                CATEGORY
        );
        OPEN_DASHBOARD = new KeyMapping(
                "key.livingmods.dashboard",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_F12,
                CATEGORY
        );
        event.register(OPEN_MAP);
        event.register(OPEN_DASHBOARD);
    }
}
