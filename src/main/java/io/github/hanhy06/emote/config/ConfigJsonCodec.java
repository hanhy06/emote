package io.github.hanhy06.emote.config;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.hanhy06.emote.config.data.Config;
import io.github.hanhy06.emote.config.data.EmoteAccessConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

final class ConfigJsonCodec {
    JsonObject writeConfig(Config config) {
        JsonObject object = new JsonObject();
        object.addProperty("schema_version", config.schemaVersion());
        object.addProperty("menu_page_size", config.menuPageSize());
        object.addProperty("mineskin_api_key", config.mineSkinApiKey());
        object.addProperty("mineskin_poll_interval_seconds", config.mineSkinPollIntervalSeconds());
        return object;
    }

    JsonObject writeEmoteAccessConfig(EmoteAccessConfig config) {
        JsonObject object = new JsonObject();
        JsonArray disabledJson = new JsonArray();
        config.disabled().forEach(disabledJson::add);
        object.add("disabled", disabledJson);
        JsonArray permissionsJson = new JsonArray();
        for (EmoteAccessConfig.PermissionEntry entry : config.permissions()) {
            JsonObject entryJson = new JsonObject();
            entryJson.addProperty("permission", entry.permission());
            JsonArray idsJson = new JsonArray();
            entry.emotes().forEach(idsJson::add);
            entryJson.add("emotes", idsJson);
            entry.idle().ifPresent(idle -> {
                JsonObject idleJson = new JsonObject();
                idleJson.addProperty("delay_seconds", idle.delaySeconds());
                idleJson.addProperty("emote", idle.emote());
                entryJson.add("idle", idleJson);
            });
            permissionsJson.add(entryJson);
        }
        object.add("permissions", permissionsJson);
        return object;
    }

    Config readConfig(JsonObject object) {
        if (object == null) {
            return null;
        }
        Config defaultConfig = Config.createDefault();
        JsonElement mineSkinApiKeyElement = object.get("mineskin_api_key");
        String mineSkinApiKey = mineSkinApiKeyElement == null || mineSkinApiKeyElement.isJsonNull()
            ? defaultConfig.mineSkinApiKey()
            : mineSkinApiKeyElement.getAsString();
        return new Config(
            readInt(object, "schema_version", Config.CURRENT_SCHEMA_VERSION),
            readInt(object, "menu_page_size", defaultConfig.menuPageSize()),
            mineSkinApiKey,
            readInt(object, "mineskin_poll_interval_seconds", defaultConfig.mineSkinPollIntervalSeconds())
        );
    }

    EmoteAccessConfig readEmoteAccessConfig(JsonObject object) {
        if (object == null) {
            return null;
        }
        JsonElement disabledElement = object.get("disabled");
        if (disabledElement != null && !disabledElement.isJsonNull() && !disabledElement.isJsonArray()) {
            return null;
        }

        List<String> disabled = new ArrayList<>();
        if (disabledElement != null && !disabledElement.isJsonNull()) {
            for (JsonElement idElement : disabledElement.getAsJsonArray()) {
                if (!idElement.isJsonPrimitive() || !idElement.getAsJsonPrimitive().isString()) {
                    return null;
                }
                String id = normalizeRequiredValue(idElement.getAsString());
                if (id == null) {
                    return null;
                }
                disabled.add(id);
            }
        }

        JsonElement permissionsElement = object.get("permissions");
        if (permissionsElement != null && !permissionsElement.isJsonNull() && !permissionsElement.isJsonArray()) {
            return null;
        }
        List<EmoteAccessConfig.PermissionEntry> permissions = new ArrayList<>();
        if (permissionsElement != null && !permissionsElement.isJsonNull()) {
            for (JsonElement entryElement : permissionsElement.getAsJsonArray()) {
                if (!entryElement.isJsonObject()) {
                    return null;
                }
                JsonObject entryJson = entryElement.getAsJsonObject();
                String permission = readRequiredString(entryJson, "permission");
                JsonElement emotesElement = entryJson.get("emotes");
                if (permission == null || emotesElement == null || !emotesElement.isJsonArray()) {
                    return null;
                }

                List<String> ids = new ArrayList<>();
                for (JsonElement idElement : emotesElement.getAsJsonArray()) {
                    if (!idElement.isJsonPrimitive() || !idElement.getAsJsonPrimitive().isString()) {
                        return null;
                    }
                    String id = normalizeRequiredValue(idElement.getAsString());
                    if (id == null) {
                        return null;
                    }
                    ids.add(id);
                }

                Optional<EmoteAccessConfig.IdleEmote> idle = readIdleEmote(entryJson.get("idle"));
                if (idle == null) {
                    return null;
                }
                permissions.add(new EmoteAccessConfig.PermissionEntry(permission, ids, idle));
            }
        }
        return new EmoteAccessConfig(disabled, permissions);
    }

    private int readInt(JsonObject object, String key, int defaultValue) {
        JsonElement element = object.get(key);
        return element == null || element.isJsonNull() ? defaultValue : element.getAsInt();
    }

    private Optional<EmoteAccessConfig.IdleEmote> readIdleEmote(JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return Optional.empty();
        }
        if (!element.isJsonObject()) {
            return null;
        }

        JsonObject object = element.getAsJsonObject();
        JsonElement delayElement = object.get("delay_seconds");
        String emote = readRequiredString(object, "emote");
        if (delayElement == null || !delayElement.isJsonPrimitive()
            || !delayElement.getAsJsonPrimitive().isNumber() || emote == null) {
            return null;
        }
        return Optional.of(new EmoteAccessConfig.IdleEmote(delayElement.getAsInt(), emote));
    }

    private String readRequiredString(JsonObject object, String key) {
        JsonElement element = object.get(key);
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
            return null;
        }
        return normalizeRequiredValue(element.getAsString());
    }

    private String normalizeRequiredValue(String value) {
        if (value == null) {
            return null;
        }
        String normalizedValue = value.trim();
        return normalizedValue.isEmpty() ? null : normalizedValue;
    }
}
