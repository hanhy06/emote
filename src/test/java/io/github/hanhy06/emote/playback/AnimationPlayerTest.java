package io.github.hanhy06.emote.playback;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.math.Transformation;
import io.github.hanhy06.emote.animation.AnimationJsonLoader;
import io.github.hanhy06.emote.content.PreparedEmote;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

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
    void rejectsPersistentVariableAssignmentInsideTrackValue() throws Exception {
        JsonObject root = base();
        positionTrack(root).get(0).getAsJsonObject().getAsJsonArray("value")
            .set(0, JsonParser.parseString("\"v.count = v.count + 1; return v.count;\""));

        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> PreparedEmote.from(load(root))
        );

        assertTrue(exception.getMessage().contains("must not assign persistent variables"));
    }

    private AnimationPlayer player(JsonObject root, FakeTarget target) throws Exception {
        return new AnimationPlayer(PreparedEmote.from(load(root)), target);
    }

    private io.github.hanhy06.emote.content.LoadedAnimation load(JsonObject root) throws Exception {
        return new AnimationJsonLoader().parse(
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

        @Override
        public Transformation createTransformation(String nodeId, PreparedEmote.PreparedTransform transform) {
            return new Transformation(transform.localMatrix());
        }

        @Override
        public void applyTransform(String nodeId, PreparedEmote.PreparedTransform transform, int interpolationDurationTicks) {
            this.transforms.put(nodeId, createTransformation(nodeId, transform));
        }

        @Override
        public void setVisible(String nodeId, boolean visible) {
            this.visibility.put(nodeId, visible);
        }

        @Override
        public void resetAll() {
            this.transforms.clear();
            this.visibility.clear();
        }

        private org.joml.Matrix4fc matrix(String nodeId) {
            return this.transforms.get(nodeId).getMatrix();
        }
    }
}
