package org.develnext.jphp.core.compiler.jvm;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import org.junit.Test;
import static org.junit.Assert.*;
import php.runtime.Memory;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.CompileScope;
import php.runtime.env.Context;
import php.runtime.env.Environment;
import php.runtime.ext.support.Extension;
import php.runtime.ext.support.compile.FunctionsContainer;
import php.runtime.loader.dump.ModuleDumper;

/** Native cancellation must unwind PHP finally without entering PHP catch(Throwable). */
public class PhpNativeUnwindFinallyTest {
    public static final class Signal extends php.runtime.exceptions.PhpUnwindSignal {}
    private static final Signal SIGNAL = new Signal();
    public static final class NativeFunctions extends FunctionsContainer {
        public static Memory jphp_test_native_unwind() { throw SIGNAL; }
    }
    public static final class NativeExtension extends Extension {
        @Override public Status getStatus() { return Status.EXPERIMENTAL; }
        @Override public void onRegister(CompileScope scope) { registerFunctions(new NativeFunctions()); }
    }
    private void check(String code, String expected) throws Throwable {
        for (var profile : PhpLanguageProfile.values())
            for (boolean debug : new boolean[]{false, true})
                for (boolean phb : new boolean[]{false, true}) {
                    var scope = new CompileScope();
                    scope.setPhpLanguageProfile(profile);
                    scope.registerExtension(new php.runtime.ext.CoreExtension());
                    scope.registerExtension(new NativeExtension());
                    var env = new Environment(scope, new ByteArrayOutputStream());
                    try {
                        var context = new Context(code);
                        context.setPhpLanguageProfile(profile);
                        var module = new JvmCompiler(env, context).compile(debug);
                        if (phb) {
                            var bytes = new ByteArrayOutputStream();
                            var codec = new ModuleDumper(context, env, debug);
                            codec.save(module, bytes);
                            module = codec.load(new ByteArrayInputStream(bytes.toByteArray()));
                        }
                        scope.loadModule(module);
                        scope.addUserModule(module);
                        env.registerModule(module);
                        try { module.include(env); fail("Native signal was swallowed"); }
                        catch (Signal actual) { assertSame(SIGNAL, actual); }
                        assertEquals(profile + " debug=" + debug + " PHB=" + phb,
                                expected, env.getGlobals().valueOfIndex("steps").toString());
                    } finally { env.doFinal(); }
                }
    }
    @Test public void cancellationRunsNestedFinallyAndBypassesPhpCatch() throws Throwable {
        check("$GLOBALS['steps']='';try{try{jphp_test_native_unwind();}"
                + "catch(Throwable $e){$GLOBALS['steps'].='caught;';}"
                + "finally{$GLOBALS['steps'].='inner;';}}"
                + "finally{$GLOBALS['steps'].='outer;';}", "inner;outer;");
    }
    @Test public void cancellationDuringReturnFinallyKeepsOuterUnwind() throws Throwable {
        check("$GLOBALS['steps']='';function pending(){try{return 1;}"
                + "finally{jphp_test_native_unwind();}}"
                + "try{pending();}finally{$GLOBALS['steps'].='outer;';}", "outer;");
    }
    @Test public void dumpDeclaresRuntimeGenerationRequiredByNativeFinally() throws Throwable {
        for (var profile : PhpLanguageProfile.values())
            for (boolean debug : new boolean[]{false, true}) {
                var scope = new CompileScope();
                scope.setPhpLanguageProfile(profile);
                scope.registerExtension(new php.runtime.ext.CoreExtension());
                var env = new Environment(scope, new ByteArrayOutputStream());
                try {
                    var context = new Context("$GLOBALS['steps']='';try{$GLOBALS['steps'].='body;';}"
                            + "finally{$GLOBALS['steps'].='finally;';}return 7;");
                    context.setPhpLanguageProfile(profile);
                    var module = new JvmCompiler(env, context).compile(debug);
                    var bytecode = new org.objectweb.asm.tree.ClassNode();
                    new org.objectweb.asm.ClassReader(module.getData()).accept(bytecode, 0);
                    boolean needsUnwindRuntime = bytecode.methods.stream().flatMap(m -> m.tryCatchBlocks.stream())
                            .anyMatch(h -> "php/runtime/exceptions/PhpUnwindSignal".equals(h.type));
                    assertTrue("Real finally bytecode must exercise the runtime dependency", needsUnwindRuntime);
                    var bytes = new ByteArrayOutputStream();
                    var codec = new ModuleDumper(context, env, debug);
                    codec.save(module, bytes);
                    var header = ModuleDumper.readHeader(new ByteArrayInputStream(bytes.toByteArray()));
                    assertTrue("Native-finally bytecode must reject pre-unwind ABI23 runtimes at the header",
                            header.getCompilerAbiVersion() >= 24);
                    assertTrue(module.getCompilerProvenance().getCompilerAbiVersion() >= 24);
                    module = codec.load(new ByteArrayInputStream(bytes.toByteArray()));
                    scope.loadModule(module);
                    scope.addUserModule(module);
                    env.registerModule(module);
                    assertEquals(7, module.include(env).toLong());
                    assertEquals("body;finally;", env.getGlobals().valueOfIndex("steps").toString());
                } finally { env.doFinal(); }
            }
    }
}
