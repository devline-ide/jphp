package org.develnext.jphp.core.compiler.jvm;

import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.*;
import php.runtime.PhpRuntimeOwnership;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.*;
import php.runtime.loader.dump.ModuleDumper;
import php.runtime.reflection.ClassEntity;
import java.io.*;
import static org.junit.Assert.*;

/** CELL must keep its owning discard protocol while an exact borrowed receiver remains neutral. */
public class PhpBorrowedReceiverCellDiscardTest extends JvmCompilerCase {
    @FunctionalInterface private interface Check { void run(ClassEntity entity) throws Throwable; }
    private void modes(String body, Check check) throws Throwable {
        for(boolean debug:new boolean[]{false,true})for(boolean dumped:new boolean[]{false,true})for(boolean strict:new boolean[]{false,true}) {
            var scope=newScope();scope.setDebugMode(debug);scope.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
            var env=new Environment(scope,new ByteArrayOutputStream());
            try {
                var context=new Context((strict?"declare(strict_types=1);":"")+"class BorrowedCellBoundary {function other(){$this;}function __destruct(){"+body+"}}");
                context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
                var module=new JvmCompiler(env,context).compile(false);
                if(dumped){var d=new ModuleDumper(context,env,true);var bytes=new ByteArrayOutputStream();d.save(module,bytes);module=d.load(new ByteArrayInputStream(bytes.toByteArray()));}
                scope.loadModule(module);scope.addUserModule(module);env.registerModule(module);
                check.run(module.getClasses().stream().filter(c->c.getName().equals("BorrowedCellBoundary")).findFirst().orElseThrow());
            }finally{env.doFinal();}
        }
    }
    private static MethodNode method(ClassEntity entity,String phpName) {
        var member=entity.findMethod(phpName).getNativeMethod();var node=new ClassNode();new ClassReader(entity.getData()).accept(node,0);
        return node.methods.stream().filter(m->m.name.equals(member.getName())&&m.desc.equals(php.runtime.PhpOwnershipExecution.descriptor(member))).findFirst().orElseThrow();
    }
    private static int cellDiscards(MethodNode method) {
        int count=0;for(var instruction:method.instructions)if(instruction instanceof MethodInsnNode call&&call.owner.equals("php/runtime/PhpPrimitiveCellRuntime")&&call.name.equals("discard"))count++;
        return count;
    }
    @Test public void repeatedBorrowedReceiverDiscardsKeepExactLifecycleProtocol() throws Throwable {
        modes("$this;$this;echo 'd';",entity->{
            var destructor=method(entity,"__destruct");
            var plan=PhpRuntimeOwnership.readRuntimePlan(entity.getData(),destructor.name,destructor.desc);
            assertTrue(plan.lifecycle());assertTrue(plan.unsupported().toString(),plan.unsupported().isEmpty());
            assertEquals(1,plan.lifecycleReceivers().size());assertEquals(2,plan.lifecycleDiscards().size());
            assertEquals("Borrowed ObjectMemory cannot carry a CELL lease",0,cellDiscards(destructor));
            assertEquals("Ordinary $this discard still has its original CELL observer",1,cellDiscards(method(entity,"other")));
        });
    }
    @Test public void closureLocalDiscardInsideDestructorRetainsCellObserver() throws Throwable {
        modes("$this;$f=function(){static $n=0;return ++$n;};$f;echo 'd';",entity->{
            var destructor=method(entity,"__destruct");
            assertTrue("Only the borrowed receiver is neutral; closure CV remains observed",cellDiscards(destructor)>=1);
            assertEquals(1,cellDiscards(method(entity,"other")));
        });
    }
}
