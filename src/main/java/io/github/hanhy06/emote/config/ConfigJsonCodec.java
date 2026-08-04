package io.github.hanhy06.emote.config;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

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
        object.addProperty("mineskin_cache_retention_days", config.mineSkinCacheRetentionDays());
        object.addProperty("mineskin_cache_max_mib", config.mineSkinCacheMaxMiB());
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
                JsonArray idleEmotesJson = new JsonArray();
                idle.emote().forEach(idleEmotesJson::add);
                idleJson.add("emote", idleEmotesJson);
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
            readInt(object, "mineskin_poll_interval_seconds", defaultConfig.mineSkinPollIntervalSeconds()),
            readInt(object, "mineskin_cache_retention_days", defaultConfig.mineSkinCacheRetentionDays()),
            readInt(object, "mineskin_cache_max_mib", defaultConfig.mineSkinCacheMaxMiB())
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

        List<String> disabled = disabledElement == null || disabledElement.isJsonNull()
            ? List.of()
            : readRequiredStringList(disabledElement);
        if (disabled == null) {
            return null;
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
                String permission = readRequiredString(entryJson.get("permission"));
                List<String> ids = readRequiredStringList(entryJson.get("emotes"));
                if (permission == null || ids == null) {
                    return null;
                }

                Optional<EmoteAccessConfig.IdleEmote> idle = Optional.empty();
                JsonElement idleElement = entryJson.get("idle");
                if (idleElement != null && !idleElement.isJsonNull()) {
                    EmoteAccessConfig.IdleEmote parsedIdle = readIdleEmote(idleElement);
                    if (parsedIdle == null) {
                        return null;
                    }
                    idle = Optional.of(parsedIdle);
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

    private EmoteAccessConfig.IdleEmote readIdleEmote(JsonElement element) {
        if (!element.isJsonObject()) {
            return null;
        }

        JsonObject object = element.getAsJsonObject();
        JsonElement delayElement = object.get("delay_seconds");
        JsonElement emoteElement = object.get("emote");
        if (delayElement == null || !delayElement.isJsonPrimitive()
            || !delayElement.getAsJsonPrimitive().isNumber()
            || emoteElement == null || !emoteElement.isJsonArray()) {
            return null;
        }

        List<String> emotes = readRequiredStringList(emoteElement);
        if (emotes == null) {
            return null;
        }
        return new EmoteAccessConfig.IdleEmote(delayElement.getAsInt(), emotes);
    }

    private List<String> readRequiredStringList(JsonElement element) {
        if (element == null || !element.isJsonArray()) {
            return null;
        }
        List<String> values = new ArrayList<>();
        for (JsonElement valueElement : element.getAsJsonArray()) {
            String value = readRequiredString(valueElement);
            if (value == null) {
                return null;
            }
            values.add(value);
        }
        return List.copyOf(values);
    }

    private String readRequiredString(JsonElement element) {
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
            return null;
        }
        return normalizeRequiredValue(element.getAsString());
    }

    static String normalizeRequiredValue(String value) {
        if (value == null) {
            return null;
        }
        String normalizedValue = value.trim();
        return normalizedValue.isEmpty() ? null : normalizedValue;
    }
}
