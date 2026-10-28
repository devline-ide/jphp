package org.develnext.jphp.core.compiler.jvm;

import org.junit.Test;
import php.runtime.annotation.Reflection;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.*;
import php.runtime.ext.CoreExtension;
import php.runtime.ext.SPLExtension;
import php.runtime.lang.BaseWrapper;
import php.runtime.loader.dump.ModuleDumper;
import php.runtime.memory.ArrayMemory;
import php.runtime.memory.ObjectMemory;
import php.runtime.memory.support.MemoryOperation;
import php.runtime.reflection.ClassEntity;
import php.runtime.wrap.ClassWrapper;
import java.io.*;
import static org.junit.Assert.*;

/** Binding declarations may allow null even when the third-party Java implementation has no annotation. */
public class PhpClassWrapperNullableTest {
    public interface Box {}
    public static class NativeBox implements Box {}
    @Reflection.Name("CWNullableBox")
    public static class BoxWrapper extends BaseWrapper<Box> {
        public BoxWrapper(Environment env, Box value) { super(env, value); }
    }
    public interface Ops {
        String nullable(@Reflection.Nullable Box box);
        String required(Box box);
        @Reflection.Name("select") String one(@Reflection.Nullable Box box);
        @Reflection.Name("select") String two(Box left, Box right);
    }
    public static class NativeOps implements Ops {
        public String nullable(Box box) { return box == null ? "null" : "box"; }
        public String required(Box box) { return box == null ? "bad" : "required"; }
        public String one(Box box) { return box == null ? "one-null" : "one-box"; }
        public String two(Box left, Box right) { return left == null || right == null ? "bad" : "two-boxes"; }
    }
    @Reflection.Name("CWNullableOps") @Reflection.WrapInterface(Ops.class)
    public static class OpsWrapper extends BaseWrapper<NativeOps> {
        public OpsWrapper(Environment env, NativeOps value) { super(env, value); }
    }
    @Test public void interfaceNullableIsAttachedToItsOwnOverloadInSourceAndDump() throws Throwable {
        for (PhpLanguageProfile profile : new PhpLanguageProfile[]{PhpLanguageProfile.JPHP_LEGACY, PhpLanguageProfile.PHP_8_5})
            for (boolean debug : new boolean[]{false, true}) for (boolean dumped : new boolean[]{false, true}) {
                var scope = new CompileScope();
                scope.registerExtension(new CoreExtension()); scope.registerExtension(new SPLExtension());
                scope.setPhpLanguageProfile(profile); scope.setDebugMode(debug);
                MemoryOperation.registerWrapper(Box.class, BoxWrapper.class);
                MemoryOperation.registerWrapper(NativeOps.class, OpsWrapper.class);
                scope.registerClass(new ClassEntity(new ClassWrapper(scope, BoxWrapper.class)));
                var entity = new ClassEntity(new ClassWrapper(scope, OpsWrapper.class)); scope.registerClass(entity);
                assertTrue(entity.findMethod("nullable").getParameters(1)[0].isNullable());
                assertFalse(entity.findMethod("required").getParameters(1)[0].isNullable());
                assertTrue(entity.findMethod("select").getParameters(1)[0].isNullable());
                assertFalse(entity.findMethod("select").getParameters(2)[0].isNullable());
                var env = new Environment(scope, new ByteArrayOutputStream());
                try {
                    var context = new Context("$a=''; try {$ops->required(null);} catch (\\TypeError $e) {$a='required-rejected';} "
                            + "$b=''; try {$ops->select(null,$box);} catch (\\TypeError $e) {$b='overload-rejected';} "
                            + "return $ops->nullable(null).'|'.$ops->nullable($box).'|'.$ops->select(null).'|'.$ops->select($box,$box).'|'.$a.'|'.$b;");
                    context.setPhpLanguageProfile(profile);
                    var module = new JvmCompiler(env, context).compile(false);
                    if (dumped) {
                        var bytes = new ByteArrayOutputStream(); var dumper = new ModuleDumper(context, env, true);
                        dumper.save(module, bytes); module = dumper.load(new ByteArrayInputStream(bytes.toByteArray()));
                    }
                    scope.loadModule(module); scope.addUserModule(module); env.registerModule(module);
                    var locals = new ArrayMemory(true);
                    locals.refOfIndex("ops").assign(new ObjectMemory(new OpsWrapper(env, new NativeOps())));
                    locals.refOfIndex("box").assign(new ObjectMemory(new BoxWrapper(env, new NativeBox())));
                    assertEquals(profile + "/" + debug + "/" + dumped,
                            "null|box|one-null|two-boxes|required-rejected|overload-rejected", module.include(env, locals).toString());
                } finally { env.doFinal(); scope.getClassLoader().close(); }
            }
    }
}
