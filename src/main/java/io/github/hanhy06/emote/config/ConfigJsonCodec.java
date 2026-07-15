package io.github.hanhy06.emote.config;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.hanhy06.emote.config.data.Config;
import io.github.hanhy06.emote.config.data.EmoteAccessConfig;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
        JsonObject permissionsJson = new JsonObject();
        for (Map.Entry<String, List<String>> entry : config.permissions().entrySet()) {
            JsonArray idsJson = new JsonArray();
            entry.getValue().forEach(idsJson::add);
            permissionsJson.add(entry.getKey(), idsJson);
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
        if (permissionsElement != null && !permissionsElement.isJsonNull() && !permissionsElement.isJsonObject()) {
            return null;
        }
        LinkedHashMap<String, List<String>> permissions = new LinkedHashMap<>();
        if (permissionsElement != null && !permissionsElement.isJsonNull()) {
            for (Map.Entry<String, JsonElement> entry : permissionsElement.getAsJsonObject().entrySet()) {
                String permission = normalizeRequiredValue(entry.getKey());
                if (permission == null || permissions.containsKey(permission) || !entry.getValue().isJsonArray()) {
                    return null;
                }
                List<String> ids = new ArrayList<>();
                for (JsonElement idElement : entry.getValue().getAsJsonArray()) {
                    if (!idElement.isJsonPrimitive() || !idElement.getAsJsonPrimitive().isString()) {
                        return null;
                    }
                    String id = normalizeRequiredValue(idElement.getAsString());
                    if (id == null) {
                        return null;
                    }
                    ids.add(id);
                }
                permissions.put(permission, ids);
            }
        }
        return new EmoteAccessConfig(disabled, permissions);
    }

    private int readInt(JsonObject object, String key, int defaultValue) {
        JsonElement element = object.get(key);
        return element == null || element.isJsonNull() ? defaultValue : element.getAsInt();
    }

    private String normalizeRequiredValue(String value) {
        if (value == null) {
            return null;
        }
        String normalizedValue = value.trim();
        return normalizedValue.isEmpty() ? null : normalizedValue;
    }
}
