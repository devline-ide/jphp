package org.develnext.jphp.core.compiler.jvm;

import org.junit.Test;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.Context;
import php.runtime.env.Environment;
import php.runtime.loader.dump.ModuleDumper;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import static org.junit.Assert.assertEquals;

/** Expected values checked against native PHP 8.5.11, with strict_types both off and on. */
public class PhpPropertyDeferredDefaultTest extends JvmCompilerCase {
    private void verify(String code, String expected) throws Throwable {
        for (boolean strict : new boolean[]{false, true}) for (boolean dumped : new boolean[]{false, true}) {
            var env = new Environment(newScope(), new ByteArrayOutputStream());
            env.getScope().registerExtension(new org.develnext.jphp.zend.ext.standard.StandardExtension());
            try {
                var context = new Context((strict ? "declare(strict_types=1);" : "") + code);
                context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
                var module = new JvmCompiler(env, context).compile(false);
                if (dumped) {
                    var dumper = new ModuleDumper(context, env, true);
                    var bytes = new ByteArrayOutputStream();
                    dumper.save(module, bytes);
                    module = dumper.load(new ByteArrayInputStream(bytes.toByteArray()));
                }
                env.getScope().setPhpLanguageProfile(PhpLanguageProfile.JPHP_LEGACY);
                env.getScope().loadModule(module);
                env.getScope().addUserModule(module);
                env.registerModule(module);
                assertEquals("strict=" + strict + " dump=" + dumped + " code=" + code,
                        expected, module.include(env).toString());
            } finally { env.doFinal(); }
        }
    }

    @Test public void testConvertedNullableAndUninitializedDefaults() throws Throwable {
        verify("const N=7;class D{public float $v=N;public static float $s=N;}return gettype((new D)->v).'|'.gettype(D::$s);",
                "double|double");
        verify("const N=7;class D{const C=N;public float $v=self::C;public static float $s=self::C;}return gettype((new D)->v).'|'.gettype(D::$s);",
                "double|double");
        verify("const N=null;class D{public ?int $v=N;}return serialize(new D);",
                "O:1:\"D\":1:{s:1:\"v\";N;}");
        verify("const N=null;class D{public mixed $v=N;}return serialize(new D);",
                "O:1:\"D\":1:{s:1:\"v\";N;}");
        verify("const N=7;class D{public float|string $v=N;}return gettype((new D)->v);",
                "double");
        verify("const N=7;class B{const C=N;public float $v=self::C;}class D extends B{const C='bad';}return gettype((new D)->v).'|'.(new D)->v;",
                "double|7");
        verify("class D{public int $v;}return (new ReflectionProperty(D::class,'v'))->isInitialized(new D)?'initialized':'undefined';",
                "undefined");
    }

    @Test public void testStrictErrorsIncludingStaticInheritanceAndTraits() throws Throwable {
        verify("const N='7';class D{public int $v=N;}try { new D; return 'NO_ERROR'; } catch(Throwable $e) { return get_class($e).'|'.$e->getMessage(); }",
                "TypeError|Cannot assign string to property D::$v of type int");
        verify("const N=2.5;class D{public int $v=N;}try { new D; return 'NO_ERROR'; } catch(Throwable $e) { return get_class($e).'|'.$e->getMessage(); }",
                "TypeError|Cannot assign float to property D::$v of type int");
        verify("const N=false;class D{public int $v=N;}try { new D; return 'NO_ERROR'; } catch(Throwable $e) { return get_class($e).'|'.$e->getMessage(); }",
                "TypeError|Cannot assign false to property D::$v of type int");
        verify("const N=null;class D{public int $v=N;}try { new D; return 'NO_ERROR'; } catch(Throwable $e) { return get_class($e).'|'.$e->getMessage(); }",
                "TypeError|Cannot assign null to property D::$v of type int");
        verify("const N='7';class D{public static int $v=N;}try { new D; return 'NO_ERROR'; } catch(Throwable $e) { return get_class($e).'|'.$e->getMessage(); }",
                "TypeError|Cannot assign string to property D::$v of type int");
        verify("const N='7';class D{public static int $v=N;}try{return (string)D::$v;}catch(Throwable $e){return get_class($e).'|'.$e->getMessage();}",
                "TypeError|Cannot assign string to property D::$v of type int");
        verify("const N='7';class D{public int $v=N;public static int $s=4;}try{return (string)D::$s;}catch(Throwable $e){return get_class($e).'|'.$e->getMessage();}",
                "TypeError|Cannot assign string to property D::$v of type int");
        verify("const N='7';class B{public int $v=N;}class D extends B{}try { new D; return 'NO_ERROR'; } catch(Throwable $e) { return get_class($e).'|'.$e->getMessage(); }",
                "TypeError|Cannot assign string to property B::$v of type int");
        verify("const N='7';trait T{public int $v=N;}class D{use T;}try { new D; return 'NO_ERROR'; } catch(Throwable $e) { return get_class($e).'|'.$e->getMessage(); }",
                "TypeError|Cannot assign string to property D::$v of type int");
        verify("const N=null;class D{public self $v=N;}try { new D; return 'NO_ERROR'; } catch(Throwable $e) { return get_class($e).'|'.$e->getMessage(); }",
                "TypeError|Cannot assign null to property D::$v of type D");
        verify("const N=null;class B{}class D extends B{public parent $v=N;}try { new D; return 'NO_ERROR'; } catch(Throwable $e) { return get_class($e).'|'.$e->getMessage(); }",
                "TypeError|Cannot assign null to property D::$v of type B");
        verify("const N=null;class D{public int|string $v=N;}try { new D; return 'NO_ERROR'; } catch(Throwable $e) { return get_class($e).'|'.$e->getMessage(); }",
                "TypeError|Cannot assign null to property D::$v of type string|int");
        verify("const N=false;class D{public true $v=N;}try { new D; return 'NO_ERROR'; } catch(Throwable $e) { return get_class($e).'|'.$e->getMessage(); }",
                "TypeError|Cannot assign false to property D::$v of type true");
        verify("const N=true;class D{public array $v=N;}try { new D; return 'NO_ERROR'; } catch(Throwable $e) { return get_class($e).'|'.$e->getMessage(); }",
                "TypeError|Cannot assign true to property D::$v of type array");
    }

    @Test public void testUndefinedConstantRemainsAnError() throws Throwable {
        verify("class D{public int $v=MISSING;}try { new D; return 'NO_ERROR'; } catch(Throwable $e) { return get_class($e).'|'.$e->getMessage(); }",
                "Error|Undefined constant \"MISSING\"");
    }

    @Test public void testUnusedInvalidDeferredDefaultRemainsLazy() throws Throwable {
        verify("const N='7';class D{public int $v=N;}return 'OK';",
                "OK");
    }

    @Test public void testGlobalConstantExpressionsRemainDeferredAfterCompileTimePruning() throws Throwable {
        verify("const BAD='abc';class D{public int $v=BAD.'x';}try{new D;}catch(Throwable $e){return get_class($e).'|'.$e->getMessage();}return 'NO_ERROR';",
                "TypeError|Cannot assign string to property D::$v of type int");
        verify("const BAD='abc';class D{public int $v=true?BAD:7;}try{new D;}catch(Throwable $e){return get_class($e).'|'.$e->getMessage();}return 'NO_ERROR';",
                "TypeError|Cannot assign string to property D::$v of type int");
        verify("const BAD='abc';class D{public int $v=BAD??'x';}try{new D;}catch(Throwable $e){return get_class($e).'|'.$e->getMessage();}return 'NO_ERROR';",
                "TypeError|Cannot assign string to property D::$v of type int");
        verify("const BAD='abc';class D{public int $v=[BAD];}try{new D;}catch(Throwable $e){return get_class($e).'|'.$e->getMessage();}return 'NO_ERROR';",
                "TypeError|Cannot assign array to property D::$v of type int");
        verify("const BAD='abc';class D{public int $v=[BAD][0];}try{new D;}catch(Throwable $e){return get_class($e).'|'.$e->getMessage();}return 'NO_ERROR';",
                "TypeError|Cannot assign string to property D::$v of type int");
        verify("const BAD='abc';class D{const INDIRECT=BAD;public int $v=self::INDIRECT;}try{new D;}catch(Throwable $e){return get_class($e).'|'.$e->getMessage();}return 'NO_ERROR';",
                "TypeError|Cannot assign string to property D::$v of type int");
        verify("const BAD='abc';class D{const INDIRECT=BAD;public int $v=D::INDIRECT;}try{new D;}catch(Throwable $e){return get_class($e).'|'.$e->getMessage();}return 'NO_ERROR';",
                "TypeError|Cannot assign string to property D::$v of type int");
        verify("const BAD='abc';class D{public int $v=7??BAD;}return (string)(new D)->v;",
                "7");
        verify("const BAD='abc';class D{public int $v=true?7:BAD;}return (string)(new D)->v;",
                "7");
        verify("const BAD='abc';class D{public int $v=false?BAD:7;}return (string)(new D)->v;",
                "7");
    }

    @Test public void testLiteralClassConstantsAndDeadGlobalBranchesRemainCompileTimeErrors() throws Throwable {
        for (boolean strict : new boolean[]{false, true}) for (String code : new String[]{
                "class D{const BAD='abc';public int $v=self::BAD;}",
                "class D{const BAD='abc';public int $v=D::BAD;}",
                "const BAD='abc';class D{public int $v=false?BAD:'abc';}",
                "const BAD='abc';class D{public int $v=true?'abc':BAD;}",
                "class D{public int $v=PHP_VERSION;}"}) {
            var env = new Environment(newScope(), new ByteArrayOutputStream());
            try {
                var context = new Context((strict ? "declare(strict_types=1);" : "") + code);
                context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
                try {
                    new JvmCompiler(env, context).compile(false);
                    org.junit.Assert.fail("Expected compile-time error: " + code);
                } catch (php.runtime.exceptions.support.ErrorException error) {
                    assertEquals(php.runtime.exceptions.support.ErrorType.E_COMPILE_ERROR, error.getType());
                    assertEquals("Cannot use string as default value for property D::$v of type int", error.getMessage());
                }
            } finally { env.doFinal(); }
        }
    }
}
