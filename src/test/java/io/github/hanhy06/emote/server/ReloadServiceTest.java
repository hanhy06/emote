package io.github.hanhy06.emote.server;

import io.github.hanhy06.emote.content.loader.AnimationJsonParser;
import io.github.hanhy06.emote.content.loader.EmoteDirectoryLoader;
import io.github.hanhy06.emote.config.ConfigManager;
import io.github.hanhy06.emote.content.EmoteCatalog;
import io.github.hanhy06.emote.content.LoadedAnimation;
import io.github.hanhy06.emote.api.animation.EmoteAnimation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class ReloadServiceTest {
    @Test
    void keepsDisabledAnimationsLoadedInTheRegistry(@TempDir Path tempDir) throws Exception {
        ConfigManager configManager = new ConfigManager(tempDir);
        Files.createDirectories(tempDir.resolve("emote/animations"));
        configManager.configure();
        Files.writeString(tempDir.resolve("emote/emotes.json"), """
            {"schema_version":2,"disabled":["example:disabled"],"permissions":[]}
            """);
        Files.writeString(configManager.getAnimationDirectory().resolve("disabled.json"), """
            {
              "type":"animation",
              "schema_version":4,
              "id":"example:disabled",
              "metadata":{"name":"Disabled","description":""},
              "settings":{
                "standalone":true,
                "cooldown":"0t",
                "player":{"hidden":true,"stop_conditions":{"movement_distance":0.1,"jump":true,"submerge":true,"ride":true,"damage":true,"attack":true,"game_mode_change":true}},
                "playback":{"mode":"once","loop_delay":"0t"}
              },
              "nodes":{"root":{"type":"anchor","space":"scene","transform":{"position":[0,0,0],"rotation":[0,0,0],"scale":[1,1,1]}}},
              "timeline":{"duration":"1t","tracks":{}}
            }
            """);
        EmoteCatalog registry = new EmoteCatalog();
        var loaded = new AnimationJsonParser().parse(configManager.getAnimationDirectory().resolve("disabled.json"));
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
            null
        );

        service.loadOnServerStart();

        assertNotNull(registry.find("example:disabled"));
        assertNull(registry.find("example:invalid"));
    }
}
