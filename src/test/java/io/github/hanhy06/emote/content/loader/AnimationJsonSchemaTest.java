package io.github.hanhy06.emote.content.loader;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.hanhy06.emote.api.animation.EmoteAnimation;
import io.github.hanhy06.emote.api.animation.EmoteAnimationLoadException;
import io.github.hanhy06.emote.content.LoadedAnimation;
import net.minecraft.world.entity.HumanoidArm;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class AnimationJsonSchemaTest {
    private static final Path SOURCE = Path.of("animation-schema-test.json");
    private final AnimationJsonParser parser = new AnimationJsonParser();

    @Test
    void loadsHierarchyMolangAndIndependentTracks() throws Exception {
        EmoteAnimation animation = parse(base()).animation();

        EmoteAnimation.Node child = animation.nodes().get("display");
        EmoteAnimation.NodeTracks tracks = animation.timeline().tracks().get("display");
        assertEquals(EmoteAnimation.NodeSpace.INITIATOR, child.space());
        assertEquals("root", child.parentId());
        assertEquals(1.5D, child.transform().position().y());
        assertEquals("v.speed = 1;", animation.molang().initialize());
        assertNull(animation.molang().tick());
        assertEquals(2, tracks.rotation().size());
        assertInstanceOf(EmoteAnimation.MolangValue.class, tracks.rotation().getFirst().post().y());
        assertEquals(EmoteAnimation.Easing.EASE_IN_OUT_SINE, tracks.rotation().getFirst().easing());
        assertTrue(((EmoteAnimation.ConstantVisibility) tracks.visible().getFirst().value()).value());
    }

    @Test
    void rejectsSchemaThree() {
        JsonObject root = base();
        root.addProperty("schema_version", 3);

        assertEquals("$.schema_version", assertInvalid(root).fieldPath());
    }

    @Test
    void loadsParticipantPhysicalHandItemSource() throws Exception {
        JsonObject root = base();
        JsonObject display = root.getAsJsonObject("nodes").getAsJsonObject("display");
        display.remove("item_stack_snbt");
        display.add("item_source", JsonParser.parseString("{\"type\":\"participant_hand\",\"arm\":\"left\"}"));
        display.addProperty("item_display", "thirdperson_lefthand");

        EmoteAnimation.ItemNode node = (EmoteAnimation.ItemNode) parse(root).animation().nodes().get("display");

        EmoteAnimation.ParticipantHandItemSource source = assertInstanceOf(
            EmoteAnimation.ParticipantHandItemSource.class,
            node.itemSource()
        );
        assertEquals(HumanoidArm.LEFT, source.arm());
    }

    @Test
    void rejectsItemWithBothFixedAndParticipantSources() {
        JsonObject root = base();
        root.getAsJsonObject("nodes").getAsJsonObject("display")
            .add("item_source", JsonParser.parseString("{\"type\":\"participant_hand\",\"arm\":\"right\"}"));

        assertEquals("$.nodes.display", assertInvalid(root).fieldPath());
    }

    @Test
    void rejectsParticipantHandItemInSceneSpace() {
        JsonObject root = base();
        root.getAsJsonObject("nodes").getAsJsonObject("root").addProperty("space", "scene");
        JsonObject display = root.getAsJsonObject("nodes").getAsJsonObject("display");
        display.remove("item_stack_snbt");
        display.add("item_source", JsonParser.parseString("{\"type\":\"participant_hand\",\"arm\":\"right\"}"));

        assertEquals("$.nodes.display.item_source", assertInvalid(root).fieldPath());
    }

    @Test
    void rejectsParentCycle() {
        JsonObject root = base();
        root.getAsJsonObject("nodes").getAsJsonObject("root").addProperty("parent", "display");
        root.getAsJsonObject("nodes").getAsJsonObject("root").remove("space");

        assertEquals("$.nodes.root.parent", assertInvalid(root).fieldPath());
    }

    @Test
    void rejectsSpaceOnChildNode() {
        JsonObject root = base();
        root.getAsJsonObject("nodes").getAsJsonObject("display").addProperty("space", "initiator");

        assertEquals("$.nodes.display.space", assertInvalid(root).fieldPath());
    }

    @Test
    void rejectsInvalidMolangAtExactAxisPath() {
        JsonObject root = base();
        root.getAsJsonObject("timeline").getAsJsonObject("tracks").getAsJsonObject("display")
            .getAsJsonArray("rotation").get(0).getAsJsonObject().getAsJsonArray("value").set(1, JsonParser.parseString("\"@\""));

        assertEquals("$.timeline.tracks.display.rotation[0].value[1]", assertInvalid(root).fieldPath());
    }

    @Test
    void rejectsTickProgramForServerSync() {
        JsonObject root = base();
        root.getAsJsonObject("settings").getAsJsonObject("playback").addProperty("mode", "server_sync");
        root.getAsJsonObject("molang").addProperty("tick", "v.count = v.count + 1;");

        assertEquals("$.molang.tick", assertInvalid(root).fieldPath());
    }

    @Test
    void rejectsRuntimeOwnedEntityNbt() {
        JsonObject root = base();
        root.getAsJsonObject("nodes").getAsJsonObject("display")
            .addProperty("entity_nbt", "{transformation:{translation:[0f,0f,0f]}}");

        assertEquals("$.nodes.display.entity_nbt", assertInvalid(root).fieldPath());
    }

    @Test
    void rejectsTrackWhoseFirstKeyframeIsNotZero() {
        JsonObject root = base();
        root.getAsJsonObject("timeline").getAsJsonObject("tracks").getAsJsonObject("display")
            .getAsJsonArray("rotation").get(0).getAsJsonObject().addProperty("time", "1t");

        assertEquals("$.timeline.tracks.display.rotation[0].time", assertInvalid(root).fieldPath());
    }

    private JsonObject base() {
        return JsonParser.parseString("""
            {
              "type": "animation",
              "schema_version": 4,
              "id": "example:schema4",
              "metadata": {"name": "Schema 4", "description": "test"},
              "settings": {
                "standalone": true,
                "cooldown": "0t",
                "player": {
                  "hidden": true,
                  "stop_conditions": {
                    "movement_distance": 0.1,
                    "jump": true,
                    "submerge": true,
                    "ride": true,
                    "damage": true,
                    "attack": true,
                    "game_mode_change": true
                  }
                },
                "playback": {"mode": "once", "loop_delay": "0t"}
              },
              "molang": {"initialize": "v.speed = 1;"},
              "nodes": {
                "root": {
                  "type": "anchor",
                  "space": "initiator",
                  "transform": {
                    "position": [0, 0, 0],
                    "rotation": [0, 0, 0],
                    "scale": [1, 1, 1]
                  }
                },
                "display": {
                  "type": "item_display",
                  "parent": "root",
                  "visible": true,
                  "item_display": "none",
                  "item_stack_snbt": "{id:\\\"minecraft:stone\\\",count:1}",
                  "transform": {
                    "position": [0, 1.5, 0],
                    "rotation": [0, 0, 0],
                    "scale": [1, 1, 1]
                  }
                }
              },
              "timeline": {
                "duration": "20t",
                "tracks": {
                  "display": {
                    "rotation": [
                      {
                        "time": "0t",
                        "value": [0, "q.anim_time * v.speed", 0],
                        "interpolation": "linear",
                        "easing": "ease_in_out_sine"
                      },
                      {"time": "20t", "value": [0, 90, 0]}
                    ],
                    "visible": [{"time": "0t", "value": true}]
                  }
                },
                "events": {}
              }
            }
            """).getAsJsonObject();
    }

    private LoadedAnimation parse(JsonObject root) throws EmoteAnimationLoadException {
        return this.parser.parse(SOURCE, root.toString().getBytes(StandardCharsets.UTF_8));
    }

    private EmoteAnimationLoadException assertInvalid(JsonObject root) {
        return assertThrows(EmoteAnimationLoadException.class, () -> parse(root));
    }
}
