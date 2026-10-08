package com.livingmods.neoforge.client;

import com.livingmods.neoforge.network.CitizenInteractionPayloads;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Realm management UI — Overview / Policies / Diplomacy / Founding.
 * Diplomacy targets are known kingdoms by name (IDs remain internal).
 */
public final class RealmManagementScreen extends Screen {
    private enum Section { OVERVIEW, POLICIES, DIPLOMACY, FOUNDING }

    private final Map<String, String> data;
    private final String panel;
    private Section section = Section.OVERVIEW;
    private EditBox nameBox;
    private EditBox valueBox;
    private String culture = "avalon";
    private final List<KnownKingdom> knownKingdoms = new ArrayList<>();
    private int selectedKingdom;

    private record KnownKingdom(String id, String name, String relation, String war, String standing) {}

    public RealmManagementScreen(CitizenInteractionPayloads.RealmPanel payload) {
        super(Component.literal("Realm"));
        this.panel = payload.panel() == null ? "MANAGE_REALM" : payload.panel();
        this.data = payload.data();
        String cultures = data.getOrDefault("cultures", "avalon:Avalon");
        if (!cultures.isBlank()) {
            culture = cultures.split(",")[0].split(":")[0];
        }
        parseKnownKingdoms();
        if ("FOUND_REALM_INFO".equals(panel) || data.getOrDefault("ruledKingdomId", "").isBlank()) {
            section = Section.FOUNDING;
        } else if ("DIPLOMACY".equals(panel)) {
            section = Section.DIPLOMACY;
        }
    }

    private void parseKnownKingdoms() {
        knownKingdoms.clear();
        String packed = data.getOrDefault("knownKingdoms", "");
        String ruled = data.getOrDefault("ruledKingdomId", "");
        for (String row : packed.split(";")) {
            if (row.isBlank()) continue;
            String[] p = row.split("\\|", -1);
            if (p.length < 2) continue;
            if (!ruled.isBlank() && p[0].equals(ruled)) continue;
            String relation = p.length > 8 ? p[8] : "NEUTRAL";
            String war = p.length > 6 ? p[6] : "PEACE";
            String standing = p.length > 4 ? p[4] : "NEUTRAL";
            knownKingdoms.add(new KnownKingdom(p[0], p[1], relation, war, standing));
        }
    }

    @Override
    protected void init() {
        nameBox = new EditBox(font, 24, 72, 180, 18, Component.literal("Realm name"));
        nameBox.setValue(data.getOrDefault("ruledKingdomName", "New Realm"));
        nameBox.setMaxLength(48);
        addRenderableWidget(nameBox);
        valueBox = new EditBox(font, 24, 100, 80, 18, Component.literal("Policy value"));
        valueBox.setValue(defaultPolicyValue());
        valueBox.setMaxLength(16);
        addRenderableWidget(valueBox);

        int bx = 24;
        for (Section s : Section.values()) {
            Section target = s;
            addRenderableWidget(Button.builder(Component.literal(s.name()), b -> {
                section = target;
                valueBox.setValue(defaultPolicyValue());
            }).bounds(bx, 40, 70, 16).build());
            bx += 74;
        }

        rebuildSectionButtons();
        addRenderableWidget(Button.builder(Component.literal("Close"), b -> onClose())
                .bounds(width - 70, height - 28, 54, 18).build());
    }

    private String defaultPolicyValue() {
        return switch (section) {
            case POLICIES -> data.getOrDefault("policyTax", "0.08");
            default -> "0.1";
        };
    }

    private void rebuildSectionButtons() {
        // Buttons added in init for all sections; visibility via action guards.
        if (section == Section.FOUNDING || data.getOrDefault("ruledKingdomId", "").isBlank()) {
            addRenderableWidget(Button.builder(Component.literal("Found Realm Here"), b ->
                    PacketDistributor.sendToServer(new CitizenInteractionPayloads.RealmAction(
                            "FOUND_REALM", nameBox.getValue(), culture, 0, 0)))
                    .bounds(24, 160, 140, 18).build());
            addRenderableWidget(Button.builder(Component.literal("Cycle Culture"), b -> cycleCulture())
                    .bounds(170, 160, 100, 18).build());
        }
        if (section == Section.POLICIES && !data.getOrDefault("ruledKingdomId", "").isBlank()) {
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
        }
        if (section == Section.DIPLOMACY && !data.getOrDefault("ruledKingdomId", "").isBlank()) {
            addRenderableWidget(Button.builder(Component.literal("Request Peace"), b ->
                    sendDiplomacy("PEACE")).bounds(24, height - 72, 110, 18).build());
            addRenderableWidget(Button.builder(Component.literal("Trade Treaty"), b ->
                    sendDiplomacy("TRADE")).bounds(140, height - 72, 100, 18).build());
            addRenderableWidget(Button.builder(Component.literal("Alliance"), b ->
                    sendDiplomacy("ALLIANCE")).bounds(246, height - 72, 80, 18).build());
            addRenderableWidget(Button.builder(Component.literal("Non-Aggression"), b ->
                    sendDiplomacy("NON_AGGRESSION")).bounds(332, height - 72, 110, 18).build());
            addRenderableWidget(Button.builder(Component.literal("Break Treaty"), b ->
                    sendDiplomacy("BREAK")).bounds(24, height - 50, 100, 18).build());
        }
    }

    private void sendDiplomacy(String kind) {
        if (knownKingdoms.isEmpty() || selectedKingdom < 0 || selectedKingdom >= knownKingdoms.size()) {
            return;
        }
        KnownKingdom k = knownKingdoms.get(selectedKingdom);
        PacketDistributor.sendToServer(new CitizenInteractionPayloads.RealmAction(
                "REQUEST_DIPLOMATIC_ACTION", kind, k.id(), 0, 0));
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
        graphics.drawCenteredString(font, "Realm — " + section.name(), width / 2, 16, 0xF0E6D2);
        String ruled = data.getOrDefault("ruledKingdomName", "(none)");
        graphics.drawString(font, "Realm: " + ruled
                + "  Treasury: " + data.getOrDefault("treasury", "—")
                + "  Tax: " + data.getOrDefault("taxRate", "—"), 24, 60, 0xDDDDDD);

        switch (section) {
            case OVERVIEW -> {
                graphics.drawString(font, "Culture: " + data.getOrDefault("culture", culture), 24, 130, 0xB8C4D8);
                graphics.drawString(font, "Settlements: " + data.getOrDefault("settlements", "—"), 24, 144, 0xB8C4D8);
                graphics.drawString(font, "Wars: " + data.getOrDefault("activeWars", "—"), 24, 158, 0xE0A0A0);
                graphics.drawString(font, "Remote governance is allowed for your own realm.", 24, 180, 0x888888);
            }
            case POLICIES -> {
                graphics.drawString(font, "Tax 0–0.4 · Defense 0–1 · Food reserves 5–200 · Build 0–1 · Migration 0/1",
                        24, 130, 0xA0A0A0);
                graphics.drawString(font, "Current: tax=" + data.getOrDefault("policyTax", "—")
                        + " def=" + data.getOrDefault("policyDefense", "—")
                        + " food=" + data.getOrDefault("policyFood", "—")
                        + " build=" + data.getOrDefault("policyConstruction", "—")
                        + " mig=" + data.getOrDefault("policyMigration", "—"), 24, 144, 0xC0D0C0);
                graphics.drawString(font, "Policies are simulation inputs — effects accrue over time.", 24, 200, 0x888888);
            }
            case DIPLOMACY -> {
                graphics.drawString(font, "Known kingdoms (select target):", 24, 130, 0xCCCCCC);
                int y = 146;
                if (knownKingdoms.isEmpty()) {
                    graphics.drawString(font, "No known kingdoms yet.", 28, y, 0x888888);
                }
                for (int i = 0; i < knownKingdoms.size() && y < height - 90; i++) {
                    KnownKingdom k = knownKingdoms.get(i);
                    int color = i == selectedKingdom ? 0xFFE6B3 : 0xDDDDDD;
                    graphics.drawString(font, (i == selectedKingdom ? "> " : "  ")
                            + k.name() + "  rel=" + k.relation() + "  " + k.war()
                            + "  (" + k.standing() + ")", 28, y, color);
                    y += 12;
                }
                graphics.drawString(font, "NPC kingdoms evaluate proposals — not all requests are accepted.", 24, height - 90, 0x888888);
            }
            case FOUNDING -> {
                graphics.drawString(font, "Culture: " + culture, 24, 130, 0xB8C4D8);
                graphics.drawString(font, "Founding cost: "
                        + data.getOrDefault("foundingGoldCost", "25") + " gold ingots", 24, 144, 0xE0C070);
                graphics.drawString(font, "Server uses your current position — stand where you want to found.", 24, 158, 0xA0A0A0);
            }
        }
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (section == Section.DIPLOMACY && button == 0 && mouseY >= 146 && mouseY < height - 90) {
            selectedKingdom = Math.max(0, Math.min(knownKingdoms.size() - 1, ((int) mouseY - 146) / 12));
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
