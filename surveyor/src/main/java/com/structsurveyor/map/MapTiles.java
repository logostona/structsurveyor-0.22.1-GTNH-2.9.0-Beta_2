package com.structsurveyor.map;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.world.biome.BiomeGenBase;

import com.structsurveyor.StructureSurveyor;

/**
 * Terrain imagery for the map, built from the save's own chunk data.
 *
 * One texture per region file (32x32 chunks = 512x512 pixels, one pixel per
 * block). Each pixel is its biome's map colour, shaded by the slope between
 * neighbouring columns, which is what gives the picture its relief.
 *
 * Only already-generated chunks exist on disk, so the map shows exactly what has
 * been explored - and nothing is generated to draw it.
 *
 * Regions are decoded on a worker thread through our own read-only reader, so
 * neither the file I/O nor the NBT parsing lands inside a frame. The render
 * thread does only what it must: upload the finished texture and commit detected
 * hits. Using our own reader rather than Minecraft's RegionFileCache keeps us out
 * of the lock the integrated server is using.
 */
public class MapTiles {

    public static final int REGION_PX = 512;          // 32 chunks * 16 blocks

    /** How many chunks to decode per frame. Keeps opening the map smooth. */
    private static final int CHUNKS_PER_FRAME = 24;

    /** Regions kept resident; each texture is 1 MB, so this is the memory cap. */
    private static int maxRegions() {
        return com.structsurveyor.Config.maxResidentRegions;
    }

    private static class Region {
        final int rx, rz;
        DynamicTexture texture;
        int[] pixels;
        int cursor;                                   // 0..1023, chunks decoded
        boolean complete;
        boolean fromCache;                            // do not rewrite what we read

        Region(int rx, int rz) {
            this.rx = rx;
            this.rz = rz;
        }
    }

    private final File dimensionDir;
    private final RegionImageCache imageCache;

    /**
     * A decoded region, waiting for the render thread.
     *
     * Reading a region means 1024 file reads, inflates and NBT parses. Doing that
     * inside a frame budget was the single largest cost left: measured at ~93,000
     * chunks in one session with the integrated server falling seconds behind.
     * The worker produces finished pixels plus the hits it found; the render
     * thread only uploads the texture and commits the hits, neither of which can
     * be done off-thread.
     */
    private static final class Decoded {
        final int rx, rz;
        final int[] pixels;                           // null for a scan-only pass
        final List<long[]> hitChunks = new ArrayList<long[]>();
        final List<List<SignatureScan.RawHit>> hits =
            new ArrayList<List<SignatureScan.RawHit>>();
        final boolean fromCache;

        Decoded(int rx, int rz, int[] pixels, boolean fromCache) {
            this.rx = rx;
            this.rz = rz;
            this.pixels = pixels;
            this.fromCache = fromCache;
        }
    }

    private final java.util.concurrent.ConcurrentLinkedQueue<Decoded> ready =
        new java.util.concurrent.ConcurrentLinkedQueue<Decoded>();
    private final java.util.Set<Long> inFlight =
        java.util.Collections.synchronizedSet(new java.util.HashSet<Long>());
    private final java.util.concurrent.ExecutorService DECODER =
        java.util.concurrent.Executors.newSingleThreadExecutor(
            new java.util.concurrent.ThreadFactory() {
                @Override
                public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "Surveyor region decoder");
                    t.setDaemon(true);
                    t.setPriority(Thread.MIN_PRIORITY);
                    return t;
                }
            });
    /** Confined to the decoder thread. */
    private RegionReader workerReader;
    /** Riders on the same chunk decode: block/spawner signature detection. */
    private SignatureScan scan;
    /** Regions queued for signature scanning without building imagery. */
    private final java.util.ArrayDeque<long[]> scanQueue = new java.util.ArrayDeque<long[]>();
    private int scanCursor;
    private int scanTotal;
    /**
     * Access-ordered, so eviction drops the region you looked at longest ago.
     *
     * Insertion order was wrong: it evicted the oldest *created* region, which
     * after enough panning is often one still on screen - terrain would vanish
     * while off-screen regions survived.
     */
    private final Map<Long, Region> regions =
        new LinkedHashMap<Long, Region>(16, 0.75f, true);
    // Filled while painting, which now happens on the decode worker.
    private final Map<Integer, Integer> biomeColors =
        new java.util.concurrent.ConcurrentHashMap<Integer, Integer>();
    /** Which region files exist, so empty map area costs nothing. */
    private java.util.Set<Long> existing;
    private long existingCheckedAt;
    /** Last answer from regionsIn, reused while the view has not moved. */
    private List<int[]> visibleCache;
    private double visKey0, visKey1, visKey2, visKey3;

    public MapTiles(File dimensionDir, File cacheDir) {
        this.dimensionDir = dimensionDir;
        this.imageCache = (cacheDir == null || !com.structsurveyor.Config.cacheToDisk)
            ? null : new RegionImageCache(cacheDir);
    }

    public void setScan(SignatureScan scan) {
        this.scan = scan;
    }

    public boolean available() {
        return dimensionDir != null && new File(dimensionDir, "region").isDirectory();
    }

    private static long chunkKey(int cx, int cz) {
        return (long) cx & 0xFFFFFFFFL | ((long) cz & 0xFFFFFFFFL) << 32;
    }

    private static long key(int rx, int rz) {
        return (long) rx & 0xFFFFFFFFL | ((long) rz & 0xFFFFFFFFL) << 32;
    }

    /** Hard ceiling on quads per frame, to survive an absurd zoom-out. */
    private static final int MAX_DRAWN = 400;

    /**
     * The set of regions with a file on disk, refreshed occasionally so newly
     * explored area appears.
     *
     * Without this, a zoomed-out view counts every empty region slot inside the
     * viewport as a candidate. That both blew past the resident limit - which
     * silently stopped any new imagery being built - and allocated a megabyte
     * texture per region that was never going to contain anything.
     */
    private java.util.Set<Long> existingRegions() {
        long now = System.currentTimeMillis();
        if (existing != null && now - existingCheckedAt < 5000L) return existing;
        java.util.Set<Long> set = new java.util.HashSet<Long>();
        File[] files = new File(dimensionDir, "region").listFiles();
        if (files != null) {
            for (File f : files) {
                String n = f.getName();
                if (!n.startsWith("r.") || !n.endsWith(".mca")) continue;
                int d1 = n.indexOf('.'), d2 = n.indexOf('.', d1 + 1), d3 = n.indexOf('.', d2 + 1);
                if (d1 < 0 || d2 < 0 || d3 < 0) continue;
                try {
                    set.add(key(Integer.parseInt(n.substring(d1 + 1, d2)),
                                Integer.parseInt(n.substring(d2 + 1, d3))));
                } catch (NumberFormatException ignored) {
                    // not a region file name we understand
                }
            }
        }
        if (existing == null || !existing.equals(set)) visibleCache = null;
        existing = set;
        existingCheckedAt = now;
        return set;
    }

    /**
     * Regions overlapping the given block-coordinate window, nearest to the view
     * centre first.
     *
     * Ordering matters at low zoom: when more regions are visible than can be
     * held in memory, the ones the eye is actually on should be the ones that
     * get built.
     */
    public List<int[]> regionsIn(double minX, double minZ, double maxX, double maxZ) {
        // The set only changes when the view does, and this is called every
        // frame; allocating and sorting it each time is pure waste.
        if (visibleCache != null && minX == visKey0 && minZ == visKey1
            && maxX == visKey2 && maxZ == visKey3) {
            return visibleCache;
        }
        final double cx = (minX + maxX) / 2 / REGION_PX;
        final double cz = (minZ + maxZ) / 2 / REGION_PX;
        int r0x = (int) Math.floor(minX / REGION_PX), r1x = (int) Math.floor(maxX / REGION_PX);
        int r0z = (int) Math.floor(minZ / REGION_PX), r1z = (int) Math.floor(maxZ / REGION_PX);

        java.util.Set<Long> present = existingRegions();
        List<int[]> out = new ArrayList<int[]>();
        for (int rx = r0x; rx <= r1x; rx++) {
            for (int rz = r0z; rz <= r1z; rz++) {
                if (present.contains(key(rx, rz))) out.add(new int[] { rx, rz });
            }
        }
        java.util.Collections.sort(out, new java.util.Comparator<int[]>() {
            @Override
            public int compare(int[] a, int[] b) {
                double da = (a[0] + .5 - cx) * (a[0] + .5 - cx) + (a[1] + .5 - cz) * (a[1] + .5 - cz);
                double db = (b[0] + .5 - cx) * (b[0] + .5 - cx) + (b[1] + .5 - cz) * (b[1] + .5 - cz);
                return Double.compare(da, db);
            }
        });
        visibleCache = out.size() > MAX_DRAWN ? out.subList(0, MAX_DRAWN) : out;
        visKey0 = minX; visKey1 = minZ; visKey2 = maxX; visKey3 = maxZ;
        return visibleCache;
    }

    /**
     * The GL texture id for a region, or -1 if it has nothing drawable yet.
     * Decoding advances a little on each call until the region is complete.
     *
     * With allowCreate false, a region that is not already resident is skipped
     * rather than allocated. That is how zooming far out keeps drawing whatever
     * is cached instead of evicting and re-decoding in a loop.
     */
    public int textureFor(int rx, int rz, long[] budget, boolean allowCreate) {
        Region r = regions.get(key(rx, rz));
        if (r == null) {
            if (!allowCreate) return -1;
            if (regions.size() >= maxRegions()) evictOldest();
            r = new Region(rx, rz);
            regions.put(key(rx, rz), r);
            submitImagery(rx, rz);
        } else if (r.texture == null && !inFlight.contains(key(rx, rz))) {
            // Its decode never arrived - a failure, or a result dropped when the
            // world changed. Ask again rather than leaving a permanent hole.
            submitImagery(rx, rz);
        }
        return r.texture == null ? -1 : r.texture.getGlTextureId();
    }

    /** Queue a region for decoding, unless it is already on its way. */
    private void submitImagery(final int rx, final int rz) {
        final long k = key(rx, rz);
        if (!inFlight.add(k)) return;
        DECODER.submit(new Runnable() {
            @Override
            public void run() {
                try {
                    ready.add(decodeRegion(rx, rz, true, Integer.MIN_VALUE,
                        Integer.MIN_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE));
                } catch (Throwable t) {
                    // Logged loudly: a silent failure here shows up as "the map
                    // is blank", with nothing to go on.
                    StructureSurveyor.LOG.warn("region decode failed " + rx + "," + rz, t);
                    decodeFailures++;
                    lastError = t.toString();
                } finally {
                    inFlight.remove(k);
                }
            }
        });
    }

    /** Runs on the decoder thread. Never touches GL or the signature lists. */
    private Decoded decodeRegion(int rx, int rz, boolean wantPixels,
                                 int minCX, int minCZ, int maxCX, int maxCZ) {
        long stamp = sourceModified(rx, rz);
        if (wantPixels && imageCache != null) {
            int[] cached = imageCache.load(rx, rz, stamp);
            if (cached != null) {
                // Imagery is cached, but detection may still be owed for it.
                Decoded d = new Decoded(rx, rz, cached, true);
                collectHits(rx, rz, d);
                return d;
            }
        }
        Decoded d = new Decoded(rx, rz,
            wantPixels ? new int[REGION_PX * REGION_PX] : null, false);
        if (workerReader == null) workerReader = new RegionReader(dimensionDir);

        // Heights and biomes are collected for the whole region first, then
        // shaded in one pass. Shading per chunk had no neighbour to compare
        // against at its first row and column, so every chunk edge came out flat
        // - a faint 16-block grid over the entire map.
        int[] heights = wantPixels ? new int[REGION_PX * REGION_PX] : null;
        int[] biomes = wantPixels ? new int[REGION_PX * REGION_PX] : null;

        for (int idx = 0; idx < 1024; idx++) {
            int cx = rx * 32 + (idx & 31), cz = rz * 32 + (idx >> 5);
            boolean inBounds = cx >= minCX && cx <= maxCX && cz >= minCZ && cz <= maxCZ;
            if (!inBounds && d.pixels == null) continue;   // scan-only: skip outside
            NBTTagCompound level = workerReader.readChunk(cx, cz);
            if (level == null) continue;
            if (heights != null) collectChunk(heights, biomes, level, idx & 31, idx >> 5);
            if (inBounds) gatherHits(level, cx, cz, d);
        }
        if (d.pixels != null) shadeRegion(d.pixels, heights, biomes);
        return d;
    }

    /** Detection only, for a region whose imagery came from cache. */
    private void collectHits(int rx, int rz, Decoded d) {
        if (scan == null || !com.structsurveyor.Config.enableScan) return;
        if (workerReader == null) workerReader = new RegionReader(dimensionDir);
        for (int idx = 0; idx < 1024; idx++) {
            int cx = rx * 32 + (idx & 31), cz = rz * 32 + (idx >> 5);
            if (scan.alreadyScanned(cx, cz)) continue;
            NBTTagCompound level = workerReader.readChunk(cx, cz);
            if (level == null) continue;
            gatherHits(level, cx, cz, d);
        }
    }

    private void gatherHits(NBTTagCompound level, int cx, int cz, Decoded d) {
        if (scan == null || !com.structsurveyor.Config.enableScan) return;
        if (scan.alreadyScanned(cx, cz)) return;
        List<SignatureScan.RawHit> found = new ArrayList<SignatureScan.RawHit>();
        scan.extract(level, cx, cz, found);
        d.hitChunks.add(new long[] { cx, cz });
        d.hits.add(found);
    }

    /**
     * Take finished regions from the worker. Render thread only: creates the
     * texture, uploads it, and commits detections.
     */
    public void drainDecoded(long[] budget) {
        Decoded d;
        while ((d = ready.poll()) != null) {
            Region r = regions.get(key(d.rx, d.rz));
            if (r != null && d.pixels != null) {
                if (r.texture == null) {
                    r.texture = new DynamicTexture(REGION_PX, REGION_PX);
                    r.pixels = r.texture.getTextureData();
                }
                System.arraycopy(d.pixels, 0, r.pixels, 0, r.pixels.length);
                r.texture.updateDynamicTexture();
                r.cursor = 1024;
                r.complete = true;
                r.fromCache = d.fromCache;
                if (imageCache != null && !d.fromCache) {
                    final int[] snapshot = d.pixels;
                    final int rx = d.rx, rz = d.rz;
                    final long stamp = sourceModified(rx, rz);
                    WRITER.submit(new Runnable() {
                        @Override
                        public void run() {
                            imageCache.save(rx, rz, stamp, snapshot);
                        }
                    });
                }
            }
            if (scan != null) {
                for (int i = 0; i < d.hitChunks.size(); i++) {
                    long[] c = d.hitChunks.get(i);
                    scan.apply((int) c[0], (int) c[1], d.hits.get(i));
                }
            }
            scannedRegions++;
            if (!canSpend(budget)) break;             // finish the rest next frame
        }
    }

    private int scannedRegions;
    private volatile int decodeFailures;
    private volatile String lastError;

    /** Pipeline state, for the on-screen readout. */
    public String pipeline() {
        return "regions " + regions.size() + "  queued " + scanQueue.size()
            + "  working " + inFlight.size() + "  ready " + ready.size()
            + "  done " + scannedRegions
            + (decodeFailures > 0 ? "  FAILED " + decodeFailures : "");
    }

    public String lastError() {
        return lastError;
    }

    /** How many regions may be resident at once. */
    public static int residentLimit() {
        return maxRegions();
    }

    private void evictOldest() {
        java.util.Iterator<Map.Entry<Long, Region>> it = regions.entrySet().iterator();
        if (!it.hasNext()) return;
        Region victim = it.next().getValue();
        it.remove();
        if (victim.texture != null) victim.texture.deleteGlTexture();
    }

    private long sourceModified(int rx, int rz) {
        return new File(new File(dimensionDir, "region"),
                        "r." + rx + "." + rz + ".mca").lastModified();
    }


    /**
     * Record terrain height and biome for one chunk's 256 columns.
     *
     * Falls back to walking the block column when HeightMap is missing or stale.
     * Returning early on a missing heightmap used to leave the chunk unpainted,
     * which showed up as black holes inside otherwise finished regions.
     */
    private void collectChunk(int[] heights, int[] biomes, NBTTagCompound level,
                              int localCx, int localCz) {
        int[] height = level.getIntArray("HeightMap");
        if (height == null || height.length < 256) height = heightsFromBlocks(level);
        if (height == null) return;                   // genuinely nothing to draw

        byte[] biome16 = level.getByteArray("Biomes16v2");
        byte[] biome8 = level.getByteArray("Biomes");
        int px0 = localCx * 16, pz0 = localCz * 16;
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                int i = z * 16 + x;
                int id;
                if (biome16 != null && biome16.length >= 512) {
                    // EndlessIDs stores biomes as 16-bit, low byte first.
                    id = (biome16[i * 2] & 0xFF) | ((biome16[i * 2 + 1] & 0xFF) << 8);
                } else if (biome8 != null && biome8.length >= 256) {
                    id = biome8[i] & 0xFF;
                } else {
                    id = 0;
                }
                int o = (pz0 + z) * REGION_PX + (px0 + x);
                heights[o] = height[i];
                biomes[o] = id;
            }
        }
    }

    /** Highest non-air block per column, when the chunk has no usable HeightMap. */
    private int[] heightsFromBlocks(NBTTagCompound level) {
        NBTTagList sections = level.getTagList("Sections", 10);
        if (sections.tagCount() == 0) return null;
        int[] out = new int[256];
        for (int s = 0; s < sections.tagCount(); s++) {
            NBTTagCompound sec = sections.getCompoundTagAt(s);
            byte[] blocks = sec.getByteArray("Blocks");
            if (blocks == null || blocks.length < 4096) continue;
            int baseY = sec.getByte("Y") * 16;
            for (int y = 0; y < 16; y++) {
                int wy = baseY + y + 1;
                int row = y * 256;
                for (int c = 0; c < 256; c++) {
                    if (blocks[row + c] != 0 && wy > out[c]) out[c] = wy;
                }
            }
        }
        return out;
    }

    /**
     * Turn heights and biomes into pixels, comparing each column with its true
     * neighbour. Only the region's outer edge lacks one now, instead of every
     * chunk's.
     */
    private void shadeRegion(int[] pixels, int[] heights, int[] biomes) {
        for (int p = 0; p < pixels.length; p++) {
            int h = heights[p];
            if (h <= 0) continue;                     // never generated: leave blank
            int row = p / REGION_PX, col = p % REGION_PX;
            int hn = row > 0 && heights[p - REGION_PX] > 0 ? heights[p - REGION_PX] : h;
            int hw = col > 0 && heights[p - 1] > 0 ? heights[p - 1] : h;
            int slope = (h - hn) + (h - hw);
            float light = slope > 0 ? 1.14f : slope < 0 ? 0.84f : 1.0f;
            // Fade with altitude so highlands read lighter than valleys.
            light *= 0.78f + 0.34f * Math.min(1f, Math.max(0f, h / 128f));
            pixels[p] = shade(colorOf(biomes[p]), light);
        }
    }

    private int colorOf(int biomeId) {
        Integer cached = biomeColors.get(biomeId);
        if (cached != null) return cached;
        int rgb = 0;
        try {
            BiomeGenBase b = biomeId >= 0 && biomeId < BiomeGenBase.getBiomeGenArray().length
                ? BiomeGenBase.getBiomeGenArray()[biomeId] : null;
            if (b != null) rgb = b.color;
        } catch (Throwable t) {
            StructureSurveyor.LOG.debug("no colour for biome " + biomeId);
        }
        if (rgb == 0) {
            // Plenty of modded biomes never set BiomeGenBase.color, and drawing
            // those as black leaves holes in the map. Derive a stable muted
            // colour from the id instead so each biome stays distinguishable.
            int h = biomeId * 0x9E3779B1;
            rgb = ((90 + ((h >>> 17) & 0x3F)) << 16)
                | ((90 + ((h >>> 9) & 0x3F)) << 8)
                | (90 + ((h >>> 1) & 0x3F));
        }
        biomeColors.put(biomeId, rgb);
        return rgb;
    }

    private static int shade(int rgb, float f) {
        int r = clamp((int) (((rgb >> 16) & 0xFF) * f));
        int g = clamp((int) (((rgb >> 8) & 0xFF) * f));
        int b = clamp((int) ((rgb & 0xFF) * f));
        return 0xFF000000 | (b << 16) | (g << 8) | r;   // DynamicTexture is ABGR
    }

    private static int clamp(int v) {
        return v < 0 ? 0 : v > 255 ? 255 : v;
    }

    /** Add one region to the signature-scan queue if it is not already in it. */
    public void queueRegionForScan(int rx, int rz) {
        for (long[] q : scanQueue) {
            if (q[0] == rx && q[1] == rz) return;
        }
        scanQueue.add(new long[] { rx, rz, Integer.MIN_VALUE, Integer.MIN_VALUE,
                                   Integer.MAX_VALUE, Integer.MAX_VALUE });
        scanTotal = Math.max(scanTotal, scanQueue.size());
    }

    /** Queue every region file in this dimension for signature scanning. */
    /**
     * Queue only the regions covering a radius around a point, and tell the
     * worker which chunks inside them matter.
     *
     * Bounding at chunk level rather than region level is what makes a small
     * radius cheap: a region holds 1024 chunks, so without this asking for 200
     * blocks would still decode everything around it.
     */
    public int scanRadius(int blockX, int blockZ, int radiusBlocks) {
        int minCX = (blockX - radiusBlocks) >> 4, maxCX = (blockX + radiusBlocks) >> 4;
        int minCZ = (blockZ - radiusBlocks) >> 4, maxCZ = (blockZ + radiusBlocks) >> 4;
        java.util.Set<Long> present = existingRegions();
        scanQueue.clear();
        for (int rx = minCX >> 5; rx <= (maxCX >> 5); rx++) {
            for (int rz = minCZ >> 5; rz <= (maxCZ >> 5); rz++) {
                if (!present.contains(key(rx, rz))) continue;
                scanQueue.add(new long[] { rx, rz, minCX, minCZ, maxCX, maxCZ });
            }
        }
        scanTotal = scanQueue.size();
        return scanTotal;
    }

    public void scanEverything() {
        File dir = new File(dimensionDir, "region");
        File[] files = dir.listFiles();
        if (files == null) return;
        scanQueue.clear();
        for (File f : files) {
            String n = f.getName();
            if (!n.startsWith("r.") || !n.endsWith(".mca")) continue;
            // r.<x>.<z>.mca - pulled apart by index rather than a regex.
            int d1 = n.indexOf('.'), d2 = n.indexOf('.', d1 + 1), d3 = n.indexOf('.', d2 + 1);
            if (d1 < 0 || d2 < 0 || d3 < 0) continue;
            try {
                scanQueue.add(new long[] {
                    Integer.parseInt(n.substring(d1 + 1, d2)),
                    Integer.parseInt(n.substring(d2 + 1, d3)),
                    Integer.MIN_VALUE, Integer.MIN_VALUE,
                    Integer.MAX_VALUE, Integer.MAX_VALUE });
            } catch (NumberFormatException ignored) {
                // not a region file name we understand
            }
        }
        scanCursor = 0;
        scanTotal = scanQueue.size();
    }

    public boolean scanning() {
        return !scanQueue.isEmpty() || !inFlight.isEmpty() || !ready.isEmpty();
    }

    public String scanProgress() {
        return scanTotal == 0 ? "" : "scanning " + (scanTotal - scanQueue.size())
            + "/" + scanTotal + " regions";
    }

    /**
     * Feed queued regions to the decoder for detection only.
     *
     * No imagery is built, so scanning a whole world costs time rather than a
     * gigabyte of textures. The work happens on the worker; this only keeps the
     * pipeline fed and hands finished results to drainDecoded.
     */
    public void advanceScan(long[] budget) {
        // Keep a couple of regions in flight: enough to hide the worker's
        // latency, few enough that a queued result is applied promptly.
        while (!scanQueue.isEmpty() && inFlight.size() < 2) {
            long[] head = scanQueue.poll();
            submitScanOnly((int) head[0], (int) head[1],
                           (int) head[2], (int) head[3], (int) head[4], (int) head[5]);
        }
    }

    private void submitScanOnly(final int rx, final int rz, final int minCX,
                                final int minCZ, final int maxCX, final int maxCZ) {
        final long k = key(rx, rz);
        if (!inFlight.add(k)) return;
        DECODER.submit(new Runnable() {
            @Override
            public void run() {
                try {
                    ready.add(decodeRegion(rx, rz, false, minCX, minCZ, maxCX, maxCZ));
                } catch (Throwable t) {
                    StructureSurveyor.LOG.warn("region scan failed " + rx + "," + rz, t);
                    decodeFailures++;
                    lastError = t.toString();
                } finally {
                    inFlight.remove(k);
                }
            }
        });
    }

    /**
     * Height of the highest block at a column, from the chunk's own HeightMap.
     * -1 when the chunk has never been generated.
     */
    /** Terrain height at a column, or -1 if that chunk was never generated. */
    public int surfaceY(int blockX, int blockZ) {
        if (dimensionDir == null) return -1;
        if (mainReader == null) mainReader = new RegionReader(dimensionDir);
        NBTTagCompound level = mainReader.readChunk(blockX >> 4, blockZ >> 4);
        if (level == null) return -1;
        int[] height = level.getIntArray("HeightMap");
        if (height == null || height.length < 256) return -1;
        return height[(blockZ & 15) * 16 + (blockX & 15)];
    }

    /** Confined to the render thread, for one-off lookups like surfaceY. */
    private RegionReader mainReader;
    /** Chunks already taken from the live world, so each is done once. */
    private final java.util.Set<Long> livePatched = new java.util.HashSet<Long>();

    /**
     * Fill in chunks the player is standing in from the loaded world.
     *
     * The decoder reads region files, and a chunk that has just been generated or
     * visited lives in memory until the next save - so the area around the player
     * is exactly where the map has no data. Reading those from the live world is
     * the only way to draw them without forcing a save.
     *
     * Render thread only: it touches the world object.
     */
    public void patchFromLiveWorld(net.minecraft.world.World world, int centerCX,
                                   int centerCZ, int radius, long[] budget) {
        if (world == null) return;
        java.util.Set<Long> dirtyRegions = null;
        for (int cx = centerCX - radius; cx <= centerCX + radius; cx++) {
            for (int cz = centerCZ - radius; cz <= centerCZ + radius; cz++) {
                if (!canSpend(budget)) break;
                long ck = chunkKey(cx, cz);
                if (livePatched.contains(ck)) continue;
                Region r = regions.get(key(cx >> 5, cz >> 5));
                if (r == null || r.pixels == null) continue;
                int bx = cx << 4, bz = cz << 4;
                if (!world.blockExists(bx, 64, bz)) continue;      // not loaded
                livePatched.add(ck);
                budget[0]--;

                int px0 = (cx & 31) * 16, pz0 = (cz & 31) * 16;
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        int wx = bx + x, wz = bz + z;
                        int h = world.getHeightValue(wx, wz);
                        if (h <= 0) continue;
                        // Neighbours come from the world too, so shading is
                        // continuous across chunk edges.
                        int hn = world.getHeightValue(wx, wz - 1);
                        int hw = world.getHeightValue(wx - 1, wz);
                        if (hn <= 0) hn = h;
                        if (hw <= 0) hw = h;
                        int slope = (h - hn) + (h - hw);
                        float light = slope > 0 ? 1.14f : slope < 0 ? 0.84f : 1.0f;
                        light *= 0.78f + 0.34f * Math.min(1f, Math.max(0f, h / 128f));
                        int biome = 0;
                        try {
                            net.minecraft.world.biome.BiomeGenBase b =
                                world.getBiomeGenForCoords(wx, wz);
                            if (b != null) biome = b.biomeID;
                        } catch (Throwable ignored) {
                            // unloaded edge; leave the default colour
                        }
                        r.pixels[(pz0 + z) * REGION_PX + (px0 + x)] =
                            shade(colorOf(biome), light);
                    }
                }
                if (dirtyRegions == null) dirtyRegions = new java.util.HashSet<Long>();
                dirtyRegions.add(key(cx >> 5, cz >> 5));
            }
        }
        if (dirtyRegions != null) {
            for (Long k : dirtyRegions) {
                Region r = regions.get(k);
                if (r != null && r.texture != null) r.texture.updateDynamicTexture();
            }
        }
    }

    /** Let the live pass run again, e.g. after a manual rescan. */
    public void forgetLivePatches() {
        livePatched.clear();
    }

    /**
     * A standing position at this column: the highest solid block with two free
     * blocks above it, or -1 if the chunk was never generated.
     *
     * Reads the actual blocks rather than the heightmap, for two reasons. The
     * heightmap describes terrain, so teleporting to it can put you inside a
     * structure that sits above or below the recorded surface. And it is absent
     * or stale in some chunks, which previously meant no teleport at all - here
     * the column is analysed on demand instead.
     */
    public int safeStandY(int blockX, int blockZ) {
        if (dimensionDir == null) return -1;
        if (mainReader == null) mainReader = new RegionReader(dimensionDir);
        NBTTagCompound level = mainReader.readChunk(blockX >> 4, blockZ >> 4);
        if (level == null) return -1;                 // never generated

        boolean[] occupied = new boolean[260];
        NBTTagList sections = level.getTagList("Sections", 10);
        int lx = blockX & 15, lz = blockZ & 15;
        boolean any = false;
        for (int s = 0; s < sections.tagCount(); s++) {
            NBTTagCompound sec = sections.getCompoundTagAt(s);
            byte[] blocks = sec.getByteArray("Blocks");
            if (blocks == null || blocks.length < 4096) continue;
            byte[] add = sec.getByteArray("Add");
            boolean hasAdd = add != null && add.length >= 2048;
            int baseY = sec.getByte("Y") * 16;
            for (int y = 0; y < 16; y++) {
                int i = y * 256 + lz * 16 + lx;
                int id = blocks[i] & 0xFF;
                if (hasAdd) {
                    int nib = add[i >> 1] & 0xFF;
                    id |= ((i & 1) == 0 ? (nib & 0x0F) : ((nib >> 4) & 0x0F)) << 8;
                }
                int wy = baseY + y;
                if (id != 0 && wy >= 0 && wy < occupied.length) {
                    occupied[wy] = true;
                    any = true;
                }
            }
        }
        if (!any) return -1;

        // Highest surface with headroom. Starting from the top means a dungeon
        // roof or a treetop is landed on, never entered.
        for (int y = 253; y >= 1; y--) {
            if (occupied[y] && !occupied[y + 1] && !occupied[y + 2]) return y + 1;
        }
        return -1;
    }

    public void dispose() {
        DECODER.shutdownNow();
        ready.clear();
        if (mainReader != null) {
            mainReader.close();
            mainReader = null;
        }
        for (Region r : regions.values()) {
            if (r.texture != null) r.texture.deleteGlTexture();
        }
        regions.clear();
    }

    /**
      * Work allowance for one frame: {chunks remaining, deadline in nanoTime}.
      *
      * A pure chunk count was the wrong bound. Decoding a chunk means an inflate
      * plus an NBT parse, and cost varies hugely, so a fixed count of 96 could be
      * a millisecond or a hundred. Measured result: ~93,000 chunks decoded in one
      * session and the integrated server falling 4.3 seconds behind.
      *
      * Time is the thing that actually matters, so bound by that and treat the
      * count as a safety valve. Create one per frame and share it, or the terrain
      * and scan passes each get a full allowance and double the stall.
      */
    public static long[] frameBudget() {
        return new long[] { 2048L, System.nanoTime()
            + com.structsurveyor.Config.frameBudgetMillis * 1_000_000L };
    }

    private static boolean canSpend(long[] budget) {
        return budget[0] > 0 && System.nanoTime() < budget[1];
    }

    /** Off-thread writer for cache images; deflating 4 MB must not block a frame. */
    private static final java.util.concurrent.ExecutorService WRITER =
        java.util.concurrent.Executors.newSingleThreadExecutor(
            new java.util.concurrent.ThreadFactory() {
                @Override
                public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "Surveyor cache writer");
                    t.setDaemon(true);
                    t.setPriority(Thread.MIN_PRIORITY);
                    return t;
                }
            });
}
