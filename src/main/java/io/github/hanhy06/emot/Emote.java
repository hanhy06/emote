package io.github.hanhy06.emot;

import io.github.hanhy06.emot.bdengine.BDEngineDatapackProcessor;
import io.github.hanhy06.emot.command.EmoteCommand;
import io.github.hanhy06.emot.config.ConfigManager;
import io.github.hanhy06.emot.dialog.EmoteDialogManager;
import io.github.hanhy06.emot.emote.EmoteRegistry;
import io.github.hanhy06.emot.permission.EmotePermissionService;
import io.github.hanhy06.emot.playback.EmotePlaybackManager;
import io.github.hanhy06.emot.skin.PlayerSkinManager;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

public class Emote implements ModInitializer {
	public static final String MOD_ID = "emote";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
	private static final PlayerSkinManager PLAYER_SKIN_MANAGER = new PlayerSkinManager();
	private static final String LEGACY_QUICK_ACTION_PACK_DIR = "emote-dialog-shortcut";

	private final ConfigManager configManager = new ConfigManager(FabricLoader.getInstance().getConfigDir());
	private final EmoteRegistry emoteRegistry = new EmoteRegistry();
	private final EmotePlaybackManager emotePlaybackManager = new EmotePlaybackManager(PLAYER_SKIN_MANAGER);
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
		LOGGER.info("{} ready", MOD_ID);
	}

	public static PlayerSkinManager getPlayerSkinManager() {
		return PLAYER_SKIN_MANAGER;
	}

	private void registerLifecycleCallbacks() {
		ServerLifecycleEvents.SERVER_STARTING.register(this::handleServerStarting);
		ServerLifecycleEvents.SERVER_STARTED.register(this::handleServerStarted);
		ServerLifecycleEvents.START_DATA_PACK_RELOAD.register((server, resourceManager) -> handleDataPackReloadStart(server));
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

	private void handleServerStarting(MinecraftServer server) {
		deleteLegacyQuickActionDatapack(server);
	}

	private void handleServerStarted(MinecraftServer server) {
		int emoteCount = this.bdEngineDatapackProcessor.reloadServerEmotes(server);
		LOGGER.info("emotes={}", emoteCount);
	}

	private void handleDataPackReloadStart(MinecraftServer server) {
		this.configManager.readConfig();
	}

	private void handleDataPackReload(MinecraftServer server, boolean success) {
		if (!success) {
			LOGGER.warn("Datapack reload failed");
			return;
		}

		this.emotePlaybackManager.stopAllEmotes(server);
		int emoteCount = this.bdEngineDatapackProcessor.reloadServerEmotes(server);
		LOGGER.info("reload emotes={}", emoteCount);
	}

	private void handleServerStopping(MinecraftServer server) {
		this.emotePlaybackManager.stopAllEmotes(server);
		PLAYER_SKIN_MANAGER.clear();
		LOGGER.info("stop emotes");
	}

	private void deleteLegacyQuickActionDatapack(MinecraftServer server) {
		Path legacyPackPath = server.getWorldPath(LevelResource.DATAPACK_DIR).resolve(LEGACY_QUICK_ACTION_PACK_DIR);
		if (!Files.exists(legacyPackPath)) {
			return;
		}

		try (var pathStream = Files.walk(legacyPackPath)) {
			for (Path path : pathStream.sorted(Comparator.reverseOrder()).toList()) {
				Files.deleteIfExists(path);
			}
			LOGGER.info("Removed legacy quick action datapack");
		} catch (IOException exception) {
			LOGGER.warn("Failed to remove legacy quick action datapack", exception);
		}
	}
}
