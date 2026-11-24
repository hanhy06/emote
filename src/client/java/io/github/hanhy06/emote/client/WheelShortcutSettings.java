package io.github.hanhy06.emote.client;

import com.google.gson.*;
import io.github.hanhy06.emote.EmoteMod;
import io.github.hanhy06.emote.application.EmoteSummary;
import io.github.hanhy06.emote.config.JsonFileStore;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public class WheelShortcutSettings {
    private static final int CURRENT_SCHEMA_VERSION = 1;
    private static final Comparator<EmoteSummary> BY_NAME = Comparator.comparing(EmoteSummary::name).thenComparing(EmoteSummary::id);

    private final Path filePath;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private final Map<String, List<String>> shortcutsByServer = new LinkedHashMap<>();
    private final Map<String, List<String>> knownIdsByServer = new LinkedHashMap<>();

    private String serverKey = "";
    private Map<String, EmoteSummary> availableById = Map.of();
    private List<String> slotIds = List.of();

    public WheelShortcutSettings(Path filePath) {
        this.filePath = filePath;
        load();
    }

    public void updateServer(String serverKey, List<EmoteSummary> availableEmotes) {
        this.serverKey = serverKey;
        Map<String, EmoteSummary> nextAvailableById = new LinkedHashMap<>();
        for (EmoteSummary emote : availableEmotes) {
            nextAvailableById.putIfAbsent(emote.id(), emote);
        }
        this.availableById = Collections.unmodifiableMap(nextAvailableById);

        List<String> storedIds = this.shortcutsByServer.get(serverKey);
        List<String> knownIds = this.knownIdsByServer.get(serverKey);
        boolean settingsChanged;
        if (storedIds == null) {
            storedIds = List.copyOf(nextAvailableById.keySet());
            this.shortcutsByServer.put(serverKey, storedIds);
            this.knownIdsByServer.put(serverKey, storedIds);
            settingsChanged = true;
        } else if (knownIds == null) {
            LinkedHashSet<String> migratedKnownIds = new LinkedHashSet<>(storedIds);
            migratedKnownIds.remove(null);
            migratedKnownIds.addAll(nextAvailableById.keySet());
            this.knownIdsByServer.put(serverKey, List.copyOf(migratedKnownIds));
            settingsChanged = true;
        } else {
            List<String> nextStoredIds = new ArrayList<>(storedIds);
            LinkedHashSet<String> nextKnownIds = new LinkedHashSet<>(knownIds);
            for (String id : storedIds) {
                if (id != null) {
                    nextKnownIds.add(id);
                }
            }
            for (String id : nextAvailableById.keySet()) {
                if (nextKnownIds.add(id)) {
                    nextStoredIds.add(id);
                }
            }
            settingsChanged = nextKnownIds.size() != knownIds.size();
            if (settingsChanged) {
                storedIds = Collections.unmodifiableList(nextStoredIds);
                this.shortcutsByServer.put(serverKey, storedIds);
                this.knownIdsByServer.put(serverKey, List.copyOf(nextKnownIds));
            }
        }

        if (settingsChanged) {
            save();
        }
        this.slotIds = storedIds;
    }

    public void clearSession() {
        this.serverKey = "";
        this.availableById = Map.of();
        this.slotIds = List.of();
    }

    List<EmoteSummary> selectedSlots() {
        return resolveSlots(this.slotIds);
    }

    List<EmoteSummary> resolveSlots(List<String> slotIds) {
        return slotIds.stream().map(this::emote).toList();
    }

    EmoteSummary emote(String id) {
        return id == null ? null : this.availableById.get(id);
    }

    List<EmoteSummary> allEmotes() {
        return List.copyOf(this.availableById.values());
    }

    List<EmoteSummary> availableEmotes(List<String> slotIds) {
        Set<String> selected = new HashSet<>(slotIds);
        return this.availableById.values().stream()
            .filter(emote -> !selected.contains(emote.id()))
            .toList();
    }

    List<String> slotIds() {
        return this.slotIds;
    }

    void sortSlots(List<String> slotIds, boolean groupByTag) {
        List<EmoteSummary> selected = new ArrayList<>();
        for (String id : slotIds) {
            EmoteSummary emote = emote(id);
            if (emote != null) {
                selected.add(emote);
            }
        }
        if (selected.size() < 2) {
            return;
        }

        Comparator<EmoteSummary> order = BY_NAME;
        if (groupByTag) {
            Map<String, Integer> tagCounts = new HashMap<>();
            for (EmoteSummary emote : selected) {
                emote.tags().stream().distinct().forEach(tag -> tagCounts.merge(tag, 1, Integer::sum));
            }
            Comparator<String> preferredTag = Comparator.<String>comparingInt(tagCounts::get).reversed().thenComparing(Comparator.naturalOrder());
            Map<String, String> groupById = new HashMap<>();
            for (EmoteSummary emote : selected) {
                emote.tags().stream().min(preferredTag).ifPresent(tag -> groupById.put(emote.id(), tag));
            }
            order = Comparator.comparing((EmoteSummary emote) -> groupById.get(emote.id()), Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(BY_NAME);
        }
        selected.sort(order);

        // Only the occupied, visible slots participate in sorting.
        Iterator<EmoteSummary> sorted = selected.iterator();
        for (int i = 0; i < slotIds.size(); i++) {
            if (emote(slotIds.get(i)) != null) {
                slotIds.set(i, sorted.next().id());
            }
        }
    }

    void saveSlots(List<String> nextIds) {
        if (this.slotIds.equals(nextIds)) {
            return;
        }
        this.slotIds = Collections.unmodifiableList(new ArrayList<>(nextIds));
        if (this.serverKey.isEmpty()) {
            return;
        }

        this.shortcutsByServer.put(this.serverKey, this.slotIds);
        save();
    }

    private void load() {
        if (Files.notExists(this.filePath)) {
            return;
        }

        try {
            JsonObject root = JsonFileStore.readObject(this.filePath);
            if (root == null || readInt(root.get("schema_version")) != CURRENT_SCHEMA_VERSION) {
                EmoteMod.LOGGER.warn("Wheel shortcut settings are empty or use an unsupported schema version");
                return;
            }

            JsonElement serversElement = root.get("servers");
            if (serversElement == null || !serversElement.isJsonObject()) {
                EmoteMod.LOGGER.warn("Wheel shortcut settings do not contain a valid servers object");
                return;
            }

            for (Map.Entry<String, JsonElement> entry : serversElement.getAsJsonObject().entrySet()) {
                List<String> ids = readIds(entry.getValue(), true);
                if (ids != null) {
                    this.shortcutsByServer.put(entry.getKey(), ids);
                }
            }

            JsonElement knownServersElement = root.get("known_servers");
            if (knownServersElement != null && knownServersElement.isJsonObject()) {
                for (Map.Entry<String, JsonElement> entry : knownServersElement.getAsJsonObject().entrySet()) {
                    List<String> ids = readIds(entry.getValue(), false);
                    if (ids != null) {
                        this.knownIdsByServer.put(entry.getKey(), ids);
                    }
                }
            }
        } catch (IOException exception) {
            EmoteMod.LOGGER.warn("Failed to read wheel shortcut settings", exception);
        } catch (RuntimeException exception) {
            EmoteMod.LOGGER.warn("Failed to parse wheel shortcut settings: {}", exception.getMessage());
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
        JsonObject knownServers = new JsonObject();
        for (Map.Entry<String, List<String>> entry : this.knownIdsByServer.entrySet()) {
            JsonArray ids = new JsonArray();
            entry.getValue().forEach(ids::add);
            knownServers.add(entry.getKey(), ids);
        }
        root.add("known_servers", knownServers);

        try {
            JsonFileStore.writeObjectAtomically(this.filePath, root, this.gson);
        } catch (IOException exception) {
            EmoteMod.LOGGER.error("Failed to save wheel shortcut settings", exception);
        }
    }

    private static int readInt(JsonElement element) {
        return element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()
            ? 0
            : element.getAsInt();
    }

    private static List<String> readIds(JsonElement element, boolean allowEmptySlots) {
        if (!element.isJsonArray()) {
            return null;
        }

        List<String> ids = new ArrayList<>();
        Set<String> seenIds = new HashSet<>();
        for (JsonElement idElement : element.getAsJsonArray()) {
            if (allowEmptySlots && idElement.isJsonNull()) {
                ids.add(null);
                continue;
            }
            if (!idElement.isJsonPrimitive() || !idElement.getAsJsonPrimitive().isString()) {
                return null;
            }
            String id = idElement.getAsString().trim();
            if (!id.isEmpty() && seenIds.add(id)) {
                ids.add(id);
            } else if (allowEmptySlots) {
                ids.add(null);
            }
        }
        return Collections.unmodifiableList(ids);
    }
}
