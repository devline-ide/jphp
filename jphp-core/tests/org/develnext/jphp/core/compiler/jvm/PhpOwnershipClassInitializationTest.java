package org.develnext.jphp.core.compiler.jvm;

import org.junit.Test;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import php.runtime.*;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.*;
import php.runtime.ext.*;
import php.runtime.loader.dump.ModuleDumper;
import java.io.*;
import static org.junit.Assert.*;
import static org.objectweb.asm.Opcodes.*;

/** Actual reflected class-default producer must not bypass explicit invocation admission. */
public class PhpOwnershipClassInitializationTest {
    @Test public void unadmittedReflectedInitializerRejectsBeforeEffectsAndCanRetryOutsideSession() throws Throwable {
        String key="jphp.ownership.class.initializer.effect";
        for (boolean debug:new boolean[]{false,true}) for (boolean dump:new boolean[]{false,true}) {
            System.clearProperty(key);
            var scope=new CompileScope();scope.registerExtension(new CoreExtension());scope.registerExtension(new SPLExtension());scope.setDebugMode(debug);
            var env=new Environment(scope,new ByteArrayOutputStream());
            try {
                var context=new Context("class OwnershipInit {public $v=BRIDGE_DEFAULT;}");context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
                var module=new JvmCompiler(env,context).compile(false);
                if (dump) {
                    var dumper=new ModuleDumper(context,env,true);var bytes=new ByteArrayOutputStream();dumper.save(module,bytes);
                    module=dumper.load(new ByteArrayInputStream(bytes.toByteArray()));
                }
                var entity=module.getClasses().iterator().next();var node=new ClassNode();new ClassReader(entity.getData()).accept(node,0);
                var initializer=node.methods.stream().filter(m->m.name.equals("__$initEnvironment")).findFirst().orElseThrow();
                var effect=new InsnList();effect.add(new LdcInsnNode(key));effect.add(new LdcInsnNode("executed"));
                effect.add(new MethodInsnNode(INVOKESTATIC,"java/lang/System","setProperty","(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;",false));effect.add(new InsnNode(POP));
                initializer.instructions.insert(effect);var writer=new ClassWriter(ClassWriter.COMPUTE_MAXS);node.accept(writer);entity.setData(writer.toByteArray());
                scope.loadModule(module);scope.addUserModule(module);env.registerModule(module);module.include(env);
                assertNotNull(entity.getNativeInitEnvironment());assertTrue(entity.hasDeferredInitialization());assertFalse(entity.isRuntimeStateInitialized(env));
                var domain=new PhpOwnershipDomain();var participant=domain.participant(env);var session=new PhpOwnershipExecution.Session(participant,env);
                try (var execution=session.enter()) {
                    int stack=env.getCallStack().getTop();
                    try {entity.initializeRuntimeState(env);fail("Unadmitted default producer ran");} catch (IllegalArgumentException expected) {}
                    assertEquals(stack,env.getCallStack().getTop());assertNull(System.getProperty(key));assertFalse(entity.isRuntimeStateInitialized(env));
                }
                env.defineConstant("BRIDGE_DEFAULT",new php.runtime.memory.LongMemory(7),true);entity.initializeRuntimeState(env);
                assertEquals("executed",System.getProperty(key));assertTrue(entity.isRuntimeStateInitialized(env));
                assertEquals(7,entity.newObjectWithoutConstruct(env).getProperties().getByScalar("v").toLong());
                try (var execution=participant.enter()) {participant.close();}
            } finally {env.doFinal();System.clearProperty(key);}
        }
    }
}
