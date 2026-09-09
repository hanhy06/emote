package io.github.hanhy06.emote.skin.account;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.hanhy06.emote.skin.account.MinecraftAccountClient.MinecraftSession;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

public class MinecraftSkinClient {
    private final HttpClient http;
    private final URI profileUri;
    private final long pollMillis;

    public MinecraftSkinClient() {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(),
            URI.create("https://api.minecraftservices.com/minecraft/profile"), 2000);
    }

    MinecraftSkinClient(HttpClient http, URI profileUri, long pollMillis) {
        this.http = http;
        this.profileUri = profileUri;
        this.pollMillis = pollMillis;
    }

    public String upload(MinecraftSession session, byte[] png, boolean slim) throws IOException, InterruptedException {
        String boundary = "emote-" + UUID.randomUUID();
        byte[] prefix = ("--" + boundary + "\r\nContent-Disposition: form-data; name=\"variant\"\r\n\r\n"
            + (slim ? "slim" : "classic") + "\r\n--" + boundary
            + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"skin.png\"\r\nContent-Type: image/png\r\n\r\n").getBytes(StandardCharsets.UTF_8);
        byte[] suffix = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);
        HttpRequest request = HttpRequest.newBuilder(URI.create(this.profileUri + "/skins"))
            .timeout(Duration.ofSeconds(30))
            .header("Authorization", "Bearer " + session.accessToken())
            .header("Content-Type", "multipart/form-data; boundary=" + boundary)
            .POST(HttpRequest.BodyPublishers.concat(HttpRequest.BodyPublishers.ofByteArray(prefix),
                HttpRequest.BodyPublishers.ofByteArray(png), HttpRequest.BodyPublishers.ofByteArray(suffix))).build();
        String expected = activeTexture(send(request), session.uuid());
        for (int attempt = 0; attempt < 10; attempt++) {
            JsonObject profile = send(HttpRequest.newBuilder(this.profileUri).timeout(Duration.ofSeconds(15))
                .header("Authorization", "Bearer " + session.accessToken()).GET().build());
            if (expected.equals(activeTexture(profile, session.uuid()))) return expected;
            Thread.sleep(this.pollMillis);
        }
        throw new IOException("Uploaded skin did not become active before the verification timeout");
    }

    public BufferedImage downloadSkin(String textureUrl) throws IOException, InterruptedException {
        HttpResponse<InputStream> response = this.http.send(HttpRequest.newBuilder(URI.create(textureUrl))
            .timeout(Duration.ofSeconds(10)).GET().build(), HttpResponse.BodyHandlers.ofInputStream());
        byte[] bytes;
        try (InputStream input = response.body()) {
            if (response.statusCode() / 100 != 2) throw new IOException("Cannot download source skin (HTTP " + response.statusCode() + ")");
            bytes = input.readNBytes(1_048_577);
            if (bytes.length > 1_048_576) throw new IOException("Source skin exceeds maximum size");
        }
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
        if (image == null) throw new IOException("Invalid source skin PNG");
        return image;
    }

    private JsonObject send(HttpRequest request) throws IOException, InterruptedException {
        HttpResponse<String> response = this.http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) {
            throw new SkinRequestException(response.statusCode(), retryDelay(response.headers().firstValue("Retry-After").orElse("120")));
        }
        try {
            return JsonParser.parseString(response.body()).getAsJsonObject();
        } catch (RuntimeException exception) {
            throw new IOException("Minecraft returned an invalid skin response");
        }
    }

    private static String activeTexture(JsonObject profile, UUID expectedUuid) throws IOException {
        try {
            if (!profile.get("id").getAsString().replace("-", "").equalsIgnoreCase(expectedUuid.toString().replace("-", ""))) {
                throw new IOException("Minecraft returned a different account profile");
            }
            for (JsonElement element : profile.getAsJsonArray("skins")) {
                JsonObject skin = element.getAsJsonObject();
                if (!"ACTIVE".equals(skin.get("state").getAsString())) continue;
                URI uri = URI.create(skin.get("url").getAsString());
                if (("https".equals(uri.getScheme()) || "http".equals(uri.getScheme()))
                    && "textures.minecraft.net".equals(uri.getHost()) && uri.getRawUserInfo() == null
                    && uri.getPort() == -1 && uri.getQuery() == null && uri.getFragment() == null
                    && uri.getPath().matches("/texture/[0-9a-fA-F]+")) {
                    return "https://textures.minecraft.net" + uri.getPath();
                }
                throw new IOException("Minecraft returned an invalid texture URL");
            }
        } catch (RuntimeException exception) {
            throw new IOException("Minecraft returned an invalid skin profile");
        }
        throw new IOException("Minecraft profile has no active skin");
    }

    static long retryDelay(String header) {
        try {
            return Math.max(1000, Math.multiplyExact(Long.parseLong(header), 1000));
        } catch (RuntimeException ignored) {
            try {
                return Math.max(1000, ZonedDateTime.parse(header, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() - System.currentTimeMillis());
            } catch (RuntimeException invalidDate) {
                return 120_000;
            }
        }
    }

    public static final class SkinRequestException extends IOException {
        public final int status;
        public final long retryDelayMillis;

        public SkinRequestException(int status, long retryDelayMillis) {
            super("Minecraft skin request failed (HTTP " + status + ")");
            this.status = status;
            this.retryDelayMillis = retryDelayMillis;
        }
    }
}
