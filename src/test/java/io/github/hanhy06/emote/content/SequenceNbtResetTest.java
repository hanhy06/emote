package io.github.hanhy06.emote.content;

import com.mojang.math.Transformation;
import io.github.hanhy06.emote.api.animation.EmoteAnimation;
import io.github.hanhy06.emote.playback.AnimationPlayer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SequenceNbtResetTest {
    @ParameterizedTest
    @CsvSource({"0,false", "2,false", "0,true", "2,true"})
    void restoresPreviousNbtAtTheNextStepStartAndReappliesMatchingPatches(int transitionTicks, boolean secondHasNbt) {
        PreparedAnimation first = animation("test:first", true);
        PreparedAnimation second = animation("test:second", secondHasNbt);
        var sequence = new EmoteSequence(Path.of("sequence.json"), Identifier.parse("test:sequence"), first.metadata(),
            new EmoteSequence.Settings(0, first.playerBehavior()), List.of(
                new EmoteSequence.EmoteStep(first.animation().id(), 1),
                new EmoteSequence.EmoteStep(second.animation().id(), 1, transitionTicks)
            ));
        var compiled = PreparedSequence.resolve(sequence, Map.of(first.id(), first, second.id(), second)).compiledAnimation();
        Target target = new Target();
        AnimationPlayer player = new AnimationPlayer(compiled, target);
        player.bindEvents(event -> {});
        player.start();
        player.startEvents();
        assertTrue(target.glowing);
        for (int tick = 1; tick < 1 + transitionTicks; tick++) {
            player.advance();
            assertTrue(target.glowing);
            assertEquals(0, target.resets);
        }
        player.advance();
        assertEquals(1, target.resets);
        assertEquals(secondHasNbt, target.glowing);
        assertEquals(secondHasNbt ? 2 : 1, target.patches);
    }

    private static PreparedAnimation animation(String id, boolean nbtTrack) {
        var template = PreparedAnimationFixture.create(id, id).animation();
        CompoundTag initial = new CompoundTag();
        initial.putBoolean("Glowing", false);
        var node = new EmoteAnimation.ItemNode(true, EmoteAnimation.NodeSpace.SCENE, null,
            EmoteAnimation.LocalTransform.IDENTITY, initial, new CompoundTag(), "none", null);
        CompoundTag patch = new CompoundTag();
        patch.putBoolean("Glowing", true);
        var tracks = new EmoteAnimation.NodeTracks(List.of(), List.of(), List.of(), List.of(),
            List.of(new EmoteAnimation.NbtKeyframe(0, patch)));
        var definition = new EmoteAnimation(template.id(), template.metadata(), template.settings(), template.molang(),
            Map.of("display", node), new EmoteAnimation.Timeline(1, nbtTrack ? Map.of("display", tracks) : Map.of(),
                EmoteAnimation.Events.empty()), List.of());
        return PreparedAnimation.from(new LoadedAnimation(Path.of("animation.json"), id, definition,
            Map.of("display", new PreparedDisplayData.Item(ItemStack.EMPTY, ItemDisplayContext.NONE))));
    }

    private static final class Target implements AnimationPlayer.TimelineTarget {
        boolean glowing;
        int patches;
        int resets;
        public Transformation createTransformation(String id, PreparedAnimation.PreparedTransform transform) { return new Transformation(transform.localMatrix()); }
        public void applyTransform(String id, PreparedAnimation.PreparedTransform transform, int ticks) {}
        public void setVisible(String id, boolean visible) {}
        public void applyNbt(String id, CompoundTag patch) { this.glowing = patch.getBooleanOr("Glowing", false); this.patches++; }
        public void resetNbt(String id) { this.glowing = false; this.resets++; }
        public void resetAll() {}
    }
}
