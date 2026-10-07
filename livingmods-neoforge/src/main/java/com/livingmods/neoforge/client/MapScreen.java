package com.livingmods.neoforge.client;

import com.livingmods.neoforge.network.MapDataPayload;
import com.livingmods.neoforge.network.MapDataRequestPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Top-down civilization map fed by server {@link MapDataPayload} packets.
 * Does not read WorldPlanCache on the client.
 */
public final class MapScreen extends Screen {
    private float zoom = 1.0f;
    private float panX;
    private float panZ;
    private String hoverText = "";
    private String selectedSettlement = "";
    private String selectedKingdom = "";
    private int overlayMode; // 0 terrain+settlements, 1 kingdoms, 2 trade/roads, 3 wars/disease/migration
    private boolean requested;

    public MapScreen() {
        super(Component.literal("LivingMods Map"));
    }

    @Override
    protected void init() {
        super.init();
        requestMapData();
    }

    private void requestMapData() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.getConnection() == null) {
            return;
        }
        int detail = 1;
        PacketDistributor.sendToServer(new MapDataRequestPayload(
                (int) mc.player.getX(),
                (int) mc.player.getZ(),
                4096,
                detail
        ));
        requested = true;
    }

    @Override
    public void tick() {
        super.tick();
        if (!requested) {
            requestMapData();
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics, mouseX, mouseY, partialTick);
        MapDataPayload data = ClientMapCache.mapData();
        if (data == null) {
            graphics.drawCenteredString(font, "Loading map from server…", width / 2, height / 2, 0xCCCCCC);
            super.render(graphics, mouseX, mouseY, partialTick);
            return;
        }

        int mapLeft = 20;
        int mapTop = 36;
        int mapW = width - 40;
        int mapH = height - 70;

        drawTerrain(graphics, data, mapLeft, mapTop, mapW, mapH);
        if (overlayMode == 1 || overlayMode == 0) {
            drawKingdomBorders(graphics, data, mapLeft, mapTop, mapW, mapH);
        }
        if (overlayMode == 2 || overlayMode == 0) {
            drawRoads(graphics, data, mapLeft, mapTop, mapW, mapH);
        }
        drawSettlements(graphics, data, mapLeft, mapTop, mapW, mapH);
        if (overlayMode == 3) {
            drawLiveOverlays(graphics, data, mapLeft, mapTop, mapW, mapH);
        }
        drawPlayer(graphics, data, mapLeft, mapTop, mapW, mapH);

        updateHover(data, mouseX, mouseY, mapLeft, mapTop, mapW, mapH);

        graphics.drawString(font, "LivingMods Map  zoom=" + String.format("%.1f", zoom)
                + "  [1]terrain [2]kingdoms [3]roads [4]live  scroll=zoom  drag=pan", 20, 12, 0xE8E0D0);
        if (!hoverText.isEmpty()) {
            graphics.drawString(font, hoverText, 20, height - 40, 0xFFE6B3);
        }
        if (!selectedSettlement.isEmpty() || !selectedKingdom.isEmpty()) {
            graphics.drawString(font,
                    (selectedSettlement.isEmpty() ? "" : selectedSettlement + "  ")
                            + (selectedKingdom.isEmpty() ? "" : selectedKingdom),
                    20, height - 28, 0xB8D4FF);
        }
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    private void drawTerrain(GuiGraphics g, MapDataPayload data, int left, int top, int w, int h) {
        int tw = data.width();
        int th = data.height();
        if (tw <= 0 || th <= 0) return;
        float cellW = (w / (float) tw) * zoom;
        float cellH = (h / (float) th) * zoom;
        for (int z = 0; z < th; z++) {
            for (int x = 0; x < tw; x++) {
                byte t = data.terrainTiles()[z * tw + x];
                int color = switch (t) {
                    case 2 -> 0xFF1F4E79; // water
                    case 3 -> 0xFF5C5346; // relief
                    case 4 -> 0xFF6B8F71; // settlement influence
                    case 1 -> 0xFF3E5C3A; // land
                    default -> 0xFF2A2A2A;
                };
                int px = left + Math.round(x * cellW + panX);
                int pz = top + Math.round(z * cellH + panZ);
                int cw = Math.max(1, Math.round(cellW) + 1);
                int ch = Math.max(1, Math.round(cellH) + 1);
                if (px + cw < left || pz + ch < top || px > left + w || pz > top + h) continue;
                g.fill(px, pz, px + cw, pz + ch, color);
            }
        }
        g.renderOutline(left, top, w, h, 0xFFD8C3A5);
    }

    private void drawKingdomBorders(GuiGraphics g, MapDataPayload data, int left, int top, int w, int h) {
        for (MapDataPayload.KingdomOverlay k : data.kingdoms()) {
            var border = k.borderXZ();
            if (border.size() < 4) continue;
            int color = 0xAA000000 | (k.color() & 0xFFFFFF);
            for (int i = 0; i + 3 < border.size(); i += 2) {
                int x1 = worldToScreenX(data, border.get(i), left, w);
                int z1 = worldToScreenZ(data, border.get(i + 1), top, h);
                int x2 = worldToScreenX(data, border.get((i + 2) % border.size()), left, w);
                int z2 = worldToScreenZ(data, border.get((i + 3) % border.size()), top, h);
                drawLine(g, x1, z1, x2, z2, color);
            }
        }
    }

    private void drawRoads(GuiGraphics g, MapDataPayload data, int left, int top, int w, int h) {
        for (MapDataPayload.RoadSegment r : data.roads()) {
            int x1 = worldToScreenX(data, r.x1(), left, w);
            int z1 = worldToScreenZ(data, r.z1(), top, h);
            int x2 = worldToScreenX(data, r.x2(), left, w);
            int z2 = worldToScreenZ(data, r.z2(), top, h);
            drawLine(g, x1, z1, x2, z2, 0xFFC4A574);
        }
    }

    private void drawSettlements(GuiGraphics g, MapDataPayload data, int left, int top, int w, int h) {
        for (MapDataPayload.SettlementMarker s : data.settlements()) {
            int px = worldToScreenX(data, s.x(), left, w);
            int pz = worldToScreenZ(data, s.z(), top, h);
            int size = s.capital() ? 4 : 2;
            int color = s.capital() ? 0xFFFFD27F : 0xFFE8E0D0;
            g.fill(px - size, pz - size, px + size, pz + size, color);
        }
    }

    private void drawLiveOverlays(GuiGraphics g, MapDataPayload data, int left, int top, int w, int h) {
        for (MapDataPayload.ArmyMarker a : data.armies()) {
            int px = worldToScreenX(data, a.x(), left, w);
            int pz = worldToScreenZ(data, a.z(), top, h);
            g.fill(px - 3, pz - 3, px + 3, pz + 3, 0xFFB33A3A);
        }
        for (MapDataPayload.EpidemicMarker e : data.epidemics()) {
            int px = worldToScreenX(data, e.x(), left, w);
            int pz = worldToScreenZ(data, e.z(), top, h);
            g.fill(px - 4, pz - 4, px + 4, pz + 4, 0x88AA66CC);
        }
        for (MapDataPayload.MigrationMarker m : data.migrations()) {
            int px = worldToScreenX(data, m.x(), left, w);
            int pz = worldToScreenZ(data, m.z(), top, h);
            g.fill(px - 2, pz - 2, px + 2, pz + 2, 0xFF66AADD);
        }
    }

    private void drawPlayer(GuiGraphics g, MapDataPayload data, int left, int top, int w, int h) {
        int px = worldToScreenX(data, data.playerX(), left, w);
        int pz = worldToScreenZ(data, data.playerZ(), top, h);
        g.fill(px - 2, pz - 2, px + 2, pz + 2, 0xFF39FF14);
    }

    private void updateHover(MapDataPayload data, int mouseX, int mouseY, int left, int top, int w, int h) {
        hoverText = "";
        for (MapDataPayload.SettlementMarker s : data.settlements()) {
            int px = worldToScreenX(data, s.x(), left, w);
            int pz = worldToScreenZ(data, s.z(), top, h);
            if (Math.abs(mouseX - px) <= 5 && Math.abs(mouseY - pz) <= 5) {
                hoverText = s.name() + " [" + s.tier() + "]"
                        + (s.kingdom().isEmpty() ? "" : " — " + s.kingdom())
                        + " @ " + s.x() + ", " + s.z();
                return;
            }
        }
        for (MapDataPayload.KingdomOverlay k : data.kingdoms()) {
            int px = worldToScreenX(data, k.capitalX(), left, w);
            int pz = worldToScreenZ(data, k.capitalZ(), top, h);
            if (Math.abs(mouseX - px) <= 6 && Math.abs(mouseY - pz) <= 6) {
                hoverText = "Kingdom " + k.name();
                return;
            }
        }
    }

    private int worldToScreenX(MapDataPayload data, int worldX, int left, int w) {
        float nx = (worldX - data.originX()) / (float) Math.max(1, data.width() * data.tileSize());
        return left + Math.round(nx * w * zoom + panX);
    }

    private int worldToScreenZ(MapDataPayload data, int worldZ, int top, int h) {
        float nz = (worldZ - data.originZ()) / (float) Math.max(1, data.height() * data.tileSize());
        return top + Math.round(nz * h * zoom + panZ);
    }

    private static void drawLine(GuiGraphics g, int x1, int y1, int x2, int y2, int color) {
        int dx = Math.abs(x2 - x1);
        int dy = Math.abs(y2 - y1);
        int steps = Math.max(dx, dy);
        if (steps == 0) {
            g.fill(x1, y1, x1 + 1, y1 + 1, color);
            return;
        }
        for (int i = 0; i <= steps; i++) {
            int x = x1 + (x2 - x1) * i / steps;
            int y = y1 + (y2 - y1) * i / steps;
            g.fill(x, y, x + 1, y + 1, color);
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        zoom = Math.max(0.5f, Math.min(4.0f, zoom + (float) scrollY * 0.1f));
        return true;
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (button == 0) {
            panX += (float) dragX;
            panZ += (float) dragY;
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        MapDataPayload data = ClientMapCache.mapData();
        if (data != null && button == 0) {
            int mapLeft = 20;
            int mapTop = 36;
            int mapW = width - 40;
            int mapH = height - 70;
            for (MapDataPayload.SettlementMarker s : data.settlements()) {
                int px = worldToScreenX(data, s.x(), mapLeft, mapW);
                int pz = worldToScreenZ(data, s.z(), mapTop, mapH);
                if (Math.abs(mouseX - px) <= 6 && Math.abs(mouseY - pz) <= 6) {
                    selectedSettlement = s.name() + " (" + s.tier() + ")";
                    selectedKingdom = s.kingdom().isEmpty() ? "Independent" : s.kingdom();
                    return true;
                }
            }
            for (MapDataPayload.KingdomOverlay k : data.kingdoms()) {
                int px = worldToScreenX(data, k.capitalX(), mapLeft, mapW);
                int pz = worldToScreenZ(data, k.capitalZ(), mapTop, mapH);
                if (Math.abs(mouseX - px) <= 8 && Math.abs(mouseY - pz) <= 8) {
                    selectedKingdom = k.name();
                    selectedSettlement = "Capital @ " + k.capitalX() + ", " + k.capitalZ();
                    return true;
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode >= 49 && keyCode <= 52) { // 1-4
            overlayMode = keyCode - 49;
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
