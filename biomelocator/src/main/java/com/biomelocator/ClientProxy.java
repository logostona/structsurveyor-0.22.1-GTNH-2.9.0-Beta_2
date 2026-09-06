package com.biomelocator;

import net.minecraftforge.client.ClientCommandHandler;

/**
 * Registers the command client-side.
 *
 * Kept in its own class so a dedicated server never loads it: 1.7.10 has no
 * clientSideOnly flag on @Mod, and ClientCommandHandler does not exist outside
 * a client, so merely referencing it from shared code would be a crash.
 */
public class ClientProxy extends CommonProxy {

    @Override
    public void init() {
        // Not the server's command handler: this must work while connected to a
        // server that has never heard of the mod.
        ClientCommandHandler.instance.registerCommand(new CommandBiome());
    }
}
