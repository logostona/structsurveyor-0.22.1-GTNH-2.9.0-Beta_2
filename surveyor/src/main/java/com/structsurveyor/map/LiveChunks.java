package com.structsurveyor.map;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.NibbleArray;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;

/**
 * Presents a loaded chunk in the shape the region decoder produces.
 *
 * On a server you do not own there are no region files: the only world data
 * that exists client-side is whatever the server has sent, held in memory by
 * ChunkProviderClient. Rather than write a second detector against live Chunk
 * objects - two implementations of the same rules, drifting apart - this
 * rebuilds the small part of the on-disk chunk format that SignatureScan
 * actually reads, so exactly the same code runs on both.
 *
 * Only what the scan looks at is written: block ids (Blocks + Add), tile
 * entities, and biomes. Metadata, lighting, entities and the heightmap are all
 * skipped, because nothing reads them here and each one costs an allocation per
 * chunk.
 */
public final class LiveChunks {

    private LiveChunks() {}

    /**
     * A minimal "Level" compound for a loaded chunk, or null if it holds
     * nothing worth scanning.
     *
     * The client receives block ids for every chunk in view distance, so this is
     * as complete as the server chose to make it - which for terrain and
     * structures is complete. Tile entities are the exception worth knowing
     * about: a spawner is sent because the client has to render the spinning
     * mob inside it, but a server that strips tile entity data will leave the
     * spawner signatures blind.
     */
    public static NBTTagCompound levelOf(Chunk chunk) {
        if (chunk == null) return null;
        NBTTagCompound level = new NBTTagCompound();

        NBTTagList sections = new NBTTagList();
        ExtendedBlockStorage[] storage = chunk.getBlockStorageArray();
        if (storage != null) {
            for (int i = 0; i < storage.length; i++) {
                ExtendedBlockStorage s = storage[i];
                if (s == null || s.isEmpty()) continue;
                byte[] lsb = s.getBlockLSBArray();
                if (lsb == null) continue;
                NBTTagCompound sec = new NBTTagCompound();
                sec.setByte("Y", (byte) (s.getYLocation() >> 4));
                sec.setByteArray("Blocks", lsb);
                NibbleArray msb = s.getBlockMSBArray();
                if (msb != null && msb.data != null) sec.setByteArray("Add", msb.data);
                sections.appendTag(sec);
            }
        }
        if (sections.tagCount() == 0) return null;          // nothing but air
        level.setTag("Sections", sections);

        byte[] biomes = chunk.getBiomeArray();
        if (biomes != null && biomes.length >= 256) level.setByteArray("Biomes", biomes);

        NBTTagList tiles = new NBTTagList();
        try {
            for (Object o : chunk.chunkTileEntityMap.values()) {
                if (!(o instanceof TileEntity)) continue;
                try {
                    NBTTagCompound te = new NBTTagCompound();
                    ((TileEntity) o).writeToNBT(te);
                    tiles.appendTag(te);
                } catch (Throwable t) {
                    // A modded tile entity that cannot serialise client-side.
                    // One bad block must not cost the whole chunk.
                }
            }
        } catch (Throwable t) {
            // Concurrent modification while the world ticks; the chunk will be
            // offered again on a later pass.
        }
        if (tiles.tagCount() > 0) level.setTag("TileEntities", tiles);

        return level;
    }
}
