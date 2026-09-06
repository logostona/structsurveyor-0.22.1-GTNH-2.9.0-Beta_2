package com.biomelocator;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.client.Minecraft;
import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.world.WorldServer;
import net.minecraft.world.biome.BiomeGenBase;
import net.minecraft.world.biome.WorldChunkManager;

/**
 * /biome - read real biome data, and test whether a local world reproduces it.
 *
 * The order matters. Predicting where a biome is means replaying world
 * generation, and a replay that has not been checked against reality produces
 * coordinates that look entirely reasonable and are wrong. So this ships the
 * check first and the search afterwards.
 */
public class CommandBiome extends CommandBase {

    @Override
    public String getCommandName() {
        return "biome";
    }

    @Override
    public String getCommandUsage(ICommandSender sender) {
        return "/biome <here|sample|verify|id>";
    }

    /** A client command: there is no server to ask about permissions. */
    @Override
    public boolean canCommandSenderUseCommand(ICommandSender sender) {
        return true;
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 0;
    }

    @Override
    public void processCommand(ICommandSender sender, String[] args) {
        String sub = args.length == 0 ? "help" : args[0].toLowerCase();
        try {
            if ("here".equals(sub)) {
                here(sender);
            } else if ("sample".equals(sub)) {
                sample(sender, args);
            } else if ("verify".equals(sub)) {
                verify(sender, args);
            } else if ("id".equals(sub)) {
                lookup(sender, args);
            } else {
                help(sender);
            }
        } catch (Throwable t) {
            say(sender, EnumChatFormatting.RED + "failed: " + t);
            BiomeLocator.LOG.warn("/biome " + sub + " failed", t);
        }
    }

    private void help(ICommandSender sender) {
        say(sender, EnumChatFormatting.AQUA + "Biome Locator");
        say(sender, EnumChatFormatting.GRAY + "/biome here"
            + EnumChatFormatting.WHITE + " - biome actually stored where you stand");
        say(sender, EnumChatFormatting.GRAY + "/biome sample [chunkRadius]"
            + EnumChatFormatting.WHITE + " - record real biome ids from loaded chunks");
        say(sender, EnumChatFormatting.GRAY + "/biome verify [file]"
            + EnumChatFormatting.WHITE + " - does THIS world reproduce those recordings?");
        say(sender, EnumChatFormatting.GRAY + "/biome id <text>"
            + EnumChatFormatting.WHITE + " - registered biomes matching a name");
    }

    // ---- reading what is really there ------------------------------------

    private void here(ICommandSender sender) {
        Minecraft mc = Minecraft.getMinecraft();
        EntityPlayer p = mc.thePlayer;
        int x = (int) Math.floor(p.posX), z = (int) Math.floor(p.posZ);
        BiomeGenBase stored = mc.theWorld.getBiomeGenForCoords(x, z);
        say(sender, EnumChatFormatting.AQUA + "at " + x + ", " + z);
        say(sender, EnumChatFormatting.WHITE + "  stored: " + describe(stored));

        // In singleplayer the generator can be asked directly, which is what a
        // search would rely on. Showing both side by side makes it obvious when
        // they have diverged.
        WorldChunkManager wcm = localChunkManager(p.dimension);
        if (wcm != null) {
            say(sender, EnumChatFormatting.WHITE + "  generator: "
                + describe(wcm.getBiomeGenAt(x, z)));
        }
    }

    /**
     * Record the biome id in every loaded chunk around the player.
     *
     * These come from chunk data the server sent to draw the world, so they are
     * what the server's own generator produced - not a guess, and not something
     * the server was asked for.
     */
    private void sample(ICommandSender sender, String[] args) throws Exception {
        Minecraft mc = Minecraft.getMinecraft();
        EntityPlayer p = mc.thePlayer;
        int radius = args.length > 1 ? boundedInt(sender, args[1], 1, 32) : 12;

        int pcx = ((int) Math.floor(p.posX)) >> 4;
        int pcz = ((int) Math.floor(p.posZ)) >> 4;

        BiomeSamples out = new BiomeSamples();
        out.dimension = p.dimension;
        out.source = worldLabel();

        int skipped = 0;
        for (int cx = pcx - radius; cx <= pcx + radius; cx++) {
            for (int cz = pcz - radius; cz <= pcz + radius; cz++) {
                int x = (cx << 4) + 8, z = (cz << 4) + 8;
                if (!mc.theWorld.blockExists(x, 64, z)) {
                    skipped++;                       // beyond view distance
                    continue;
                }
                BiomeGenBase b = mc.theWorld.getBiomeGenForCoords(x, z);
                if (b != null) out.add(x, z, b.biomeID);
            }
        }

        File file = sampleFile(out.dimension);
        int fresh = out.size();
        out.save(file);
        say(sender, EnumChatFormatting.GREEN + "recorded " + fresh + " chunks ("
            + skipped + " not loaded)");
        say(sender, EnumChatFormatting.WHITE + "  " + out.size()
            + " total in " + file.getName());
        say(sender, EnumChatFormatting.GRAY
            + "  walk somewhere else and run it again to strengthen the test");
    }

    // ---- testing a replay against them -----------------------------------

    /**
     * Compare this world's generator against readings taken from another world.
     *
     * Two different failures hide behind "it does not match", and telling them
     * apart is the whole job:
     *
     *  - the biome LAYOUT differs, meaning the seed, world type or generation
     *    settings are not the same and no coordinate from here transfers;
     *  - the layout is identical but the biome IDS are numbered differently,
     *    which is only a config difference and is entirely survivable.
     *
     * A raw match percentage cannot distinguish them, so the mapping from each
     * recorded id to what this world puts there is scored too. A consistent
     * one-to-one mapping means the worlds agree about where things are.
     */
    private void verify(ICommandSender sender, String[] args) throws Exception {
        BiomeSamples truth = new BiomeSamples();
        File file = args.length > 1
            ? new File(dataDir(), args[1])
            : sampleFile(Minecraft.getMinecraft().thePlayer.dimension);
        if (!file.isFile()) {
            // The default name is built from the world you are standing in, and
            // verification is by definition run somewhere else - so a miss here
            // is the normal case, not an error. Show what there is to pick from.
            say(sender, EnumChatFormatting.RED + "no recordings at " + file.getName());
            File[] found = dataDir().listFiles();
            boolean any = false;
            if (found != null) {
                for (File f : found) {
                    if (!f.getName().endsWith(".csv")) continue;
                    if (!any) say(sender, EnumChatFormatting.GRAY + "recordings you have:");
                    any = true;
                    say(sender, EnumChatFormatting.WHITE + "  /biome verify " + f.getName());
                }
            }
            if (!any) {
                say(sender, EnumChatFormatting.GRAY
                    + "run /biome sample on the world you want to reproduce first");
            }
            return;
        }
        truth.load(file);
        if (truth.size() == 0) {
            say(sender, EnumChatFormatting.RED + "that file holds no readings");
            return;
        }

        WorldChunkManager wcm = localChunkManager(truth.dimension);
        if (wcm == null) {
            say(sender, EnumChatFormatting.RED
                + "no local world generator - open a singleplayer world to test against");
            return;
        }

        WorldServer ws = MinecraftServer.getServer().worldServerForDimension(truth.dimension);
        say(sender, EnumChatFormatting.AQUA + "testing this world against "
            + truth.size() + " readings from " + truth.source);
        say(sender, EnumChatFormatting.WHITE + "  local seed: " + ws.getSeed());
        say(sender, EnumChatFormatting.WHITE + "  local type: "
            + ws.getWorldInfo().getTerrainType().getWorldTypeName());

        // recorded id -> what this world generates there -> how often
        Map<Integer, Map<Integer, Integer>> mapping =
            new LinkedHashMap<Integer, Map<Integer, Integer>>();
        int exact = 0;
        for (BiomeSamples.Sample s : truth.samples) {
            BiomeGenBase local = wcm.getBiomeGenAt(s.x, s.z);
            int got = local == null ? -1 : local.biomeID;
            if (got == s.biomeId) exact++;
            Map<Integer, Integer> hist = mapping.get(s.biomeId);
            if (hist == null) {
                hist = new LinkedHashMap<Integer, Integer>();
                mapping.put(s.biomeId, hist);
            }
            Integer n = hist.get(got);
            hist.put(got, n == null ? 1 : n + 1);
        }

        // How much of the data is explained by giving each recorded id its single
        // most common local counterpart.
        int explained = 0;
        List<String> rows = new ArrayList<String>();
        for (Map.Entry<Integer, Map<Integer, Integer>> e : mapping.entrySet()) {
            int best = -1, bestN = 0, total = 0;
            for (Map.Entry<Integer, Integer> h : e.getValue().entrySet()) {
                total += h.getValue();
                if (h.getValue() > bestN) {
                    bestN = h.getValue();
                    best = h.getKey();
                }
            }
            explained += bestN;
            rows.add(String.format("  %3d %-22s -> %3d %-22s %3d/%d",
                e.getKey(), nameOf(e.getKey()), best, nameOf(best), bestN, total));
        }

        int n = truth.size();
        double exactPct = exact * 100.0 / n;
        double mapPct = explained * 100.0 / n;
        say(sender, String.format(EnumChatFormatting.WHITE + "  same id: %.1f%%   "
            + "consistent mapping: %.1f%%", exactPct, mapPct));

        if (exactPct >= 95.0) {
            say(sender, EnumChatFormatting.GREEN
                + "  MATCH - this world reproduces the recordings.");
            say(sender, EnumChatFormatting.GRAY
                + "  Coordinates found here will hold on the world they came from.");
        } else if (mapPct >= 95.0) {
            say(sender, EnumChatFormatting.GREEN
                + "  LAYOUT MATCHES, ids are numbered differently.");
            say(sender, EnumChatFormatting.GRAY
                + "  A config difference, not a different world. Use the mapping below.");
        } else if (mapPct >= 70.0) {
            say(sender, EnumChatFormatting.YELLOW
                + "  PARTIAL - related but not the same world.");
            say(sender, EnumChatFormatting.GRAY
                + "  Usually a different pack version or edited biome settings.");
        } else {
            say(sender, EnumChatFormatting.RED
                + "  NO MATCH - this is a different world.");
            say(sender, EnumChatFormatting.GRAY
                + "  Check the seed and world type. Until this passes, any predicted");
            say(sender, EnumChatFormatting.GRAY
                + "  coordinate is guesswork - that is what this command is for.");
        }

        int shown = 0;
        for (String row : rows) {
            if (shown++ >= 12) {
                say(sender, EnumChatFormatting.GRAY + "  ... and "
                    + (rows.size() - 12) + " more");
                break;
            }
            say(sender, EnumChatFormatting.GRAY + row);
        }
    }

    // ---- odds and ends ---------------------------------------------------

    private void lookup(ICommandSender sender, String[] args) {
        if (args.length < 2) {
            say(sender, EnumChatFormatting.RED + "usage: /biome id <text>");
            return;
        }
        StringBuilder q = new StringBuilder();
        for (int i = 1; i < args.length; i++) {
            if (i > 1) q.append(' ');
            q.append(args[i]);
        }
        String needle = q.toString().toLowerCase();
        int found = 0;
        BiomeGenBase[] all = BiomeGenBase.getBiomeGenArray();
        for (int i = 0; i < all.length; i++) {
            BiomeGenBase b = all[i];
            if (b == null || b.biomeName == null) continue;
            if (!b.biomeName.toLowerCase().contains(needle)) continue;
            say(sender, EnumChatFormatting.WHITE + "  " + b.biomeID + "  " + b.biomeName);
            if (++found >= 20) break;
        }
        if (found == 0) say(sender, EnumChatFormatting.GRAY + "  nothing matches");
    }

    /** The integrated server's generator, or null when there is not one. */
    private static WorldChunkManager localChunkManager(int dimension) {
        try {
            MinecraftServer server = MinecraftServer.getServer();
            if (server == null) return null;
            WorldServer ws = server.worldServerForDimension(dimension);
            return ws == null ? null : ws.getWorldChunkManager();
        } catch (Throwable t) {
            return null;
        }
    }

    private static String describe(BiomeGenBase b) {
        return b == null ? "none" : b.biomeID + " " + b.biomeName;
    }

    private static String nameOf(int id) {
        if (id < 0) return "-";
        BiomeGenBase b = BiomeGenBase.getBiome(id);
        return b == null || b.biomeName == null ? "?" : b.biomeName;
    }

    private static File dataDir() {
        return new File(Minecraft.getMinecraft().mcDataDir, "biomelocator");
    }

    private static File sampleFile(int dimension) {
        return new File(dataDir(), "samples-" + worldLabel() + "-DIM" + dimension + ".csv");
    }

    /** Server address, or the save name in singleplayer. */
    private static String worldLabel() {
        Minecraft mc = Minecraft.getMinecraft();
        try {
            // func_147104_D is getCurrentServerData; unnamed in 1.7.10 mappings.
            net.minecraft.client.multiplayer.ServerData sd = mc.func_147104_D();
            if (sd != null && sd.serverIP != null && !sd.serverIP.isEmpty()) {
                return sanitize(sd.serverIP);
            }
            if (mc.getIntegratedServer() != null) {
                return sanitize(mc.getIntegratedServer().getFolderName());
            }
        } catch (Throwable ignored) {
            // fall through to something usable
        }
        return "world";
    }

    private static String sanitize(String s) {
        return s.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static int boundedInt(ICommandSender sender, String s, int lo, int hi) {
        int v;
        try {
            v = Integer.parseInt(s);
        } catch (NumberFormatException e) {
            v = lo;
        }
        return Math.max(lo, Math.min(hi, v));
    }

    private static void say(ICommandSender sender, String msg) {
        sender.addChatMessage(new ChatComponentText(msg));
    }
}
