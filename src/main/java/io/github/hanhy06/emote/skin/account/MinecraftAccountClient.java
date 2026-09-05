package io.github.hanhy06.emote.skin.account;

import com.google.gson.*;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/** Microsoft device authorization followed by Xbox Live, XSTS and Minecraft authentication. */
public class MinecraftAccountClient {
    private static final String OAUTH = "https://login.microsoftonline.com/consumers/oauth2/v2.0/";
    private static final String SCOPE = "XboxLive.signin offline_access";
    private final HttpClient http;
    private final String clientId;

    public MinecraftAccountClient(String clientId) {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(), clientId);
    }

    MinecraftAccountClient(HttpClient http, String clientId) {
        this.http = http;
        this.clientId = clientId == null ? "" : clientId.trim();
    }

    public DeviceLogin startLogin() throws IOException, InterruptedException {
        if (this.clientId.isBlank()) throw new IOException("Set EMOTE_MICROSOFT_CLIENT_ID to your registered application ID");
        JsonObject response = form("devicecode", Map.of("client_id", this.clientId, "scope", SCOPE));
        URI uri = URI.create(response.get("verification_uri").getAsString());
        if (!"https".equalsIgnoreCase(uri.getScheme())) throw new IOException("Invalid Microsoft verification URL");
        return new DeviceLogin(response.get("device_code").getAsString(), response.get("user_code").getAsString(), uri,
            System.currentTimeMillis() + response.get("expires_in").getAsLong() * 1000,
            Math.max(1, response.get("interval").getAsInt()));
    }

    public MicrosoftTokens poll(DeviceLogin login) throws IOException, InterruptedException {
        JsonObject response = form("token", Map.of("client_id", this.clientId,
            "grant_type", "urn:ietf:params:oauth:grant-type:device_code", "device_code", login.deviceCode()));
        return tokens(response);
    }

    public MicrosoftTokens refresh(String refreshToken) throws IOException, InterruptedException {
        if (this.clientId.isBlank()) throw new IOException("Set EMOTE_MICROSOFT_CLIENT_ID to your registered application ID");
        JsonObject response = form("token", Map.of("client_id", this.clientId, "grant_type", "refresh_token",
            "refresh_token", refreshToken, "scope", SCOPE));
        return new MicrosoftTokens(response.get("access_token").getAsString(),
            response.has("refresh_token") ? response.get("refresh_token").getAsString() : refreshToken);
    }

    public MinecraftSession authenticate(MicrosoftTokens tokens) throws IOException, InterruptedException {
        JsonObject userRequest = new JsonObject();
        JsonObject userProperties = new JsonObject();
        userProperties.addProperty("AuthMethod", "RPS");
        userProperties.addProperty("SiteName", "user.auth.xboxlive.com");
        userProperties.addProperty("RpsTicket", "d=" + tokens.accessToken());
        userRequest.add("Properties", userProperties);
        userRequest.addProperty("RelyingParty", "http://auth.xboxlive.com");
        userRequest.addProperty("TokenType", "JWT");
        JsonObject user = post("https://user.auth.xboxlive.com/user/authenticate", userRequest);

        JsonObject xstsRequest = new JsonObject();
        JsonObject xstsProperties = new JsonObject();
        JsonArray userTokens = new JsonArray();
        userTokens.add(user.get("Token").getAsString());
        xstsProperties.addProperty("SandboxId", "RETAIL");
        xstsProperties.add("UserTokens", userTokens);
        xstsRequest.add("Properties", xstsProperties);
        xstsRequest.addProperty("RelyingParty", "rp://api.minecraftservices.com/");
        xstsRequest.addProperty("TokenType", "JWT");
        JsonObject xsts = post("https://xsts.auth.xboxlive.com/xsts/authorize", xstsRequest);
        String userHash = xsts.getAsJsonObject("DisplayClaims").getAsJsonArray("xui").get(0).getAsJsonObject().get("uhs").getAsString();
        JsonObject minecraftRequest = new JsonObject();
        minecraftRequest.addProperty("identityToken", "XBL3.0 x=" + userHash + ";" + xsts.get("Token").getAsString());
        JsonObject minecraft = post("https://api.minecraftservices.com/authentication/login_with_xbox", minecraftRequest);
        String accessToken = minecraft.get("access_token").getAsString();
        JsonObject profile = send(HttpRequest.newBuilder(URI.create("https://api.minecraftservices.com/minecraft/profile"))
            .header("Authorization", "Bearer " + accessToken).GET());
        String id = profile.get("id").getAsString();
        UUID uuid = UUID.fromString(id.replaceFirst("(\\w{8})(\\w{4})(\\w{4})(\\w{4})(\\w{12})", "$1-$2-$3-$4-$5"));
        return new MinecraftSession(uuid, profile.get("name").getAsString(), accessToken,
            System.currentTimeMillis() + minecraft.get("expires_in").getAsLong() * 1000);
    }

    private MicrosoftTokens tokens(JsonObject response) throws IOException {
        if (!response.has("refresh_token")) throw new IOException("Microsoft did not issue a refresh token");
        return new MicrosoftTokens(response.get("access_token").getAsString(), response.get("refresh_token").getAsString());
    }

    private JsonObject form(String path, Map<String, String> values) throws IOException, InterruptedException {
        String body = values.entrySet().stream().map(entry -> URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8)
            + "=" + URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8)).collect(Collectors.joining("&"));
        return send(HttpRequest.newBuilder(URI.create(OAUTH + path)).header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(body)));
    }

    private JsonObject post(String url, JsonObject body) throws IOException, InterruptedException {
        return send(HttpRequest.newBuilder(URI.create(url)).header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body.toString())));
    }

    private JsonObject send(HttpRequest.Builder request) throws IOException, InterruptedException {
        HttpResponse<String> response = this.http.send(request.timeout(Duration.ofSeconds(30)).header("Accept", "application/json").build(), HttpResponse.BodyHandlers.ofString());
        JsonObject json;
        try {
            json = JsonParser.parseString(response.body()).getAsJsonObject();
        } catch (RuntimeException exception) {
            throw new IOException("Authentication returned an invalid response (HTTP " + response.statusCode() + ")");
        }
        if (response.statusCode() / 100 != 2) {
            String error = json.has("error") && json.get("error").isJsonPrimitive() ? json.get("error").getAsString() : "http_error";
            // Never propagate response bodies, tokens or provider error descriptions to logs/chat.
            throw new AuthenticationException(response.statusCode(), switch (error) {
                case "authorization_pending", "slow_down", "authorization_declined", "expired_token", "invalid_grant" -> error;
                default -> "http_error";
            });
        }
        return json;
    }

    public record DeviceLogin(String deviceCode, String userCode, URI verificationUri, long expiresAt, int intervalSeconds) {
        @Override public String toString() { return "DeviceLogin[redacted]"; }
    }

    public record MicrosoftTokens(String accessToken, String refreshToken) {
        @Override public String toString() { return "MicrosoftTokens[redacted]"; }
    }

    public record MinecraftSession(UUID uuid, String name, String accessToken, long expiresAt) {
        @Override public String toString() { return "MinecraftSession[" + uuid + "]"; }
    }

    public static final class AuthenticationException extends IOException {
        public final int status;
        public final String code;

        AuthenticationException(int status, String code) {
            super("Authentication failed (HTTP " + status + ", " + code + ")");
            this.status = status;
            this.code = code;
        }
    }
}
