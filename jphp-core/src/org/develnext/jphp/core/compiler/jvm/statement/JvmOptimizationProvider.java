package org.develnext.jphp.core.compiler.jvm.statement;

import org.develnext.jphp.core.tokenizer.token.stmt.BodyStmtToken;
import org.develnext.jphp.core.tokenizer.token.stmt.MethodStmtToken;
import java.util.List;
import java.util.ServiceLoader;

/** Optional compiler extensions. Original PHP bodies remain available for guarded fallback. */
public interface JvmOptimizationProvider {
    /** The standard scalar verifier proves this complete projection. Never mutate source nodes. */
    default BodyStmtToken project(ClassStmtCompiler owner, MethodStmtToken method) { return null; }
    /** Emit an additional guarded entry only after proving the entire original method. */
    default void emit(MethodStmtCompiler method) {}

    static BodyStmtToken projectScalarBody(ClassStmtCompiler owner, MethodStmtToken method) {
        for (JvmOptimizationProvider provider : Providers.ALL) {
            BodyStmtToken body = provider.project(owner, method);
            if (body != null) return body;
        }
        return null;
    }

    static void emitEntries(MethodStmtCompiler method) {
        for (JvmOptimizationProvider provider : Providers.ALL) provider.emit(method);
    }

    final class Providers {
        private static final List<JvmOptimizationProvider> ALL = ServiceLoader.load(
                JvmOptimizationProvider.class, JvmOptimizationProvider.class.getClassLoader()).stream()
                .map(ServiceLoader.Provider::get).toList();
        private Providers() {}
    }
}
