package io.github.hanhy06.emote.playback.molang;

import io.github.hanhy06.emote.molang.MolangEngine;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

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
    void evaluatesParameterizedQueriesWithTypedArguments() throws Exception {
        MolangEngine.Session session = this.engine.createSession();
        session.setQueryFunction("weighted", arguments -> MolangEngine.QueryValue.number(
            arguments.number(0)
                + (arguments.string(1).equals("main_hand") ? 10.0D : 0.0D)
                + (arguments.bool(2) ? 100.0D : 0.0D)
        ));
        session.setQueryFunction("text", arguments -> MolangEngine.QueryValue.string(arguments.string(0)));

        assertEquals(112.5D, session.evaluate(this.engine.compile("q.weighted(2.5, 'main_hand', true)")));
        assertEquals(12.5D, session.evaluate(this.engine.compile("query.weighted(2.5, 'main_hand', false)")));
        assertEquals(1.0D, session.evaluate(this.engine.compile("q.text('selected') ? 1 : 0")));
    }

    @Test
    void evaluatesEachQueryArgumentOnceFromLeftToRight() throws Exception {
        MolangEngine.Session session = this.engine.createSession();
        session.setQueryFunction("sum", arguments -> MolangEngine.QueryValue.number(arguments.number(0) + arguments.number(1)));

        MolangEngine.CompiledExpression expression = this.engine.compile(
            "v.count = 0; q.sum(v.count = v.count + 1, v.count = v.count + 1); return v.count;"
        );

        assertEquals(2.0D, session.evaluate(expression));
    }

    @Test
    void recordsQueryCallsSeparatelyFromQueryValuesAndAssignments() throws Exception {
        MolangEngine.CompiledExpression expression = this.engine.compile(
            "q.anim_time; query.sample(1, 'value'); q.blocked = 1;"
        );

        assertEquals(
            java.util.List.of(
                new MolangEngine.QueryUse("anim_time", MolangEngine.QueryUseKind.VALUE, 0),
                new MolangEngine.QueryUse("sample", MolangEngine.QueryUseKind.CALL, 2),
                new MolangEngine.QueryUse("blocked", MolangEngine.QueryUseKind.ASSIGNMENT, 0)
            ),
            expression.queryUses()
        );
    }

    @Test
    void rejectsCallingScalarQueriesDuringTimelineValidation() throws Exception {
        MolangEngine.CompiledExpression expression = this.engine.compile("q.anim_time()");

        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> MolangQueries.validate(expression, "$.test")
        );
        assertEquals("$.test must not call query value anim_time", exception.getMessage());
    }

    @Test
    void compilesBedrockStartDelayExpressions() throws Exception {
        this.engine.compile("math.max(0, q.anim_time - 0.1)");
        MolangEngine.CompiledExpression expression = this.engine.compile(
            "v.time = q.anim_time < 0.1 ? 0 : (v.time + q.delta_time); return v.time;"
        );
        MolangEngine.Session session = this.engine.createSession();
        session.setQuery("delta_time", 0.05D);

        session.setQuery("anim_time", 0.05D);
        assertEquals(0.0D, session.evaluate(expression));
        session.setQuery("anim_time", 0.2D);
        assertEquals(0.05D, session.evaluate(expression));
    }

    @Test
    void evaluatesConditionalValuesAndOnlyTheSelectedBranch() throws Exception {
        MolangEngine.CompiledExpression expression = this.engine.compile(
            "v.result = q.enabled ? 10 : 20;"
                + "q.enabled ? { v.selected = v.selected + 1; } : { v.rejected = v.rejected + 1; };"
                + "return v.result + v.selected * 100 + v.rejected * 1000;"
        );
        MolangEngine.Session session = this.engine.createSession();

        session.setQuery("enabled", 1.0D);
        assertEquals(110.0D, session.evaluate(expression));
        session.setQuery("enabled", 0.0D);
        assertEquals(1120.0D, session.evaluate(expression));
    }

    @Test
    void associatesNestedConditionalExpressionsFromTheRight() throws Exception {
        MolangEngine.Session session = this.engine.createSession();

        assertEquals(2.0D, session.evaluate(this.engine.compile("0 ? 1 : 1 ? 2 : 3")));
        assertEquals(3.0D, session.evaluate(this.engine.compile("0 ? 1 : 0 ? 2 : 3")));
        assertEquals(2.0D, session.evaluate(this.engine.compile("v.nested = 0 ? 1 : 1 ? 2 : 3; return v.nested;")));
    }

    @Test
    void returnStopsTheSelectedConditionalBlock() throws Exception {
        MolangEngine.CompiledExpression expression = this.engine.compile(
            "1 ? { v.before = 1; return 7; v.after = 1; }; return 9;"
        );
        MolangEngine.Session session = this.engine.createSession();

        assertEquals(7.0D, session.evaluate(expression));
        assertEquals(1.0D, session.evaluate(this.engine.compile("return v.before;")));
        assertEquals(0.0D, session.evaluate(this.engine.compile("return v.after;")));
    }

    @Test
    void evaluatesEveryOfficialMolangMathFunction() throws Exception {
        Map<String, Double> examples = Map.ofEntries(
            Map.entry("math.abs(-2)", 2.0D),
            Map.entry("math.acos(0)", 90.0D),
            Map.entry("math.asin(1)", 90.0D),
            Map.entry("math.atan(1)", 45.0D),
            Map.entry("math.atan2(1, 1)", 45.0D),
            Map.entry("math.ceil(1.2)", 2.0D),
            Map.entry("math.clamp(4, 1, 3)", 3.0D),
            Map.entry("math.copy_sign(2, -1)", -2.0D),
            Map.entry("math.cos(60)", 0.5D),
            Map.entry("math.die_roll(2, 3, 3)", 6.0D),
            Map.entry("math.die_roll_integer(2, 3, 3)", 6.0D),
            Map.entry("math.exp(0)", 1.0D),
            Map.entry("math.floor(1.8)", 1.0D),
            Map.entry("math.hermite_blend(0.5)", 0.5D),
            Map.entry("math.inverse_lerp(2, 6, 3)", 0.25D),
            Map.entry("math.lerp(2, 6, 0.25)", 3.0D),
            Map.entry("math.lerprotate(170, -170, 0.5)", 180.0D),
            Map.entry("math.ln(1)", 0.0D),
            Map.entry("math.max(2, 3)", 3.0D),
            Map.entry("math.min(2, 3)", 2.0D),
            Map.entry("math.min_angle(180)", -180.0D),
            Map.entry("math.mod(5, 2)", 1.0D),
            Map.entry("math.pi", Math.PI),
            Map.entry("math.pow(2, 3)", 8.0D),
            Map.entry("math.random(3, 3)", 3.0D),
            Map.entry("math.random_integer(3, 3)", 3.0D),
            Map.entry("math.round(1.6)", 2.0D),
            Map.entry("math.sign(2)", 1.0D),
            Map.entry("math.sign(0)", -1.0D),
            Map.entry("math.sin(30)", 0.5D),
            Map.entry("math.sqrt(9)", 3.0D),
            Map.entry("math.trunc(-1.8)", -1.0D)
        );
        MolangEngine.Session session = this.engine.createSession();

        for (Map.Entry<String, Double> example : examples.entrySet()) {
            assertEquals(example.getValue(), session.evaluate(this.engine.compile(example.getKey())), 1.0E-9D, example.getKey());
        }
    }

    @Test
    void evaluatesEveryOfficialMolangEasingFamily() throws Exception {
        Set<String> families = Set.of("back", "bounce", "circ", "cubic", "elastic", "expo", "quad", "quart", "quint", "sine");
        MolangEngine.Session session = this.engine.createSession();

        for (String family : families) {
            for (String direction : Set.of("in", "out", "in_out")) {
                String function = "math.ease_" + direction + "_" + family;
                assertEquals(2.0D, session.evaluate(this.engine.compile(function + "(2, 6, 0)")), 1.0E-9D, function);
                assertEquals(6.0D, session.evaluate(this.engine.compile(function + "(2, 6, 1)")), 1.0E-9D, function);
            }
        }
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
                + " + q.health + q.max_health + q.is_alive + q.is_spectator"
                + " + q.head_is_in_water + q.is_in_lava + q.is_in_water_or_rain"
                + " + q.hurt_time + q.death_ticks + q.invulnerable_ticks + q.player_level"
                + " + q.item_in_use_duration + q.item_remaining_use_duration + q.item_max_use_duration"
        );
        MolangEngine.Session session = this.engine.createSession();
        for (String name : MolangQueries.SUPPORTED_NAMES) {
            session.setQuery(name, 99.0D);
        }

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
