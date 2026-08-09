package io.github.hanhy06.emote;

import io.github.hanhy06.emote.animation.AnimationDirectoryLoader;
import io.github.hanhy06.emote.animation.AnimationServerPreparer;
import io.github.hanhy06.emote.api.ApiEvents;
import io.github.hanhy06.emote.api.ApiImpl;
import io.github.hanhy06.emote.command.RootCommand;
import io.github.hanhy06.emote.config.ConfigManager;
import io.github.hanhy06.emote.dialog.DialogManager;
import io.github.hanhy06.emote.emote.EmoteRegistry;
import io.github.hanhy06.emote.emote.PlayService;
import io.github.hanhy06.emote.emote.PlayableEmoteService;
import io.github.hanhy06.emote.network.PayloadRegistry;
import io.github.hanhy06.emote.network.PlaybackStateService;
import io.github.hanhy06.emote.network.WheelSyncService;
import io.github.hanhy06.emote.permission.PermissionService;
import io.github.hanhy06.emote.playback.PlaybackManager;
import io.github.hanhy06.emote.server.ServerLifecycle;
import io.github.hanhy06.emote.server.ReloadService;
import io.github.hanhy06.emote.server.IdlePlaybackService;
import io.github.hanhy06.emote.skin.PlayerSkinManager;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Emote implements ModInitializer {
    public static final String MOD_ID = "emote";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    public static MinecraftServer SERVER;

    @Override
    public void onInitialize() {
        ConfigManager configManager = new ConfigManager(FabricLoader.getInstance().getConfigDir());
        EmoteRegistry emoteRegistry = new EmoteRegistry();
        PermissionService permissionService = new PermissionService();

        PlayerSkinManager skinManager = new PlayerSkinManager();
        PlaybackManager playbackManager = new PlaybackManager(skinManager);
        PlaybackStateService playbackStateService = new PlaybackStateService();
        ApiEvents apiEvents = new ApiEvents();

        PlayableEmoteService playableEmoteService = new PlayableEmoteService(emoteRegistry, permissionService);
        PlayService playService = new PlayService(
            emoteRegistry,
            permissionService,
            playbackManager,
            apiEvents
        );
        IdlePlaybackService idlePlaybackService = new IdlePlaybackService(permissionService, playService, playbackManager);

        DialogManager dialogManager = new DialogManager(
            configManager,
            emoteRegistry,
            playableEmoteService,
            playbackManager
        );
        WheelSyncService wheelSyncService = new WheelSyncService(playableEmoteService);

        new ApiImpl(
            emoteRegistry,
            playService,
            playbackManager,
            apiEvents,
            wheelSyncService,
            new AnimationServerPreparer()
        );
        ReloadService reloadService = new ReloadService(
            configManager,
            emoteRegistry,
            new AnimationDirectoryLoader(),
            playbackManager,
            wheelSyncService
        );

        PayloadRegistry networking = new PayloadRegistry();
        ServerLifecycle lifecycle = new ServerLifecycle(
            skinManager,
            emoteRegistry,
            playbackManager,
            reloadService,
            wheelSyncService,
            idlePlaybackService
        );
        RootCommand rootCommand = new RootCommand(
            emoteRegistry,
            playbackManager,
            dialogManager,
            playableEmoteService,
            playService,
            permissionService,
            reloadService,
            configManager
        );

        configManager.addAccessConfigListener(permissionService);
        configManager.addAccessConfigListener(idlePlaybackService);
        configManager.addListener(skinManager);
        configManager.addListener(playbackManager);

        playbackManager.addStateListener(playbackStateService);
        playbackManager.addStateListener(apiEvents);
        playbackManager.registerVisibilityService();

        networking.register();
        lifecycle.register();
        rootCommand.register();

        LOGGER.info("{} ready", MOD_ID);
    }
}
