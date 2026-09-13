package io.github.hanhy06.emote.server;

import io.github.hanhy06.emote.EmoteMod;
import net.minecraft.server.MinecraftServer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ServerConfigTest {
    private static final long SERVER_TIMEOUT_SECONDS = 60;
    private static Path runDirectory;
    private static Path configDirectory;
    private static Path serverLog;
    private static Process server;
    private static long deadline;

    @BeforeAll
    static void deleteConfigAndStartServer(@TempDir Path tempDir) throws Exception {
        runDirectory = tempDir;
        configDirectory = runDirectory.resolve("config/emote");
        serverLog = runDirectory.resolve("server-config.log");

        Path eula = runDirectory.resolve("eula.txt");
        if (Files.notExists(eula)) {
            Files.writeString(eula, "eula=true\n", StandardCharsets.UTF_8);
        }
        assertTrue(Files.readString(eula).matches("(?s).*\\beula=true\\b.*"), "run/eula.txt must accept the Minecraft EULA");
        Files.writeString(runDirectory.resolve("server.properties"), "server-port=0\n", StandardCharsets.UTF_8);
        assertFalse(Files.exists(configDirectory));

        Path mainClasses = Path.of(EmoteMod.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        Path mainResources = Path.of("build/resources/main").toAbsolutePath();
        Path minecraftJar = Path.of(MinecraftServer.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        var command = List.of(
            Path.of(System.getProperty("java.home"), "bin", executable).toString(),
            "-Dfabric.development=true",
            "-Dfabric.gameJarPath=" + minecraftJar,
            "-Dfabric.classPathGroups=" + mainClasses + File.pathSeparator + mainResources,
            "-Dfile.encoding=UTF-8",
            "-cp", System.getProperty("java.class.path"),
            "net.fabricmc.loader.impl.launch.knot.KnotServer",
            "--world", "TEST", "nogui"
        );
        server = new ProcessBuilder(command).directory(runDirectory.toFile())
            .redirectErrorStream(true).redirectOutput(serverLog.toFile()).start();
        deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(SERVER_TIMEOUT_SECONDS);
        server.onExit().orTimeout(SERVER_TIMEOUT_SECONDS, TimeUnit.SECONDS).exceptionally(exception -> {
            server.destroyForcibly();
            return server;
        });
    }

    @Test
    @Order(1)
    void copiesAndLoadsBundledEmotesAfterConfigDeletion() throws Exception {
        awaitLoading("Loaded");
        assertTrue(Files.isRegularFile(configDirectory.resolve("config.json")));
        assertTrue(Files.isRegularFile(configDirectory.resolve("emotes.json")));
        Path samples = Path.of("docs/sample");
        try (var paths = Files.walk(samples)) {
            for (Path sample : paths.filter(path -> path.toString().endsWith(".json")).toList()) {
                Path copied = configDirectory.resolve("emote").resolve(samples.relativize(sample));
                assertTrue(Files.isRegularFile(copied), "Missing bundled emote: " + sample);
                assertEquals(-1L, Files.mismatch(sample, copied), "Bundled emote differs: " + sample);
            }
        }
    }

    @Test
    @Order(2)
    void reloadsWithoutEmoteLoadingFailures() throws Exception {
        awaitLoading("Loaded");
        long reloadLogOffset = Files.size(serverLog);
        server.outputWriter(StandardCharsets.UTF_8).write("emote reload\n");
        server.outputWriter(StandardCharsets.UTF_8).flush();
        awaitLoading("Loaded", reloadLogOffset);
    }

    private static void awaitLoading(String action) throws Exception {
        awaitLoading(action, 0L);
    }

    private static void awaitLoading(String action, long logOffset) throws Exception {
        Pattern summary = Pattern.compile(action + " (\\d+) emotes from (\\d+) files");
        while (System.nanoTime() < deadline) {
            byte[] logBytes = Files.readAllBytes(serverLog);
            int offset = Math.toIntExact(Math.min(logOffset, logBytes.length));
            String log = new String(logBytes, StandardCharsets.UTF_8);
            String appendedLog = new String(logBytes, offset, logBytes.length - offset, StandardCharsets.UTF_8);
            var match = summary.matcher(appendedLog);
            if (match.find()) {
                long expected;
                try (var paths = Files.walk(Path.of("docs/sample"))) {
                    expected = paths.filter(path -> path.toString().endsWith(".json")).count();
                }
                assertTrue(expected > 0, "Bundled samples must not be empty");
                assertEquals(expected, Long.parseLong(match.group(2)), log);
                assertEquals(expected, Long.parseLong(match.group(1)), log);
                return;
            }
            assertTrue(server.isAlive(), "Server exited before " + action + ":\n" + log);
            Thread.sleep(100);
        }
        fail("Server timed out before " + action + ":\n" + new String(Files.readAllBytes(serverLog), StandardCharsets.UTF_8));
    }

    @AfterAll
    static void stopServer() throws Exception {
        if (server == null || !server.isAlive()) {
            return;
        }
        try {
            server.outputWriter(StandardCharsets.UTF_8).write("stop\n");
            server.outputWriter(StandardCharsets.UTF_8).flush();
            server.waitFor(5, TimeUnit.SECONDS);
        } finally {
            if (server.isAlive()) {
                server.destroyForcibly();
                server.waitFor(5, TimeUnit.SECONDS);
            }
        }
    }
}
