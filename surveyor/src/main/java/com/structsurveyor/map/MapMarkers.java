package com.structsurveyor.map;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.IChunkProvider;
import net.minecraft.world.gen.ChunkProviderServer;
import net.minecraft.world.gen.structure.MapGenStructure;

import com.structsurveyor.GeneratorRefs;
import com.structsurveyor.StructureSurveyor;

/** What the map draws on top of the terrain. */
public final class MapMarkers {

    public static class Marker {

        public final String kind;
        public final int x, z;
        /** "recorded" (the world wrote it) or "predicted" (replayed from seed). */
        public final String source;
        /** False for predictions from a generator that failed self-verification. */
        public final boolean reliable;
        /** Extra context for the tooltip. Never part of the layer name. */
        public final String detail;
        /** Short qualifier shown under the title, e.g. the building to look for. */
        public final String subtitle;
        /** The structure's own Y, or -1 when unknown. Used as a teleport fallback. */
        public final int y;

        public Marker(String kind, int x, int z, String source, boolean reliable) {
            this(kind, x, z, source, reliable, "", "");
        }

        public Marker(String kind, int x, int z, String source, boolean reliable,
                      String detail) {
            this(kind, x, z, source, reliable, detail, "");
        }

        public Marker(String kind, int x, int z, String source, boolean reliable,
                      String detail, String subtitle) {
            this(kind, x, z, source, reliable, detail, subtitle, -1);
        }

        public Marker(String kind, int x, int z, String source, boolean reliable,
                      String detail, String subtitle, int y) {
            this.kind = kind;
            this.x = x;
            this.z = z;
            this.source = source;
            this.reliable = reliable;
            this.detail = detail == null ? "" : detail;
            this.subtitle = subtitle == null ? "" : subtitle;
            this.y = y;
        }
    }

    /** Predictions from the most recent /survey, per dimension, this session. */
    private static final Map<Integer, List<Marker>> predicted =
        new HashMap<Integer, List<Marker>>();
    /**
     * Recorded structures, cached briefly.
     *
     * Reading these walks every generator and loads its saved structure data. The
     * map refreshes its marker list whenever a scan reports a hit - which is
     * every frame during a scan - so without this that whole walk repeated each
     * frame. Structures the world has already written change rarely.
     */
    private static final Map<Integer, List<Marker>> recordedCache =
        new HashMap<Integer, List<Marker>>();
    private static final Map<Integer, Long> recordedAt = new HashMap<Integer, Long>();
    private static final long RECORDED_TTL_MS = 5000L;

    private MapMarkers() {}

    public static void publishPredicted(int dimension, List<Marker> markers) {
        predicted.put(dimension, markers);
    }

    public static boolean hasPredicted(int dimension) {
        return predicted.containsKey(dimension);
    }

    /**
     * Everything to draw for a dimension: this session's predictions plus the
     * structures the world has actually recorded.
     *
     * Recorded markers come from the generators' own structureMap, which only
     * exists on the server - fine in singleplayer, where the integrated server
     * shares this JVM. On a remote server there is nothing to read, so the map
     * falls back to predictions alone.
     */
    public static List<Marker> forDimension(int dimension) {
        List<Marker> out = new ArrayList<Marker>();
        List<Marker> pred = predicted.get(dimension);
        if (pred != null) out.addAll(pred);
        out.addAll(cachedRecorded(dimension));
        return out;
    }

    private static synchronized List<Marker> cachedRecorded(int dimension) {
        long now = System.currentTimeMillis();
        Long at = recordedAt.get(dimension);
        List<Marker> hit = recordedCache.get(dimension);
        if (hit != null && at != null && now - at < RECORDED_TTL_MS) return hit;
        List<Marker> fresh = recorded(dimension);
        recordedCache.put(dimension, fresh);
        recordedAt.put(dimension, now);
        return fresh;
    }

    private static List<Marker> recorded(int dimension) {
        List<Marker> out = new ArrayList<Marker>();
        MinecraftServer server = MinecraftServer.getServer();
        if (server == null || !GeneratorRefs.resolve()) return out;
        WorldServer world;
        try {
            world = server.worldServerForDimension(dimension);
        } catch (Throwable t) {
            return out;
        }
        if (world == null) return out;

        IChunkProvider provider = world.theChunkProviderServer;
        if (provider instanceof ChunkProviderServer) {
            IChunkProvider inner = ((ChunkProviderServer) provider).currentChunkProvider;
            if (inner != null) provider = inner;
        }
        for (MapGenStructure gen : GeneratorRefs.discover(provider)) {
            if (!GeneratorRefs.hasRun(gen)) continue;      // inherited but unused here
            String tag = GeneratorRefs.tagOf(gen);
            try {
                for (int[] c : GeneratorRefs.knownStructureChunks(gen, world)) {
                    out.add(new Marker(tag, c[0] * 16 + 8, c[1] * 16 + 8, "recorded", true));
                }
            } catch (Throwable t) {
                StructureSurveyor.LOG.debug("no recorded structures for " + tag);
            }
        }
        return out;
    }
}
