package com.livingmods.neoforge.client;

import com.livingmods.neoforge.network.CitizenInteractionPayloads;
import com.livingmods.neoforge.network.CitizenInteractionPayloads.TaskEntry;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Player task journal — objectives from canonical typed TaskEntry payloads. */
public final class TaskJournalScreen extends Screen {
    private final List<TaskEntry> tasks = new ArrayList<>();
    private int selected;
    private Button actionButton;

    public TaskJournalScreen(List<TaskEntry> tasks) {
        super(Component.literal("Task Journal"));
        if (tasks != null) this.tasks.addAll(tasks);
    }

    public void merge(CitizenInteractionPayloads.TaskJournalUpdate update) {
        if (update == null) return;
        tasks.clear();
        tasks.addAll(update.tasks());
        rebuildActionButton();
    }

    @Override
    protected void init() {
        actionButton = Button.builder(Component.literal(actionLabel()), b -> runSelectedAction())
                .bounds(20, height - 48, 180, 18).build();
        addRenderableWidget(actionButton);
        addRenderableWidget(Button.builder(Component.literal("Abandon"), b -> {
            TaskEntry t = selected();
            if (t == null) return;
            PacketDistributor.sendToServer(new CitizenInteractionPayloads.TaskAction(
                    t.taskId(), "ABANDON", "", 0, new UUID(0, 0)));
        }).bounds(210, height - 48, 70, 18).build());
        addRenderableWidget(Button.builder(Component.literal("Refresh"), b ->
                PacketDistributor.sendToServer(new CitizenInteractionPayloads.JournalRequest()))
                .bounds(290, height - 48, 70, 18).build());
        addRenderableWidget(Button.builder(Component.literal("Close"), b -> onClose())
                .bounds(width - 70, height - 28, 54, 18).build());
    }

    private void rebuildActionButton() {
        if (actionButton != null) {
            actionButton.setMessage(Component.literal(actionLabel()));
        }
    }

    private String actionLabel() {
        TaskEntry t = selected();
        if (t == null) return "No action";
        return switch (t.type()) {
            case "FOOD_DELIVERY" -> "Deliver " + Math.max(1, t.amount()) + " "
                    + (t.resource().isBlank() ? "Grain" : itemLabel(t.resource()));
            case "MEDICINE_DELIVERY" -> "Deliver " + Math.max(1, t.amount()) + " Medicine";
            case "CONSTRUCTION_RESOURCES" -> "Deliver " + Math.max(1, t.wood()) + " Logs + "
                    + Math.max(1, t.stone()) + " Cobble";
            case "BANDIT_REMOVAL" -> t.campId().isBlank() ? "Track Camp" : "Track Camp objective";
            case "ESCORT", "MISSING_CARAVAN" -> "Show caravan objective";
            case "DIPLOMATIC_DELIVERY" -> "Show delivery destination";
            default -> "Objective";
        };
    }

    private static String itemLabel(String resource) {
        return switch (resource.toUpperCase()) {
            case "GRAIN", "FOOD" -> "Wheat";
            case "WOOD" -> "Logs";
            case "STONE" -> "Cobblestone";
            case "MEDICINE" -> "Medicine";
            default -> resource;
        };
    }

    private void runSelectedAction() {
        TaskEntry t = selected();
        if (t == null) return;
        switch (t.type()) {
            case "FOOD_DELIVERY", "MEDICINE_DELIVERY" -> {
                String resource = t.resource().isBlank()
                        ? (t.type().startsWith("MED") ? "MEDICINE" : "GRAIN")
                        : t.resource();
                int amount = Math.max(1, t.amount());
                PacketDistributor.sendToServer(new CitizenInteractionPayloads.TaskAction(
                        t.taskId(), "DELIVER", resource, amount, t.settlementId()));
            }
            case "CONSTRUCTION_RESOURCES" -> PacketDistributor.sendToServer(
                    new CitizenInteractionPayloads.TaskAction(
                            t.taskId(), "DELIVER", "CONSTRUCTION",
                            Math.max(t.wood(), t.stone()) > 0 ? Math.max(t.wood(), t.stone()) : 15,
                            t.settlementId()));
            case "BANDIT_REMOVAL", "ESCORT", "MISSING_CARAVAN", "DIPLOMATIC_DELIVERY" -> {
                // No fake item-delivery — physical/canonical outcomes complete these.
            }
            default -> {
            }
        }
    }

    private TaskEntry selected() {
        if (tasks.isEmpty() || selected < 0 || selected >= tasks.size()) return null;
        return tasks.get(selected);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(font, "Emergent Tasks", width / 2, 16, 0xF0E6D2);
        int y = 40;
        if (tasks.isEmpty()) {
            graphics.drawString(font, "No discovered or accepted tasks.", 24, y, 0xAAAAAA);
            graphics.drawString(font, "Talk to citizens or press J after exploring.", 24, y + 14, 0x888888);
        }
        for (int i = 0; i < tasks.size() && y < height - 70; i++) {
            TaskEntry t = tasks.get(i);
            String line = (i == selected ? "> " : "  ")
                    + t.title() + " [" + t.status() + "]";
            graphics.drawString(font, line, 24, y, i == selected ? 0xFFE6B3 : 0xDDDDDD);
            y += 12;
            if (i == selected) {
                graphics.drawString(font, objectiveLine(t), 32, y, 0xA0C0A0);
                y += 12;
                if (!t.description().isBlank()) {
                    graphics.drawString(font, t.description(), 32, y, 0xA0A0A0);
                    y += 12;
                }
                if (!t.settlementName().isBlank()) {
                    graphics.drawString(font, "Destination: " + t.settlementName(), 32, y, 0x90A0B8);
                    y += 12;
                }
            }
            y += 4;
        }
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    private static String objectiveLine(TaskEntry t) {
        return switch (t.type()) {
            case "FOOD_DELIVERY" -> "Deliver " + Math.max(1, t.amount()) + " "
                    + itemLabel(t.resource().isBlank() ? "GRAIN" : t.resource());
            case "MEDICINE_DELIVERY" -> "Deliver " + Math.max(1, t.amount()) + " Medicine";
            case "CONSTRUCTION_RESOURCES" -> "Deliver " + Math.max(1, t.wood()) + " Logs + "
                    + Math.max(1, t.stone()) + " Cobblestone";
            case "BANDIT_REMOVAL" -> t.campId().isBlank()
                    ? "Clear bandits near destination" : "Clear camp " + t.campId().substring(0, 8) + "…";
            case "ESCORT", "MISSING_CARAVAN" -> t.shipmentId().isBlank()
                    ? "Escort / recover caravan" : "Shipment " + t.shipmentId().substring(0, 8) + "…";
            case "DIPLOMATIC_DELIVERY" -> "Deliver sealed letters to destination";
            default -> t.type();
        };
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && mouseY >= 40 && mouseY < height - 60) {
            selected = Math.max(0, Math.min(tasks.size() - 1, ((int) mouseY - 40) / 28));
            rebuildActionButton();
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
