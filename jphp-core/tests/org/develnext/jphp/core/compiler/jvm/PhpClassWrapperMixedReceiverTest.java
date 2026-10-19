package org.develnext.jphp.core.compiler.jvm;

import org.junit.Test;
import php.runtime.Memory;
import php.runtime.annotation.Reflection;
import php.runtime.common.HintType;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.*;
import php.runtime.ext.CoreExtension;
import php.runtime.ext.SPLExtension;
import php.runtime.invoke.Invoker;
import php.runtime.lang.BaseWrapper;
import php.runtime.loader.dump.ModuleDumper;
import php.runtime.memory.*;
import php.runtime.memory.support.MemoryOperation;
import php.runtime.reflection.ClassEntity;
import php.runtime.wrap.ClassWrapper;
import java.io.*;
import static org.junit.Assert.*;

/** The actual arity-selected native overload owns its receiver adapter. */
public class PhpClassWrapperMixedReceiverTest {
    public interface Box { String label(); }
    public static class NativeBox implements Box {
        private final String label;
        public NativeBox(String label) { this.label = label; }
        public String label() { return label; }
    }
    @Reflection.Name("CWMBox")
    public static class BoxWrapper extends BaseWrapper<Box> {
        public BoxWrapper(Environment env, Box value) { super(env, value); }
    }
    public interface Ops {
        String mixed(); String mixed(Box box); String mixed(Box left, Box right);
        @Reflection.Name("aliased") String alias(Box box);
        @Reflection.Name("aliased") String alias(Box left, Box right);
        String injected(Box box); String injected(Box left, Box right);
    }
    public static class NativeOps implements Ops {
        public String mixed() { return "wrapped0"; }
        public String mixed(Box box) { return "wrapped1:" + box.label(); }
        public String mixed(Box a, Box b) { return "wrapped2:" + a.label() + b.label(); }
        public String alias(Box box) { return "wrappedAlias1:" + box.label(); }
        public String alias(Box a, Box b) { return "wrappedAlias2:" + a.label() + b.label(); }
        public String injected(Box box) { return "wrappedInject1:" + box.label(); }
        public String injected(Box a, Box b) { return "wrappedInject2:" + a.label() + b.label(); }
    }
    @Reflection.Name("CWMMixed") @Reflection.WrapInterface(Ops.class)
    public static class Mixed extends BaseWrapper<NativeOps> {
        public Mixed(Environment env, NativeOps value) { super(env, value); }
        @Reflection.Signature public String mixed(Box box) { return "explicit1:" + box.label(); }
        @Reflection.Signature @Reflection.Name("renamed") public String mixed() { return "renamed0"; }
        @Reflection.Signature @Reflection.Name("ALIASED") public String otherJavaName(Box box) { return "explicitAlias1:" + box.label(); }
        @Reflection.Signature @Reflection.Name("injected") public String extra(Environment env, TraceInfo trace, Box box) {
            return (env == getEnvironment() && trace != null ? "explicitInject1:" : "badInjection:") + box.label();
        }
        @Reflection.Signature @Reflection.Name("join") public String first(String s) { return "one:" + s; }
        @Reflection.Signature @Reflection.Name("join") public String second(String a, String b) { return "two:" + a + b; }
    }
    public interface Tail { String choose(String... text); }
    public static class NativeTail implements Tail {
        public String choose(String... text) { return "wrappedTail:" + String.join("", text); }
    }
    @Reflection.Name("CWMTwo") @Reflection.WrapInterface(Tail.class)
    public static class Two extends BaseWrapper<NativeTail> {
        public Two(Environment env, NativeTail value) { super(env, value); }
        @Reflection.Signature @Reflection.Name("choose") public String two(String a, String b) { return "fixed2:" + a + b; }
    }
    @Reflection.Name("CWMOne") @Reflection.WrapInterface(Tail.class)
    public static class One extends BaseWrapper<NativeTail> {
        public One(Environment env, NativeTail value) { super(env, value); }
        @Reflection.Signature @Reflection.Name("choose") public String one(String a) { return "fixed1:" + a; }
    }
    @Reflection.Name("CWMDirectTail") @Reflection.WrapInterface(Tail.class)
    public static class DirectTail extends BaseWrapper<NativeTail> {
        public DirectTail(Environment env, NativeTail value) { super(env, value); }
        @Reflection.Signature @Reflection.Name("choose") public String otherJavaName(String... text) {
            return "explicitTail:" + String.join("", text);
        }
    }
    public interface SkipOps {
        @Reflection.Name("probe") String one(Environment env, TraceInfo trace, String value);
        @Reflection.Name("probe") String three(String a, String b, String c);
    }
    public static class NativeSkip implements SkipOps {
        @Reflection.Name("probe") public String one(Environment env, TraceInfo trace, String value) {
            return (env != null && trace != null ? "skip1:" : "badInjection:") + value;
        }
        @Reflection.Name("probe") public String three(String a, String b, String c) { return "skip3:" + a + b + c; }
    }
    @Reflection.Name("CWMSkip") @Reflection.WrapInterface(value=SkipOps.class, skipConflicts=true)
    public static class Skip extends BaseWrapper<NativeSkip> {
        public Skip(Environment env, NativeSkip value) { super(env, value); }
    }
    private static void register(CompileScope scope, Class<?> value, Class<? extends BaseWrapper> wrapper) {
        MemoryOperation.registerWrapper(value, wrapper);
        scope.registerClass(new ClassEntity(new ClassWrapper(scope, wrapper)));
    }
    private static Environment environment(PhpLanguageProfile profile, boolean debug) {
        var scope = new CompileScope(); scope.registerExtension(new CoreExtension()); scope.registerExtension(new SPLExtension());
        scope.setPhpLanguageProfile(profile); scope.setDebugMode(debug);
        register(scope, Box.class, BoxWrapper.class); register(scope, NativeOps.class, Mixed.class);
        register(scope, NativeTail.class, Two.class); register(scope, NativeTail.class, One.class);
        register(scope, NativeSkip.class, Skip.class);
        register(scope, NativeTail.class, DirectTail.class);
        return new Environment(scope, new ByteArrayOutputStream());
    }
    private static String execute(Environment env, PhpLanguageProfile profile, boolean dumped, String source, ArrayMemory locals) throws Throwable {
        var context = new Context(source); context.setPhpLanguageProfile(profile);
        var module = new JvmCompiler(env, context).compile(false);
        if (dumped) {
            var bytes = new ByteArrayOutputStream(); var dumper = new ModuleDumper(context, env, true);
            dumper.save(module, bytes); module = dumper.load(new ByteArrayInputStream(bytes.toByteArray()));
        }
        env.getScope().loadModule(module); env.getScope().addUserModule(module); env.registerModule(module);
        return module.include(env, locals).toString();
    }
    private static ArrayMemory locals(Object... pairs) {
        var locals = new ArrayMemory(true);
        for (int i = 0; i < pairs.length; i += 2) locals.refOfIndex((String) pairs[i]).assign((Memory) pairs[i + 1]);
        return locals;
    }
    private static Invoker callback(Environment env, BaseWrapper receiver, String name) {
        var callable = new ArrayMemory(); callable.add(new ObjectMemory(receiver)); callable.add(new StringMemory(name));
        var invoker = Invoker.valueOf(env, TraceInfo.UNKNOWN, callable); assertNotNull(invoker); return invoker;
    }
    @Test public void mixedFixedReceiversSourceAndDump() throws Throwable {
        for (PhpLanguageProfile profile : new PhpLanguageProfile[]{PhpLanguageProfile.JPHP_LEGACY, PhpLanguageProfile.PHP_8_5})
            for (boolean debug : new boolean[]{false,true}) for (boolean dumped : new boolean[]{false,true}) {
                var env = environment(profile, debug);
                try {
                    var object = new Mixed(env, new NativeOps());
                    var a = new ObjectMemory(new BoxWrapper(env, new NativeBox("a")));
                    var b = new ObjectMemory(new BoxWrapper(env, new NativeBox("b")));
                    String source = "return $o->mixed().'|'.$o->MiXeD($a).'|'.$o->mixed($a,$b).'|'"
                            + ".$o->renamed().'|'.$o->aliased($a).'|'.$o->ALiAsEd($a,$b).'|'"
                            + ".$o->injected($a).'|'.$o->injected($a,$b).'|'.$o->join('x').'|'.$o->join('x','y');";
                    String label = profile + "/debug=" + debug + "/dump=" + dumped;
                    assertEquals(label, "wrapped0|explicit1:a|wrapped2:ab|renamed0|explicitAlias1:a|wrappedAlias2:ab|explicitInject1:a|wrappedInject2:ab|one:x|two:xy",
                            execute(env, profile, dumped, source, locals("o",new ObjectMemory(object),"a",a,"b",b)));
                    assertEquals(label, "wrapped0", callback(env,object,"mixed").call().toString());
                    assertEquals(label, "explicit1:a", callback(env,object,"mixed").call(a).toString());
                    assertEquals(label, "wrapped2:ab", callback(env,object,"mixed").call(a,b).toString());
                    assertEquals(label, "wrappedAlias2:ab", callback(env,object,"ALIASED").call(a,b).toString());
                    // Object-overload entry must use the same actual selected adapter as IObject entry.
                    var method = object.getReflection().findMethod("mixed");
                    assertEquals(label,"explicit1:a", ((php.runtime.reflection.CompileMethodEntity)method).invokeDynamic((Object)object,env,TraceInfo.UNKNOWN,a).toString());
                    assertEquals(label,"wrapped2:ab", ((php.runtime.reflection.CompileMethodEntity)method).invokeDynamic((Object)object,env,TraceInfo.UNKNOWN,a,b).toString());
                    // Preserve the existing public raw-native Object receiver entry.
                    assertEquals(label,"wrapped2:ab", ((php.runtime.reflection.CompileMethodEntity)method).invokeDynamic((Object)object.getWrappedObject(),env,TraceInfo.UNKNOWN,a,b).toString());
                    try {
                        ((php.runtime.reflection.CompileMethodEntity)method).invokeDynamic((Object)object.getWrappedObject(),env,TraceInfo.UNKNOWN,a);
                        fail(label + ": explicit overload accepted a wrapped-native receiver");
                    } catch (IllegalArgumentException expected) { /* Reflection rejects the wrong exact receiver. */ }
                } finally { env.doFinal(); env.getScope().getClassLoader().close(); }
            }
    }
    @Test public void fixedAndWrappedVariadicSlotsSourceAndDump() throws Throwable {
        for (PhpLanguageProfile profile : new PhpLanguageProfile[]{PhpLanguageProfile.JPHP_LEGACY, PhpLanguageProfile.PHP_8_5})
            for (boolean debug : new boolean[]{false,true}) for (boolean dumped : new boolean[]{false,true}) {
                var env = environment(profile, debug);
                try {
                    var two = new Two(env,new NativeTail()); var one = new One(env,new NativeTail());
                    var direct = new DirectTail(env,new NativeTail());
                    String label = profile + "/debug=" + debug + "/dump=" + dumped;
                    assertEquals(label,"wrappedTail:ab|fixed2:xy|fixed1:z|explicitTail:ab",execute(env,profile,dumped,
                            "return $two->choose(array('a','b')).'|'.$two->choose('x','y').'|'.$one->choose('z').'|'.$direct->choose(array('a','b'));",
                            locals("two",new ObjectMemory(two),"one",new ObjectMemory(one),"direct",new ObjectMemory(direct))));
                    var array = new ArrayMemory(); array.add("a"); array.add("b");
                    assertEquals(label,"wrappedTail:ab",callback(env,two,"choose").call(array).toString());
                    assertEquals(label,"fixed2:xy",callback(env,two,"choose").call(new StringMemory("x"),new StringMemory("y")).toString());
                    assertEquals(label,"fixed1:z",callback(env,one,"choose").call(new StringMemory("z")).toString());
                    // Direct Object entry exercises wildcard selection with its existing typed-array transport.
                    var wrappedWildcard = (php.runtime.reflection.CompileMethodEntity)one.getReflection().findMethod("choose");
                    var directWildcard = (php.runtime.reflection.CompileMethodEntity)direct.getReflection().findMethod("choose");
                    // Raw entity invocation consumes prepared VALUE arguments; retain the caller's ARRAY separately.
                    assertEquals(label,"wrappedTail:ab",wrappedWildcard.invokeDynamic((Object)one,env,TraceInfo.UNKNOWN,array.toImmutable(),Memory.NULL).toString());
                    assertEquals(label,2,array.size());
                    assertEquals(label,"explicitTail:ab",directWildcard.invokeDynamic((Object)direct,env,TraceInfo.UNKNOWN,array.toImmutable(),Memory.NULL).toString());
                    assertEquals(label,2,array.size());
                    assertEquals(label,HintType.TRAVERSABLE,directWildcard.getParameters(2)[0].getType());
                    // Same stored arity masks only the fixed slot, retaining the wrapped wildcard metadata.
                    assertEquals(label,HintType.TRAVERSABLE,one.getReflection().findMethod("choose").getParameters(2)[0].getType());
                    assertEquals(label,1,one.getReflection().findMethod("choose").getParameters(1).length);
                    // StringMemoryOperation converts Java String but inherits the no-op metadata hint.
                    assertEquals(label,HintType.ANY,one.getReflection().findMethod("choose").getParameters(1)[0].getType());
                } finally { env.doFinal(); env.getScope().getClassLoader().close(); }
            }
    }
    @Test public void injectedSkipConflictsUsesExactPhpSlotSourceAndDump() throws Throwable {
        for (PhpLanguageProfile profile : new PhpLanguageProfile[]{PhpLanguageProfile.JPHP_LEGACY, PhpLanguageProfile.PHP_8_5})
            for (boolean debug : new boolean[]{false,true}) for (boolean dumped : new boolean[]{false,true}) {
                var env = environment(profile, debug);
                try {
                    var object = new Skip(env,new NativeSkip());
                    String label = profile + "/debug=" + debug + "/dump=" + dumped;
                    assertEquals(label,"skip1:x|skip3:abc",execute(env,profile,dumped,
                            "return $o->probe('x').'|'.$o->PrObE('a','b','c');",locals("o",new ObjectMemory(object))));
                    assertEquals(label,"skip1:x",callback(env,object,"probe").call(new StringMemory("x")).toString());
                    assertEquals(label,"skip3:abc",callback(env,object,"PROBE").call(new StringMemory("a"),new StringMemory("b"),new StringMemory("c")).toString());
                    assertEquals(label,1,object.getReflection().findMethod("probe").getParameters(1).length);
                    assertEquals(label,3,object.getReflection().findMethod("probe").getParameters(3).length);
                } finally { env.doFinal(); env.getScope().getClassLoader().close(); }
            }
    }
}
