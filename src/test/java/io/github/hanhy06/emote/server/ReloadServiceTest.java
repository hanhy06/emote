package io.github.hanhy06.emote.server;

import io.github.hanhy06.emote.content.loader.AnimationJsonParser;
import io.github.hanhy06.emote.content.loader.EmoteDirectoryLoader;
import io.github.hanhy06.emote.config.ConfigManager;
import io.github.hanhy06.emote.content.EmoteCatalog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertNotNull;

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
        ReloadService service = new ReloadService(
            configManager,
            registry,
            ignored -> new EmoteDirectoryLoader.LoadResult(java.util.List.of(loaded), java.util.List.of(), 1),
            null,
            null
        );

        service.loadOnServerStart();

        assertNotNull(registry.find("example:disabled"));
    }
}
