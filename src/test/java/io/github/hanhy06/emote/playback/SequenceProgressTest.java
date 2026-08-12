package io.github.hanhy06.emote.playback;

import io.github.hanhy06.emote.api.animation.EmoteAnimation;
import io.github.hanhy06.emote.emote.RegisteredEmote;
import io.github.hanhy06.emote.emote.RegisteredSequence;
import io.github.hanhy06.emote.sequence.EmoteSequence;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static io.github.hanhy06.emote.emote.RegisteredEmoteFixture.create;
import static org.junit.jupiter.api.Assertions.*;

class SequenceProgressTest {
    @Test
    void advancesAfterRepeatingTheCurrentStep() {
        RegisteredEmote idle = create("demo:idle", "Idle", false);
        EmoteAnimation.PlayerBehavior visiblePlayer = new EmoteAnimation.PlayerBehavior(
            false,
            new EmoteAnimation.StopConditions(0.0D, false, false, false, false, false, false)
        );
        RegisteredEmote stand = create("demo:stand", "Stand", visiblePlayer);
        EmoteSequence source = new EmoteSequence(
            Path.of("sequence.json"),
            Identifier.parse("demo:sit"),
            new EmoteSequence.Metadata("Sit", "Sit sequence"),
            EmoteAnimation.PlayerBehavior.createDefault(),
            List.of(
                new EmoteSequence.Step(Identifier.parse(idle.id()), 3),
                new EmoteSequence.Step(Identifier.parse(stand.id()), 1)
            )
        );
        RootTransform root = RootTransform.create(new Vec3(10.0D, 64.0D, -3.0D), 90.0F);
        SequenceProgress progress = new SequenceProgress(
            RegisteredSequence.resolve(source, Map.of(idle.id(), idle, stand.id(), stand)),
            root
        );

        assertEquals(EmoteAnimation.PlayerBehavior.createDefault(), progress.sequence().playerBehavior());
        assertSame(root, progress.root());
        assertSame(idle, progress.currentAnimation());
        assertFalse(progress.completeCycle());
        assertSame(idle, progress.currentAnimation());
        assertFalse(progress.completeCycle());
        assertSame(idle, progress.currentAnimation());
        assertFalse(progress.completeCycle());
        assertSame(stand, progress.currentAnimation());
        assertTrue(progress.completeCycle());
        assertSame(root, progress.root());
    }
}
