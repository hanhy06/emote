package io.github.hanhy06.emote.molang;

import team.unnamed.mocha.MochaEngine;
import team.unnamed.mocha.parser.ParseException;
import team.unnamed.mocha.parser.ast.Expression;
import team.unnamed.mocha.runtime.value.MutableObjectBinding;
import team.unnamed.mocha.runtime.value.NumberValue;
import team.unnamed.mocha.runtime.value.StringValue;
import team.unnamed.mocha.runtime.value.Value;
import team.unnamed.mocha.runtime.value.Function;

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
            MolangExpressionInspector.Inspection inspection = MolangExpressionInspector.inspect(expressions);
            return new CompiledExpression(source, expressions, inspection.queryUses(), inspection.assignsPersistentVariables());
        } catch (ParseException exception) {
            throw new MolangCompileException(exception.getMessage(), exception);
        }
    }

    public Session createSession() {
        return new Session();
    }

    public static final class CompiledExpression {
        private final String source;
        private final List<Expression> expressions;
        private final List<QueryUse> queryUses;
        private final boolean assignsPersistentVariables;

        private CompiledExpression(
            String source,
            List<Expression> expressions,
            List<QueryUse> queryUses,
            boolean assignsPersistentVariables
        ) {
            this.source = Objects.requireNonNull(source, "source");
            this.expressions = List.copyOf(expressions);
            this.queryUses = List.copyOf(queryUses);
            this.assignsPersistentVariables = assignsPersistentVariables;
        }

        public String source() {
            return this.source;
        }

        public List<QueryUse> queryUses() {
            return this.queryUses;
        }

        public boolean assignsPersistentVariables() {
            return this.assignsPersistentVariables;
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
            setQueryFunction("any", arguments -> QueryValue.bool(arguments.anyMatchesFirst()));
            setQueryFunction("all", arguments -> QueryValue.bool(arguments.allMatchFirst()));
            setQueryFunction("in_range", arguments -> QueryValue.bool(
                arguments.number(0) >= arguments.number(1) && arguments.number(0) <= arguments.number(2)
            ));
            setQueryFunction("approx_eq", arguments -> QueryValue.bool(arguments.allApproximatelyEqual()));
        }

        public void setQuery(String name, double value) {
            Objects.requireNonNull(name, "name");
            this.query.set(name.toLowerCase(Locale.ROOT), NumberValue.of(value));
        }

        public void setQueryFunction(String name, QueryFunction function) {
            setQueryFunction(name, null, function);
        }

        public void setQueryFunction(String name, double value, QueryFunction function) {
            setQueryFunction(name, Double.valueOf(value), function);
        }

        private void setQueryFunction(String name, Double value, QueryFunction function) {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(function, "function");
            this.query.set(name.toLowerCase(Locale.ROOT), new Function<Object>() {
                @Override
                public Value evaluate(team.unnamed.mocha.runtime.ExecutionContext<Object> context, Arguments arguments) {
                    List<Value> values = new ArrayList<>(arguments.length());
                    for (int i = 0; i < arguments.length(); i++) {
                        Value argument = arguments.next().eval();
                        values.add(argument == null ? NumberValue.zero() : argument);
                    }
                    return runtimeValue(Objects.requireNonNull(function.evaluate(new QueryArguments(values)), "query result"));
                }

                @Override
                public double getAsNumber() {
                    return value == null ? 0.0D : value;
                }

                @Override
                public boolean getAsBoolean() {
                    return value == null || value != 0.0D;
                }
            });
        }

        public double evaluate(CompiledExpression expression) {
            Objects.requireNonNull(expression, "expression");
            return MolangRuntime.evaluate(this.evaluator.scope(), expression.expressions);
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

        public boolean anyMatchesFirst() {
            for (int i = 1; i < this.values.size(); i++) {
                if (sameValue(this.values.getFirst(), this.values.get(i))) {
                    return true;
                }
            }
            return false;
        }

        public boolean allMatchFirst() {
            for (int i = 1; i < this.values.size(); i++) {
                if (!sameValue(this.values.getFirst(), this.values.get(i))) {
                    return false;
                }
            }
            return true;
        }

        public boolean allApproximatelyEqual() {
            double first = number(0);
            double tolerance = Math.ulp(first);
            for (int i = 1; i < this.values.size(); i++) {
                double value = number(i);
                if (Math.abs(first - value) > Math.max(tolerance, Math.ulp(value))) {
                    return false;
                }
            }
            return true;
        }

        private static boolean sameValue(Value first, Value second) {
            if (first instanceof StringValue || second instanceof StringValue) {
                return first instanceof StringValue && second instanceof StringValue && first.getAsString().equals(second.getAsString());
            }
            return first.getAsNumber() == second.getAsNumber();
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
