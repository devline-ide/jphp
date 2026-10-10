package org.develnext.jphp.core.compiler.jvm.node;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;

import java.util.ArrayList;

public class ClassNodeImpl extends ClassNode {

    public ClassNodeImpl() {
        super(Opcodes.ASM9);
        version = Opcodes.V25; // Matches the Java 25 runtime baseline.
        this.interfaces = new ArrayList();
        this.visibleAnnotations = new ArrayList();
    }
}
