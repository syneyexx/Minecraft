package com.livingmods.neoforge.client;

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

/** Presentation-only citizen interaction screen. Server decides topics/actions/text. */
public final class CitizenDialogueScreen extends Screen {
    private final UUID sessionId;
    private final String citizenName;
    private final String profession;
    private final String settlementName;
    private final String kingdomName;
    private final String attitude;
    private final String standing;
    private final String legalStatus;
    private final List<String> lines = new ArrayList<>();
    private final List<String> actions = new ArrayList<>();
    private int scroll;

    public CitizenDialogueScreen(CitizenInteractionPayloads.OpenScreen payload) {
        super(Component.literal("Conversation"));
        this.sessionId = payload.sessionId();
        this.citizenName = payload.citizenName();
        this.profession = payload.profession();
        this.settlementName = payload.settlementName();
        this.kingdomName = payload.kingdomName();
        this.attitude = payload.attitude();
        this.standing = payload.standing();
        this.legalStatus = payload.legalStatus();
        this.lines.addAll(payload.lines());
        this.actions.addAll(payload.actions());
    }

    public void applyDialogueUpdate(CitizenInteractionPayloads.DialogueUpdate update) {
        lines.clear();
        lines.addAll(update.lines());
        scroll = 0;
    }

    @Override
    protected void init() {
        clearWidgets();
        int y = height - 28;
        int x = 16;
        for (String action : actions) {
            if ("LEAVE".equals(action)) continue;
            String label = action.length() > 22 ? action.substring(0, 22) : action;
            int w = Math.min(120, font.width(label) + 12);
            if (x + w > width - 100) {
                x = 16;
                y -= 22;
            }
            final String choice = action;
            addRenderableWidget(Button.builder(Component.literal(label), b -> {
                PacketDistributor.sendToServer(new CitizenInteractionPayloads.DialogueChoice(sessionId, choice));
            }).bounds(x, y, w, 18).build());
            x += w + 4;
        }
        addRenderableWidget(Button.builder(Component.literal("Leave"), b -> {
            PacketDistributor.sendToServer(new CitizenInteractionPayloads.DialogueChoice(sessionId, "LEAVE"));
            onClose();
        }).bounds(width - 70, height - 28, 54, 18).build());
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics, mouseX, mouseY, partialTick);
        graphics.fill(12, 12, width - 12, height - 56, 0xC0101018);
        graphics.drawString(font, citizenName + "  ·  " + profession, 20, 20, 0xF0E6D2);
        graphics.drawString(font, settlementName + "  ·  " + kingdomName, 20, 34, 0xB8C4D8);
        graphics.drawString(font, "Attitude: " + attitude + "  Standing: " + standing
                + "  Legal: " + legalStatus, 20, 48, 0xA0D0A0);
        int y = 68;
        int shown = 0;
        for (int i = scroll; i < lines.size() && shown < 12; i++, shown++) {
            graphics.drawString(font, lines.get(i), 24, y, 0xE0E0E0);
            y += 12;
        }
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (scrollY > 0 && scroll > 0) scroll--;
        if (scrollY < 0 && scroll < Math.max(0, lines.size() - 8)) scroll++;
        return true;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
