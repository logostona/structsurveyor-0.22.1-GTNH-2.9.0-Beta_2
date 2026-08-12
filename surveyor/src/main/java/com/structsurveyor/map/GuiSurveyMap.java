package com.structsurveyor.map;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.util.EnumChatFormatting;

import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;

import com.structsurveyor.map.MapMarkers.Marker;

/**
 * Top-down map of the surveyed world.
 *
 * Terrain comes from the save's heightmap and biome data (see MapTiles), drawn
 * one pixel per block, with structure markers on top. Drag to pan, wheel to
 * zoom, click a marker to copy its coordinates to chat.
 */
public class GuiSurveyMap extends GuiScreen {

    private static final int[] PALETTE = {
        0xFF4A9EFF, 0xFFFF5A5A, 0xFF35D6A0, 0xFFFFB300, 0xFFA974FF,
        0xFFFF6BC1, 0xFF25C7C7, 0xFFFF8A3D, 0xFF7C86FF, 0xFF9BD64A,
    };

    /**
      * Colours pinned per structure type, so a layer keeps the same colour
      * regardless of what order things are discovered in. Spawner cluster is
      * deliberately muted: it is the catch-all and should not compete with the
      * things worth travelling to.
      */
    private static final Map<String, Integer> FIXED_COLORS = new LinkedHashMap<String, Integer>();
    static {
        FIXED_COLORS.put("Roguelike dungeon", 0xFFB14BFF);   // violet
        FIXED_COLORS.put("Lootgames dungeon", 0xFFFFB300);   // amber
        FIXED_COLORS.put("Space dungeon", 0xFF25C7C7);       // cyan
        FIXED_COLORS.put("Mars dungeon", 0xFFFF6B35);        // orange-red
        FIXED_COLORS.put("HEE dungeon", 0xFF9BD64A);         // lime
        FIXED_COLORS.put("Spawner cluster", 0xFF7A8896);     // muted slate
        FIXED_COLORS.put("AE2 meteorite", 0xFFE8E4FF);       // pale sky-stone
        FIXED_COLORS.put("Slime island", 0xFF6BE04A);        // slime green
        FIXED_COLORS.put("Stone circle", 0xFF4AC7FF);        // water blue
        FIXED_COLORS.put("Hilltop stones", 0xFFC08CFF);      // obsidian violet
        FIXED_COLORS.put("Wizard tower", 0xFFFF4FA3);        // magenta
    }

    private final Map<String, Integer> colorByKind = new LinkedHashMap<String, Integer>();
    private final Map<String, Boolean> shown = new LinkedHashMap<String, Boolean>();
    private final List<Marker> markers = new ArrayList<Marker>();

    private MapTiles tiles;
    private SignatureScan scan;
    private UserMarks visited;
    private int scanRevision = -1;
    private int dimension;

    /** Blocks per pixel is 1/zoom; centre is in world coordinates. */
    private double centerX, centerZ;
    private double zoom = 0.5;

    private int dragButton = -1;
    private int dragStartX, dragStartY;
    private double dragOriginX, dragOriginZ;
    private String status = "";
    /** Radius for the scan-around-me key, adjustable with [ and ] or typed. */
    private int scanRadius = com.structsurveyor.Config.scanRadiusBlocks;
    private GuiTextField radiusField;
    private java.awt.Point lastHover;
    /** Screen y of each sidebar row, filled while drawing so clicks line up. */
    private final List<int[]> rowHits = new ArrayList<int[]>();
    private final List<String> rowKinds = new ArrayList<String>();

    @Override
    public void initGui() {
        Minecraft mc = Minecraft.getMinecraft();
        dimension = mc.thePlayer != null ? mc.thePlayer.dimension : 0;
        centerX = mc.thePlayer != null ? mc.thePlayer.posX : 0;
        centerZ = mc.thePlayer != null ? mc.thePlayer.posZ : 0;

        // Shared, not rebuilt: reopening the map should be instant.
        tiles = MapCache.tiles(dimension);
        scan = MapCache.scan(dimension);
        visited = MapCache.marks(dimension);
        radiusField = new GuiTextField(fontRendererObj, 9, height - 26, 84, 14);
        radiusField.setMaxStringLength(6);
        radiusField.setText(String.valueOf(scanRadius));
        refreshMarkers();
        if (!tiles.available()) {
            status = "No terrain data (remote server?) - markers only";
        } else if (markers.isEmpty()) {
            status = "No structures known here. Run /survey to predict some.";
        }
    }

    /**
     * Rebuild the marker list: recorded structures and this session's
     * predictions, plus whatever the signature scan has turned up so far. The
     * scan fills in progressively, so this re-runs whenever it finds something.
     */
    private void refreshMarkers() {
        markers.clear();
        markers.addAll(MapMarkers.forDimension(dimension));
        if (scan != null) {
            markers.addAll(scan.markers());
            scanRevision = scan.revision();
        }
        for (Marker m : markers) {
            if (!colorByKind.containsKey(m.kind)) {
                Integer fixed = FIXED_COLORS.get(m.kind);
                colorByKind.put(m.kind, fixed != null ? fixed
                    : PALETTE[colorByKind.size() % PALETTE.length]);
                shown.put(m.kind, Boolean.TRUE);
            }
        }
    }

    @Override
    public void onGuiClosed() {
        // Imagery stays resident for the next opening; scan results and
        // visited marks need writing out.
        MapCache.flush();
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    // ---- coordinate transforms -------------------------------------------

    private double screenX(double worldX) { return (worldX - centerX) * zoom + width / 2.0; }
    private double screenY(double worldZ) { return (worldZ - centerZ) * zoom + height / 2.0; }
    private double worldX(double sx) { return (sx - width / 2.0) / zoom + centerX; }
    private double worldZ(double sy) { return (sy - height / 2.0) / zoom + centerZ; }

    // ---- rendering -------------------------------------------------------

    @Override
    public void drawScreen(int mouseX, int mouseY, float partial) {
        drawRect(0, 0, width, height, 0xFF10141A);
        if (tiles != null && tiles.available()) {
            // One allowance for the whole frame. Handing terrain and scanning
            // separate budgets doubled the work each frame could do.
            long[] budget = MapTiles.frameBudget();
            drawTerrain(budget);
            if (tiles.scanning()) tiles.advanceScan(budget);
            // Decoding happens on a worker; this is where finished regions are
            // uploaded and their detections committed.
            tiles.drainDecoded(budget);
            // Chunks around the player may not be on disk yet; take those from
            // the loaded world so the map is not blank where you are standing.
            Minecraft mcl = Minecraft.getMinecraft();
            if (mcl.theWorld != null && mcl.thePlayer != null) {
                tiles.patchFromLiveWorld(mcl.theWorld,
                    ((int) Math.floor(mcl.thePlayer.posX)) >> 4,
                    ((int) Math.floor(mcl.thePlayer.posZ)) >> 4, 12, budget);
            }
        }
        if (scan != null && scan.revision() != scanRevision) refreshMarkers();
        drawGrid();
        drawVisited();
        drawMarkers();
        drawPlayer();
        drawSidebar(mouseX, mouseY);
        drawHover(mouseX, mouseY);
        super.drawScreen(mouseX, mouseY, partial);
    }

    private void drawTerrain(long[] budget) {
        double minX = worldX(0), maxX = worldX(width);
        double minZ = worldZ(0), maxZ = worldZ(height);

        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glColor4f(1f, 1f, 1f, 1f);
        GL11.glDisable(GL11.GL_BLEND);
        java.util.List<int[]> visible = tiles.regionsIn(minX, minZ, maxX, maxZ);
        // Past the resident limit, stop building new imagery and just draw what
        // is cached - otherwise a wide view evicts and re-decodes on every frame.
        boolean allowCreate = visible.size() <= MapTiles.residentLimit();
        for (int[] r : visible) {
            int tex = tiles.textureFor(r[0], r[1], budget, allowCreate);
            if (tex < 0) continue;
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, tex);
            // Crisp blocks when zoomed in; the default smoothing turns the map
            // into mush at 1 pixel per block.
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
            // Without clamping, the default REPEAT lets an edge pixel sample from
            // the opposite side of the texture - a wrong-coloured line along every
            // region border.
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S,
                                 org.lwjgl.opengl.GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T,
                                 org.lwjgl.opengl.GL12.GL_CLAMP_TO_EDGE);

            double wx = r[0] * (double) MapTiles.REGION_PX;
            double wz = r[1] * (double) MapTiles.REGION_PX;
            double x0 = screenX(wx), y0 = screenY(wz);
            double x1 = screenX(wx + MapTiles.REGION_PX), y1 = screenY(wz + MapTiles.REGION_PX);

            Tessellator t = Tessellator.instance;
            t.startDrawingQuads();
            t.addVertexWithUV(x0, y1, 0, 0, 1);
            t.addVertexWithUV(x1, y1, 0, 1, 1);
            t.addVertexWithUV(x1, y0, 0, 1, 0);
            t.addVertexWithUV(x0, y0, 0, 0, 0);
            t.draw();
        }
        GL11.glEnable(GL11.GL_BLEND);
    }

    private void drawGrid() {
        double step = 16;
        while (step * zoom < 48) step *= 4;
        int line = 0x22FFFFFF;
        double startX = Math.floor(worldX(0) / step) * step;
        for (double x = startX; x < worldX(width); x += step) {
            int sx = (int) Math.round(screenX(x));
            drawRect(sx, 0, sx + 1, height, line);
        }
        double startZ = Math.floor(worldZ(0) / step) * step;
        for (double z = startZ; z < worldZ(height); z += step) {
            int sy = (int) Math.round(screenY(z));
            drawRect(0, sy, width, sy + 1, line);
        }
        // Origin axes
        int ax = (int) Math.round(screenX(0)), az = (int) Math.round(screenY(0));
        drawRect(ax, 0, ax + 1, height, 0x55FFFFFF);
        drawRect(0, az, width, az + 1, 0x55FFFFFF);
    }

    private void drawMarkers() {
        for (Marker m : markers) {
            if (Boolean.FALSE.equals(shown.get(m.kind))) continue;
            int sx = (int) Math.round(screenX(m.x)), sy = (int) Math.round(screenY(m.z));
            if (sx < -8 || sy < -8 || sx > width + 8 || sy > height + 8) continue;
            int c = colorByKind.get(m.kind);
            if ("scanned".equals(m.source)) {
                double[][] d = { { sx, sy - 5 }, { sx + 5, sy }, { sx, sy + 5 }, { sx - 5, sy } };
                drawPolygon(outset(d, sx, sy, 1.3), 0x99000000);
                if (m.reliable) {
                    drawPolygon(d, c);
                } else {
                    // Candidate: mostly outline, so it reads as "maybe" at a
                    // glance without competing with a confirmed finding.
                    int alpha = Math.max(0, Math.min(255,
                        com.structsurveyor.Config.candidateAlpha));
                    if (alpha > 0) drawPolygon(d, (c & 0xFFFFFF) | (alpha << 24));
                    drawThickLine(sx, sy - 5, sx + 5, sy, 1.0, c);
                    drawThickLine(sx + 5, sy, sx, sy + 5, 1.0, c);
                    drawThickLine(sx, sy + 5, sx - 5, sy, 1.0, c);
                    drawThickLine(sx - 5, sy, sx, sy - 5, 1.0, c);
                }
                continue;
            }
            if (m.reliable) {
                drawRect(sx - 3, sy - 3, sx + 3, sy + 3, c);
                drawRect(sx - 4, sy - 4, sx + 4, sy - 3, 0x66000000);
            } else {
                // Hollow: unverified prediction. Never let it look like fact.
                drawRect(sx - 3, sy - 3, sx + 3, sy - 2, c);
                drawRect(sx - 3, sy + 2, sx + 3, sy + 3, c);
                drawRect(sx - 3, sy - 3, sx - 2, sy + 3, c);
                drawRect(sx + 2, sy - 3, sx + 3, sy + 3, c);
            }
        }
    }

    /**
     * The player as a facing arrow, plus a separate line for where they are
     * looking.
     *
     * Minecraft yaw 0 faces south (+Z) and increases toward west, so the unit
     * heading is (-sin y, cos y). This map draws +X right and +Z down, so that
     * vector is usable directly as a screen direction with no extra rotation.
     *
     * The arrow uses renderYawOffset (the body) and the line uses
     * rotationYawHead (the head), which diverge whenever you walk one way and
     * look another - strafing, or turning while running.
     */
    /** Hand-placed "been here" marks: a plain X, distinct from any detection. */
    private void drawVisited() {
        if (visited == null) return;
        for (int[] m : visited.all()) {
            double sx = screenX(m[0]), sy = screenY(m[1]);
            if (sx < -8 || sy < -8 || sx > width + 8 || sy > height + 8) continue;
            drawThickLine(sx - 5, sy - 5, sx + 5, sy + 5, 2.1, 0xCC000000);
            drawThickLine(sx + 5, sy - 5, sx - 5, sy + 5, 2.1, 0xCC000000);
            drawThickLine(sx - 5, sy - 5, sx + 5, sy + 5, 1.2, 0xFFFFFFFF);
            drawThickLine(sx + 5, sy - 5, sx - 5, sy + 5, 1.2, 0xFFFFFFFF);
        }
    }

    private void drawPlayer() {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer == null) return;
        double px = screenX(mc.thePlayer.posX);
        double pz = screenY(mc.thePlayer.posZ);

        // rotationYaw, not renderYawOffset. renderYawOffset is the body angle
        // derived from movement and sits 180 degrees out whenever you walk
        // backwards, which made the arrow point the wrong way.
        double bodyRad = Math.toRadians(mc.thePlayer.rotationYaw);
        double bdx = -Math.sin(bodyRad), bdz = Math.cos(bodyRad);
        double headRad = Math.toRadians(mc.thePlayer.rotationYawHead);
        double hdx = -Math.sin(headRad), hdz = Math.cos(headRad);

        double nx = bdz, nz = -bdx;                 // body normal, for the base
        double tip = 10.0, back = 5.5, half = 5.2;
        double[][] arrow = {
            { px + bdx * tip, pz + bdz * tip },
            { px - bdx * back + nx * half, pz - bdz * back + nz * half },
            { px - bdx * back * 0.3, pz - bdz * back * 0.3 },     // notched tail
            { px - bdx * back - nx * half, pz - bdz * back - nz * half },
        };
        drawPolygon(outset(arrow, px, pz, 1.7), 0xEE000000);      // outline
        drawPolygon(arrow, 0xFFFFFFFF);

        // The head rides on top of the arrow: a small disc at the player's
        // position with a short nub for where they are looking. It aligns with
        // the arrow when head and body agree and visibly swings off it when they
        // do not, without adding a second shape to read.
        drawThickLine(px, pz, px + hdx * 7.5, pz + hdz * 7.5, 1.3, 0xFF12241F);
        drawThickLine(px, pz, px + hdx * 7.0, pz + hdz * 7.0, 0.9, 0xFF38E0D0);
        drawPolygon(disc(px, pz, 3.3), 0xFF12241F);
        drawPolygon(disc(px, pz, 2.3), 0xFF38E0D0);
    }

    /**
     * Teleport to whatever the cursor is over, landing on the surface.
     *
     * The Y comes from the chunk's HeightMap rather than the marker, because a
     * marker can sit deep underground - arriving inside a dungeon ceiling is
     * worse than arriving above it. Sent as a command, so it needs the same
     * permission /survey does.
     */
    /** Read the typed radius, clamped to the same range the config allows. */
    private void applyTypedRadius() {
        try {
            int v = Integer.parseInt(radiusField.getText().trim());
            scanRadius = Math.max(64, Math.min(64000, v));
        } catch (NumberFormatException e) {
            status = "Radius must be a number";
        }
        radiusField.setText(String.valueOf(scanRadius));
        radiusField.setFocused(false);
        status = "Scan radius " + scanRadius + " - press Z to scan";
    }

    private void teleportToHovered() {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer == null) return;
        java.awt.Point p = lastHover;
        Marker m = p == null ? null : markerAt(p.x, p.y);
        if (m == null) {
            status = "Point at a marker, then press T";
            return;
        }
        // Analyse the column and stand on top of whatever is there. Using the
        // heightmap alone could drop the player inside a structure, and left
        // chunks with no heightmap untravelable.
        int destY = tiles == null ? -1 : tiles.safeStandY(m.x, m.z);
        if (destY <= 0) {
            status = "That chunk has not generated yet - nothing to stand on";
            return;
        }
        // Close the map before moving. An earlier edit removed this line, so the
        // screen stayed open over the new location.
        mc.displayGuiScreen(null);
        if (!teleportServerSide(m.x, destY, m.z)) {
            mc.thePlayer.sendChatMessage("/tp " + m.x + " " + destY + " " + m.z);
        }
    }

    /**
     * Move the player directly on the integrated server, clearing fall distance.
     *
     * Doing it here rather than through /tp means the arrival cannot be counted
     * as a fall, and it does not depend on cheats being enabled. Returns false on
     * a remote server, where the client has no authority and /tp is the only
     * option.
     */
    private boolean teleportServerSide(int x, int y, int z) {
        try {
            net.minecraft.server.MinecraftServer server =
                net.minecraft.server.MinecraftServer.getServer();
            if (server == null) return false;
            String name = Minecraft.getMinecraft().thePlayer.getCommandSenderName();
            net.minecraft.entity.player.EntityPlayerMP target =
                server.getConfigurationManager().func_152612_a(name);
            if (target == null) return false;
            target.mountEntity(null);              // riding blocks repositioning
            target.setPositionAndUpdate(x + 0.5D, y, z + 0.5D);
            target.fallDistance = 0.0F;
            target.velocityChanged = true;
            return true;
        } catch (Throwable t) {
            com.structsurveyor.StructureSurveyor.LOG.debug("direct teleport unavailable", t);
            return false;
        }
    }

    /** Polygon approximating a circle, for the head marker. */
    private static double[][] disc(double cx, double cy, double r) {
        int steps = 10;
        double[][] pts = new double[steps][2];
        for (int i = 0; i < steps; i++) {
            double a = i * 2 * Math.PI / steps;
            pts[i][0] = cx + Math.cos(a) * r;
            pts[i][1] = cy + Math.sin(a) * r;
        }
        return pts;
    }

    /** Scale a polygon about a centre, to fake an outline behind the fill. */
    private static double[][] outset(double[][] pts, double cx, double cy, double px) {
        double[][] out = new double[pts.length][2];
        for (int i = 0; i < pts.length; i++) {
            double dx = pts[i][0] - cx, dy = pts[i][1] - cy;
            double len = Math.max(0.0001, Math.sqrt(dx * dx + dy * dy));
            out[i][0] = pts[i][0] + dx / len * px;
            out[i][1] = pts[i][1] + dy / len * px;
        }
        return out;
    }

    private void drawPolygon(double[][] pts, int argb) {
        beginShapes(argb);
        Tessellator t = Tessellator.instance;
        t.startDrawing(GL11.GL_TRIANGLE_FAN);
        t.setColorRGBA_I(argb & 0xFFFFFF, (argb >>> 24) & 0xFF);
        for (double[] p : pts) t.addVertex(p[0], p[1], 0);
        t.draw();
        endShapes();
    }

    private void drawThickLine(double x1, double y1, double x2, double y2,
                               double halfWidth, int argb) {
        double dx = x2 - x1, dy = y2 - y1;
        double len = Math.max(0.0001, Math.sqrt(dx * dx + dy * dy));
        double nx = -dy / len * halfWidth, ny = dx / len * halfWidth;
        drawPolygon(new double[][] {
            { x1 + nx, y1 + ny }, { x2 + nx, y2 + ny },
            { x2 - nx, y2 - ny }, { x1 - nx, y1 - ny },
        }, argb);
    }

    private void beginShapes(int argb) {
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glDisable(GL11.GL_ALPHA_TEST);
    }

    private void endShapes() {
        GL11.glEnable(GL11.GL_ALPHA_TEST);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glColor4f(1f, 1f, 1f, 1f);
    }

    private void drawSidebar(int mouseX, int mouseY) {
        int w = 150;
        drawRect(0, 0, w, height, 0xD00B0E13);
        drawRect(w, 0, w + 1, height, 0x33FFFFFF);

        rowHits.clear();
        rowKinds.clear();
        int y = 8;
        fontRendererObj.drawString(EnumChatFormatting.BOLD + "Structure Surveyor", 8, y, 0xFFFFFF);
        y += 12;
        fontRendererObj.drawString("DIM" + dimension + "  ·  " + markers.size() + " markers",
                                   8, y, 0x9AA5B1);
        y += 16;

        for (Map.Entry<String, Integer> e : colorByKind.entrySet()) {
            boolean on = !Boolean.FALSE.equals(shown.get(e.getKey()));
            int count = 0;
            boolean anyUnreliable = false;
            for (Marker m : markers) {
                if (m.kind.equals(e.getKey())) {
                    count++;
                    if (!m.reliable) anyUnreliable = true;
                }
            }
            rowHits.add(new int[] { y - 2, y + 9 });
            rowKinds.add(e.getKey());
            boolean hot = mouseX < w && mouseY >= y - 2 && mouseY <= y + 9;
            if (hot) drawRect(4, y - 2, w - 4, y + 9, 0x22FFFFFF);
            drawRect(8, y + 1, 15, y + 8, on ? e.getValue() : 0xFF3A4048);
            String label = e.getKey();
            if (label.length() > 19) label = label.substring(0, 18) + "…";
            fontRendererObj.drawString(label, 20, y, on ? 0xE8ECF1 : 0x5B6570);
            String n = String.valueOf(count);
            fontRendererObj.drawString(n, w - 8 - fontRendererObj.getStringWidth(n), y,
                                       anyUnreliable ? 0xE0A030 : 0x9AA5B1);
            y += 12;
        }

        y += 6;
        fontRendererObj.drawString("filled = confirmed", 8, y, 0x6B7480); y += 10;
        fontRendererObj.drawString("hollow = unverified", 8, y, 0x6B7480); y += 14;
        fontRendererObj.drawString("drag pan · wheel zoom", 8, y, 0x6B7480); y += 10;
        fontRendererObj.drawString("click marker → chat", 8, y, 0x6B7480); y += 10;
        fontRendererObj.drawString("R centre on player", 8, y, 0x6B7480); y += 10;
        fontRendererObj.drawString("S scan whole world", 8, y, 0x6B7480); y += 10;
        fontRendererObj.drawString("D dump signature stats", 8, y, 0x6B7480); y += 10;
        if (tiles != null) {
            fontRendererObj.drawString(tiles.pipeline(), 8, y, 0x6B7480);
            y += 10;
            if (tiles.lastError() != null) {
                fontRendererObj.drawString(tiles.lastError().substring(0,
                    Math.min(34, tiles.lastError().length())), 8, y, 0xFF6B6B);
                y += 10;
            }
        }
        if (scan != null && scan.lastClusterMicros() > 0) {
            // Surfaced so the next round of tuning can be measured.
            fontRendererObj.drawString("cluster "
                + String.format("%.2f", scan.lastClusterMicros() / 1000.0) + " ms",
                8, y, 0x6B7480);
            y += 10;
        }
        fontRendererObj.drawString("T teleport to marker", 8, y, 0x6B7480); y += 10;
        fontRendererObj.drawString("right-click mark visited", 8, y, 0x6B7480); y += 10;
        fontRendererObj.drawString("Z scan " + scanRadius + " blocks  [ ]",
                                   8, y, 0x6B7480); y += 10;
        fontRendererObj.drawString("C clear + rescan", 8, y, 0x6B7480); y += 10;
        fontRendererObj.drawString("A show overlaps", 8, y, 0x6B7480); y += 12;
        if (scan != null && scan.suppressedCount() > 0 && !scan.showingSuppressed()) {
            fontRendererObj.drawString(scan.suppressedCount()
                + " overlapping hidden (A)", 8, y, 0xE0A030);
            y += 12;
        }
        if (visited != null && !visited.all().isEmpty()) {
            fontRendererObj.drawString(visited.all().size() + " visited marks",
                                       8, y, 0x6B7480);
            y += 12;
        }
        if (scan != null) {
            fontRendererObj.drawString(scan.scannedChunkCount() + " chunks scanned",
                                       8, y, 0x6B7480);
        }

        if (tiles != null && tiles.scanning()) {
            fontRendererObj.drawString(tiles.scanProgress(), 8, height - 26, 0x38E0D0);
        }

        if (radiusField != null) {
            fontRendererObj.drawString("radius (enter)", 9, height - 38, 0x6B7480);
            radiusField.drawTextBox();
        }
        if (!status.isEmpty()) {
            fontRendererObj.drawString(status, 8, height - 14, 0xE0A030);
        }
    }

    private Marker markerAt(int mouseX, int mouseY) {
        Marker best = null;
        double bestD = 36;
        for (Marker m : markers) {
            if (Boolean.FALSE.equals(shown.get(m.kind))) continue;
            double dx = screenX(m.x) - mouseX, dz = screenY(m.z) - mouseY;
            double d = dx * dx + dz * dz;
            if (d < bestD) { bestD = d; best = m; }
        }
        return best;
    }

    private void drawHover(int mouseX, int mouseY) {
        lastHover = new java.awt.Point(mouseX, mouseY);
        int bx = (int) Math.floor(worldX(mouseX)), bz = (int) Math.floor(worldZ(mouseY));
        String coords = "X " + bx + "   Z " + bz + "      " + String.format("%.2f", zoom) + "x";
        int cw = fontRendererObj.getStringWidth(coords) + 10;
        drawRect(width - cw - 6, height - 20, width - 6, height - 6, 0xC00B0E13);
        fontRendererObj.drawString(coords, width - cw - 1, height - 16, 0x9AA5B1);

        Marker m = markerAt(mouseX, mouseY);
        if (m == null || mouseX < 152) return;

        List<String> lines = new ArrayList<String>();
        lines.add(EnumChatFormatting.WHITE + m.kind);
        if (!m.subtitle.isEmpty()) {
            lines.add(EnumChatFormatting.AQUA + m.subtitle);
        }
        lines.add(EnumChatFormatting.GRAY + "X " + m.x + "  Z " + m.z);
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer != null) {
            int dist = (int) Math.hypot(m.x - mc.thePlayer.posX, m.z - mc.thePlayer.posZ);
            lines.add(EnumChatFormatting.GRAY + String.valueOf(dist) + " blocks away");
        }
        if (!m.detail.isEmpty()) {
            lines.add(EnumChatFormatting.GRAY + m.detail);
        }
        lines.add((m.reliable ? EnumChatFormatting.GRAY : EnumChatFormatting.GOLD)
                  + m.source + (m.reliable ? "" : " · unverified"));

        // Wrap before measuring. Detail lines carry mob counts, y ranges and
        // overlap notes, which together run far wider than any screen; without a
        // cap the box simply ran off the edge and the text was lost.
        int maxWidth = Math.min(220, Math.max(120, width - 180));
        List<String> wrapped = new ArrayList<String>();
        for (String line : lines) {
            @SuppressWarnings("unchecked")
            List<String> parts = fontRendererObj.listFormattedStringToWidth(line, maxWidth);
            if (parts.isEmpty()) {
                wrapped.add(line);
            } else {
                wrapped.addAll(parts);
            }
        }
        lines = wrapped;

        int tw = 0;
        for (String s : lines) tw = Math.max(tw, fontRendererObj.getStringWidth(s));
        int th = lines.size() * 10 + 6;
        // Flip near an edge, then clamp: flipping alone can still push the box
        // off the opposite side once it is tall or wide.
        int tx = mouseX + 12, ty = mouseY + 12;
        if (tx + tw + 8 > width) tx = mouseX - tw - 14;
        if (ty + th > height) ty = mouseY - th - 4;
        tx = Math.max(154, Math.min(tx, width - tw - 6));
        ty = Math.max(4, Math.min(ty, height - th - 2));

        drawRect(tx - 4, ty - 4, tx + tw + 4, ty + th - 2, 0xEE0B0E13);
        drawRect(tx - 4, ty - 4, tx + tw + 4, ty - 3, 0x44FFFFFF);
        for (int i = 0; i < lines.size(); i++) {
            fontRendererObj.drawString(lines.get(i), tx, ty + i * 10, 0xFFFFFF);
        }
    }

    // ---- input -----------------------------------------------------------

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int button) {
        if (radiusField != null) {
            radiusField.mouseClicked(mouseX, mouseY, button);
            if (radiusField.isFocused()) return;
        }
        if (mouseX < 150) {
            // Use the row bounds recorded while drawing. A hardcoded start
            // offset here was one row out, so clicking a layer toggled its
            // neighbour - which looked exactly like markers refusing to appear.
            for (int i = 0; i < rowHits.size(); i++) {
                int[] bounds = rowHits.get(i);
                if (mouseY >= bounds[0] && mouseY <= bounds[1]) {
                    String kind = rowKinds.get(i);
                    shown.put(kind, Boolean.FALSE.equals(shown.get(kind)));
                    return;
                }
            }
            return;
        }
        Marker m = markerAt(mouseX, mouseY);
        if (m != null && button == 0) {
            Minecraft.getMinecraft().thePlayer.addChatMessage(
                new net.minecraft.util.ChatComponentText(
                    EnumChatFormatting.GRAY + m.kind + ": "
                    + EnumChatFormatting.WHITE + "X " + m.x + " Z " + m.z));
            return;
        }
        if (button == 1 && visited != null) {
            // Right-click marks a spot as visited, or clears the mark already
            // there. Detection can say a structure exists but never whether you
            // have already emptied it.
            int wx = (int) Math.floor(worldX(mouseX));
            int wz = (int) Math.floor(worldZ(mouseY));
            int radius = (int) Math.max(4, 8 / Math.max(0.02, zoom));
            boolean added = visited.toggle(wx, wz, radius);
            status = added ? "Marked visited at X " + wx + " Z " + wz
                           : "Cleared visited mark";
            return;
        }
        dragButton = button;
        dragStartX = mouseX;
        dragStartY = mouseY;
        dragOriginX = centerX;
        dragOriginZ = centerZ;
    }

    @Override
    protected void mouseClickMove(int mouseX, int mouseY, int button, long since) {
        if (dragButton < 0) return;
        centerX = dragOriginX - (mouseX - dragStartX) / zoom;
        centerZ = dragOriginZ - (mouseY - dragStartY) / zoom;
    }

    @Override
    protected void mouseMovedOrUp(int mouseX, int mouseY, int state) {
        if (state != -1) dragButton = -1;
    }

    @Override
    public void handleMouseInput() {
        super.handleMouseInput();
        int wheel = Mouse.getEventDWheel();
        if (wheel == 0) return;
        // Zoom about the cursor so the point under it stays put.
        int mx = Mouse.getEventX() * width / mc.displayWidth;
        int my = height - Mouse.getEventY() * height / mc.displayHeight - 1;
        double ax = worldX(mx), az = worldZ(my);
        zoom = Math.max(0.02, Math.min(8.0, zoom * (wheel > 0 ? 1.25 : 0.8)));
        centerX = ax - (mx - width / 2.0) / zoom;
        centerZ = az - (my - height / 2.0) / zoom;
    }

    @Override
    protected void keyTyped(char c, int key) {
        if (radiusField != null && radiusField.isFocused()) {
            if (key == Keyboard.KEY_RETURN || key == Keyboard.KEY_NUMPADENTER) {
                applyTypedRadius();
                return;
            }
            if (key == Keyboard.KEY_ESCAPE) {
                radiusField.setFocused(false);
                return;
            }
            radiusField.textboxKeyTyped(c, key);
            return;                        // never let a digit trigger a shortcut
        }
        if (key == Keyboard.KEY_R) {
            Minecraft mcc = Minecraft.getMinecraft();
            if (mcc.thePlayer != null) {
                centerX = mcc.thePlayer.posX;
                centerZ = mcc.thePlayer.posZ;
            }
            return;
        }
        if (key == Keyboard.KEY_T) {
            teleportToHovered();
            return;
        }
        if (key == Keyboard.KEY_D && scan != null) {
            Minecraft mcd = Minecraft.getMinecraft();
            mcd.thePlayer.addChatMessage(new net.minecraft.util.ChatComponentText(
                EnumChatFormatting.AQUA + "Signatures in DIM" + dimension + " ("
                + scan.scannedChunkCount() + " chunks scanned):"));
            for (String line : scan.describe()) {
                mcd.thePlayer.addChatMessage(new net.minecraft.util.ChatComponentText(
                    EnumChatFormatting.GRAY + "  " + line));
            }
            java.util.List<String> mobs = scan.describeMobs();
            if (!mobs.isEmpty()) {
                mcd.thePlayer.addChatMessage(new net.minecraft.util.ChatComponentText(
                    EnumChatFormatting.GRAY + "  spawner mobs: " + mobs));
            }
            mcd.displayGuiScreen(null);
            return;
        }
        if (key == Keyboard.KEY_Z) {
            // Bounded scan around the player: the whole-world pass is overkill
            // when you only care about what is within reach.
            Minecraft mcz = Minecraft.getMinecraft();
            if (tiles != null && tiles.available() && mcz.thePlayer != null) {
                int px = (int) Math.floor(mcz.thePlayer.posX);
                int pz = (int) Math.floor(mcz.thePlayer.posZ);
                int regions = tiles.scanRadius(px, pz, scanRadius);
                status = regions == 0
                    ? "Nothing generated within " + scanRadius + " blocks"
                    : "Scanning " + scanRadius + " blocks around you (" + regions
                      + " regions)";
            }
            return;
        }
        if (key == Keyboard.KEY_LBRACKET) {
            scanRadius = Math.max(64, scanRadius - (scanRadius > 1000 ? 500 : 128));
            if (radiusField != null) radiusField.setText(String.valueOf(scanRadius));
            status = "Scan radius " + scanRadius;
            return;
        }
        if (key == Keyboard.KEY_RBRACKET) {
            scanRadius = Math.min(64000, scanRadius + (scanRadius >= 1000 ? 500 : 128));
            if (radiusField != null) radiusField.setText(String.valueOf(scanRadius));
            status = "Scan radius " + scanRadius;
            return;
        }
        if (key == Keyboard.KEY_C) {
            // Reset: drop every finding and rescan from nothing. Results are
            // cumulative and chunk-deduplicated, so without this a stale finding
            // from older rules has no way out.
            if (scan != null) {
                MapCache.resetScan(dimension);
                tiles.forgetLivePatches();
                if (tiles != null && tiles.available()) tiles.scanEverything();
                refreshMarkers();
                status = "Cleared findings; rescanning";
            }
            return;
        }
        if (key == Keyboard.KEY_A && scan != null) {
            scan.setShowSuppressed(!scan.showingSuppressed());
            refreshMarkers();
            status = scan.showingSuppressed()
                ? "Showing overlapping matches too" : "Hiding overlapping matches";
            return;
        }
        if (key == Keyboard.KEY_S) {
            // Imagery only covers what has been panned over; this walks every
            // region file for signatures without building textures for them.
            if (tiles != null && tiles.available()) {
                tiles.scanEverything();
                status = "";
            }
            return;
        }
        if (key == Keyboard.KEY_ADD || key == Keyboard.KEY_EQUALS) {
            zoom = Math.min(8.0, zoom * 1.25);
            return;
        }
        if (key == Keyboard.KEY_MINUS || key == Keyboard.KEY_SUBTRACT) {
            zoom = Math.max(0.02, zoom * 0.8);
            return;
        }
        super.keyTyped(c, key);
    }
}
