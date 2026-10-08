package com.livingmods.neoforge.client;

import com.livingmods.neoforge.network.CitizenInteractionPayloads;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Civilization / player overview dashboard.
 * Client is presentation-only — requests go C2S → server → LiveStateCache/sidecar → S2C.
 */
public final class DashboardScreen extends Screen {
    private enum Tab {
        WORLD, KINGDOMS, SETTLEMENTS, ECONOMY, DIPLOMACY, WAR, HISTORY, SIDECAR
    }

    private Tab tab = Tab.WORLD;
    private final Map<String, String> metrics = new LinkedHashMap<>();
    private long lastRefreshMs;
    private int selectedRow;

    public DashboardScreen() {
        super(Component.literal("MineLife"));
    }

    @Override
    protected void init() {
        super.init();
        refresh();
    }

    @Override
    public void tick() {
        super.tick();
        if (System.currentTimeMillis() - lastRefreshMs > 2000) {
            refresh();
        }
    }

    private void refresh() {
        lastRefreshMs = System.currentTimeMillis();
        metrics.clear();
        metrics.putAll(ClientMapCache.dashboardSnapshot());
        Map<String, String> ctx = ClientMapCache.playerContextSnapshot();
        if (!ctx.isEmpty()) {
            metrics.put("hasPlayerContext", "true");
        }
        // Server-authoritative path — never SidecarClient from client.
        PacketDistributor.sendToServer(new CitizenInteractionPayloads.DashboardRequest());
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(font, "MineLife — Civilization Overview", width / 2, 12, 0xF0E6D2);

        int tabX = 16;
        for (Tab t : Tab.values()) {
            boolean active = t == tab;
            int tw = font.width(t.name()) + 10;
            graphics.fill(tabX, 28, tabX + tw, 42, active ? 0xFF3E5C3A : 0xFF2A2A2A);
            graphics.drawString(font, t.name(), tabX + 5, 31, active ? 0xFFE8E0D0 : 0xFFAAAAAA);
            tabX += tw + 4;
        }

        List<String> lines = linesForTab(tab);
        int y = 56;
        int row = 0;
        for (String line : lines) {
            int color = row == selectedRow ? 0xFFE6B3 : 0xFFDDDDDD;
            graphics.drawString(font, line, 24, y, color);
            y += 12;
            row++;
            if (y > height - 40) break;
        }
        graphics.drawCenteredString(font, "Click tabs · J journal · Esc close", width / 2, height - 22, 0xFFAAAAAA);
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    private List<String> linesForTab(Tab tab) {
        Map<String, String> ctx = ClientMapCache.playerContextSnapshot();
        List<String> lines = new ArrayList<>();
        switch (tab) {
            case WORLD -> {
                lines.add("Day/ticks: " + metrics.getOrDefault("simTicks", "—"));
                lines.add("Known kingdoms: " + countPacked(ctx.get("knownKingdoms")));
                lines.add("Known settlements: " + countPacked(ctx.get("knownSettlements")));
                lines.add("Citizens (live): " + metrics.getOrDefault("citizens", "—"));
                lines.add("Open tasks: " + metrics.getOrDefault("openTasks", "—"));
                lines.add("Your realm: " + blank(ctx.get("ruledKingdomName"), "(none)"));
                String crises = metrics.getOrDefault("epidemicActive", "0");
                lines.add("Epidemics: " + crises + "  Wars: " + metrics.getOrDefault("warActive", "0"));
            }
            case KINGDOMS -> {
                String packed = ctx.getOrDefault("knownKingdoms", "");
                if (packed.isBlank()) {
                    lines.add("No kingdoms discovered yet — explore or talk to citizens.");
                    lines.add("Live count (world): " + metrics.getOrDefault("kingdomsLive",
                            metrics.getOrDefault("planKingdoms", "—")));
                } else {
                    for (String row : packed.split(";")) {
                        if (row.isBlank()) continue;
                        String[] p = row.split("\\|", -1);
                        if (p.length >= 7) {
                            lines.add(p[1] + "  " + p[2] + " / " + p[3]
                                    + "  standing=" + p[4] + "  " + p[5] + "  " + p[6]
                                    + (p.length > 7 ? "  ¤" + p[7] : ""));
                        } else {
                            lines.add(row);
                        }
                    }
                }
            }
            case SETTLEMENTS -> {
                String packed = ctx.getOrDefault("knownSettlements", "");
                if (packed.isBlank()) {
                    lines.add("No settlements known — visit or receive rumors.");
                } else {
                    for (String row : packed.split(";")) {
                        if (row.isBlank()) continue;
                        String[] p = row.split("\\|", -1);
                        if (p.length >= 8) {
                            lines.add(p[1] + " [" + p[2] + "] houses=" + p[3]
                                    + " food=" + p[4] + " sec=" + p[5] + " unrest=" + p[6]
                                    + " (" + (p.length > 8 ? p[8] : "?") + ")");
                        } else {
                            lines.add(row);
                        }
                    }
                }
            }
            case ECONOMY -> {
                lines.add("Shipments in transit: " + metrics.getOrDefault("shipments", "—"));
                lines.add("Your treasury: " + blank(ctx.get("treasury"), "—"));
                lines.add("Tax rate: " + blank(ctx.get("taxRate"), "—"));
                lines.add("Talk to merchants for local market prices.");
                lines.add("Currency: gold ingots ↔ canonical GOLD.");
            }
            case DIPLOMACY -> {
                boolean ruler = !blank(ctx.get("ruledKingdomId"), "").isBlank();
                lines.add(ruler ? "You rule a realm — open Realm UI (F12 / steward)."
                        : "Read-only: join a kingdom or found a realm for diplomacy.");
                lines.add("Treaties (live): " + metrics.getOrDefault("treaties", "—"));
                lines.add("Active wars: " + blank(ctx.get("activeWars"), metrics.getOrDefault("warActive", "—")));
            }
            case WAR -> {
                String wars = ctx.getOrDefault("activeWars", "");
                if (wars.isBlank()) {
                    lines.add("No known active wars.");
                } else {
                    for (String row : wars.split(";")) {
                        if (row.isBlank()) continue;
                        String[] p = row.split("\\|", -1);
                        if (p.length >= 3) {
                            lines.add(p[1] + " vs " + p[2]);
                        } else {
                            lines.add(row);
                        }
                    }
                }
                lines.add("Allegiance: " + blank(ctx.get("ruledKingdomName"), "none / civilian"));
            }
            case HISTORY -> {
                String hist = blank(ctx.get("historyRecent"), metrics.getOrDefault("historyRecent", ""));
                if (hist.isBlank()) {
                    lines.add("No recent events known.");
                } else {
                    for (String part : hist.split(" \\| ")) {
                        lines.add("• " + part);
                    }
                }
            }
            case SIDECAR -> {
                // Diagnostics only — values come from server DashboardDataPayload.
                add(lines, "connected");
                add(lines, "connectionState");
                add(lines, "pid");
                add(lines, "simLag");
                add(lines, "roundTripLatencyMs");
                add(lines, "revision");
                add(lines, "stepNanos");
                add(lines, "workers");
                add(lines, "queueDepth");
                add(lines, "pendingRequests");
                add(lines, "inboundEvents");
                add(lines, "ipcIn");
                add(lines, "ipcOut");
                add(lines, "lastError");
            }
        }
        if (lines.isEmpty()) {
            lines.add("No data yet.");
        }
        return lines;
    }

    private void add(List<String> lines, String key) {
        lines.add(key + " = " + metrics.getOrDefault(key, "—"));
    }

    private static String blank(String v, String fallback) {
        return v == null || v.isBlank() ? fallback : v;
    }

    private static int countPacked(String packed) {
        if (packed == null || packed.isBlank()) return 0;
        int n = 0;
        for (String row : packed.split(";")) {
            if (!row.isBlank()) n++;
        }
        return n;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && mouseY >= 28 && mouseY < 42) {
            int tabX = 16;
            for (Tab t : Tab.values()) {
                int tw = font.width(t.name()) + 10;
                if (mouseX >= tabX && mouseX < tabX + tw) {
                    tab = t;
                    selectedRow = 0;
                    return true;
                }
                tabX += tw + 4;
            }
        }
        if (button == 0 && mouseY >= 56) {
            selectedRow = Math.max(0, ((int) mouseY - 56) / 12);
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_J) {
            PacketDistributor.sendToServer(new CitizenInteractionPayloads.JournalRequest());
            return true;
        }
        if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_R) {
            PacketDistributor.sendToServer(new CitizenInteractionPayloads.RealmAction(
                    "OPEN_REALM", "MANAGE_REALM", "", 0, 0));
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
