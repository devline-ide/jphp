package org.develnext.jphp.core.compiler.jvm;

import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.util.CheckClassAdapter;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.*;
import php.runtime.exceptions.support.ErrorException;
import php.runtime.loader.dump.ModuleDumper;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.Assert.*;

public class PhpConstantDeclarationsTest extends JvmCompilerCase {
    @Test public void testLazyValuesAreIsolatedAcrossConcurrentEnvironments() throws Throwable {
        for (boolean dumped : new boolean[] {false, true}) {
            var scope = newScope();
            Environment compilerEnv = new Environment(scope, new ByteArrayOutputStream());
            var workers = java.util.concurrent.Executors.newFixedThreadPool(8);
            try {
                var module = lazyModule(compilerEnv,
                        "class LazyShared {const int X=LAZY_VALUE;const array ITEMS=[self::X];const \\Closure FN=static function(){static $n=0;return [LAZY_VALUE,++$n];};public static $items=self::ITEMS;}"
                        + "class LazyCycle {const A=self::B;const B=self::A;}"
                        + "try {LazyCycle::A;}catch(Throwable $e){}"
                        + "return [LazyShared::X,LazyShared::ITEMS,LazyShared::$items,(LazyShared::FN)()];", dumped);
                scope.setPhpLanguageProfile(PhpLanguageProfile.JPHP_LEGACY);
                scope.loadModule(module); scope.addUserModule(module);
                var start = new java.util.concurrent.CountDownLatch(1);
                List<java.util.concurrent.Future<?>> jobs = new ArrayList<>();
                for (int i=0; i<32; i++) {
                    final long expected = i*7L;
                    jobs.add(workers.submit(() -> {
                        start.await();
                        Environment env = new Environment(scope, new ByteArrayOutputStream());
                        try {
                            assertTrue(env.defineConstant("LAZY_VALUE", php.runtime.memory.LongMemory.valueOf(expected), true));
                            env.registerModule(module);
                            for (int pass=0; pass<8; pass++) {
                                var result = module.include(env);
                                assertEquals(expected, result.valueOfIndex(0).toLong());
                                assertEquals(expected, result.valueOfIndex(1).valueOfIndex(0).toLong());
                                assertEquals(expected, result.valueOfIndex(2).valueOfIndex(0).toLong());
                                assertEquals(expected, result.valueOfIndex(3).valueOfIndex(0).toLong());
                                assertEquals(pass+1, result.valueOfIndex(3).valueOfIndex(1).toLong());
                                result.valueOfIndex(1).toValue(php.runtime.memory.ArrayMemory.class).refOfIndex(0).assign(999);
                            }
                        } catch (Throwable failure) {throw new RuntimeException(failure);}
                        finally {
                            try {env.doFinal();} catch (Throwable failure) {throw new RuntimeException(failure);}
                        }
                        return null;
                    }));
                }
                start.countDown();
                for (var job : jobs) job.get(30, java.util.concurrent.TimeUnit.SECONDS);
            } finally {workers.shutdownNow(); compilerEnv.doFinal();}
        }
    }

    @Test public void testChildEnvironmentResolvesTraitChecksWithInheritedGlobals() throws Throwable {
        for (boolean dumped : new boolean[] {false, true}) {
            Environment parent = new Environment(newScope(), new ByteArrayOutputStream());
            try {
                assertTrue(parent.defineConstant("LAZY_VALUE", php.runtime.memory.LongMemory.valueOf(7), true));
                var module = lazyModule(parent, "trait LazyTrait {const X=LAZY_VALUE;}"
                        + "class LazyOwner {const X=LAZY_VALUE;use LazyTrait;}return LazyOwner::X;", dumped);
                parent.getScope().loadModule(module); parent.getScope().addUserModule(module); parent.registerModule(module);
                assertEquals(7, module.include(parent).toLong());
                Environment child = new Environment(parent, new ByteArrayOutputStream());
                try {assertEquals(7, module.include(child).toLong());}
                finally {child.doFinal();}
            } finally {parent.doFinal();}
        }
    }

    private php.runtime.reflection.ModuleEntity lazyModule(Environment env, String source, boolean dumped) throws Throwable {
        Context context = new Context(source); context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
        var module = new JvmCompiler(env, context).compile(false);
        if (dumped) {
            var dumper = new ModuleDumper(context, env, true); var bytes = new ByteArrayOutputStream();
            dumper.save(module, bytes); module = dumper.load(new ByteArrayInputStream(bytes.toByteArray()));
        }
        return module;
    }

    @Test public void testLoadedDeclarationsRetainLinkingRules() throws Throwable {
        String[][] cases={
                {"class P{const int X=1;}","class C extends P{const int|string X=1;}","Type of C::X must be compatible with P::X of type int"},
                {"class P{final public const X=1;}","class C extends P{const X=2;}","C::X cannot override final constant P::X"},
                {"interface I{final const X=1;}","class C implements I{const X=2;}","C::X cannot override final constant I::X"},
                {"trait T{final public const X=1;}","class C{const X=1;use T;}","C and T define the same constant (X) in the composition of C. However, the definition differs and is considered incompatible. Class was composed"},
                {"trait T{final public const X=self::SEED;}","class C{private const SEED=7;use T;}return C::X;",null}
        };
        for(String[] sample:cases)for(boolean dumped:new boolean[]{false,true}) {
            Environment env=new Environment(newScope(),new ByteArrayOutputStream());
            try {
                Context context=new Context(sample[0]);context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
                var module=new JvmCompiler(env,context).compile(false);
                if(dumped){var dumper=new ModuleDumper(context,env,true);var bytes=new ByteArrayOutputStream();dumper.save(module,bytes);module=dumper.load(new ByteArrayInputStream(bytes.toByteArray()));}
                env.getScope().loadModule(module);env.getScope().addUserModule(module);env.registerModule(module);module.include(env);
                context=new Context(sample[1]);context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
                try {
                    var child=new JvmCompiler(env,context).compile(false);
                    env.getScope().loadModule(child);env.getScope().addUserModule(child);env.registerModule(child);
                    assertEquals(7,child.include(env).toLong());
                    assertTrue(child.findClass("c").findConstant("X").isFinal());
                    assertNull(sample[2]);
                } catch(ErrorException error) {
                    assertEquals(sample[2],error.getMessage());
                    assertEquals(php.runtime.exceptions.support.ErrorType.E_COMPILE_ERROR,error.getType());
                }
            } finally {env.doFinal();}
        }
    }

    @Test public void testNativeDeclarationAndLinkingContracts() throws Throwable { declarationOracle("php85_constant_declarations"); }
    @Test public void testNativeConstantExpressions() throws Throwable { declarationOracle("php85_constant_expressions"); }
    @Test public void testNativeTypedConstants() throws Throwable { declarationOracle("php85_typed_constants"); }

    private void declarationOracle(String fixture) throws Throwable {
        String rows;
        try (var input=getClass().getClassLoader().getResourceAsStream("resources/expressions/"+fixture+".tsv")) {
            assertNotNull(input);rows=new String(input.readAllBytes(),StandardCharsets.UTF_8);
        }
        List<String> mismatches=new ArrayList<>();
        for (String row:rows.split("\\R")) {
            String[] fields=row.split("\t",-1);
            String code=new String(Base64.getDecoder().decode(fields[2]),StandardCharsets.UTF_8);
            for(boolean dumped:new boolean[]{false,true}) {
                Environment env=new Environment(newScope(),new ByteArrayOutputStream());
                try {
                    Context context=new Context(code);context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
                    try {
                        var module=new JvmCompiler(env,context).compile(false);
                        if(dumped){var dumper=new ModuleDumper(context,env,true);var bytes=new ByteArrayOutputStream();dumper.save(module,bytes);module=dumper.load(new ByteArrayInputStream(bytes.toByteArray()));}
                        env.getScope().setPhpLanguageProfile(PhpLanguageProfile.JPHP_LEGACY);
                        env.getScope().loadModule(module);env.getScope().addUserModule(module);env.registerModule(module);
                        var diagnostics=new StringWriter();var writer=new PrintWriter(diagnostics);var loader=env.getScope().getClassLoader();
                        CheckClassAdapter.verify(new ClassReader(module.getData()),loader,false,writer);
                        for(var clazz:module.getClasses())if(clazz.getData()!=null)CheckClassAdapter.verify(new ClassReader(clazz.getData()),loader,false,writer);
                        assertEquals(fields[0],"",diagnostics.toString());
                        module.include(env);
                        if(fields[1].equals("reject"))mismatches.add(fields[0]+" dump="+dumped+": unexpectedly accepted");
                    } catch(ErrorException error) {
                        if(fields[1].equals("accept"))mismatches.add(fields[0]+" dump="+dumped+": "+error.getMessage());
                        else {
                            String expected=new String(Base64.getDecoder().decode(fields[4]),StandardCharsets.UTF_8);
                            if(!expected.equals(error.getMessage()) || error.getType().value!=Integer.parseInt(fields[3]))
                                mismatches.add(fields[0]+" dump="+dumped+" expected="+fields[3]+":"+expected+" actual="+error.getType()+":"+error.getMessage());
                        }
                    }
                } finally {env.doFinal();}
            }
        }
        assertEquals(String.join("\n",mismatches),0,mismatches.size());
    }
}
