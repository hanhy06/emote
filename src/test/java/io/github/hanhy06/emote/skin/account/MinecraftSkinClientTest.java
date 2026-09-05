package io.github.hanhy06.emote.skin.account;

import com.sun.net.httpserver.HttpServer;
import io.github.hanhy06.emote.skin.account.MinecraftAccountClient.MinecraftSession;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class MinecraftSkinClientTest {
    @Test void uploadUsesAccountAndWaitsForItsNewActiveTexture() throws Exception {
        UUID uuid = UUID.randomUUID();
        List<String> requests = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger gets = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/profile", exchange -> {
            requests.add(exchange.getRequestMethod() + " " + exchange.getRequestHeaders().getFirst("Authorization") + " "
                + new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.ISO_8859_1));
            String hash = exchange.getRequestMethod().equals("GET") && gets.getAndIncrement() == 0 ? "aaa" : "bbb";
            byte[] body = profile(uuid, hash).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            var client = new MinecraftSkinClient(HttpClient.newHttpClient(), URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/profile"), 1);
            assertEquals("https://textures.minecraft.net/texture/bbb", client.upload(new MinecraftSession(uuid, "Alpha", "test-access", Long.MAX_VALUE), new byte[]{1, 2, 3}, true));
            assertEquals(3, requests.size());
            assertTrue(requests.getFirst().startsWith("POST Bearer test-access"));
            assertTrue(requests.getFirst().contains("name=\"file\"; filename=\"skin.png\""));
            assertTrue(requests.getFirst().contains("\r\nslim\r\n"));
        } finally {
            server.stop(0);
        }
    }

    @Test void rateLimitIsReportedWithoutLeakingResponseBody() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/profile", exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().add("Retry-After", "3600");
            byte[] body = "secret-server-response".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(429, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            var client = new MinecraftSkinClient(HttpClient.newHttpClient(), URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/profile"), 1);
            var error = assertThrows(MinecraftSkinClient.SkinRequestException.class,
                () -> client.upload(new MinecraftSession(UUID.randomUUID(), "Alpha", "test-access", Long.MAX_VALUE), new byte[]{1}, false));
            assertEquals(429, error.status);
            assertEquals(3_600_000, error.retryDelayMillis);
            assertFalse(error.getMessage().contains("secret"));
        } finally {
            server.stop(0);
        }
    }

    @Test void mismatchedAccountCannotSupplyATexture() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/profile", exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] body = profile(UUID.randomUUID(), "aaa").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            var client = new MinecraftSkinClient(HttpClient.newHttpClient(), URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/profile"), 1);
            assertThrows(IOException.class, () -> client.upload(new MinecraftSession(UUID.randomUUID(), "Alpha", "test", Long.MAX_VALUE), new byte[]{1}, false));
        } finally {
            server.stop(0);
        }
    }

    private static String profile(UUID uuid, String hash) {
        return "{\"id\":\"" + uuid.toString().replace("-", "") + "\",\"skins\":[{\"state\":\"ACTIVE\",\"url\":\"http://textures.minecraft.net/texture/" + hash + "\"}]}";
    }
}
