package com.livingmods.neoforge.client;

import com.livingmods.neoforge.network.CitizenInteractionPayloads;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Lightweight journal of discovered/accepted emergent tasks. */
public final class TaskJournalScreen extends Screen {
    private final List<String> tasks = new ArrayList<>();
    private int selected;

    public TaskJournalScreen(List<String> tasks) {
        super(Component.literal("Task Journal"));
        if (tasks != null) this.tasks.addAll(tasks);
    }

    public void merge(CitizenInteractionPayloads.TaskJournalUpdate update) {
        if (update == null) return;
        for (String row : update.tasks()) {
            if (!tasks.contains(row)) tasks.add(0, row);
        }
    }

    @Override
    protected void init() {
        addRenderableWidget(Button.builder(Component.literal("Deliver (GRAIN×10)"), b -> {
            String[] p = selectedRow();
            if (p == null) return;
            try {
                PacketDistributor.sendToServer(new CitizenInteractionPayloads.TaskAction(
                        UUID.fromString(p[0]), "DELIVER", "GRAIN", 10,
                        p.length > 3 ? UUID.fromString(p[3]) : new UUID(0, 0)));
            } catch (Exception ignored) {
            }
        }).bounds(20, height - 48, 140, 18).build());
        addRenderableWidget(Button.builder(Component.literal("Abandon"), b -> {
            String[] p = selectedRow();
            if (p == null) return;
            try {
                PacketDistributor.sendToServer(new CitizenInteractionPayloads.TaskAction(
                        UUID.fromString(p[0]), "ABANDON", "", 0, new UUID(0, 0)));
            } catch (Exception ignored) {
            }
        }).bounds(170, height - 48, 70, 18).build());
        addRenderableWidget(Button.builder(Component.literal("Close"), b -> onClose())
                .bounds(width - 70, height - 28, 54, 18).build());
    }

    private String[] selectedRow() {
        if (tasks.isEmpty() || selected < 0 || selected >= tasks.size()) return null;
        return tasks.get(selected).split("\\|", -1);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(font, "Emergent Tasks", width / 2, 16, 0xF0E6D2);
        int y = 40;
        if (tasks.isEmpty()) {
            graphics.drawString(font, "No accepted or discovered tasks.", 24, y, 0xAAAAAA);
        }
        for (int i = 0; i < tasks.size() && y < height - 60; i++) {
            String[] p = tasks.get(i).split("\\|", -1);
            String line = (i == selected ? "> " : "  ")
                    + (p.length > 2 ? p[2] : tasks.get(i))
                    + (p.length > 1 ? " [" + p[1] + "]" : "");
            graphics.drawString(font, line, 24, y, i == selected ? 0xFFE6B3 : 0xDDDDDD);
            if (i == selected && p.length > 4) {
                graphics.drawString(font, p[4], 32, y + 12, 0xA0A0A0);
                y += 12;
            }
            y += 14;
        }
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && mouseY >= 40 && mouseY < height - 60) {
            selected = Math.max(0, Math.min(tasks.size() - 1, ((int) mouseY - 40) / 14));
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
