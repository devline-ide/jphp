package org.develnext.jphp.core.compiler.jvm;

import org.develnext.jphp.zend.ext.standard.StandardExtension;
import org.junit.Test;
import php.runtime.*;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.*;
import php.runtime.loader.dump.ModuleDumper;
import php.runtime.memory.LongMemory;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.Assert.*;

/** Actual generated native argument/result bytecode, separate from the package gate. */
public class PhpRuntimeNativeCompilerTest extends JvmCompilerCase {
    @Test public void passiveNativeFixedVarargsNestedAndArrayResultsSourceDump() throws Throwable {
        for(boolean debug:new boolean[]{false,true})for(boolean dump:new boolean[]{false,true}) {
            var scope=newScope();scope.setDebugMode(debug);scope.registerExtension(new StandardExtension());
            var output=new ByteArrayOutputStream();var env=new Environment(scope,output);
            try {
                var context=new Context("function passive_packet($x){echo abs($x),'|',max($x,2,3),'|',count([$x,2,3]),'|',count(array_reverse([$x,2])),'|',abs(abs($x));}passive_packet(-7);");
                context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
                var module=new JvmCompiler(env,context).compile(false);
                if(dump){var dumper=new ModuleDumper(context,env,true);var bytes=new ByteArrayOutputStream();dumper.save(module,bytes);module=dumper.load(new ByteArrayInputStream(bytes.toByteArray()));}
                scope.loadModule(module);scope.addUserModule(module);env.registerModule(module);module.include(env);env.getDefaultBuffer().flush();
                assertEquals("debug="+debug+" dump="+dump,"7|3|3|2|7",output.toString(StandardCharsets.UTF_8));
                var nativeClass=new org.objectweb.asm.tree.ClassNode();new org.objectweb.asm.ClassReader(env.fetchFunction("passive_packet").getData()).accept(nativeClass,0);
                var helperCalls=new HashMap<String,Integer>();
                for(var method:nativeClass.methods)for(var instruction:method.instructions.toArray())
                    if(instruction instanceof org.objectweb.asm.tree.MethodInsnNode call && call.owner.equals("php/runtime/PhpRuntimeOwnership"))helperCalls.merge(call.name,1,Integer::sum);
                for(String helper:new String[]{"nativeCall","nativeEnterBody","nativeLeaveBody","nativeFailBody","nativeResult"})assertTrue(helper+" "+helperCalls,helperCalls.getOrDefault(helper,0)>=5);
                for(String helper:new String[]{"nativeSend","nativeBorrowSend"})assertTrue(helper+" "+helperCalls,helperCalls.getOrDefault(helper,0)>0);
            }finally{env.doFinal();}
        }
    }
    private static final ThreadLocal<BodyProbe> BODY_PROBE=new ThreadLocal<>();
    private static final class BodyProbe {
        PhpRuntimeOwnership.Authority authority;
        java.lang.reflect.Method member;
        final php.runtime.exceptions.CriticalException failure=new php.runtime.exceptions.CriticalException("actual native BODY failure");
        final List<PhpRuntimeOwnership.NativeBody> entered=new ArrayList<>();
    }
    public static final class BodyExtension extends php.runtime.ext.support.Extension {
        public Status getStatus(){return Status.EXPERIMENTAL;}
        public void onRegister(CompileScope scope){registerFunctions(new BodyFunctions());}
    }
    public static final class BodyFunctions extends php.runtime.ext.support.compile.FunctionsContainer {
        public static Memory runtime_body_probe(Environment env,Memory input)throws Throwable {
            BodyProbe probe=BODY_PROBE.get();
            PhpRuntimeOwnership.NativeBody body=PhpRuntimeOwnership.currentNativeBody(env);
            assertNotNull("selected native opcode must enter BODY",body);assertEquals(probe.member,body.member());
            probe.entered.add(body);long operation=input.toLong();
            if(operation==1||operation==3){
                try(var result=probe.authority.invoke(env.fetchFunction("owned_body_inner"),TraceInfo.UNKNOWN,LongMemory.valueOf(operation==1?2:-1))){
                    assertEquals(2,result.raw().toLong());
                }finally{assertSame("nested BODY restores its caller",body,PhpRuntimeOwnership.currentNativeBody(env));}
            }
            if(operation==-1)throw probe.failure;
            if(operation==4||operation==5){
                var producer=body.arena().allocateArray();var child=body.arena().temp(LongMemory.valueOf(9));
                var address=producer.assembly().address("value");
                try(var publication=address.prepareMoveFrom(address.current(),child,child.current())){publication.store();publication.commit();}
                assertNull("actual child producer is consumed",child.current().binding());
                var cleanup=body.arena().empty();
                try(var publication=address.prepareClearTo(address.current(),cleanup,cleanup.current())){publication.store();publication.commit();}
                assertEquals(9,cleanup.raw().toLong());
                try(var publication=address.prepareMoveFrom(address.current(),cleanup,cleanup.current())){publication.store();publication.commit();}
                assertNull(cleanup.current().binding());
                if(operation==5)throw probe.failure;
                return producer.publishResult();
            }
            return LongMemory.valueOf(2);
        }
    }
    @Test public void admittedNativeBodyNestedFailureAndArrayResultSourceDump()throws Throwable {
        for(boolean debug:new boolean[]{false,true})for(boolean dump:new boolean[]{false,true}){
            var scope=newScope();scope.setDebugMode(debug);scope.registerExtension(new StandardExtension());scope.registerExtension(new BodyExtension());
            var env=new Environment(scope,new ByteArrayOutputStream());var domain=new PhpOwnershipDomain();
            try{
                var context=new Context("function owned_body_outer($n){return runtime_body_probe($n);}function owned_body_inner($n){return runtime_body_probe($n);}function owned_body_array_failure($n){return count([$n,runtime_body_probe($n)]);}return 0;");context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
                var module=new JvmCompiler(env,context).compile(false);
                if(dump){var dumper=new ModuleDumper(context,env,true);var bytes=new ByteArrayOutputStream();dumper.save(module,bytes);module=dumper.load(new ByteArrayInputStream(bytes.toByteArray()));}
                scope.loadModule(module);scope.addUserModule(module);env.registerModule(module);module.include(env);
                try(var authority=new PhpRuntimeOwnership.Authority(env,domain);var execution=authority.enter()){
                    var probe=new BodyProbe();probe.authority=authority;probe.member=BodyFunctions.class.getMethod("runtime_body_probe",Environment.class,Memory.class);BODY_PROBE.set(probe);
                    try{
                        authority.registerNative(probe.member,new PhpRuntimeOwnership.CompleteNativeProtocol(){
                            public void validateMember(java.lang.reflect.Method actual){assertEquals(probe.member,actual);}
                            public void validateArguments(List<PhpRuntimeOwnership.Value> actual){assertEquals(1,actual.size());}
                        });
                        var count=php.runtime.ext.core.ArrayFunctions.class.getMethod("count",Environment.class,TraceInfo.class,Memory.class);
                        authority.registerNative(count,new PhpRuntimeOwnership.CompleteNativeProtocol(){
                            public void validateMember(java.lang.reflect.Method actual){assertEquals(count,actual);}
                            public void validateArguments(List<PhpRuntimeOwnership.Value> actual){assertEquals(1,actual.size());}
                        });
                        for(String name:new String[]{"owned_body_outer","owned_body_inner","owned_body_array_failure"}){
                            var function=env.fetchFunction(name);var member=function.getNativeMethod();
                            var plan=PhpRuntimeOwnership.readRuntimePlan(function.getData(),member.getName(),PhpOwnershipExecution.descriptor(member));
                            assertEquals(plan.unsupported().toString(),Collections.emptyList(),plan.unsupported());
                            authority.registerFunction(function,function.getData(),(p,m,bytes)->{});
                        }
                        try(var result=authority.invoke(env.fetchFunction("owned_body_outer"),TraceInfo.UNKNOWN,LongMemory.valueOf(1))){assertEquals(2,result.raw().toLong());}
                        assertEquals(2,probe.entered.size());assertNotSame(probe.entered.get(0),probe.entered.get(1));
                        assertNull(PhpRuntimeOwnership.currentNativeBody(env));assertEquals(0,domain.graph().liveNodes());
                        for(String name:new String[]{"owned_body_outer","owned_body_array_failure"}){
                            try(var ignored=authority.invoke(env.fetchFunction(name),TraceInfo.UNKNOWN,LongMemory.valueOf(name.endsWith("outer")?3:-1))){fail("native failure must escape");}
                            catch(php.runtime.exceptions.CriticalException failure){assertSame(probe.failure,failure);}
                            assertNull(PhpRuntimeOwnership.currentNativeBody(env));assertEquals(0,domain.graph().liveNodes());
                        }
                        try(var result=authority.invoke(env.fetchFunction("owned_body_outer"),TraceInfo.UNKNOWN,LongMemory.valueOf(4))){
                            assertEquals(9,result.raw().toValue(php.runtime.memory.ArrayMemory.class).getByScalar("value").toLong());
                            assertEquals(1,domain.graph().liveNodes());
                        }
                        assertNull(PhpRuntimeOwnership.currentNativeBody(env));assertEquals(0,domain.graph().liveNodes());
                        try(var ignored=authority.invoke(env.fetchFunction("owned_body_outer"),TraceInfo.UNKNOWN,LongMemory.valueOf(5))){fail("allocated native failure must escape");}
                        catch(php.runtime.exceptions.CriticalException failure){assertSame(probe.failure,failure);}
                        assertNull(PhpRuntimeOwnership.currentNativeBody(env));assertEquals(0,domain.graph().liveNodes());
                    }finally{BODY_PROBE.remove();}
                }
            }finally{env.doFinal();}
        }
    }
    @Test public void admittedCountConsumesLiteralArrayAndReturnsActualEvalSourceDump() throws Throwable {admittedCount(false);}
    @Test public void nestedNativeSendRetainsOuterArrayEvalSourceDump() throws Throwable {admittedCount(true);}
    private void admittedCount(boolean nested) throws Throwable {
        for(boolean debug:new boolean[]{false,true})for(boolean dump:new boolean[]{false,true}) {
            var scope=newScope();scope.setDebugMode(debug);scope.registerExtension(new StandardExtension());
            var env=new Environment(scope,new ByteArrayOutputStream());var domain=new PhpOwnershipDomain();
            try {
                var context=new Context("function owned_native_count($n){return count([$n,2,3]);}function owned_native_nested($n){return count([$n,count([$n])]);}return 0;");context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
                var module=new JvmCompiler(env,context).compile(false);
                if(dump){var dumper=new ModuleDumper(context,env,true);var bytes=new ByteArrayOutputStream();dumper.save(module,bytes);module=dumper.load(new ByteArrayInputStream(bytes.toByteArray()));}
                scope.loadModule(module);scope.addUserModule(module);env.registerModule(module);module.include(env);
                try(var authority=new PhpRuntimeOwnership.Authority(env,domain);var execution=authority.enter()){
                    var member=php.runtime.ext.core.ArrayFunctions.class.getMethod("count",Environment.class,TraceInfo.class,Memory.class);
                    int[] calls={0};
                    authority.registerNative(member,new PhpRuntimeOwnership.CompleteNativeProtocol(){
                        public void validateMember(java.lang.reflect.Method actual){assertEquals(member,actual);}
                        public void validateArguments(List<PhpRuntimeOwnership.Value> actual){calls[0]++;assertEquals(1,actual.size());assertEquals(PhpRuntimeOwnership.Role.VALUE,actual.get(0).role());assertEquals(1,actual.get(0).node().incoming());}
                    });
                    var function=env.fetchFunction(nested?"owned_native_nested":"owned_native_count");
                    var actualMember=function.getNativeMethod();var ordinary=PhpRuntimeOwnership.readRuntimePlan(function.getData(),actualMember.getName(),PhpOwnershipExecution.descriptor(actualMember));
                    assertNotNull(ordinary);assertEquals(ordinary.unsupported().toString(),Collections.emptyList(),ordinary.unsupported());
                    authority.registerFunction(function,function.getData(),(p,m,b)->assertEquals(PhpOwnershipExecution.EntryKind.FUNCTION,p.entryKind()));
                    try(var result=authority.invoke(function,TraceInfo.UNKNOWN,LongMemory.valueOf(1))){assertEquals(nested?2:3,result.raw().toLong());}
                    assertEquals(nested?2:1,calls[0]);assertEquals(0,domain.graph().liveNodes());
                }
            }finally{env.doFinal();}
        }
    }
}
