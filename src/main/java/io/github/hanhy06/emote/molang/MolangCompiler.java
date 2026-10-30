package io.github.hanhy06.emote.molang;

import team.unnamed.mocha.parser.ast.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class MolangCompiler {
    private final List<MolangEngine.QueryUse> queryUses = new ArrayList<>();
    private boolean assignsPersistentVariables;

    MolangEngine.CompiledExpression compile(List<Expression> expressions) {
        return new MolangEngine.CompiledExpression(expressions.stream().map(this::normalize).toList(), this.queryUses, this.assignsPersistentVariables);
    }

    private Expression normalize(Expression expression) {
        if (expression instanceof TernaryConditionalExpression ternary) {
            return normalizeTernary(ternary);
        }
        if (expression instanceof BinaryExpression binary) {
            String query = binary.op() == BinaryExpression.Op.ASSIGN ? memberName(binary.left(), "q", "query") : null;
            if (query != null) this.queryUses.add(new MolangEngine.QueryUse(query, MolangEngine.QueryUseKind.ASSIGNMENT, 0));
            if (binary.op() == BinaryExpression.Op.ASSIGN && memberName(binary.left(), "v", "variable") != null) this.assignsPersistentVariables = true;
            return new BinaryExpression(binary.op(), query == null ? normalize(binary.left()) : binary.left(), normalize(binary.right()));
        }
        if (expression instanceof UnaryExpression unary) {
            return new UnaryExpression(unary.op(), normalize(unary.expression()));
        }
        if (expression instanceof AccessExpression access) {
            String query = memberName(access, "q", "query");
            if (query != null) this.queryUses.add(new MolangEngine.QueryUse(query, MolangEngine.QueryUseKind.VALUE, 0));
            return new AccessExpression(query == null ? normalize(access.object()) : access.object(), access.property());
        }
        if (expression instanceof ArrayAccessExpression arrayAccess) {
            return new ArrayAccessExpression(normalize(arrayAccess.array()), normalize(arrayAccess.index()));
        }
        if (expression instanceof CallExpression call) {
            if (call.function() instanceof UnaryExpression unary && unary.op() != UnaryExpression.Op.RETURN) {
                return normalize(new UnaryExpression(unary.op(), new CallExpression(unary.expression(), call.arguments())));
            }
            String query = memberName(call.function(), "q", "query");
            if (query != null) this.queryUses.add(new MolangEngine.QueryUse(query, MolangEngine.QueryUseKind.CALL, call.arguments().size()));
            return new CallExpression(query == null ? normalize(call.function()) : call.function(), call.arguments().stream().map(this::normalize).toList());
        }
        if (expression instanceof ExecutionScopeExpression scope) {
            return new ExecutionScopeExpression(scope.expressions().stream().map(this::normalize).toList());
        }
        return expression;
    }

    private Expression normalizeTernary(TernaryConditionalExpression ternary) {
        Expression condition = ternary.condition();
        Expression trueExpression = ternary.trueExpression();
        Expression falseExpression = ternary.falseExpression();

        if (condition instanceof TernaryConditionalExpression nested) {
            return normalize(new TernaryConditionalExpression(
                nested.condition(),
                nested.trueExpression(),
                new TernaryConditionalExpression(nested.falseExpression(), trueExpression, falseExpression)
            ));
        }
        if (condition instanceof BinaryExpression assignment && assignment.op() == BinaryExpression.Op.ASSIGN) {
            return normalize(new BinaryExpression(
                BinaryExpression.Op.ASSIGN,
                assignment.left(),
                new TernaryConditionalExpression(assignment.right(), trueExpression, falseExpression)
            ));
        }
        return new TernaryConditionalExpression(
            normalize(condition),
            normalize(trueExpression),
            normalize(falseExpression)
        );
    }

    private static String memberName(Expression expression, String shortNamespace, String fullNamespace) {
        if (!(expression instanceof AccessExpression access) || !(access.object() instanceof IdentifierExpression identifier)) return null;
        String namespace = identifier.name().toLowerCase(Locale.ROOT);
        return namespace.equals(shortNamespace) || namespace.equals(fullNamespace) ? access.property().toLowerCase(Locale.ROOT) : null;
    }
}
