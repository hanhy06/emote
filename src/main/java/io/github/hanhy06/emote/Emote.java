package io.github.hanhy06.emote;

import io.github.hanhy06.emote.bdengine.BDEngineDatapackProcessor;
import io.github.hanhy06.emote.command.RootCommand;
import io.github.hanhy06.emote.config.ConfigManager;
import io.github.hanhy06.emote.dialog.DialogManager;
import io.github.hanhy06.emote.emote.EmoteRegistry;
import io.github.hanhy06.emote.emote.PlayableEmoteService;
import io.github.hanhy06.emote.network.EmoteNetworking;
import io.github.hanhy06.emote.network.service.PlayService;
import io.github.hanhy06.emote.network.service.PlaybackStateService;
import io.github.hanhy06.emote.network.service.PlaybackStateSyncListener;
import io.github.hanhy06.emote.network.service.WheelSyncService;
import io.github.hanhy06.emote.permission.PermissionService;
import io.github.hanhy06.emote.playback.PlaybackManager;
import io.github.hanhy06.emote.server.EmoteLifecycle;
import io.github.hanhy06.emote.server.EmoteReloadService;
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

    private final ConfigManager configManager = new ConfigManager(FabricLoader.getInstance().getConfigDir());
    private final PlayerSkinManager skinManager = new PlayerSkinManager();

    private final EmoteRegistry emoteRegistry = new EmoteRegistry();
    private final PermissionService permissionService = new PermissionService();
    private final PlayableEmoteService playableEmoteService = new PlayableEmoteService(
        this.emoteRegistry,
        this.permissionService
    );

    private final PlaybackManager playbackManager = new PlaybackManager(this.skinManager);
    private final PlaybackStateService playbackStateService = new PlaybackStateService();
    private final PlaybackStateSyncListener playbackStateSyncListener = new PlaybackStateSyncListener(this.playbackStateService);

    private final BDEngineDatapackProcessor bdEngineDatapackProcessor = new BDEngineDatapackProcessor(
        this.configManager,
        this.emoteRegistry
    );
    private final DialogManager dialogManager = new DialogManager(
        this.configManager,
        this.emoteRegistry,
        this.playableEmoteService,
        this.playbackManager
    );
    private final PlayService playService = new PlayService(
        this.playableEmoteService,
        this.playbackManager
    );
    private final WheelSyncService wheelSyncService = new WheelSyncService(this.playableEmoteService);
    private final EmoteReloadService reloadService = new EmoteReloadService(
        this.configManager,
        this.emoteRegistry,
        this.bdEngineDatapackProcessor,
        this.playbackManager,
        this.wheelSyncService
    );

    private final EmoteNetworking networking = new EmoteNetworking();
    private final EmoteLifecycle lifecycle = new EmoteLifecycle(
        this.skinManager,
        this.playbackManager,
        this.reloadService,
        this.wheelSyncService
    );
    private final RootCommand rootCommand = new RootCommand(
        this.emoteRegistry,
        this.playbackManager,
        this.dialogManager,
        this.playableEmoteService,
        this.playService,
        this.permissionService,
        this.reloadService
    );

    @Override
    public void onInitialize() {
        registerConfigListeners();
        this.configManager.readConfig();
        this.configManager.readPackConfig();

        this.playbackManager.setStateListener(this.playbackStateSyncListener);

        this.networking.register();
        this.lifecycle.register();
        this.rootCommand.register();

        LOGGER.info("{} ready", MOD_ID);
    }

    private void registerConfigListeners() {
        this.configManager.addListener(this.permissionService);
        this.configManager.addPackListener(this.permissionService);
        this.configManager.addListener(this.skinManager);
    }
}
