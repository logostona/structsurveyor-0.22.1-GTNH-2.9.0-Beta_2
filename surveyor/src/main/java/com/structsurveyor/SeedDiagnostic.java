package com.structsurveyor;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import net.minecraft.command.ICommandSender;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.world.WorldServer;
import net.minecraft.world.gen.structure.MapGenStructure;

/**
 * Works out which per-chunk RNG seeding actually drives a generator.
 *
 * The sweep assumes MapGenBase's seeding. When self-verification says a
 * generator is UNRELIABLE, that assumption is wrong for it, and guessing from
 * decompiled source is not enough: a coremod mixin can change the predicate's
 * bytecode at runtime, so the only authority is the live object.
 *
 * So: take the chunks the world really has structures in, and for each candidate
 * seeding ask the generator's own predicate whether it would place there. The
 * right seeding accepts essentially all of them; everything else scores around
 * the base spawn rate.
 */
public final class SeedDiagnostic {

    private SeedDiagnostic() {}

    private static final String[] FORMULAS = {
        "xor/xor (MapGenBase)",
        "add/xor (Forge populate)",
        "add/add",
        "xor no-seed",
        "swapped xor",
    };

    private static long seedFor(int formula, int cx, int cz, long xm, long zm, long worldSeed) {
        switch (formula) {
            case 0:  return (long) cx * xm ^ (long) cz * zm ^ worldSeed;
            case 1:  return ((long) cx * xm + (long) cz * zm) ^ worldSeed;
            case 2:  return (long) cx * xm + (long) cz * zm + worldSeed;
            case 3:  return (long) cx * xm ^ (long) cz * zm;
            default: return (long) cz * xm ^ (long) cx * zm ^ worldSeed;
        }
    }

    public static void run(ICommandSender sender, WorldServer world,
                          List<MapGenStructure> generators) {
        long worldSeed = world.getSeed();

        Random seeder = new Random(worldSeed);
        long xmA = seeder.nextLong();
        long zmA = seeder.nextLong();
        // The variant Forge's chunk populate uses: forced odd.
        seeder = new Random(worldSeed);
        long xmB = seeder.nextLong() / 2L * 2L + 1L;
        long zmB = seeder.nextLong() / 2L * 2L + 1L;

        for (int gi = 0; gi < generators.size(); gi++) {
            MapGenStructure gen = generators.get(gi);
            String tag = GeneratorRefs.tagOf(gen);

            List<int[]> known;
            Random rand;
            try {
                GeneratorRefs.bindWorld(gen, world);
                known = GeneratorRefs.knownStructureChunks(gen, world);
                rand = GeneratorRefs.randOf(gen);
            } catch (Throwable t) {
                say(sender, EnumChatFormatting.RED + tag + ": cannot inspect - " + t);
                continue;
            }

            if (known.isEmpty()) {
                say(sender, EnumChatFormatting.GRAY + tag
                    + ": no known structures to test against, skipping");
                continue;
            }

            String bestLabel = null;
            int bestHits = -1;
            List<String> perfect = new ArrayList<String>();

            for (int mult = 0; mult < 2; mult++) {
                long xm = mult == 0 ? xmA : xmB;
                long zm = mult == 0 ? zmA : zmB;
                for (int formula = 0; formula < FORMULAS.length; formula++) {
                    for (int skips = 0; skips < 4; skips++) {
                        int hits = 0;
                        for (int i = 0; i < known.size(); i++) {
                            int[] c = known.get(i);
                            try {
                                rand.setSeed(seedFor(formula, c[0], c[1], xm, zm, worldSeed));
                                for (int s = 0; s < skips; s++) rand.nextInt();
                                if (GeneratorRefs.canSpawnAt(gen, c[0], c[1])) hits++;
                            } catch (Throwable ignored) {
                                // predicate threw for this chunk; treat as a miss
                            }
                        }
                        String label = FORMULAS[formula]
                            + (mult == 0 ? " | nextLong" : " | nextLong/2*2+1")
                            + " | skips=" + skips;
                        if (hits > bestHits) {
                            bestHits = hits;
                            bestLabel = label;
                        }
                        if (hits == known.size()) perfect.add(label);
                    }
                }
            }

            // Reachability: under randomly chosen RNG states, does the predicate
            // ever accept the chunks that really do contain structures? This
            // separates "we have the wrong seeding" from "this predicate would
            // never place here at all", which no seeding could fix.
            int accepts = 0;
            int trials = 0;
            Random probe = new Random(0xC0FFEEL);
            for (int i = 0; i < known.size() && i < 8; i++) {
                int[] c = known.get(i);
                for (int k = 0; k < 2000; k++) {
                    try {
                        rand.setSeed(probe.nextLong());
                        if (GeneratorRefs.canSpawnAt(gen, c[0], c[1])) accepts++;
                    } catch (Throwable ignored) {
                        // count as a rejection
                    }
                    trials++;
                }
            }

            int n = known.size();
            say(sender, EnumChatFormatting.AQUA + tag + ": " + n + " known chunks tested");
            double pct = trials == 0 ? 0.0 : (accepts * 100.0 / trials);
            if (accepts == 0) {
                say(sender, EnumChatFormatting.RED + String.format(
                    "  reachability 0%% of %d random states - predicate REFUSES these chunks", trials));
                say(sender, EnumChatFormatting.GRAY
                    + "  -> gated on biome/terrain, not RNG. No seeding can reproduce it.");
            } else {
                say(sender, EnumChatFormatting.GRAY + String.format(
                    "  reachability %.2f%% of %d random states (RNG-driven)", pct, trials));
            }
            if (!perfect.isEmpty()) {
                say(sender, EnumChatFormatting.GREEN + "  EXACT MATCH: " + perfect.get(0));
            } else if (bestHits * 100 >= n * 90) {
                say(sender, EnumChatFormatting.GREEN + "  best " + bestHits + "/" + n + ": " + bestLabel);
            } else {
                say(sender, EnumChatFormatting.RED + "  best only " + bestHits + "/" + n
                    + " (" + bestLabel + ")");
                say(sender, EnumChatFormatting.GRAY
                    + "  -> not driven by MapGenBase-style per-chunk seeding at all");
            }
        }
    }

    private static void say(ICommandSender sender, String msg) {
        sender.addChatMessage(new ChatComponentText(msg));
    }
}
