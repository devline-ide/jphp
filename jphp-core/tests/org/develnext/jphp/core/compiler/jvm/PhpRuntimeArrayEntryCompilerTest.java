package org.develnext.jphp.core.compiler.jvm;

import org.junit.Test;
import php.runtime.*;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.*;
import php.runtime.loader.dump.ModuleDumper;
import java.io.*;
import static org.junit.Assert.*;

/** Source/PHB entry publication consumes actual RHS producers, with unsupported paths closed. */
public class PhpRuntimeArrayEntryCompilerTest extends JvmCompilerCase {
    private php.runtime.reflection.ModuleEntity compile(Environment env,String source,boolean dump) throws Throwable {
        var context=new Context(source);context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
        var module=new JvmCompiler(env,context).compile(false);
        if(dump){var dumper=new ModuleDumper(context,env,true);var bytes=new ByteArrayOutputStream();dumper.save(module,bytes);module=dumper.load(new ByteArrayInputStream(bytes.toByteArray()));}
        var scope=env.getScope();scope.loadModule(module);scope.addUserModule(module);env.registerModule(module);return module;
    }
    @Test public void appendIndexReplacementAndNullInitializationTransferRealArrayProducers() throws Throwable {
        String[] sources={"$a=[];$a[]=[1,2];$a=null;return 7;",
                "$a=[];$a['k']=[1];$a['k']=[2];$a=null;return 7;",
                "$a=null;$a[]=[1];$a=null;return 7;",
                "$a=false;$a[]=[1];$a=null;return 7;",
                "$a=[];$copy=$a;$a[]=[1];$copy=null;$a=null;return 7;"};
        for(String source:sources)for(boolean debug:new boolean[]{false,true})for(boolean dump:new boolean[]{false,true}) {
            var scope=newScope();scope.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);scope.setDebugMode(debug);
            var env=new Environment(scope,new ByteArrayOutputStream());var domain=new PhpOwnershipDomain();
            try {
                var module=compile(env,source,dump);assertEquals(7,module.include(env).toLong());
                var authority=new PhpRuntimeOwnership.Authority(env,domain);
                try(var execution=authority.enter();var owned=authority) {
                    var member=module.getNativeMethod();var plan=PhpRuntimeOwnership.readRuntimePlan(module.getData(),member.getName(),PhpOwnershipExecution.descriptor(member));
                    assertTrue(plan.unsupported().toString(),plan.unsupported().isEmpty());
                    assertTrue(plan.producers().stream().anyMatch(p->p.kind().equals("ARRAY_LITERAL")&&p.consumers().contains("ENTRY")));
                    authority.registerModule(module,module.getData(),(p,m,bytes)->{});
                    try(var result=authority.include(module)){assertEquals(7,result.raw().toLong());}
                    assertEquals(0,domain.graph().liveNodes());
                }
            } finally {env.doFinal();}
        }
    }
    @Test public void invalidScalarUnwindsRhsWithoutPublishingAnEntry() throws Throwable {
        for(boolean debug:new boolean[]{false,true})for(boolean dump:new boolean[]{false,true}) {
            var scope=newScope();scope.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);scope.setDebugMode(debug);
            var env=new Environment(scope,new ByteArrayOutputStream());var domain=new PhpOwnershipDomain();
            try {
                var module=compile(env,"$a=1;$a[]=[1];return 7;",dump);var authority=new PhpRuntimeOwnership.Authority(env,domain);
                try(var execution=authority.enter();var owned=authority) {
                    authority.registerModule(module,module.getData(),(p,m,bytes)->{});
                    Throwable thrown=null;
                    try(var result=authority.include(module)){fail("Scalar ARRAY target accepted");}catch(Throwable failure){thrown=failure;}
                    assertNotNull(thrown);assertTrue(thrown.toString(),thrown.toString().contains("scalar"));
                    assertEquals(0,domain.graph().liveNodes());
                }
            }finally{env.doFinal();}
        }
    }
    @Test public void multiStepReturnedAndUnknownKeyRejectBeforeEffects() throws Throwable {
        String[] sources={"echo 'entered';$a=[];$a[0][]=[1];return 7;",
                "echo 'entered';$a=[];$r=($a[]=[1]);return 7;",
                "echo 'entered';$a=[];$k=0;$a[$k]=[1];return 7;"};
        for(String source:sources)for(boolean debug:new boolean[]{false,true})for(boolean dump:new boolean[]{false,true}) {
            var scope=newScope();scope.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);scope.setDebugMode(debug);
            var output=new ByteArrayOutputStream();var env=new Environment(scope,output);
            try {
                var module=compile(env,source,dump);var authority=new PhpRuntimeOwnership.Authority(env,new PhpOwnershipDomain());
                try(var execution=authority.enter();var owned=authority) {
                    try{authority.registerModule(module,module.getData(),(p,m,bytes)->{});fail("Unproven ARRAY path admitted");}
                    catch(IllegalArgumentException | IOException expected){}
                    assertEquals(0,output.size());assertEquals(0,authority.domain().graph().liveNodes());
                }
            }finally{env.doFinal();}
        }
    }
}
