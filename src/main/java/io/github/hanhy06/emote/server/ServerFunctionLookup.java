package io.github.hanhy06.emote.server;

import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;

public final class ServerFunctionLookup {
    private ServerFunctionLookup() {
    }

    public static boolean isLoaded(MinecraftServer server, String functionId) {
        Identifier identifier = Identifier.tryParse(functionId);
        return identifier != null && server.getFunctions().get(identifier).isPresent();
    }
}
