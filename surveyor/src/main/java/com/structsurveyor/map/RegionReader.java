package com.structsurveyor.map;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.RandomAccessFile;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;

/**
 * Reads chunks straight out of region files, read-only.
 *
 * Deliberately not Minecraft's RegionFileCache: that instance is shared with the
 * integrated server, so touching it from a worker thread means contending for its
 * locks with the thread that runs the game. Opening our own handles read-only
 * keeps decoding entirely out of the server's way.
 *
 * Not thread-safe. One instance per worker.
 */
final class RegionReader {

    private static final int SECTOR = 4096;

    private static class Handle {
        final RandomAccessFile file;
        final int[] offsets = new int[1024];
        final int[] sectors = new int[1024];

        Handle(File f) throws Exception {
            file = new RandomAccessFile(f, "r");
            byte[] header = new byte[SECTOR];
            file.readFully(header);
            for (int i = 0; i < 1024; i++) {
                int b = i * 4;
                offsets[i] = ((header[b] & 0xFF) << 16) | ((header[b + 1] & 0xFF) << 8)
                    | (header[b + 2] & 0xFF);
                sectors[i] = header[b + 3] & 0xFF;
            }
        }
    }

    private final File dimensionDir;
    /** A few open handles, oldest evicted; a region is 1024 sequential reads. */
    private final Map<Long, Handle> open = new LinkedHashMap<Long, Handle>();
    private static final int MAX_OPEN = 4;

    RegionReader(File dimensionDir) {
        this.dimensionDir = dimensionDir;
    }

    private static long key(int rx, int rz) {
        return (long) rx & 0xFFFFFFFFL | ((long) rz & 0xFFFFFFFFL) << 32;
    }

    private Handle handle(int rx, int rz) {
        long k = key(rx, rz);
        Handle h = open.get(k);
        if (h != null) return h;
        File f = new File(new File(dimensionDir, "region"), "r." + rx + "." + rz + ".mca");
        if (!f.isFile()) return null;
        try {
            h = new Handle(f);
        } catch (Throwable t) {
            return null;
        }
        if (open.size() >= MAX_OPEN) {
            java.util.Iterator<Map.Entry<Long, Handle>> it = open.entrySet().iterator();
            if (it.hasNext()) {
                Handle victim = it.next().getValue();
                it.remove();
                closeQuietly(victim.file);
            }
        }
        open.put(k, h);
        return h;
    }

    /** Parsed chunk NBT ("Level" tag), or null if absent or unreadable. */
    NBTTagCompound readChunk(int chunkX, int chunkZ) {
        int rx = chunkX >> 5, rz = chunkZ >> 5;
        Handle h = handle(rx, rz);
        if (h == null) return null;
        int idx = (chunkX & 31) | ((chunkZ & 31) << 5);
        if (h.offsets[idx] == 0 || h.sectors[idx] == 0) return null;   // never generated
        try {
            h.file.seek((long) h.offsets[idx] * SECTOR);
            int length = h.file.readInt();
            int scheme = h.file.readUnsignedByte();
            if (length <= 1 || length > h.sectors[idx] * SECTOR) return null;
            byte[] payload = new byte[length - 1];
            h.file.readFully(payload);

            DataInputStream in;
            if (scheme == 1) {
                in = new DataInputStream(new GZIPInputStream(new ByteArrayInputStream(payload)));
            } else if (scheme == 2) {
                in = new DataInputStream(new InflaterInputStream(
                    new ByteArrayInputStream(payload)));
            } else {
                return null;
            }
            try {
                NBTTagCompound root = CompressedStreamTools.read(in);
                return root == null ? null : root.getCompoundTag("Level");
            } finally {
                closeQuietly(in);
            }
        } catch (Throwable t) {
            return null;                                 // torn or half-written
        }
    }

    void close() {
        for (Handle h : open.values()) closeQuietly(h.file);
        open.clear();
    }

    private static void closeQuietly(java.io.Closeable c) {
        if (c == null) return;
        try {
            c.close();
        } catch (Throwable ignored) {
            // nothing useful to do
        }
    }
}
