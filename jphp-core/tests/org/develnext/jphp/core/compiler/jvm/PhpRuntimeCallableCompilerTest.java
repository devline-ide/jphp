package org.develnext.jphp.core.compiler.jvm;

import org.junit.Test;
import php.runtime.*;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.*;
import php.runtime.loader.dump.ModuleDumper;
import java.io.*;
import java.util.*;
import static org.junit.Assert.*;

/** Generated lexical CV publication, zero-argument CALL and actual RESULT transfer. */
public class PhpRuntimeCallableCompilerTest extends JvmCompilerCase {
    @Test public void unknownOrReferenceEscapedCallableRejectsBeforeModuleEffects()throws Throwable {
        for(String source:new String[]{"echo 'entered';$f();return 0;","echo 'entered';$f=function(){return 9;};$alias=&$f;$f();return 0;"})
            for(boolean debug:new boolean[]{false,true})for(boolean dump:new boolean[]{false,true}) {
                var scope=newScope();scope.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);scope.setDebugMode(debug);
                var output=new ByteArrayOutputStream();var env=new Environment(scope,output);
                try {
                    var context=new Context(source);context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);var module=new JvmCompiler(env,context).compile(false);
                    if(dump){var dumper=new ModuleDumper(context,env,true);var bytes=new ByteArrayOutputStream();dumper.save(module,bytes);module=dumper.load(new ByteArrayInputStream(bytes.toByteArray()));}
                    scope.loadModule(module);scope.addUserModule(module);env.registerModule(module);
                    var authority=new PhpRuntimeOwnership.Authority(env,new PhpOwnershipDomain());
                    try(var execution=authority.enter();var ownedAuthority=authority) {
                        var member=module.getNativeMethod();var plan=PhpRuntimeOwnership.readRuntimePlan(module.getData(),member.getName(),PhpOwnershipExecution.descriptor(member));
                        assertTrue(plan.calls().toString(),plan.calls().isEmpty());assertFalse(plan.unsupported().isEmpty());
                        try{authority.registerModule(module,module.getData(),(p,m,bytes)->{});fail("Unknown CALL protocol admitted");}catch(IllegalArgumentException expected){}
                        assertEquals(0,output.size());assertEquals(0,authority.domain().graph().liveNodes());
                    }
                }finally{env.doFinal();}
            }
    }
    @Test public void publishedClosureCvCallsTransferValueAndCellResultsSourceDump()throws Throwable {
        String[] sources={"$f=function(){return 9;};$r=$f();$f=null;return $r;",
                "$f=function &(){static $x=5;return $x;};$r=$f();$f=null;return $r;",
                "$f=function &(){static $x=5;return $x;};$ref=&$f();unset($ref);$f=null;return 7;"};
        long[] expected={9,5,7};
        for(int route=0;route<sources.length;route++)for(boolean debug:new boolean[]{false,true})for(boolean dump:new boolean[]{false,true}){
            var scope=newScope();scope.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);scope.setDebugMode(debug);var env=new Environment(scope,new ByteArrayOutputStream());var domain=new PhpOwnershipDomain();
            try{
                var context=new Context(sources[route]);context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
                var module=new JvmCompiler(env,context).compile(false);
                if(dump){var dumper=new ModuleDumper(context,env,true);var bytes=new ByteArrayOutputStream();dumper.save(module,bytes);module=dumper.load(new ByteArrayInputStream(bytes.toByteArray()));}
                scope.loadModule(module);scope.addUserModule(module);env.registerModule(module);
                assertEquals(expected[route],module.include(env).toLong());
                var authority=new PhpRuntimeOwnership.Authority(env,domain);
                try(var execution=authority.enter();var ownedAuthority=authority){
                    var plan=PhpRuntimeOwnership.readRuntimePlan(module.getData(),module.getNativeMethod().getName(),PhpOwnershipExecution.descriptor(module.getNativeMethod()));
                    assertEquals(plan.unsupported().toString(),Collections.emptyList(),plan.unsupported());
                    assertEquals(1,plan.calls().size());assertEquals(1,plan.closureCvs().size());
                    assertTrue(plan.producers().stream().anyMatch(p->p.kind().equals("CALL_RESULT")&&p.consumers().contains("CV")));
                    for(var closure:module.getClosures())authority.registerMethod(closure.findMethod("__invoke"),closure.getData(),(p,m,bytes)->{});
                    authority.registerModule(module,module.getData(),(p,m,bytes)->{});
                    try(var result=authority.include(module)){assertEquals(expected[route],result.raw().toLong());}
                    assertEquals(0,domain.graph().liveNodes());
                }
            }finally{env.doFinal();}
        }
    }
}
