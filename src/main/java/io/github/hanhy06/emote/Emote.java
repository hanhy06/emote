package io.github.hanhy06.emote;

import io.github.hanhy06.emote.animation.AnimationDirectoryLoader;
import io.github.hanhy06.emote.animation.AnimationServerPreparer;
import io.github.hanhy06.emote.application.ApiEventDispatcher;
import io.github.hanhy06.emote.application.EmoteApiImpl;
import io.github.hanhy06.emote.command.DialogManager;
import io.github.hanhy06.emote.command.RootCommand;
import io.github.hanhy06.emote.config.ConfigManager;
import io.github.hanhy06.emote.content.EmoteCatalog;
import io.github.hanhy06.emote.application.EmotePlayService;
import io.github.hanhy06.emote.application.EmoteQueryService;
import io.github.hanhy06.emote.network.PayloadRegistry;
import io.github.hanhy06.emote.network.PlaybackStateService;
import io.github.hanhy06.emote.network.WheelSyncService;
import io.github.hanhy06.emote.permission.PermissionService;
import io.github.hanhy06.emote.playback.PlaybackManager;
import io.github.hanhy06.emote.server.IdlePlaybackService;
import io.github.hanhy06.emote.server.ReloadService;
import io.github.hanhy06.emote.server.ServerLifecycle;
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
        EmoteCatalog emoteRegistry = new EmoteCatalog();
        PermissionService permissionService = new PermissionService();

        PlayerSkinManager playerSkinManager = new PlayerSkinManager();
        PlaybackManager playbackManager = new PlaybackManager(playerSkinManager);
        PlaybackStateService playbackStateService = new PlaybackStateService();
        ApiEventDispatcher apiEvents = new ApiEventDispatcher();

        EmoteQueryService playableEmoteService = new EmoteQueryService(emoteRegistry, permissionService);
        EmotePlayService playService = new EmotePlayService(
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

        new EmoteApiImpl(
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

        ServerLifecycle serverLifecycle = new ServerLifecycle(
            playerSkinManager,
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
        configManager.addListener(playerSkinManager);
        configManager.addListener(playbackManager);

        playbackManager.addStateListener(playbackStateService);
        playbackManager.addStateListener(apiEvents);
        playbackManager.registerVisibilityService();

        PayloadRegistry.register();
        serverLifecycle.register();
        rootCommand.register();

        LOGGER.info("{} ready", MOD_ID);
    }
}
