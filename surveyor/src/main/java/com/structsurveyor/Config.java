package com.structsurveyor;

import java.io.File;

import net.minecraftforge.common.config.Configuration;

/**
 * Settings, read from config/structsurveyor.cfg.
 *
 * `enabled = false` is a genuine off switch: no command, no keybind, no event
 * handlers, nothing cached. It exists so the mod can be taken out of the picture
 * by editing one line, without moving jars around or reinstalling the pack -
 * which matters when you are diagnosing whether it is responsible for a
 * stutter.
 */
public final class Config {

    /** Master switch. False means the mod registers nothing at all. */
    public static boolean enabled = true;
    /** The map screen and its keybind. */
    public static boolean enableMap = true;
    /** Block and spawner signature detection while the map reads chunks. */
    public static boolean enableScan = true;
    /** Milliseconds per frame the map may spend decoding. */
    public static int frameBudgetMillis = 5;
    /** Region images held in memory; each is about 1 MB of heap plus 1 MB of VRAM. */
    public static int maxResidentRegions = 64;
    /** Write built imagery to disk so reopening does not rebuild it. */
    public static boolean cacheToDisk = true;
    /** Default radius, in blocks, for a scan around the player. */
    public static int scanRadiusBlocks = 1000;
    /** Fill opacity, 0-255, for candidate markers that are not confirmed. */
    public static int candidateAlpha = 60;
    /** Record terrain from loaded chunks on servers, with the map closed. */
    public static boolean harvestWhileWalking = true;
    /** Chunk radius recorded around the player while walking a server. */
    public static int harvestRadiusChunks = 8;
    /**
     * Run signature detection on a server you do not own.
     *
     * Off by default, and deliberately so. Mapping terrain you have walked
     * through is what any minimap does, but reading loaded chunks for spawners
     * and buried blocks finds things through solid rock, and plenty of servers
     * treat that as cheating whatever the mod is called. Turning it on is a
     * choice about that server's rules, so it is left to be made explicitly.
     */
    public static boolean scanOnRemoteServers = false;

    private Config() {}

    public static void load(File file) {
        Configuration cfg = new Configuration(file);
        try {
            cfg.load();
            enabled = cfg.getBoolean("enabled", "general", enabled,
                "Master switch. False disables the mod entirely: no /survey command, "
                    + "no map keybind, no scanning, nothing held in memory.");
            enableMap = cfg.getBoolean("enableMap", "general", enableMap,
                "The in-game map screen and its keybind.");
            enableScan = cfg.getBoolean("enableScan", "general", enableScan,
                "Detect Roguelike/Lootgames/space dungeons from blocks and spawners "
                    + "while the map reads chunks.");
            frameBudgetMillis = cfg.getInt("frameBudgetMillis", "performance",
                frameBudgetMillis, 1, 50,
                "Milliseconds per frame the map may spend decoding chunks. Lower this "
                    + "if opening the map stutters; raise it to fill the map faster.");
            maxResidentRegions = cfg.getInt("maxResidentRegions", "performance",
                maxResidentRegions, 4, 256,
                "Region images kept in memory. Each costs roughly 1 MB of heap and "
                    + "1 MB of video memory.");
            cacheToDisk = cfg.getBoolean("cacheToDisk", "performance", cacheToDisk,
                "Write built map imagery to surveyor/cache so reopening the map does "
                    + "not rebuild it.");
            candidateAlpha = cfg.getInt("candidateAlpha", "advanced",
                candidateAlpha, 0, 255,
                "Fill opacity of candidate markers - findings that are unverified or "
                    + "that a stronger signature overlaps. 0 leaves only the outline.");
            harvestWhileWalking = cfg.getBoolean("harvestWhileWalking", "server",
                harvestWhileWalking,
                "On a server, record terrain from loaded chunks as you travel, even "
                    + "with the map closed. Without this the map only fills in while "
                    + "it is open. No effect in singleplayer, where region files are "
                    + "read directly.");
            harvestRadiusChunks = cfg.getInt("harvestRadiusChunks", "server",
                harvestRadiusChunks, 2, 16,
                "Chunk radius recorded around you while travelling a server. Larger "
                    + "captures more per pass; it cannot exceed the server's view "
                    + "distance, since unsent chunks do not exist client-side.");
            scanOnRemoteServers = cfg.getBoolean("scanOnRemoteServers", "server",
                scanOnRemoteServers,
                "Run block and spawner detection on servers you do not own. OFF by "
                    + "default: mapping terrain you have walked through is ordinary "
                    + "minimap behaviour, but scanning loaded chunks reveals dungeons "
                    + "and spawners through solid rock, which many servers forbid. "
                    + "Enabling it is your call and your responsibility.");
            scanRadiusBlocks = cfg.getInt("scanRadiusBlocks", "general",
                scanRadiusBlocks, 64, 64000,
                "Default radius in blocks for the scan-around-me key on the map. "
                    + "Adjustable in the map itself with [ and ].");
        } catch (Throwable t) {
            StructureSurveyor.LOG.error("Could not read config; using defaults", t);
        } finally {
            if (cfg.hasChanged()) cfg.save();
        }
    }
}
