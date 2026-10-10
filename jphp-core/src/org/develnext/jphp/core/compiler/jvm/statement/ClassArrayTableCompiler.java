package org.develnext.jphp.core.compiler.jvm.statement;

import org.develnext.jphp.core.compiler.jvm.node.MethodNodeImpl;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.TypeInsnNode;
import php.runtime.Memory;
import java.util.List;
import java.util.function.BiConsumer;
import static org.objectweb.asm.Opcodes.*;

/** Bound initializer methods by table entries; preserve initialization order and immutable table identity. */
final class ClassArrayTableCompiler {
    private ClassArrayTableCompiler() {}
    static <T> void write(ClassStmtCompiler owner, ExpressionStmtCompiler expr, String field, Class<?> arrayClass,
                          Class<?> componentClass, List<T> entries, BiConsumer<ExpressionStmtCompiler,T> value) {
        expr.writePushSmallInt(entries.size());
        expr.getMethodNode().instructions.add(new TypeInsnNode(ANEWARRAY,Type.getInternalName(componentClass)));
        expr.stackPop(); expr.stackPush(Memory.Type.REFERENCE);
        if (entries.size() <= 256) {
            for (int i=0;i<entries.size();i++) entry(expr,i,entries.get(i),value);
        } else for (int start=0;start<entries.size();start+=256) {
            var node=new MethodNodeImpl();node.access=ACC_PRIVATE|ACC_STATIC;node.name="$fill"+field+"$"+start;
            node.desc=Type.getMethodDescriptor(Type.VOID_TYPE,Type.getType(arrayClass));
            var method=new MethodStmtCompiler(owner,node);var part=new ExpressionStmtCompiler(method,null);method.writeHeader();
            var table=method.addLocalVariable("~table",null,arrayClass);
            for (int i=start;i<Math.min(start+256,entries.size());i++) {
                part.writeVarLoad(table);part.writePushSmallInt(i);value.accept(part,entries.get(i));
                node.instructions.add(new InsnNode(AASTORE));part.stackPop();part.stackPop();part.stackPop();
            }
            node.instructions.add(new InsnNode(RETURN));method.writeFooter();owner.node.methods.add(node);
            expr.writePushDup();expr.writeSysCall(owner.node.name,INVOKESTATIC,node.name,void.class,arrayClass);
        }
        expr.writePutStatic(field,arrayClass);
    }
    private static <T> void entry(ExpressionStmtCompiler expr,int index,T item,BiConsumer<ExpressionStmtCompiler,T> value) {
        expr.writePushDup();expr.writePushSmallInt(index);value.accept(expr,item);
        expr.getMethodNode().instructions.add(new InsnNode(AASTORE));expr.stackPop();expr.stackPop();expr.stackPop();
    }
}
