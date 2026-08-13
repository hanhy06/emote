package io.github.hanhy06.emote.config;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.hanhy06.emote.util.MinecraftTime;

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
        object.addProperty("max_active_display_entities", config.maxActiveDisplayEntities());
        return object;
    }

    JsonObject writeAccessConfig(AccessConfig config) {
        JsonObject object = new JsonObject();
        object.addProperty("schema_version", AccessConfig.CURRENT_SCHEMA_VERSION);
        JsonArray disabledJson = new JsonArray();
        config.disabled().forEach(disabledJson::add);
        object.add("disabled", disabledJson);
        JsonArray permissionsJson = new JsonArray();
        for (AccessConfig.PermissionEntry entry : config.permissions()) {
            JsonObject entryJson = new JsonObject();
            entryJson.addProperty("permission", entry.permission());
            JsonArray idsJson = new JsonArray();
            entry.emotes().forEach(idsJson::add);
            entryJson.add("emotes", idsJson);
            entry.idle().ifPresent(idle -> {
                JsonObject idleJson = new JsonObject();
                idleJson.addProperty("delay", idle.delayTicks() + "t");
                JsonArray idleEmotesJson = new JsonArray();
                for (AccessConfig.IdleSettings.Choice choice : idle.choices()) {
                    idleEmotesJson.add(choice.id());
                    if (choice.chance() > 0) {
                        idleEmotesJson.add(choice.chance());
                    }
                }
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
            readInt(object, "mineskin_cache_max_mib", defaultConfig.mineSkinCacheMaxMiB()),
            readInt(object, "max_active_display_entities", defaultConfig.maxActiveDisplayEntities())
        );
    }

    AccessConfig readAccessConfig(JsonObject object) {
        if (object == null) {
            return null;
        }

        JsonElement schemaElement = object.get("schema_version");
        if (schemaElement == null || !schemaElement.isJsonPrimitive() || !schemaElement.getAsJsonPrimitive().isNumber()) {
            return null;
        }
        try {
            if (schemaElement.getAsBigDecimal().intValueExact() != AccessConfig.CURRENT_SCHEMA_VERSION) {
                return null;
            }
        } catch (ArithmeticException exception) {
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
        List<AccessConfig.PermissionEntry> permissions = new ArrayList<>();
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

                Optional<AccessConfig.IdleSettings> idle = Optional.empty();
                JsonElement idleElement = entryJson.get("idle");
                if (idleElement != null && !idleElement.isJsonNull()) {
                    AccessConfig.IdleSettings parsedIdle = readIdleSettings(idleElement);
                    if (parsedIdle == null) {
                        return null;
                    }
                    idle = Optional.of(parsedIdle);
                }
                permissions.add(new AccessConfig.PermissionEntry(permission, ids, idle));
            }
        }
        return new AccessConfig(disabled, permissions);
    }

    private int readInt(JsonObject object, String key, int defaultValue) {
        JsonElement element = object.get(key);
        return element == null || element.isJsonNull() ? defaultValue : element.getAsInt();
    }

    private AccessConfig.IdleSettings readIdleSettings(JsonElement element) {
        if (!element.isJsonObject()) {
            return null;
        }

        JsonObject object = element.getAsJsonObject();
        JsonElement delayElement = object.get("delay");
        JsonElement emoteElement = object.get("emote");
        if (delayElement == null || !delayElement.isJsonPrimitive()
            || !delayElement.getAsJsonPrimitive().isString()
            || emoteElement == null || !emoteElement.isJsonArray()) {
            return null;
        }

        List<AccessConfig.IdleSettings.Choice> choices = readIdleChoices(emoteElement.getAsJsonArray());
        if (choices == null) {
            return null;
        }
        int delayTicks;
        try {
            delayTicks = MinecraftTime.parse(delayElement.getAsString(), 1);
        } catch (IllegalArgumentException exception) {
            return null;
        }
        return new AccessConfig.IdleSettings(delayTicks, choices);
    }

    private List<AccessConfig.IdleSettings.Choice> readIdleChoices(JsonArray array) {
        if (array.isEmpty()) {
            return null;
        }
        boolean weighted = array.size() > 1
            && array.get(1).isJsonPrimitive()
            && array.get(1).getAsJsonPrimitive().isNumber();
        int stride = weighted ? 2 : 1;
        if (weighted && array.size() % 2 != 0) {
            return null;
        }

        List<AccessConfig.IdleSettings.Choice> choices = new ArrayList<>();
        int totalChance = 0;
        for (int index = 0; index < array.size(); index += stride) {
            String id = readRequiredString(array.get(index));
            if (id == null || choices.stream().anyMatch(choice -> choice.id().equals(id))) {
                return null;
            }
            int chance = 0;
            if (weighted) {
                chance = readChance(array.get(index + 1));
                if (chance < 1) {
                    return null;
                }
                totalChance += chance;
            }
            choices.add(new AccessConfig.IdleSettings.Choice(id, chance));
        }
        if (weighted && totalChance != 100) {
            return null;
        }
        return List.copyOf(choices);
    }

    private int readChance(JsonElement element) {
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            return -1;
        }
        try {
            return element.getAsBigDecimal().intValueExact();
        } catch (ArithmeticException exception) {
            return -1;
        }
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
