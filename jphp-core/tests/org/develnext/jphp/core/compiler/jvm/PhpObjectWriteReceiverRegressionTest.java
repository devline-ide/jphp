package org.develnext.jphp.core.compiler.jvm;

import org.junit.Test;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.*;
import php.runtime.loader.dump.ModuleDumper;
import java.io.*;
import static org.junit.Assert.*;

/** Exact captured native receiver fixtures, also loaded from a resource JAR. */
public final class PhpObjectWriteReceiverRegressionTest extends JvmCompilerCase {
    private void check(String group,int rows,int expectedComparisons) throws Throwable {
        int comparisons=0;
        for(String mode:new String[]{"weak","strict"})for(boolean debug:new boolean[]{false,true})for(boolean dumped:new boolean[]{false,true}) {
            String resource="object-write-receiver/"+group+"/"+mode;
            String source=PhpNativeTestResources.text(resource+".php");
            byte[] expected=PhpNativeTestResources.read(resource+".stdout");
            assertEquals(resource+" captured stderr",0,PhpNativeTestResources.read(resource+".stderr").length);
            var scope=newScope();scope.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);scope.setDebugMode(debug);
            scope.registerExtension(new org.develnext.jphp.zend.ext.standard.StandardExtension());
            scope.registerExtension(new org.develnext.jphp.zend.ext.json.JsonExtension());
            var stdout=new ByteArrayOutputStream();var env=new Environment(scope,stdout);
            try {
                var context=new Context(source.startsWith("<?php")?source.substring(5):source);
                context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
                var module=new JvmCompiler(env,context).compile(false);
                if(dumped) {
                    var bytes=new ByteArrayOutputStream();var dumper=new ModuleDumper(context,env,true);
                    dumper.save(module,bytes);module=dumper.load(new ByteArrayInputStream(bytes.toByteArray()));
                }
                scope.setPhpLanguageProfile(PhpLanguageProfile.JPHP_LEGACY);
                scope.loadModule(module);scope.addUserModule(module);env.registerModule(module);
                module.include(env);env.getDefaultBuffer().flush();
                assertArrayEquals(resource+" debug="+debug+" PHB="+dumped+" exact native stdout",expected,stdout.toByteArray());
                comparisons+=rows;
            } finally {env.doFinal();}
        }
        assertEquals(group+" all captured native rows x debug/source-PHB",expectedComparisons,comparisons);
    }
    @Test public void expandedNativeReceiversKeepInitializationPermissionsGetterMagicAndReferenceOrderSourcePhbDebug() throws Throwable {
        check("receivers",26,208);
    }
    @Test public void virtualHookInitializationValidatesReceiverWithoutInvokingHooksSourcePhbDebug() throws Throwable {
        check("virtual-init",19,152);
    }
    @Test public void effectiveHookInitializationPreservesPrivateIdentityWideningAndReceiverValidationSourcePhbDebug() throws Throwable {
        check("effective-init",15,120);
    }
}
