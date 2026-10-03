package io.github.hanhy06.emote.content;

import io.github.hanhy06.emote.api.sequence.EmoteSequence;

import com.mojang.math.Transformation;
import io.github.hanhy06.emote.api.EmoteMetadata;
import io.github.hanhy06.emote.api.EmotePlayerBehavior;
import io.github.hanhy06.emote.content.loader.AnimationJsonParser;
import io.github.hanhy06.emote.playback.PlaybackPlayer;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class SequencePlaybackTest {
    @Test
    void movesBetweenSelectedStepsWithoutReplayingSkippedExecutions() throws Exception {
        PreparedEmote first = animation("example:first", 1);
        PreparedEmote second = animation("example:second", 10);
        EmoteSequence sequence = new EmoteSequence(Identifier.parse("example:sequence"),
            new EmoteMetadata("Sequence", "test"), new EmoteSequence.Settings(0, playerBehavior()),
            List.of(new EmoteSequence.AnimationStep(first.model().id(), 2), new EmoteSequence.WaitStep(2),
                new EmoteSequence.AnimationStep(second.model().id(), 1, 4)));
        PreparedEmote prepared = PreparedSequence.resolve(sequence, Map.of(first.id(), first, second.id(), second)).compile(new Random(1));
        FakeTarget target = new FakeTarget();
        PlaybackPlayer player = new PlaybackPlayer(prepared, target);
        List<String> calls = new java.util.ArrayList<>();
        player.bindLifecycleListener(new PlaybackPlayer.LifecycleListener() {
            public void onStart(PreparedEmote animation) { calls.add("start:" + animation.id() + ":" + player.position().animationTick()); }
            public void onClose(io.github.hanhy06.emote.api.PlaybackStopReason reason) { calls.add("close:" + reason); }
        });
        player.start();
        player.startEvents();
        player.setTick(player.stepTickTarget(2, 0, 1));
        assertEquals(second.model().id(), player.position().animationId());
        assertEquals(1, player.position().animationTick());
        assertEquals(11.0F, target.x("display"));
        assertEquals(List.of("start:example:first:0", "close:REPLACED", "start:example:second:1"), calls);
        player.setTick(player.animationTickTarget(0));
        assertEquals(11.0F, target.x("display"));
        assertEquals(3, calls.size());
        player.setTick(player.stepTickTarget(0, 1, 0));
        assertEquals(1, player.position().repeatIndex());
        assertEquals(5, calls.size());
        int before = player.currentTick();
        assertThrows(IllegalArgumentException.class, () -> player.stepTickTarget(1, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> player.stepTickTarget(0, 3, 0));
        assertThrows(IllegalArgumentException.class, () -> player.canSetTick(prepared.durationTicks()));
        assertEquals(before, player.currentTick());
        player.setTick(5);
        assertEquals(io.github.hanhy06.emote.api.PlaybackTimeline.Phase.WAIT, player.position().phase());
        assertEquals(-1, player.animationTickTarget(0));
        assertEquals(0, target.lastInterpolationDuration);
        player.setTick(8);
        assertEquals(io.github.hanhy06.emote.api.PlaybackTimeline.Phase.TRANSITION, player.position().phase());
        assertEquals(0, target.lastInterpolationDuration);
        assertEquals(6.5F, target.x("display"));
        player.advance();
        assertEquals(8.75F, target.x("display"));
        player.advance();
        assertEquals(0, player.position().animationTick());
    }

    @Test
    void startsIndependentMolangSessionForEachAnimationSegment() throws Exception {
        PreparedEmote first = animation("example:first", 1);
        PreparedEmote second = animation("example:second", 10);
        EmoteSequence sequence = new EmoteSequence(Identifier.parse("example:sequence"),
            new EmoteMetadata("Sequence", "test", Map.of()),
            new EmoteSequence.Settings(0, playerBehavior()),
            List.of(
                new EmoteSequence.AnimationStep(first.model().id(), 1),
                new EmoteSequence.WaitStep(1),
                new EmoteSequence.AnimationStep(second.model().id(), 1)
            )
        );
        PreparedSequence prepared = PreparedSequence.resolve(sequence, Map.of(first.id(), first, second.id(), second));
        PreparedEmote playback = prepared.compile(new Random(1));
        FakeTarget target = new FakeTarget();
        PlaybackPlayer player = new PlaybackPlayer(playback, target);

        player.start();
        assertEquals(2.0F, target.x("display"), 1.0E-5F);
        player.advance();
        assertEquals(3.0F, target.x("display"), 1.0E-5F);
        player.advance();
        assertEquals(4.0F, target.x("display"), 1.0E-5F);
        player.advance();
        assertEquals(11.0F, target.x("display"), 1.0E-5F);
        assertEquals(5, playback.durationTicks());
    }

    @Test
    void appliesTheNextPoseOnceAndWaitsForItsTransitionBeforeStartingTheSegment() throws Exception {
        PreparedEmote first = animation("example:first", 1);
        PreparedEmote second = animation("example:second", 10);
        EmoteSequence sequence = new EmoteSequence(Identifier.parse("example:sequence"),
            new EmoteMetadata("Sequence", "test", Map.of()),
            new EmoteSequence.Settings(0, playerBehavior()),
            List.of(
                new EmoteSequence.AnimationStep(first.model().id(), 1),
                new EmoteSequence.AnimationStep(second.model().id(), 1, 2)
            )
        );
        PreparedSequence prepared = PreparedSequence.resolve(sequence, Map.of(first.id(), first, second.id(), second));
        FakeTarget target = new FakeTarget();
        PlaybackPlayer player = new PlaybackPlayer(prepared.compile(new Random(1)), target);

        player.start();
        player.advance();
        player.advance();
        int updateCountAfterTransitionStarted = target.transformUpdateCount;

        assertEquals(2, target.lastInterpolationDuration);
        assertEquals(11.0F, target.x("display"), 1.0E-5F);

        player.advance();
        assertEquals(updateCountAfterTransitionStarted, target.transformUpdateCount);

        player.advance();
        assertEquals(updateCountAfterTransitionStarted, target.transformUpdateCount);

        player.advance();
        assertEquals(12.0F, target.x("display"), 1.0E-5F);
    }

    @Test
    void composesTheSequenceBasePoseThroughParentAnchors() throws Exception {
        PreparedEmote animation = zeroScaleParentAnimation();
        EmoteSequence sequence = new EmoteSequence(Identifier.parse("example:sequence"),
            new EmoteMetadata("Sequence", "test", Map.of()),
            new EmoteSequence.Settings(0, playerBehavior()),
            List.of(new EmoteSequence.AnimationStep(animation.model().id(), 1))
        );

        PreparedEmote playback = PreparedSequence.resolve(sequence, Map.of(animation.id(), animation)).compile(new Random(1));

        assertEquals(0.0F, playback.defaultTransform("display").scale().x(), 1.0E-5F);
        assertEquals(0.0F, playback.defaultTransform("display").scale().y(), 1.0E-5F);
        assertEquals(0.0F, playback.defaultTransform("display").scale().z(), 1.0E-5F);
    }

    private PreparedEmote animation(String id, int initialValue) throws Exception {
        String json = """
            {
              "type":"animation",
              "schema_version":4,
              "id":"%s",
              "metadata":{"name":"Animation","description":"test"},
              "settings":{
                "standalone":false,
                "cooldown":"0t",
                "rotation_deadzone":50,
                "player":{
                  "hidden":true,
                  "stop_conditions":{
                    "movement_distance":0,
                    "jump":true,
                    "submerge":true,
                    "ride":true,
                    "damage":true,
                    "attack":true,
                    "game_mode_change":true
                  }
                },
                "playback":{"mode":"once","loop_delay":"0t"}
              },
              "molang":{
                "initialize":"v.value = %d;",
                "tick":"v.value = v.value + 1;"
              },
              "nodes":{
                "root":{
                  "type":"anchor",
                  "transform":{"position":[0,0,0],"rotation":[0,0,0],"scale":[1,1,1]}
                },
                "display":{
                  "type":"item_display",
                  "parent":"root",
                  "visible":true,
                  "item_display":"none",
                  "item_stack_snbt":"{id:'minecraft:stone',count:1}",
                  "transform":{"position":[0,0,0],"rotation":[0,0,0],"scale":[1,1,1]}
                }
              },
              "timeline":{
                "duration":"2t",
                "tracks":{
                  "display":{
                    "position":[{"time":"0t","value":["v.value",0,0]}]
                  }
                },
                "events":{}
              }
            }
            """.formatted(id, initialValue);
        LoadedAnimation loaded = new AnimationJsonParser().parse(
            Path.of(id.replace(':', '_') + ".json"),
            json.getBytes(StandardCharsets.UTF_8)
        );
        return PreparedEmote.from(loaded);
    }

    private PreparedEmote zeroScaleParentAnimation() throws Exception {
        String json = """
            {
              "type":"animation",
              "schema_version":4,
              "id":"example:hidden_flower",
              "metadata":{"name":"Hidden flower","description":"test"},
              "settings":{
                "standalone":false,
                "cooldown":"0t",
                "rotation_deadzone":50,
                "player":{
                  "hidden":true,
                  "stop_conditions":{
                    "movement_distance":0,
                    "jump":true,
                    "submerge":true,
                    "ride":true,
                    "damage":true,
                    "attack":true,
                    "game_mode_change":true
                  }
                },
                "playback":{"mode":"once","loop_delay":"0t"}
              },
              "nodes":{
                "root":{
                  "type":"anchor",
                  "transform":{"position":[0,0,0],"rotation":[0,0,0],"scale":[0,0,0]}
                },
                "display":{
                  "type":"item_display",
                  "parent":"root",
                  "visible":true,
                  "item_display":"none",
                  "item_stack_snbt":"{id:'minecraft:poppy',count:1}",
                  "transform":{"position":[0,0,0],"rotation":[0,0,0],"scale":[1,1,1]}
                }
              },
              "timeline":{"duration":"1t","tracks":{},"events":{}}
            }
            """;
        LoadedAnimation loaded = new AnimationJsonParser().parse(
            Path.of("hidden_flower.json"),
            json.getBytes(StandardCharsets.UTF_8)
        );
        return PreparedEmote.from(loaded);
    }

    private EmotePlayerBehavior playerBehavior() {
        return new EmotePlayerBehavior(true, new EmotePlayerBehavior.StopConditions(
            0.0D, true, true, true, true, true, true
        ));
    }

    private static final class FakeTarget implements PlaybackPlayer.TimelineTarget {
        private final Map<String, Transformation> transforms = new HashMap<>();
        private int lastInterpolationDuration;
        private int transformUpdateCount;

        @Override
        public Transformation createTransformation(String nodeId, PreparedEmote.PreparedTransform transform) {
            return new Transformation(transform.localMatrix());
        }

        @Override
        public void applyTransform(String nodeId, PreparedEmote.PreparedTransform transform, int interpolationDurationTicks) {
            this.transforms.put(nodeId, createTransformation(nodeId, transform));
            this.lastInterpolationDuration = interpolationDurationTicks;
            this.transformUpdateCount++;
        }

        @Override
        public void setVisible(String nodeId, boolean visible) {
        }

        @Override
        public void applyNbt(String nodeId, net.minecraft.nbt.CompoundTag nbt) {
        }

        @Override
        public void resetAll() {
            this.transforms.clear();
        }

        float x(String nodeId) {
            return this.transforms.get(nodeId).getMatrix().m30();
        }
    }
}
