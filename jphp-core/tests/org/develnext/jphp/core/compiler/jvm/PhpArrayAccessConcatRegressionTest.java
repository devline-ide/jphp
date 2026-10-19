package org.develnext.jphp.core.compiler.jvm;

import org.junit.Test;
import php.runtime.*;
import php.runtime.env.*;
import php.runtime.ext.*;
import php.runtime.memory.*;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.loader.dump.ModuleDumper;
import java.io.*;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.*;

/** Real legacy/modern ArrayAccess callbacks. The original Zend EXPECTF fixture stays untouched. */
public class PhpArrayAccessConcatRegressionTest {
    private static final String DECL="error_reporting(0);class A implements ArrayAccess {public $current='A';public $gets=0;"
            +"function offsetExists($k){return true;}function offsetGet($k){$this->gets++;echo 'G;';return $this->current;}"
            +"function offsetSet($k,$v){echo 'S;';$this->current=$v;}function offsetUnset($k){}}$a=new A;";
    private static final class Fixture implements AutoCloseable {
        final CompileScope scope=new CompileScope();final Environment env;final ByteArrayOutputStream output=new ByteArrayOutputStream();
        Fixture(String source,PhpLanguageProfile profile,boolean debug,boolean dump)throws Throwable {
            scope.registerExtension(new CoreExtension());scope.registerExtension(new SPLExtension());scope.registerExtension(new org.develnext.jphp.zend.ext.ZendExtension());
            scope.setPhpLanguageProfile(profile);scope.setDebugMode(debug);env=new Environment(scope,output);env.getDefaultBuffer().setChunkSize(0);
            var context=new Context(source);context.setPhpLanguageProfile(profile);var module=new JvmCompiler(env,context).compile(false);
            if(dump){var dumper=new ModuleDumper(context,env,true);var bytes=new ByteArrayOutputStream();dumper.save(module,bytes);module=dumper.load(new ByteArrayInputStream(bytes.toByteArray()));}
            scope.loadModule(module);scope.addUserModule(module);env.registerModule(module);module.include(env);env.getDefaultBuffer().flush();
            var member=module.getNativeMethod();assertTrue(((php.runtime.loader.RuntimeClassLoader)member.getDeclaringClass().getClassLoader()).matchesDefinedBytecode(member.getDeclaringClass(),module.getData()));
        }
        ObjectMemory object(){return env.getGlobals().getByScalar("a").toValue(ObjectMemory.class);}
        String text()throws Throwable {env.getDefaultBuffer().flush();return output.toString(StandardCharsets.UTF_8);}
        public void close()throws Exception {try{env.doFinal();}catch(Throwable failure){throw new AssertionError(failure);}finally{scope.getClassLoader().close();}}
    }
    private void check(String source,String expected,PhpLanguageProfile profile)throws Throwable {
        for(boolean debug:new boolean[]{false,true})for(boolean dump:new boolean[]{false,true})try(var f=new Fixture(source,profile,debug,dump)){assertEquals(expected,f.text());}
    }
    @Test public void legacyArrayAccessConcatFetchesOnlyOnceBeforeStore()throws Throwable {
        check(DECL+"$a['x'].='B';echo $a->gets,'|',$a->current;","G;S;1|AB",PhpLanguageProfile.JPHP_LEGACY);
    }
    @Test public void modernArrayAccessConcatFetchesOnlyOnceBeforeStore()throws Throwable {
        check(DECL+"$a['x'].='B';echo $a->gets,'|',$a->current;","G;S;1|AB",PhpLanguageProfile.PHP_8_5);
    }
    @Test public void legacyNestedArrayAccessPreservesOneReadAtEachActualAddress()throws Throwable {
        String source=DECL+"class Outer implements ArrayAccess{public $inner;function __construct($i){$this->inner=$i;}function offsetExists($k){return true;}function offsetGet($k){echo 'O;';return $this->inner;}function offsetSet($k,$v){}function offsetUnset($k){}}$o=new Outer($a);$o['a']['x'].='B';echo $a->gets,'|',$a->current;";
        check(source,"O;G;S;1|AB",PhpLanguageProfile.JPHP_LEGACY);
    }
    @Test public void actualLegacyArrayAccessAddressPreservesRawByteOperandWithoutSecondGet()throws Throwable {
        for(boolean debug:new boolean[]{false,true})for(boolean dump:new boolean[]{false,true})try(var f=new Fixture(DECL+"function append_actual(){global $a;$a['x'].='B';}",PhpLanguageProfile.JPHP_LEGACY,debug,dump)) {
            f.object().getProperties().refOfIndex("current").assign(new PhpByteStringMemory(new byte[]{(byte)255}));
            var fn=f.env.fetchFunction("append_actual");assertTrue(((php.runtime.loader.RuntimeClassLoader)fn.getNativeMethod().getDeclaringClass().getClassLoader()).matchesDefinedBytecode(fn.getNativeMethod().getDeclaringClass(),fn.getData()));
            fn.invoke(f.env,TraceInfo.UNKNOWN,new Memory[0]);assertEquals("G;S;",f.text());
            assertArrayEquals(new byte[]{(byte)255,66},f.object().getProperties().getByScalar("current").toValue().getBinaryBytes(StandardCharsets.UTF_8));
            assertEquals(1,f.object().getProperties().getByScalar("gets").toLong());
        }
    }
    @Test public void actualArrayAccessCapturesRightReferenceAfterLeftConversionOnlyOnce()throws Throwable {
        for(boolean debug:new boolean[]{false,true})for(boolean dump:new boolean[]{false,true})try(var f=new Fixture(DECL,PhpLanguageProfile.JPHP_LEGACY,debug,dump)) {
            final int[] reads={0};var right=new ReferenceMemory(StringMemory.valueOf("old")) {
                @Override public Memory toValue(){reads[0]++;return super.toValue();}
            };
            var left=new StringMemory("K"){@Override public String toString(){right.assign("Z");return "K";}};
            f.object().getProperties().refOfIndex("current").assign(left);
            Memory address=f.object().refOfIndex(TraceInfo.UNKNOWN,StringMemory.valueOf("x"));PhpByteStrings.assignConcatLegacy(address,right);
            assertEquals("G;S;",f.text());assertEquals("KZ",f.object().getProperties().getByScalar("current").toString());assertEquals(1,reads[0]);
        }
    }
    @Test public void modernTypedAndReadonlyPropertyConcatKeepTheirActualWriteGuards()throws Throwable {
        String source="class T{public string $s='A';}class R{public readonly string $s;function __construct(){$this->s='A';}}$t=new T;$t->s.='B';echo $t->s;$r=new R;try{$r->s.='B';}catch(Error $e){echo '|',$e->getMessage();}echo '|',$r->s;";
        check(source,"AB|Cannot modify readonly property R::$s|A",PhpLanguageProfile.PHP_8_5);
    }
}
