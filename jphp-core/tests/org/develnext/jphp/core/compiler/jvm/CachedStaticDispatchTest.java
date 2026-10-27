package org.develnext.jphp.core.compiler.jvm;

import org.junit.Test;
import php.runtime.Memory;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.Context;
import php.runtime.env.Environment;
import php.runtime.env.TraceInfo;
import php.runtime.invoke.InvokeHelper;
import php.runtime.invoke.cache.MethodCallCache;
import php.runtime.loader.dump.ModuleDumper;
import php.runtime.memory.LongMemory;
import php.runtime.memory.ReferenceMemory;
import php.runtime.memory.StringMemory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Locale;

import static org.junit.Assert.*;

/** A cache hit must retain the full PHP boundary and exact resolution context. */
public class CachedStaticDispatchTest extends JvmCompilerCase {
    private static final String SOURCE = "class DispatchBase {"
            + "public static function who(){return static::class;}"
            + "private static function secret(){return 19;}"
            + "public static function typed(int $value):int{return $value;}"
            + "public static function bump(&$value){$value+=2;return $value;}"
            + "public static function bad():int{return 'wrong';}"
            + "}class DispatchChild extends DispatchBase {}"
            + "class DispatchMagic {public static function __callStatic($name,$args){return $name;}}return 0;";

    @Test public void receiverForwardingAndLexicalScopeRemainPartOfResolution() throws Throwable {
        for (boolean dumped : new boolean[]{false, true}) {
            var env = new Environment(newScope(), new ByteArrayOutputStream());
            try {
                TraceInfo trace = load(env, dumped);
                var cache = new MethodCallCache();
                for (int i = 0; i < 3; i++) {
                    assertEquals("DispatchBase", call(env, trace, cache, "DispatchBase", "who").toString());
                    assertEquals("DispatchChild", call(env, trace, cache, "DispatchChild", "who").toString());
                }
                env.pushCall(trace, null, new Memory[0], "scope", "DispatchBase", "DispatchChild");
                try {
                    assertEquals(19, call(env, trace, cache, "DispatchBase", "secret").toLong());
                    assertEquals(19, call(env, trace, cache, "DispatchBase", "secret").toLong());
                    assertEquals("DispatchChild", InvokeHelper.callStatic(env, trace, "dispatchbase", "who",
                            "DispatchBase", "who", new Memory[0], cache, 1, true).toString());
                    assertEquals("DispatchChild", InvokeHelper.callStatic(env, trace, "dispatchbase", "who",
                            "DispatchBase", "who", new Memory[0], cache, 1, true).toString());
                } finally { env.popCall(); }
                Throwable denied = assertThrows(Throwable.class,
                        () -> call(env, trace, cache, "DispatchBase", "secret"));
                assertTrue(denied.toString(), denied.toString().contains("private"));
                assertEquals("first", call(env, trace, cache, "DispatchMagic", "first").toString());
                assertEquals("second", call(env, trace, cache, "DispatchMagic", "second").toString());
            } finally { env.doFinal(); }
        }
    }

    @Test public void cachedCallsKeepCoercionReferencesAndReturnDiagnostics() throws Throwable {
        for (boolean dumped : new boolean[]{false, true}) {
            var env = new Environment(newScope(), new ByteArrayOutputStream());
            try {
                TraceInfo trace = load(env, dumped);
                var cache = new MethodCallCache();
                Memory original = StringMemory.valueOf("7");
                Memory[] arguments = {original};
                for (int i = 0; i < 3; i++) {
                    assertEquals(7, call(env, trace, cache, "DispatchBase", "typed", arguments).toLong());
                    assertSame(original, arguments[0]);
                }
                var reference = new ReferenceMemory(LongMemory.valueOf(3));
                assertEquals(5, call(env, trace, cache, "DispatchBase", "bump", reference).toLong());
                assertEquals(7, call(env, trace, cache, "DispatchBase", "bump", reference).toLong());
                assertEquals(7, reference.toLong());
                for (int i = 0; i < 2; i++) {
                    Throwable failure = assertThrows(Throwable.class,
                            () -> call(env, trace, cache, "DispatchBase", "bad"));
                    assertTrue(failure.toString(), failure.toString().contains("DispatchBase::bad"));
                }
                env.pushCall(trace, null, new Memory[0], "scope", "DispatchBase", "DispatchBase");
                try {
                    assertEquals(9, call(env, null, cache, "DispatchBase", "typed", LongMemory.valueOf(9)).toLong());
                    assertEquals(9, call(env, null, cache, "DispatchBase", "typed", LongMemory.valueOf(9)).toLong());
                } finally { env.popCall(); }
            } finally { env.doFinal(); }
        }
    }

    @Test public void nullTraceKeepsTheExistingSubstitutedCallerProfile() throws Throwable {
        var env = new Environment(newScope(), new ByteArrayOutputStream());
        try {
            load(env, false);
            var context = new Context("return 0;");
            context.setPhpLanguageProfile(PhpLanguageProfile.JPHP_LEGACY);
            var legacy = new JvmCompiler(env, context).compile(false);
            env.getScope().loadModule(legacy); env.getScope().addUserModule(legacy); env.registerModule(legacy);
            var trace = TraceInfo.valueOf(context.getFileName(), 0L, 0L, legacy.getInternalName());
            var cache = new MethodCallCache();
            env.pushCall(trace, null, new Memory[0], "caller", "DispatchBase", "DispatchChild");
            try {
                assertEquals(PhpLanguageProfile.PHP_8_5, env.getModuleManager().getPhpLanguageProfile(null));
                assertEquals(PhpLanguageProfile.JPHP_LEGACY, env.getModuleManager().getPhpLanguageProfile(env.trace()));
                for (int i = 0; i < 3; i++)
                    assertEquals("DispatchBase", call(env, null, cache, "DispatchChild", "who").toString());
            } finally { env.popCall(); }
        } finally { env.doFinal(); }
    }

    private TraceInfo load(Environment env, boolean dumped) throws Throwable {
        env.getScope().setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
        var context = new Context(SOURCE);
        context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
        var module = new JvmCompiler(env, context).compile(false);
        if (dumped) {
            var dumper = new ModuleDumper(context, env, true);
            var bytes = new ByteArrayOutputStream(); dumper.save(module, bytes);
            module = dumper.load(new ByteArrayInputStream(bytes.toByteArray()));
        }
        env.getScope().loadModule(module); env.getScope().addUserModule(module); env.registerModule(module);
        module.include(env);
        return TraceInfo.valueOf(context.getFileName(), 0L, 0L, module.getInternalName());
    }

    private static Memory call(Environment env, TraceInfo trace, MethodCallCache cache,
                               String owner, String method, Memory... args) throws Throwable {
        return InvokeHelper.callStatic(env, trace, owner.toLowerCase(Locale.ROOT), method.toLowerCase(Locale.ROOT),
                owner, method, args, cache, 0);
    }
}
