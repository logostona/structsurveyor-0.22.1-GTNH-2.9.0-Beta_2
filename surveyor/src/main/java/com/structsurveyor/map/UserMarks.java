package com.structsurveyor.map;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

import com.structsurveyor.StructureSurveyor;

/**
 * Places the player has marked by hand - "I have been here".
 *
 * Detection can only ever say a structure exists, never whether it has been
 * looted. Kept as plain text so it can be edited or backed up without this mod.
 */
public class UserMarks {

    private final List<int[]> marks = new ArrayList<int[]>();
    private final File file;
    private boolean dirty;

    public UserMarks(File file) {
        this.file = file;
        load();
    }

    public List<int[]> all() {
        return marks;
    }

    /** Nearest mark within radius blocks, or null. */
    public int[] near(int x, int z, int radius) {
        int[] best = null;
        long bestD = (long) radius * radius;
        for (int[] m : marks) {
            long dx = m[0] - x, dz = m[1] - z;
            long d = dx * dx + dz * dz;
            if (d <= bestD) { bestD = d; best = m; }
        }
        return best;
    }

    /** Add a mark, or remove the one already there. Returns true if added. */
    public boolean toggle(int x, int z, int radius) {
        int[] existing = near(x, z, radius);
        if (existing != null) {
            marks.remove(existing);
            dirty = true;
            return false;
        }
        marks.add(new int[] { x, z });
        dirty = true;
        return true;
    }

    private void load() {
        if (!file.isFile()) return;
        BufferedReader in = null;
        try {
            in = new BufferedReader(new FileReader(file));
            String line;
            while ((line = in.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                int space = line.indexOf(' ');
                if (space < 0) continue;
                marks.add(new int[] { Integer.parseInt(line.substring(0, space).trim()),
                                      Integer.parseInt(line.substring(space + 1).trim()) });
            }
        } catch (Throwable t) {
            StructureSurveyor.LOG.debug("could not read visited marks");
        } finally {
            close(in);
        }
    }

    public void save() {
        if (!dirty) return;
        File parent = file.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) return;
        PrintWriter out = null;
        try {
            out = new PrintWriter(new FileWriter(file));
            out.println("# Structure Surveyor visited marks: one \"x z\" per line");
            for (int[] m : marks) out.println(m[0] + " " + m[1]);
            dirty = false;
        } catch (Throwable t) {
            StructureSurveyor.LOG.debug("could not write visited marks");
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
