package io.github.hanhy06.emote.skin.account;

import io.github.hanhy06.emote.skin.account.MinecraftAccountClient.*;
import org.junit.jupiter.api.Test;

import javax.net.ssl.*;
import java.io.ByteArrayOutputStream;
import java.net.*;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.Flow;

import static org.junit.jupiter.api.Assertions.*;

class MinecraftAccountClientTest {
    @Test void deviceLoginAndXboxExchangeUseTheRegisteredAppAndAuthenticatedProfile() throws Exception {
        UUID uuid = UUID.randomUUID();
        var http = new StubClient(
            new Reply(200, "{\"device_code\":\"device-secret\",\"user_code\":\"ABCD\",\"verification_uri\":\"https://microsoft.com/devicelogin\",\"expires_in\":900,\"interval\":5}"),
            new Reply(400, "{\"error\":\"authorization_pending\"}"),
            new Reply(200, "{\"access_token\":\"msa-access\",\"refresh_token\":\"msa-refresh\"}"),
            new Reply(200, "{\"Token\":\"xbox-user\"}"),
            new Reply(200, "{\"Token\":\"xsts-token\",\"DisplayClaims\":{\"xui\":[{\"uhs\":\"user-hash\"}]}}"),
            new Reply(200, "{\"access_token\":\"minecraft-access\",\"expires_in\":3600}"),
            new Reply(200, "{\"id\":\"" + uuid.toString().replace("-", "") + "\",\"name\":\"Baker\"}")
        );
        var client = new MinecraftAccountClient(http, "own-app-id");
        DeviceLogin login = client.startLogin();
        assertEquals("ABCD", login.userCode());
        assertEquals(5, login.intervalSeconds());
        var pending = assertThrows(AuthenticationException.class, () -> client.poll(login));
        assertEquals("authorization_pending", pending.code);
        MicrosoftTokens tokens = client.poll(login);
        MinecraftSession session = client.authenticate(tokens);
        assertEquals(uuid, session.uuid());
        assertEquals("Baker", session.name());
        assertEquals("minecraft-access", session.accessToken());
        assertTrue(body(http.requests.get(0)).contains("client_id=own-app-id"));
        assertTrue(body(http.requests.get(0)).contains("XboxLive.signin+offline_access"));
        assertTrue(body(http.requests.get(3)).contains("d=msa-access"));
        assertTrue(body(http.requests.get(4)).contains("rp://api.minecraftservices.com/"));
        assertTrue(body(http.requests.get(5)).contains("XBL3.0 x=user-hash;xsts-token"));
        assertEquals("Bearer minecraft-access", http.requests.get(6).headers().firstValue("Authorization").orElseThrow());
        assertEquals("https://api.minecraftservices.com/minecraft/profile", http.requests.get(6).uri().toString());
        assertFalse(tokens.toString().contains("msa-refresh"));
        assertFalse(login.toString().contains("device-secret"));
        assertFalse(session.toString().contains("minecraft-access"));
    }

    @Test void refreshKeepsTokenWhenNotRotatedAndErrorsDoNotExposeProviderDetails() throws Exception {
        var http = new StubClient(new Reply(200, "{\"access_token\":\"new-access\"}"),
            new Reply(400, "{\"error\":\"invalid_grant\",\"error_description\":\"sensitive provider detail\"}"));
        var client = new MinecraftAccountClient(http, "own-app-id");
        assertEquals("old-refresh", client.refresh("old-refresh").refreshToken());
        var error = assertThrows(AuthenticationException.class, () -> client.refresh("old-refresh"));
        assertEquals("invalid_grant", error.code);
        assertFalse(error.getMessage().contains("sensitive"));
        assertFalse(error.getMessage().contains("old-refresh"));
    }

    @Test void missingOrBlankClientIdUsesEmoteApplicationForLoginAndRefresh() throws Exception {
        for (String configuredId : Arrays.asList(null, "", "  ")) {
            var http = new StubClient(
                new Reply(200, "{\"device_code\":\"device-secret\",\"user_code\":\"ABCD\",\"verification_uri\":\"https://microsoft.com/devicelogin\",\"expires_in\":900,\"interval\":5}"),
                new Reply(200, "{\"access_token\":\"new-access\"}")
            );
            var client = new MinecraftAccountClient(http, configuredId);
            client.startLogin();
            client.refresh("test-refresh");
            for (HttpRequest request : http.requests) {
                assertTrue(body(request).contains("client_id=38fe1d33-748d-4f65-926c-82022799df8e"));
            }
        }
    }

    private static String body(HttpRequest request) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        request.bodyPublisher().orElseThrow().subscribe(new Flow.Subscriber<>() {
            @Override public void onSubscribe(Flow.Subscription subscription) { subscription.request(Long.MAX_VALUE); }
            @Override public void onNext(ByteBuffer buffer) {
                byte[] part = new byte[buffer.remaining()];
                buffer.get(part);
                bytes.writeBytes(part);
            }
            @Override public void onError(Throwable throwable) { throw new AssertionError(throwable); }
            @Override public void onComplete() {}
        });
        return bytes.toString(StandardCharsets.UTF_8);
    }

    private record Reply(int status, String body) {}

    private static final class StubClient extends HttpClient {
        final Queue<Reply> replies;
        final List<HttpRequest> requests = new ArrayList<>();
        StubClient(Reply... replies) { this.replies = new ArrayDeque<>(List.of(replies)); }
        @Override @SuppressWarnings("unchecked") public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler) {
            this.requests.add(request);
            Reply reply = this.replies.remove();
            return new HttpResponse<>() {
                @Override public int statusCode() { return reply.status; }
                @Override public HttpRequest request() { return request; }
                @Override public Optional<HttpResponse<T>> previousResponse() { return Optional.empty(); }
                @Override public HttpHeaders headers() { return HttpHeaders.of(Map.of(), (a, b) -> true); }
                @Override public T body() { return (T) reply.body; }
                @Override public Optional<SSLSession> sslSession() { return Optional.empty(); }
                @Override public URI uri() { return request.uri(); }
                @Override public Version version() { return Version.HTTP_1_1; }
            };
        }
        @Override public Optional<CookieHandler> cookieHandler() { return Optional.empty(); }
        @Override public Optional<Duration> connectTimeout() { return Optional.empty(); }
        @Override public Redirect followRedirects() { return Redirect.NEVER; }
        @Override public Optional<ProxySelector> proxy() { return Optional.empty(); }
        @Override public SSLContext sslContext() { throw new UnsupportedOperationException(); }
        @Override public SSLParameters sslParameters() { return new SSLParameters(); }
        @Override public Optional<Authenticator> authenticator() { return Optional.empty(); }
        @Override public Version version() { return Version.HTTP_1_1; }
        @Override public Optional<Executor> executor() { return Optional.empty(); }
        @Override public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> handler) { throw new UnsupportedOperationException(); }
        @Override public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> handler, HttpResponse.PushPromiseHandler<T> push) { throw new UnsupportedOperationException(); }
    }
}
