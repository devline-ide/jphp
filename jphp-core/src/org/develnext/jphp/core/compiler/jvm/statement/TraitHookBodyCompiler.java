package org.develnext.jphp.core.compiler.jvm.statement;

import org.develnext.jphp.core.compiler.jvm.node.MethodNodeImpl;
import org.objectweb.asm.tree.MethodNode;
import php.runtime.env.Environment;
import php.runtime.loader.dump.PhpTraitPropertyHookOrigin;
import php.runtime.reflection.support.PhpPropertyHooks;

/** Source import and PHB preflight share one exact donor/consumer physical protocol. */
final class TraitHookBodyCompiler {
    record TraceOrigin(String owner,int index) {}
    private TraitHookBodyCompiler() {}
    static MethodNodeImpl copy(PhpPropertyHooks.Hook hook,Environment env) {
        return MethodNodeImpl.duplicate(PhpTraitPropertyHookOrigin.source(hook,env).method());
    }
    static TraceOrigin rebind(MethodNode node,PhpPropertyHooks.Hook hook,Environment env,
            String targetOwner,String targetMethod,int targetTrace) {
        var source=PhpTraitPropertyHookOrigin.source(hook,env);
        var trace=PhpTraitPropertyHookOrigin.rebind(node,source,targetOwner,targetMethod,targetTrace);
        return new TraceOrigin(trace.owner(),trace.index());
    }
}
