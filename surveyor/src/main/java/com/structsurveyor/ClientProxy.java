package com.structsurveyor;

import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;

import cpw.mods.fml.client.registry.ClientRegistry;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.InputEvent;

import org.lwjgl.input.Keyboard;

import com.structsurveyor.map.GuiSurveyMap;

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

    /** Performs deferred cache invalidation on the thread that owns the GL context. */
    @SubscribeEvent
    public void onClientTick(cpw.mods.fml.common.gameevent.TickEvent.ClientTickEvent event) {
        if (event.phase == cpw.mods.fml.common.gameevent.TickEvent.Phase.END) {
            com.structsurveyor.map.MapCache.tick();
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
