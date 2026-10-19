package org.develnext.jphp.core.compiler.jvm;

import org.junit.Test;
import php.runtime.*;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.*;
import php.runtime.loader.dump.ModuleDumper;
import java.io.*;
import static org.junit.Assert.*;

/** Actual named FUNCTION acquisition, ADDRESS CELL SEND, parameter import and checked RESULT. */
public class PhpRuntimeNamedFunctionCompilerTest extends JvmCompilerCase {
    @Test public void originalBorrowCallRetiresItsActualParameterCellBeforeCloneSourceDump()throws Throwable {
        String original="$f=function &(){static $x=0;return $x;};$r=&$f();$r=5;function borrow(&$v){}borrow($r);unset($r);$g=clone $f;$s=&$g();$s=9;$t=&$f();echo $t,',',$s;";
        for(boolean strict:new boolean[]{false,true})for(boolean debug:new boolean[]{false,true})for(boolean dump:new boolean[]{false,true}) {
            var scope=newScope();scope.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);scope.setDebugMode(debug);var env=new Environment(scope,new ByteArrayOutputStream());env.getDefaultBuffer().setChunkSize(0);var domain=new PhpOwnershipDomain();
            try {
                var context=new Context((strict?"declare(strict_types=1);":"")+original);context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);var module=new JvmCompiler(env,context).compile(false);
                if(dump){var buffer=new ByteArrayOutputStream();var dumper=new ModuleDumper(context,env,true);dumper.save(module,buffer);module=dumper.load(new ByteArrayInputStream(buffer.toByteArray()));}
                scope.loadModule(module);scope.addUserModule(module);env.registerModule(module);
                var authority=new PhpRuntimeOwnership.Authority(env,domain);
                try(var execution=authority.enter();var owned=authority) {
                    for(var function:module.getFunctions())authority.registerFunction(function,function.getData(),(plan,member,bytes)->{});
                    for(var closure:module.getClosures())authority.registerMethod(closure.findMethod("__invoke"),closure.getData(),(plan,member,bytes)->{});
                    var method=module.getNativeMethod();var plan=PhpRuntimeOwnership.readRuntimePlan(module.getData(),method.getName(),PhpOwnershipExecution.descriptor(method));assertEquals(plan.unsupported().toString(),0,plan.unsupported().size());assertEquals(1,plan.functionCalls().size());
                    authority.registerModule(module,module.getData(),(p1,m,bytes)->{});try(var result=authority.include(module)){}
                    assertEquals("Pinned PHP8.5.11 original output", "5,9",env.getDefaultBuffer().getContents().toString());
                    var f=(php.runtime.lang.Closure)((php.runtime.memory.ObjectMemory)authority.globalAddress("f").getRawValue()).value;
                    var g=(php.runtime.lang.Closure)((php.runtime.memory.ObjectMemory)authority.globalAddress("g").getRawValue()).value;
                    var fc=((php.runtime.memory.ReferenceMemory)f.getStatic("x")).getReferenceForBinding();var gc=((php.runtime.memory.ReferenceMemory)g.getStatic("x")).getReferenceForBinding();assertNotSame(fc,gc);assertEquals(2,fc.getRuntimeCell().node().incoming());assertEquals(2,gc.getRuntimeCell().node().incoming());
                }
                assertEquals(0,domain.graph().liveNodes());
            }finally{env.doFinal();}
        }
    }
    @Test public void missingNamedTargetRejectsBeforeCallerEffects()throws Throwable {
        for(boolean debug:new boolean[]{false,true}) {
            var scope=newScope();scope.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);scope.setDebugMode(debug);var env=new Environment(scope,new ByteArrayOutputStream());env.getDefaultBuffer().setChunkSize(0);
            try {
                var context=new Context("function consume(&$v){} echo 'entered';$x=5;consume($x);return 0;");context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);var module=new JvmCompiler(env,context).compile(false);scope.loadModule(module);scope.addUserModule(module);env.registerModule(module);
                var authority=new PhpRuntimeOwnership.Authority(env,new PhpOwnershipDomain());try(var execution=authority.enter();var owned=authority){assertThrows(IllegalArgumentException.class,()->authority.registerModule(module,module.getData(),(p1,m,b)->{}));assertEquals("",env.getDefaultBuffer().getContents().toString());assertEquals(0,authority.domain().graph().liveNodes());}
            }finally{env.doFinal();}
        }
    }
    @Test public void actualReferenceParameterWritesCallerPayloadAndHostByValueEntryRejectsBeforeEffects()throws Throwable {
        for(boolean debug:new boolean[]{false,true})for(boolean dump:new boolean[]{false,true}) {
            var scope=newScope();scope.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);scope.setDebugMode(debug);var env=new Environment(scope,new ByteArrayOutputStream());env.getDefaultBuffer().setChunkSize(0);var domain=new PhpOwnershipDomain();
            try {
                var context=new Context("function update(&$v){$v=7;}$x=5;update($x);return $x;");context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);var module=new JvmCompiler(env,context).compile(false);
                if(dump){var buffer=new ByteArrayOutputStream();var dumper=new ModuleDumper(context,env,true);dumper.save(module,buffer);module=dumper.load(new ByteArrayInputStream(buffer.toByteArray()));}scope.loadModule(module);scope.addUserModule(module);env.registerModule(module);
                var authority=new PhpRuntimeOwnership.Authority(env,domain);try(var execution=authority.enter();var owned=authority) {
                    var function=module.getFunctions().iterator().next();authority.registerFunction(function,function.getData(),(p1,m,b)->{});
                    assertThrows(IllegalArgumentException.class,()->authority.invoke(function,TraceInfo.UNKNOWN,Memory.CONST_INT_5));assertEquals("",env.getDefaultBuffer().getContents().toString());assertEquals(0,domain.graph().liveNodes());
                    authority.registerModule(module,module.getData(),(p1,m,b)->{});try(var result=authority.include(module)){assertEquals(7,result.raw().toLong());}
                    var cell=authority.globalAddress("x").getReferenceForBinding();assertEquals(1,cell.getRuntimeCell().node().incoming());assertEquals(7,cell.toLong());
                }assertEquals(0,domain.graph().liveNodes());
            }finally{env.doFinal();}
        }
    }
}
