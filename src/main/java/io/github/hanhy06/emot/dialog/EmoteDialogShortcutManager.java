package io.github.hanhy06.emot.dialog;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.stream.JsonWriter;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;
import io.github.hanhy06.emot.Emote;
import io.github.hanhy06.emot.config.ConfigManager;
import net.minecraft.SharedConstants;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.dialog.ActionButton;
import net.minecraft.server.dialog.CommonButtonData;
import net.minecraft.server.dialog.CommonDialogData;
import net.minecraft.server.dialog.Dialog;
import net.minecraft.server.dialog.DialogAction;
import net.minecraft.server.dialog.MultiActionDialog;
import net.minecraft.server.dialog.action.Action;
import net.minecraft.server.dialog.action.StaticAction;
import net.minecraft.server.dialog.body.PlainMessage;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.server.packs.metadata.pack.PackMetadataSection;
import net.minecraft.world.level.storage.LevelResource;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public class EmoteDialogShortcutManager {
	private static final String PACK_DIR_NAME = "emote-dialog-shortcut";
	private static final String DIALOG_NAMESPACE = Emote.MOD_ID;
	private static final String DIALOG_NAME = "menu_shortcut";
	private static final String QUICK_ACTION_TAG_NAME = "quick_actions.json";
	private static final String PACK_FILE_NAME = "pack.mcmeta";
	private static final Gson GSON = new GsonBuilder()
		.setPrettyPrinting()
		.disableHtmlEscaping()
		.create();

	public void updateDatapack(MinecraftServer server) {
		Path packDirPath = server.getWorldPath(LevelResource.DATAPACK_DIR).resolve(PACK_DIR_NAME);

		if (!ConfigManager.getConfig().quick_action_enabled()) {
			deleteDatapack(packDirPath);
			return;
		}

		try {
			Files.createDirectories(packDirPath);
			writePackMeta(packDirPath);
			writeDialog(packDirPath);
			writeQuickActionTag(packDirPath);
		} catch (IOException exception) {
			Emote.LOGGER.warn("Failed to update quick action datapack", exception);
		}
	}

	public boolean reloadIfNeeded(MinecraftServer server) {
		List<String> currentPackIds = List.copyOf(server.getPackRepository().getSelectedIds());
		List<String> reloadPackIds = getReloadPackIds(server);
		if (currentPackIds.equals(reloadPackIds)) {
			return false;
		}

		reload(server, reloadPackIds);
		return true;
	}

	public CompletableFuture<Void> reload(MinecraftServer server) {
		return reload(server, getReloadPackIds(server));
	}

	private void writePackMeta(Path packDirPath) throws IOException {
		PackMetadataSection packMetadataSection = new PackMetadataSection(
			Component.literal("Emote dialog shortcut"),
			SharedConstants.getCurrentVersion().packVersion(PackType.SERVER_DATA).minorRange()
		);
		JsonObject packMetaObject = new JsonObject();
		packMetaObject.add("pack", encodeJson(PackMetadataSection.SERVER_TYPE.codec().encodeStart(JsonOps.INSTANCE, packMetadataSection), PACK_FILE_NAME));
		writeJson(packDirPath.resolve(PACK_FILE_NAME), packMetaObject);
	}

	private void writeDialog(Path packDirPath) throws IOException {
		Path dialogPath = packDirPath
			.resolve("data")
			.resolve(DIALOG_NAMESPACE)
			.resolve("dialog")
			.resolve(DIALOG_NAME + ".json");
		Dialog dialog = createDialog();
		writeJson(dialogPath, encodeJson(Dialog.DIRECT_CODEC.encodeStart(JsonOps.INSTANCE, dialog), DIALOG_NAME + ".json"));
	}

	private void writeQuickActionTag(Path packDirPath) throws IOException {
		Path tagPath = packDirPath
			.resolve("data")
			.resolve("minecraft")
			.resolve("tags")
			.resolve("dialog")
			.resolve(QUICK_ACTION_TAG_NAME);

		JsonObject tagObject = new JsonObject();
		JsonArray values = new JsonArray();
		values.add(DIALOG_NAMESPACE + ":" + DIALOG_NAME);
		tagObject.add("values", values);
		writeJson(tagPath, tagObject);
	}

	private Dialog createDialog() {
		CommonButtonData buttonData = new CommonButtonData(
			Component.literal("Open Emotes"),
			Optional.of(Component.literal("Open emote menu")),
			170
		);
		Action action = new StaticAction(new ClickEvent.RunCommand("/emote menu"));
		ActionButton actionButton = new ActionButton(buttonData, Optional.of(action));
		CommonDialogData dialogData = new CommonDialogData(
			Component.literal("Emotes"),
			Optional.of(Component.literal("Quick Action")),
			true,
			false,
			DialogAction.CLOSE,
			List.of(new PlainMessage(Component.literal("Open the emote menu."), 220)),
			List.of()
		);

		return new MultiActionDialog(dialogData, List.of(actionButton), Optional.empty(), 1);
	}

	private JsonElement encodeJson(DataResult<JsonElement> result, String fileName) throws IOException {
		Optional<DataResult.Error<JsonElement>> error = result.error();
		if (error.isPresent()) {
			throw new IOException("Failed to encode " + fileName + ": " + error.get().message());
		}

		return result.getOrThrow();
	}

	private void writeJson(Path filePath, JsonElement element) throws IOException {
		Files.createDirectories(filePath.getParent());

		try (BufferedWriter writer = Files.newBufferedWriter(
			filePath,
			StandardCharsets.UTF_8,
			StandardOpenOption.CREATE,
			StandardOpenOption.TRUNCATE_EXISTING
		)) {
			JsonWriter jsonWriter = new JsonWriter(writer);
			jsonWriter.setSerializeNulls(false);
			jsonWriter.setIndent("  ");
			GSON.toJson(element, jsonWriter);
			jsonWriter.close();
		}
	}

	private void deleteDatapack(Path packDirPath) {
		if (!Files.exists(packDirPath)) {
			return;
		}

		try (var pathStream = Files.walk(packDirPath)) {
			for (Path path : pathStream.sorted(Comparator.reverseOrder()).toList()) {
				Files.deleteIfExists(path);
			}
		} catch (IOException exception) {
			Emote.LOGGER.warn("Failed to delete quick action datapack", exception);
		}
	}

	private List<String> getReloadPackIds(MinecraftServer server) {
		PackRepository packRepository = server.getPackRepository();
		LinkedHashSet<String> selectedPackIds = new LinkedHashSet<>(packRepository.getSelectedIds());
		packRepository.reload();

		if (ConfigManager.getConfig().quick_action_enabled()) {
			if (packRepository.isAvailable(PACK_DIR_NAME)) {
				selectedPackIds.add(PACK_DIR_NAME);
			}
		} else {
			selectedPackIds.remove(PACK_DIR_NAME);
		}

		return List.copyOf(new ArrayList<>(selectedPackIds));
	}

	private CompletableFuture<Void> reload(MinecraftServer server, List<String> reloadPackIds) {
		return server.reloadResources(reloadPackIds).exceptionally(throwable -> {
			Emote.LOGGER.warn("Failed to reload quick action datapack", throwable);
			return null;
		});
	}
}
