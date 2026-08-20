package io.github.hanhy06.emote.playback.molang;

import team.unnamed.mocha.MochaEngine;
import team.unnamed.mocha.parser.ParseException;
import team.unnamed.mocha.parser.ast.Expression;
import team.unnamed.mocha.runtime.value.MutableObjectBinding;
import team.unnamed.mocha.runtime.value.NumberValue;

import java.util.List;
import java.util.Objects;

public final class MolangEngine {
    public static final MolangEngine INSTANCE = new MolangEngine();
    private static final int MAX_SOURCE_LENGTH = 16_384;

    private MolangEngine() {
    }

    public CompiledExpression compile(String source) throws MolangCompileException {
        Objects.requireNonNull(source, "source");
        if (source.length() > MAX_SOURCE_LENGTH) {
            throw new MolangCompileException("expression exceeds " + MAX_SOURCE_LENGTH + " characters");
        }
        try {
            return new CompiledExpression(source, MochaEngine.create().parse(source));
        } catch (ParseException exception) {
            throw new MolangCompileException(exception.getMessage(), exception);
        }
    }

    public Session createSession() {
        return new Session();
    }

    public record CompiledExpression(String source, List<Expression> expressions) {
        public CompiledExpression {
            Objects.requireNonNull(source, "source");
            expressions = List.copyOf(expressions);
        }
    }

    public static final class Session {
        private final MutableObjectBinding query = new MutableObjectBinding();
        private final MochaEngine<Void> evaluator;

        private Session() {
            this.evaluator = MochaEngine.createStandard(null);
            this.evaluator.scope().set("query", this.query);
            this.evaluator.scope().set("q", this.query);
        }

        public void setQuery(String name, double value) {
            Objects.requireNonNull(name, "name");
            this.query.set(name, NumberValue.of(value));
        }

        public double evaluate(CompiledExpression expression) {
            Objects.requireNonNull(expression, "expression");
            return this.evaluator.eval(expression.expressions());
        }
    }

    public static final class MolangCompileException extends Exception {
        public MolangCompileException(String message) {
            super(message);
        }

        public MolangCompileException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
