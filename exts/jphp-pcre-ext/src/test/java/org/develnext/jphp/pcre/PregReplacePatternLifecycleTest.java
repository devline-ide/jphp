package org.develnext.jphp.pcre;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import org.develnext.jphp.core.compiler.jvm.JvmCompiler;
import org.junit.Test;
import php.runtime.Memory;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.CompileScope;
import php.runtime.env.Context;
import php.runtime.env.Environment;
import php.runtime.ext.CoreExtension;
import php.runtime.ext.SPLExtension;
import php.runtime.loader.dump.ModuleDumper;
import php.runtime.reflection.ModuleEntity;
import static org.junit.Assert.*;

/** Actual PHP calls and real PCRE2 allocation/free handles; no substitute engine or production hooks. */
public class PregReplacePatternLifecycleTest {
    private static final class Observation implements AutoCloseable {
        private final Pcre2Bytes engine;
        private final Field compileField, freeField;
        private final MethodHandle originalCompile, originalFree;
        private final Set<Long> live = new HashSet<>();
        private final Thread thread = Thread.currentThread();
        int attempts, allocated, freed, peak;
        boolean installedCompile, installedFree;

        Observation(Pcre2Bytes engine) throws Throwable {
            this.engine = engine;
            compileField = Pcre2Bytes.class.getDeclaredField("compile");
            freeField = Pcre2Bytes.class.getDeclaredField("codeFree");
            compileField.setAccessible(true); freeField.setAccessible(true);
            originalCompile = (MethodHandle) compileField.get(engine);
            originalFree = (MethodHandle) freeField.get(engine);
            assertEquals(MemorySegment.class, originalCompile.type().returnType());
            assertEquals(MethodType.methodType(void.class, MemorySegment.class), originalFree.type());
            MethodHandle observeCompile = MethodHandles.lookup().findVirtual(Observation.class, "compiled",
                    MethodType.methodType(MemorySegment.class, MemorySegment.class)).bindTo(this);
            MethodHandle wrappedCompile = MethodHandles.filterReturnValue(originalCompile, observeCompile);
            MethodHandle wrappedFree = MethodHandles.insertArguments(MethodHandles.lookup().findStatic(
                    PregReplacePatternLifecycleTest.class, "freeReal",
                    MethodType.methodType(void.class, Observation.class, MethodHandle.class, MemorySegment.class)),
                    0, this, originalFree);
            try {
                compileField.set(engine, wrappedCompile); installedCompile = true;
                freeField.set(engine, wrappedFree); installedFree = true;
                assertSame("Actual engine must read instrumented compile field", wrappedCompile, compileField.get(engine));
                assertSame("Actual engine must read instrumented free field", wrappedFree, freeField.get(engine));
            } catch (Throwable failure) {
                try { close(); } catch (Throwable restore) { failure.addSuppressed(restore); }
                throw new AssertionError("JDK must permit observing these fresh application-instance MethodHandle fields; no Unsafe/skip fallback", failure);
            }
        }

        private void confined() { assertSame("Observer is lexical/thread-confined", thread, Thread.currentThread()); }
        private MemorySegment compiled(MemorySegment address) {
            confined(); attempts++;
            if (address.address() != 0) {
                assertTrue("Native compile cannot return an already live code address", live.add(address.address()));
                allocated++; peak = Math.max(peak, live.size());
            }
            return address;
        }
        private void beforeFree(MemorySegment address) {
            confined();
            assertTrue("Reject unknown/duplicate native free before executing unsafe native call", address.address() != 0 && live.contains(address.address()));
        }
        private void freed(MemorySegment address) {
            assertTrue(live.remove(address.address())); freed++;
        }
        void balanced() {
            assertTrue("No compiled code survives the PHP call body", live.isEmpty());
            assertEquals("Every successful real compile was freed", allocated, freed);
            assertTrue("Observer must see genuine native activity", allocated > 0);
        }
        @Override public void close() throws IllegalAccessException {
            try { if (installedFree) { freeField.set(engine, originalFree); installedFree = false; } }
            finally { if (installedCompile) { compileField.set(engine, originalCompile); installedCompile = false; } }
            assertSame(originalCompile, compileField.get(engine)); assertSame(originalFree, freeField.get(engine));
        }
    }

    private static void freeReal(Observation observation, MethodHandle actual, MemorySegment address) throws Throwable {
        observation.beforeFree(address);
        actual.invokeExact(address);
        observation.freed(address);
    }

    private static final class Fixture implements AutoCloseable {
        final Environment env;
        final Observation observer;
        final boolean debug, phb;
        Fixture(boolean debug, boolean phb) throws Throwable {
            this.debug = debug; this.phb = phb;
            var scope = new CompileScope();
            scope.setDebugMode(debug); scope.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
            scope.registerExtension(new CoreExtension()); scope.registerExtension(new SPLExtension());
            scope.registerExtension(new org.develnext.jphp.zend.ext.standard.StandardExtension());
            String library = System.getProperty("qa.pcre.native.path"), hash = System.getProperty("qa.pcre.native.sha256");
            if ((library == null) != (hash == null)) throw new IllegalArgumentException("Both explicit pinned native properties required");
            var extension = library == null ? new PcreExtension() : new PcreExtension(Path.of(library).toRealPath(), hash);
            scope.registerExtension(extension);
            env = new Environment(scope, new ByteArrayOutputStream());
            // Fresh provider/engine; only its metadata/configuration have run before handle observation.
            Observation installed = null;
            try {
                installed = new Observation(extension.engine());
                origin(Environment.class, "qa.runtime"); origin(JvmCompiler.class, "qa.core"); origin(PcreExtension.class, "qa.pcre");
            } catch (Throwable failure) {
                try { if (installed != null) installed.close(); } catch (Throwable restore) { failure.addSuppressed(restore); }
                try { env.doFinal(); } catch (Throwable cleanup) { failure.addSuppressed(cleanup); }
                throw failure;
            }
            observer = installed;
        }
        void php(String source) throws Throwable {
            var context = new Context(source); context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
            ModuleEntity module = new JvmCompiler(env, context).compile(false);
            if (phb) {
                var bytes = new ByteArrayOutputStream(); var dumper = new ModuleDumper(context, env, debug);
                dumper.save(module, bytes); assertTrue(bytes.size() > 0);
                module = dumper.load(new ByteArrayInputStream(bytes.toByteArray())); assertTrue(module.isCompiledArtifact());
            }
            env.scope.loadModule(module); env.scope.addUserModule(module); env.registerModule(module);
            Memory result = module.include(env);
            assertTrue("PHP semantic invariant debug=" + debug + " phb=" + phb,
                    result.toValue().getRealType() == Memory.Type.BOOL && result.toBoolean());
            observer.balanced();
        }
        @Override public void close() throws Exception {
            try { env.doFinal(); }
            catch (Throwable failure) { throw new AssertionError("Environment finalization failed", failure); }
            finally { observer.close(); }
        }
    }

    private static void origin(Class<?> type, String property) throws Exception {
        String expected = System.getProperty(property);
        if (expected != null) assertEquals(type.getName(), Path.of(expected).toRealPath(),
                Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath());
    }
    @FunctionalInterface private interface Case { void run(Fixture fixture) throws Throwable; }
    private static void modes(Case test) throws Throwable {
        for (boolean debug : new boolean[]{false, true}) for (boolean phb : new boolean[]{false, true})
            try (var fixture = new Fixture(debug, phb)) { test.run(fixture); }
    }

    @Test(timeout=60000) public void arraySubjectsReuseOneCompiledPatternAndCloseBeforeReturn() throws Throwable {
        modes(f -> {
            f.php("$n=99;$out=preg_replace('~a~','b',['first'=>'a',7=>'aa','same'=>'z'],-1,$n);return $out===['first'=>'b',7=>'bb','same'=>'z']&&$n===3;");
            assertEquals(1, f.observer.attempts); assertEquals(1, f.observer.allocated); assertEquals(1, f.observer.peak);
        });
    }
    @Test(timeout=60000) public void arrayPatternABAReusesTwoActualCodes() throws Throwable {
        modes(f -> {
            f.php("$n=99;$out=preg_replace(['~a~','~b~','~a~'],['b','a','z'],['x'=>'a',4=>'a'],-1,$n);return $out===['x'=>'z',4=>'z']&&$n===6;");
            assertEquals(2, f.observer.attempts); assertEquals(2, f.observer.allocated); assertTrue(f.observer.peak <= 2);
        });
    }
    @Test(timeout=60000) public void separateScalarCallsNeverRetainCodeAcrossCalls() throws Throwable {
        modes(f -> {
            f.php("$a=preg_replace('~a~','b','a');$b=preg_replace('~a~','b','aa');return $a==='b'&&$b==='bb';");
            assertEquals(2, f.observer.attempts); assertEquals(2, f.observer.allocated); assertEquals(1, f.observer.peak);
        });
    }
    @Test(timeout=60000) public void manyUniquePatternsHaveBoundedNativeRetentionAndCloseAll() throws Throwable {
        modes(f -> {
            f.php("$patterns=[];for($i=0;$i<12;$i++)$patterns[]='~a(?#p'.$i.')~';$n=99;$out=preg_replace($patterns,'b',['a','a'],-1,$n);return $out===['b','b']&&$n===2;");
            assertTrue("Every distinct pattern was genuinely compiled", f.observer.attempts >= 12);
            assertTrue("At most eight retained codes plus one overflow operation", f.observer.peak <= 9);
        });
    }
    @Test(timeout=60000) public void ninthPatternIsOneShotAndFirstRetainedPatternSurvivesOverflow() throws Throwable {
        modes(f -> {
            f.php("$patterns=[];for($i=0;$i<9;$i++)$patterns[]='~a(?#p'.$i.')~';$patterns[]=$patterns[8];$patterns[]=$patterns[0];$n=99;$out=preg_replace($patterns,'b',['a','z'],-1,$n);return $out===['b','z']&&$n===1;");
            assertEquals("Eight retained plus ninth twice for each subject, first retained survives overflow", 12, f.observer.attempts);
            assertEquals(12, f.observer.allocated); assertTrue(f.observer.peak <= 9);
        });
    }
    @Test(timeout=60000) public void throwingSubjectConversionClosesCacheAndFollowingCallWorks() throws Throwable {
        modes(f -> {
            f.php("class LifecycleSubject {public function __toString(){throw new \\RuntimeException('subject-stop');}}$n=99;$caught=false;try{preg_replace('~a~','b',['a',new LifecycleSubject()],-1,$n);}catch(\\RuntimeException $e){$caught=$e->getMessage()==='subject-stop';}$m=99;$out=preg_replace('~b~','c',['b'],-1,$m);return $caught&&$n===1&&$out===['c']&&$m===1;");
            assertEquals(2, f.observer.attempts); assertEquals(2, f.observer.allocated); assertEquals(1, f.observer.peak);
        });
    }
    @Test(timeout=60000) public void oversizedRawPatternIsOneShotAndDoesNotEvictSmallRetainedPattern() throws Throwable {
        modes(f -> {
            f.php("$large='~a(?#'.str_repeat('x',70000).')~';$n=99;$out=preg_replace(['~a~',$large,$large,'~a~'],'b',['a','z'],-1,$n);return $out===['b','z']&&$n===1;");
            assertEquals("One small retained code and four real oversized compilations", 5, f.observer.attempts);
            assertEquals(5, f.observer.allocated); assertTrue(f.observer.peak <= 2);
        });
    }
    @Test(timeout=60000) public void throwingCompileWarningClosesPriorCodeAndFollowingCallWorks() throws Throwable {
        modes(f -> {
            f.php("set_error_handler(function($level,$text){throw new \\RuntimeException('compile-stop');});$n=99;$caught=false;try{preg_replace(['~a~','~(~'],['b','x'],['a','z'],-1,$n);}catch(\\RuntimeException $e){$caught=$e->getMessage()==='compile-stop';}finally{restore_error_handler();}$m=99;$out=preg_replace('~b~','c',['b'],-1,$m);return $caught&&$n===1&&$out===['c']&&$m===1;");
            assertEquals("Valid prefix, NULL compile failure, valid next call", 3, f.observer.attempts);
            assertEquals(2, f.observer.allocated); assertEquals(1, f.observer.peak);
        });
    }
}
