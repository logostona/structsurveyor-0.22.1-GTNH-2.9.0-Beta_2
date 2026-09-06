package com.structsurveyor;

import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;

import cpw.mods.fml.client.registry.ClientRegistry;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.InputEvent;

import org.lwjgl.input.Keyboard;

import com.structsurveyor.map.GuiSurveyMap;
import com.structsurveyor.map.MapCache;
import com.structsurveyor.map.MapTiles;

public class ClientProxy extends CommonProxy {

    /**
     * Default N: it is unbound in GTNH, unlike M and J which JourneyMap claims.
     * Registering it as a KeyBinding means it shows up in Options > Controls, so
     * it can be rebound like any other key.
     */
    public static KeyBinding openMap;

    @Override
    public void preInit() {
        if (!com.structsurveyor.Config.enableMap) {
            FMLCommonHandler.instance().bus().register(this);   // still free memory
            return;
        }
        openMap = new KeyBinding("key.structsurveyor.map", Keyboard.KEY_N, "key.categories.misc");
        ClientRegistry.registerKeyBinding(openMap);
        FMLCommonHandler.instance().bus().register(this);
    }

    /**
     * Drop cached imagery when leaving a world, so a different save cannot reuse
     * it and the GL textures are released.
     *
     * Deliberately not WorldEvent.Unload: that fires on every dimension change,
     * which would throw the cache away every time you step through a portal.
     */
    @SubscribeEvent
    public void onDisconnect(cpw.mods.fml.common.network.FMLNetworkEvent
                                 .ClientDisconnectionFromServerEvent event) {
        com.structsurveyor.map.MapCache.requestInvalidate();
    }

    /**
     * Deferred cache invalidation on the thread that owns the GL context, and
     * the live terrain harvest.
     */
    @SubscribeEvent
    public void onClientTick(cpw.mods.fml.common.gameevent.TickEvent.ClientTickEvent event) {
        if (event.phase != cpw.mods.fml.common.gameevent.TickEvent.Phase.END) return;
        MapCache.tick();
        harvest();
    }

    /** Ticks between harvest passes; terrain does not change fast enough to want more. */
    private static final int HARVEST_INTERVAL = 20;
    private int harvestCountdown;

    /**
     * Copy the chunks around the player out of the loaded world.
     *
     * On a server someone else runs there are no region files, so the only
     * terrain that will ever exist client-side is what gets taken from chunks
     * while they are loaded. Doing that only while the map is open would map the
     * places you stopped to look at it and nothing in between - which is not a
     * map so much as a set of postcards.
     *
     * Skipped in singleplayer, where the save on disk is complete and
     * authoritative: nothing to gain, and it would build map state for a player
     * who never opens the map.
     */
    private void harvest() {
        if (!com.structsurveyor.Config.enableMap
            || !com.structsurveyor.Config.harvestWhileWalking) return;
        if (--harvestCountdown > 0) return;
        harvestCountdown = HARVEST_INTERVAL;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.theWorld == null || mc.thePlayer == null || mc.isSingleplayer()) return;
        if (mc.currentScreen instanceof GuiSurveyMap) return;   // it harvests already
        try {
            MapTiles tiles = MapCache.tiles(mc.thePlayer.dimension);
            if (!tiles.remote()) return;
            tiles.patchFromLiveWorld(mc.theWorld,
                ((int) Math.floor(mc.thePlayer.posX)) >> 4,
                ((int) Math.floor(mc.thePlayer.posZ)) >> 4,
                com.structsurveyor.Config.harvestRadiusChunks, MapTiles.frameBudget());
        } catch (Throwable t) {
            com.structsurveyor.StructureSurveyor.LOG.debug("live harvest failed", t);
        }
    }

    @SubscribeEvent
    public void onKeyInput(InputEvent.KeyInputEvent event) {
        if (openMap == null || !openMap.isPressed()) return;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.currentScreen == null && mc.theWorld != null) {
            mc.displayGuiScreen(new GuiSurveyMap());
        }
    }
}
