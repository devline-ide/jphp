package org.develnext.jphp.core.compiler.jvm;

import org.junit.Test;
import php.runtime.*;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.*;
import php.runtime.loader.dump.ModuleDumper;
import php.runtime.memory.output.serialization.PhpSerialization;
import java.io.*;
import java.util.*;
import static org.junit.Assert.*;

/** Native wire controls through the actual generated BODY/TEMP/RESULT path, not default activation. */
public class PhpNativeSerializationArenaTest extends JvmCompilerCase {
    void roundTrip(String wire,String expected) throws Throwable {
        roundTrip(wire,expected,authority -> {});
    }
    void roundTrip(String wire,String expected,java.util.function.Consumer<PhpRuntimeOwnership.Authority> setup) throws Throwable {
        for(boolean debug:new boolean[]{false,true})for(boolean dump:new boolean[]{false,true}) {
            var scope=newScope();scope.setDebugMode(debug);scope.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
            var env=new Environment(scope,new ByteArrayOutputStream());var domain=new PhpOwnershipDomain();
            try {
                var context=new Context("function native_reader(){return unserialize('"+wire+"');}return 0;");
                context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
                var module=new JvmCompiler(env,context).compile(false);
                if(dump){var dumper=new ModuleDumper(context,env,true);var bytes=new ByteArrayOutputStream();dumper.save(module,bytes);module=dumper.load(new ByteArrayInputStream(bytes.toByteArray()));}
                scope.loadModule(module);scope.addUserModule(module);env.registerModule(module);module.include(env);
                try(var authority=new PhpRuntimeOwnership.Authority(env,domain);var execution=authority.enter()) {
                    setup.accept(authority);
                    var member=PhpSerialization.class.getMethod("unserialize",Environment.class,TraceInfo.class,Memory.class);
                    authority.registerNative(member,new PhpRuntimeOwnership.CompleteNativeProtocol() {
                        public void validateMember(java.lang.reflect.Method actual){assertEquals(member,actual);}
                        public void validateArguments(List<PhpRuntimeOwnership.Value> arguments){
                            assertEquals(1,arguments.size());assertEquals(PhpRuntimeOwnership.Role.VALUE,arguments.getFirst().role());
                            assertEquals(wire,arguments.getFirst().raw().toString());assertNull(arguments.getFirst().node());
                        }
                    });
                    var function=env.fetchFunction("native_reader");var nativeMethod=function.getNativeMethod();
                    var plan=PhpRuntimeOwnership.readRuntimePlan(function.getData(),nativeMethod.getName(),PhpOwnershipExecution.descriptor(nativeMethod));
                    assertNotNull(plan);assertEquals(plan.unsupported().toString(),Collections.emptyList(),plan.unsupported());
                    authority.registerFunction(function,function.getData(),(p,m,b)->assertEquals(PhpOwnershipExecution.EntryKind.FUNCTION,p.entryKind()));
                    try(var result=authority.invoke(function,TraceInfo.UNKNOWN)) {
                        assertNull(PhpRuntimeOwnership.currentNativeBody(env));
                        assertEquals("debug="+debug+" dump="+dump,expected,PhpSerialization.serialize(env,TraceInfo.UNKNOWN,result.raw()).toString());
                    }
                    domain.collectCycles();assertEquals(0,domain.graph().liveNodes());
                }
            }finally{env.doFinal();}
        }
    }
    @Test public void singletonRootCycleMatchesNativeValueReturn() throws Throwable {
        roundTrip("a:3:{s:1:\"x\";R:1;s:1:\"x\";i:2;s:1:\"y\";R:1;}","a:2:{s:1:\"x\";i:2;s:1:\"y\";N;}");
    }
    @Test public void repeatedRootReferencePreservesSharedCell() throws Throwable {
        roundTrip("a:2:{i:0;R:1;i:1;R:1;}","a:2:{i:0;a:2:{i:0;R:2;i:1;R:2;}i:1;R:2;}");
    }
    @Test public void duplicateAddressUsesReplacementBeforePromotion() throws Throwable {
        roundTrip("a:3:{s:1:\"x\";i:1;s:1:\"x\";i:2;s:1:\"y\";R:2;}","a:2:{s:1:\"x\";i:2;s:1:\"y\";R:2;}");
    }
    @Test public void overwrittenCellCleanupDoesNotKeepSingletonAliasShared() throws Throwable {
        roundTrip("a:4:{s:1:\"x\";i:1;s:1:\"y\";R:2;s:1:\"x\";i:2;s:1:\"z\";R:2;}","a:3:{s:1:\"x\";i:2;s:1:\"y\";i:1;s:1:\"z\";R:2;}");
    }
    @Test public void nestedCycleFollowsPhysicalParentPlacement() throws Throwable {
        roundTrip("a:1:{i:0;a:2:{i:0;R:2;i:1;R:2;}}","a:1:{i:0;a:2:{i:0;R:2;i:1;R:2;}}");
    }
}
