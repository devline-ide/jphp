package org.develnext.jphp.ext.intl;

import php.runtime.env.CompileScope;
import php.runtime.ext.support.Extension;

/** Optional PHP 8.5 subset; not a complete intl implementation. */
public final class IntlExtension extends Extension {
    @Override public String getName() { return "intl"; }
    @Override public Status getStatus() { return Status.EXPERIMENTAL; }
    @Override public void onRegister(CompileScope scope) {
        for (var function : new IntlFunctions().getFunctionSpecs()) {
            scope.registerPhp85Function(this, function);
            if (function.getName().equals("grapheme_str_split"))
                scope.fetchPhp85Function(function.getName()).setReturnTypeChecker(
                        new php.runtime.reflection.support.PhpTypeDeclaration(java.util.List.of(
                                java.util.List.of("array"), java.util.List.of("false"))));
        }
    }
}
