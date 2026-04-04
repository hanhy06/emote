package io.github.hanhy06.emote.skin;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.hanhy06.emote.Emote;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Objects;

public class MineSkinApiClient {
    private static final URI QUEUE_URI = URI.create("https://api.mineskin.org/v2/queue");
    private static final int JOB_POLL_LIMIT = 60;
    private static final int JOB_POLL_INTERVAL_MILLIS = 1000;
    private static final String USER_AGENT = createUserAgent();

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final Gson gson = new Gson();

    public String generateSkinUrl(String apiKey, byte[] pngBytes, boolean slimModel) throws IOException, InterruptedException {
        Objects.requireNonNull(pngBytes, "pngBytes");

        String normalizedApiKey = normalizeApiKey(apiKey);
        if (normalizedApiKey == null) {
            throw new IOException("MineSkin API key is missing");
        }

        JsonObject requestBody = new JsonObject();
        requestBody.addProperty("url", "data:image/png;base64," + Base64.getEncoder().encodeToString(pngBytes));
        requestBody.addProperty("variant", slimModel ? "slim" : "classic");
        requestBody.addProperty("visibility", "unlisted");

        JsonObject queueResponse = sendJsonRequest(HttpRequest.newBuilder(QUEUE_URI)
                .header("Authorization", "Bearer " + normalizedApiKey)
                .header("User-Agent", USER_AGENT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(this.gson.toJson(requestBody), StandardCharsets.UTF_8))
                .build());
        String textureUrl = readTextureUrl(queueResponse);
        if (textureUrl != null) {
            return textureUrl;
        }

        String jobId = readJobId(queueResponse);
        if (jobId == null) {
            throw new IOException("MineSkin queue response did not include a job id");
        }

        for (int attempt = 0; attempt < JOB_POLL_LIMIT; attempt++) {
            Thread.sleep(JOB_POLL_INTERVAL_MILLIS);

            JsonObject jobResponse = sendJsonRequest(HttpRequest.newBuilder(QUEUE_URI.resolve("/v2/queue/" + jobId))
                    .header("Authorization", "Bearer " + normalizedApiKey)
                    .header("User-Agent", USER_AGENT)
                    .GET()
                    .build());
            textureUrl = readTextureUrl(jobResponse);
            if (textureUrl != null) {
                return textureUrl;
            }

            String jobStatus = readJobStatus(jobResponse);
            if ("failed".equalsIgnoreCase(jobStatus)) {
                throw new IOException(readErrorMessage(jobResponse, "MineSkin job failed"));
            }
        }

        throw new IOException("MineSkin job timed out");
    }

    private JsonObject sendJsonRequest(HttpRequest request) throws IOException, InterruptedException {
        HttpResponse<String> response = this.httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        JsonObject responseBody = parseJsonObject(response.body());
        if (response.statusCode() / 100 == 2) {
            return responseBody;
        }

        throw new IOException(readErrorMessage(responseBody, "MineSkin request failed: " + response.statusCode()));
    }

    private JsonObject parseJsonObject(String body) throws IOException {
        if (body == null || body.isBlank()) {
            return new JsonObject();
        }

        try {
            JsonElement element = JsonParser.parseString(body);
            if (!element.isJsonObject()) {
                throw new IOException("MineSkin response was not a JSON object");
            }

            return element.getAsJsonObject();
        } catch (RuntimeException exception) {
            throw new IOException("Failed to parse MineSkin response", exception);
        }
    }

    private String readTextureUrl(JsonObject responseBody) {
        JsonObject skinObject = findObject(responseBody, "skin");
        JsonObject textureObject = findObject(skinObject, "texture");
        JsonObject urlObject = findObject(textureObject, "url");
        return readString(urlObject, "skin");
    }

    private String readJobId(JsonObject responseBody) {
        JsonObject jobObject = findObject(responseBody, "job");
        return readString(jobObject, "id");
    }

    private String readJobStatus(JsonObject responseBody) {
        JsonObject jobObject = findObject(responseBody, "job");
        return readString(jobObject, "status");
    }

    private String readErrorMessage(JsonObject responseBody, String fallbackMessage) {
        JsonArray errors = findArray(responseBody, "errors");
        if (errors != null) {
            for (JsonElement errorElement : errors) {
                if (!errorElement.isJsonObject()) {
                    continue;
                }

                String message = readString(errorElement.getAsJsonObject(), "message");
                if (message != null) {
                    return message;
                }
            }
        }

        JsonArray messages = findArray(responseBody, "messages");
        if (messages != null) {
            for (JsonElement messageElement : messages) {
                if (!messageElement.isJsonObject()) {
                    continue;
                }

                String message = readString(messageElement.getAsJsonObject(), "message");
                if (message != null) {
                    return message;
                }
            }
        }

        return fallbackMessage;
    }

    private JsonObject findObject(JsonObject parent, String key) {
        if (parent == null) {
            return null;
        }

        JsonElement element = parent.get(key);
        return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
    }

    private JsonArray findArray(JsonObject parent, String key) {
        if (parent == null) {
            return null;
        }

        JsonElement element = parent.get(key);
        return element != null && element.isJsonArray() ? element.getAsJsonArray() : null;
    }

    private String readString(JsonObject parent, String key) {
        if (parent == null) {
            return null;
        }

        JsonElement element = parent.get(key);
        if (element == null || element.isJsonNull()) {
            return null;
        }

        String value = element.getAsString().trim();
        return value.isEmpty() ? null : value;
    }

    private String normalizeApiKey(String apiKey) {
        if (apiKey == null) {
            return null;
        }

        String normalizedApiKey = apiKey.trim();
        if (normalizedApiKey.isEmpty()) {
            return null;
        }

        if (normalizedApiKey.regionMatches(true, 0, "Bearer ", 0, 7)) {
            normalizedApiKey = normalizedApiKey.substring(7).trim();
        }

        return normalizedApiKey.isEmpty() ? null : normalizedApiKey;
    }

    private static String createUserAgent() {
        try {
            return FabricLoader.getInstance()
                    .getModContainer(Emote.MOD_ID)
                    .map(container -> Emote.MOD_ID + "/" + container.getMetadata().getVersion().getFriendlyString())
                    .orElse(Emote.MOD_ID + "/dev");
        } catch (RuntimeException exception) {
            return Emote.MOD_ID + "/dev";
        }
    }
}
