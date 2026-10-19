package org.develnext.jphp.core.compiler.jvm;

import org.junit.Test;
import php.runtime.annotation.Reflection;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.*;
import php.runtime.ext.CoreExtension;
import php.runtime.ext.SPLExtension;
import php.runtime.invoke.Invoker;
import php.runtime.lang.BaseWrapper;
import php.runtime.loader.dump.ModuleDumper;
import php.runtime.memory.ArrayMemory;
import php.runtime.memory.ObjectMemory;
import php.runtime.memory.StringMemory;
import php.runtime.memory.support.MemoryOperation;
import php.runtime.reflection.ClassEntity;
import php.runtime.wrap.ClassWrapper;
import java.io.*;
import static org.junit.Assert.*;

/** PHP aliases retain their explicit or wrapped receiver and overload group. */
public class PhpClassWrapperAliasTest {
    public interface Value { String value(); }
    public interface AliasedValue { @Reflection.Name("originalAlias") String value(); }

    @Reflection.Name("CWExplicitAlias") @Reflection.WrapInterface(Value.class)
    public static class Renamed extends BaseWrapper<Value> {
        public Renamed(Environment env, Value value) { super(env, value); }
        @Reflection.Signature @Reflection.Name("renamed") public String value() { return "explicit"; }
        @Reflection.Signature @Reflection.Name("join") public String first(String text) { return "one:" + text; }
        @Reflection.Signature @Reflection.Name("join") public String second(String left, String right) { return "two:" + left + right; }
    }
    @Reflection.Name("CWSameAlias") @Reflection.WrapInterface(AliasedValue.class)
    public static class Same extends BaseWrapper<AliasedValue> {
        public Same(Environment env, AliasedValue value) { super(env, value); }
        @Reflection.Signature @Reflection.Name("originalAlias") public String value() { return "same"; }
    }
    @Reflection.Name("CWOtherAlias") @Reflection.WrapInterface(AliasedValue.class)
    public static class Other extends BaseWrapper<AliasedValue> {
        public Other(Environment env, AliasedValue value) { super(env, value); }
        @Reflection.Signature @Reflection.Name("otherAlias") public String value() { return "other"; }
    }
    @Reflection.Name("CWCollisionAlias") @Reflection.WrapInterface(AliasedValue.class)
    public static class Collision extends BaseWrapper<AliasedValue> {
        public Collision(Environment env, AliasedValue value) { super(env, value); }
        @Reflection.Signature @Reflection.Name("originalAlias") public String differentJavaName() { return "collision"; }
    }
    private static void register(CompileScope scope, Class<?> value, Class<? extends BaseWrapper> wrapper) {
        MemoryOperation.registerWrapper(value, wrapper);
        scope.registerClass(new ClassEntity(new ClassWrapper(scope, wrapper)));
    }
    @Test public void aliasesKeepReceiverAndOverloadsSourceAndDump() throws Throwable {
        for (PhpLanguageProfile profile : new PhpLanguageProfile[]{PhpLanguageProfile.JPHP_LEGACY, PhpLanguageProfile.PHP_8_5})
            for (boolean debug : new boolean[]{false,true}) for (boolean dumped : new boolean[]{false,true}) {
                var scope = new CompileScope();
                scope.registerExtension(new CoreExtension()); scope.registerExtension(new SPLExtension());
                scope.setPhpLanguageProfile(profile); scope.setDebugMode(debug);
                register(scope, Value.class, Renamed.class);
                register(scope, AliasedValue.class, Same.class);
                register(scope, AliasedValue.class, Other.class);
                register(scope, AliasedValue.class, Collision.class);
                var env = new Environment(scope, new ByteArrayOutputStream());
                try {
                    var renamed = new Renamed(env, () -> "original");
                    var same = new Same(env, () -> "original-alias");
                    var other = new Other(env, () -> "original-alias");
                    var collision = new Collision(env, () -> "original-alias");
                    String source = "return $renamed->value().'|'.$renamed->renamed().'|'.$renamed->join('x').'|'"
                            + ".$renamed->join('x','y').'|'.$same->originalAlias().'|'.$other->originalAlias().'|'"
                            + ".$other->otherAlias().'|'.$collision->originalAlias();";
                    var context = new Context(source); context.setPhpLanguageProfile(profile);
                    var module = new JvmCompiler(env, context).compile(false);
                    if (dumped) {
                        var bytes = new ByteArrayOutputStream(); var dumper = new ModuleDumper(context, env, true);
                        dumper.save(module, bytes); module = dumper.load(new ByteArrayInputStream(bytes.toByteArray()));
                    }
                    scope.loadModule(module); scope.addUserModule(module); env.registerModule(module);
                    var locals = new ArrayMemory(true);
                    locals.refOfIndex("renamed").assign(new ObjectMemory(renamed));
                    locals.refOfIndex("same").assign(new ObjectMemory(same));
                    locals.refOfIndex("other").assign(new ObjectMemory(other));
                    locals.refOfIndex("collision").assign(new ObjectMemory(collision));
                    String label = profile + "/debug=" + debug + "/dump=" + dumped;
                    assertEquals(label, "original|explicit|one:x|two:xy|same|original-alias|other|collision", module.include(env, locals).toString());
                    var callback = new ArrayMemory(); callback.add(new ObjectMemory(renamed)); callback.add(new StringMemory("renamed"));
                    var invoker = Invoker.valueOf(env, TraceInfo.UNKNOWN, callback);
                    assertNotNull(label, invoker); assertEquals(label, "explicit", invoker.call().toString());
                } finally { env.doFinal(); scope.getClassLoader().close(); }
            }
    }
}
