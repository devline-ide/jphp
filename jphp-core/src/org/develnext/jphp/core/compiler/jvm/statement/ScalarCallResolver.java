package org.develnext.jphp.core.compiler.jvm.statement;

import org.develnext.jphp.core.tokenizer.token.expr.operator.ArgumentUnpackExprToken;
import org.develnext.jphp.core.tokenizer.token.expr.value.CallArgument;
import org.develnext.jphp.core.tokenizer.token.expr.value.CallExprToken;
import org.develnext.jphp.core.tokenizer.token.expr.value.NameToken;
import org.develnext.jphp.core.tokenizer.token.expr.value.SelfExprToken;
import org.develnext.jphp.core.tokenizer.token.expr.value.StaticAccessExprToken;
import org.develnext.jphp.core.tokenizer.token.stmt.ClassStmtToken;
import org.develnext.jphp.core.tokenizer.token.stmt.ExprStmtToken;
import org.develnext.jphp.core.tokenizer.token.stmt.MethodStmtToken;
import php.runtime.common.Modifier;

import java.util.List;

/** Lexically closed same-class calls only; scalar body analysis still proves the target and operands. */
public final class ScalarCallResolver {
    private ScalarCallResolver() {}

    public record Target(MethodStmtToken method, List<ExprStmtToken> arguments) {
        public Target { arguments = List.copyOf(arguments); }
    }

    public static Target resolve(ClassStmtCompiler clazz, CallExprToken call) {
        return clazz == null ? null : resolve(clazz.statement, call);
    }

    public static Target resolve(ClassStmtToken clazz, CallExprToken call) {
        if (clazz == null || call == null || clazz.isInterface() || clazz.isTrait()
                || clazz.getMethods() == null || call.isFirstClassCallable() || !(call.getName() instanceof StaticAccessExprToken access)
                || access.isDynamicConstant() || access.getFieldExpr() != null
                || !(access.getField() instanceof NameToken name)) return null;
        if (!(access.getClazz() instanceof SelfExprToken)) {
            if (!(access.getClazz() instanceof NameToken owner)
                    || !normalize(owner.getName()).equals(normalize(clazz.getFulledName()))) return null;
        }
        List<ExprStmtToken> arguments = call.getParameters();
        if (arguments == null || arguments.stream().anyMatch(argument -> argument == null
                || argument.getTokens().stream().anyMatch(ArgumentUnpackExprToken.class::isInstance))) return null;
        if (call.getArguments() != null && call.getArguments().stream()
                .anyMatch(argument -> argument.getKind() != CallArgument.Kind.POSITIONAL)) return null;
        for (MethodStmtToken method : clazz.getMethods()) {
            if (method.getName() == null || !normalize(method.getName().getName()).equals(normalize(name.getName()))) continue;
            if (!method.isStatic() || method.isAbstract() || method.getBody() == null
                    || !clazz.isFinal() && !method.isFinal() && method.getModifier() != Modifier.PRIVATE
                    || !method.getPhpAttributes().isEmpty()
                    || method.getClazz() != null && method.getClazz() != clazz
                    || method.isReturnReference() || method.isGenerator()
                    || method.getArguments().size() != arguments.size()
                    || method.getArguments().stream().anyMatch(argument -> argument.isReference()
                        || argument.isVariadic() || argument.getValue() != null)) return null;
            return new Target(method, arguments);
        }
        // Inherited, cross-module and dynamic methods keep the PHP call path.
        return null;
    }

    private static String normalize(String name) {
        String value = name.startsWith("\\") ? name.substring(1) : name;
        StringBuilder normalized = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char c = value.charAt(index);
            normalized.append(c >= 'A' && c <= 'Z' ? (char) (c + ('a' - 'A')) : c);
        }
        return normalized.toString();
    }
}
