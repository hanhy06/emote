package io.github.hanhy06.emote.animation;

import io.github.hanhy06.emote.api.animation.EmoteAnimation;
import io.github.hanhy06.emote.api.animation.EmoteAnimationLoadException;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EmoteAnimationComplexityValidatorTest {
    private static final String MINECRAFT_VERSION = System.getProperty("emote.minecraftVersion");
    private static final EmoteAnimation.Matrix IDENTITY = new EmoteAnimation.Matrix(List.of(
        1.0D, 0.0D, 0.0D, 0.0D,
        0.0D, 1.0D, 0.0D, 0.0D,
        0.0D, 0.0D, 1.0D, 0.0D,
        0.0D, 0.0D, 0.0D, 1.0D
    ));

    private final EmoteAnimationComplexityValidator validator = new EmoteAnimationComplexityValidator();

    @Test
    void acceptsAllBundledAnimations() throws Exception {
        EmoteAnimationJsonLoader loader = new EmoteAnimationJsonLoader();
        try (var paths = Files.list(Path.of("docs/example"))) {
            for (Path path : paths.filter(file -> file.getFileName().toString().endsWith(".json")).toList()) {
                EmoteAnimation.Loaded loaded = loader.load(path, MINECRAFT_VERSION);
                assertDoesNotThrow(() -> this.validator.validate(loaded), path.toString());
            }
        }
    }

    @Test
    void acceptsAnimationDefinedNodeTransformStateAndCommandCounts() {
        Map<String, EmoteAnimation.Node> nodes = new LinkedHashMap<>();
        Map<String, EmoteAnimation.NodeTransform> transforms = new LinkedHashMap<>();
        Map<String, EmoteAnimation.NodeState> states = new LinkedHashMap<>();
        List<String> commands = new ArrayList<>();
        for (int index = 0; index < 100; index++) {
            String nodeId = "node_" + index;
            nodes.put(nodeId, blockNode());
            transforms.put(nodeId, new EmoteAnimation.NodeTransform(IDENTITY, 0));
            states.put(nodeId, new EmoteAnimation.NodeState(true));
            commands.add("say " + index);
        }
        EmoteAnimation.Event event = new EmoteAnimation.Event(
            new EmoteAnimation.CommandSource(EmoteAnimation.SourceType.SERVER, null),
            new EmoteAnimation.CommandOrigin(EmoteAnimation.OriginType.ROOT, null, EmoteAnimation.Vec3.ZERO),
            commands
        );
        EmoteAnimation.Keyframe keyframe = new EmoteAnimation.Keyframe(0, transforms, states);

        assertDoesNotThrow(() -> this.validator.validate(loaded(
            nodes,
            20,
            List.of(keyframe),
            new EmoteAnimation.Events(List.of(event), List.of(), List.of(), List.of())
        )));
    }

    @Test
    void rejectsTimelineLongerThanTenMinutes() {
        EmoteAnimationLoadException exception = assertThrows(
            EmoteAnimationLoadException.class,
            () -> this.validator.validate(loaded(
                Map.of("node", blockNode()),
                EmoteAnimationComplexityValidator.MAX_DURATION_TICKS + 1,
                List.of(),
                EmoteAnimation.Events.empty()
            ))
        );

        assertEquals("$.timeline.duration_ticks", exception.fieldPath());
    }

    private EmoteAnimation.Loaded loaded(
        Map<String, EmoteAnimation.Node> nodes,
        int durationTicks,
        List<EmoteAnimation.Keyframe> keyframes,
        EmoteAnimation.Events events
    ) {
        EmoteAnimation animation = new EmoteAnimation(
            Identifier.parse("test:complexity"),
            new EmoteAnimation.Metadata("Complexity", "Complexity", false),
            nodes,
            new EmoteAnimation.Timeline(
                durationTicks,
                EmoteAnimation.LoopMode.ONCE,
                0,
                keyframes,
                events
            )
        );
        return new EmoteAnimation.Loaded(Path.of("complexity.json"), "test", animation);
    }

    private EmoteAnimation.BlockNode blockNode() {
        return new EmoteAnimation.BlockNode(true, IDENTITY, new CompoundTag(), new CompoundTag());
    }
}
