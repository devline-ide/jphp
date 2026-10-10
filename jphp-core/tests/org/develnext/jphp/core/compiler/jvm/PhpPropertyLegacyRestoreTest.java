package org.develnext.jphp.core.compiler.jvm;

import org.junit.Test;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.Context;
import php.runtime.env.Environment;
import php.runtime.loader.dump.ModuleDumper;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import static org.junit.Assert.assertEquals;

/** Typed class storage stays guarded when restoration is invoked by a legacy module. */
public class PhpPropertyLegacyRestoreTest extends JvmCompilerCase {
    private void verify(String declarations,String body,String expected) throws Throwable {
        for(boolean dumped:new boolean[]{false,true}) {
            Environment env=new Environment(newScope(),new ByteArrayOutputStream());
            env.getScope().registerExtension(new org.develnext.jphp.zend.ext.standard.StandardExtension());
            try {
                run(env,"function bridgeRestore($s){return unserialize($s);} function bridgeSerialize($v){return serialize($v);} class BridgeLegacy {public $text;}",PhpLanguageProfile.JPHP_LEGACY,dumped);
                run(env,declarations,PhpLanguageProfile.PHP_8_5,dumped);
                assertEquals("dumped="+dumped,expected,run(env,body,PhpLanguageProfile.PHP_8_5,dumped));
            } finally {env.doFinal();}
        }
    }
    private String run(Environment env,String code,PhpLanguageProfile profile,boolean dumped) throws Throwable {
        Context context=new Context(code);context.setPhpLanguageProfile(profile);
        var module=new JvmCompiler(env,context).compile(false);
        if(dumped) {
            var dumper=new ModuleDumper(context,env,true);var bytes=new ByteArrayOutputStream();dumper.save(module,bytes);
            module=dumper.load(new ByteArrayInputStream(bytes.toByteArray()));
        }
        env.getScope().loadModule(module);env.getScope().addUserModule(module);env.registerModule(module);
        return module.include(env).toString();
    }
    @Test public void testLegacyCallerCannotRestoreInvalidModernTypedValue() throws Throwable {
        verify("class BridgeGuard {public int $v=7;}",
                "try{$o=bridgeRestore('O:11:\"BridgeGuard\":1:{s:1:\"v\";s:1:\"3\";}');return 'accepted:'.$o->v;}catch(Throwable $e){return get_class($e).'|'.$e->getMessage();}",
                "TypeError|Cannot assign string to property BridgeGuard::$v of type int");
    }
    @Test public void testLegacyCallerUsesModernVisibilityAndHookContracts() throws Throwable {
        verify("class BridgePrivate {private int $v=7;function value(){return $this->v;}}",
                "return bridgeRestore('O:13:\"BridgePrivate\":1:{s:1:\"v\";i:3;}')->value();","3");
    }
    @Test public void testLegacyCallerInvokesModernUnserializeHook() throws Throwable {
        verify("class BridgeHook {public int $v=7;function __unserialize(array $data){$this->v=$data['v'];}}",
                "return gettype(bridgeRestore('O:10:\"BridgeHook\":1:{s:1:\"v\";s:1:\"3\";}')->v);","integer");
    }
    @Test public void testLegacyCharacterCountWireRemainsReadableForBothClassProfiles() throws Throwable {
        verify("class BridgeText {public string $text='';}",
                "$a=new BridgeLegacy;$a->text='Жук';$b=new BridgeText;$b->text='Жук';return bridgeRestore(bridgeSerialize($a))->text.'|'.bridgeRestore(bridgeSerialize($b))->text;","Жук|Жук");
    }
}
