package org.develnext.jphp.core.compiler.jvm;

import org.junit.Test;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.Context;
import php.runtime.env.Environment;
import php.runtime.loader.dump.ModuleDumper;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import static org.junit.Assert.assertEquals;

/** Native PHP 8.5.11 object casts preserve reference identity across array copies. */
public class PhpPropertyObjectCastTest extends JvmCompilerCase {
    @Test public void testInitializationAndVisibilityKeys() throws Throwable {
        assertNative("class C{public int$v;public ?int$n;public $u;}return serialize((array)new C);",
                "a:1:{s:1:\"u\";N;}");
        assertNative("class P{private int$v=3;private int$z;protected int$p=4;}class C extends P{public string$v=\"x\";private int$c=5;}return serialize((array)new C);",
                "a:4:{s:4:\"\0P\0v\";i:3;s:4:\"\0*\0p\";i:4;s:1:\"v\";s:1:\"x\";s:4:\"\0C\0c\";i:5;}");
        assertNative("class C{public int$v=3;public $x=4;}$c=new C;unset($c->v);return serialize((array)$c);",
                "a:1:{s:1:\"x\";i:4;}");
        assertNative("class C{public int$v=3;public $x=4;}$c=new C;$c->v=7;return serialize((array)$c);",
                "a:2:{s:1:\"v\";i:7;s:1:\"x\";i:4;}");
    }

    @Test public void testCanonicalReferencesAndAtomicWrites() throws Throwable {
        assertNative("class C{public int$v=3;}$c=new C;$r=&$c->v;$a=(array)$c;try{$a[\"v\"]=\"abc\";$m=\"accepted\";}catch(Throwable$e){$m=get_class($e).\":\".$e->getMessage();}return serialize([$m,$c->v,$r,$a]);",
                "a:4:{i:0;s:78:\"TypeError:Cannot assign string to reference held by property C::$v of type int\";i:1;i:3;i:2;i:3;i:3;a:1:{s:1:\"v\";i:3;}}");
        assertNative("class C{public int$v=3;}$c=new C;$r=&$c->v;$a=(array)$c;$a[\"v\"]=5;return serialize([$c->v,$r,$a]);",
                "a:3:{i:0;i:5;i:1;i:5;i:2;a:1:{s:1:\"v\";i:5;}}");
        assertNative("class P{private int$v=3;function &ref(){return $this->v;}function get(){return $this->v;}}class C extends P{public string$v=\"x\";}$c=new C;$r=&$c->ref();$a=(array)$c;$a[\"\\0P\\0v\"]=5;return serialize([$c->get(),$c->v,$r,$a]);",
                "a:4:{i:0;i:5;i:1;s:1:\"x\";i:2;i:5;i:3;a:2:{s:4:\"\0P\0v\";i:5;s:1:\"v\";s:1:\"x\";}}");
        assertNative("class C{protected int$v=3;function &ref(){return $this->v;}function get(){return $this->v;}}$c=new C;$r=&$c->ref();$a=(array)$c;$a[\"\\0*\\0v\"]=5;return serialize([$c->get(),$r,$a]);",
                "a:3:{i:0;i:5;i:1;i:5;i:2;a:1:{s:4:\"\0*\0v\";i:5;}}");
    }

    @Test public void testCopyOnWriteKeepsReferenceBoundaries() throws Throwable {
        assertNative("class C{public int$v=3;}$c=new C;$a=(array)$c;$b=$a;$a[\"v\"]=\"abc\";return serialize([$c->v,$a,$b]);",
                "a:3:{i:0;i:3;i:1;a:1:{s:1:\"v\";s:3:\"abc\";}i:2;a:1:{s:1:\"v\";i:3;}}");
        assertNative("class C{public int$v=3;}$c=new C;$r=&$c->v;$a=(array)$c;$b=$a;$a[\"v\"]=5;return serialize([$c->v,$r,$a,$b]);",
                "a:4:{i:0;i:5;i:1;i:5;i:2;a:1:{s:1:\"v\";i:5;}i:3;a:1:{s:1:\"v\";R:5;}}");
        assertNative("class C{public array$v=[1];}$c=new C;$a=(array)$c;$b=$a;$a[\"v\"][]=2;return serialize([$c->v,$a,$b]);",
                "a:3:{i:0;a:1:{i:0;i:1;}i:1;a:1:{s:1:\"v\";a:2:{i:0;i:1;i:1;i:2;}}i:2;a:1:{s:1:\"v\";a:1:{i:0;i:1;}}}");
        assertNative("class C{public array$v=[1];}$c=new C;$r=&$c->v;$a=(array)$c;$b=$a;$a[\"v\"][]=2;return serialize([$c->v,$r,$a,$b]);",
                "a:4:{i:0;a:2:{i:0;i:1;i:1;i:2;}i:1;a:2:{i:0;i:1;i:1;i:2;}i:2;a:1:{s:1:\"v\";a:2:{i:0;i:1;i:1;i:2;}}i:3;a:1:{s:1:\"v\";R:9;}}");
        assertNative("class C{public array$v=[1];}$c=new C;$r=&$c->v;$a=(array)$c;try{$a[\"v\"]=false;$m=\"accepted\";}catch(Throwable$e){$m=get_class($e).\":\".$e->getMessage();}return serialize([$m,$c->v,$r,$a]);",
                "a:4:{i:0;s:79:\"TypeError:Cannot assign false to reference held by property C::$v of type array\";i:1;a:1:{i:0;i:1;}i:2;a:1:{i:0;i:1;}i:3;a:1:{s:1:\"v\";a:1:{i:0;i:1;}}}");
    }

    @Test public void testLegacyCastStillCopiesReferenceValues() throws Throwable {
        assertProfile("class C{public $v=3;}$c=new C;$r=&$c->v;$a=(array)$c;$a[\"v\"]=\"abc\";return serialize([$c->v,$r,$a]);",
                "a:3:{i:0;i:3;i:1;i:3;i:2;a:1:{s:1:\"v\";s:3:\"abc\";}}",PhpLanguageProfile.JPHP_LEGACY);
    }

    private void assertNative(String code, String expected) throws Throwable {
        assertProfile(code,expected,PhpLanguageProfile.PHP_8_5);
    }

    private void assertProfile(String code, String expected, PhpLanguageProfile profile) throws Throwable {
        for (boolean dumped : new boolean[]{false,true}) {
            Environment env=new Environment(newScope(),new ByteArrayOutputStream());
            env.getScope().registerExtension(new org.develnext.jphp.zend.ext.standard.StandardExtension());
            try {
                Context context=new Context(code);context.setPhpLanguageProfile(profile);
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
