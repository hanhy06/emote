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
import io.github.hanhy06.emote.skin.PlayerSkinManager;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Emote implements ModInitializer {
	public static final String MOD_ID = "emote";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
	public static final PlayerSkinManager SKIN_MANAGER = new PlayerSkinManager();
	public static MinecraftServer SERVER;

	private final ConfigManager configManager = new ConfigManager(FabricLoader.getInstance().getConfigDir());

	private final EmoteRegistry emoteRegistry = new EmoteRegistry();
	private final PermissionService permissionService = new PermissionService();
	private final PlayableEmoteService playableEmoteService = new PlayableEmoteService(
		this.emoteRegistry,
		this.permissionService
	);

	private final PlaybackManager playbackManager = new PlaybackManager(SKIN_MANAGER);
	private final PlaybackStateService playbackStateService = new PlaybackStateService();
	private final PlaybackStateSyncListener playbackStateSyncListener = new PlaybackStateSyncListener(this.playbackStateService);

	private final BDEngineDatapackProcessor bdEngineDatapackProcessor = new BDEngineDatapackProcessor(
		this.configManager,
		this.emoteRegistry
	);
	private final DialogManager dialogManager = new DialogManager(
		this.emoteRegistry,
		this.playableEmoteService,
		this.playbackManager
	);
	private final PlayService playService = new PlayService(
		this.playableEmoteService,
		this.playbackManager
	);
	private final WheelSyncService wheelSyncService = new WheelSyncService(this.playableEmoteService);

	private final EmoteNetworking networking = new EmoteNetworking(this.playService, this.wheelSyncService);
	private final EmoteLifecycle lifecycle = new EmoteLifecycle(
		this.configManager,
		SKIN_MANAGER,
		this.playbackManager,
		this.bdEngineDatapackProcessor,
		this.wheelSyncService
	);

	@Override
	public void onInitialize() {
		registerConfigListeners();
		this.configManager.readConfig();
		this.configManager.readIdentifierConfig();

		this.playbackManager.setStateListener(this.playbackStateSyncListener);

		this.networking.register();
		this.lifecycle.register();
		RootCommand.register(
			this.emoteRegistry,
			this.playbackManager,
			this.bdEngineDatapackProcessor,
			this.configManager,
			this.dialogManager,
			this.playableEmoteService,
			this.playService,
			this.permissionService,
			this.wheelSyncService
		);

		LOGGER.info("{} ready", MOD_ID);
	}

	private void registerConfigListeners() {
		this.configManager.addListener(this.permissionService);
		this.configManager.addIdentifierListener(this.permissionService);
		this.configManager.addListener(SKIN_MANAGER);
	}
}
