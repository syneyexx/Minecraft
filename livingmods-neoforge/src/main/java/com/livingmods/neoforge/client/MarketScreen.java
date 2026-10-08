package com.livingmods.neoforge.client;

import com.livingmods.common.model.ResourceType;
import com.livingmods.neoforge.network.CitizenInteractionPayloads;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Settlement market UI — prices/stock from canonical market; trades go through server. */
public final class MarketScreen extends Screen {
    private static final ResourceType[] TRADEABLE = {
            ResourceType.GRAIN, ResourceType.FOOD, ResourceType.WOOD, ResourceType.STONE,
            ResourceType.IRON, ResourceType.MEDICINE, ResourceType.TOOLS, ResourceType.WEAPONS,
            ResourceType.CLOTH, ResourceType.COAL
    };

    private final UUID settlementId;
    private final Map<String, String> market;
    private int selected;
    private int amount = 1;

    public MarketScreen(CitizenInteractionPayloads.MarketScreenData data) {
        super(Component.literal("Market"));
        this.settlementId = data.settlementId();
        this.market = data.market();
    }

    @Override
    protected void init() {
        addRenderableWidget(Button.builder(Component.literal("-"), b -> amount = Math.max(1, amount - 1))
                .bounds(width / 2 - 60, height - 52, 24, 18).build());
        addRenderableWidget(Button.builder(Component.literal("+"), b -> amount = Math.min(64, amount + 1))
                .bounds(width / 2 - 30, height - 52, 24, 18).build());
        addRenderableWidget(Button.builder(Component.literal("Buy"), b -> {
            ResourceType r = TRADEABLE[Math.max(0, Math.min(TRADEABLE.length - 1, selected))];
            PacketDistributor.sendToServer(new CitizenInteractionPayloads.MarketTransaction(
                    settlementId, r.name(), amount, true));
        }).bounds(width / 2 + 10, height - 52, 48, 18).build());
        addRenderableWidget(Button.builder(Component.literal("Sell"), b -> {
            ResourceType r = TRADEABLE[Math.max(0, Math.min(TRADEABLE.length - 1, selected))];
            PacketDistributor.sendToServer(new CitizenInteractionPayloads.MarketTransaction(
                    settlementId, r.name(), amount, false));
        }).bounds(width / 2 + 64, height - 52, 48, 18).build());
        addRenderableWidget(Button.builder(Component.literal("Close"), b -> onClose())
                .bounds(width - 70, height - 28, 54, 18).build());
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(font, "Settlement Market  (pay with gold ingots)", width / 2, 16, 0xF0E6D2);
        graphics.drawString(font, "Crisis: " + market.getOrDefault("crisis", "—"), 24, 36, 0xCCCCCC);
        int y = 56;
        for (int i = 0; i < TRADEABLE.length; i++) {
            ResourceType r = TRADEABLE[i];
            String price = market.getOrDefault("price_" + r.name(), "—");
            String stock = market.getOrDefault("stock_" + r.name(), "—");
            int color = i == selected ? 0xFFE6B3 : 0xDDDDDD;
            graphics.drawString(font, (i == selected ? "> " : "  ") + r.name()
                    + "  buy@" + price + "  stock=" + stock, 28, y, color);
            y += 12;
        }
        graphics.drawCenteredString(font, "Amount: " + amount, width / 2, height - 72, 0xA0D0A0);
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && mouseY >= 56 && mouseY < 56 + TRADEABLE.length * 12) {
            selected = Math.max(0, Math.min(TRADEABLE.length - 1, ((int) mouseY - 56) / 12));
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
