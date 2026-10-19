package org.develnext.jphp.core.compiler.jvm;

import org.develnext.jphp.zend.ext.standard.StandardExtension;
import org.junit.Test;
import php.runtime.*;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.*;
import php.runtime.loader.dump.ModuleDumper;
import php.runtime.memory.LongMemory;
import java.io.*;
import java.util.*;
import static org.junit.Assert.*;

/** Actual ordinary result producer publication into a physical CV address. */
public class PhpRuntimeAddressCompilerTest extends JvmCompilerCase {
    @Test public void nativeResultMovesIntoAddressBeforeReferenceExportSourceDump()throws Throwable {
        for(boolean debug:new boolean[]{false,true})for(boolean dump:new boolean[]{false,true}){
            var scope=newScope();scope.setDebugMode(debug);scope.registerExtension(new StandardExtension());
            var env=new Environment(scope,new ByteArrayOutputStream());var domain=new PhpOwnershipDomain();
            try{
                var context=new Context("function owned_address_eval($n){$a=count([$n,2]);$alias=&$a;return count([$alias]);}return 0;");context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
                var module=new JvmCompiler(env,context).compile(false);
                if(dump){var dumper=new ModuleDumper(context,env,true);var bytes=new ByteArrayOutputStream();dumper.save(module,bytes);module=dumper.load(new ByteArrayInputStream(bytes.toByteArray()));}
                scope.loadModule(module);scope.addUserModule(module);env.registerModule(module);module.include(env);
                try(var authority=new PhpRuntimeOwnership.Authority(env,domain);var execution=authority.enter()){
                    var member=php.runtime.ext.core.ArrayFunctions.class.getMethod("count",Environment.class,TraceInfo.class,Memory.class);
                    authority.registerNative(member,new PhpRuntimeOwnership.CompleteNativeProtocol(){
                        public void validateMember(java.lang.reflect.Method actual){assertEquals(member,actual);}
                        public void validateArguments(List<PhpRuntimeOwnership.Value> actual){assertEquals(1,actual.size());}
                    });
                    var function=env.fetchFunction("owned_address_eval");var nativeMethod=function.getNativeMethod();
                    var plan=PhpRuntimeOwnership.readRuntimePlan(function.getData(),nativeMethod.getName(),PhpOwnershipExecution.descriptor(nativeMethod));
                    assertEquals(plan.unsupported().toString(),Collections.emptyList(),plan.unsupported());
                    assertTrue(plan.producers().toString(),plan.producers().stream().anyMatch(p->p.kind().equals("NATIVE_RESULT")&&p.consumers().contains("CV")));
                    authority.registerFunction(function,function.getData(),(p,m,bytes)->{});
                    try(var result=authority.invoke(function,TraceInfo.UNKNOWN,LongMemory.valueOf(7))){assertEquals(1,result.raw().toLong());}
                    assertEquals(0,domain.graph().liveNodes());
                }
            }finally{env.doFinal();}
        }
    }
}
