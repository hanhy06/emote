package io.github.hanhy06.emote.animation.molang;

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
    void keepsVariablesButRecreatesTempsForEachEvaluation() throws Exception {
        MolangEngine.CompiledExpression variable = this.engine.compile(
            "v.count = v.count + 1; t.value = t.value + 1; return v.count * 10 + t.value;"
        );
        MolangEngine.Session session = this.engine.createSession();

        assertEquals(11.0D, session.evaluate(variable));
        assertEquals(21.0D, session.evaluate(variable));
    }
}
