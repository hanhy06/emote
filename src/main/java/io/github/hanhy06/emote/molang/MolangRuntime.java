/*
 * Portions derived from mocha's ExpressionInterpreter, licensed under the MIT License.
 * Copyright (c) 2021-2025 Unnamed Team.
 */
package io.github.hanhy06.emote.molang;

import team.unnamed.mocha.parser.ast.*;
import team.unnamed.mocha.runtime.ExecutionContext;
import team.unnamed.mocha.runtime.Scope;
import team.unnamed.mocha.runtime.value.*;

import java.util.List;

final class MolangRuntime implements ExpressionVisitor<Value>, ExecutionContext<Object> {
    private final Object entity;
    private final Scope scope;
    private Object flag;
    private Value returnValue;

    private MolangRuntime(Object entity, Scope scope) {
        this.entity = entity;
        this.scope = scope;
    }

    static double evaluate(Scope rootScope, List<Expression> expressions) {
        Scope localScope = rootScope.copy();
        MutableObjectBinding temp = new MutableObjectBinding();
        localScope.set("temp", temp);
        localScope.set("t", temp);
        localScope.readOnly(true);

        MolangRuntime runtime = new MolangRuntime(null, localScope);
        Value lastResult = NumberValue.zero();
        for (Expression expression : expressions) {
            lastResult = expression.visit(runtime);
            if (runtime.returnValue != null) {
                return runtime.returnValue.getAsNumber();
            }
        }
        return lastResult == null ? 0.0D : lastResult.getAsNumber();
    }

    @Override
    public Object entity() {
        return this.entity;
    }

    @Override
    public Value eval(Expression expression) {
        return expression.visit(this);
    }

    @Override
    public Object flag() {
        return this.flag;
    }

    @Override
    public void flag(Object flag) {
        this.flag = flag;
    }

    @Override
    public Value visitArrayAccess(ArrayAccessExpression expression) {
        Value array = expression.array().visit(this);
        Value index = expression.index().visit(this);
        if (!(array instanceof ArrayValue arrayValue) || arrayValue.values().length == 0) {
            return Value.nil();
        }
        Value[] values = arrayValue.values();
        return values[Math.max(0, (int) index.getAsNumber()) % values.length];
    }

    @Override
    public Value visitAccess(AccessExpression expression) {
        Value object = expression.object().visit(this);
        return object instanceof ObjectValue objectValue ? objectValue.get(expression.property()) : NumberValue.zero();
    }

    @Override
    public Value visitCall(CallExpression expression) {
        FunctionArguments arguments = new FunctionArguments(expression.arguments());
        if (expression.function() instanceof IdentifierExpression identifier) {
            if (identifier.name().equals("loop")) {
                return evaluateLoop(arguments);
            }
            if (identifier.name().equals("for_each")) {
                return evaluateForEach(arguments);
            }
        }

        Value function = expression.function().visit(this);
        if (!(function instanceof Function<?> callable)) {
            return Value.nil();
        }
        @SuppressWarnings("unchecked") Function<Object> typedCallable = (Function<Object>) callable;
        Value result = typedCallable.evaluate(this, arguments);
        return result == null ? NumberValue.zero() : result;
    }

    private Value evaluateLoop(FunctionArguments arguments) {
        int count = Math.min(1024, Math.max(0, Math.round((float) arguments.next().eval().getAsNumber())));
        Value body = arguments.next().eval();
        if (!(body instanceof Function<?> callable)) {
            return NumberValue.zero();
        }

        @SuppressWarnings("unchecked") Function<Object> typedCallable = (Function<Object>) callable;
        for (int i = 0; i < count; i++) {
            MolangRuntime child = new MolangRuntime(this.entity, this.scope);
            typedCallable.evaluate(child);
            if (child.returnValue != null) {
                this.returnValue = child.returnValue;
                break;
            }
            if (child.flag == StatementExpression.Op.BREAK) {
                break;
            }
        }
        return NumberValue.zero();
    }

    private Value evaluateForEach(FunctionArguments arguments) {
        Expression variable = arguments.next().expression();
        if (!(variable instanceof AccessExpression variableAccess)) {
            return NumberValue.zero();
        }
        Value collection = arguments.next().eval();
        Value body = arguments.next().eval();
        if (!(collection instanceof ArrayValue array) || !(body instanceof Function<?> callable)) {
            return NumberValue.zero();
        }

        @SuppressWarnings("unchecked") Function<Object> typedCallable = (Function<Object>) callable;
        for (Value value : array.values()) {
            Value object = variableAccess.object().visit(this);
            if (object instanceof MutableObjectBinding binding) {
                binding.set(variableAccess.property(), value);
            }
            MolangRuntime child = new MolangRuntime(this.entity, this.scope);
            typedCallable.evaluate(child);
            if (child.returnValue != null) {
                this.returnValue = child.returnValue;
                break;
            }
            if (child.flag == StatementExpression.Op.BREAK) {
                break;
            }
        }
        return NumberValue.zero();
    }

    @Override
    public Value visitDouble(DoubleExpression expression) {
        return NumberValue.of(expression.value());
    }

    @Override
    public Value visitExecutionScope(ExecutionScopeExpression expression) {
        return (Function<Object>) (context, arguments) -> {
            if (context instanceof MolangRuntime runtime) {
                return runtime.executeScope(expression.expressions());
            }
            for (Expression child : expression.expressions()) {
                context.eval(child);
                if (context.flag() != null) {
                    break;
                }
            }
            return NumberValue.zero();
        };
    }

    private Value executeScope(List<Expression> expressions) {
        for (Expression expression : expressions) {
            expression.visit(this);
            if (this.returnValue != null || this.flag != null) {
                break;
            }
        }
        return NumberValue.zero();
    }

    @Override
    public Value visitIdentifier(IdentifierExpression expression) {
        return this.scope.get(expression.name());
    }

    @Override
    public Value visitBinary(BinaryExpression expression) {
        return switch (expression.op()) {
            case AND -> Value.of(expression.left().visit(this).getAsBoolean() && expression.right().visit(this).getAsBoolean());
            case OR -> Value.of(expression.left().visit(this).getAsBoolean() || expression.right().visit(this).getAsBoolean());
            case LT -> Value.of(expression.left().visit(this).getAsNumber() < expression.right().visit(this).getAsNumber());
            case LTE -> Value.of(expression.left().visit(this).getAsNumber() <= expression.right().visit(this).getAsNumber());
            case GT -> Value.of(expression.left().visit(this).getAsNumber() > expression.right().visit(this).getAsNumber());
            case GTE -> Value.of(expression.left().visit(this).getAsNumber() >= expression.right().visit(this).getAsNumber());
            case ADD -> number(expression.left().visit(this).getAsNumber() + expression.right().visit(this).getAsNumber());
            case SUB -> number(expression.left().visit(this).getAsNumber() - expression.right().visit(this).getAsNumber());
            case MUL -> number(expression.left().visit(this).getAsNumber() * expression.right().visit(this).getAsNumber());
            case DIV -> divide(expression);
            case ARROW -> evaluateArrow(expression);
            case NULL_COALESCE -> evaluateNullCoalesce(expression);
            case ASSIGN -> assign(expression);
            case CONDITIONAL -> expression.left().visit(this).getAsBoolean() ? evaluateBranch(expression.right()) : NumberValue.zero();
            case EQ -> Value.of(expression.left().visit(this).getAsNumber() == expression.right().visit(this).getAsNumber());
            case NEQ -> Value.of(expression.left().visit(this).getAsNumber() != expression.right().visit(this).getAsNumber());
        };
    }

    private Value divide(BinaryExpression expression) {
        double dividend = expression.left().visit(this).getAsNumber();
        double divisor = expression.right().visit(this).getAsNumber();
        return number(divisor == 0.0D ? 0.0D : dividend / divisor);
    }

    private Value evaluateArrow(BinaryExpression expression) {
        Value owner = expression.left().visit(this);
        if (!(owner instanceof JavaValue javaValue)) {
            return NumberValue.zero();
        }
        return expression.right().visit(new MolangRuntime(javaValue.value(), this.scope));
    }

    private Value evaluateNullCoalesce(BinaryExpression expression) {
        Value left = expression.left().visit(this);
        return left.getAsBoolean() ? left : expression.right().visit(this);
    }

    private Value assign(BinaryExpression expression) {
        Value value = expression.right().visit(this);
        if (expression.left() instanceof AccessExpression access) {
            Value object = access.object().visit(this);
            if (object instanceof MutableObjectBinding binding) {
                binding.set(access.property(), value);
            }
        }
        return value;
    }

    @Override
    public Value visitUnary(UnaryExpression expression) {
        Value value = expression.expression().visit(this);
        return switch (expression.op()) {
            case LOGICAL_NEGATION -> Value.of(!value.getAsBoolean());
            case ARITHMETICAL_NEGATION -> number(-value.getAsNumber());
            case RETURN -> {
                this.returnValue = value;
                yield NumberValue.zero();
            }
        };
    }

    @Override
    public Value visitStatement(StatementExpression expression) {
        this.flag = expression.op();
        return NumberValue.zero();
    }

    @Override
    public Value visitString(StringExpression expression) {
        return StringValue.of(expression.value());
    }

    @Override
    public Value visitTernaryConditional(TernaryConditionalExpression expression) {
        return expression.condition().visit(this).getAsBoolean()
            ? evaluateBranch(expression.trueExpression())
            : evaluateBranch(expression.falseExpression());
    }

    private Value evaluateBranch(Expression expression) {
        Value value = expression.visit(this);
        if (!(value instanceof Function<?> callable)) {
            return value;
        }
        @SuppressWarnings("unchecked") Function<Object> typedCallable = (Function<Object>) callable;
        Value result = typedCallable.evaluate(this);
        return result == null ? NumberValue.zero() : result;
    }

    @Override
    public Value visit(Expression expression) {
        throw new UnsupportedOperationException("Unsupported expression type: " + expression);
    }

    private static NumberValue number(double value) {
        return NumberValue.of(value);
    }

    private final class FunctionArguments implements Function.Arguments {
        private final List<Expression> expressions;
        private int index;

        private FunctionArguments(List<Expression> expressions) {
            this.expressions = expressions;
        }

        @Override
        public Function.Argument next() {
            if (this.index >= this.expressions.size()) {
                return EmptyArgument.INSTANCE;
            }
            Expression expression = this.expressions.get(this.index++);
            return new Function.Argument() {
                @Override
                public Expression expression() {
                    return expression;
                }

                @Override
                public Value eval() {
                    return expression.visit(MolangRuntime.this);
                }
            };
        }

        @Override
        public int length() {
            return this.expressions.size();
        }
    }

    private enum EmptyArgument implements Function.Argument {
        INSTANCE;

        @Override
        public Expression expression() {
            return null;
        }

        @Override
        public Value eval() {
            return NumberValue.zero();
        }
    }
}
