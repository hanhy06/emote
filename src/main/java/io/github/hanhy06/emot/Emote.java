package io.github.hanhy06.emot;

import io.github.hanhy06.emot.bdengine.BDEngineDatapackProcessor;
import io.github.hanhy06.emot.command.EmoteCommand;
import io.github.hanhy06.emot.config.ConfigManager;
import io.github.hanhy06.emot.dialog.EmoteDialogManager;
import io.github.hanhy06.emot.emote.EmoteRegistry;
import io.github.hanhy06.emot.permission.EmotePermissionService;
import io.github.hanhy06.emot.playback.EmotePlaybackManager;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Emote implements ModInitializer {
	public static final String MOD_ID = "emote";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	private final ConfigManager configManager = new ConfigManager(FabricLoader.getInstance().getConfigDir());
	private final EmoteRegistry emoteRegistry = new EmoteRegistry();
	private final EmotePlaybackManager emotePlaybackManager = new EmotePlaybackManager();
	private final EmotePermissionService emotePermissionService = new EmotePermissionService();
	private final BDEngineDatapackProcessor bdEngineDatapackProcessor = new BDEngineDatapackProcessor(this.emoteRegistry);
	private final EmoteDialogManager emoteDialogManager = new EmoteDialogManager(
		this.emoteRegistry,
		this.emotePermissionService,
		this.emotePlaybackManager
	);

	@Override
	public void onInitialize() {
		this.configManager.addListener(this.emotePermissionService);
		this.configManager.readConfig();
		registerLifecycleCallbacks();
		registerCommands();
		LOGGER.info("Initialized {} bootstrap", MOD_ID);
	}

	private void registerLifecycleCallbacks() {
		ServerLifecycleEvents.SERVER_STARTED.register(this::handleServerStarted);
		ServerLifecycleEvents.END_DATA_PACK_RELOAD.register((server, resourceManager, success) -> handleDataPackReload(server, success));
		ServerLifecycleEvents.SERVER_STOPPING.register(this::handleServerStopping);
		ServerTickEvents.END_SERVER_TICK.register(this.emotePlaybackManager::tick);
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> this.emotePlaybackManager.stopEmote(handler.player));
	}

	private void registerCommands() {
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> EmoteCommand.registerCommand(
			dispatcher,
			this.emoteRegistry,
			this.emotePlaybackManager,
			this.bdEngineDatapackProcessor,
			this.configManager,
			this.emoteDialogManager,
			this.emotePermissionService
		));
	}

	private void handleServerStarted(MinecraftServer server) {
		int emoteCount = this.bdEngineDatapackProcessor.reloadServerEmotes(server);
		LOGGER.info("Loaded {} emotes from datapacks", emoteCount);
	}

	private void handleDataPackReload(MinecraftServer server, boolean success) {
		if (!success) {
			LOGGER.warn("Skipped emote reload because the datapack reload failed");
			return;
		}

		this.emotePlaybackManager.stopAllEmotes(server);
		int emoteCount = this.bdEngineDatapackProcessor.reloadServerEmotes(server);
		LOGGER.info("Reloaded {} emotes from datapacks", emoteCount);
	}

	private void handleServerStopping(MinecraftServer server) {
		this.emotePlaybackManager.stopAllEmotes(server);
		LOGGER.info("Stopped active emote sessions for server shutdown");
	}
}
