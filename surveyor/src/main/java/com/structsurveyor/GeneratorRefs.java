package com.structsurveyor;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.World;
import net.minecraft.world.WorldProvider;
import net.minecraft.world.chunk.IChunkProvider;
import net.minecraft.world.gen.MapGenBase;
import net.minecraft.world.gen.structure.MapGenStructure;

/**
 * Reflective access to the pieces of MapGenStructure we need.
 *
 * Everything here is resolved by *signature*, never by name. Minecraft members
 * carry MCP names in a dev workspace and SRG names at runtime, so any
 * name-based lookup would work in exactly one of the two. Signature lookup
 * works in both and needs no access transformer.
 */
public final class GeneratorRefs {

    private GeneratorRefs() {}

    /** MapGenStructure.canSpawnStructureAtCoords(int, int) -> boolean */
    private static Method CAN_SPAWN;
    /** MapGenBase.rand */
    private static Field RAND;
    /** MapGenBase.worldObj */
    private static Field WORLD;
    /** MapGenStructure.func_143025_a() -> String, the save tag ("Village", ...) */
    private static Method TAG_NAME;
    /** MapGenStructure.structureMap: every structure start the world has recorded. */
    private static Field STRUCTURE_MAP;
    /** MapGenStructure.func_143027_a(World): loads the saved structure data. */
    private static Method LOAD_DATA;

    private static boolean resolved;
    private static String resolveError;

    public static synchronized boolean resolve() {
        if (resolved) return resolveError == null;
        resolved = true;
        try {
            // MapGenStructure declares exactly one (int,int)->boolean method:
            // canSpawnStructureAtCoords. getStructureStart returns StructureStart,
            // so there is no ambiguity to worry about.
            CAN_SPAWN = uniqueMethod(MapGenStructure.class, boolean.class, int.class, int.class);
            // ...and exactly one no-arg method returning String: the save tag.
            TAG_NAME = uniqueMethod(MapGenStructure.class, String.class);
            RAND = uniqueField(MapGenBase.class, Random.class);
            WORLD = uniqueField(MapGenBase.class, World.class);

            // Ground truth for self-verification. structureMap is the only Map
            // on MapGenStructure, and the data loader is its only void method
            // taking a single World.
            STRUCTURE_MAP = uniqueField(MapGenStructure.class, Map.class);
            LOAD_DATA = uniqueMethod(MapGenStructure.class, void.class, World.class);

            CAN_SPAWN.setAccessible(true);
            TAG_NAME.setAccessible(true);
            RAND.setAccessible(true);
            WORLD.setAccessible(true);
            STRUCTURE_MAP.setAccessible(true);
            LOAD_DATA.setAccessible(true);
        } catch (Throwable t) {
            resolveError = t.toString();
            StructureSurveyor.LOG.error("Could not resolve MapGenStructure internals", t);
            return false;
        }
        return true;
    }

    public static String resolveError() {
        return resolveError;
    }

    private static Method uniqueMethod(Class<?> owner, Class<?> ret, Class<?>... params) {
        Method found = null;
        for (Method m : owner.getDeclaredMethods()) {
            if (m.getReturnType() != ret) continue;
            if (!java.util.Arrays.equals(m.getParameterTypes(), params)) continue;
            if (found != null) {
                throw new IllegalStateException(
                    "ambiguous method on " + owner.getName() + ": " + found + " and " + m);
            }
            found = m;
        }
        if (found == null) {
            throw new IllegalStateException("no method on " + owner.getName()
                + " returning " + ret.getSimpleName() + " with " + params.length + " int params");
        }
        return found;
    }

    private static Field uniqueField(Class<?> owner, Class<?> type) {
        Field found = null;
        for (Field f : owner.getDeclaredFields()) {
            if (f.getType() != type || Modifier.isStatic(f.getModifiers())) continue;
            if (found != null) {
                throw new IllegalStateException(
                    "ambiguous field on " + owner.getName() + ": " + found + " and " + f);
            }
            found = f;
        }
        if (found == null) {
            throw new IllegalStateException(
                "no " + type.getSimpleName() + " field on " + owner.getName());
        }
        return found;
    }

    // ---- accessors -------------------------------------------------------

    public static boolean canSpawnAt(MapGenStructure gen, int chunkX, int chunkZ) throws Exception {
        return (Boolean) CAN_SPAWN.invoke(gen, chunkX, chunkZ);
    }

    public static Random randOf(MapGenStructure gen) throws Exception {
        return (Random) RAND.get(gen);
    }

    public static void bindWorld(MapGenStructure gen, World world) throws Exception {
        WORLD.set(gen, world);
    }

    /**
     * Whether this generator has actually run in this dimension.
     *
     * MapGenBase.func_151539_a assigns worldObj every time it generates, so a
     * generator that still has null has never been invoked. That matters because
     * providers inherit generator fields they never use - ChunkProviderMoon
     * extends ChunkProviderGenerate, so the Moon carries vanilla village,
     * mineshaft, stronghold and temple generators that never place anything.
     * Sweeping those would predict structures that cannot exist there.
     *
     * Must be read before bindWorld(), which would set the field and destroy the
     * signal.
     */
    public static boolean hasRun(MapGenStructure gen) {
        try {
            return WORLD.get(gen) != null;
        } catch (Throwable t) {
            return true;            // unknown; assume active rather than hide it
        }
    }

    /** Pack a chunk coordinate the way ChunkCoordIntPair.chunkXZ2Int does. */
    public static long chunkKey(int chunkX, int chunkZ) {
        return (long) chunkX & 0xFFFFFFFFL | ((long) chunkZ & 0xFFFFFFFFL) << 32;
    }

    /**
     * Every chunk this generator has actually recorded a structure in, read from
     * the world's own saved data.
     *
     * This is ground truth: the game wrote these. A correct placement replay has
     * to reproduce all of them, so comparing against this catches a wrong replay
     * that would otherwise produce entirely plausible-looking output.
     */
    public static List<int[]> knownStructureChunks(MapGenStructure gen, World world) throws Exception {
        LOAD_DATA.invoke(gen, world);           // populates structureMap from disk
        Map<?, ?> map = (Map<?, ?>) STRUCTURE_MAP.get(gen);
        List<int[]> out = new ArrayList<int[]>();
        if (map == null) return out;
        for (Object key : map.keySet()) {
            if (!(key instanceof Long)) continue;
            long k = (Long) key;
            out.add(new int[] { (int) (k & 0xFFFFFFFFL), (int) (k >>> 32) });
        }
        return out;
    }

    /** Save tag such as "Village" / "Mineshaft"; falls back to the class name. */
    public static String tagOf(MapGenStructure gen) {
        try {
            Object s = TAG_NAME.invoke(gen);
            if (s instanceof String && !((String) s).isEmpty()) return (String) s;
        } catch (Throwable ignored) {
            // Some modded generators throw here before a world is bound.
        }
        return gen.getClass().getSimpleName();
    }

    // ---- discovery -------------------------------------------------------

    /**
     * Walk a chunk provider's fields to find every MapGenStructure it owns.
     *
     * Chunk providers keep their generators in private fields; some mods keep
     * them in arrays or collections. We recurse a couple of levels so wrapper
     * providers (which are common once mods start chaining generation) still
     * yield their inner generators.
     */
    public static List<MapGenStructure> discover(IChunkProvider provider) {
        List<MapGenStructure> out = new ArrayList<MapGenStructure>();
        Map<Object, Boolean> seen = new IdentityHashMap<Object, Boolean>();
        walk(provider, out, seen, 0);
        return out;
    }

    private static void walk(Object node, List<MapGenStructure> out,
                             Map<Object, Boolean> seen, int depth) {
        if (node == null || depth > 3) return;
        if (seen.put(node, Boolean.TRUE) != null) return;

        if (node instanceof MapGenStructure) {
            out.add((MapGenStructure) node);
            return;                  // no need to descend into a generator
        }

        // Never leave this dimension. A chunk provider holds a World, which
        // reaches MinecraftServer, which holds every other WorldServer - so
        // without this we would walk into other dimensions' generators and
        // report them as belonging to this one, with coordinates that mean
        // nothing here.
        if (node instanceof World || node instanceof WorldProvider
            || node instanceof MinecraftServer) {
            return;
        }

        if (node instanceof Collection) {
            for (Object o : (Collection<?>) node) walk(o, out, seen, depth + 1);
            return;
        }
        if (node instanceof Map) {
            for (Object o : ((Map<?, ?>) node).values()) walk(o, out, seen, depth + 1);
            return;
        }
        if (node.getClass().isArray()) {
            if (node.getClass().getComponentType().isPrimitive()) return;
            int n = Array.getLength(node);
            for (int i = 0; i < n; i++) walk(Array.get(node, i), out, seen, depth + 1);
            return;
        }

        // Only descend through Minecraft/mod objects, never into the JDK.
        String cls = node.getClass().getName();
        if (cls.startsWith("java.") || cls.startsWith("javax.") || cls.startsWith("sun.")) return;

        for (Class<?> c = node.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                if (f.getType().isPrimitive()) continue;
                try {
                    f.setAccessible(true);
                    walk(f.get(node), out, seen, depth + 1);
                } catch (Throwable ignored) {
                    // Inaccessible field; nothing we can do, keep scanning.
                }
            }
        }
    }
}
