package io.github.hanhy06.emote.molang;

import team.unnamed.mocha.parser.ast.*;

import java.util.List;

final class MolangAstNormalizer {
    private MolangAstNormalizer() {
    }

    static List<Expression> normalize(List<Expression> expressions) {
        return expressions.stream().map(MolangAstNormalizer::normalize).toList();
    }

    private static Expression normalize(Expression expression) {
        if (expression instanceof TernaryConditionalExpression ternary) {
            return normalizeTernary(ternary);
        }
        if (expression instanceof BinaryExpression binary) {
            return new BinaryExpression(binary.op(), normalize(binary.left()), normalize(binary.right()));
        }
        if (expression instanceof UnaryExpression unary) {
            return new UnaryExpression(unary.op(), normalize(unary.expression()));
        }
        if (expression instanceof AccessExpression access) {
            return new AccessExpression(normalize(access.object()), access.property());
        }
        if (expression instanceof ArrayAccessExpression arrayAccess) {
            return new ArrayAccessExpression(normalize(arrayAccess.array()), normalize(arrayAccess.index()));
        }
        if (expression instanceof CallExpression call) {
            List<Expression> arguments = call.arguments().stream().map(MolangAstNormalizer::normalize).toList();
            return new CallExpression(normalize(call.function()), arguments);
        }
        if (expression instanceof ExecutionScopeExpression scope) {
            return new ExecutionScopeExpression(normalize(scope.expressions()));
        }
        return expression;
    }

    private static Expression normalizeTernary(TernaryConditionalExpression ternary) {
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
            return new BinaryExpression(
                BinaryExpression.Op.ASSIGN,
                normalize(assignment.left()),
                normalize(new TernaryConditionalExpression(assignment.right(), trueExpression, falseExpression))
            );
        }
        return new TernaryConditionalExpression(
            normalize(condition),
            normalize(trueExpression),
            normalize(falseExpression)
        );
    }
}
