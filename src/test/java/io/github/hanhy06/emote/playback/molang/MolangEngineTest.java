package io.github.hanhy06.emote.playback.molang;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MolangEngineTest {
    private final MolangEngine engine = MolangEngine.INSTANCE;

    @Test
    void compilesStrictlyAndEvaluatesQueries() throws Exception {
        MolangEngine.CompiledExpression expression = this.engine.compile("q.anim_time * 2");
        MolangEngine.Session session = this.engine.createSession();
        session.setQuery("anim_time", 1.25D);

        assertEquals(2.5D, session.evaluate(expression));
        assertThrows(MolangEngine.MolangCompileException.class, () -> this.engine.compile("@"));
    }

    @Test
    void compilesBedrockStartDelayExpressions() throws Exception {
        this.engine.compile("math.max(0, q.anim_time - 0.1)");
        this.engine.compile("v.time = q.anim_time < 0.1 ? 0 : (v.time + q.delta_time);");
    }

    @Test
    void keepsVariablesButRecreatesTempsForEachEvaluation() throws Exception {
        MolangEngine.CompiledExpression variable = this.engine.compile(
            "v.count = v.count + 1; t.value = t.value + 1; return v.count * 10 + t.value;"
        );
        MolangEngine.Session session = this.engine.createSession();

        assertEquals(11.0D, session.evaluate(variable));
        assertEquals(21.0D, session.evaluate(variable));
    }

    @Test
    void emptyQuerySourceSuppliesZeroPlayerState() throws Exception {
        MolangEngine.CompiledExpression expression = this.engine.compile(
            "q.ground_speed + q.vertical_speed + q.is_moving + q.is_on_ground + q.is_sprinting"
                + " + q.is_swimming + q.is_gliding + q.is_riding + q.is_using_item + q.is_on_fire + q.is_in_water"
                + " + q.target_x_rotation + q.target_y_rotation + q.body_x_rotation + q.body_y_rotation"
                + " + q.head_x_rotation + q.head_y_rotation + q.eye_target_x_rotation + q.eye_target_y_rotation"
                + " + q.modified_distance_moved + q.walk_distance + q.is_sneaking + q.is_sleeping"
                + " + q.is_emoting + q.item_is_charged + q.sleep_rotation"
        );
        MolangEngine.Session session = this.engine.createSession();

        MolangQueries.EMPTY.apply(session);

        assertEquals(0.0D, session.evaluate(expression));
    }

    @Test
    void exposesBedrockPlayerAnimationQueries() {
        assertEquals(
            java.util.Set.of(
                "target_x_rotation", "target_y_rotation",
                "body_x_rotation", "body_y_rotation",
                "head_x_rotation", "head_y_rotation",
                "eye_target_x_rotation", "eye_target_y_rotation",
                "modified_distance_moved", "walk_distance", "is_sneaking", "is_sleeping",
                "is_emoting", "item_is_charged", "sleep_rotation"
            ),
            MolangQueries.SUPPORTED_NAMES.stream()
                .filter(name -> name.contains("_rotation")
                    || name.equals("modified_distance_moved")
                    || name.equals("walk_distance")
                    || name.equals("is_sneaking")
                    || name.equals("is_sleeping")
                    || name.equals("is_emoting")
                    || name.equals("item_is_charged"))
                .collect(java.util.stream.Collectors.toSet())
        );
    }
}
