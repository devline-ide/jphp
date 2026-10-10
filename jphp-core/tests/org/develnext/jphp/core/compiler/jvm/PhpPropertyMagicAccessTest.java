package org.develnext.jphp.core.compiler.jvm;

import org.junit.Test;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.Context;
import php.runtime.env.Environment;
import php.runtime.loader.dump.ModuleDumper;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import static org.junit.Assert.assertEquals;

/** Expectations captured from PHP 8.5.11; both source and persisted bytecode use caller profiles. */
public class PhpPropertyMagicAccessTest extends JvmCompilerCase {
    @Test public void testInaccessibleTypedPropertyUsesMagicGetter() throws Throwable {
        assertNative("class C{private int $v=3;function __get($k){return \"abc\";}}return serialize([(new C)->v]);",
                "a:1:{i:0;s:3:\"abc\";}");
        assertNative("class C{protected int $v=3;function __get($k){return \"abc\";}}return serialize([(new C)->v]);",
                "a:1:{i:0;s:3:\"abc\";}");
        assertNative("class C{private int $v=3;public $b=\"abc\";function &__get($k){return $this->b;}}$c=new C;$r=&$c->v;$r=\"z\";return serialize([$c->b,$r]);",
                "a:2:{i:0;s:1:\"z\";i:1;s:1:\"z\";}");
        assertNative("class C{protected int $v=3;public $b=\"abc\";function &__get($k){return $this->b;}}$c=new C;$r=&$c->v;$r=\"z\";return serialize([$c->b,$r]);",
                "a:2:{i:0;s:1:\"z\";i:1;s:1:\"z\";}");
    }

    @Test public void testUnsetTypedPropertyRetainsMagicReference() throws Throwable {
        assertNative("class C{public int $v=3;public $b=4;function &__get($k){return $this->b;}}$c=new C;unset($c->v);$r=&$c->v;$r=5;return serialize([$c->b,$r]);",
                "a:2:{i:0;i:5;i:1;i:5;}");
        assertNative("class C{public int $v=3;public $b=\"4\";function &__get($k){return $this->b;}}$c=new C;unset($c->v);$r=&$c->v;return serialize([$c->b,$r]);",
                "a:2:{i:0;i:4;i:1;i:4;}");
        assertNative("class C{public int $v=3;public $b=4;function &__get($k){return $this->b;}}$c=new C;unset($c->v);$r=&$c->v;$r=\"abc\";return serialize([$c->b,$r]);",
                "a:2:{i:0;s:3:\"abc\";i:1;s:3:\"abc\";}");
        assertNative("class C{public int $v=3;public float $b=4;function &__get($k){return $this->b;}}$c=new C;unset($c->v);try{$r=&$c->v;$m=\"accepted\";}catch(Throwable$e){$m=get_class($e).\":\".$e->getMessage();}return serialize([$m,$c->b]);",
                "a:2:{i:0;s:131:\"TypeError:Reference with value of type float held by property C::$b of type float is not compatible with property C::$v of type int\";i:1;d:4;}");
        assertNative("class C{public int $v=3;public string $b=\"4\";function &__get($k){return $this->b;}}$c=new C;unset($c->v);try{$r=&$c->v;$m=\"accepted\";}catch(Throwable$e){$m=get_class($e).\":\".$e->getMessage();}return serialize([$m,$c->b]);",
                "a:2:{i:0;s:133:\"TypeError:Reference with value of type string held by property C::$b of type string is not compatible with property C::$v of type int\";i:1;s:1:\"4\";}");
    }

    @Test public void testMagicGetterValueCoercionAndInitialization() throws Throwable {
        assertNative("class C{public int $v=3;function __get($k){return \"4\";}}$c=new C;unset($c->v);return serialize([$c->v]);",
                "a:1:{i:0;i:4;}");
        assertNative("class C{public int $v=3;function __get($k){return \"abc\";}}$c=new C;unset($c->v);try{$r=$c->v;$m=\"accepted\";}catch(Throwable$e){$m=get_class($e).\":\".$e->getMessage();}return serialize([$m]);",
                "a:1:{i:0;s:112:\"TypeError:Value of type string returned from C::__get() must be compatible with unset property C::$v of type int\";}");
        assertNative("class C{public int $v;public $calls=0;function __get($k){$this->calls++;return 4;}}$c=new C;try{$r=$c->v;$m=\"accepted\";}catch(Throwable$e){$m=get_class($e).\":\".$e->getMessage();}return serialize([$m,$c->calls]);",
                "a:2:{i:0;s:69:\"Error:Typed property C::$v must not be accessed before initialization\";i:1;i:0;}");
    }

    @Test public void testStaticUnsetCannotChangeStorage() throws Throwable {
        assertNative("class C{public static int $v=3;}try{unset(C::$v);$m=\"accepted\";}catch(Throwable$e){$m=get_class($e).\":\".$e->getMessage();}return serialize([$m,C::$v]);",
                "a:2:{i:0;s:44:\"Error:Attempt to unset static property C::$v\";i:1;i:3;}");
        assertNative("class C{public static $v=3;}try{unset(C::$v);$m=\"accepted\";}catch(Throwable$e){$m=get_class($e).\":\".$e->getMessage();}return serialize([$m,C::$v]);",
                "a:2:{i:0;s:44:\"Error:Attempt to unset static property C::$v\";i:1;i:3;}");
        assertNative("class C{private static int $v=3;static function get(){return self::$v;}}try{unset(C::$v);$m=\"accepted\";}catch(Throwable$e){$m=get_class($e).\":\".$e->getMessage();}return serialize([$m,C::get()]);",
                "a:2:{i:0;s:44:\"Error:Attempt to unset static property C::$v\";i:1;i:3;}");
        assertNative("class C{}try{unset(C::$missing);$m=\"accepted\";}catch(Throwable$e){$m=get_class($e).\":\".$e->getMessage();}return serialize([$m]);",
                "a:1:{i:0;s:50:\"Error:Attempt to unset static property C::$missing\";}");
        assertNative("class P{public static int$v=3;}class C extends P{}try{unset(C::$v);$m=\"accepted\";}catch(Throwable$e){$m=get_class($e).\":\".$e->getMessage();}return serialize([$m,P::$v]);",
                "a:2:{i:0;s:44:\"Error:Attempt to unset static property C::$v\";i:1;i:3;}");
    }

    @Test public void testCloningUnsetPropertyDoesNotRestoreDefaults() throws Throwable {
        assertNative("class C{public int$v=7;}$c=new C;unset($c->v);$copy=clone $c;return serialize([serialize($copy),(new ReflectionProperty(\"C\",\"v\"))->isInitialized($copy)]);",
                "a:2:{i:0;s:12:\"O:1:\"C\":0:{}\";i:1;b:0;}");
    }

    private void assertNative(String code, String expected) throws Throwable {
        for (boolean dumped : new boolean[]{false,true}) {
            Environment env=new Environment(newScope(),new ByteArrayOutputStream());
            env.getScope().registerExtension(new org.develnext.jphp.zend.ext.standard.StandardExtension());
            try {
                Context context=new Context(code);context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
                var module=new JvmCompiler(env,context).compile(false);
                if (dumped) {
                    var dumper=new ModuleDumper(context,env,true);var bytes=new ByteArrayOutputStream();
                    dumper.save(module,bytes);module=dumper.load(new ByteArrayInputStream(bytes.toByteArray()));
                }
                env.getScope().setPhpLanguageProfile(PhpLanguageProfile.JPHP_LEGACY);
                env.getScope().loadModule(module);env.getScope().addUserModule(module);env.registerModule(module);
                assertEquals("dump="+dumped+" source="+code,expected,module.include(env).toString());
            } finally {env.doFinal();}
        }
    }
}
