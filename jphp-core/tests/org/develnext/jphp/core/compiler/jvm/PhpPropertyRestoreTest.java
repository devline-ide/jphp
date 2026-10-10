package org.develnext.jphp.core.compiler.jvm;

import org.junit.Test;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.Context;
import php.runtime.env.Environment;
import php.runtime.loader.dump.ModuleDumper;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.assertEquals;

public class PhpPropertyRestoreTest extends JvmCompilerCase {
    @Test public void nativeTypedPropertyRestoreAndReferenceParameterContracts() throws Throwable {
        String code=new String(getClass().getResourceAsStream("/resources/type_hinting/php85_property_restore.php").readAllBytes(),StandardCharsets.UTF_8);
        String expected=new String(getClass().getResourceAsStream("/resources/type_hinting/php85_property_restore.php.expected").readAllBytes(),StandardCharsets.UTF_8);
        for(boolean dumped:new boolean[]{false,true}) {
            ByteArrayOutputStream output=new ByteArrayOutputStream();
            Environment env=new Environment(newScope(),output);
            env.getScope().registerExtension(new org.develnext.jphp.zend.ext.standard.StandardExtension());
            try {
                Context context=new Context(code.substring(5).replace("echo serialize($rows);","return serialize($rows);"));context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
                var module=new JvmCompiler(env,context).compile(false);
                if(dumped) {
                    var dumper=new ModuleDumper(context,env,true);var bytes=new ByteArrayOutputStream();dumper.save(module,bytes);
                    module=dumper.load(new ByteArrayInputStream(bytes.toByteArray()));
                }
                env.getScope().loadModule(module);env.getScope().addUserModule(module);env.registerModule(module);
                assertEquals("dumped="+dumped,expected,module.include(env).toString());
            } finally {env.doFinal();}
        }
    }
}
