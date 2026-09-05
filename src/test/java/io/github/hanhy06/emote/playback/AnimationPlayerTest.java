package io.github.hanhy06.emote.playback;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.mojang.math.Transformation;
import io.github.hanhy06.emote.content.PreparedAnimation;
import io.github.hanhy06.emote.content.loader.AnimationJsonParser;
import io.github.hanhy06.emote.playback.molang.PlayerMolangQueries;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class AnimationPlayerTest {
    @Test
    void evaluatesMolangBeforeTracksAndComposesParentTransform() throws Exception {
        JsonObject root = base();
        root.add("molang", JsonParser.parseString("""
            {"initialize":"v.offset = 2;","tick":"v.offset = v.offset + 1;"}
            """));
        positionTrack(root).get(0).getAsJsonObject().getAsJsonArray("value")
            .set(0, JsonParser.parseString("\"v.offset\""));

        FakeTarget target = new FakeTarget();
        AnimationPlayer player = player(root, target);
        player.start();

        assertEquals(4.0F, target.matrix("display").m30(), 1.0E-5F);
        assertEquals(AnimationPlayer.AdvanceResult.CONTINUE, player.advance());
        assertEquals(5.6F, target.matrix("display").m30(), 1.0E-5F);
    }

    @Test
    void appliesEasingAndDynamicVisibilityAtServerTicks() throws Exception {
        JsonObject root = base();
        JsonObject first = positionTrack(root).get(0).getAsJsonObject();
        first.addProperty("easing", "ease_in_quad");
        root.getAsJsonObject("timeline").getAsJsonObject("tracks").getAsJsonObject("display")
            .add("visible", JsonParser.parseString("""
                [{"time":"0t","value":"q.anim_time_ticks < 5"}]
                """));

        FakeTarget target = new FakeTarget();
        AnimationPlayer player = player(root, target);
        player.start();
        for (int tick = 0; tick < 5; tick++) {
            player.advance();
        }

        assertEquals(3.5F, target.matrix("display").m30(), 1.0E-5F);
        assertFalse(target.visibility.get("display"));
    }

    @Test
    void evaluatesInitiatorQueriesAndLifeTimeBeforeTracks() throws Exception {
        JsonObject root = base();
        positionTrack(root).remove(1);
        JsonObject frame = positionTrack(root).get(0).getAsJsonObject();
        frame.remove("interpolation");
        frame.getAsJsonArray("value").set(0, JsonParser.parseString(
            "\"q.life_time + q.ground_speed + q.vertical_speed + q.is_moving + q.is_on_ground + q.is_sprinting"
                + " + q.is_swimming + q.is_gliding + q.is_riding + q.is_using_item + q.is_on_fire + q.is_in_water\""
        ));

        PlayerMolangQueries.Source queries = session -> {
            session.setQuery("ground_speed", 2.0D);
            session.setQuery("vertical_speed", 3.0D);
            session.setQuery("is_moving", 1.0D);
            session.setQuery("is_on_ground", 1.0D);
            session.setQuery("is_sprinting", 1.0D);
            session.setQuery("is_swimming", 1.0D);
            session.setQuery("is_gliding", 1.0D);
            session.setQuery("is_riding", 1.0D);
            session.setQuery("is_using_item", 1.0D);
            session.setQuery("is_on_fire", 1.0D);
            session.setQuery("is_in_water", 1.0D);
        };
        FakeTarget target = new FakeTarget();
        AnimationPlayer player = new AnimationPlayer(PreparedAnimation.from(load(root)), target, queries);

        player.start();
        assertEquals(15.0F, target.matrix("display").m30(), 1.0E-5F);

        player.advance();
        assertEquals(15.05F, target.matrix("display").m30(), 1.0E-5F);
    }

    @Test
    void resetsPersistentVariablesAtLoopBoundary() throws Exception {
        JsonObject root = base();
        root.getAsJsonObject("settings").getAsJsonObject("playback").addProperty("mode", "loop");
        root.getAsJsonObject("timeline").addProperty("duration", "1t");
        positionTrack(root).remove(1);
        positionTrack(root).get(0).getAsJsonObject().remove("interpolation");
        root.add("molang", JsonParser.parseString("""
            {"initialize":"v.count = 0;","tick":"v.count = v.count + 1;"}
            """));
        positionTrack(root).get(0).getAsJsonObject().getAsJsonArray("value")
            .set(0, JsonParser.parseString("\"v.count\""));

        FakeTarget target = new FakeTarget();
        AnimationPlayer player = player(root, target);
        player.start();
        assertEquals(2.0F, target.matrix("display").m30(), 1.0E-5F);

        assertEquals(AnimationPlayer.AdvanceResult.LOOP_BOUNDARY, player.advance());
        assertEquals(AnimationPlayer.AdvanceResult.RESTARTED, player.continueAfterLoopEvent());
        assertEquals(2.0F, target.matrix("display").m30(), 1.0E-5F);
    }

    @Test
    void appliesNbtOnlyWhenTheStepFrameChangesAndRestoresItOnLoop() throws Exception {
        JsonObject root = base();
        root.getAsJsonObject("settings").getAsJsonObject("playback").addProperty("mode", "loop");
        root.getAsJsonObject("timeline").addProperty("duration", "2t");
        positionTrack(root).remove(1);
        positionTrack(root).get(0).getAsJsonObject().remove("interpolation");
        root.getAsJsonObject("timeline").getAsJsonObject("tracks").getAsJsonObject("display")
            .add("nbt", JsonParser.parseString("""
                [
                  {"time":"0t","value":"{item:{id:'minecraft:stone',count:1}}"},
                  {"time":"2t","value":"{item:{id:'minecraft:diamond',count:1}}"}
                ]
                """));

        FakeTarget target = new FakeTarget();
        AnimationPlayer player = player(root, target);
        player.start();
        player.advance();

        assertEquals(1, target.nbtApplyCount);
        assertTrue(target.nbt.get("display").toString().contains("minecraft:stone"));

        assertEquals(AnimationPlayer.AdvanceResult.LOOP_BOUNDARY, player.advance());
        assertEquals(2, target.nbtApplyCount);
        assertTrue(target.nbt.get("display").toString().contains("minecraft:diamond"));

        assertEquals(AnimationPlayer.AdvanceResult.RESTARTED, player.continueAfterLoopEvent());
        assertEquals(3, target.nbtApplyCount);
        assertTrue(target.nbt.get("display").toString().contains("minecraft:stone"));
    }

    @Test
    void selectsNbtOncePerKeyframeAndAgainForANewLoop() throws Exception {
        JsonObject root = base();
        root.getAsJsonObject("settings").getAsJsonObject("playback").addProperty("mode", "loop");
        root.getAsJsonObject("timeline").addProperty("duration", "1t");
        positionTrack(root).remove(1);
        positionTrack(root).get(0).getAsJsonObject().remove("interpolation");
        root.getAsJsonObject("timeline").getAsJsonObject("tracks").getAsJsonObject("display")
            .add("nbt", JsonParser.parseString("""
                [{
                  "time":"0t",
                  "value":{
                    "select":"q.is_sneaking ? 1 : 0",
                    "options":[
                      "{item:{id:'minecraft:poppy',count:1}}",
                      "{item:{id:'minecraft:blue_orchid',count:1}}"
                    ]
                  }
                }]
                """));
        AtomicBoolean sneaking = new AtomicBoolean();
        PlayerMolangQueries.Source queries = session -> session.setQuery("is_sneaking", sneaking.get() ? 1.0D : 0.0D);
        FakeTarget target = new FakeTarget();
        AnimationPlayer player = new AnimationPlayer(PreparedAnimation.from(load(root)), target, queries);

        player.start();
        assertTrue(target.nbt.get("display").toString().contains("minecraft:poppy"));

        sneaking.set(true);
        assertEquals(AnimationPlayer.AdvanceResult.LOOP_BOUNDARY, player.advance());
        assertEquals(1, target.nbtApplyCount);
        assertTrue(target.nbt.get("display").toString().contains("minecraft:poppy"));

        assertEquals(AnimationPlayer.AdvanceResult.RESTARTED, player.continueAfterLoopEvent());
        assertEquals(2, target.nbtApplyCount);
        assertTrue(target.nbt.get("display").toString().contains("minecraft:blue_orchid"));
    }

    @Test
    void rejectsNbtSelectorResultOutsideItsOptions() throws Exception {
        JsonObject root = base();
        root.getAsJsonObject("timeline").getAsJsonObject("tracks").getAsJsonObject("display")
            .add("nbt", JsonParser.parseString("""
                [{
                  "time":"0t",
                  "value":{
                    "select":"2",
                    "options":["{Glowing:false}", "{Glowing:true}"]
                  }
                }]
                """));
        AnimationPlayer player = player(root, new FakeTarget());

        IllegalStateException exception = assertThrows(IllegalStateException.class, player::start);

        assertTrue(exception.getMessage().contains("only 2 options exist"));
    }

    @Test
    void rebuildsSelectedNbtStateWhenStartingInsideTheTimeline() throws Exception {
        JsonObject root = base();
        root.getAsJsonObject("timeline").getAsJsonObject("tracks").getAsJsonObject("display")
            .add("nbt", JsonParser.parseString("""
                [
                  {"time":"0t","value":"{item:{id:'minecraft:poppy',count:1},Glowing:false}"},
                  {
                    "time":"5t",
                    "value":{
                      "select":"1",
                      "options":["{Glowing:false}", "{Glowing:true}"]
                    }
                  }
                ]
                """));
        FakeTarget target = new FakeTarget();
        AnimationPlayer player = player(root, target);

        player.startAtCyclePhase(5L);

        assertTrue(target.nbt.get("display").toString().contains("minecraft:poppy"));
        assertTrue(target.nbt.get("display").getBooleanOr("Glowing", false));
    }

    @Test
    void rejectsPersistentVariableAssignmentInsideTrackValue() throws Exception {
        JsonObject root = base();
        positionTrack(root).get(0).getAsJsonObject().getAsJsonArray("value")
            .set(0, JsonParser.parseString("\"v.count = v.count + 1; return v.count;\""));

        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> PreparedAnimation.from(load(root))
        );

        assertTrue(exception.getMessage().contains("must not assign persistent variables"));
    }

    @Test
    void rejectsQueryAssignmentInsideTickProgram() throws Exception {
        JsonObject root = base();
        root.add("molang", JsonParser.parseString("{\"tick\":\"q.anim_time = 100;\"}"));

        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> PreparedAnimation.from(load(root))
        );

        assertTrue(exception.getMessage().contains("must not assign queries"));
    }

    @Test
    void acceptsBedrockPlayerAnimationQueries() throws Exception {
        JsonObject root = base();
        positionTrack(root).get(0).getAsJsonObject().getAsJsonArray("value").set(0, new JsonPrimitive(
            "q.target_x_rotation + q.target_y_rotation + q.body_x_rotation + q.body_y_rotation"
                + " + q.head_x_rotation + q.head_y_rotation + q.eye_target_x_rotation + q.eye_target_y_rotation"
                + " + q.modified_distance_moved + q.walk_distance + q.is_sneaking + q.is_sleeping"
                + " + q.is_emoting + q.item_is_charged + q.sleep_rotation"
                + " + q.health + query.max_health + q.is_alive + query.is_spectator"
                + " + q.head_is_in_water + query.is_in_lava + q.is_in_water_or_rain"
                + " + q.hurt_time + query.death_ticks + q.invulnerable_ticks + query.player_level"
                + " + q.item_in_use_duration + query.item_remaining_use_duration + q.item_max_use_duration"
        ));

        assertDoesNotThrow(() -> PreparedAnimation.from(load(root)));
    }

    private AnimationPlayer player(JsonObject root, FakeTarget target) throws Exception {
        return new AnimationPlayer(PreparedAnimation.from(load(root)), target);
    }

    private io.github.hanhy06.emote.content.LoadedAnimation load(JsonObject root) throws Exception {
        return new AnimationJsonParser().parse(
            Path.of("schema4-player-test.json"),
            root.toString().getBytes(StandardCharsets.UTF_8)
        );
    }

    private com.google.gson.JsonArray positionTrack(JsonObject root) {
        return root.getAsJsonObject("timeline").getAsJsonObject("tracks").getAsJsonObject("display")
            .getAsJsonArray("position");
    }

    private JsonObject base() {
        return JsonParser.parseString("""
            {
              "type":"animation",
              "schema_version":4,
              "id":"example:runtime",
              "metadata":{"name":"Runtime","description":"test"},
              "settings":{
                "standalone":true,
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
                  "space":"scene",
                  "transform":{"position":[1,0,0],"rotation":[0,0,0],"scale":[1,1,1]}
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
                "duration":"10t",
                "tracks":{
                  "display":{
                    "position":[
                      {"time":"0t","value":[0,0,0],"interpolation":"linear"},
                      {"time":"10t","value":[10,0,0]}
                    ]
                  }
                },
                "events":{}
              }
            }
            """).getAsJsonObject();
    }

    private static final class FakeTarget implements AnimationPlayer.TimelineTarget {
        private final Map<String, Transformation> transforms = new HashMap<>();
        private final Map<String, Boolean> visibility = new HashMap<>();
        private final Map<String, net.minecraft.nbt.CompoundTag> nbt = new HashMap<>();
        private int nbtApplyCount;

        @Override
        public Transformation createTransformation(String nodeId, PreparedAnimation.PreparedTransform transform) {
            return new Transformation(transform.localMatrix());
        }

        @Override
        public void applyTransform(String nodeId, PreparedAnimation.PreparedTransform transform, int interpolationDurationTicks) {
            this.transforms.put(nodeId, createTransformation(nodeId, transform));
        }

        @Override
        public void setVisible(String nodeId, boolean visible) {
            this.visibility.put(nodeId, visible);
        }

        @Override
        public void applyNbt(String nodeId, net.minecraft.nbt.CompoundTag nbt) {
            this.nbt.put(nodeId, nbt.copy());
            this.nbtApplyCount++;
        }

        @Override
        public void resetAll() {
            this.transforms.clear();
            this.visibility.clear();
            this.nbt.clear();
        }

        private org.joml.Matrix4fc matrix(String nodeId) {
            return this.transforms.get(nodeId).getMatrix();
        }
    }
}
