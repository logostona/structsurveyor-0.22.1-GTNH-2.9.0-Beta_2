package com.structsurveyor;

import net.minecraft.command.ICommandManager;
import net.minecraft.command.ServerCommandManager;

import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.SidedProxy;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.event.FMLServerStartingEvent;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

@Mod(modid = StructureSurveyor.MODID, name = "Structure Surveyor", version = Tags.VERSION, acceptableRemoteVersions = "*")
public class StructureSurveyor {

    public static final String MODID = "structsurveyor";
    public static final Logger LOG = LogManager.getLogger("StructureSurveyor");

    @SidedProxy(clientSide = "com.structsurveyor.ClientProxy",
                serverSide = "com.structsurveyor.CommonProxy")
    public static CommonProxy proxy;

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        Config.load(event.getSuggestedConfigurationFile());
        if (!Config.enabled) {
            LOG.info("Structure Surveyor is disabled in config; registering nothing.");
            return;
        }
        proxy.preInit();
    }

    @Mod.EventHandler
    public void serverStarting(FMLServerStartingEvent event) {
        if (!Config.enabled) return;
        ICommandManager manager = event.getServer().getCommandManager();
        ((ServerCommandManager) manager).registerCommand(new SurveyCommand());
    }
}
