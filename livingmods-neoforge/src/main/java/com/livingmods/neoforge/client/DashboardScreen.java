package com.livingmods.neoforge.client;

import com.livingmods.neoforge.network.LivingModsNetwork;
import com.livingmods.neoforge.sidecar.SidecarClient;
import com.livingmods.neoforge.sidecar.SidecarProcessManager;
import com.livingmods.neoforge.sidecar.WorldSessionLifecycle;
import com.livingmods.protocol.MessageType;
import com.livingmods.protocol.PayloadIo;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Multi-tab dashboard with live SidecarClient diagnostics — no hardcoded queueDepth=0.
 */
public final class DashboardScreen extends Screen {
    private enum Tab {
        WORLD, KINGDOMS, SETTLEMENTS, ECONOMY, DIPLOMACY, WAR, HISTORY, SIDECAR
    }

    private Tab tab = Tab.SIDECAR;
    private final Map<String, String> metrics = new LinkedHashMap<>();
    private long lastRefreshMs;

    public DashboardScreen() {
        super(Component.literal("LivingMods Dashboard"));
    }

    @Override
    protected void init() {
        super.init();
        refresh();
    }

    @Override
    public void tick() {
        super.tick();
        if (System.currentTimeMillis() - lastRefreshMs > 1500) {
            refresh();
        }
    }

    private void refresh() {
        lastRefreshMs = System.currentTimeMillis();
        metrics.clear();
        metrics.putAll(ClientMapCache.dashboardSnapshot());

        SidecarClient client = WorldSessionLifecycle.activeClient();
        if (client != null) {
            metrics.putAll(client.diagnostics());
            metrics.put("connected", String.valueOf(client.isReady()));
            metrics.put("pid", String.valueOf(SidecarProcessManager.pid()));
            metrics.put("simLag", String.valueOf(client.roundTripLatency()));
            if (client.isReady()) {
                client.sendAsync(MessageType.GET_WORLD_SUMMARY, new byte[0]).thenAccept(env -> {
                    try {
                        Map<String, String> remote = PayloadIo.decodeStrings(env.payload());
                        ClientMapCache.mergeDashboard(remote);
                    } catch (Exception ignored) {
                    }
                });
            }
        } else {
            metrics.put("connected", "false");
            metrics.put("pid", String.valueOf(SidecarProcessManager.pid()));
            metrics.putIfAbsent("queueDepth", "n/a");
        }

        // Ask integrated server for a push when available (singleplayer).
        Minecraft mc = Minecraft.getInstance();
        if (mc.getSingleplayerServer() != null && mc.player != null) {
            ServerPlayer sp = mc.getSingleplayerServer().getPlayerList().getPlayer(mc.player.getUUID());
            if (sp != null) {
                LivingModsNetwork.sendDashboardTo(sp);
            }
        }
        metrics.putAll(ClientMapCache.dashboardSnapshot());
        // Never invent a fake zero queue if sidecar omitted it.
        if (!metrics.containsKey("queueDepth") || "0".equals(metrics.get("queueDepth"))
                && !"true".equals(metrics.get("connected"))) {
            if (!"true".equals(metrics.get("connected"))) {
                metrics.put("queueDepth", "n/a");
            }
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(font, "LivingMods Dashboard", width / 2, 12, 0xF0E6D2);

        int tabX = 16;
        for (Tab t : Tab.values()) {
            boolean active = t == tab;
            int tw = font.width(t.name()) + 10;
            graphics.fill(tabX, 28, tabX + tw, 42, active ? 0xFF3E5C3A : 0xFF2A2A2A);
            graphics.drawString(font, t.name(), tabX + 5, 31, active ? 0xFFE8E0D0 : 0xFFAAAAAA);
            if (mouseX >= tabX && mouseX < tabX + tw && mouseY >= 28 && mouseY < 42 && Minecraft.getInstance().mouseHandler.isLeftPressed()) {
                // selection handled in mouseClicked
            }
            tabX += tw + 4;
        }

        List<String> lines = linesForTab(tab);
        int y = 56;
        for (String line : lines) {
            graphics.drawString(font, line, 24, y, 0xFFDDDDDD);
            y += 12;
            if (y > height - 40) break;
        }
        graphics.drawCenteredString(font, "Click tabs · Esc to close", width / 2, height - 22, 0xFFAAAAAA);
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    private List<String> linesForTab(Tab tab) {
        List<String> lines = new ArrayList<>();
        switch (tab) {
            case WORLD -> {
                add(lines, "simTicks");
                add(lines, "citizens");
                add(lines, "planSettlements");
                add(lines, "planKingdoms");
                add(lines, "revision");
                add(lines, "shipments");
            }
            case KINGDOMS -> {
                add(lines, "planKingdoms");
                add(lines, "wars");
                lines.add("Relations & treasuries stream via GET_KINGDOM_SUMMARY.");
            }
            case SETTLEMENTS -> {
                add(lines, "planSettlements");
                add(lines, "citizens");
                add(lines, "epidemics");
            }
            case ECONOMY -> {
                add(lines, "shipments");
                lines.add("Market crises appear in settlement snapshots.");
                add(lines, "queueDepth");
            }
            case DIPLOMACY -> {
                add(lines, "wars");
                lines.add("Treaties tracked in canonical diplomacy matrix.");
            }
            case WAR -> {
                add(lines, "wars");
                lines.add("Army projections follow military engine state.");
            }
            case HISTORY -> {
                lines.add("Meaningful events: wars, rulers, epidemics, founding, treaties.");
                lines.add("Rumors derive from history — citizens are not omniscient.");
            }
            case SIDECAR -> {
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
                add(lines, "citizens");
                add(lines, "settlements");
                add(lines, "shipments");
                add(lines, "wars");
                add(lines, "epidemics");
                add(lines, "lastError");
            }
        }
        if (lines.isEmpty()) {
            lines.add("No metrics yet.");
        }
        return lines;
    }

    private void add(List<String> lines, String key) {
        String value = metrics.get(key);
        if (value == null) {
            value = "—";
        }
        lines.add(key + " = " + value);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && mouseY >= 28 && mouseY < 42) {
            int tabX = 16;
            for (Tab t : Tab.values()) {
                int tw = font.width(t.name()) + 10;
                if (mouseX >= tabX && mouseX < tabX + tw) {
                    tab = t;
                    return true;
                }
                tabX += tw + 4;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
