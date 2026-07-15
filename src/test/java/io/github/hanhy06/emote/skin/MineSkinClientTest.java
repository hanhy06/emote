package io.github.hanhy06.emote.skin;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLSession;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class MineSkinClientTest {
    @Test
    void sharedHttpClientUsesSkinRequestConnectionSettings() {
        try (HttpClient httpClient = MineSkinClient.createHttpClient()) {
            assertEquals(Optional.of(Duration.ofSeconds(10)), httpClient.connectTimeout());
            assertEquals(HttpClient.Redirect.NORMAL, httpClient.followRedirects());
        }
    }

    @Test
    void apiKeyPresenceAcceptsRawAndBearerFormats() {
        assertTrue(MineSkinClient.hasApiKey("api-key"));
        assertTrue(MineSkinClient.hasApiKey("  Bearer api-key  "));
        assertTrue(MineSkinClient.hasApiKey("  bearer api-key  "));
    }

    @Test
    void apiKeyPresenceRejectsMissingValues() {
        assertFalse(MineSkinClient.hasApiKey(null));
        assertFalse(MineSkinClient.hasApiKey("  "));
        assertFalse(MineSkinClient.hasApiKey("Bearer   "));
    }

    @Test
    void uploadBodyUsesMultipartFileInsteadOfDataUrl() {
        byte[] pngBytes = new byte[]{0, 1, 2, (byte)255};

        MineSkinClient.MultipartBody body = MineSkinClient.createUploadBody(pngBytes, true);
        String bodyText = new String(body.bytes(), StandardCharsets.ISO_8859_1);

        assertTrue(body.contentType().startsWith("multipart/form-data; boundary=emote-"));
        assertTrue(bodyText.contains("name=\"file\"; filename=\"skin.png\""));
        assertTrue(bodyText.contains("Content-Type: image/png"));
        assertTrue(bodyText.contains("name=\"variant\""));
        assertTrue(bodyText.contains("\r\nslim\r\n"));
        assertTrue(bodyText.contains("name=\"visibility\""));
        assertTrue(bodyText.contains("\r\nunlisted\r\n"));
        assertFalse(bodyText.contains("data:image/png;base64"));
        assertTrue(contains(body.bytes(), pngBytes));
    }

    @Test
    void rateLimitDelayKeepsHourlyResetDuration() {
        MineSkinClient client = new MineSkinClient();

        long delay = client.readRetryDelayMillis(
            new StubHttpResponse(HttpHeaders.of(java.util.Map.of(), (ignoredName, ignoredValue) -> true)),
            JsonParser.parseString("{\"rateLimit\":{\"next\":{\"relative\":3600000}}}").getAsJsonObject()
        );

        assertEquals(3_600_000L, delay);
    }

    @Test
    void jobFailureRecognizesRateLimitMessages() {
        assertTrue(new MineSkinClient.JobFailedException(
            "[proxy_rate_limited] All proxies have rate-limit queue backlog"
        ).isRateLimited());
        assertTrue(new MineSkinClient.JobFailedException("Too many requests").isRateLimited());
        assertFalse(new MineSkinClient.JobFailedException("Invalid skin image").isRateLimited());
    }

    private boolean contains(byte[] body, byte[] expected) {
        for (int offset = 0; offset <= body.length - expected.length; offset++) {
            boolean matches = true;
            for (int index = 0; index < expected.length; index++) {
                if (body[offset + index] != expected[index]) {
                    matches = false;
                    break;
                }
            }
            if (matches) {
                return true;
            }
        }
        return false;
    }

    private record StubHttpResponse(HttpHeaders headers) implements HttpResponse<Void> {
        @Override
        public int statusCode() {
            return 429;
        }

        @Override
        public HttpRequest request() {
            return null;
        }

        @Override
        public Optional<HttpResponse<Void>> previousResponse() {
            return Optional.empty();
        }

        @Override
        public Void body() {
            return null;
        }

        @Override
        public Optional<SSLSession> sslSession() {
            return Optional.empty();
        }

        @Override
        public URI uri() {
            return URI.create("https://api.mineskin.org/v2/queue");
        }

        @Override
        public HttpClient.Version version() {
            return HttpClient.Version.HTTP_2;
        }
    }
}
