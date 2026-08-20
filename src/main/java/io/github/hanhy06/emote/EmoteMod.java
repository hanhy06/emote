package io.github.hanhy06.emote;

import net.fabricmc.api.ModInitializer;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class EmoteMod implements ModInitializer {
    public static final String MOD_ID = "emote";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    public static MinecraftServer SERVER;

    @Override
    public void onInitialize() {
        EmoteBootstrap.initialize();
    }
}
