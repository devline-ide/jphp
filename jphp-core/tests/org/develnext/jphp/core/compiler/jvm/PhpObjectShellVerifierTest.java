package org.develnext.jphp.core.compiler.jvm;

import org.junit.Test;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import php.runtime.*;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.*;
import php.runtime.ext.*;
import php.runtime.loader.dump.ModuleDumper;
import php.runtime.reflection.*;
import php.runtime.reflection.helper.PhpObjectShellVerifier;
import java.io.*;
import java.util.*;
import static org.junit.Assert.*;
import static org.objectweb.asm.Opcodes.*;

/** Proves actual loaded shells without granting authority to their PHP method bodies. */
public class PhpObjectShellVerifierTest {
    private Environment environment(boolean debug) {
        var scope=new CompileScope();scope.registerExtension(new CoreExtension());scope.registerExtension(new SPLExtension());
        scope.setDebugMode(debug);return new Environment(scope,new ByteArrayOutputStream());
    }
    private ModuleEntity compile(Environment env,String source,boolean dumped) throws Throwable {
        var context=new Context(source);context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
        var module=new JvmCompiler(env,context).compile(false);
        if (dumped) {
            var dumper=new ModuleDumper(context,env,true);var bytes=new ByteArrayOutputStream();dumper.save(module,bytes);
            module=dumper.load(new ByteArrayInputStream(bytes.toByteArray()));
        }
        return module;
    }
    private void load(Environment env,ModuleEntity module) throws Throwable {
        env.scope.loadModule(module);env.scope.addUserModule(module);env.registerModule(module);
    }
    private ClassEntity clazz(ModuleEntity module,String name) {
        return module.getClasses().stream().filter(c->c.getName().equals(name)).findFirst().orElseThrow();
    }
    private void reject(Runnable action) {
        try {action.run();fail("Unproved shell accepted");} catch (IllegalArgumentException | IllegalStateException expected) {}
    }
    @Test public void actualInheritedShellAllocatesCertifiedPhysicalDefaultsSourceAndDump() throws Throwable {
        for (boolean debug:new boolean[]{false,true}) for (boolean dumped:new boolean[]{false,true}) {
            var env=environment(debug);
            try {
                var module=compile(env,"class ShellParent {public int $v=3;} class ShellChild extends ShellParent {public ?string $s=null;}",dumped);
                load(env,module);module.include(env);var entity=clazz(module,"ShellChild");entity.initializeRuntimeState(env);
                var domain=new PhpOwnershipDomain();var participant=domain.participant(env);
                try (var execution=participant.enter()) {
                    var arrays=new PhpOwnershipArrays(domain.heap());var objects=new PhpOwnershipObjects(domain.heap(),arrays);
                    var provider=PhpObjectShellVerifier.register(objects,env,entity);
                    var defaults=new IdentityHashMap<PropertyEntity,PhpOwnershipValues.Binding>();
                    for (var property:provider.properties()) defaults.put(property,PhpOwnershipValues.bindValue(domain,property.getDefaultValue(env).toImmutable(),null));
                    try (var allocation=objects.allocate(provider,env,defaults)) {
                        assertSame(entity.getNativeClass(),allocation.object().getClass());
                        assertEquals(3,allocation.object().getProperties().getByScalar("v").toLong());
                        assertTrue(allocation.object().getProperties().getByScalar("s").isNull());
                        assertSame(allocation.certificate(),((php.runtime.lang.BaseObject)allocation.object()).getOwnershipObject());
                    }
                    participant.close();
                }
            } finally {env.doFinal();}
        }
    }
    @Test public void sideEffectingInitializerAndConstructorRejectBeforeAnyInitializerRuns() throws Throwable {
        String key="jphp.object.shell.verifier.effect";
        for (boolean constructor:new boolean[]{false,true}) for (boolean dumped:new boolean[]{false,true}) {
            System.clearProperty(key);var env=environment(false);
            try {
                var module=compile(env,"class ShellEffect {}",dumped);var entity=clazz(module,"ShellEffect");
                var node=new ClassNode();new ClassReader(entity.getData()).accept(node,0);
                var method=node.methods.stream().filter(m->m.name.equals(constructor ? "<init>" : "<clinit>")).findFirst().orElseThrow();
                var effect=new InsnList();effect.add(new LdcInsnNode(key));effect.add(new LdcInsnNode("executed"));
                effect.add(new MethodInsnNode(INVOKESTATIC,"java/lang/System","setProperty","(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;",false));effect.add(new InsnNode(POP));
                method.instructions.insertBefore(method.instructions.getLast(),effect);
                var writer=new ClassWriter(ClassWriter.COMPUTE_MAXS);node.accept(writer);entity.setData(writer.toByteArray());
                load(env,module);reject(()->PhpObjectShellVerifier.requireLoadedShell(entity));assertNull(System.getProperty(key));
            } finally {env.doFinal();System.clearProperty(key);}
        }
    }
    @Test public void staleBytecodeAndUnprovedNativeAncestryNeverMintAllocationAuthority() throws Throwable {
        for (boolean dumped:new boolean[]{false,true}) {
            var env=environment(false);
            try {
                var module=compile(env,"class ShellFresh {} class ShellException extends Exception {}",dumped);load(env,module);
                var fresh=clazz(module,"ShellFresh");assertNotNull(PhpObjectShellVerifier.requireLoadedShell(fresh));
                byte[] stale=fresh.getData().clone();stale[stale.length-1]^=1;fresh.setData(stale);
                reject(()->PhpObjectShellVerifier.requireLoadedShell(fresh));
                reject(()->PhpObjectShellVerifier.requireLoadedShell(clazz(module,"ShellException")));
            } finally {env.doFinal();}
        }
    }
    @Test public void incompletePhpDefaultsRejectWithoutRunningInitialization() throws Throwable {
        var env=environment(false);
        try {
            var module=compile(env,"class ShellDeferred {public int $v=3;}",false);load(env,module);
            var entity=clazz(module,"ShellDeferred");entity.setDeferredInitialization(true);
            var domain=new PhpOwnershipDomain();var participant=domain.participant(env);
            try (var execution=participant.enter()) {
                var arrays=new PhpOwnershipArrays(domain.heap());var objects=new PhpOwnershipObjects(domain.heap(),arrays);
                assertFalse(entity.isRuntimeStateInitialized(env));reject(()->PhpObjectShellVerifier.register(objects,env,entity));
                assertFalse(entity.isRuntimeStateInitialized(env));entity.initializeRuntimeState(env);assertTrue(entity.isRuntimeStateInitialized(env));
                assertNotNull(PhpObjectShellVerifier.register(objects,env,entity));participant.close();
            }
        } finally {env.doFinal();}
    }
}
