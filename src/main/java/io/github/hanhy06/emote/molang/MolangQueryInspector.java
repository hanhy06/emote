package io.github.hanhy06.emote.molang;

import team.unnamed.mocha.parser.ast.AccessExpression;
import team.unnamed.mocha.parser.ast.ArrayAccessExpression;
import team.unnamed.mocha.parser.ast.BinaryExpression;
import team.unnamed.mocha.parser.ast.CallExpression;
import team.unnamed.mocha.parser.ast.ExecutionScopeExpression;
import team.unnamed.mocha.parser.ast.Expression;
import team.unnamed.mocha.parser.ast.IdentifierExpression;
import team.unnamed.mocha.parser.ast.TernaryConditionalExpression;
import team.unnamed.mocha.parser.ast.UnaryExpression;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class MolangQueryInspector {
    private MolangQueryInspector() {
    }

    static List<MolangEngine.QueryUse> inspect(List<Expression> expressions) {
        List<MolangEngine.QueryUse> uses = new ArrayList<>();
        expressions.forEach(expression -> inspect(expression, uses));
        return List.copyOf(uses);
    }

    private static void inspect(Expression expression, List<MolangEngine.QueryUse> uses) {
        if (expression instanceof CallExpression call) {
            String query = queryName(call.function());
            if (query != null) {
                uses.add(new MolangEngine.QueryUse(query, MolangEngine.QueryUseKind.CALL, call.arguments().size()));
            } else {
                inspect(call.function(), uses);
            }
            call.arguments().forEach(argument -> inspect(argument, uses));
            return;
        }
        if (expression instanceof BinaryExpression binary) {
            String query = binary.op() == BinaryExpression.Op.ASSIGN ? queryName(binary.left()) : null;
            if (query != null) {
                uses.add(new MolangEngine.QueryUse(query, MolangEngine.QueryUseKind.ASSIGNMENT, 0));
            } else {
                inspect(binary.left(), uses);
            }
            inspect(binary.right(), uses);
            return;
        }
        if (expression instanceof AccessExpression access) {
            String query = queryName(access);
            if (query != null) {
                uses.add(new MolangEngine.QueryUse(query, MolangEngine.QueryUseKind.VALUE, 0));
            } else {
                inspect(access.object(), uses);
            }
            return;
        }
        if (expression instanceof TernaryConditionalExpression ternary) {
            inspect(ternary.condition(), uses);
            inspect(ternary.trueExpression(), uses);
            inspect(ternary.falseExpression(), uses);
            return;
        }
        if (expression instanceof UnaryExpression unary) {
            inspect(unary.expression(), uses);
            return;
        }
        if (expression instanceof ArrayAccessExpression arrayAccess) {
            inspect(arrayAccess.array(), uses);
            inspect(arrayAccess.index(), uses);
            return;
        }
        if (expression instanceof ExecutionScopeExpression scope) {
            scope.expressions().forEach(child -> inspect(child, uses));
        }
    }

    private static String queryName(Expression expression) {
        if (!(expression instanceof AccessExpression access) || !(access.object() instanceof IdentifierExpression identifier)) {
            return null;
        }
        String namespace = identifier.name().toLowerCase(Locale.ROOT);
        return namespace.equals("q") || namespace.equals("query") ? access.property().toLowerCase(Locale.ROOT) : null;
    }
}
