package io.github.hanhy06.emote.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.hanhy06.emote.Emote;
import io.github.hanhy06.emote.config.JsonFileStore;
import io.github.hanhy06.emote.emote.PlayableEmote;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

public class WheelShortcutSettings {
    private static final int CURRENT_SCHEMA_VERSION = 1;

    private final Path filePath;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private final Map<String, List<String>> shortcutsByServer = new LinkedHashMap<>();

    private String serverKey = "";
    private Map<String, PlayableEmote> availableById = Map.of();
    private List<String> selectedIds = List.of();

    public WheelShortcutSettings(Path filePath) {
        this.filePath = filePath;
        load();
    }

    public void updateServer(String serverKey, List<PlayableEmote> availableEmotes) {
        this.serverKey = serverKey;
        Map<String, PlayableEmote> nextAvailableById = new LinkedHashMap<>();
        for (PlayableEmote emote : availableEmotes) {
            nextAvailableById.putIfAbsent(emote.id(), emote);
        }
        this.availableById = Collections.unmodifiableMap(nextAvailableById);

        List<String> storedIds = this.shortcutsByServer.get(serverKey);
        if (storedIds == null) {
            storedIds = List.copyOf(nextAvailableById.keySet());
            this.shortcutsByServer.put(serverKey, storedIds);
            save();
        }
        this.selectedIds = storedIds;
    }

    public void clearSession() {
        this.serverKey = "";
        this.availableById = Map.of();
        this.selectedIds = List.of();
    }

    public List<PlayableEmote> selectedEmotes() {
        List<PlayableEmote> selected = new ArrayList<>();
        for (String id : this.selectedIds) {
            PlayableEmote emote = this.availableById.get(id);
            if (emote != null) {
                selected.add(emote);
            }
        }
        return List.copyOf(selected);
    }

    public List<PlayableEmote> availableEmotes() {
        LinkedHashSet<String> selected = new LinkedHashSet<>(this.selectedIds);
        return this.availableById.values().stream()
            .filter(emote -> !selected.contains(emote.id()))
            .toList();
    }

    public void add(String id) {
        if (!this.availableById.containsKey(id) || this.selectedIds.contains(id)) {
            return;
        }

        List<String> nextIds = new ArrayList<>(this.selectedIds);
        nextIds.add(id);
        updateSelectedIds(nextIds);
    }

    public void remove(String id) {
        if (!this.selectedIds.contains(id)) {
            return;
        }

        List<String> nextIds = new ArrayList<>(this.selectedIds);
        nextIds.remove(id);
        updateSelectedIds(nextIds);
    }

    public void moveUp(String id) {
        move(id, -1);
    }

    public void moveDown(String id) {
        move(id, 1);
    }

    private void move(String id, int direction) {
        List<PlayableEmote> visibleSelection = selectedEmotes();
        int visibleIndex = -1;
        for (int index = 0; index < visibleSelection.size(); index++) {
            if (visibleSelection.get(index).id().equals(id)) {
                visibleIndex = index;
                break;
            }
        }

        int targetVisibleIndex = visibleIndex + direction;
        if (visibleIndex < 0 || targetVisibleIndex < 0 || targetVisibleIndex >= visibleSelection.size()) {
            return;
        }

        String targetId = visibleSelection.get(targetVisibleIndex).id();
        List<String> nextIds = new ArrayList<>(this.selectedIds);
        int index = nextIds.indexOf(id);
        int targetIndex = nextIds.indexOf(targetId);
        nextIds.set(index, targetId);
        nextIds.set(targetIndex, id);
        updateSelectedIds(nextIds);
    }

    private void updateSelectedIds(List<String> nextIds) {
        this.selectedIds = List.copyOf(nextIds);
        if (this.serverKey.isEmpty()) {
            return;
        }

        this.shortcutsByServer.put(this.serverKey, this.selectedIds);
        save();
    }

    private void load() {
        if (Files.notExists(this.filePath)) {
            return;
        }

        try {
            JsonObject root = JsonFileStore.readObject(this.filePath);
            if (root == null || readInt(root.get("schema_version"), 0) != CURRENT_SCHEMA_VERSION) {
                Emote.LOGGER.warn("Wheel shortcut settings are empty or use an unsupported schema version.");
                return;
            }

            JsonElement serversElement = root.get("servers");
            if (serversElement == null || !serversElement.isJsonObject()) {
                Emote.LOGGER.warn("Wheel shortcut settings do not contain a valid servers object.");
                return;
            }

            for (Map.Entry<String, JsonElement> entry : serversElement.getAsJsonObject().entrySet()) {
                List<String> ids = readIds(entry.getValue());
                if (ids != null) {
                    this.shortcutsByServer.put(entry.getKey(), ids);
                }
            }
        } catch (IOException | RuntimeException exception) {
            Emote.LOGGER.warn("Failed to read wheel shortcut settings: {}", exception.getMessage());
        }
    }

    private void save() {
        JsonObject root = new JsonObject();
        root.addProperty("schema_version", CURRENT_SCHEMA_VERSION);
        JsonObject servers = new JsonObject();
        for (Map.Entry<String, List<String>> entry : this.shortcutsByServer.entrySet()) {
            JsonArray ids = new JsonArray();
            entry.getValue().forEach(ids::add);
            servers.add(entry.getKey(), ids);
        }
        root.add("servers", servers);

        try {
            JsonFileStore.writeObjectAtomically(this.filePath, root, this.gson);
        } catch (IOException exception) {
            Emote.LOGGER.error("Failed to save wheel shortcut settings: {}", exception.getMessage());
        }
    }

    private static int readInt(JsonElement element, int defaultValue) {
        return element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()
            ? defaultValue
            : element.getAsInt();
    }

    private static List<String> readIds(JsonElement element) {
        if (!element.isJsonArray()) {
            return null;
        }

        LinkedHashSet<String> ids = new LinkedHashSet<>();
        for (JsonElement idElement : element.getAsJsonArray()) {
            if (!idElement.isJsonPrimitive() || !idElement.getAsJsonPrimitive().isString()) {
                return null;
            }
            String id = idElement.getAsString().trim();
            if (!id.isEmpty()) {
                ids.add(id);
            }
        }
        return List.copyOf(ids);
    }
}
