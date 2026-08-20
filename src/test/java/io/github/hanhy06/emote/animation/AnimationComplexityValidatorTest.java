package io.github.hanhy06.emote.animation;

import io.github.hanhy06.emote.api.EmoteMetadata;
import io.github.hanhy06.emote.api.EmotePlayerBehavior;
import io.github.hanhy06.emote.api.animation.EmoteAnimation;
import io.github.hanhy06.emote.api.animation.EmoteAnimationLoadException;
import io.github.hanhy06.emote.content.LoadedAnimation;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AnimationComplexityValidatorTest {
    private final AnimationComplexityValidator validator = new AnimationComplexityValidator();

    @Test
    void acceptsAllBundledAnimations() throws Exception {
        AnimationJsonLoader loader = new AnimationJsonLoader();
        try (var paths = Files.list(Path.of("docs/example"))) {
            for (Path path : paths.filter(file -> file.getFileName().toString().endsWith(".json")).toList()) {
                LoadedAnimation loaded = loader.load(path);
                assertDoesNotThrow(() -> this.validator.validate(loaded), path.toString());
            }
        }
    }

    @Test
    void acceptsAnimationDefinedNodeAndCommandCounts() {
        Map<String, EmoteAnimation.Node> nodes = new LinkedHashMap<>();
        List<String> commands = new java.util.ArrayList<>();
        for (int index = 0; index < 100; index++) {
            String nodeId = "node_" + index;
            nodes.put(nodeId, blockNode());
            commands.add("say " + index);
        }
        EmoteAnimation.Event event = new EmoteAnimation.Event(
            new EmoteAnimation.CommandSource(EmoteAnimation.SourceType.SERVER, null),
            new EmoteAnimation.CommandOrigin(EmoteAnimation.OriginType.ROOT, null, EmoteAnimation.Vec3.ZERO),
            commands
        );
        assertDoesNotThrow(() -> this.validator.validate(loaded(
            nodes,
            20,
            new EmoteAnimation.Events(List.of(event), List.of(), List.of(), List.of())
        )));
    }

    @Test
    void rejectsTimelineLongerThanTenMinutes() {
        EmoteAnimationLoadException exception = assertThrows(
            EmoteAnimationLoadException.class,
            () -> this.validator.validate(loaded(
                Map.of("node", blockNode()),
                AnimationComplexityValidator.MAX_DURATION_TICKS + 1,
                EmoteAnimation.Events.empty()
            ))
        );

        assertEquals("$.timeline.duration", exception.fieldPath());
    }

    private LoadedAnimation loaded(
        Map<String, EmoteAnimation.Node> nodes,
        int durationTicks,
        EmoteAnimation.Events events
    ) {
        EmoteAnimation animation = new EmoteAnimation(
            Identifier.parse("test:complexity"),
            new EmoteMetadata("Complexity", "Complexity"),
            new EmoteAnimation.Settings(true, 0, EmotePlayerBehavior.createDefault(), new EmoteAnimation.PlaybackSettings(EmoteAnimation.LoopMode.ONCE, 0)),
            EmoteAnimation.MolangPrograms.empty(),
            nodes,
            new EmoteAnimation.Timeline(
                durationTicks,
                Map.of(),
                events
            )
        );
        return new LoadedAnimation(Path.of("complexity.json"), "test", animation);
    }

    private EmoteAnimation.BlockNode blockNode() {
        return new EmoteAnimation.BlockNode(
            true,
            EmoteAnimation.NodeSpace.SCENE,
            null,
            EmoteAnimation.LocalTransform.IDENTITY,
            new CompoundTag(),
            new CompoundTag()
        );
    }
}
