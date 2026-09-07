package io.github.hanhy06.emote.content;

import com.mojang.math.Transformation;
import io.github.hanhy06.emote.api.EmoteMetadata;
import io.github.hanhy06.emote.api.EmotePlayerBehavior;
import io.github.hanhy06.emote.api.animation.EmoteAnimation;
import io.github.hanhy06.emote.content.loader.AnimationJsonParser;
import io.github.hanhy06.emote.playback.AnimationPlayer;
import io.github.hanhy06.emote.playback.SequencePlayer;
import io.github.hanhy06.emote.playback.molang.PlayerMolangQueries;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SequencePlaybackTest {
    @Test
    void keepsLoopPlaybackInsideSequenceInsteadOfAdvancingToTheNextStep() throws Exception {
        PreparedAnimation looping = animation("example:looping", 1, EmoteAnimation.LoopMode.LOOP, 1, 1);
        PreparedAnimation unreachable = animation("example:unreachable", 10);
        EmoteSequence sequence = new EmoteSequence(
            Path.of("sequence.json"),
            Identifier.parse("example:sequence"),
            new EmoteMetadata("Sequence", "test", Map.of()),
            new EmoteSequence.Settings(0, playerBehavior()),
            List.of(
                new EmoteSequence.EmoteStep(looping.animation().id(), 1),
                new EmoteSequence.EmoteStep(unreachable.animation().id(), 1)
            )
        );
        PreparedAnimation playback = PreparedSequence.resolve(
            sequence,
            Map.of(looping.id(), looping, unreachable.id(), unreachable)
        ).compile(new Random(1));
        FakeTarget target = new FakeTarget();
        SequencePlayer player = new SequencePlayer(playback, target, PlayerMolangQueries.EMPTY, () -> 0L);

        player.start();
        player.startEvents();
        player.advance();
        player.advance();
        player.advance();

        assertEquals(2.0F, target.x("display"), 1.0E-5F);
        for (int tick = 0; tick < 10; tick++) {
            assertTrue(player.advance() != AnimationPlayer.AdvanceResult.FINISHED);
        }
        assertTrue(target.x("display") < 10.0F);
    }

    @Test
    void keepsHoldPlaybackInsideSequenceInsteadOfAdvancingToTheNextStep() throws Exception {
        PreparedAnimation holding = animation("example:holding", 1, EmoteAnimation.LoopMode.HOLD, 0, 0);
        PreparedAnimation unreachable = animation("example:unreachable", 10);
        EmoteSequence sequence = new EmoteSequence(
            Path.of("sequence.json"),
            Identifier.parse("example:sequence"),
            new EmoteMetadata("Sequence", "test", Map.of()),
            new EmoteSequence.Settings(0, playerBehavior()),
            List.of(
                new EmoteSequence.EmoteStep(holding.animation().id(), 1),
                new EmoteSequence.EmoteStep(unreachable.animation().id(), 1)
            )
        );
        PreparedAnimation playback = PreparedSequence.resolve(
            sequence,
            Map.of(holding.id(), holding, unreachable.id(), unreachable)
        ).compile(new Random(1));
        FakeTarget target = new FakeTarget();
        SequencePlayer player = new SequencePlayer(playback, target, PlayerMolangQueries.EMPTY, () -> 0L);

        player.start();
        player.startEvents();
        for (int tick = 0; tick < 10; tick++) {
            assertEquals(AnimationPlayer.AdvanceResult.CONTINUE, player.advance());
        }

        assertEquals(4.0F, target.x("display"), 1.0E-5F);
    }

    @Test
    void keepsServerSynchronizedPlaybackInsideSequence() throws Exception {
        PreparedAnimation synchronizedAnimation = animation(
            "example:synchronized",
            1,
            EmoteAnimation.LoopMode.SERVER_SYNC,
            0,
            1
        );
        PreparedAnimation unreachable = animation("example:unreachable", 10);
        EmoteSequence sequence = new EmoteSequence(
            Path.of("sequence.json"),
            Identifier.parse("example:sequence"),
            new EmoteMetadata("Sequence", "test", Map.of()),
            new EmoteSequence.Settings(0, playerBehavior()),
            List.of(
                new EmoteSequence.EmoteStep(synchronizedAnimation.animation().id(), 1),
                new EmoteSequence.EmoteStep(unreachable.animation().id(), 1)
            )
        );
        PreparedAnimation playback = PreparedSequence.resolve(
            sequence,
            Map.of(synchronizedAnimation.id(), synchronizedAnimation, unreachable.id(), unreachable)
        ).compile(new Random(1));
        FakeTarget target = new FakeTarget();
        SequencePlayer player = new SequencePlayer(playback, target, PlayerMolangQueries.EMPTY, () -> 1L);

        player.start();
        player.startEvents();
        for (int tick = 0; tick < 10; tick++) {
            assertTrue(player.advance() != AnimationPlayer.AdvanceResult.FINISHED);
        }

        assertTrue(target.x("display") < 10.0F);
    }

    @Test
    void startsIndependentMolangSessionForEachAnimationSegment() throws Exception {
        PreparedAnimation first = animation("example:first", 1);
        PreparedAnimation second = animation("example:second", 10);
        EmoteSequence sequence = new EmoteSequence(
            Path.of("sequence.json"),
            Identifier.parse("example:sequence"),
            new EmoteMetadata("Sequence", "test", Map.of()),
            new EmoteSequence.Settings(0, playerBehavior()),
            List.of(
                new EmoteSequence.EmoteStep(first.animation().id(), 1),
                new EmoteSequence.WaitStep(1),
                new EmoteSequence.EmoteStep(second.animation().id(), 1)
            )
        );
        PreparedSequence prepared = PreparedSequence.resolve(sequence, Map.of(first.id(), first, second.id(), second));
        PreparedAnimation playback = prepared.compile(new Random(1));
        FakeTarget target = new FakeTarget();
        AnimationPlayer player = new SequencePlayer(playback, target, PlayerMolangQueries.EMPTY, () -> 0L);

        player.start();
        player.startEvents();
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
        PreparedAnimation first = animation("example:first", 1);
        PreparedAnimation second = animation("example:second", 10);
        EmoteSequence sequence = new EmoteSequence(
            Path.of("sequence.json"),
            Identifier.parse("example:sequence"),
            new EmoteMetadata("Sequence", "test", Map.of()),
            new EmoteSequence.Settings(0, playerBehavior()),
            List.of(
                new EmoteSequence.EmoteStep(first.animation().id(), 1),
                new EmoteSequence.EmoteStep(second.animation().id(), 1, 2)
            )
        );
        PreparedSequence prepared = PreparedSequence.resolve(sequence, Map.of(first.id(), first, second.id(), second));
        FakeTarget target = new FakeTarget();
        AnimationPlayer player = new SequencePlayer(
            prepared.compile(new Random(1)),
            target,
            PlayerMolangQueries.EMPTY,
            () -> 0L
        );

        player.start();
        player.startEvents();
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
    void duplicatesHierarchyAndTracksForGeneratedPartner() throws Exception {
        PreparedAnimation animation = animation("example:mirror", 1);

        SequenceNodeLayout.Expansion expansion = SequenceNodeLayout.expandPartnerLayout(
            true,
            animation.animation(),
            Map.of()
        );

        String partnerRoot = expansion.partnerNodeIds().get("root");
        String partnerDisplay = expansion.partnerNodeIds().get("display");
        assertTrue(expansion.generatedPartner());
        assertEquals(EmoteAnimation.NodeSpace.PARTNER, expansion.animation().nodes().get(partnerDisplay).space());
        assertEquals(partnerRoot, expansion.animation().nodes().get(partnerDisplay).parentId());
        assertEquals(
            expansion.animation().timeline().tracks().get("display"),
            expansion.animation().timeline().tracks().get(partnerDisplay)
        );
    }

    @Test
    void composesTheSequenceBasePoseThroughParentAnchors() throws Exception {
        PreparedAnimation animation = zeroScaleParentAnimation();
        EmoteSequence sequence = new EmoteSequence(
            Path.of("sequence.json"),
            Identifier.parse("example:sequence"),
            new EmoteMetadata("Sequence", "test", Map.of()),
            new EmoteSequence.Settings(0, playerBehavior()),
            List.of(new EmoteSequence.EmoteStep(animation.animation().id(), 1))
        );

        PreparedAnimation playback = PreparedSequence.resolve(sequence, Map.of(animation.id(), animation)).compile(new Random(1));

        assertEquals(0.0F, playback.defaultTransform("display").scale().x(), 1.0E-5F);
        assertEquals(0.0F, playback.defaultTransform("display").scale().y(), 1.0E-5F);
        assertEquals(0.0F, playback.defaultTransform("display").scale().z(), 1.0E-5F);
    }

    private PreparedAnimation animation(String id, int initialValue) throws Exception {
        return animation(id, initialValue, EmoteAnimation.LoopMode.ONCE, 0, 0);
    }

    private PreparedAnimation animation(
        String id,
        int initialValue,
        EmoteAnimation.LoopMode mode,
        int loopStart,
        int loopDelay
    ) throws Exception {
        String molang = mode == EmoteAnimation.LoopMode.SERVER_SYNC
            ? "\"initialize\":\"v.value = %d;\"".formatted(initialValue)
            : "\"initialize\":\"v.value = %d;\",\"tick\":\"v.value = v.value + 1;\"".formatted(initialValue);
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
                "playback":{"mode":"%s","loop_start":"%dt","loop_delay":"%dt"}
              },
              "molang":{
                %s
              },
              "nodes":{
                "root":{
                  "type":"anchor",
                  "space":"initiator",
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
            """.formatted(id, mode.name().toLowerCase(java.util.Locale.ROOT), loopStart, loopDelay, molang);
        LoadedAnimation loaded = new AnimationJsonParser().parse(
            Path.of(id.replace(':', '_') + ".json"),
            json.getBytes(StandardCharsets.UTF_8)
        );
        return PreparedAnimation.from(loaded);
    }

    private PreparedAnimation zeroScaleParentAnimation() throws Exception {
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
                  "space":"initiator",
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
        return PreparedAnimation.from(loaded);
    }

    private EmotePlayerBehavior playerBehavior() {
        return new EmotePlayerBehavior(true, new EmotePlayerBehavior.StopConditions(
            0.0D, true, true, true, true, true, true
        ));
    }

    private static final class FakeTarget implements AnimationPlayer.TimelineTarget {
        private final Map<String, Transformation> transforms = new HashMap<>();
        private int lastInterpolationDuration;
        private int transformUpdateCount;

        @Override
        public Transformation createTransformation(String nodeId, PreparedAnimation.PreparedTransform transform) {
            return new Transformation(transform.localMatrix());
        }

        @Override
        public void applyTransform(String nodeId, PreparedAnimation.PreparedTransform transform, int interpolationDurationTicks) {
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
