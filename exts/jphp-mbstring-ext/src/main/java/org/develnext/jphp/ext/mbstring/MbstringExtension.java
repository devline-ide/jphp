package org.develnext.jphp.ext.mbstring;

import php.runtime.env.CompileScope;
import php.runtime.ext.support.Extension;

/** Optional PHP 8.5 subset; not a complete mbstring implementation. */
public final class MbstringExtension extends Extension {
    @Override public String getName() { return "mbstring"; }
    @Override public Status getStatus() { return Status.EXPERIMENTAL; }
    @Override public void onRegister(CompileScope scope) {
        for (var function : new MbstringFunctions().getFunctionSpecs()) {
            scope.registerPhp85Function(this, function);
            if (function.getName().equals("mb_internal_encoding"))
                scope.fetchPhp85Function(function.getName()).setReturnTypeChecker(
                        new php.runtime.reflection.support.PhpTypeDeclaration(java.util.List.of(
                                java.util.List.of("string"), java.util.List.of("bool"))));
        }
    }
}
