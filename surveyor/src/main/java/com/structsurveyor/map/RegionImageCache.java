package com.structsurveyor.map;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

import com.structsurveyor.StructureSurveyor;

/**
 * Keeps built region imagery on disk so reopening the map does not re-decode
 * 1024 chunks per region.
 *
 * Each entry records the source .mca file's timestamp. If the region file has
 * changed since - because you explored more of it - the cache entry is ignored
 * and rebuilt, so the map can never show stale terrain.
 *
 * Compressed with BEST_SPEED: a region of terrain colours deflates well, and the
 * write happens on the render thread, so trading ratio for latency is the right
 * way round here.
 */
final class RegionImageCache {

    private static final int MAGIC = 0x53535631;        // "SSV1"

    private final File dir;

    RegionImageCache(File dir) {
        this.dir = dir;
    }

    private File fileFor(int rx, int rz) {
        return new File(dir, "r." + rx + "." + rz + ".img");
    }

    /** Pixels for a region, or null if absent or out of date. */
    int[] load(int rx, int rz, long sourceModified) {
        File f = fileFor(rx, rz);
        if (!f.isFile()) return null;
        DataInputStream in = null;
        try {
            in = new DataInputStream(new InflaterInputStream(
                new BufferedInputStream(new FileInputStream(f))));
            if (in.readInt() != MAGIC) return null;
            if (in.readLong() != sourceModified) return null;   // region changed
            int n = in.readInt();
            if (n != MapTiles.REGION_PX * MapTiles.REGION_PX) return null;
            int[] pixels = new int[n];
            for (int i = 0; i < n; i++) pixels[i] = in.readInt();
            return pixels;
        } catch (Throwable t) {
            return null;                                        // treat as a miss
        } finally {
            close(in);
        }
    }

    void save(int rx, int rz, long sourceModified, int[] pixels) {
        if (!dir.isDirectory() && !dir.mkdirs()) return;
        File f = fileFor(rx, rz);
        DataOutputStream out = null;
        try {
            out = new DataOutputStream(new DeflaterOutputStream(
                new BufferedOutputStream(new FileOutputStream(f)),
                new Deflater(Deflater.BEST_SPEED)));
            out.writeInt(MAGIC);
            out.writeLong(sourceModified);
            out.writeInt(pixels.length);
            for (int p : pixels) out.writeInt(p);
        } catch (Throwable t) {
            StructureSurveyor.LOG.debug("could not cache region " + rx + "," + rz);
        } finally {
            close(out);
        }
    }

    private static void close(java.io.Closeable c) {
        if (c == null) return;
        try {
            c.close();
        } catch (Throwable ignored) {
            // nothing useful to do
        }
    }
}
