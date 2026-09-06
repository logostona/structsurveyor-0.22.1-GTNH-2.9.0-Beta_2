package com.biomelocator;

import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.SidedProxy;
import cpw.mods.fml.common.event.FMLInitializationEvent;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Finds biomes by replaying world generation, and proves the replay first.
 *
 * Separate from Structure Surveyor on purpose. Everything here is arithmetic on
 * a seed you already know plus the biome data a server has already sent your
 * client - it asks the server for nothing, and it reads nothing the client was
 * not already given. Keeping it in its own jar makes that boundary something you
 * can point at rather than something you have to take on trust.
 *
 * Client-side only. It registers one client command and no world generation, so
 * a server never sees it and never has to have it.
 */
@Mod(modid = BiomeLocator.MODID, name = "Biome Locator", version = Tags.VERSION,
     acceptableRemoteVersions = "*")
public class BiomeLocator {

    public static final String MODID = "biomelocator";
    public static final Logger LOG = LogManager.getLogger("BiomeLocator");

    @SidedProxy(clientSide = "com.biomelocator.ClientProxy",
                serverSide = "com.biomelocator.CommonProxy")
    public static CommonProxy proxy;

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        proxy.init();
    }
}
