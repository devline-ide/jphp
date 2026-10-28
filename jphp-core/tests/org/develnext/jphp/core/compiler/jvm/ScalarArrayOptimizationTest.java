package org.develnext.jphp.core.compiler.jvm;

import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.util.CheckClassAdapter;
import php.runtime.Memory;
import php.runtime.common.*;
import php.runtime.env.*;
import php.runtime.loader.dump.ModuleDumper;
import php.runtime.reflection.*;

import java.io.*;
import java.util.*;

import static org.junit.Assert.*;

/** Opt-in proof, runtime guards, bytecode and PHB parity. */
public class ScalarArrayOptimizationTest extends JvmCompilerCase {
    private static final String BODY = "$values=[$seed,2,3,4];$sum=0;foreach($values as $value){$sum+=$value;}return $sum;";

    private final class Fixture implements AutoCloseable {
        final Environment env;
        final ModuleEntity module;
        final Memory value;
        Fixture(String methods, String expression, boolean enabled, boolean phb, boolean debug,
                PhpOptimizationProfile numeric, PhpRuntimeOwnershipMode ownership) throws Throwable {
            var scope = newScope(); scope.setDebugMode(debug);
            env = new Environment(scope, new ByteArrayOutputStream());
            Context context = new Context("class Reduction {"+methods+"} return "+expression+";");
            context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
            context.setPhpOptimizationProfile(numeric); context.setPhpOwnershipMode(ownership);
            context.setPhpArrayOptimizationEnabled(enabled);
            ModuleEntity compiled = new JvmCompiler(env, context).compile(false);
            if (phb) {
                var dumper = new ModuleDumper(context, env, true);
                var output = new ByteArrayOutputStream(); dumper.save(compiled, output);
                assertEquals(enabled, ModuleDumper.readHeader(new ByteArrayInputStream(output.toByteArray())).isArrayOptimizationEnabled());
                compiled = dumper.load(new ByteArrayInputStream(output.toByteArray()));
            }
            module = compiled;
            // Artifact policy survives a different default at runtime.
            scope.setPhpArrayOptimizationEnabled(!enabled);
            scope.loadModule(module); scope.addUserModule(module); env.registerModule(module);
            value = module.include(env);
            assertEquals(enabled, module.isArrayOptimizationEnabled());
            for (ClassEntity entity : module.getClasses()) {
                StringWriter errors = new StringWriter();
                CheckClassAdapter.verify(new ClassReader(entity.getData()), scope.getClassLoader(), false, new PrintWriter(errors));
                assertEquals(errors.toString(), "", errors.toString());
            }
        }
        ClassNode bytes() {
            var node = new ClassNode(); new ClassReader(env.fetchClass("Reduction", false).getData()).accept(node, 0); return node;
        }
        MethodNode kernel(String name) {
            return bytes().methods.stream().filter(m -> m.name.equals("__$scalar$"+name+"$"+module.getOptimizationProfile().getId())).findFirst().orElse(null);
        }
        @Override public void close() throws Exception { try { env.doFinal(); } catch (Throwable e) { throw new AssertionError(e); } }
    }

    private Fixture fixture(String methods, String expression, boolean enabled, boolean phb) throws Throwable {
        return new Fixture(methods, expression, enabled, phb, false, PhpOptimizationProfile.COMPATIBLE, PhpRuntimeOwnershipMode.AUTO);
    }

    @Test public void sourceAndPhbEliminateOnlyWhenEnabled() throws Throwable {
        for (boolean phb : new boolean[]{false,true}) for (boolean enabled : new boolean[]{false,true}) {
            try (var f = fixture("public static function total($seed){"+BODY+"} public function local($seed){"+BODY+"}",
                    "[Reduction::total(12345),(new Reduction)->local(-88),Reduction::total(seed:1024)]", enabled, phb)) {
                assertEquals(12354, f.value.valueOfIndex(null,0).toLong());
                assertEquals(-79, f.value.valueOfIndex(null,1).toLong());
                assertEquals(1033, f.value.valueOfIndex(null,2).toLong());
                for (String name : List.of("total","local")) {
                    MethodNode kernel = f.kernel(name); assertEquals(enabled, kernel != null);
                    if (kernel != null) for (var node : kernel.instructions) {
                        if (node instanceof MethodInsnNode call) {
                            assertFalse(call.owner.contains("ArrayMemory")); assertFalse(call.owner.contains("ForeachIterator"));
                            assertFalse(call.owner.contains("PhpArithmetic"));
                        }
                    }
                }
            }
        }
    }

    @Test public void namesAndInputsAreNotSpecialCased() throws Throwable {
        Random random = new Random(0x1950ab);
        for (int length : new int[]{0,1,7,31,64}) {
            StringBuilder entries = new StringBuilder("$input"); long offset = 0;
            for (int i=1;i<length;i++) { int n=random.nextInt(200000)+10001; entries.append(',').append(n); offset+=n; }
            if (length==0) entries.setLength(0);
            String method = "public function renamed($input){$bucket=["+entries+"];$acc=0;foreach($bucket as $item){$acc=$acc+$item;}return $acc;}";
            StringJoiner calls = new StringJoiner(",", "[", "]"); long[] expected = new long[24];
            for(int i=0;i<expected.length;i++) { long n=random.nextInt(2000000)-1000000; calls.add("(new Reduction)->renamed("+n+")");expected[i]=length==0?0:n+offset; }
            for(boolean phb:new boolean[]{false,true}) try(var f=fixture(method,calls.toString(),true,phb)) {
                assertNotNull(f.kernel("renamed"));
                for(int i=0;i<expected.length;i++)assertEquals(expected[i],f.value.valueOfIndex(null,i).toLong());
            }
        }
    }

    @Test public void guardsRetainCoercionReferencesAndOverflow() throws Throwable {
        String expressions="[Reduction::total('12345'),Reduction::total(0.5),Reduction::total(null),Reduction::total(true),Reduction::total(PHP_INT_MAX),Reduction::total(PHP_INT_MIN),Reduction::total($reference)]";
        // Caller-side PHP binding owns coercion/reference semantics before the specialized entry.
        expressions="(function(){$reference=12345;return "+expressions+";})()";
        for(boolean phb:new boolean[]{false,true}) {
            try(var ordinary=fixture("public static function total($seed){"+BODY+"}",expressions,false,phb);
                var optimized=fixture("public static function total($seed){"+BODY+"}",expressions,true,phb)) {
                for(int i=0;i<7;i++) {
                    Memory a=ordinary.value.valueOfIndex(null,i).toValue(),b=optimized.value.valueOfIndex(null,i).toValue();
                    assertEquals(a.type,b.type);assertEquals(a.toString(),b.toString());
                }
                assertEquals(Memory.Type.DOUBLE,optimized.value.valueOfIndex(null,4).toValue().type);
            }
        }
    }

    @Test public void wrappingDebugAndCheckedRemainIndependent() throws Throwable {
        for(boolean phb:new boolean[]{false,true}) {
            try(var f=new Fixture("public function total($seed){"+BODY+"}","(new Reduction)->total(PHP_INT_MAX)",true,phb,false,PhpOptimizationProfile.FAST_INT64_WRAP,PhpRuntimeOwnershipMode.AUTO)) {
                assertNotNull(f.kernel("total"));assertEquals(Long.MIN_VALUE+8,f.value.toLong());
            }
            for(boolean debug:new boolean[]{false,true})try(var f=new Fixture("public function total($seed){"+BODY+"}","(new Reduction)->total(700)",true,phb,debug,PhpOptimizationProfile.COMPATIBLE,debug?PhpRuntimeOwnershipMode.AUTO:PhpRuntimeOwnershipMode.CHECKED)) {
                assertNull(f.kernel("total"));assertEquals(709,f.value.toLong());
            }
        }
    }

    @Test public void unsupportedArraySemanticsKeepOriginalMethod() throws Throwable {
        List<String> bodies=List.of(
            BODY.replace("as $value", "as &$value"),
            BODY.replace("as $value", "as $key=>$value"),
            BODY.replace("[$seed,2,3,4]", "['key'=>$seed,2,3,4]"),
            BODY.replace("[$seed,2,3,4]", "[$seed,2.5,3,4]"),
            BODY.replace("$sum+=$value;", "$sum+=$value;$values[]=9;"),
            BODY.replace("$sum=0;", "$copy=$values;$sum=0;"),
            BODY.replace("$sum=0;", "$sum=0;$values[0]=8;"),
            BODY.replace("$sum+=$value;", "$sum+=abs($value);"));
        for(boolean phb:new boolean[]{false,true})for(String body:bodies)try(var f=fixture("public static function total($seed){"+body+"}","Reduction::total(40)",true,phb)) {
            assertNull(body,f.kernel("total"));assertTrue(f.value.toDouble()>0);
        }
    }

    @Test public void arrayPolicySeparatesSharedSourceCache() throws Throwable {
        var scope=newScope();var env=new Environment(scope,new ByteArrayOutputStream());
        try {
            Context off=new Context(new ByteArrayInputStream("return 7;".getBytes(java.nio.charset.StandardCharsets.UTF_8)),"memory://array-policy.php",java.nio.charset.StandardCharsets.UTF_8);
            off.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);off.setPhpArrayOptimizationEnabled(false);
            var first=env.importModule(off);
            Context on=new Context(new ByteArrayInputStream("return 7;".getBytes(java.nio.charset.StandardCharsets.UTF_8)),"memory://array-policy.php",java.nio.charset.StandardCharsets.UTF_8);on.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);on.setPhpArrayOptimizationEnabled(true);
            var second=env.importModule(on);assertNotSame(first,second);
            assertFalse(first.isArrayOptimizationEnabled());assertTrue(second.isArrayOptimizationEnabled());
            assertSame(first,env.importModule(off));assertSame(second,env.importModule(on));
            assertNotEquals(new ModuleCacheKey("res://a.php",false,PhpLanguageProfile.PHP_8_5,"",PhpOptimizationProfile.COMPATIBLE,PhpRuntimeOwnershipMode.AUTO,false),
                new ModuleCacheKey("res://a.php",false,PhpLanguageProfile.PHP_8_5,"",PhpOptimizationProfile.COMPATIBLE,PhpRuntimeOwnershipMode.AUTO,true));
        } finally { env.doFinal(); }
    }

    @Test public void headerDefaultAndInvalidProvenanceAreExplicit() throws Throwable {
        for(int abi:new int[]{30,31,32,33}) {
            var bytes=new ByteArrayOutputStream();var out=new DataOutputStream(bytes);
            out.writeInt(ModuleDumper.DUMP_STAMP);out.writeInt(ModuleDumper.DUMP_VERSION);out.writeInt(abi);
            out.writeInt(PhpLanguageProfile.PHP_8_5.getId());out.writeInt(PhpOptimizationProfile.COMPATIBLE.getId());
            if(abi>=31)out.writeInt(PhpRuntimeOwnershipMode.AUTO.getId());
            if(abi>=33)out.writeBoolean(true);
            var header=ModuleDumper.readHeader(new ByteArrayInputStream(bytes.toByteArray()));
            assertEquals(abi>=33,header.isArrayOptimizationEnabled());
        }
        try(var f=fixture("public function total($seed){"+BODY+"}","(new Reduction)->total(5)",true,false)) {
            f.module.setPhpLanguageProfile(PhpLanguageProfile.JPHP_LEGACY);
            try {new ModuleDumper(f.module.getContext(),f.env,true).save(f.module,new ByteArrayOutputStream());fail("Legacy array policy accepted");}
            catch(php.runtime.loader.dump.io.DumpException expected) {assertTrue(expected.getMessage().contains("PHP_8_5"));}
        }
    }

    @Test public void instanceVisibilityInheritanceAndOverrideKeepPhpDispatch() throws Throwable {
        String methods="private function hidden($seed){"+BODY+"} protected function guarded($seed){"+BODY+"} public function total($seed){"+BODY+"} "
                +"public function viaPrivate($seed){return $this->hidden($seed);} public function viaProtected($seed){return $this->guarded($seed);}";
        String expression="(function(){class ChildReduction extends Reduction { public function total($seed){return 700+$seed;} } "
                +"$a=new Reduction;$b=new ChildReduction;return [$a->viaPrivate(40),$b->viaPrivate(40),$b->viaProtected(40),$b->total(40),$a->total(40)];})()";
        for(boolean phb:new boolean[]{false,true})try(var f=fixture(methods,expression,true,phb)) {
            long[] expected={49,49,49,740,49};
            for(int i=0;i<expected.length;i++)assertEquals(expected[i],f.value.valueOfIndex(null,i).toLong());
            assertNotNull(f.kernel("hidden"));assertNotNull(f.kernel("guarded"));
        }
    }
}
