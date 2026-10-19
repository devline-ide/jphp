package php.runtime.loader;

import java.lang.classfile.*;
import java.lang.classfile.instruction.*;
import java.lang.constant.ClassDesc;
import java.lang.constant.MethodTypeDesc;
import java.util.List;

/** Adapt only the historical generated PHP finalizer; preserve all PHP methods and input identities. */
final class PhpJvmFinalizerBytecode {
    private static final ClassFile FILE = ClassFile.of(ClassFile.AttributesProcessingOption.PASS_ALL_ATTRIBUTES);
    private static final String DESTRUCTOR = "(Lphp/runtime/env/Environment;[Lphp/runtime/Memory;)Lphp/runtime/Memory;";

    static byte[] rewrite(byte[] data) {
        ClassModel model = FILE.parse(data);
        MethodModel legacy = null;
        for (MethodModel method : model.methods()) {
            if (method.methodName().equalsString("finalize") && method.methodType().equalsString("()V")
                    && method.flags().flagsMask() == ClassFile.ACC_PUBLIC && method.code().isPresent()
                    && historicalBody(model, method.code().get())) {
                legacy = method;
                break;
            }
        }
        if (legacy == null) return data;
        MethodModel selected = legacy;
        return FILE.transformClass(model, (builder, element) -> {
            if (element == selected) {
                builder.transformMethod(selected, (method, part) -> {
                    if (part instanceof CodeModel) {
                        method.withCode(code -> code.aload(0).invokestatic(
                                ClassDesc.of("php.runtime.invoke.PhpObjectLifecycle"), "enqueueJvmFinalizer",
                                MethodTypeDesc.ofDescriptor("(Lphp/runtime/lang/IObject;)V")).return_());
                    } else method.with(part);
                });
            } else builder.with(element);
        });
    }

    private static boolean historicalBody(ClassModel model, CodeModel code) {
        if (!code.exceptionHandlers().isEmpty()) return false;
        List<Instruction> instructions = code.elementList().stream().filter(Instruction.class::isInstance)
                .map(Instruction.class::cast).toList();
        if (instructions.size() != 12) return false;
        String owner = model.thisClass().asInternalName();
        if (!self(instructions.get(0)) || !call(instructions.get(1), owner, "isFinalized", "()Z")
                || !(instructions.get(2) instanceof BranchInstruction branch)
                || branch.opcode() != Opcode.IFEQ && branch.opcode() != Opcode.IFNE
                || !self(instructions.get(3)) || instructions.get(4).opcode() != Opcode.DUP
                || !call(instructions.get(5), owner, "doFinalize", "()V")
                || !self(instructions.get(6)) || !call(instructions.get(7), owner, "getEnvironment", "()Lphp/runtime/env/Environment;")
                || instructions.get(8).opcode() != Opcode.ACONST_NULL
                || !(instructions.get(9) instanceof InvokeInstruction destructor)
                || !destructor.name().stringValue().startsWith("__destruct$")
                || !call(destructor, owner, destructor.name().stringValue(), DESTRUCTOR)
                || instructions.get(10).opcode() != Opcode.POP || instructions.get(11).opcode() != Opcode.RETURN) return false;
        int position = 0;
        for (CodeElement element : code.elementList()) {
            if (element instanceof LabelTarget target && target.label() == branch.target()) return position == 11;
            if (element instanceof Instruction) position++;
        }
        return false;
    }

    private static boolean self(Instruction instruction) {
        return instruction instanceof LoadInstruction load && load.slot() == 0 && load.typeKind() == TypeKind.REFERENCE;
    }

    private static boolean call(Instruction instruction, String owner, String name, String type) {
        return instruction instanceof InvokeInstruction invoke && invoke.opcode() == Opcode.INVOKEVIRTUAL
                && invoke.owner().asInternalName().equals(owner) && invoke.name().equalsString(name) && invoke.type().equalsString(type);
    }
}
