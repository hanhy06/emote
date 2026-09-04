package io.github.hanhy06.emote.server;

import io.github.hanhy06.emote.EmoteMod;
import net.minecraft.server.MinecraftServer;
import org.junit.jupiter.api.*;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ServerConfigTest {
    private static final Path RUN_DIRECTORY = Path.of("run").toAbsolutePath().normalize();
    private static final Path CONFIG_DIRECTORY = RUN_DIRECTORY.resolve("config/emote");
    private static final Path SERVER_LOG = Path.of("build/test-results/server-config.log").toAbsolutePath();
    private static Process server;
    private static long deadline;

    @BeforeAll
    static void deleteConfigAndStartServer() throws Exception {
        assertTrue(Files.readString(RUN_DIRECTORY.resolve("eula.txt")).matches("(?s).*\\beula=true\\b.*"), "run/eula.txt must already accept the Minecraft EULA");
        if (Files.exists(CONFIG_DIRECTORY)) {
            assertEquals(CONFIG_DIRECTORY, CONFIG_DIRECTORY.toRealPath(), "Refusing to delete a redirected config directory");
            try (var paths = Files.walk(CONFIG_DIRECTORY)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    Files.delete(path);
                }
            }
        }
        assertFalse(Files.exists(CONFIG_DIRECTORY));

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
        Files.createDirectories(SERVER_LOG.getParent());
        server = new ProcessBuilder(command).directory(RUN_DIRECTORY.toFile())
            .redirectErrorStream(true).redirectOutput(SERVER_LOG.toFile()).start();
        deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        server.onExit().orTimeout(60, TimeUnit.SECONDS).exceptionally(exception -> {
            server.destroyForcibly();
            return server;
        });
    }

    @Test
    @Order(1)
    void copiesAndLoadsBundledEmotesAfterConfigDeletion() throws Exception {
        awaitLoading("Loaded");
        assertTrue(Files.isRegularFile(CONFIG_DIRECTORY.resolve("config.json")));
        assertTrue(Files.isRegularFile(CONFIG_DIRECTORY.resolve("emotes.json")));
        Path samples = Path.of("docs/sample");
        try (var paths = Files.walk(samples)) {
            for (Path sample : paths.filter(path -> path.toString().endsWith(".json")).toList()) {
                Path copied = CONFIG_DIRECTORY.resolve("emote").resolve(samples.relativize(sample));
                assertTrue(Files.isRegularFile(copied), "Missing bundled emote: " + sample);
                assertEquals(-1L, Files.mismatch(sample, copied), "Bundled emote differs: " + sample);
            }
        }
    }

    @Test
    @Order(2)
    void reloadsWithoutEmoteLoadingFailures() throws Exception {
        awaitLoading("Loaded");
        server.outputWriter(StandardCharsets.UTF_8).write("emote reload\n");
        server.outputWriter(StandardCharsets.UTF_8).flush();
        awaitLoading("Reloaded");
    }

    private static void awaitLoading(String action) throws Exception {
        Pattern summary = Pattern.compile(action + " (\\d+) emotes from (\\d+) files");
        while (System.nanoTime() < deadline) {
            String log = Files.readString(SERVER_LOG);
            var match = summary.matcher(log);
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
        fail("Server timed out before " + action + ":\n" + Files.readString(SERVER_LOG));
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
