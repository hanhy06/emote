package io.github.hanhy06.emote.server;

import io.github.hanhy06.emote.api.animation.EmoteAnimation;
import io.github.hanhy06.emote.config.ConfigManager;
import io.github.hanhy06.emote.content.EmoteCatalog;
import io.github.hanhy06.emote.content.LoadedAnimation;
import io.github.hanhy06.emote.content.PlayableEmote;
import io.github.hanhy06.emote.content.loader.AnimationJsonParser;
import io.github.hanhy06.emote.content.loader.EmoteDirectoryLoader;
import io.github.hanhy06.emote.resource.PolymerResourcePackDistributor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static io.github.hanhy06.emote.content.PreparedAnimationFixture.create;

class ReloadServiceTest {
    @Test
    void keepsDisabledAnimationsLoadedInTheRegistry(@TempDir Path tempDir) throws Exception {
        ConfigManager configManager = new ConfigManager(tempDir);
        Files.createDirectories(tempDir.resolve("emote/emote"));
        configManager.configure();
        Files.writeString(tempDir.resolve("emote/emotes.json"), """
            {"schema_version":2,"disabled":["example:disabled"],"permissions":[]}
            """);
        Files.writeString(configManager.getEmoteDirectory().resolve("disabled.json"), """
            {
              "type":"animation",
              "schema_version":4,
              "id":"example:disabled",
              "metadata":{"name":"Disabled","description":""},
              "settings":{
                "standalone":true,
                "cooldown":"0t",
                "rotation_deadzone":50,
                "player":{"hidden":true,"stop_conditions":{"movement_distance":0.1,"jump":true,"submerge":true,"ride":true,"damage":true,"attack":true,"game_mode_change":true}},
                "playback":{"mode":"once","loop_delay":"0t"}
              },
              "nodes":{"root":{"type":"anchor","space":"scene","transform":{"position":[0,0,0],"rotation":[0,0,0],"scale":[1,1,1]}}},
              "timeline":{"duration":"1t","tracks":{}}
            }
            """);
        EmoteCatalog registry = new EmoteCatalog();
        var loaded = new AnimationJsonParser().parse(configManager.getEmoteDirectory().resolve("disabled.json"));
        var value = new EmoteAnimation.MolangValue("q.unsupported", "$.timeline.tracks.root.position[0].value[0]");
        var vector = new EmoteAnimation.VectorValue(value, value, value);
        var invalidAnimation = new EmoteAnimation(
            net.minecraft.resources.Identifier.parse("example:invalid"),
            loaded.animation().metadata(),
            loaded.animation().settings(),
            loaded.animation().molang(),
            loaded.animation().nodes(),
            new EmoteAnimation.Timeline(
                1,
                Map.of("root", new EmoteAnimation.NodeTracks(
                    List.of(new EmoteAnimation.VectorKeyframe(
                        0,
                        vector,
                        vector,
                        EmoteAnimation.Interpolation.LINEAR,
                        EmoteAnimation.Easing.LINEAR
                    )),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of()
                )),
                EmoteAnimation.Events.empty()
            )
        );
        var invalid = new LoadedAnimation(Path.of("invalid.json"), "invalid", invalidAnimation);
        ReloadService service = new ReloadService(
            configManager,
            registry,
            ignored -> new EmoteDirectoryLoader.LoadResult(List.of(invalid, loaded), List.of(), 2),
            null,
            null,
            null,
            () -> {}
        );

        service.loadOnServerStart();

        assertNotNull(registry.find("example:disabled"));
        assertNull(registry.find("example:invalid"));
    }

    @Test
    void preparesAndReplacesRegistryBeforeStoppingCurrentPlayback(@TempDir Path tempDir) {
        ConfigManager configManager = new ConfigManager(tempDir);
        configManager.configure();
        List<String> operations = new ArrayList<>();
        EmoteCatalog registry = new EmoteCatalog() {
            @Override
            public synchronized int replace(Collection<? extends PlayableEmote> emotes) {
                operations.add("replace");
                return super.replace(emotes);
            }
        };
        ReloadService service = new ReloadService(
            configManager,
            registry,
            ignored -> {
                operations.add("prepare");
                return new EmoteDirectoryLoader.LoadResult(List.of(), List.of(), 0);
            },
            ignored -> operations.add("stop"),
            () -> operations.add("sync"),
            () -> {
                operations.add("build");
                return PolymerResourcePackDistributor.BuildResult.BUILT;
            },
            () -> operations.add("push")
        );

        service.reloadFromCommand();

        assertEquals(List.of("prepare", "build", "replace", "stop", "push", "sync"), operations);
    }

    @Test
    void keepsCurrentRuntimeStateWhenDirectoryLoadingFails(@TempDir Path tempDir) {
        ConfigManager configManager = new ConfigManager(tempDir);
        configManager.configure();
        EmoteCatalog registry = new EmoteCatalog();
        registry.replace(List.of(create("example:current", "Current")));
        List<String> operations = new ArrayList<>();
        ReloadService service = new ReloadService(
            configManager,
            registry,
            ignored -> { throw new UncheckedIOException(new IOException("scan failed")); },
            ignored -> operations.add("stop"),
            () -> operations.add("sync"),
            () -> {
                operations.add("build");
                return PolymerResourcePackDistributor.BuildResult.BUILT;
            },
            () -> operations.add("push")
        );

        ReloadResult result = service.reloadFromCommand();

        assertFalse(result.successful());
        assertNotNull(registry.find("example:current"));
        assertEquals(List.of(), operations);
    }

    @Test
    void keepsCurrentRuntimeStateWhenResourcePackBuildFails(@TempDir Path tempDir) {
        ConfigManager configManager = new ConfigManager(tempDir);
        configManager.configure();
        EmoteCatalog registry = new EmoteCatalog();
        registry.replace(List.of(create("example:current", "Current")));
        List<String> operations = new ArrayList<>();
        ReloadService service = new ReloadService(
            configManager,
            registry,
            ignored -> {
                operations.add("prepare");
                return new EmoteDirectoryLoader.LoadResult(List.of(), List.of(), 0);
            },
            ignored -> operations.add("stop"),
            () -> operations.add("sync"),
            () -> {
                operations.add("build");
                return PolymerResourcePackDistributor.BuildResult.FAILED;
            },
            () -> operations.add("push")
        );

        ReloadResult result = service.reloadFromCommand();

        assertEquals(ReloadResult.Failure.RESOURCE_PACK_BUILD, result.failure());
        assertNotNull(registry.find("example:current"));
        assertEquals(List.of("prepare", "build"), operations);
    }
}
