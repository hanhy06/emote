package io.github.hanhy06.emote;

import io.github.hanhy06.emote.animation.EmoteAnimationDirectoryLoader;
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

    private final DialogManager dialogManager = new DialogManager(
        this.configManager,
        this.emoteRegistry,
        this.playableEmoteService,
        this.playbackManager
    );
    private final PlayService playService = new PlayService(
        this.emoteRegistry,
        this.permissionService,
        this.playbackManager
    );
    private final IdleEmoteService idleEmoteService = new IdleEmoteService(
        this.permissionService,
        this.playService,
        this.playbackManager
    );
    private final WheelSyncService wheelSyncService = new WheelSyncService(this.playableEmoteService);
    private final EmoteReloadService reloadService = new EmoteReloadService(
        this.configManager,
        this.emoteRegistry,
        new EmoteAnimationDirectoryLoader(),
        this.playbackManager,
        this.wheelSyncService
    );

    private final EmoteNetworking networking = new EmoteNetworking();
    private final EmoteLifecycle lifecycle = new EmoteLifecycle(
        this.skinManager,
        this.playbackManager,
        this.reloadService,
        this.wheelSyncService,
        this.idleEmoteService
    );
    private final RootCommand rootCommand = new RootCommand(
        this.emoteRegistry,
        this.playbackManager,
        this.dialogManager,
        this.playableEmoteService,
        this.playService,
        this.permissionService,
        this.reloadService,
        this.configManager
    );

    @Override
    public void onInitialize() {
        this.configManager.addEmoteAccessListener(this.permissionService);
        this.configManager.addListener(this.skinManager);

        this.playbackManager.addStateListener(this.playbackStateService);
        this.playbackManager.registerVisibilityService();

        this.networking.register();
        this.lifecycle.register();
        this.rootCommand.register();

        LOGGER.info("{} ready", MOD_ID);
    }
}
