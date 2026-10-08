package com.livingmods.neoforge.client;

import com.livingmods.neoforge.network.CitizenInteractionPayloads;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.Map;

/** Player realm overview / policy / founding UI. */
public final class RealmManagementScreen extends Screen {
    private final Map<String, String> data;
    private final String panel;
    private EditBox nameBox;
    private EditBox valueBox;
    private String culture = "avalon";

    public RealmManagementScreen(CitizenInteractionPayloads.RealmPanel payload) {
        super(Component.literal("Realm"));
        this.panel = payload.panel() == null ? "MANAGE_REALM" : payload.panel();
        this.data = payload.data();
        String cultures = data.getOrDefault("cultures", "avalon:Avalon");
        if (!cultures.isBlank()) {
            culture = cultures.split(",")[0].split(":")[0];
        }
    }

    @Override
    protected void init() {
        nameBox = new EditBox(font, 24, 80, 160, 18, Component.literal("Realm name"));
        nameBox.setValue(data.getOrDefault("ruledKingdomName", "New Realm"));
        nameBox.setMaxLength(48);
        addRenderableWidget(nameBox);
        valueBox = new EditBox(font, 24, 120, 80, 18, Component.literal("Value"));
        valueBox.setValue("0.1");
        valueBox.setMaxLength(16);
        addRenderableWidget(valueBox);

        if ("FOUND_REALM_INFO".equals(panel) || data.getOrDefault("ruledKingdomId", "").isBlank()) {
            addRenderableWidget(Button.builder(Component.literal("Found Realm Here"), b -> {
                var player = minecraft == null ? null : minecraft.player;
                int x = player == null ? 0 : (int) player.getX();
                int z = player == null ? 0 : (int) player.getZ();
                PacketDistributor.sendToServer(new CitizenInteractionPayloads.RealmAction(
                        "FOUND_REALM", nameBox.getValue(), culture, x, z));
            }).bounds(24, 160, 140, 18).build());
            addRenderableWidget(Button.builder(Component.literal("Cycle Culture"), b -> cycleCulture())
                    .bounds(170, 160, 100, 18).build());
        } else {
            addRenderableWidget(Button.builder(Component.literal("Set Tax"), b ->
                    PacketDistributor.sendToServer(new CitizenInteractionPayloads.RealmAction(
                            "SET_TAX_POLICY", valueBox.getValue(), "", 0, 0)))
                    .bounds(24, 160, 70, 18).build());
            addRenderableWidget(Button.builder(Component.literal("Defense"), b ->
                    PacketDistributor.sendToServer(new CitizenInteractionPayloads.RealmAction(
                            "SET_DEFENSE_POLICY", valueBox.getValue(), "", 0, 0)))
                    .bounds(100, 160, 70, 18).build());
            addRenderableWidget(Button.builder(Component.literal("Food"), b ->
                    PacketDistributor.sendToServer(new CitizenInteractionPayloads.RealmAction(
                            "SET_FOOD_POLICY", valueBox.getValue(), "", 0, 0)))
                    .bounds(176, 160, 60, 18).build());
            addRenderableWidget(Button.builder(Component.literal("Build"), b ->
                    PacketDistributor.sendToServer(new CitizenInteractionPayloads.RealmAction(
                            "SET_CONSTRUCTION_POLICY", valueBox.getValue(), "", 0, 0)))
                    .bounds(242, 160, 60, 18).build());
            addRenderableWidget(Button.builder(Component.literal("Migration"), b ->
                    PacketDistributor.sendToServer(new CitizenInteractionPayloads.RealmAction(
                            "SET_MIGRATION_POLICY", valueBox.getValue(), "", 0, 0)))
                    .bounds(308, 160, 70, 18).build());
            addRenderableWidget(Button.builder(Component.literal("Peace w/ target"), b ->
                    PacketDistributor.sendToServer(new CitizenInteractionPayloads.RealmAction(
                            "REQUEST_DIPLOMATIC_ACTION", "PEACE", valueBox.getValue(), 0, 0)))
                    .bounds(24, 184, 120, 18).build());
            addRenderableWidget(Button.builder(Component.literal("Trade Treaty"), b ->
                    PacketDistributor.sendToServer(new CitizenInteractionPayloads.RealmAction(
                            "REQUEST_DIPLOMATIC_ACTION", "TRADE", valueBox.getValue(), 0, 0)))
                    .bounds(150, 184, 100, 18).build());
        }
        addRenderableWidget(Button.builder(Component.literal("Close"), b -> onClose())
                .bounds(width - 70, height - 28, 54, 18).build());
    }

    private void cycleCulture() {
        String cultures = data.getOrDefault("cultures", "avalon:Avalon");
        String[] parts = cultures.split(",");
        if (parts.length == 0) return;
        int idx = 0;
        for (int i = 0; i < parts.length; i++) {
            if (parts[i].startsWith(culture + ":") || parts[i].equals(culture)) {
                idx = (i + 1) % parts.length;
                break;
            }
        }
        culture = parts[idx].split(":")[0];
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(font, "Realm Management", width / 2, 16, 0xF0E6D2);
        String ruled = data.getOrDefault("ruledKingdomName", "(none)");
        graphics.drawString(font, "Realm: " + ruled
                + "  Treasury: " + data.getOrDefault("treasury", "—")
                + "  Tax: " + data.getOrDefault("taxRate", "—"), 24, 40, 0xDDDDDD);
        graphics.drawString(font, "Culture: " + culture
                + "  Settlements: " + data.getOrDefault("settlements", "—"), 24, 54, 0xB8C4D8);
        graphics.drawString(font, "Name / diplomacy target kingdom UUID in value box when needed", 24, 100, 0x888888);
        graphics.drawString(font, "Wars: " + data.getOrDefault("activeWars", "—"), 24, 220, 0xE0A0A0);
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
