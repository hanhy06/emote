package io.github.hanhy06.emote.skin;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLSession;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MineSkinApiClientTest {
    @Test
    void rateLimitDelayKeepsHourlyResetDuration() {
        MineSkinApiClient client = new MineSkinApiClient();

        long delay = client.readRetryDelayMillis(
            new StubHttpResponse(HttpHeaders.of(java.util.Map.of(), (name, value) -> true)),
            JsonParser.parseString("{\"rateLimit\":{\"next\":{\"relative\":3600000}}}").getAsJsonObject()
        );

        assertEquals(3_600_000L, delay);
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
