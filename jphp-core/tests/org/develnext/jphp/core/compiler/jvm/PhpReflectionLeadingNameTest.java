package org.develnext.jphp.core.compiler.jvm;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import org.junit.Test;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.Context;
import php.runtime.env.Environment;
import php.runtime.loader.dump.ModuleDumper;
import static org.junit.Assert.*;

/** Exact captured native PHP output; every fixture/mode receives an independent scope and loader. */
public class PhpReflectionLeadingNameTest extends JvmCompilerCase {
    private record Fixture(String id,String source,byte[] stdout,int nativeExit) {}

    private List<Fixture> fixtures(String resource,int expectedCount)throws IOException {
        try(var input=getClass().getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(resource,input);
            List<Fixture> fixtures=new ArrayList<>();
            for(String line:new String(input.readAllBytes(),StandardCharsets.UTF_8).split("\\R")) {
                String[] row=line.split("\t",-1);assertEquals(resource+" row columns",4,row.length);
                fixtures.add(new Fixture(row[0],new String(Base64.getDecoder().decode(row[1]),StandardCharsets.UTF_8),
                        Base64.getDecoder().decode(row[2]),Integer.parseInt(row[3])));
            }
            assertEquals(resource+" fixture count",expectedCount,fixtures.size());return fixtures;
        }
    }
    private void execute(Fixture fixture,boolean debug,boolean dumped)throws Throwable {
        var scope=newScope();scope.setDebugMode(debug);scope.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
        scope.registerExtension(new org.develnext.jphp.zend.ext.standard.StandardExtension());
        var output=new ByteArrayOutputStream();var env=new Environment(scope,output);
        String label=fixture.id()+" debug="+debug+" PHB="+dumped;
        try {
            assertEquals(label+" captured native exit",0,fixture.nativeExit());
            var context=new Context(fixture.source());context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
            var module=new JvmCompiler(env,context).compile(false);
            if(dumped) {
                var dumper=new ModuleDumper(context,env,true);var bytes=new ByteArrayOutputStream();
                dumper.save(module,bytes);module=dumper.load(new ByteArrayInputStream(bytes.toByteArray()));
            }
            scope.loadModule(module);scope.addUserModule(module);env.registerModule(module);module.include(env);
            env.getDefaultBuffer().flush();assertArrayEquals(label,fixture.stdout(),output.toByteArray());
        }finally {try{env.doFinal();}finally{scope.getClassLoader().close();}}
    }
    private void compare(String resource,int fixtureCount)throws Throwable {
        List<String> differences=new ArrayList<>();int comparisons=0;
        for(var fixture:fixtures(resource,fixtureCount))for(boolean debug:new boolean[]{false,true})for(boolean dumped:new boolean[]{false,true}) {
            comparisons++;
            try{execute(fixture,debug,dumped);}catch(AssertionError mismatch){differences.add(mismatch.getMessage());}
        }
        assertEquals(fixtureCount*4,comparisons);assertEquals(String.join("\n",differences),0,differences.size());
    }
    @Test public void nativeReflectionClassLeadingSeparatorsAndObjectConstructorSourceAndPhb()throws Throwable {
        compare("resources/reflection/php85_reflection_leading_names.tsv",1);
    }
    @Test public void nativeAutoloadNamesCallbacksEffectsAndReentrySourceAndPhb()throws Throwable {
        compare("resources/reflection/php85_reflection_leading_name_autoload.tsv",9);
    }
}
