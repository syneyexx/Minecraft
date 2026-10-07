package com.livingmods.neoforge.client;

import com.livingmods.neoforge.worldgen.WorldPlanCache;
import com.livingmods.worldgen.plan.WorldPlan;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public final class MapScreen extends Screen {
    public MapScreen() {
        super(Component.literal("LivingMods Map"));
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics, mouseX, mouseY, partialTick);
        WorldPlan plan = WorldPlanCache.get();
        String summary = plan == null ? "World plan not loaded" :
                "Kingdoms: " + plan.kingdoms().size() + "  Settlements: " + plan.settlements().size();
        graphics.drawCenteredString(font, summary, width / 2, 20, 0xFFFFFF);
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
