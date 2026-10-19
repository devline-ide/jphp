package org.develnext.jphp.core.compiler.jvm;

import org.junit.Test;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.*;
import php.runtime.loader.dump.ModuleDumper;
import java.io.*;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.*;

public class PhpSplLegacyInterfaceTest extends JvmCompilerCase {
    private void check(String source, PhpLanguageProfile profile, String expected) throws Throwable {
        for (boolean debug : new boolean[]{false,true}) for (boolean dumped : new boolean[]{false,true}) {
            var scope=newScope(); scope.registerExtension(new org.develnext.jphp.zend.ext.ZendExtension());
            scope.setPhpLanguageProfile(profile); scope.setDebugMode(debug);
            var output=new ByteArrayOutputStream(); var env=new Environment(scope,output);
            try {
                var context=new Context(source); context.setPhpLanguageProfile(profile);
                var module=new JvmCompiler(env,context).compile(false);
                if(dumped){var dumper=new ModuleDumper(context,env,true);var bytes=new ByteArrayOutputStream();dumper.save(module,bytes);module=dumper.load(new ByteArrayInputStream(bytes.toByteArray()));}
                scope.loadModule(module);scope.addUserModule(module);env.registerModule(module);module.include(env);env.doFinal();
                assertEquals("profile="+profile+" debug="+debug+" dump="+dumped,expected,output.toString(StandardCharsets.UTF_8));
            } finally {env.doFinal();}
        }
    }
    private static final String SEEK="class I implements SeekableIterator{function current(){return 0;}function key(){return 0;}function next(){}function rewind(){}function valid(){return false;}function seek($x){return $x;}}";
    @Test public void legacySerializableAndSeekableKeepUntypedImplementations() throws Throwable {
        check("class S implements Serializable{function serialize(){return ''; }function unserialize($x){return $x;}}"+SEEK+"echo (new S)->unserialize(7),':',(new I)->seek(8);",PhpLanguageProfile.JPHP_LEGACY,"7:8");
    }
    @Test public void legacySeekableKeepsUntypedImplementation() throws Throwable {
        check(SEEK+"echo (new I)->seek(8);",PhpLanguageProfile.JPHP_LEGACY,"8");
    }
    @Test public void legacySubclassInheritsTypedNativeSeek() throws Throwable {
        check("class A extends ArrayIterator{}; $a=new A([7,8]); $a->seek(1); echo $a->current();",PhpLanguageProfile.JPHP_LEGACY,"8");
    }
    @Test public void modernWidenedNativeInterfaceParametersStillWork() throws Throwable {
        check("class S implements Serializable{function serialize(){return ''; }function unserialize($x){return $x;}}"+SEEK+"echo (new S)->unserialize(7),':',(new I)->seek(8);",PhpLanguageProfile.PHP_8_5,"7:8");
    }
    @Test public void modernNativeInterfaceMetadataRemainsTyped() throws Throwable {
        check("echo (new ReflectionMethod('Serializable','unserialize'))->getParameters()[0]->getType(),':',(new ReflectionMethod('SeekableIterator','seek'))->getParameters()[0]->getType();",PhpLanguageProfile.PHP_8_5,"string:int");
    }
    @Test public void unrelatedLegacyInterfaceStillRejectsIncompatibleSignature() throws Throwable {
        var scope=newScope(); scope.setPhpLanguageProfile(PhpLanguageProfile.JPHP_LEGACY);var env=new Environment(scope);
        try {
            var context=new Context("interface Other{function seek(int $x);}class Wrong implements Other{function seek($x){}}");context.setPhpLanguageProfile(PhpLanguageProfile.JPHP_LEGACY);
            try{new JvmCompiler(env,context).compile(false);fail("Legacy unrelated interface must still reject the signature");}
            catch(php.runtime.exceptions.CustomErrorException failure){assertTrue(failure.getMessage().contains("must be compatible"));}
        } finally {env.doFinal();}
    }
}
