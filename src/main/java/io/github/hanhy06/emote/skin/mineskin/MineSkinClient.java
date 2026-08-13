package io.github.hanhy06.emote.skin.mineskin;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.hanhy06.emote.Emote;
import net.fabricmc.loader.api.FabricLoader;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;

public final class MineSkinClient {
    private static final URI QUEUE_URI = URI.create("https://api.mineskin.org/v2/queue");
    private static final int MAX_SKIN_DOWNLOAD_BYTES = 1_048_576;
    private static final int SKIN_DOWNLOAD_TIMEOUT_MILLIS = 5000;
    private static final long JOB_TIMEOUT_MILLIS = Duration.ofMinutes(30).toMillis();
    private static final List<String> ERROR_MESSAGE_FIELDS = List.of("errors", "messages");
    private static final String USER_AGENT = createUserAgent();

    private final HttpClient httpClient;

    private volatile long jobPollIntervalMillis = 3000L;

    public MineSkinClient() {
        this(createHttpClient());
    }

    public MineSkinClient(HttpClient httpClient) {
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
    }

    public static HttpClient createHttpClient() {
        return HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    }

    void setJobPollIntervalSeconds(int seconds) {
        if (seconds < 1 || seconds > 60) {
            throw new IllegalArgumentException("seconds must be between 1 and 60");
        }
        this.jobPollIntervalMillis = seconds * 1000L;
    }

    BufferedImage downloadSkinImage(String textureUrl) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(textureUrl))
            .timeout(Duration.ofMillis(SKIN_DOWNLOAD_TIMEOUT_MILLIS))
            .GET()
            .build();
        HttpResponse<InputStream> response = this.httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        byte[] imageBytes;
        try (InputStream input = response.body()) {
            if (response.statusCode() / 100 != 2) {
                throw new IOException("unexpected skin response: " + response.statusCode());
            }
            imageBytes = input.readNBytes(MAX_SKIN_DOWNLOAD_BYTES + 1);
            if (imageBytes.length > MAX_SKIN_DOWNLOAD_BYTES) {
                throw new IOException("skin image exceeds maximum size");
            }
        }

        BufferedImage image = ImageIO.read(new ByteArrayInputStream(imageBytes));
        if (image == null) {
            throw new IOException("skin image decode failed");
        }
        return image;
    }

    String generateSkinUrl(
        String apiKey,
        byte[] pngBytes,
        boolean slimModel,
        Consumer<String> queuedJobListener
    ) throws IOException, InterruptedException {
        Objects.requireNonNull(pngBytes, "pngBytes");
        Objects.requireNonNull(queuedJobListener, "queuedJobListener");

        String normalizedApiKey = normalizeApiKey(apiKey);
        if (normalizedApiKey == null) {
            throw new IOException("MineSkin API key is missing");
        }

        MultipartBody requestBody = createUploadBody(pngBytes, slimModel);

        JsonObject queueResponse = sendJsonRequest(HttpRequest.newBuilder(QUEUE_URI)
            .header("Authorization", "Bearer " + normalizedApiKey)
            .header("User-Agent", USER_AGENT)
            .header("Content-Type", requestBody.contentType())
            .timeout(Duration.ofSeconds(20))
            .POST(HttpRequest.BodyPublishers.ofByteArray(requestBody.bytesForUpload()))
            .build());
        String textureUrl = readTextureUrl(queueResponse);
        if (textureUrl != null) {
            return textureUrl;
        }

        String jobId = readJobId(queueResponse);
        if (jobId == null) {
            throw new IOException("MineSkin queue response did not include a job id");
        }

        queuedJobListener.accept(jobId);
        return waitForSkinUrlWithApiKey(normalizedApiKey, jobId);
    }

    String waitForSkinUrl(String apiKey, String jobId) throws IOException, InterruptedException {
        String normalizedApiKey = normalizeApiKey(apiKey);
        if (normalizedApiKey == null) {
            throw new IOException("MineSkin API key is missing");
        }
        return waitForSkinUrlWithApiKey(normalizedApiKey, jobId);
    }

    static boolean hasApiKey(String apiKey) {
        return normalizeApiKey(apiKey) != null;
    }

    static MultipartBody createUploadBody(byte[] pngBytes, boolean slimModel) {
        Objects.requireNonNull(pngBytes, "pngBytes");
        String boundary = "emote-" + UUID.randomUUID();
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        writePartHeader(body, boundary, "file", "skin.png", "image/png");
        body.writeBytes(pngBytes);
        writeAscii(body, "\r\n");
        writeTextPart(body, boundary, "variant", slimModel ? "slim" : "classic");
        writeTextPart(body, boundary, "visibility", "unlisted");
        writeAscii(body, "--" + boundary + "--\r\n");
        return new MultipartBody(body.toByteArray(), "multipart/form-data; boundary=" + boundary);
    }

    private static void writeTextPart(ByteArrayOutputStream body, String boundary, String name, String value) {
        writePartHeader(body, boundary, name, null, "text/plain; charset=UTF-8");
        writeAscii(body, value + "\r\n");
    }

    private static void writePartHeader(
        ByteArrayOutputStream body,
        String boundary,
        String name,
        String fileName,
        String contentType
    ) {
        writeAscii(body, "--" + boundary + "\r\n");
        String disposition = "Content-Disposition: form-data; name=\"" + name + "\"";
        if (fileName != null) {
            disposition += "; filename=\"" + fileName + "\"";
        }
        writeAscii(body, disposition + "\r\n");
        writeAscii(body, "Content-Type: " + contentType + "\r\n\r\n");
    }

    private static void writeAscii(ByteArrayOutputStream body, String value) {
        body.writeBytes(value.getBytes(StandardCharsets.US_ASCII));
    }

    record MultipartBody(byte[] bytes, String contentType) {
        MultipartBody {
            Objects.requireNonNull(bytes, "bytes");
            Objects.requireNonNull(contentType, "contentType");
        }

        @Override
        public byte[] bytes() {
            return this.bytes.clone();
        }

        private byte[] bytesForUpload() {
            return this.bytes;
        }
    }

    private String waitForSkinUrlWithApiKey(String apiKey, String jobId) throws IOException, InterruptedException {
        if (jobId == null || jobId.isBlank()) {
            throw new IOException("MineSkin job id is missing");
        }

        long deadline = System.nanoTime() + Duration.ofMillis(JOB_TIMEOUT_MILLIS).toNanos();
        while (System.nanoTime() < deadline) {
            Thread.sleep(this.jobPollIntervalMillis);

            JsonObject jobResponse = sendJsonRequest(HttpRequest.newBuilder(QUEUE_URI.resolve("/v2/queue/" + jobId))
                .header("Authorization", "Bearer " + apiKey)
                .header("User-Agent", USER_AGENT)
                .timeout(Duration.ofSeconds(20))
                .GET()
                .build());
            String textureUrl = readTextureUrl(jobResponse);
            if (textureUrl != null) {
                return textureUrl;
            }

            String jobStatus = readJobStatus(jobResponse);
            if ("failed".equalsIgnoreCase(jobStatus)) {
                throw new JobFailedException(
                    readErrorMessage(jobResponse, "MineSkin job failed"),
                    Math.max(this.jobPollIntervalMillis, readRelativeDelay(findObject(findObject(jobResponse, "rateLimit"), "next")))
                );
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

        if (response.statusCode() == 429) {
            throw new RateLimitException(
                readErrorMessage(responseBody, "MineSkin request rate limit exceeded"),
                readRetryDelayMillis(response, responseBody)
            );
        }

        throw new IOException(readErrorMessage(responseBody, "MineSkin request failed: " + response.statusCode()));
    }

    long readRetryDelayMillis(HttpResponse<?> response, JsonObject responseBody) {
        long headerDelay = response.headers().firstValue("Retry-After")
            .flatMap(value -> {
                try {
                    return java.util.Optional.of(Long.parseLong(value.trim()) * 1000L);
                } catch (NumberFormatException ignored) {
                    return java.util.Optional.empty();
                }
            })
            .orElse(0L);
        JsonObject rateLimit = findObject(responseBody, "rateLimit");
        JsonObject next = findObject(rateLimit, "next");
        long bodyDelay = readRelativeDelay(next);
        return Math.max(this.jobPollIntervalMillis, Math.max(headerDelay, bodyDelay));
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
        for (String field : ERROR_MESSAGE_FIELDS) {
            JsonArray entries = findArray(responseBody, field);
            if (entries == null) {
                continue;
            }
            for (JsonElement entry : entries) {
                String message = entry.isJsonObject()
                    ? readString(entry.getAsJsonObject(), "message")
                    : null;
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

    private long readRelativeDelay(JsonObject parent) {
        if (parent == null) {
            return 0L;
        }
        JsonElement element = parent.get("relative");
        if (element == null || element.isJsonNull()) {
            return 0L;
        }
        try {
            return Math.max(0L, element.getAsLong());
        } catch (RuntimeException ignored) {
            return 0L;
        }
    }

    private static String normalizeApiKey(String apiKey) {
        if (apiKey == null) {
            return null;
        }

        String normalizedApiKey = apiKey.trim();
        if (normalizedApiKey.isEmpty()) {
            return null;
        }

        if (normalizedApiKey.equalsIgnoreCase("Bearer")) {
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

    static final class JobFailedException extends IOException {
        private final long retryDelayMillis;

        JobFailedException(String message) {
            this(message, 0L);
        }

        JobFailedException(String message, long retryDelayMillis) {
            super(message);
            this.retryDelayMillis = Math.max(0L, retryDelayMillis);
        }

        boolean isRateLimited() {
            String message = getMessage();
            if (message == null) {
                return false;
            }
            String normalized = message.toLowerCase(java.util.Locale.ROOT);
            return normalized.contains("rate_limit")
                || normalized.contains("rate limit")
                || normalized.contains("too many requests");
        }

        long retryDelayMillis() {
            return this.retryDelayMillis;
        }
    }

    static final class RateLimitException extends IOException {
        private final long retryDelayMillis;

        RateLimitException(String message, long retryDelayMillis) {
            super(message);
            this.retryDelayMillis = Math.max(0L, retryDelayMillis);
        }

        long retryDelayMillis() {
            return this.retryDelayMillis;
        }
    }
}
