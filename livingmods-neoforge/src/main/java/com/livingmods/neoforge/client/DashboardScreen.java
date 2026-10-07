package com.livingmods.neoforge.client;

import com.livingmods.neoforge.sidecar.SidecarClient;
import com.livingmods.neoforge.sidecar.SidecarProcessManager;
import com.livingmods.neoforge.sidecar.WorldSessionLifecycle;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.Map;

public final class DashboardScreen extends Screen {
    public DashboardScreen() {
        super(Component.literal("LivingMods Dashboard"));
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics, mouseX, mouseY, partialTick);
        String sidecar = SidecarProcessManager.enabled() ? "Sidecar: enabled" : "Sidecar: disabled";
        graphics.drawCenteredString(font, sidecar, width / 2, 24, 0xA0FFA0);
        SidecarClient client = WorldSessionLifecycle.activeClient();
        int y = 44;
        if (client != null) {
            for (Map.Entry<String, String> e : client.diagnostics().entrySet()) {
                graphics.drawCenteredString(font, e.getKey() + "=" + e.getValue(), width / 2, y, 0xDDDDDD);
                y += 12;
                if (y > height - 48) {
                    break;
                }
            }
        } else {
            graphics.drawCenteredString(font, "No active sidecar client", width / 2, y, 0xAAAAAA);
        }
        graphics.drawCenteredString(font, "Press Esc to close", width / 2, height - 30, 0xCCCCCC);
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
