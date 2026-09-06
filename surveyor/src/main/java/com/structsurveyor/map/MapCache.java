package com.structsurveyor.map;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

import net.minecraft.client.Minecraft;

import net.minecraftforge.common.DimensionManager;

import com.structsurveyor.StructureSurveyor;

/**
 * Keeps map state alive between openings.
 *
 * Without this the GuiScreen built everything in initGui and threw it away in
 * onGuiClosed, so every press of the key re-decoded every visible region and
 * re-ran every scan. State is held per dimension and only dropped when the world
 * itself changes.
 */
public final class MapCache {

    private static final Map<Integer, MapTiles> tiles = new HashMap<Integer, MapTiles>();
    private static final Map<Integer, SignatureScan> scans = new HashMap<Integer, SignatureScan>();
    private static final Map<Integer, UserMarks> marks = new HashMap<Integer, UserMarks>();
    /** Identifies the loaded save, so switching worlds cannot reuse its imagery. */
    private static String worldKey;

    private MapCache() {}

    /** Directory holding this dimension's region files, or null if unavailable. */
    public static File dimensionDir(int dimension) {
        try {
            File root = DimensionManager.getCurrentSaveRootDirectory();
            if (root == null) return null;
            String sub = Minecraft.getMinecraft().theWorld.provider.getSaveFolder();
            return (sub == null || sub.isEmpty()) ? root : new File(root, sub);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Where persistent caches live: gamedir/surveyor/cache/<world>/DIM<n>. */
    private static File cacheDir(int dimension) {
        return new File(new File(Minecraft.getMinecraft().mcDataDir,
                                 "surveyor/cache/" + worldName()), "DIM" + dimension);
    }

    /**
     * A folder name for the world being played.
     *
     * On a server the client owns no save directory, so the address identifies
     * the world instead. Getting this wrong matters more there than in
     * singleplayer: terrain harvested from live chunks exists nowhere else, so
     * showing one server's map on another would be both wrong and unfixable.
     */
    private static String worldName() {
        File root = DimensionManager.getCurrentSaveRootDirectory();
        if (root != null) return sanitize(root.getName());
        try {
            // func_147104_D is getCurrentServerData; 1.7.10's mappings leave it
            // unnamed, and the SRG name is what reobfuscation expects anyway.
            net.minecraft.client.multiplayer.ServerData sd =
                Minecraft.getMinecraft().func_147104_D();
            if (sd != null && sd.serverIP != null && !sd.serverIP.isEmpty()) {
                return "server_" + sanitize(sd.serverIP);
            }
        } catch (Throwable ignored) {
            // no server data yet; fall through
        }
        return "unknown";
    }

    private static String sanitize(String s) {
        return s.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static void checkWorld() {
        File root = DimensionManager.getCurrentSaveRootDirectory();
        String key = root == null ? worldName() : root.getAbsolutePath();
        if (worldKey != null && !worldKey.equals(key)) invalidate();
        worldKey = key;
    }

    public static synchronized MapTiles tiles(int dimension) {
        checkWorld();
        MapTiles t = tiles.get(dimension);
        if (t == null) {
            t = new MapTiles(dimensionDir(dimension), cacheDir(dimension));
            t.setScan(scan(dimension));
            tiles.put(dimension, t);
        }
        return t;
    }

    public static synchronized SignatureScan scan(int dimension) {
        checkWorld();
        SignatureScan s = scans.get(dimension);
        if (s == null) {
            s = new SignatureScan(dimension);
            s.load(scanFile(dimension));
            scans.put(dimension, s);
        }
        return s;
    }

    public static synchronized UserMarks marks(int dimension) {
        checkWorld();
        UserMarks m = marks.get(dimension);
        if (m == null) {
            m = new UserMarks(new File(cacheDir(dimension), "visited.txt"));
            marks.put(dimension, m);
        }
        return m;
    }

    /** Clear a dimension's findings and delete its cached scan. */
    public static synchronized void resetScan(int dimension) {
        SignatureScan s = scans.get(dimension);
        if (s != null) s.clear();
        File f = scanFile(dimension);
        if (f.isFile() && !f.delete()) {
            StructureSurveyor.LOG.debug("could not delete " + f);
        }
    }

    private static File scanFile(int dimension) {
        return new File(cacheDir(dimension), "signatures.bin");
    }

    /** Persist scan results. Called when the map closes and on world unload. */
    public static synchronized void flush() {
        for (UserMarks m : marks.values()) m.save();
        for (Map.Entry<Integer, SignatureScan> e : scans.entrySet()) {
            try {
                e.getValue().save(scanFile(e.getKey()));
            } catch (Throwable t) {
                StructureSurveyor.LOG.debug("could not save scan for DIM" + e.getKey());
            }
        }
    }

    private static volatile boolean invalidatePending;

    /**
     * Ask for invalidation from any thread.
     *
     * Disconnection is signalled on a network thread, and deleting GL textures
     * from anything but the render thread is undefined behaviour - the kind that
     * takes the process down with no Java stack trace. So flag it here and let
     * the client tick do the work.
     */
    public static void requestInvalidate() {
        invalidatePending = true;
    }

    /** Called from the client thread each tick. */
    public static void tick() {
        if (!invalidatePending) return;
        invalidatePending = false;
        invalidate();
    }

    /** Drop everything, releasing GL textures. Client thread only. */
    public static synchronized void invalidate() {
        flush();
        for (MapTiles t : tiles.values()) t.dispose();
        tiles.clear();
        scans.clear();
        marks.clear();
        worldKey = null;
    }
}
