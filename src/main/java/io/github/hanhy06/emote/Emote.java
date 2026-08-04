package io.github.hanhy06.emote;

import io.github.hanhy06.emote.animation.EmoteAnimationDirectoryLoader;
import io.github.hanhy06.emote.animation.EmoteAnimationServerValidator;
import io.github.hanhy06.emote.api.EmoteApiEvents;
import io.github.hanhy06.emote.api.EmoteApiImpl;
import io.github.hanhy06.emote.command.RootCommand;
import io.github.hanhy06.emote.config.ConfigManager;
import io.github.hanhy06.emote.dialog.DialogManager;
import io.github.hanhy06.emote.emote.EmoteRegistry;
import io.github.hanhy06.emote.emote.PlayService;
import io.github.hanhy06.emote.emote.PlayableEmoteService;
import io.github.hanhy06.emote.network.EmoteNetworking;
import io.github.hanhy06.emote.network.PlaybackStateService;
import io.github.hanhy06.emote.network.WheelSyncService;
import io.github.hanhy06.emote.permission.PermissionService;
import io.github.hanhy06.emote.playback.PlaybackManager;
import io.github.hanhy06.emote.server.EmoteLifecycle;
import io.github.hanhy06.emote.server.EmoteReloadService;
import io.github.hanhy06.emote.server.IdleEmoteService;
import io.github.hanhy06.emote.skin.PlayerSkinManager;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Emote implements ModInitializer {
    public static Emote INSTANCE;
    public static final String MOD_ID = "emote";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    public static MinecraftServer SERVER;

    public Emote() {
        INSTANCE = this;
    }

    @Override
    public void onInitialize() {
        initializeInstances();

        ConfigManager.INSTANCE.addEmoteAccessListener(PermissionService.INSTANCE);
        ConfigManager.INSTANCE.addEmoteAccessListener(IdleEmoteService.INSTANCE);
        ConfigManager.INSTANCE.addListener(PlayerSkinManager.INSTANCE);

        PlaybackManager.INSTANCE.addStateListener(PlaybackStateService.INSTANCE);
        PlaybackManager.INSTANCE.addStateListener(EmoteApiEvents.INSTANCE);
        PlaybackManager.INSTANCE.registerVisibilityService();

        EmoteNetworking.INSTANCE.register();
        EmoteLifecycle.INSTANCE.register();
        RootCommand.INSTANCE.register();

        LOGGER.info("{} ready", MOD_ID);
    }

    private void initializeInstances() {
        new ConfigManager(FabricLoader.getInstance().getConfigDir());
        new PlayerSkinManager();
        new EmoteRegistry();
        new PermissionService();
        new PlayableEmoteService(EmoteRegistry.INSTANCE, PermissionService.INSTANCE);
        new PlaybackManager(PlayerSkinManager.INSTANCE);
        new PlaybackStateService();
        new EmoteApiEvents();
        new DialogManager(
            ConfigManager.INSTANCE,
            EmoteRegistry.INSTANCE,
            PlayableEmoteService.INSTANCE,
            PlaybackManager.INSTANCE
        );
        new PlayService(
            EmoteRegistry.INSTANCE,
            PermissionService.INSTANCE,
            PlaybackManager.INSTANCE,
            EmoteApiEvents.INSTANCE
        );
        new IdleEmoteService(PermissionService.INSTANCE, PlayService.INSTANCE, PlaybackManager.INSTANCE);
        new WheelSyncService(PlayableEmoteService.INSTANCE);
        new EmoteApiImpl(
            EmoteRegistry.INSTANCE,
            PlayService.INSTANCE,
            PlaybackManager.INSTANCE,
            EmoteApiEvents.INSTANCE,
            WheelSyncService.INSTANCE,
            new EmoteAnimationServerValidator()
        );
        new EmoteReloadService(
            ConfigManager.INSTANCE,
            EmoteRegistry.INSTANCE,
            new EmoteAnimationDirectoryLoader(),
            PlaybackManager.INSTANCE,
            WheelSyncService.INSTANCE
        );
        new EmoteNetworking();
        new EmoteLifecycle(
            PlayerSkinManager.INSTANCE,
            EmoteRegistry.INSTANCE,
            PlaybackManager.INSTANCE,
            EmoteReloadService.INSTANCE,
            WheelSyncService.INSTANCE,
            IdleEmoteService.INSTANCE
        );
        new RootCommand(
            EmoteRegistry.INSTANCE,
            PlaybackManager.INSTANCE,
            DialogManager.INSTANCE,
            PlayableEmoteService.INSTANCE,
            PlayService.INSTANCE,
            PermissionService.INSTANCE,
            EmoteReloadService.INSTANCE,
            ConfigManager.INSTANCE
        );
    }
}
