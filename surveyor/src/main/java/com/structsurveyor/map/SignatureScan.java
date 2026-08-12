package com.structsurveyor.map;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.block.Block;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import cpw.mods.fml.common.registry.GameRegistry;

import com.structsurveyor.map.MapMarkers.Marker;

/**
 * Finds structures that leave no structure data - Roguelike Dungeons,
 * Lootgames, space dungeons - by their blocks and spawners.
 *
 * These cannot be predicted from the seed (their placement depends on terrain
 * height) and they write no MapGenStructureData, so the only evidence is what
 * they built. The map already decodes every chunk to draw terrain, so this rides
 * along on that same pass rather than reading anything twice.
 *
 * Every rule below that looks arbitrary was earned from a false positive:
 * without the dimension whitelist, Nether fortress blaze spawners read as
 * dungeons; without the vanilla-mob filter, so did BoP wasp nests.
 */
public class SignatureScan {

    private static class Signature {
        final String label;
        final String[] blockNames;
        final boolean spawners;
        boolean vanillaMobsOnly;
        String mobMatch;
        int[] onlyDims;
        int[] notDims;
        int minCluster;
        int radius;
        /** Maximum horizontal extent in blocks, 0 to ignore. */
        int maxExtent;
        /** Lowest Y that counts, 0 to ignore. */
        int minY;
        /**
         * Require the block to be at or near the surface.
         *
         * This is what separates a Hilltop Stone from a dungeon loot chest: both
         * are a chest on obsidian, but one is on a hilltop and the other is
         * buried. Proximity suppression could not fix that, because it only helps
         * when the dungeon itself was also detected.
         */
        boolean surfaceOnly;
        /**
         * Distinct vertical bands the hits must occupy, 0 to ignore.
         *
         * A roguelike dungeon is four stacked floors. Two unrelated dungeons in
         * the same cave also span plenty of Y, but they occupy two bands, not
         * four - which is the difference a plain min/max spread cannot see.
         */
        int minFloors;
        /**
         * Distinct spawner mobs the cluster must contain, 0 to ignore.
         *
         * This is what separates a mineshaft from a dungeon. A mineshaft's
         * spawners are all CaveSpider, and a vanilla dungeon has a single mob
         * too; a roguelike dungeon themes its floors differently and mixes
         * Zombie, Skeleton, Spider, Creeper, Witch and Silverfish. Neither
         * spawner count nor vertical spread can tell those apart, because a
         * mineshaft is both numerous and deep.
         */
        int minMobTypes;
        /**
         * Findings from a lower-priority signature are dropped when a
         * higher-priority one sits nearby. Dungeons contain chests on obsidian
         * and dispensers under water, so without this their contents get
         * reported as separate structures.
         */
        int priority;
        /**
         * Blocks that must sit directly on top of the matched block.
         *
         * This is what makes common blocks usable as signatures: an obsidian
         * block means nothing, but obsidian with a chest on it is a Hilltop
         * Stone, and a dispenser under water or ice is a Stone Circle.
         */
        String[] aboveNames;
        final java.util.Set<Integer> aboveIds = new java.util.HashSet<Integer>();
        /** Minimum vertical spread in blocks, 0 to ignore. */
        int minYSpread;
        /** Whether the biome implies a surface building worth naming. */
        boolean biomeVariant;
        boolean generic;
        boolean nameByMob;

        // Why candidates were discarded. Each constraint here was added to kill
        // one false positive, and stacked they can quietly reject real
        // structures - so record which rule did it rather than guessing.
        int rejSurface, rejAbove;                     // per block, cumulative
        int rejSize, rejMobs, rejFloors, rejSpread;   // per cluster, per pass

        final List<int[]> hits = new ArrayList<int[]>();   // x, y, z
        final List<String> mobs = new ArrayList<String>(); // parallel to hits
        final List<Integer> biomes = new ArrayList<Integer>(); // parallel to hits

        Signature(String label, boolean spawners, String... blockNames) {
            this.label = label;
            this.spawners = spawners;
            this.blockNames = blockNames;
        }
    }

    private final List<Signature> signatures = new ArrayList<Signature>();
    /** block id -> signature, for the ids we resolved in this world. */
    private final Map<Integer, Signature> byBlockId = new HashMap<Integer, Signature>();
    /** Fast reject: is any wanted id's low byte this value? */
    private final boolean[] lowWanted = new boolean[256];

    private final int dimension;
    /** Chunks already examined, so nothing is scanned twice across sessions. */
    // Read by the decode worker, written by the render thread. A plain HashSet
    // read during another thread's write can return nonsense or throw.
    private final java.util.Set<Long> scannedChunks = java.util.Collections.newSetFromMap(
        new java.util.concurrent.ConcurrentHashMap<Long, Boolean>());
    private int revision;
    private List<Marker> cache;
    private int cachedRevision = -1;
    /**
     * Whether findings a stronger signature overlaps are still drawn.
     *
     * On by default: hiding them meant real structures silently vanished from the
     * map with only a sidebar count to hint at it. They are drawn as faint
     * candidates instead, so nothing detected is invisible.
     */
    private boolean showSuppressed = true;
    /** How many findings the last pass hid, so it is never silent. */
    private int suppressedCount;
    private long lastClusterAt;
    private long lastClusterMicros;

    public SignatureScan(int dimension) {
        this.dimension = dimension;
        build();
        resolveBlocks();
    }

    private void build() {
        Signature loot = new Signature("Lootgames dungeon", false,
            "lootgames:ms_master", "lootgames:sdk_master", "lootgames:gol_master",
            "lootgames:LootGamesMasterBlock", "lootgames:GOLMasterBlock");
        loot.minCluster = 1;              // one master block is one dungeon
        loot.radius = 32;
        loot.priority = 10;
        signatures.add(loot);

        Signature rogue = new Signature("Roguelike dungeon", true);
        // Identified by sheer spawner count, not by mob id. Requiring vanilla
        // mobs found nothing: GTNH ships SpecialMobs, so dungeon spawners carry
        // namespaced ids and every hit was being discarded.
        //
        // Size separates these cleanly on real data: measured roguelike dungeons
        // hold 127-136 spawners, BoP wasp nests 4-6, a vanilla dungeon exactly 1.
        // 25 sits far above the noise and needs no knowledge of mob names.
        // Spawner count alone was the wrong test. Measured dungeons ran 132, 128,
        // 125, 41 and 4 spawners, so any threshold high enough to exclude wasp
        // nests also threw away the small dungeons - which is why a castle tower
        // standing right in front of you failed to register.
        //
        // Verticality is the real signature. A roguelike dungeon stacks floors
        // roughly 8 blocks apart, so its spawners span tens of blocks in Y. A
        // vanilla dungeon is one spawner at one level, and a wasp nest sits at a
        // single depth. That distinction does not care how big the dungeon is.
        rogue.minCluster = 6;
        rogue.minYSpread = 12;
        rogue.biomeVariant = true;
        // Radius 12 was too tight and split single dungeons in two: rooms more
        // than 12 blocks apart became separate clusters. The sprawl problem is
        // solved by bounding the group's extent as it grows (see cluster), not by
        // starving the radius, so this can be generous again.
        rogue.radius = 24;
        rogue.maxExtent = 56;
        // Restored. Dropping this was based on a wrong diagnosis - the real cause
        // of zero hits was a stale scan cache - and without it multi-level nether
        // fortresses qualify as dungeons.
        rogue.onlyDims = new int[] { 0 };
        // Four floors roughly 8 blocks apart. Requiring three distinct bands
        // rejects the stacked cave dungeons that a bare Y spread let through.
        // Backed off from 3 each. Seven constraints had accumulated here, one per
        // false-positive report, and stacked they were rejecting real dungeons.
        // Two mob types still excludes a mineshaft outright, which is the case
        // this rule exists for; two bands still excludes a flat spawner nest.
        rogue.minFloors = 2;
        rogue.minMobTypes = 2;
        rogue.priority = 10;
        rogue.nameByMob = true;           // so a misfire names itself
        signatures.add(rogue);

        Signature space = new Signature("Space dungeon", true);
        space.mobMatch = "galacticraft";  // Evolved* spawners are unique to them
        space.notDims = new int[] { 0 };
        space.minCluster = 2;
        space.radius = 64;
        signatures.add(space);

        Signature mars = new Signature("Mars dungeon", false,
            "GalacticraftMars:tile.marsDungeonBricksStairs");
        mars.minCluster = 8;              // the bricks are craftable
        mars.radius = 64;
        signatures.add(mars);

        Signature hee = new Signature("HEE dungeon", false,
            "HardcoreEnderExpansion:dungeon_puzzle");
        hee.minCluster = 1;
        hee.radius = 64;
        signatures.add(hee);

        Signature meteor = new Signature("AE2 meteorite", false,
            "appliedenergistics2:tile.BlockSkyChest");
        meteor.minCluster = 1;            // every meteorite has exactly one chest
        meteor.radius = 32;
        meteor.priority = 10;
        signatures.add(meteor);

        Signature slime = new Signature("Slime island", false,
            "TConstruct:slime.grass", "TConstruct:slime.leaves", "TConstruct:slime.gel");
        slime.minCluster = 20;            // an island, not a stray block
        slime.radius = 48;
        // No altitude gate. y > 100 was an assumption about where these
        // generate, and it is the most likely reason a visible island was
        // missed. Twenty clustered slime blocks are distinctive on their own.
        slime.priority = 10;
        signatures.add(slime);

        Signature circle = new Signature("Stone circle", false, "minecraft:dispenser");
        // A dispenser alone is just redstone. Under water or ice it is the
        // centre of a stone circle.
        circle.aboveNames = new String[] {
            "minecraft:water", "minecraft:flowing_water", "minecraft:ice" };
        circle.minCluster = 1;
        circle.radius = 16;
        circle.surfaceOnly = true;
        circle.priority = 5;              // yields to a dungeon at the same spot
        signatures.add(circle);

        Signature hilltop = new Signature("Hilltop stones", false, "minecraft:obsidian");
        hilltop.aboveNames = new String[] { "minecraft:chest" };
        hilltop.minCluster = 1;
        hilltop.radius = 16;
        hilltop.surfaceOnly = true;       // a hilltop, not a dungeon floor
        // Chest-on-obsidian also occurs inside dungeons and in player builds, so
        // this loses to anything more specific nearby.
        hilltop.priority = 5;
        signatures.add(hilltop);

        Signature wizard = new Signature("Wizard tower", false,
            "minecraft:enchanting_table", "etfuturum:enchantment_table");
        // An enchantment table is the only reliable tell: in the overworld the
        // wizard tower variant of a roguelike dungeon is the sole place one
        // generates. Player-built tables will also match, so this is a lead
        // rather than a certainty.
        wizard.onlyDims = new int[] { 0 };
        wizard.minCluster = 1;
        wizard.radius = 24;
        wizard.priority = 10;
        signatures.add(wizard);

        Signature any = new Signature("Spawner cluster", true);
        any.minCluster = 4;
        any.radius = 24;
        any.generic = true;               // suppressed where something specific matched
        any.nameByMob = true;
        signatures.add(any);
    }

    private void resolveBlocks() {
        for (Signature sig : signatures) {
            if (sig.spawners || !applies(sig)) continue;
            for (String name : sig.blockNames) {
                int colon = name.indexOf(':');
                if (colon < 0) continue;
                Block block = GameRegistry.findBlock(name.substring(0, colon),
                                                     name.substring(colon + 1));
                if (block == null) continue;          // mod absent, skip quietly
                int id = Block.getIdFromBlock(block);
                if (id <= 0) continue;
                byBlockId.put(id, sig);
                lowWanted[id & 0xFF] = true;
            }
            if (sig.aboveNames != null) {
                for (String name : sig.aboveNames) {
                    int colon = name.indexOf(':');
                    if (colon < 0) continue;
                    Block block = GameRegistry.findBlock(name.substring(0, colon),
                                                         name.substring(colon + 1));
                    if (block == null) continue;
                    int id = Block.getIdFromBlock(block);
                    if (id > 0) sig.aboveIds.add(id);
                }
            }
        }
    }

    /** Full block id at a section index, combining the Add nibble. */
    private static int idAt(byte[] blocks, byte[] add, boolean hasAdd, int index) {
        int low = blocks[index] & 0xFF;
        int high = 0;
        if (hasAdd) {
            int nib = add[index >> 1] & 0xFF;
            high = (index & 1) == 0 ? (nib & 0x0F) : ((nib >> 4) & 0x0F);
        }
        return (high << 8) | low;
    }

    /**
      * Biome id for a column. EndlessIDs replaces the vanilla byte array with
      * Biomes16v2 - 256 cells of two bytes, low byte first - so ids exceed 255.
      */
    private static int biomeAt(NBTTagCompound level, int localX, int localZ) {
        byte[] wide = level.getByteArray("Biomes16v2");
        int i = localZ * 16 + localX;
        if (wide != null && wide.length >= 512) {
            return (wide[i * 2] & 0xFF) | ((wide[i * 2 + 1] & 0xFF) << 8);
        }
        byte[] narrow = level.getByteArray("Biomes");
        if (narrow != null && narrow.length >= 256) return narrow[i] & 0xFF;
        return -1;
    }

    /**
     * Roguelike Dungeons picks its surface building from the biome, and that
     * building is the only part visible from above - so it is what you actually
     * need to know before travelling.
     *
     * Matched on biome family rather than read out of Roguelike's own settings,
     * so treat an odd label as a hint. The coordinates are exact either way.
     */
    private static final String[][] VARIANTS = {
        { "Sandstone Temple", "desert", "sand", "mesa", "dune", "oasis", "wasteland" },
        { "Overgrown Temple", "jungle", "rainforest", "tropic", "bamboo" },
        { "Wooden Outpost", "swamp", "bayou", "bog", "sludge", "marsh", "quagmire",
          "fen", "wetland" },
        { "Wizard Tower", "mountain", "alps", "crag", "extreme hills", "highland",
          "peak", "cliff" },
        { "Brick House", "forest", "taiga", "wood", "grove", "thicket", "boreal",
          "conifer", "shrub", "brushland", "orchard" },
        { "Castle Tower", "plain", "meadow", "prairie", "field", "steppe", "grass",
          "savanna", "heath", "moor", "scrub", "shield", "chaparral" },
    };

    private static String biomeName(int id) {
        try {
            if (id >= 0 && id < net.minecraft.world.biome.BiomeGenBase
                    .getBiomeGenArray().length) {
                net.minecraft.world.biome.BiomeGenBase b =
                    net.minecraft.world.biome.BiomeGenBase.getBiomeGenArray()[id];
                if (b != null && b.biomeName != null) return b.biomeName;
            }
        } catch (Throwable ignored) {
            // fall through to the unnamed case
        }
        return id < 0 ? "" : "biome " + id;
    }

    private static String variantFor(String biome) {
        String low = biome.toLowerCase();
        for (String[] rule : VARIANTS) {
            for (int i = 1; i < rule.length; i++) {
                if (low.contains(rule[i])) return rule[0];
            }
        }
        return "";
    }

    /** Whether some signature has a raw hit within radius of a point. */
    private boolean hasHitNear(String label, int x, int z, int radius) {
        Signature sig = byLabel(label);
        if (sig == null) return false;
        long r2 = (long) radius * radius;
        for (int i = 0; i < sig.hits.size(); i++) {
            int[] h = sig.hits.get(i);
            long dx = h[0] - x, dz = h[2] - z;
            if (dx * dx + dz * dz <= r2) return true;
        }
        return false;
    }

    private boolean applies(Signature sig) {
        if (sig.onlyDims != null) {
            boolean ok = false;
            for (int d : sig.onlyDims) if (d == dimension) ok = true;
            if (!ok) return false;
        }
        if (sig.notDims != null) {
            for (int d : sig.notDims) if (d == dimension) return false;
        }
        return true;
    }

    public boolean anythingToLookFor() {
        if (!byBlockId.isEmpty()) return true;
        for (Signature s : signatures) if (s.spawners && applies(s)) return true;
        return false;
    }

    public int revision() {
        return revision;
    }

    public int suppressedCount() {
        return suppressedCount;
    }

    public boolean showingSuppressed() {
        return showSuppressed;
    }

    public void setShowSuppressed(boolean show) {
        if (showSuppressed == show) return;
        showSuppressed = show;
        cachedRevision = -1;              // force a re-cluster
    }

    /**
     * Forget everything found so far, so the next scan starts clean.
     *
     * Needed because results are cumulative and chunk-deduplicated: once a chunk
     * is recorded as scanned, nothing re-examines it. After the detection rules
     * change, old findings would otherwise linger with no way to clear them
     * short of deleting files by hand.
     */
    public void clear() {
        for (Signature sig : signatures) {
            sig.hits.clear();
            sig.mobs.clear();
            sig.biomes.clear();
        }
        scannedChunks.clear();
        cache = null;
        cachedRevision = -1;
        revision++;
    }

    // ---- the per-chunk pass ---------------------------------------------

    /**
     * One detection, produced without touching mutable state.
     *
     * The decode worker parses chunks and fills these in; the render thread then
     * commits them. Keeping the two apart is what lets parsing leave the render
     * thread without any locking around the signature lists.
     */
    public static final class RawHit {
        final int signature;
        final int x, y, z;
        final String mob;
        final int biome;

        RawHit(int signature, int x, int y, int z, String mob, int biome) {
            this.signature = signature;
            this.x = x;
            this.y = y;
            this.z = z;
            this.mob = mob;
            this.biome = biome;
        }
    }

    public boolean alreadyScanned(int chunkX, int chunkZ) {
        return scannedChunks.contains(chunkKey(chunkX, chunkZ));
    }

    private static long chunkKey(int cx, int cz) {
        return (long) cx & 0xFFFFFFFFL | ((long) cz & 0xFFFFFFFFL) << 32;
    }

    /** Called once per decoded chunk, from the same read that paints terrain. */
    public void scanChunk(NBTTagCompound level, int chunkX, int chunkZ) {
        if (alreadyScanned(chunkX, chunkZ)) return;
        List<RawHit> found = new ArrayList<RawHit>();
        extract(level, chunkX, chunkZ, found);
        apply(chunkX, chunkZ, found);
    }

    /**
     * Detect into the supplied list. Reads only immutable configuration, so it is
     * safe to call from a decode worker.
     */
    public void extract(NBTTagCompound level, int chunkX, int chunkZ, List<RawHit> out) {
        scanBlocks(level, chunkX, chunkZ, out);
        scanSpawners(level, out);
    }

    /** Commit extracted hits. Render thread only. */
    public void apply(int chunkX, int chunkZ, List<RawHit> found) {
        if (!scannedChunks.add(chunkKey(chunkX, chunkZ))) return;
        for (int i = 0; i < found.size(); i++) {
            RawHit h = found.get(i);
            Signature sig = signatures.get(h.signature);
            sig.hits.add(new int[] { h.x, h.y, h.z });
            sig.mobs.add(h.mob);
            sig.biomes.add(h.biome);
            revision++;
        }
    }

    /** True when any signature wants blocks or spawners in this dimension. */
    public boolean wantsChunks() {
        return anythingToLookFor();
    }

    /** One line per signature: raw hits and resulting findings. */
    public List<String> describe() {
        List<String> out = new ArrayList<String>();
        out.add("dimension " + dimension + ", " + spawnersSeen + " spawners read, "
                + scannedChunks.size() + " chunks");
        for (Signature sig : signatures) {
            int findings = applies(sig) && !sig.hits.isEmpty() ? cluster(sig).size() : 0;
            StringBuilder sb = new StringBuilder();
            sb.append(sig.label).append(": ").append(sig.hits.size()).append(" hits -> ")
              .append(findings).append(" findings");
            if (!applies(sig)) sb.append("  [not active in DIM").append(dimension).append("]");
            sb.append("  (min ").append(sig.minCluster).append(", r").append(sig.radius);
            if (sig.vanillaMobsOnly) sb.append(", vanilla mobs only");
            if (sig.minMobTypes > 0) sb.append(", ").append(sig.minMobTypes).append("+ mobs");
            if (sig.minFloors > 0) sb.append(", ").append(sig.minFloors).append(" floors");
            sb.append(")");
            int dropped = sig.rejSize + sig.rejMobs + sig.rejFloors + sig.rejSpread
                + sig.rejSurface + sig.rejAbove;
            if (dropped > 0) {
                sb.append("  rejected:");
                if (sig.rejSize > 0) sb.append(" size ").append(sig.rejSize);
                if (sig.rejMobs > 0) sb.append(" mobs ").append(sig.rejMobs);
                if (sig.rejFloors > 0) sb.append(" floors ").append(sig.rejFloors);
                if (sig.rejSpread > 0) sb.append(" spread ").append(sig.rejSpread);
                if (sig.rejSurface > 0) sb.append(" buried ").append(sig.rejSurface);
                if (sig.rejAbove > 0) sb.append(" no-top ").append(sig.rejAbove);
            }
            sb.append("");
            if (sig.mobMatch != null) sb.append(", mob~").append(sig.mobMatch);
            out.add(sb.toString());
        }
        return out;
    }

    /** Distinct spawner mob ids seen, with counts - names the filter's victims. */
    public List<String> describeMobs() {
        Map<String, Integer> tally = new HashMap<String, Integer>();
        for (Signature sig : signatures) {
            if (!sig.spawners) continue;
            for (String mob : sig.mobs) {
                if (mob == null) continue;
                Integer c = tally.get(mob);
                tally.put(mob, c == null ? 1 : c + 1);
            }
        }
        List<String> out = new ArrayList<String>();
        for (Map.Entry<String, Integer> e : tally.entrySet()) {
            out.add(e.getKey() + " x" + e.getValue());
        }
        return out;
    }

    public int scannedChunkCount() {
        return scannedChunks.size();
    }

    // ---- persistence -----------------------------------------------------

    private static final int MAGIC = 0x53534732;          // "SSG2"

    /**
     * Fingerprint of the signature rules.
     *
     * The cache records which chunks have been examined, so a saved scan makes
     * every one of them a no-op on the next run. That is the point - but it also
     * means changing a rule can never take effect on already-scanned chunks: the
     * old results stay, and hits the old rules discarded are gone for good.
     *
     * Deriving this from the rules themselves means any edit to a threshold,
     * radius, filter or block list invalidates the cache automatically, instead
     * of depending on someone remembering to bump a version.
     */
    private int rulesFingerprint() {
        int h = 17;
        for (Signature sig : signatures) {
            h = h * 31 + sig.label.hashCode();
            h = h * 31 + java.util.Arrays.hashCode(sig.blockNames);
            h = h * 31 + sig.minCluster;
            h = h * 31 + sig.radius;
            h = h * 31 + (sig.spawners ? 1 : 0);
            h = h * 31 + (sig.vanillaMobsOnly ? 1 : 0);
            h = h * 31 + (sig.generic ? 1 : 0);
            h = h * 31 + (sig.nameByMob ? 1 : 0);
            h = h * 31 + (sig.mobMatch == null ? 0 : sig.mobMatch.hashCode());
            h = h * 31 + java.util.Arrays.hashCode(sig.onlyDims);
            h = h * 31 + java.util.Arrays.hashCode(sig.notDims);
        }
        return h;
    }

    /**
     * Saves raw hits rather than clustered results. Clustering has to run over
     * everything at once for a structure spanning a region boundary to stay a
     * single finding, so the inputs are what must survive a restart.
     */
    public void save(java.io.File file) {
        java.io.File parent = file.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) return;
        java.io.DataOutputStream out = null;
        try {
            out = new java.io.DataOutputStream(new java.util.zip.DeflaterOutputStream(
                new java.io.BufferedOutputStream(new java.io.FileOutputStream(file))));
            out.writeInt(MAGIC);
            out.writeInt(rulesFingerprint());
            out.writeInt(scannedChunks.size());
            for (long k : scannedChunks) out.writeLong(k);
            out.writeInt(signatures.size());
            for (Signature sig : signatures) {
                out.writeUTF(sig.label);
                out.writeInt(sig.hits.size());
                for (int i = 0; i < sig.hits.size(); i++) {
                    int[] h = sig.hits.get(i);
                    out.writeInt(h[0]);
                    out.writeInt(h[1]);
                    out.writeInt(h[2]);
                    String mob = sig.mobs.get(i);
                    out.writeUTF(mob == null ? "" : mob);
                    out.writeInt(i < sig.biomes.size() ? sig.biomes.get(i) : -1);
                }
            }
        } catch (Throwable t) {
            // A failed cache write must never break the map.
        } finally {
            closeQuietly(out);
        }
    }

    public void load(java.io.File file) {
        if (!file.isFile()) return;
        java.io.DataInputStream in = null;
        try {
            in = new java.io.DataInputStream(new java.util.zip.InflaterInputStream(
                new java.io.BufferedInputStream(new java.io.FileInputStream(file))));
            if (in.readInt() != MAGIC) return;
            if (in.readInt() != rulesFingerprint()) {
                // Rules changed since this was written; rescanning is the only
                // way to get results that match them.
                return;
            }
            int chunks = in.readInt();
            for (int i = 0; i < chunks; i++) scannedChunks.add(in.readLong());
            int sigCount = in.readInt();
            for (int s = 0; s < sigCount; s++) {
                String label = in.readUTF();
                int hits = in.readInt();
                Signature sig = byLabel(label);
                for (int i = 0; i < hits; i++) {
                    int x = in.readInt(), y = in.readInt(), z = in.readInt();
                    String mob = in.readUTF();
                    int biome = in.readInt();
                    if (sig == null) continue;            // signature since removed
                    sig.hits.add(new int[] { x, y, z });
                    sig.mobs.add(mob.isEmpty() ? null : mob);
                    sig.biomes.add(biome);
                }
            }
            revision++;
        } catch (Throwable t) {
            // A truncated cache leaves partial hits loaded, which is harmless:
            // every hit stands on its own.
        } finally {
            closeQuietly(in);
        }
    }

    private static void closeQuietly(java.io.Closeable c) {
        if (c == null) return;
        try {
            c.close();
        } catch (Throwable ignored) {
            // nothing useful to do
        }
    }

    private Signature byLabel(String label) {
        for (Signature sig : signatures) if (sig.label.equals(label)) return sig;
        return null;
    }

    private void scanBlocks(NBTTagCompound level, int chunkX, int chunkZ, List<RawHit> out) {
        // Index sections by their Y so the block above can be read even when it
        // lives in the next section. Skipping the top layer of every section - as
        // this used to - silently dropped one candidate height in sixteen, and
        // structures sit at consistent heights, so whole types could vanish.
        NBTTagList allSections = level.getTagList("Sections", 10);
        NBTTagCompound[] bySectionY = new NBTTagCompound[16];
        for (int s = 0; s < allSections.tagCount(); s++) {
            NBTTagCompound sec = allSections.getCompoundTagAt(s);
            int sy = sec.getByte("Y");
            if (sy >= 0 && sy < bySectionY.length) bySectionY[sy] = sec;
        }
        if (byBlockId.isEmpty()) return;
        int[] heightMap = level.getIntArray("HeightMap");
        if (heightMap != null && heightMap.length < 256) heightMap = null;
        NBTTagList sections = level.getTagList("Sections", 10);
        for (int s = 0; s < sections.tagCount(); s++) {
            NBTTagCompound sec = sections.getCompoundTagAt(s);
            byte[] blocks = sec.getByteArray("Blocks");
            if (blocks == null || blocks.length < 4096) continue;
            byte[] add = sec.getByteArray("Add");
            boolean hasAdd = add != null && add.length >= 2048;
            int baseY = sec.getByte("Y") * 16;

            for (int i = 0; i < 4096; i++) {
                int low = blocks[i] & 0xFF;
                // One array lookup rejects almost everything before we pay for
                // the nibble maths.
                if (!lowWanted[low]) continue;
                int high = 0;
                if (hasAdd) {
                    int nib = add[i >> 1] & 0xFF;
                    high = (i & 1) == 0 ? (nib & 0x0F) : ((nib >> 4) & 0x0F);
                }
                Signature sig = byBlockId.get((high << 8) | low);
                if (sig == null) continue;
                int worldY = baseY + (i >> 8);
                if (sig.minY > 0 && worldY < sig.minY) continue;
                if (sig.surfaceOnly && heightMap != null) {
                    // HeightMap is the first free y above terrain, so allow a
                    // little burial and reject anything properly underground.
                    int surface = heightMap[((i >> 4) & 15) * 16 + (i & 15)];
                    if (worldY < surface - 8) { sig.rejSurface++; continue; }
                }
                if (!sig.aboveIds.isEmpty()) {
                    int above;
                    if (i < 3840) {
                        above = idAt(blocks, add, hasAdd, i + 256);
                    } else {
                        // Top layer: the block above is the bottom layer of the
                        // section overhead.
                        NBTTagCompound up = bySectionY[Math.min(15, (baseY / 16) + 1)];
                        if (up == null) continue;
                        byte[] upBlocks = up.getByteArray("Blocks");
                        if (upBlocks == null || upBlocks.length < 4096) continue;
                        byte[] upAdd = up.getByteArray("Add");
                        boolean upHasAdd = upAdd != null && upAdd.length >= 2048;
                        above = idAt(upBlocks, upAdd, upHasAdd, i - 3840);
                    }
                    if (!sig.aboveIds.contains(above)) { sig.rejAbove++; continue; }
                }
                out.add(new RawHit(signatures.indexOf(sig),
                                   chunkX * 16 + (i & 15),
                                   baseY + (i >> 8),
                                   chunkZ * 16 + ((i >> 4) & 15),
                                   null,
                                   biomeAt(level, i & 15, (i >> 4) & 15)));
            }
        }
    }

    private int spawnersSeen;

    private void scanSpawners(NBTTagCompound level, List<RawHit> out) {
        boolean wanted = false;
        for (Signature s : signatures) if (s.spawners && applies(s)) wanted = true;
        if (!wanted) return;

        NBTTagList list = level.getTagList("TileEntities", 10);
        for (int i = 0; i < list.tagCount(); i++) {
            NBTTagCompound te = list.getCompoundTagAt(i);
            if (!"MobSpawner".equals(te.getString("id"))) continue;
            spawnersSeen++;
            String mob = te.hasKey("EntityId") ? te.getString("EntityId") : "?";
            int x = te.getInteger("x"), y = te.getInteger("y"), z = te.getInteger("z");
            for (Signature sig : signatures) {
                if (!sig.spawners || !applies(sig)) continue;
                if (sig.vanillaMobsOnly && (mob.indexOf('.') >= 0 || mob.indexOf(':') >= 0)) continue;
                if (sig.mobMatch != null && !mob.toLowerCase().contains(sig.mobMatch)) continue;
                out.add(new RawHit(signatures.indexOf(sig), x, y, z, mob,
                                   biomeAt(level, x & 15, z & 15)));
            }
        }
    }

    // ---- clustering ------------------------------------------------------

    /** Microseconds the last clustering pass took, for the on-screen readout. */
    public long lastClusterMicros() {
        return lastClusterMicros;
    }

    public List<Marker> markers() {
        if (cachedRevision == revision && cache != null) return cache;
        // Every recorded hit bumps the revision, so during a scan this is asked
        // to re-cluster on every frame. The result only has to be fresh enough
        // to watch progress with, so rebuild at most twice a second and serve
        // the previous answer in between.
        long nowMs = System.currentTimeMillis();
        if (cache != null && nowMs - lastClusterAt < 500L) return cache;
        lastClusterAt = nowMs;
        long startNanos = System.nanoTime();

        List<Marker> ranked = new ArrayList<Marker>();
        final List<Integer> ranks = new ArrayList<Integer>();
        for (Signature sig : signatures) {
            if (!applies(sig) || sig.hits.isEmpty()) continue;
            for (Object[] group : cluster(sig)) {
                int[] centre = (int[]) group[0];
                int count = (Integer) group[1];
                String topMob = (String) group[2];
                // The layer name stays fixed. Folding the mob into it made every
                // distinct mob its own "kind", which exhausted the colour palette
                // and split one structure type across several near-identical
                // sidebar rows - so real layers became impossible to pick out.
                int[] yRange = group.length > 4 ? (int[]) group[4] : null;
                int mobKinds = distinctMobs(sig, group);
                String detail = (topMob == null ? "" : topMob + ", ")
                    + count + (sig.spawners ? " spawners" : " blocks")
                    + (mobKinds > 1 ? " / " + mobKinds + " mob types" : "")
                    + (yRange == null ? "" : "  ·  y " + yRange[0]
                       + (yRange[1] != yRange[0] ? "-" + yRange[1] : ""));
                // Only claim a building type when there is evidence for it.
                //
                // Guessing the variant from the biome produced confident, wrong
                // labels - "Brick House" on dungeons that were nothing of the
                // kind - because the biome families do not line up with
                // Roguelike's own choice. An enchantment table, by contrast, is
                // proof: in the overworld only the wizard tower variant has one.
                // Everything else shows the biome and makes no claim.
                String biome = biomeName(dominantBiome(sig, group));
                String subtitle = biome;
                if (sig.biomeVariant
                    && hasHitNear("Wizard tower", centre[0], centre[2], 48)) {
                    subtitle = "Wizard Tower  ·  " + biome;
                }
                // Small clusters of an inherently fuzzy signature stay hollow.
                boolean sure = count >= Math.max(sig.minCluster * 2, sig.minCluster + 1);
                Marker m = new Marker(sig.label, centre[0], centre[2], "scanned", sure,
                                      detail, subtitle, centre[1]);
                ranked.add(m);
                ranks.add(sig.generic ? -1 : sig.priority);
            }
        }

        // One structure produces several signatures at once: a dungeon is also a
        // spawner cluster, and its loot chests sit on obsidian. Keep the most
        // specific reading of any given spot and drop the rest.
        List<Marker> out = new ArrayList<Marker>();
        int hidden = 0;
        for (int i = 0; i < ranked.size(); i++) {
            Marker m = ranked.get(i);
            int rank = ranks.get(i);
            boolean outranked = false;
            for (int j = 0; j < ranked.size(); j++) {
                if (j == i || ranks.get(j) <= rank) continue;
                Marker other = ranked.get(j);
                if (Math.abs(m.x - other.x) <= 48 && Math.abs(m.z - other.z) <= 48) {
                    outranked = true;
                    break;
                }
            }
            if (!outranked) {
                out.add(m);
            } else {
                hidden++;
                // Still drawn on request, flagged so it cannot be mistaken for a
                // confident finding.
                if (showSuppressed) {
                    out.add(new Marker(m.kind, m.x, m.z, m.source, false,
                                       m.detail.isEmpty() ? "overlaps a stronger match"
                                           : m.detail + "  ·  overlaps a stronger match",
                                       m.subtitle));
                }
            }
        }

        suppressedCount = hidden;
        lastClusterMicros = (System.nanoTime() - startNanos) / 1000L;
        cache = out;
        cachedRevision = revision;
        return out;
    }

    /** How many distinct spawner mobs a cluster contains. */
    private int distinctMobs(Signature sig, Object[] group) {
        Object idx = group.length > 3 ? group[3] : null;
        if (!(idx instanceof List)) return 0;
        java.util.Set<String> kinds = new java.util.HashSet<String>();
        for (Object o : (List<?>) idx) {
            int i = (Integer) o;
            if (i >= sig.mobs.size()) continue;
            String mob = sig.mobs.get(i);
            if (mob != null) kinds.add(mob);
        }
        return kinds.size();
    }

    /** Most common biome across a cluster, so one edge column cannot mislabel it. */
    private int dominantBiome(Signature sig, Object[] group) {
        Object idx = group.length > 3 ? group[3] : null;
        if (!(idx instanceof List)) return -1;
        Map<Integer, Integer> tally = new HashMap<Integer, Integer>();
        for (Object o : (List<?>) idx) {
            int i = (Integer) o;
            if (i >= sig.biomes.size()) continue;
            int b = sig.biomes.get(i);
            if (b < 0) continue;
            Integer c = tally.get(b);
            tally.put(b, c == null ? 1 : c + 1);
        }
        int best = -1, bestCount = 0;
        for (Map.Entry<Integer, Integer> e : tally.entrySet()) {
            if (e.getValue() > bestCount) { bestCount = e.getValue(); best = e.getKey(); }
        }
        return best;
    }

    /**
     * Spatial grouping in XZ. Each result is {centre, size, dominant mob,
     * indices, y range}.
     *
     * Uses a grid index and a breadth-first walk. The previous version rescanned
     * every hit against every group member on each growth pass - roughly
     * O(n^2 * g^2) - and it runs on every frame while a scan streams hits in.
     * With a few thousand spawners that dominated the frame. Bucketing hits by
     * cell means a candidate is only ever compared against the nine cells around
     * it, which makes growth linear in practice.
     */
    private List<Object[]> cluster(Signature sig) {
        sig.rejSize = sig.rejMobs = sig.rejFloors = sig.rejSpread = 0;
        List<Object[]> out = new ArrayList<Object[]>();
        int n = sig.hits.size();
        boolean[] used = new boolean[n];
        int r2 = sig.radius * sig.radius;

        // Cell side at least the radius, so neighbours can only be one cell away.
        final int cell = Math.max(8, sig.radius);
        Map<Long, List<Integer>> grid = new HashMap<Long, List<Integer>>();
        for (int i = 0; i < n; i++) {
            int[] h = sig.hits.get(i);
            long key = cellKey(Math.floorDiv(h[0], cell), Math.floorDiv(h[2], cell));
            List<Integer> bucket = grid.get(key);
            if (bucket == null) {
                bucket = new ArrayList<Integer>(4);
                grid.put(key, bucket);
            }
            bucket.add(i);
        }

        java.util.ArrayDeque<Integer> queue = new java.util.ArrayDeque<Integer>();
        for (int i = 0; i < n; i++) {
            if (used[i]) continue;
            List<Integer> group = new ArrayList<Integer>();
            group.add(i);
            used[i] = true;
            queue.clear();
            queue.add(i);
            int[] seed = sig.hits.get(i);
            int loX = seed[0], hiX = seed[0], loZ = seed[2], hiZ = seed[2];

            // Breadth-first so the group is the transitive closure of "within
            // radius", which keeps a structure spanning a region boundary as one
            // finding. Extent is bounded as it grows: the member that would burst
            // the box is refused, leaving it to form its own cluster, rather than
            // the whole group being discarded afterwards.
            while (!queue.isEmpty()) {
                int[] a = sig.hits.get(queue.poll());
                int gx = Math.floorDiv(a[0], cell), gz = Math.floorDiv(a[2], cell);
                for (int ox = -1; ox <= 1; ox++) {
                    for (int oz = -1; oz <= 1; oz++) {
                        List<Integer> bucket = grid.get(cellKey(gx + ox, gz + oz));
                        if (bucket == null) continue;
                        for (int k = 0; k < bucket.size(); k++) {
                            int j = bucket.get(k);
                            if (used[j]) continue;
                            int[] b = sig.hits.get(j);
                            int dx = a[0] - b[0], dz = a[2] - b[2];
                            if (dx * dx + dz * dz > r2) continue;
                            if (sig.maxExtent > 0) {
                                int nLoX = Math.min(loX, b[0]), nHiX = Math.max(hiX, b[0]);
                                int nLoZ = Math.min(loZ, b[2]), nHiZ = Math.max(hiZ, b[2]);
                                if (nHiX - nLoX > sig.maxExtent
                                    || nHiZ - nLoZ > sig.maxExtent) {
                                    continue;
                                }
                                loX = nLoX; hiX = nHiX; loZ = nLoZ; hiZ = nHiZ;
                            }
                            used[j] = true;
                            group.add(j);
                            queue.add(j);
                        }
                    }
                }
            }
            if (group.size() < sig.minCluster) { sig.rejSize++; continue; }
            if (sig.minMobTypes > 0) {
                java.util.Set<String> kinds = new java.util.HashSet<String>();
                for (int gi : group) {
                    String mob = sig.mobs.get(gi);
                    if (mob != null) kinds.add(mob);
                }
                if (kinds.size() < sig.minMobTypes) { sig.rejMobs++; continue; }
            }
            if (sig.minFloors > 0) {
                java.util.Set<Integer> bands = new java.util.HashSet<Integer>();
                for (int gi : group) bands.add(sig.hits.get(gi)[1] / 6);
                if (bands.size() < sig.minFloors) { sig.rejFloors++; continue; }
            }
            if (sig.minYSpread > 0) {
                int loY = Integer.MAX_VALUE, hiY = Integer.MIN_VALUE;
                for (int gi : group) {
                    int y = sig.hits.get(gi)[1];
                    if (y < loY) loY = y;
                    if (y > hiY) hiY = y;
                }
                if (hiY - loY < sig.minYSpread) { sig.rejSpread++; continue; }
            }

            long sx = 0, sy = 0, sz = 0;
            Map<String, Integer> tally = new HashMap<String, Integer>();
            for (int gi : group) {
                int[] h = sig.hits.get(gi);
                sx += h[0]; sy += h[1]; sz += h[2];
                String mob = sig.mobs.get(gi);
                if (mob != null) {
                    Integer c = tally.get(mob);
                    tally.put(mob, c == null ? 1 : c + 1);
                }
            }
            String top = null;
            int best = 0;
            for (Map.Entry<String, Integer> e : tally.entrySet()) {
                if (e.getValue() > best) { best = e.getValue(); top = e.getKey(); }
            }
            // Report the member nearest the average rather than the average
            // itself: a centroid can land in open ground between hits, which
            // reads as "marked but nothing here". This always sits on a real hit.
            int cx = (int) (sx / group.size()), cz = (int) (sz / group.size());
            int[] anchor = sig.hits.get(group.get(0));
            long bestD = Long.MAX_VALUE;
            int loY = Integer.MAX_VALUE, hiY = Integer.MIN_VALUE;
            for (int gi : group) {
                int[] h = sig.hits.get(gi);
                long dx = h[0] - cx, dz = h[2] - cz;
                long d = dx * dx + dz * dz;
                if (d < bestD) { bestD = d; anchor = h; }
                if (h[1] < loY) loY = h[1];
                if (h[1] > hiY) hiY = h[1];
            }
            out.add(new Object[] {
                new int[] { anchor[0], anchor[1], anchor[2] },
                group.size(), top, group, new int[] { loY, hiY } });
        }
        return out;
    }

    private static long cellKey(int cx, int cz) {
        return (long) cx & 0xFFFFFFFFL | ((long) cz & 0xFFFFFFFFL) << 32;
    }
}
