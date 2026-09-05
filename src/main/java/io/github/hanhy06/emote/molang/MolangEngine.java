package io.github.hanhy06.emote.molang;

import team.unnamed.mocha.MochaEngine;
import team.unnamed.mocha.parser.ParseException;
import team.unnamed.mocha.parser.ast.Expression;
import team.unnamed.mocha.runtime.value.MutableObjectBinding;
import team.unnamed.mocha.runtime.value.NumberValue;
import team.unnamed.mocha.runtime.value.StringValue;
import team.unnamed.mocha.runtime.value.Value;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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
            List<Expression> expressions = MolangAstNormalizer.normalize(MochaEngine.create().parse(source));
            return new CompiledExpression(source, expressions, MolangQueryInspector.inspect(expressions));
        } catch (ParseException exception) {
            throw new MolangCompileException(exception.getMessage(), exception);
        }
    }

    public Session createSession() {
        return new Session();
    }

    public record CompiledExpression(String source, List<Expression> expressions, List<QueryUse> queryUses) {
        public CompiledExpression {
            Objects.requireNonNull(source, "source");
            expressions = List.copyOf(expressions);
            queryUses = List.copyOf(queryUses);
        }
    }

    public enum QueryUseKind {
        VALUE,
        CALL,
        ASSIGNMENT
    }

    public record QueryUse(String name, QueryUseKind kind, int argumentCount) {
        public QueryUse {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(kind, "kind");
        }
    }

    public static final class Session {
        private final MutableObjectBinding query = new MutableObjectBinding();
        private final MochaEngine<Void> evaluator;

        private Session() {
            this.evaluator = MochaEngine.createStandard(null);
            this.evaluator.scope().set("math", MolangMath.INSTANCE);
            this.evaluator.scope().set("query", this.query);
            this.evaluator.scope().set("q", this.query);
        }

        public void setQuery(String name, double value) {
            Objects.requireNonNull(name, "name");
            this.query.set(name.toLowerCase(Locale.ROOT), NumberValue.of(value));
        }

        public void setQueryFunction(String name, QueryFunction function) {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(function, "function");
            this.query.set(name.toLowerCase(Locale.ROOT), (team.unnamed.mocha.runtime.value.Function<Object>) (context, arguments) -> {
                List<Value> values = new ArrayList<>(arguments.length());
                for (int i = 0; i < arguments.length(); i++) {
                    Value value = arguments.next().eval();
                    values.add(value == null ? NumberValue.zero() : value);
                }
                return runtimeValue(Objects.requireNonNull(function.evaluate(new QueryArguments(values)), "query result"));
            });
        }

        public double evaluate(CompiledExpression expression) {
            Objects.requireNonNull(expression, "expression");
            return MolangRuntime.evaluate(this.evaluator.scope(), expression.expressions());
        }

        private static Value runtimeValue(QueryValue value) {
            return switch (value) {
                case NumberQueryValue number -> NumberValue.of(number.value());
                case StringQueryValue string -> StringValue.of(string.value());
            };
        }
    }

    @FunctionalInterface
    public interface QueryFunction {
        QueryValue evaluate(QueryArguments arguments);
    }

    public static final class QueryArguments {
        private final List<Value> values;

        private QueryArguments(List<Value> values) {
            this.values = List.copyOf(values);
        }

        public int size() {
            return this.values.size();
        }

        public boolean isNumber(int index) {
            return value(index) instanceof NumberValue;
        }

        public boolean isString(int index) {
            return value(index) instanceof StringValue;
        }

        public double number(int index) {
            return value(index).getAsNumber();
        }

        public String string(int index) {
            Value value = value(index);
            return value instanceof StringValue string ? string.value() : "";
        }

        public boolean bool(int index) {
            return value(index).getAsBoolean();
        }

        private Value value(int index) {
            return index >= 0 && index < this.values.size() ? this.values.get(index) : NumberValue.zero();
        }
    }

    public sealed interface QueryValue permits NumberQueryValue, StringQueryValue {
        static QueryValue number(double value) {
            return new NumberQueryValue(value);
        }

        static QueryValue bool(boolean value) {
            return new NumberQueryValue(value ? 1.0D : 0.0D);
        }

        static QueryValue string(String value) {
            return new StringQueryValue(Objects.requireNonNull(value, "value"));
        }
    }

    private record NumberQueryValue(double value) implements QueryValue {
    }

    private record StringQueryValue(String value) implements QueryValue {
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
