package io.github.hanhy06.emote.molang;

import team.unnamed.mocha.parser.ast.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class MolangExpressionInspector {
    private MolangExpressionInspector() {
    }

    static Inspection inspect(List<Expression> expressions) {
        InspectionBuilder inspection = new InspectionBuilder();
        expressions.forEach(expression -> inspect(expression, inspection));
        return new Inspection(List.copyOf(inspection.queryUses), inspection.assignsPersistentVariables);
    }

    private static void inspect(Expression expression, InspectionBuilder inspection) {
        if (expression instanceof CallExpression call) {
            String query = memberName(call.function(), "q", "query");
            if (query != null) {
                inspection.queryUses.add(new MolangEngine.QueryUse(query, MolangEngine.QueryUseKind.CALL, call.arguments().size()));
            } else {
                inspect(call.function(), inspection);
            }
            call.arguments().forEach(argument -> inspect(argument, inspection));
            return;
        }
        if (expression instanceof BinaryExpression binary) {
            if (binary.op() == BinaryExpression.Op.ASSIGN) {
                String query = memberName(binary.left(), "q", "query");
                if (query != null) {
                    inspection.queryUses.add(new MolangEngine.QueryUse(query, MolangEngine.QueryUseKind.ASSIGNMENT, 0));
                } else {
                    inspection.assignsPersistentVariables |= memberName(binary.left(), "v", "variable") != null;
                    inspect(binary.left(), inspection);
                }
            } else {
                inspect(binary.left(), inspection);
            }
            inspect(binary.right(), inspection);
            return;
        }
        if (expression instanceof AccessExpression access) {
            String query = memberName(access, "q", "query");
            if (query != null) {
                inspection.queryUses.add(new MolangEngine.QueryUse(query, MolangEngine.QueryUseKind.VALUE, 0));
            } else {
                inspect(access.object(), inspection);
            }
            return;
        }
        if (expression instanceof TernaryConditionalExpression ternary) {
            inspect(ternary.condition(), inspection);
            inspect(ternary.trueExpression(), inspection);
            inspect(ternary.falseExpression(), inspection);
            return;
        }
        if (expression instanceof UnaryExpression unary) {
            inspect(unary.expression(), inspection);
            return;
        }
        if (expression instanceof ArrayAccessExpression arrayAccess) {
            inspect(arrayAccess.array(), inspection);
            inspect(arrayAccess.index(), inspection);
            return;
        }
        if (expression instanceof ExecutionScopeExpression scope) {
            scope.expressions().forEach(child -> inspect(child, inspection));
        }
    }

    private static String memberName(Expression expression, String shortNamespace, String fullNamespace) {
        if (!(expression instanceof AccessExpression access) || !(access.object() instanceof IdentifierExpression identifier)) {
            return null;
        }
        String namespace = identifier.name().toLowerCase(Locale.ROOT);
        return namespace.equals(shortNamespace) || namespace.equals(fullNamespace)
            ? access.property().toLowerCase(Locale.ROOT)
            : null;
    }

    record Inspection(List<MolangEngine.QueryUse> queryUses, boolean assignsPersistentVariables) {
    }

    private static final class InspectionBuilder {
        private final List<MolangEngine.QueryUse> queryUses = new ArrayList<>();
        private boolean assignsPersistentVariables;
    }
}
