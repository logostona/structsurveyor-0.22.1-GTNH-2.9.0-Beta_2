package com.biomelocator;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

/**
 * Biome readings taken from a world, and the file they live in.
 *
 * These are ground truth: each one is a biome id the server itself put in a
 * chunk it sent us, at a coordinate we know. Nothing here is predicted. That is
 * the whole point - a replay is only worth something once it has reproduced
 * numbers it did not get to choose.
 *
 * Plain CSV rather than NBT or a binary blob, because the first thing anyone
 * will want to do with a failed verification is open the file and look at it.
 */
public final class BiomeSamples {

    /** One reading: a world coordinate and the biome id actually stored there. */
    public static final class Sample {
        public final int x, z, biomeId;

        Sample(int x, int z, int biomeId) {
            this.x = x;
            this.z = z;
            this.biomeId = biomeId;
        }
    }

    public final List<Sample> samples = new ArrayList<Sample>();
    /** Free-text note about where the readings came from, kept in the header. */
    public String source = "unknown";
    public int dimension;

    public int size() {
        return samples.size();
    }

    public void add(int x, int z, int biomeId) {
        samples.add(new Sample(x, z, biomeId));
    }

    /**
     * Merge into an existing file rather than replacing it.
     *
     * Sampling is something you do in several sittings - walk a bit, sample,
     * walk further - and a run that silently discarded the previous walk would
     * make a weak test look like a thorough one.
     */
    public void save(File file) throws Exception {
        BiomeSamples existing = new BiomeSamples();
        if (file.isFile()) existing.load(file);

        java.util.Map<Long, Sample> merged = new java.util.LinkedHashMap<Long, Sample>();
        for (Sample s : existing.samples) merged.put(pack(s.x, s.z), s);
        for (Sample s : samples) merged.put(pack(s.x, s.z), s);

        File dir = file.getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs()) {
            throw new Exception("could not create " + dir);
        }
        PrintWriter out = new PrintWriter(new java.io.OutputStreamWriter(
            new FileOutputStream(file), "UTF-8"));
        try {
            out.println("# Biome Locator ground truth - biome ids read from real chunk data");
            out.println("# source=" + source);
            out.println("# dimension=" + dimension);
            out.println("x,z,biome");
            for (Sample s : merged.values()) {
                out.println(s.x + "," + s.z + "," + s.biomeId);
            }
        } finally {
            out.close();
        }
        samples.clear();
        samples.addAll(merged.values());
    }

    public void load(File file) throws Exception {
        samples.clear();
        BufferedReader in = new BufferedReader(new InputStreamReader(
            new FileInputStream(file), "UTF-8"));
        try {
            String line;
            while ((line = in.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                if (line.startsWith("#")) {
                    if (line.startsWith("# source=")) source = line.substring(9);
                    if (line.startsWith("# dimension=")) {
                        try {
                            dimension = Integer.parseInt(line.substring(12).trim());
                        } catch (NumberFormatException ignored) {
                            // leave the default
                        }
                    }
                    continue;
                }
                if (line.startsWith("x,")) continue;              // header row
                String[] parts = line.split(",");
                if (parts.length < 3) continue;
                try {
                    samples.add(new Sample(Integer.parseInt(parts[0].trim()),
                                           Integer.parseInt(parts[1].trim()),
                                           Integer.parseInt(parts[2].trim())));
                } catch (NumberFormatException ignored) {
                    // a line someone edited by hand; skip it rather than refuse
                }
            }
        } finally {
            in.close();
        }
    }

    private static long pack(int x, int z) {
        return (long) x & 0xFFFFFFFFL | ((long) z & 0xFFFFFFFFL) << 32;
    }
}
