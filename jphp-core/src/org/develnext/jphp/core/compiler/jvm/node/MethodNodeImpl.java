package org.develnext.jphp.core.compiler.jvm.node;

import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.MethodNode;

import java.util.ArrayList;

public class MethodNodeImpl extends MethodNode {

    public MethodNodeImpl() {
        super(Opcodes.ASM9);
        exceptions = new ArrayList();
        tryCatchBlocks = new ArrayList();
        localVariables = new ArrayList();
        attrs = new ArrayList();
    }

    public static MethodNodeImpl duplicate(MethodNode node) {
        MethodNodeImpl self = new MethodNodeImpl();

        self.name = node.name;
        self.desc = node.desc;
        self.signature = node.signature;
        self.access = node.access;
        self.exceptions = new ArrayList<>(node.exceptions);

        // Copy instructions and metadata through ASM's visitor API. Fresh labels
        // keep trait aliases independent without accessing MethodNode internals.
        node.instructions.resetLabels();
        node.accept(self);
        return self;
    }

    @Override
    public void accept(ClassVisitor cv) {
        super.accept(cv);
    }
}
