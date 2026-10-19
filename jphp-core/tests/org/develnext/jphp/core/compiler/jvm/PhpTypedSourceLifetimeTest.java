package org.develnext.jphp.core.compiler.jvm;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.Test;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.Context;
import php.runtime.env.Environment;
import php.runtime.loader.dump.ModuleDumper;
import php.runtime.memory.ArrayMemory;
import php.runtime.memory.output.serialization.PhpSerialization;
import static org.junit.Assert.*;

public class PhpTypedSourceLifetimeTest extends JvmCompilerCase {
    @Test public void testNativeFreshOwnerAndEscapeGuards() throws Throwable {
        String data;
        try(var input=getClass().getClassLoader().getResourceAsStream("resources/type_hinting/php85_typed_source_lifetime.tsv")) {
            assertNotNull(input);data=new String(input.readAllBytes(),StandardCharsets.UTF_8);
        }
        List<String> differences=new ArrayList<>();
        for(String row:data.split("\\R"))for(boolean debug:new boolean[]{false,true})for(boolean dumped:new boolean[]{false,true}) {
            String[] fields=row.split("\t",-1);String name=fields[0]+" debug="+debug+" PHB="+dumped;
            var scope=newScope();scope.setDebugMode(debug);
            var env=new Environment(scope,new ByteArrayOutputStream());
            env.getScope().registerExtension(new org.develnext.jphp.zend.ext.standard.StandardExtension());
            try {
                var context=new Context(new String(Base64.getDecoder().decode(fields[1]),StandardCharsets.UTF_8));
                context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);var module=new JvmCompiler(env,context).compile(false);
                if(dumped){var dumper=new ModuleDumper(context,env,true);var bytes=new ByteArrayOutputStream();dumper.save(module,bytes);module=dumper.load(new ByteArrayInputStream(bytes.toByteArray()));}
                env.getScope().setPhpLanguageProfile(PhpLanguageProfile.JPHP_LEGACY);
                env.getScope().loadModule(module);env.getScope().addUserModule(module);env.registerModule(module);
                var actual=module.include(env).toValue(ArrayMemory.class);int size=Integer.parseInt(fields[2]);
                assertEquals(name,size,actual.size());
                var trace=php.runtime.env.TraceInfo.valueOf(context.getFileName(),0,0,module.getInternalName());
                for(int i=0;i<size;i++) {
                    String expected=new String(Base64.getDecoder().decode(fields[i+3]),StandardCharsets.UTF_8);
                    String result=PhpSerialization.serialize(env,trace,actual.valueOfIndex(i)).toString();
                    if(!expected.equals(result))differences.add(name+" row="+i+" expected="+expected+" actual="+result);
                }
            }finally{env.doFinal();}
        }
        assertEquals(String.join("\n",differences),0,differences.size());
    }
}
