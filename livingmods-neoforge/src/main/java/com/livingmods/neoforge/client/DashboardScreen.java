package com.livingmods.neoforge.client;

import com.livingmods.neoforge.sidecar.SidecarProcessManager;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public final class DashboardScreen extends Screen {
    public DashboardScreen() {
        super(Component.literal("LivingMods Dashboard"));
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics, mouseX, mouseY, partialTick);
        String sidecar = SidecarProcessManager.enabled() ? "Sidecar: enabled" : "Sidecar: disabled";
        graphics.drawCenteredString(font, sidecar, width / 2, 24, 0xA0FFA0);
        graphics.drawCenteredString(font, "Press Esc to close", width / 2, height - 30, 0xCCCCCC);
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
