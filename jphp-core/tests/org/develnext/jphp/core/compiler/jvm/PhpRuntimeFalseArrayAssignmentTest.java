package org.develnext.jphp.core.compiler.jvm;

import org.junit.Test;
import php.runtime.*;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.*;
import php.runtime.ext.*;
import php.runtime.ext.core.LangFunctions;
import php.runtime.loader.dump.ModuleDumper;
import php.runtime.memory.*;
import java.io.*;
import java.util.*;
import static org.junit.Assert.*;

/** Native ASSIGN_DIM false-conversion order, with actual FIELD/capture/CV/ENTRY roots. */
public class PhpRuntimeFalseArrayAssignmentTest {
    private static final ThreadLocal<Boolean> THROW=new ThreadLocal<>();
    private static final ThreadLocal<Integer> SEEN=new ThreadLocal<>();
    public static final class ProbeExtension extends php.runtime.ext.support.Extension {
        public Status getStatus(){return Status.EXPERIMENTAL;}
        public void onRegister(CompileScope scope){registerFunctions(new ProbeFunctions());}
    }
    public static final class ProbeFunctions extends php.runtime.ext.support.compile.FunctionsContainer {
        public static Memory false_conversion_probe(Environment env) {
            var raw=env.getGlobals().getByScalar("a").toValue();
            assertTrue("Callback observes the published ARRAY",raw instanceof ArrayMemory);
            SEEN.set(((ArrayMemory)raw).size());
            return Memory.TRUE;
        }
        public static Memory false_conversion_finish() {
            if(Boolean.TRUE.equals(THROW.get()))throw new php.runtime.exceptions.CriticalException("callback");
            return Memory.TRUE;
        }
    }
    @Test public void replacementCancellationCopiesAliasesAndPendingCallbackFailureMatchNativeOrder() throws Throwable {
        String[] actions={"", "$a=[9];", "$a=null;", "$a=false;", "$copy=$a;", "$copy=$a;$a=[9];", "$alias=&$a;$a=[9];", "unset($a);"};
        for(int route=0;route<actions.length;route++)for(boolean pending:new boolean[]{false,true})
        for(boolean append:new boolean[]{false,true})
        for(boolean debug:new boolean[]{false,true})for(boolean dump:new boolean[]{false,true}) {
            SEEN.remove();THROW.set(pending);
            var scope=new CompileScope();scope.registerExtension(new CoreExtension());scope.registerExtension(new SPLExtension());scope.registerExtension(new ProbeExtension());
            scope.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);scope.setDebugMode(debug);
            var env=new Environment(scope,new ByteArrayOutputStream());var domain=new PhpOwnershipDomain();
            var source="$a=false;$copy=null;$alias=null;set_error_handler(function($n,$s)use(&$a,&$copy,&$alias){false_conversion_probe();"+actions[route]+"false_conversion_finish();return true;});$a"+(append?"[]":"[0]")+"=[1];return 7;";
            var context=new Context(source);context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
            var module=new JvmCompiler(env,context).compile(false);
            if(dump){var bytes=new ByteArrayOutputStream();var dumper=new ModuleDumper(context,env,true);dumper.save(module,bytes);module=dumper.load(new ByteArrayInputStream(bytes.toByteArray()));}
            scope.loadModule(module);scope.addUserModule(module);env.registerModule(module);
            var authority=new PhpRuntimeOwnership.Authority(env,domain);
            try(var execution=authority.enter()) {
                try {
                    var members=new ArrayList<java.lang.reflect.Method>();
                    for(var member:LangFunctions.class.getDeclaredMethods())if(Set.of("set_error_handler","restore_error_handler").contains(member.getName()))members.add(member);
                    members.add(ProbeFunctions.class.getMethod("false_conversion_probe",Environment.class));
                    members.add(ProbeFunctions.class.getMethod("false_conversion_finish"));
                    for(var member:members)authority.registerNative(member,new PhpRuntimeOwnership.CompleteNativeProtocol(){
                        public void validateMember(java.lang.reflect.Method actual){assertEquals(member,actual);}
                        public void validateArguments(List<PhpRuntimeOwnership.Value> arguments){}
                    });
                    for(var closure:module.getClosures())authority.registerMethod(closure.findMethod("__invoke"),closure.getData(),(p,m,b)->{});
                    authority.registerModule(module,module.getData(),(p,m,b)->{});
                    Throwable failure=null;
                    try(var result=authority.include(module)){assertEquals(7,result.raw().toLong());}
                    catch(Throwable actual){failure=actual;}
                    if(pending)assertNotNull(failure);else if(failure!=null) {
                        throw new AssertionError("route="+route+" append="+append+" debug="+debug+" dump="+dump,failure);
                    }
                    assertEquals("route="+route+" pending="+pending+" failure="+failure,Integer.valueOf(0),SEEN.get());
                    var raw=authority.globalAddress("a").toValue();
                    if(route==2)assertTrue(raw.isNull());
                    else if(route==3)assertSame(Memory.FALSE,raw);
                    else {
                        var array=(ArrayMemory)raw;
                        assertEquals(route==5&&append?2:1,array.size());
                        if(route==1||route==6)assertEquals(9,array.getByScalar(LongMemory.valueOf(0)).toLong());
                        else assertEquals(1,array.getByScalar(LongMemory.valueOf(route==5&&append?1:0)).toValue(ArrayMemory.class).getByScalar(LongMemory.valueOf(0)).toLong());
                    }
                    if(route==4||route==5)assertEquals(0,authority.globalAddress("copy").toValue(ArrayMemory.class).size());
                    if(route==6)assertTrue("Rebinding a captured CV leaves its prior outer CELL unchanged",authority.globalAddress("alias").toValue().isNull());
                } finally {env.doFinal();authority.close();}
                assertEquals(0,domain.graph().liveNodes());
            } finally {THROW.remove();SEEN.remove();}
        }
    }
}
