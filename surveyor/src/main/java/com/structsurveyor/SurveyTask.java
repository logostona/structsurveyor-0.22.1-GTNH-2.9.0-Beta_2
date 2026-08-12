package com.structsurveyor;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import net.minecraft.command.ICommandSender;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.world.WorldServer;
import net.minecraft.world.gen.structure.MapGenStructure;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/**
 * Replays each structure generator's placement decision over a square of chunks
 * and records every chunk it would have placed a structure in.
 *
 * No terrain is generated. The placement predicate is a pure function of the
 * world seed and the chunk coordinates (plus biome lookups for some structure
 * types), which is exactly why this can see into terrain the player has never
 * visited.
 *
 * The sweep runs on the server thread in per-tick slices. The biome lookups
 * some predicates perform are not thread-safe in 1.7.10, so moving this to a
 * worker thread would race with chunk generation.
 */
public class SurveyTask {

    /** One generator plus the cached reflective handles we need for it. */
    private static class Target {

        final MapGenStructure gen;
        final String tag;
        final Random rand;
        final List<int[]> hits = new ArrayList<int[]>();
        boolean broken;
        String error;

        // Self-verification against the world's own recorded structures.
        int knownInRegion = -1;
        int reproduced;
        String verdict = "not checked";
        /** False if this generator has never run here - an inherited, unused field. */
        boolean active = true;

        Target(MapGenStructure gen, String tag, Random rand) {
            this.gen = gen;
            this.tag = tag;
            this.rand = rand;
        }
    }

    private final WorldServer world;
    private final ICommandSender sender;
    private final int dimension;
    private final int centerChunkX, centerChunkZ, radiusChunks;
    private final int chunksPerTick;
    private final List<Target> targets = new ArrayList<Target>();

    private final long worldSeed;
    private final long xMultiplier;
    private final long zMultiplier;

    private final long totalChunks;
    private long processed;
    private int cursorX, cursorZ;
    private boolean done;
    private long startedAtMs;
    private long lastReportMs;

    public SurveyTask(WorldServer world, ICommandSender sender, int centerChunkX, int centerChunkZ,
                      int radiusChunks, int chunksPerTick, List<MapGenStructure> generators) {
        this.world = world;
        this.sender = sender;
        this.dimension = world.provider.dimensionId;
        this.centerChunkX = centerChunkX;
        this.centerChunkZ = centerChunkZ;
        this.radiusChunks = radiusChunks;
        this.chunksPerTick = chunksPerTick;
        this.worldSeed = world.getSeed();

        // MapGenBase derives two multipliers from the world seed once per
        // generate() call. They depend only on the seed, so they are identical
        // for every generator and can be computed once here.
        Random seeder = new Random(worldSeed);
        this.xMultiplier = seeder.nextLong();
        this.zMultiplier = seeder.nextLong();

        for (MapGenStructure gen : generators) {
            try {
                // Read this before binding - bindWorld would overwrite the field
                // this check depends on.
                boolean active = GeneratorRefs.hasRun(gen);
                GeneratorRefs.bindWorld(gen, world);
                Target t = new Target(gen, GeneratorRefs.tagOf(gen), GeneratorRefs.randOf(gen));
                t.active = active;
                targets.add(t);
            } catch (Throwable t) {
                StructureSurveyor.LOG.warn("Skipping generator " + gen.getClass().getName(), t);
            }
        }

        int side = radiusChunks * 2 + 1;
        this.totalChunks = (long) side * (long) side;
        this.cursorX = centerChunkX - radiusChunks;
        this.cursorZ = centerChunkZ - radiusChunks;
    }

    /** True once the sweep has finished or been cancelled. */
    public boolean isDone() {
        return done;
    }

    public void start() {
        startedAtMs = System.currentTimeMillis();
        lastReportMs = startedAtMs;
        FMLCommonHandler.instance().bus().register(this);
        say(EnumChatFormatting.GRAY + "Sweeping " + totalChunks + " chunks x " + targets.size()
            + " generators. /survey stop to cancel.");
    }

    public void stop(String reason) {
        if (done) return;
        done = true;
        FMLCommonHandler.instance().bus().unregister(this);
        say(EnumChatFormatting.YELLOW + "Survey " + reason + " after " + processed + " chunks.");
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || done) return;

        int budget = chunksPerTick;
        while (budget-- > 0) {
            if (cursorZ > centerChunkZ + radiusChunks) {
                finish();
                return;
            }
            step(cursorX, cursorZ);
            processed++;
            if (++cursorX > centerChunkX + radiusChunks) {
                cursorX = centerChunkX - radiusChunks;
                cursorZ++;
            }
        }

        long now = System.currentTimeMillis();
        if (now - lastReportMs > 5000L) {
            lastReportMs = now;
            int pct = (int) (processed * 100L / totalChunks);
            long elapsed = Math.max(1L, now - startedAtMs);
            long eta = (totalChunks - processed) * elapsed / Math.max(1L, processed) / 1000L;
            say(EnumChatFormatting.GRAY + "  " + pct + "%  " + hitCount() + " found  ~" + eta + "s left");
        }
    }

    private void step(int chunkX, int chunkZ) {
        for (int i = 0; i < targets.size(); i++) {
            Target t = targets.get(i);
            if (t.broken) continue;
            try {
                // Reproduce MapGenBase's per-chunk seeding, then the single
                // nextInt() that MapGenStructure consumes before testing the
                // chunk. Getting this sequence wrong silently produces
                // plausible-looking but wrong results, which is what
                // /survey verify exists to catch.
                t.rand.setSeed((long) chunkX * xMultiplier ^ (long) chunkZ * zMultiplier ^ worldSeed);
                t.rand.nextInt();
                if (GeneratorRefs.canSpawnAt(t.gen, chunkX, chunkZ)) {
                    t.hits.add(new int[] { chunkX, chunkZ });
                }
            } catch (Throwable ex) {
                t.broken = true;
                t.error = ex.toString();
                StructureSurveyor.LOG.warn("Generator " + t.tag + " failed, skipping", ex);
            }
        }
    }

    private int hitCount() {
        int n = 0;
        for (int i = 0; i < targets.size(); i++) n += targets.get(i).hits.size();
        return n;
    }

    private boolean inRegion(int chunkX, int chunkZ) {
        return Math.abs(chunkX - centerChunkX) <= radiusChunks
            && Math.abs(chunkZ - centerChunkZ) <= radiusChunks;
    }

    /**
     * Check the replay against what the world actually generated.
     *
     * The placement replay has to reproduce Minecraft's per-chunk RNG seeding
     * exactly. When it doesn't, the output is wrong but still looks completely
     * plausible - right density, believable spread. The only way to know is to
     * compare against structures the game itself recorded, so we do that
     * automatically rather than asking anyone to take the numbers on trust.
     */
    private void verify() {
        for (int i = 0; i < targets.size(); i++) {
            Target t = targets.get(i);
            if (t.broken) continue;
            try {
                Set<Long> predicted = new HashSet<Long>();
                for (int h = 0; h < t.hits.size(); h++) {
                    int[] c = t.hits.get(h);
                    predicted.add(GeneratorRefs.chunkKey(c[0], c[1]));
                }

                int known = 0;
                int ok = 0;
                for (int[] c : GeneratorRefs.knownStructureChunks(t.gen, world)) {
                    if (!inRegion(c[0], c[1])) continue;
                    known++;
                    if (predicted.contains(GeneratorRefs.chunkKey(c[0], c[1]))) ok++;
                }

                t.knownInRegion = known;
                t.reproduced = ok;
                if (known == 0) {
                    t.verdict = "unverified - no known structures in this region";
                } else if (ok == known) {
                    t.verdict = "verified";
                } else {
                    t.verdict = "UNRELIABLE";
                }
            } catch (Throwable ex) {
                t.verdict = "check failed: " + ex;
                StructureSurveyor.LOG.warn("Verification failed for " + t.tag, ex);
            }
        }
    }

    private void finish() {
        done = true;
        FMLCommonHandler.instance().bus().unregister(this);
        long seconds = (System.currentTimeMillis() - startedAtMs) / 1000L;
        verify();

        File out = new File(MinecraftServer.getServer().getFile("surveyor"),
                            "survey_dim" + dimension + ".json");
        try {
            writeJson(out);
        } catch (Throwable t) {
            say(EnumChatFormatting.RED + "Failed to write results: " + t);
            StructureSurveyor.LOG.error("Failed writing survey output", t);
            return;
        }

        say(EnumChatFormatting.GREEN + "Survey complete: " + hitCount() + " structures in "
            + totalChunks + " chunks (" + seconds + "s)");
        for (int i = 0; i < targets.size(); i++) {
            Target t = targets.get(i);
            if (t.broken) {
                say(EnumChatFormatting.RED + "  " + t.tag + ": failed - " + t.error);
                continue;
            }
            String line = "  " + t.tag + ": " + t.hits.size() + " found";
            if (!t.active) {
                say(EnumChatFormatting.DARK_GRAY + line
                    + "  [INACTIVE - never ran in this dimension, ignore these]");
            } else if ("verified".equals(t.verdict)) {
                say(EnumChatFormatting.GREEN + line + "  [verified " + t.reproduced + "/"
                    + t.knownInRegion + " known]");
            } else if ("UNRELIABLE".equals(t.verdict)) {
                say(EnumChatFormatting.RED + line + "  [UNRELIABLE: only " + t.reproduced + "/"
                    + t.knownInRegion + " known structures reproduced]");
            } else {
                say(EnumChatFormatting.YELLOW + line + "  [" + t.verdict + "]");
            }
        }
        say(EnumChatFormatting.GRAY + "-> " + out.getPath());
        publishToMap();
    }

    /** Hand the results to the in-game map, carrying the verdict with them. */
    private void publishToMap() {
        List<com.structsurveyor.map.MapMarkers.Marker> out =
            new ArrayList<com.structsurveyor.map.MapMarkers.Marker>();
        for (int i = 0; i < targets.size(); i++) {
            Target t = targets.get(i);
            if (t.broken || !t.active) continue;
            boolean ok = "verified".equals(t.verdict);
            for (int h = 0; h < t.hits.size(); h++) {
                int[] c = t.hits.get(h);
                out.add(new com.structsurveyor.map.MapMarkers.Marker(
                    t.tag, c[0] * 16 + 8, c[1] * 16 + 8, "predicted", ok));
            }
        }
        com.structsurveyor.map.MapMarkers.publishPredicted(dimension, out);
    }

    private void writeJson(File out) throws Exception {
        Map<String, Object> root = new LinkedHashMap<String, Object>();
        root.put("world_seed", worldSeed);
        root.put("dimension", dimension);
        root.put("center_chunk_x", centerChunkX);
        root.put("center_chunk_z", centerChunkZ);
        root.put("radius_chunks", radiusChunks);
        root.put("chunks_scanned", processed);

        List<Object> gens = new ArrayList<Object>();
        for (int i = 0; i < targets.size(); i++) {
            Target t = targets.get(i);
            Map<String, Object> g = new LinkedHashMap<String, Object>();
            g.put("tag", t.tag);
            g.put("class", t.gen.getClass().getName());
            g.put("failed", t.broken);
            if (t.error != null) g.put("error", t.error);
            g.put("verdict", t.verdict);
            g.put("active_in_dimension", t.active);
            g.put("known_in_region", t.knownInRegion);
            g.put("known_reproduced", t.reproduced);

            List<Object> found = new ArrayList<Object>();
            for (int h = 0; h < t.hits.size(); h++) {
                int[] c = t.hits.get(h);
                Map<String, Object> e = new LinkedHashMap<String, Object>();
                e.put("chunk_x", c[0]);
                e.put("chunk_z", c[1]);
                e.put("x", c[0] * 16 + 8);
                e.put("z", c[1] * 16 + 8);
                found.add(e);
            }
            g.put("count", found.size());
            g.put("structures", found);
            gens.add(g);
        }
        root.put("generators", gens);

        Json.writeFile(out, root);
    }

    private void say(String msg) {
        sender.addChatMessage(new ChatComponentText(msg));
    }
}
