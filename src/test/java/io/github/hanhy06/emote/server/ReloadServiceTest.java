package io.github.hanhy06.emote.server;

import io.github.hanhy06.emote.animation.AnimationDirectoryLoader;
import io.github.hanhy06.emote.animation.AnimationJsonLoader;
import io.github.hanhy06.emote.config.ConfigManager;
import io.github.hanhy06.emote.emote.EmoteRegistry;
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
              "schema_version":3,
              "id":"example:disabled",
              "metadata":{"name":"Disabled","description":""},
              "settings":{
                "standalone":true,
                "cooldown":"0t",
                "player":{"hidden":true,"stop_conditions":{"movement_distance":0.1,"jump":true,"submerge":true,"ride":true,"damage":true,"attack":true,"game_mode_change":true}},
                "playback":{"mode":"once","loop_delay":"0t"}
              },
              "nodes":{"root":{"type":"anchor","space":"scene","default_matrix":[1,0,0,0,0,1,0,0,0,0,1,0,0,0,0,1]}},
              "timeline":{"duration":"1t","keyframes":[]}
            }
            """);
        EmoteRegistry registry = new EmoteRegistry();
        var loaded = new AnimationJsonLoader().load(configManager.getAnimationDirectory().resolve("disabled.json"));
        ReloadService service = new ReloadService(
            configManager,
            registry,
            ignored -> new AnimationDirectoryLoader.DirectoryContents(java.util.List.of(loaded), java.util.List.of()),
            null,
            null
        );

        service.loadOnServerStart();

        assertNotNull(registry.findDefinition("example:disabled"));
    }
}
