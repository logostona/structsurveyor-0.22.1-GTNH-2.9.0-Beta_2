package com.structsurveyor;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.command.WrongUsageException;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.IChunkProvider;
import net.minecraft.world.gen.ChunkProviderServer;
import net.minecraft.world.gen.structure.MapGenStructure;

public class SurveyCommand extends CommandBase {

    private static SurveyTask current;
    private static int chunksPerTick = 2000;

    @Override
    public String getCommandName() {
        return "survey";
    }

    @Override
    public String getCommandUsage(ICommandSender sender) {
        return "/survey <radius> [x z] | list | diag | status | stop | rate <n>";
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 2;
    }

    @Override
    public void processCommand(ICommandSender sender, String[] args) {
        if (args.length == 0) throw new WrongUsageException(getCommandUsage(sender));

        String sub = args[0].toLowerCase();
        if ("list".equals(sub)) { doList(sender, args); return; }
        if ("status".equals(sub)) { doStatus(sender); return; }
        if ("stop".equals(sub)) { doStop(sender); return; }
        if ("rate".equals(sub)) { doRate(sender, args); return; }
        if ("diag".equals(sub)) { doDiag(sender); return; }
        doRun(sender, args);
    }

    // ----------------------------------------------------------------

    private void doList(ICommandSender sender, String[] args) {
        boolean full = args.length > 1 && "full".equalsIgnoreCase(args[1]);
        WorldServer world = worldOf(sender);
        List<MapGenStructure> gens = discover(world);
        if (gens.isEmpty()) {
            reply(sender, EnumChatFormatting.YELLOW
                + "No MapGenStructure generators found in " + dimLabel(world) + ".");
            reply(sender, EnumChatFormatting.GRAY
                + "This dimension's structures likely use custom worldgen, which this cannot predict.");
            return;
        }
        reply(sender, EnumChatFormatting.GREEN + "Generators in " + dimLabel(world) + ":");
        for (int i = 0; i < gens.size(); i++) {
            MapGenStructure g = gens.get(i);
            boolean active = GeneratorRefs.hasRun(g);
            String line = "  " + GeneratorRefs.tagOf(g) + (active ? "" : "  [inactive]");
            if (full) line += EnumChatFormatting.DARK_GRAY + "  " + g.getClass().getName();
            reply(sender, (active ? EnumChatFormatting.GRAY : EnumChatFormatting.DARK_GRAY) + line);
        }
    }

    /**
     * The running survey, or null.
     *
     * A finished task clears itself here rather than lingering: otherwise a
     * completed sweep still looks active, and the next /survey is refused until
     * you stop something that already stopped. Clearing lazily on read also
     * covers a task that died without reaching finish().
     */
    private static SurveyTask activeTask() {
        if (current != null && current.isDone()) current = null;
        return current;
    }

    private void doDiag(ICommandSender sender) {
        if (!GeneratorRefs.resolve()) {
            reply(sender, EnumChatFormatting.RED
                + "Cannot access structure internals: " + GeneratorRefs.resolveError());
            return;
        }
        WorldServer world = worldOf(sender);
        List<MapGenStructure> gens = discover(world);
        if (gens.isEmpty()) {
            reply(sender, EnumChatFormatting.YELLOW + "No generators found in " + dimLabel(world) + ".");
            return;
        }
        reply(sender, EnumChatFormatting.GRAY
            + "Testing 40 seeding candidates against each generator's own predicate...");
        SeedDiagnostic.run(sender, world, gens);
    }

    private void doStatus(ICommandSender sender) {
        reply(sender, activeTask() == null
            ? EnumChatFormatting.GRAY + "No survey running."
            : EnumChatFormatting.GRAY + "Survey in progress. /survey stop to cancel.");
    }

    private void doStop(ICommandSender sender) {
        SurveyTask task = activeTask();
        if (task == null) {
            reply(sender, EnumChatFormatting.GRAY + "No survey running.");
            return;
        }
        task.stop("cancelled");
        current = null;
    }

    private void doRate(ICommandSender sender, String[] args) {
        if (args.length < 2) {
            reply(sender, EnumChatFormatting.GRAY + "Chunks per tick: " + chunksPerTick);
            return;
        }
        chunksPerTick = Math.max(1, parseInt(sender, args[1]));
        reply(sender, EnumChatFormatting.GREEN + "Chunks per tick: " + chunksPerTick
            + EnumChatFormatting.GRAY + " (lower this if the game stutters)");
    }

    private void doRun(ICommandSender sender, String[] args) {
        if (activeTask() != null) {
            reply(sender, EnumChatFormatting.YELLOW
                + "A survey is already running. /survey stop first.");
            return;
        }
        if (!GeneratorRefs.resolve()) {
            reply(sender, EnumChatFormatting.RED
                + "Cannot access structure internals: " + GeneratorRefs.resolveError());
            return;
        }

        int radiusBlocks = parseInt(sender, args[0]);
        if (radiusBlocks < 16) throw new WrongUsageException("Radius must be at least 16 blocks.");

        WorldServer world = worldOf(sender);
        int centerX, centerZ;
        if (args.length >= 3) {
            centerX = parseInt(sender, args[1]);
            centerZ = parseInt(sender, args[2]);
        } else {
            centerX = (int) Math.floor(sender.getPlayerCoordinates().posX);
            centerZ = (int) Math.floor(sender.getPlayerCoordinates().posZ);
        }

        List<MapGenStructure> gens = discover(world);
        if (gens.isEmpty()) {
            reply(sender, EnumChatFormatting.YELLOW + "No generators found in " + dimLabel(world) + ".");
            return;
        }

        int radiusChunks = radiusBlocks >> 4;
        current = new SurveyTask(world, sender, centerX >> 4, centerZ >> 4,
                                 radiusChunks, chunksPerTick, gens);
        current.start();
    }

    // ----------------------------------------------------------------

    private static List<MapGenStructure> discover(WorldServer world) {
        if (!GeneratorRefs.resolve()) return new ArrayList<MapGenStructure>();
        IChunkProvider provider = world.theChunkProviderServer;
        if (provider instanceof ChunkProviderServer) {
            IChunkProvider inner = ((ChunkProviderServer) provider).currentChunkProvider;
            if (inner != null) provider = inner;
        }
        return GeneratorRefs.discover(provider);
    }

    private static WorldServer worldOf(ICommandSender sender) {
        if (sender instanceof EntityPlayerMP) {
            return (WorldServer) ((EntityPlayerMP) sender).worldObj;
        }
        return (WorldServer) sender.getEntityWorld();
    }

    private static String dimLabel(WorldServer world) {
        return "DIM" + world.provider.dimensionId;
    }

    private static void reply(ICommandSender sender, String msg) {
        sender.addChatMessage(new ChatComponentText(msg));
    }

    @Override
    public List<?> addTabCompletionOptions(ICommandSender sender, String[] args) {
        if (args.length == 1) {
            return getListOfStringsMatchingLastWord(args, "list", "diag", "status", "stop", "rate");
        }
        return null;
    }
}
