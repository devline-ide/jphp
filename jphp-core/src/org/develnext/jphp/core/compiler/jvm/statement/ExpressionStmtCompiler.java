package org.develnext.jphp.core.compiler.jvm.statement;

import org.develnext.jphp.core.compiler.common.ASMExpression;
import org.develnext.jphp.core.compiler.common.misc.StackItem;
import org.develnext.jphp.core.compiler.common.util.CompilerUtils;
import org.develnext.jphp.core.compiler.jvm.Constants;
import org.develnext.jphp.core.compiler.jvm.JvmCompiler;
import org.develnext.jphp.core.compiler.jvm.misc.LocalVariable;
import org.develnext.jphp.core.compiler.jvm.statement.expr.*;
import org.develnext.jphp.core.compiler.jvm.statement.expr.operator.DynamicAccessCompiler;
import org.develnext.jphp.core.compiler.jvm.statement.expr.operator.InstanceOfCompiler;
import org.develnext.jphp.core.compiler.jvm.statement.expr.value.*;
import org.develnext.jphp.core.tokenizer.TokenMeta;
import org.develnext.jphp.core.tokenizer.token.OpenEchoTagToken;
import org.develnext.jphp.core.tokenizer.token.Token;
import org.develnext.jphp.core.tokenizer.token.expr.ClassExprToken;
import org.develnext.jphp.core.tokenizer.token.expr.OperatorExprToken;
import org.develnext.jphp.core.tokenizer.token.expr.ValueExprToken;
import org.develnext.jphp.core.tokenizer.token.expr.operator.*;
import org.develnext.jphp.core.tokenizer.token.expr.value.*;
import org.develnext.jphp.core.tokenizer.token.expr.value.macro.*;
import org.develnext.jphp.core.tokenizer.token.stmt.*;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;
import php.runtime.Memory;
import php.runtime.OperatorUtils;
import php.runtime.annotation.Runtime;
import php.runtime.common.Association;
import php.runtime.common.Messages;
import php.runtime.common.StringUtils;
import php.runtime.env.Environment;
import php.runtime.env.TraceInfo;
import php.runtime.exceptions.CriticalException;
import php.runtime.exceptions.support.ErrorType;
import php.runtime.ext.support.compile.CompileClass;
import php.runtime.ext.support.compile.CompileConstant;
import php.runtime.ext.support.compile.CompileFunction;
import php.runtime.invoke.InvokeHelper;
import php.runtime.invoke.ObjectInvokeHelper;
import php.runtime.invoke.PhpCallFrame;
import php.runtime.invoke.cache.FunctionCallCache;
import php.runtime.invoke.cache.MethodCallCache;
import php.runtime.lang.ForeachIterator;
import php.runtime.lang.IObject;
import php.runtime.memory.*;
import php.runtime.memory.helper.UndefinedMemory;
import php.runtime.memory.support.MemoryUtils;
import php.runtime.reflection.*;
import php.runtime.reflection.support.Entity;

import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.*;

import static org.objectweb.asm.Opcodes.*;

public class ExpressionStmtCompiler extends StmtCompiler {
    protected final MethodStmtCompiler method;
    protected final MethodStmtToken methodStatement;
    protected final ExprStmtToken expression;

    private MethodNode node;
    private InsnList code;

    private Stack<Integer> exprStackInit = new Stack<Integer>();

    private int lastLineNumber = -1;
    // FIELD -> FIELD binding has no attested reference-CV consumer yet.
    private int runtimeFieldReferenceBindingDepth;
    public boolean isRuntimeFieldReferenceBinding() {return runtimeFieldReferenceBindingDepth != 0;}
    private int runtimeReferenceIteratorDepth;
    public boolean isRuntimeReferenceIterator() {return runtimeReferenceIteratorDepth != 0;}
    /** Iterator consumption has no attested FIELD-to-reference-CV publication. */
    public Memory writeForeachIteratorExpression(ExprStmtToken expression,boolean reference) {
        ExprStmtToken prepared=expression.isStmtList()?expression:expression.getAsmExpr();
        boolean propertyReference=reference && prepared!=null && prepared.getTokens().stream()
                .anyMatch(token->token.getClass()==DynamicAccessGetRefExprToken.class);
        if(propertyReference) {
            runtimeReferenceIteratorDepth++;
        }
        try{return writeExpression(expression,true,false,true);}
        finally{if(propertyReference)runtimeReferenceIteratorDepth--;}
    }
    private final Map<StackItem,MethodInsnNode> runtimeLiteralPairs=new IdentityHashMap<>();
    private final Set<Token> quietThisReads = Collections.newSetFromMap(new IdentityHashMap<>());
    // Only the current argument's property spine gets its callee's SEND_REF mode.
    private final Map<Token, LocalVariable> referenceArgumentFetches = new IdentityHashMap<>();
    private final Set<Token> arrayArgumentPropertyFetches = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<ArrayGetRefExprToken> incrementArrayTargets = Collections.newSetFromMap(new IdentityHashMap<>());

    private Map<Class<? extends Token>, BaseStatementCompiler> compilers;
    private static final Map<Class<? extends Token>, Class<? extends BaseStatementCompiler>> compilerRules;

    static {
        compilerRules = new HashMap<Class<? extends Token>, Class<? extends BaseStatementCompiler>>();
        compilerRules.put(IfStmtToken.class, IfElseCompiler.class);
        compilerRules.put(SwitchStmtToken.class, SwitchCompiler.class);
        compilerRules.put(FunctionStmtToken.class, FunctionCompiler.class);
        compilerRules.put(ClassStmtToken.class, ClassDeclarationCompiler.class);
        compilerRules.put(EchoRawToken.class, EchoRawCompiler.class);
        compilerRules.put(EchoStmtToken.class, EchoCompiler.class);
        compilerRules.put(OpenEchoTagToken.class, OpenEchoTagCompiler.class);
        compilerRules.put(ReturnStmtToken.class, ReturnCompiler.class);
        compilerRules.put(BodyStmtToken.class, BodyCompiler.class);
        compilerRules.put(WhileStmtToken.class, WhileCompiler.class);
        compilerRules.put(DoStmtToken.class, DoCompiler.class);
        compilerRules.put(ForStmtToken.class, ForCompiler.class);
        compilerRules.put(ForeachStmtToken.class, ForeachCompiler.class);
        compilerRules.put(TryStmtToken.class, TryCatchCompiler.class);
        compilerRules.put(ThrowStmtToken.class, ThrowCompiler.class);

        compilerRules.put(BreakStmtToken.class, JumpCompiler.class);
        compilerRules.put(ContinueStmtToken.class, JumpCompiler.class);

        compilerRules.put(GotoStmtToken.class, GotoCompiler.class);
        compilerRules.put(LabelStmtToken.class, GotoLabelCompiler.class);

        compilerRules.put(GlobalStmtToken.class, GlobalDefinitionCompiler.class);
        compilerRules.put(StaticStmtToken.class, StaticDefinitionCompiler.class);

        compilerRules.put(JvmCompiler.ClassInitEnvironment.class, ClassInitEnvironmentCompiler.class);
        compilerRules.put(JvmCompiler.ConstantInitEnvironment.class, ConstantInitEnvironmentCompiler.class);

        // values
        compilerRules.put(BooleanExprToken.class, BooleanValueCompiler.class);
        compilerRules.put(IntegerExprToken.class, IntValueCompiler.class);
        compilerRules.put(NullExprToken.class, NullValueCompiler.class);
        compilerRules.put(DoubleExprToken.class, DoubleValueCompiler.class);
        compilerRules.put(StringExprToken.class, StringValueCompiler.class);
        compilerRules.put(ShellExecExprToken.class, ShellExecValueCompiler.class);

        compilerRules.put(IncludeExprToken.class, ImportCompiler.class);
        compilerRules.put(IncludeOnceExprToken.class, ImportCompiler.class);
        compilerRules.put(RequireExprToken.class, ImportCompiler.class);
        compilerRules.put(RequireOnceExprToken.class, ImportCompiler.class);

        compilerRules.put(NewExprToken.class, NewValueCompiler.class);
        compilerRules.put(MatchExprToken.class, MatchValueCompiler.class);
        compilerRules.put(ThrowExprToken.class, ThrowValueCompiler.class);
        compilerRules.put(StringBuilderExprToken.class, StringBuilderValueCompiler.class);
        compilerRules.put(UnsetExprToken.class, UnsetCompiler.class);
        compilerRules.put(IssetExprToken.class, IssetCompiler.class);
        compilerRules.put(EmptyExprToken.class, EmptyCompiler.class);
        compilerRules.put(DieExprToken.class, DieCompiler.class);
        compilerRules.put(StaticExprToken.class, StaticValueCompiler.class);
        compilerRules.put(ClosureStmtToken.class, ClosureValueCompiler.class);
        compilerRules.put(GetVarExprToken.class, VarVarValueCompiler.class);
        compilerRules.put(NullCoalesceAssignExprToken.class, NullCoalesceAssignCompiler.class);
        compilerRules.put(ArrayAssignTargetExprToken.class, ArrayAssignTargetCompiler.class);
        compilerRules.put(SelfExprToken.class, SelfValueCompiler.class);
        compilerRules.put(StaticAccessExprToken.class, StaticAccessValueCompiler.class);
        compilerRules.put(StaticAccessIssetExprToken.class, StaticAccessValueCompiler.class);
        compilerRules.put(StaticAccessUnsetExprToken.class, StaticAccessValueCompiler.class);
        compilerRules.put(ListExprToken.class, ListCompiler.class);
        compilerRules.put(YieldExprToken.class, YieldValueCompiler.class);

        // operation
        compilerRules.put(InstanceofExprToken.class, InstanceOfCompiler.class);
        compilerRules.put(DynamicAccessAssignExprToken.class, DynamicAccessCompiler.class);
        compilerRules.put(DynamicAccessEmptyExprToken.class, DynamicAccessCompiler.class);
        compilerRules.put(DynamicAccessExprToken.class, DynamicAccessCompiler.class);
        compilerRules.put(DynamicAccessGetRefExprToken.class, DynamicAccessCompiler.class);
        compilerRules.put(DynamicAccessIssetExprToken.class, DynamicAccessCompiler.class);
        compilerRules.put(DynamicAccessUnsetExprToken.class, DynamicAccessCompiler.class);
        compilerRules.put(StaticAccessOperatorExprToken.class, StaticAccessValueAsOperatorCompiler.class);
    }

    public ExpressionStmtCompiler(MethodStmtCompiler method, ExprStmtToken expression) {
        super(method.getCompiler());
        this.method = method;
        this.expression = expression;
        this.node = method.node;
        this.code = method.node.instructions;
        this.methodStatement = method.statement != null ? method.statement : MethodStmtToken.of("", method.clazz.statement);
    }

    public ExpressionStmtCompiler(JvmCompiler compiler) {
        super(compiler);
        this.expression = null;
        ClassStmtCompiler classStmtCompiler = new ClassStmtCompiler(
                compiler, new ClassStmtToken(new TokenMeta("", 0, 0, 0, 0))
        );

        this.method = new MethodStmtCompiler(classStmtCompiler, (MethodStmtToken) null);
        this.methodStatement = method.statement != null ? method.statement : MethodStmtToken.of("", method.clazz.statement);

        this.node = method.node;
        this.code = method.node.instructions;
    }

    @SuppressWarnings("unchecked")
    public <T extends Token> T write(Class<? extends Token> clazz, T token) {
        BaseStatementCompiler<T> cmp = (BaseStatementCompiler<T>) getCompiler(clazz);
        if (cmp == null)
            throw new CriticalException("Cannot find compiler for " + clazz.getName());
        cmp.write(token);
        return token;
    }

    @SuppressWarnings("unchecked")
    public <T extends Token> T write(T token) {
        if (token == null)
            throw new IllegalArgumentException("Token cannot be null");

        return write(token.getClass(), token);
    }

    @SuppressWarnings("unchecked")
    public <T extends ValueExprToken> T writeValue(T token, boolean returnValue) {
        BaseStatementCompiler<T> cmp = (BaseStatementCompiler<T>) getCompiler(token.getClass());
        if (cmp == null)
            throw new CriticalException("Cannot find compiler for " + token.getClass().getName());

        if (cmp instanceof BaseExprCompiler)
            ((BaseExprCompiler) cmp).write(token, returnValue);
        else
            throw new CriticalException("Compiler is not for values");

        return token;
    }

    @SuppressWarnings("unchecked")
    public <T extends Token> BaseStatementCompiler<T> getCompiler(Class<T> clazz) {
        if (compilers == null) {
            compilers = new HashMap<Class<? extends Token>, BaseStatementCompiler>();
        }

        BaseStatementCompiler<T> r = compilers.get(clazz);
        if (r != null)
            return r;

        Class<? extends BaseStatementCompiler<T>> rule =
                (Class<? extends BaseStatementCompiler<T>>) compilerRules.get(clazz);

        if (rule == null)
            return null;

        try {
            r = rule.getConstructor(ExpressionStmtCompiler.class).newInstance(this);
            compilers.put(clazz, r);
            return r;
        } catch (Exception e) {
            throw new CriticalException(e);
        }
    }

    public MethodNode getMethodNode() {
        return node;
    }

    public MethodStmtCompiler getMethod() {
        return method;
    }

    public MethodStmtToken getMethodStatement() {
        return methodStatement;
    }

    public void makeVarStore(LocalVariable variable) {
        StackItem value = method.getStackCount() == 0 ? null : stackPeek();
        if (!method.ownership().store(code, variable, value == null ? -1 : value.ownershipEvalId))
            code.add(new VarInsnNode(ASTORE, variable.index));
        else if (value != null) value.ownershipEvalId = -1;
    }

    public void makeVarLoad(LocalVariable variable) {
        code.add(new VarInsnNode(ALOAD, variable.index));
    }

    public void makeUnknown(AbstractInsnNode node) {
        code.add(node);
    }

    public LabelNode makeLabel() {
        LabelNode el;
        code.add(el = new LabelNode());
        return el;
    }

    protected Memory getMacros(ValueExprToken token) {
        if (token instanceof SelfExprToken) {
            if (method.clazz.entity.isTrait()) {
                throw new IllegalArgumentException("Cannot use this in Traits");
            } else {
                return new StringMemory(method.clazz.statement.getFulledName());
            }
        }

        return null;
    }

    protected void stackPush(ValueExprToken token, StackItem.Type type) {
        method.push(new StackItem(token, type));
    }

    public void stackPush(StackItem item) {
        method.push(item);
    }

    public void stackPush(Memory memory) {
        method.push(new StackItem(memory));
    }

    public void stackPush(ValueExprToken token, Memory memory) {
        method.push(new StackItem(token, memory));
    }

    public void stackPush(Memory.Type type) {
        stackPush(null, StackItem.Type.valueOf(type));
    }

    public void stackPush(ValueExprToken token) {
        Memory o = CompilerUtils.toMemory(token);

        if (o != null) {
            stackPush(o);
        } else if (token instanceof StaticAccessExprToken) {
            StaticAccessExprToken access = (StaticAccessExprToken) token;
            if (access.getField() instanceof NameToken) {
                String constant = ((NameToken) access.getField()).getName();
                String clazz = null;
                ClassEntity entity = null;
                if (access.getClazz() instanceof ParentExprToken && !(php8CallFrame()
                        && (method.clazz.isClosure() || method.clazz.entity.isTrait()))) {
                    entity = method.clazz.entity.getParent();
                    if (entity != null)
                        clazz = entity.getName();
                } else if (access.getClazz() instanceof FulledNameToken) {
                    clazz = ((FulledNameToken) access.getClazz()).getName();
                    entity = compiler.getModule().findClass(clazz);
                }

                if (entity == null && method.clazz.entity != null && method.clazz.entity.getName().equalsIgnoreCase(clazz))
                    entity = method.clazz.entity;

                if (entity != null) {
                    ConstantEntity c = entity.findConstant(constant);

                    if (c != null && c.getValue() != null && c.isPublic() && !(php8CallFrame() && entity.isTrait())
                            && !(php8CallFrame() && php.runtime.invoke.PhpDeprecatedInvocation.marker(c) != null)) {
                        stackPush(c.getValue());
                        stackPeek().setLevel(-1);
                        return;
                    }
                }
            }
            stackPush(token, StackItem.Type.REFERENCE);
        } else {
            if (token instanceof VariableExprToken
                    && !methodStatement.isUnstableVariable((VariableExprToken) token)) {
                LocalVariable local = method.getLocalVariable(((VariableExprToken) token).getName());
                if (local != null && local.getValue() != null) {
                    stackPush(token, local.getValue());
                    stackPeek().setLevel(-1);
                    return;
                }
            }
            stackPush(token, StackItem.Type.REFERENCE);
        }

        stackPeek().setLevel(-1);
    }

    public StackItem stackPop() {
        return method.pop();
    }

    public ValueExprToken stackPopToken() {
        return method.pop().getToken();
    }

    public StackItem stackPeek() {
        return method.peek();
    }

    public void setStackPeekAsImmutable(boolean value) {
        method.peek().immutable = value;
    }

    public void setStackPeekAsImmutable() {
        setStackPeekAsImmutable(true);
    }

    public ValueExprToken stackPeekToken() {
        return method.peek().getToken();
    }

    public boolean stackEmpty(boolean relative) {
        if (relative)
            return method.getStackCount() == exprStackInit.peek();
        else
            return method.getStackCount() == 0;
    }

    public void writeLineNumber(Token token) {
        if (token.getMeta().getStartLine() > lastLineNumber) {
            lastLineNumber = token.getMeta().getStartLine();
            code.add(new LineNumberNode(lastLineNumber, new LabelNode()));
        }
    }

    public void writeWarning(Token token, String message) {
        writePushEnv();
        writePushTraceInfo(token);
        writePushConstString(message);
        writePushConstNull();
        writeSysDynamicCall(Environment.class, "warning", void.class, TraceInfo.class, String.class, Object[].class);
    }

    public void writePushBooleanAsMemory(boolean value) {
        if (value)
            code.add(new FieldInsnNode(
                    GETSTATIC, Type.getInternalName(Memory.class), "TRUE", Type.getDescriptor(Memory.class)
            ));
        else
            code.add(new FieldInsnNode(
                    GETSTATIC, Type.getInternalName(Memory.class), "FALSE", Type.getDescriptor(Memory.class)
            ));

        stackPush(Memory.Type.REFERENCE);
    }

    public void writePushMemory(Memory memory) {
        memory=CompilerUtils.immutableConstant(memory,getCompiler().getModule().getPhpLanguageProfile());
        Memory.Type type = Memory.Type.REFERENCE;

        if (memory instanceof PhpByteStringMemory) {
            String binary = memory.toBinaryString();
            // Modified UTF-8 encodes every projected byte in at most two bytes, including NUL.
            // Conservative chunks keep each constant below the classfile u2 UTF length limit.
            final int chunkSize = 16000;
            if (binary.length() <= chunkSize) {
                writePushConstString(binary);
            } else {
                writePushNewObject(StringBuilder.class);
                for (int from = 0; from < binary.length(); from += chunkSize) {
                    writePushConstString(binary.substring(from, Math.min(binary.length(), from + chunkSize)));
                    writeSysDynamicCall(StringBuilder.class, "append", StringBuilder.class, String.class);
                }
                writeSysDynamicCall(StringBuilder.class, "toString", String.class);
            }
            writeSysStaticCall(PhpByteStringMemory.class, "fromBinaryString", PhpByteStringMemory.class, String.class);
            if (binary.length() <= chunkSize) method.ownership().immutableByteLiteral((MethodInsnNode)code.getLast());
            setStackPeekAsImmutable();
            return;
        } else if (memory instanceof UndefinedMemory) {
            code.add(new FieldInsnNode(
                    GETSTATIC, Type.getInternalName(Memory.class), "UNDEFINED", Type.getDescriptor(Memory.class)
            ));
        } else if (memory instanceof NullMemory) {
            code.add(new FieldInsnNode(
                    GETSTATIC, Type.getInternalName(Memory.class), "NULL", Type.getDescriptor(Memory.class)
            ));
        } else if (memory instanceof FalseMemory) {
            writePushConstBoolean(false);
            return;
        } else if (memory instanceof TrueMemory) {
            writePushConstBoolean(true);
            return;
        } else if (memory instanceof KeyValueMemory) {
            writePushMemory(((KeyValueMemory) memory).key);
            writePopBoxing();

            writePushMemory(((KeyValueMemory) memory).getValue());
            writePopBoxing();

            if(method.ownership().runtimeEnabled()) {
                int childRuntimeEval=stackPeek().runtimeEvaluationId;
                writeSysStaticCall(KeyValueMemory.class,"valueOf",Memory.class,Memory.class,Memory.class);
                stackPeek().runtimeEvaluationId=childRuntimeEval;
                Memory key=((KeyValueMemory)memory).key;
                if(key.isString()||key.type==Memory.Type.INT)runtimeLiteralPairs.put(stackPeek(),(MethodInsnNode)code.getLast());
                else method.ownership().runtimeUnsupported("literal-entry-key-conversion");
                setStackPeekAsImmutable();return;
            }
            Object arrayKey = ((KeyValueMemory) memory).getArrayKey();

            if (arrayKey instanceof String) {
                writePushConstString((String) arrayKey);
                writeSysStaticCall(KeyValueMemory.class, "valueOf", Memory.class, Memory.class, Memory.class, String.class);
            } else if (arrayKey instanceof LongMemory) {
                writePushConstantMemory((Memory) arrayKey);
                writeSysStaticCall(KeyValueMemory.class, "valueOf", Memory.class, Memory.class, Memory.class, Memory.class);
            } else {
                writeSysStaticCall(KeyValueMemory.class, "valueOf", Memory.class, Memory.class, Memory.class);
            }

            setStackPeekAsImmutable();
            return;
        } else if (memory instanceof ReferenceMemory) {
            code.add(new TypeInsnNode(NEW, Type.getInternalName(ReferenceMemory.class)));
            code.add(new InsnNode(DUP));
            code.add(new MethodInsnNode(INVOKESPECIAL, Type.getInternalName(ReferenceMemory.class), Constants.INIT_METHOD, "()V", false));
        } else if (memory instanceof ArrayMemory) {
            ArrayMemory array = (ArrayMemory) memory;

            writePushConstInt(array.size());

            if (!array.isList()) {
                writeSysStaticCall(ArrayMemory.class, "createHashed", ArrayMemory.class, int.class);
            } else {
                writeSysStaticCall(ArrayMemory.class, "createListed", ArrayMemory.class, int.class);
            }

            stageRuntimeArray();
            ForeachIterator foreachIterator = ((ArrayMemory) memory).foreachIterator(false, false);
            while (foreachIterator.next()) {
                writePushDup();
                int childRuntimeEval;
                if (array.isList()) {
                    writePushMemory(foreachIterator.getValue());
                    writePopBoxing();childRuntimeEval=stackPeek().runtimeEvaluationId;
                    writeSysDynamicCall(ArrayMemory.class, "add", ReferenceMemory.class, Memory.class);
                } else {
                    Memory key = foreachIterator.getMemoryKey();
                    writePushMemory(key);

                    if (!key.isString()) {
                        writePopBoxing();
                    }

                    writePushMemory(foreachIterator.getValue());
                    writePopBoxing();childRuntimeEval=stackPeek().runtimeEvaluationId;

                    writeSysDynamicCall(ArrayMemory.class, "put", ReferenceMemory.class, Object.class, Memory.class);
                }

                writePopAll(1);releaseRuntimeEvaluation(childRuntimeEval,"ENTRY");
            }
            /*stackPop();
            stackPush(memory);*/

            setStackPeekAsImmutable();
            return;
        } else {
            switch (memory.type) {
                case INT: {
                    code.add(new LdcInsnNode(memory.toLong()));
                    type = Memory.Type.INT;
                }
                break;
                case DOUBLE: {
                    code.add(new LdcInsnNode(memory.toDouble()));
                    type = Memory.Type.DOUBLE;
                }
                break;
                case STRING: {
                    code.add(new LdcInsnNode(memory.toString()));
                    type = Memory.Type.STRING;
                }
                break;
            }
        }

        stackPush(type);
        setStackPeekAsImmutable();
    }

    public boolean writePopBoxing(boolean asImmutable) {
        return writePopBoxing(stackPeek().type, asImmutable);
    }

    public boolean writePopBoxing(boolean asImmutable, boolean useConstants) {
        return writePopBoxing(stackPeek().type, asImmutable, useConstants);
    }

    public boolean writePopBoxing() {
        return writePopBoxing(false);
    }

    public boolean writePopBoxing(Class<?> clazz, boolean asImmutable) {
        return writePopBoxing(StackItem.Type.valueOf(clazz), asImmutable);
    }

    public boolean writePopBoxing(Class<?> clazz) {
        return writePopBoxing(clazz, false);
    }

    public boolean writePopBoxing(StackItem.Type type) {
        return writePopBoxing(type, false);
    }

    public boolean writePopBoxing(StackItem.Type type, boolean asImmutable) {
        return writePopBoxing(type, asImmutable, true);
    }

    public boolean writePopBoxing(StackItem.Type type, boolean asImmutable, boolean useConstants) {
        switch (type) {
            case STRING_VALUE:
                writeSysStaticCall(php.runtime.PhpByteStringValues.class,"box",Memory.class,Object.class);
                setStackPeekAsImmutable();return true;
            case BOOL:
                int runtimeBooleanEval=stackPeek().runtimeEvaluationId;
                /*if (useConstants && code.getLast() instanceof InsnNode) {
                    InsnNode node = (InsnNode) code.getLast();

                    StackItem stackPeek = stackPeek();

                    if (stackPeek.type == StackItem.Type.BOOL) {
                        switch (node.getOpcode()) {
                            case ICONST_0:
                            case ICONST_1:
                                code.remove(node);

                                stackPop();
                                writePushBooleanAsMemory(node.getOpcode() != ICONST_0);
                                break;
                            default:
                                writeSysStaticCall(TrueMemory.class, "valueOf", Memory.class, type.toClass());
                        }

                        break;
                    }
                }*/

                writeSysStaticCall(TrueMemory.class, "valueOf", Memory.class, type.toClass());
                stackPeek().runtimeEvaluationId=runtimeBooleanEval;
                setStackPeekAsImmutable();
                return true;
            case SHORT:
            case BYTE:
            case LONG:
            case INT: {
                if (useConstants && code.getLast() instanceof LdcInsnNode) {
                    LdcInsnNode node = (LdcInsnNode) code.getLast();
                    code.remove(node);

                    stackPop();

                    writePushConstantMemory(LongMemory.valueOf((Long) node.cst));
                } else {
                    writeSysStaticCall(LongMemory.class, "valueOf", Memory.class, type.toClass());
                }

                setStackPeekAsImmutable();
            }
            return true;
            case FLOAT:
            case DOUBLE: {
                if (useConstants && code.getLast() instanceof LdcInsnNode) {
                    LdcInsnNode node = (LdcInsnNode) code.getLast();
                    code.remove(node);

                    stackPop();

                    if (type == StackItem.Type.FLOAT) {
                        writePushConstantMemory(DoubleMemory.valueOf((Float) node.cst));
                    } else {
                        writePushConstantMemory(DoubleMemory.valueOf((Double) node.cst));
                    }
                } else {
                    writeSysStaticCall(DoubleMemory.class, "valueOf", Memory.class, type.toClass());
                }

                setStackPeekAsImmutable();
                return true;
            }
            case STRING: {
                if (useConstants && code.getLast() instanceof LdcInsnNode) {
                    LdcInsnNode node = (LdcInsnNode) code.getLast();
                    code.remove(node);

                    stackPop();

                    writePushConstantMemory(StringMemory.valueOf(node.cst.toString()));
                } else {
                    writeSysStaticCall(StringMemory.class, "valueOf", Memory.class, String.class);
                }

                setStackPeekAsImmutable();
                return true;
            }
            case REFERENCE: {
                if (asImmutable) {
                    writePopImmutable();
                }
            }
            break;
        }
        return false;
    }

    public void writePushSmallInt(int value) {
        switch (value) {
            case -1:
                code.add(new InsnNode(ICONST_M1));
                break;
            case 0:
                code.add(new InsnNode(ICONST_0));
                break;
            case 1:
                code.add(new InsnNode(ICONST_1));
                break;
            case 2:
                code.add(new InsnNode(ICONST_2));
                break;
            case 3:
                code.add(new InsnNode(ICONST_3));
                break;
            case 4:
                code.add(new InsnNode(ICONST_4));
                break;
            case 5:
                code.add(new InsnNode(ICONST_5));
                break;
            default:
                code.add(new LdcInsnNode(value));
        }

        stackPush(Memory.Type.INT);
    }

    public void writePushScalarBoolean(boolean value) {
        writePushSmallInt(value ? 1 : 0);
        stackPop();
        stackPush(value ? Memory.TRUE : Memory.FALSE);
    }

    public void writePushString(String value) {
        writePushMemory(new StringMemory(value));
    }

    public void writePushConstString(String value) {
        writePushString(value);
    }

    public void writePushConstDouble(double value) {
        code.add(new LdcInsnNode(value));
        stackPush(null, StackItem.Type.DOUBLE);
    }

    public void writePushConstFloat(float value) {
        code.add(new LdcInsnNode(value));
        stackPush(null, StackItem.Type.FLOAT);
    }

    public void writePushConstLong(long value) {
        code.add(new LdcInsnNode(value));
        stackPush(null, StackItem.Type.LONG);
    }

    public void writePushConstInt(int value) {
        writePushSmallInt(value);
    }

    public void writePushConstShort(short value) {
        writePushConstInt(value);
        stackPop();
        stackPush(null, StackItem.Type.SHORT);
    }

    public void writePushConstByte(byte value) {
        writePushConstInt(value);
        stackPop();
        stackPush(null, StackItem.Type.BYTE);
    }

    public void writePushConstBoolean(boolean value) {
        writePushConstInt(value ? 1 : 0);
        stackPop();
        stackPush(null, StackItem.Type.BOOL);
    }

    public void writePushSelf(boolean withLower) {
        if (compiler.getModule().getPhpLanguageProfile() == php.runtime.common.PhpLanguageProfile.PHP_8_5) {
            writePushEnv();
            writePushTraceInfo(methodStatement);
            writeSysDynamicCall(Environment.class, "__getSelf", String.class, TraceInfo.class);
            if (withLower) writePushDupLowerCase();
        } else if (method.clazz.entity.isTrait()) {
            if (method.getLocalVariable("~class_name") != null) {
                writeVarLoad("~class_name");
            } else {
                writePushEnv();
                writeSysDynamicCall(Environment.class, "__getMacroClass", Memory.class);
            }
            writePopString();
            if (withLower)
                writePushDupLowerCase();
        } else {
            writePushConstString(method.clazz.statement.getFulledName());
            if (withLower)
                writePushConstString(method.clazz.statement.getFulledName().toLowerCase());
        }
    }

    public void writePushStatic() {
        writePushEnv();
        if (php8CallFrame()) {
            writePushTraceInfo(methodStatement);
            writeSysStaticCall(php.runtime.invoke.PhpMethodResolver.class, "staticClass", ClassEntity.class,
                    Environment.class, TraceInfo.class);
            writeSysDynamicCall(php.runtime.reflection.support.Entity.class, "getName", String.class);
        } else writeSysDynamicCall(Environment.class, "getLateStatic", String.class);
    }

    public void writePushParent(Token token) {
        writePushEnv();
        writePushTraceInfo(token);

        if (!php8CallFrame() && method.getLocalVariable("~class_name") != null) {
            writeVarLoad("~class_name");
            writeSysDynamicCall(Environment.class, "__getParent", String.class, TraceInfo.class, String.class);
        } else {
            writeSysDynamicCall(Environment.class, "__getParent", String.class, TraceInfo.class);
        }
    }

    public void writePushLocal() {
        writeVarLoad("~local");
    }

    public void writePushEnv() {
        method.entity.setUsesStackTrace(true);

        LocalVariable variable = method.getLocalVariable("~env");
        if (variable == null) {
            if (!methodStatement.isStatic())
                writePushEnvFromSelf();
            else
                throw new RuntimeException("Cannot find `~env` variable");
            return;
        }

        stackPush(Memory.Type.REFERENCE);
        makeVarLoad(variable);
    }

    public void writePushEnvFromSelf() {
        method.entity.setUsesStackTrace(true);

        writeVarLoad("~this");
        writeSysDynamicCall(null, "getEnvironment", Environment.class);
    }

    public void writePushDup(StackItem.Type type) {
        stackPush(null, type);
        if (type.size() == 2)
            code.add(new InsnNode(DUP2));
        else
            code.add(new InsnNode(DUP));
    }

    public void writePushDup() {
        StackItem item = method.peek();
        stackPush(item);

        if (item.type.size() == 2)
            code.add(new InsnNode(DUP2));
        else
            code.add(new InsnNode(DUP));
    }

    public void writePushDupLowerCase() {
        writePushDup();
        writePopString();
        writeGetStatic(java.util.Locale.class, "ROOT", java.util.Locale.class);
        writeSysDynamicCall(String.class, "toLowerCase", String.class, java.util.Locale.class);
    }

    public void writePushNull() {
        writePushMemory(Memory.NULL);
    }

    public void writePushVoid() {
        writePushMemory(Memory.UNDEFINED);
    }

    public void writePushConstNull() {
        stackPush(Memory.Type.REFERENCE);
        code.add(new InsnNode(ACONST_NULL));
    }

    public void writePushNewObject(Class clazz) {
        code.add(new TypeInsnNode(NEW, Type.getInternalName(clazz)));
        stackPush(Memory.Type.REFERENCE);
        writePushDup();

        writeSysCall(clazz, INVOKESPECIAL, Constants.INIT_METHOD, void.class);
        stackPop();
    }

    public void writePushNewObject(String internalName, Class... paramClasses) {
        code.add(new TypeInsnNode(NEW, internalName));
        stackPush(Memory.Type.REFERENCE);
        writePushDup();
        writeSysCall(internalName, INVOKESPECIAL, Constants.INIT_METHOD, void.class, paramClasses);
        stackPop();
    }

    public void writePushStaticCall(Method method) {
        writeSysStaticCall(
                method.getDeclaringClass(),
                method.getName(),
                method.getReturnType(),
                method.getParameterTypes()
        );
    }

    private LocalVariable beginRuntimeNativePacket(CompileFunction.Method nativeMethod,int argc,int evalId) {
        String owner=Type.getInternalName(nativeMethod.method.getDeclaringClass());
        String descriptor=Type.getMethodDescriptor(nativeMethod.method);
        method.ownership().runtimeProducer(evalId,"NATIVE_RESULT");
        method.ownership().runtimeNativeMember(evalId,owner,nativeMethod.method.getName(),descriptor,argc);
        LocalVariable packet=method.addLocalVariable("~runtimeNativePacket"+evalId,null,Object.class);
        code.add(new VarInsnNode(ALOAD,method.ownership().runtimeFrameIndex()));code.add(new LdcInsnNode(evalId));
        code.add(new LdcInsnNode(Type.getType(nativeMethod.method.getDeclaringClass())));
        code.add(new LdcInsnNode(nativeMethod.method.getName()));code.add(new LdcInsnNode(descriptor));code.add(new LdcInsnNode(argc));
        code.add(new MethodInsnNode(INVOKESTATIC,"php/runtime/PhpRuntimeOwnership","nativeCall",
                "(Lphp/runtime/PhpRuntimeOwnership$Frame;ILjava/lang/Class;Ljava/lang/String;Ljava/lang/String;I)Lphp/runtime/PhpRuntimeOwnership$NativePacket;",false));
        code.add(new VarInsnNode(ASTORE,packet.index));return packet;
    }
    private void sendRuntimeNative(LocalVariable packet,int index,int sourceEval) {
        if (packet == null) return;
        LocalVariable raw=method.addLocalVariable("~runtimeNativeSend"+method.ownership().reserveRuntimeEval(),null,Memory.class);
        code.add(new VarInsnNode(ASTORE,raw.index));code.add(new VarInsnNode(ALOAD,packet.index));
        code.add(new LdcInsnNode(index));code.add(new VarInsnNode(ALOAD,raw.index));code.add(new LdcInsnNode(sourceEval));
        code.add(new MethodInsnNode(INVOKESTATIC,"php/runtime/PhpRuntimeOwnership","nativeSend",
                "(Lphp/runtime/PhpRuntimeOwnership$NativePacket;ILphp/runtime/Memory;I)Lphp/runtime/Memory;",false));
        if (sourceEval >= 0) method.ownership().runtimeConsumer(sourceEval,"SEND");
        stackPeek().runtimeEvaluationId=-1;
    }
    private void sendRuntimeNativeBorrow(LocalVariable packet,int index,int sourceEval,int callId,boolean fast,boolean wrappedTransport) {
        LocalVariable raw=method.addLocalVariable("~runtimeNativeBorrow"+method.ownership().reserveRuntimeEval(),null,Memory.class);
        code.add(new VarInsnNode(ASTORE,raw.index));code.add(new VarInsnNode(ALOAD,packet.index));
        code.add(new LdcInsnNode(index));code.add(new VarInsnNode(ALOAD,raw.index));code.add(new LdcInsnNode(sourceEval));
        code.add(new InsnNode(fast?ICONST_1:ICONST_0));code.add(new InsnNode(wrappedTransport?ICONST_1:ICONST_0));
        MethodInsnNode borrow=new MethodInsnNode(INVOKESTATIC,"php/runtime/PhpRuntimeOwnership","nativeBorrowSend",
                "(Lphp/runtime/PhpRuntimeOwnership$NativePacket;ILphp/runtime/Memory;IZZ)Lphp/runtime/Memory;",false);
        code.add(borrow);method.ownership().runtimeNativeBorrow(callId,borrow);
        if(sourceEval>=0)method.ownership().runtimeConsumer(sourceEval,"SEND");
        stackPeek().runtimeEvaluationId=-1;
    }
    private LocalVariable nativeDirectCvLocal(VariableExprToken variable) {
        LocalVariable local=method.getLocalVariable(variable.getName());
        AbstractInsnNode source=code.getLast();
        while(source!=null&&source.getOpcode()<0)source=source.getPrevious();
        return local!=null&&source instanceof VarInsnNode read&&read.getOpcode()==ALOAD&&read.var==local.index?local:null;
    }
    private void sendRuntimeNativeDirectCv(LocalVariable packet,int argument,LocalVariable local,int callId) {
        AbstractInsnNode source=code.getLast();
        while(source!=null&&source.getOpcode()<0)source=source.getPrevious();
        if(local==null||!(source instanceof VarInsnNode read)||read.getOpcode()!=ALOAD||read.var!=local.index)
            throw new IllegalArgumentException("Native direct CV lacks its exact physical local read");
        LocalVariable scratch=method.addLocalVariable("~runtimeNativeDirectCv"+method.ownership().reserveRuntimeEval(),null,Memory.class);
        VarInsnNode store=new VarInsnNode(ASTORE,scratch.index);code.add(store);
        code.add(new VarInsnNode(ALOAD,packet.index));code.add(new LdcInsnNode(argument));
        code.add(new VarInsnNode(ALOAD,scratch.index));code.add(new LdcInsnNode(local.index));
        MethodInsnNode helper=new MethodInsnNode(INVOKESTATIC,"php/runtime/PhpRuntimeOwnership","nativeDirectCvSend",
                "(Lphp/runtime/PhpRuntimeOwnership$NativePacket;ILphp/runtime/Memory;I)Lphp/runtime/Memory;",false);
        code.add(helper);method.ownership().runtimeNativeDirectCv(callId,local.index,read,store,helper);
        stackPeek().runtimeEvaluationId=-1;
    }
    private void recordRuntimeHandler(CompileFunction.Method selected,ExprStmtToken argument,int index,int callId,
                                      int producer,String closure) {
        if (index==0 && producer>=0 && closure!=null && argument.isSingle()
                && argument.getSingle() instanceof ClosureStmtToken
                && selected.method.getDeclaringClass()==php.runtime.ext.core.LangFunctions.class
                && selected.method.getName().equals("set_error_handler"))
            method.ownership().runtimeHandlerSite(callId,producer,closure,(MethodInsnNode)code.getLast());
    }
    private void writeRuntimeNativeBody(Method selected,LocalVariable packet,int callId) {
        writeRuntimeNativeBody(selected,selected,packet,callId);
    }
    private void writeRuntimeNativeBody(Method semantic,Method selected,LocalVariable packet,int callId) {
        LocalVariable token=method.addLocalVariable("~runtimeNativeBody"+callId,null,Object.class);
        LocalVariable failure=method.addLocalVariable("~runtimeNativeFailure"+callId,null,Throwable.class);
        MethodInsnNode enter=new MethodInsnNode(INVOKESTATIC,"php/runtime/PhpRuntimeOwnership","nativeEnterBody",
                "(Lphp/runtime/PhpRuntimeOwnership$NativePacket;)Lphp/runtime/PhpRuntimeOwnership$NativeBody;",false);
        code.add(new VarInsnNode(ALOAD,packet.index));code.add(enter);code.add(new VarInsnNode(ASTORE,token.index));
        LabelNode start=new LabelNode(),end=new LabelNode(),handler=new LabelNode(),joined=new LabelNode();
        code.add(start);writePushStaticCall(selected);MethodInsnNode body=(MethodInsnNode)code.getLast();code.add(end);
        MethodInsnNode leave=new MethodInsnNode(INVOKESTATIC,"php/runtime/PhpRuntimeOwnership","nativeLeaveBody",
                "(Lphp/runtime/PhpRuntimeOwnership$NativeBody;)V",false);
        code.add(new VarInsnNode(ALOAD,token.index));code.add(leave);code.add(new JumpInsnNode(GOTO,joined));
        code.add(handler);code.add(new VarInsnNode(ASTORE,failure.index));code.add(new VarInsnNode(ALOAD,token.index));
        code.add(new VarInsnNode(ALOAD,failure.index));
        MethodInsnNode fail=new MethodInsnNode(INVOKESTATIC,"php/runtime/PhpRuntimeOwnership","nativeFailBody",
                "(Lphp/runtime/PhpRuntimeOwnership$NativeBody;Ljava/lang/Throwable;)Ljava/lang/Throwable;",false);
        code.add(fail);code.add(new InsnNode(ATHROW));code.add(joined);
        TryCatchBlockNode guard=new TryCatchBlockNode(start,end,handler,"java/lang/Throwable");
        method.node.tryCatchBlocks.add(0,guard);
        method.ownership().runtimeNativeBody(callId,semantic,selected,enter,body,leave,fail,guard);
    }
    private void finishRuntimeNative(LocalVariable packet,int evalId) {
        if (packet == null) return;
        StackItem.Type primitive=stackPeek().type;writePopBoxing();
        MethodInsnNode boxing=(primitive==StackItem.Type.INT||primitive==StackItem.Type.LONG||primitive==StackItem.Type.BOOL)?(MethodInsnNode)code.getLast():null;
        LocalVariable raw=method.addLocalVariable("~runtimeNativeResult"+evalId,null,Memory.class);
        code.add(new VarInsnNode(ASTORE,raw.index));code.add(new VarInsnNode(ALOAD,packet.index));
        code.add(new VarInsnNode(ALOAD,raw.index));code.add(new LdcInsnNode(evalId));
        code.add(new MethodInsnNode(INVOKESTATIC,"php/runtime/PhpRuntimeOwnership","nativeResult",
                "(Lphp/runtime/PhpRuntimeOwnership$NativePacket;Lphp/runtime/Memory;I)Lphp/runtime/Memory;",false));
        if(boxing!=null)method.ownership().runtimeNativeScalarResult(evalId,boxing,(MethodInsnNode)code.getLast());
        code.add(new InsnNode(ACONST_NULL));code.add(new VarInsnNode(ASTORE,raw.index));
        stackPeek().runtimeEvaluationId=evalId;
    }
    /** Keep historical immutable transports only for this module's legacy direct ord/chr calls. */
    private static CompileFunction.Method legacyByteCharacterMethod(String name, CompileFunction.Method selected, int arity) {
        if (selected == null || arity != 1 || selected.argsCount != 1
                || selected.method.getDeclaringClass() != php.runtime.ext.core.StringFunctions.class
                || !Arrays.equals(selected.parameterTypes, new Class<?>[]{Environment.class, TraceInfo.class, Memory.class}))
            return selected;
        String target;
        Class<?> parameter;
        if (name.equals("ord") && selected.method.getName().equals("nativeOrd") && selected.resultType == long.class) {
            target = "ord"; parameter = char.class;
        } else if (name.equals("chr") && selected.method.getName().equals("nativeChr") && selected.resultType == Memory.class) {
            target = "chr"; parameter = int.class;
        } else return selected;
        try {
            return new CompileFunction.Method(php.runtime.ext.core.StringFunctions.class.getDeclaredMethod(target, parameter), 1, false);
        } catch (java.lang.NoSuchMethodException missing) {
            throw new IllegalStateException("Missing historical byte-character transport", missing);
        }
    }

    Memory writePushCompileFunction(CallExprToken function, CompileFunction compileFunction, boolean returnValue,
                                    boolean writeOpcode, PushCallStatistic statistic) {
        CompileFunction.Method ordinary = compileFunction.find(function.getParameters().size());
        CompileFunction.Method method = php8CallFrame() ? compileFunction.findDirect(function.getParameters().size())
                : legacyByteCharacterMethod(compileFunction.name, ordinary, function.getParameters().size());
        Runtime.DirectCall directEntry = method != ordinary ? ordinary.method.getAnnotation(Runtime.DirectCall.class) : null;
        if (method == null) {
            if (!writeOpcode)
                return Memory.NULL;

            writeWarning(function, Messages.ERR_EXPECT_LEAST_PARAMS.fetch(
                    compileFunction.name, compileFunction.getMinArgs(), function.getParameters().size()
            ));
            if (returnValue)
                writePushNull();

            return null;
        } else if (!method.isVarArg() && method.argsCount < function.getParameters().size()) {
            if (!writeOpcode)
                return Memory.NULL;

            writeWarning(function, Messages.ERR_EXPECT_EXACTLY_PARAMS.fetch(
                    compileFunction.name, method.argsCount, function.getParameters().size()
            ));
            if (returnValue)
                writePushNull();

            return null;
        }

        if (statistic != null)
            statistic.returnType = StackItem.Type.valueOf(method.resultType);

        Class[] types = method.parameterTypes;
        ListIterator<ExprStmtToken> iterator = function.getParameters().listIterator();

        Object[] arguments = new Object[types.length];
        int j = 0;
        boolean immutable = method.isImmutable;
        boolean runtimeNative=writeOpcode && this.method.ownership().runtimeEnabled();
        if (runtimeNative && immutable) {
            Memory folded=writePushCompileFunction(function,compileFunction,true,false,statistic);
            if (folded != null) return returnValue ? folded : null;
            immutable=false;
        }
        int runtimeResultEval=-1;LocalVariable runtimePacket=null;
        if (runtimeNative && !immutable) {
            runtimeResultEval=this.method.ownership().reserveRuntimeEval();
            runtimePacket=beginRuntimeNativePacket(method,function.getParameters().size(),runtimeResultEval);
        }
        Runtime.Frameless frameless=method.method.getAnnotation(Runtime.Frameless.class);
        String nativeBorrow=frameless != null && Arrays.stream(frameless.value()).anyMatch(arity->arity==function.getParameters().size())
                ? "borrowNativeFastArgument" : "borrowNativeArgument";

        boolean init = false;
        for (int i = 0; i < method.parameterTypes.length; i++) {
            Class<?> argType = method.parameterTypes[i];
            boolean isRef = method.references[i];
            boolean fetchByReference = php8CallFrame() && PhpCallFrame.nativeArgumentByReference(method,i);
            boolean borrowedNativeInput = php8CallFrame() && isRef && !fetchByReference;
            boolean isMutable = method.isPresentAnnotationOfParam(i, Runtime.MutableValue.class);

            if (method.isPresentAnnotationOfParam(i, Runtime.GetLocals.class)) {
                if (!writeOpcode)
                    return null;

                immutable = false;
                writePushLocal();
            } else if (argType == Environment.class) {
                if (immutable) {
                    arguments[i] = compiler.getEnvironment();
                } else {
                    if (!writeOpcode)
                        return null;
                    writePushEnv();
                }
            } else if (argType == TraceInfo.class) {
                if (immutable) {
                    arguments[i] = function.toTraceInfo(compiler.getContext());
                } else {
                    if (!writeOpcode)
                        return null;

                    writePushTraceInfo(function);
                }
            } else {
                if (argType == Memory[].class) {
                    Memory[] args = new Memory[function.getParameters().size() - j];
                    boolean arrCreated = false;
                    for (int t = 0; t < function.getParameters().size() - j; t++) {
                        ExprStmtToken next = iterator.next();
                        if (immutable)
                            args[t] = writeExpression(next, true, true, false);

                        if (args[t] == null) {
                            if (!writeOpcode)
                                return null;

                            if (!arrCreated) {
                                if (immutable) {
                                    for (int n = 0; n < i /*j*/; n++) {
                                        if (arguments[n] instanceof TraceInfo) {
                                            writePushTraceInfo(function);
                                        } else if (arguments[n] instanceof Environment) {
                                            writePushEnv();
                                        } else {
                                            writePushMemory((Memory) arguments[n]);
                                            writePop(types[n], true, true);
                                            arguments[t] = null;
                                        }
                                    }
                                }

                                // create new array
                                writePushSmallInt(args.length);
                                code.add(new TypeInsnNode(ANEWARRAY, Type.getInternalName(Memory.class)));
                                stackPop();
                                stackPush(Memory.Type.REFERENCE);
                                arrCreated = true;
                            }
                            writePushDup();
                            writePushSmallInt(t);
                            writeArgumentExpression(next, fetchByReference, null, writeOpcode);

                            int sourceRuntimeEval=stackPeek().runtimeEvaluationId;
                            String sourceClosure=stackPeek().runtimeClosureInternalName;
                            //if (!isRef)  BUGFIX - array is memory[] and so we need to convert value to memory
                            writePopBoxing(!isRef);
                            if (fetchByReference)
                                writeSysStaticCall(PhpCallFrame.class,"referenceNativeArgument",Memory.class,Memory.class);
                            else if (borrowedNativeInput && runtimePacket == null)
                                writeSysStaticCall(PhpCallFrame.class,nativeBorrow,Memory.class,Memory.class);

                            if (borrowedNativeInput && runtimePacket != null) {
                                sendRuntimeNativeBorrow(runtimePacket,j+t,sourceRuntimeEval,runtimeResultEval,nativeBorrow.equals("borrowNativeFastArgument"),true);
                                recordRuntimeHandler(method,next,j+t,runtimeResultEval,sourceRuntimeEval,sourceClosure);
                            } else sendRuntimeNative(runtimePacket,j+t,sourceRuntimeEval);
                            code.add(new InsnNode(AASTORE));
                            stackPop();
                            stackPop();
                            stackPop();

                            immutable = false;
                        }
                    }

                    if (!immutable && !arrCreated) {
                        code.add(new InsnNode(ACONST_NULL));
                        stackPush(Memory.Type.REFERENCE);
                    }

                    arguments[i/*j*/] = MemoryUtils.valueOf(args);
                } else {
                    ExprStmtToken next = iterator.next();
                    if (!immutable && !init) {
                        init = true;
                        for (int k = 0; k < i/*j*/; k++) {
                            if (arguments[k] != null) {
                                if (arguments[k] instanceof TraceInfo) {
                                    writePushTraceInfo(function);
                                } else if (arguments[k] instanceof Environment) {
                                    writePushEnv();
                                } else {
                                    writePushMemory((Memory) arguments[k]);
                                    writePop(types[k], true, isRef);
                                    arguments[k] = null;
                                }
                            }
                        }
                    }

                    if (immutable)
                        arguments[i/*j*/] = writeExpression(next, true, true, false);

                    if (arguments[i/*j*/] == null) {
                        if (!writeOpcode)
                            return null;

                        if (!init) {
                            for (int n = 0; n < i/*j*/; n++) {
                                if (arguments[n] instanceof TraceInfo) {
                                    writePushTraceInfo(function);
                                } else if (arguments[n] instanceof Environment) {
                                    writePushEnv();
                                } else {
                                    writePushMemory((Memory) arguments[n]);
                                    writePop(types[n], true, false);
                                }
                                arguments[n] = null;
                            }

                            init = true;
                        }

                        boolean runtimeScalarLiteral=false;
                        if (isRef)
                            writeArgumentExpression(next, fetchByReference, null, writeOpcode);
                        else {
                            Memory tmp = writeExpression(next, true, true, true);
                            if (tmp != null) {
                                if(runtimePacket!=null&&argType==int.class&&tmp instanceof LongMemory) {
                                    writePushConstLong(tmp.toLong());writeSysStaticCall(LongMemory.class,"valueOf",Memory.class,long.class);runtimeScalarLiteral=true;
                                }else if(runtimePacket!=null&&argType==String.class&&tmp.getClass()==StringMemory.class) {
                                    writePushConstString(tmp.toString());writeSysStaticCall(StringMemory.class,"valueOf",Memory.class,String.class);runtimeScalarLiteral=true;
                                }else writePushMemory(tmp);
                            }
                        }

                        int sourceRuntimeEval=stackPeek().runtimeEvaluationId;
                        String sourceClosure=stackPeek().runtimeClosureInternalName;
                        if (fetchByReference || borrowedNativeInput) {
                            writePopBoxing(false);
                            if (fetchByReference) writeSysStaticCall(PhpCallFrame.class,"referenceNativeArgument",Memory.class,Memory.class);
                            else if (runtimePacket == null && !(directEntry != null && directEntry.liveArrayVariables()
                                    && next.isSingle() && next.getSingle() instanceof VariableExprToken))
                                writeSysStaticCall(PhpCallFrame.class,nativeBorrow,Memory.class,Memory.class);
                        }
                        if (runtimePacket != null) {
                            writePopBoxing(false);
                            if (fetchByReference) sendRuntimeNative(runtimePacket,j,sourceRuntimeEval);
                            else {
                                LocalVariable directCv = directEntry != null && directEntry.liveArrayVariables() && isRef
                                        && next.isSingle() && next.getSingle() instanceof VariableExprToken variable
                                        ? nativeDirectCvLocal(variable) : null;
                                if (directCv != null) sendRuntimeNativeDirectCv(runtimePacket, j, directCv, runtimeResultEval);
                                else sendRuntimeNativeBorrow(runtimePacket,j,sourceRuntimeEval,runtimeResultEval,nativeBorrow.equals("borrowNativeFastArgument"),isRef);
                                recordRuntimeHandler(method,next,j,runtimeResultEval,sourceRuntimeEval,sourceClosure);
                            }
                        }
                        writePop(argType, true, isMutable && !isRef);
                        if(runtimeScalarLiteral&&j<function.getParameters().size()) {
                            AbstractInsnNode last=code.getLast();
                            if(argType==int.class&&last.getOpcode()==L2I&&last.getPrevious() instanceof MethodInsnNode)
                                this.method.ownership().runtimeNativeScalarArgument(runtimeResultEval,j,(MethodInsnNode)last.getPrevious(),last);
                            else if(argType==String.class&&last instanceof MethodInsnNode)
                                this.method.ownership().runtimeNativeScalarArgument(runtimeResultEval,j,(MethodInsnNode)last,last);
                        }
                        if (!isMutable && !isRef && stackPeek().type.isReference()) {
                            writeSysDynamicCall(Memory.class, "toValue", Memory.class);
                            if (runtimePacket != null) this.method.ownership().runtimeNativeConversion(runtimeResultEval,(MethodInsnNode)code.getLast());
                        }

                        immutable = false;
                    }
                    j++;
                }
            }
        }

        if (immutable) {
            if (!returnValue)
                return null;

            Object[] typedArguments = new Object[arguments.length];
            for (int i = 0; i < arguments.length; i++) {
                if (method.parameterTypes[i] != Memory.class && arguments[i] instanceof Memory)
                    typedArguments[i] = method.converters[i].run((Memory) arguments[i]);
                    //MemoryUtils.toValue((Memory)arguments[i], types[i]);
                else
                    typedArguments[i] = arguments[i];
            }
            try {
                Object value = method.method.invoke(null, typedArguments);
                return MemoryUtils.valueOf(value);
            } catch (IllegalAccessException e) {
                throw new RuntimeException(e);
            } catch (InvocationTargetException e) {
                throw new RuntimeException(e.getCause());
            }
        }

        if (!writeOpcode)
            return null;

        this.method.entity.setImmutable(false);

        writeLineNumber(function);
        java.lang.reflect.Method member=php.runtime.invoke.PhpNativeResultUse.select(method.method,semanticDiscarded(function,returnValue) && php8CallFrame());
        if (runtimePacket == null) writePushStaticCall(member);
        else writeRuntimeNativeBody(method.method,member,runtimePacket,runtimeResultEval);
        if (runtimePacket != null) {
            if (method.resultType == void.class) writePushNull();
            finishRuntimeNative(runtimePacket,runtimeResultEval);
            if (statistic != null) statistic.returnType=StackItem.Type.REFERENCE;
            if (!returnValue) writePopAll(1);
        }
        if (returnValue) {
            if (method.resultType == void.class && runtimePacket == null) writePushNull();
            setStackPeekAsImmutable();
        }
        return null;
    }

    public void writePushParameters(Collection<Memory> memories) {
        writePushSmallInt(memories.size());
        code.add(new TypeInsnNode(ANEWARRAY, Type.getInternalName(Memory.class)));
        stackPop();
        stackPush(Memory.Type.REFERENCE);

        int i = 0;
        for (Memory param : memories) {
            writePushDup();
            writePushSmallInt(i);

            writePushMemory(param);
            writePopBoxing(true, false);

            code.add(new InsnNode(AASTORE));
            stackPop();
            stackPop();
            stackPop();
            i++;
        }
    }

    public void writePushParameters(Collection<ExprStmtToken> parameters, Memory... additional) {
        writePushParameters(parameters, true, additional);
    }

    public void writePushParameters(Collection<ExprStmtToken> parameters, boolean useConstants, Memory... additional) {
        if (parameters.isEmpty()) {
            code.add(new InsnNode(ACONST_NULL));
            stackPush(Memory.Type.REFERENCE);
            return;
        }

        if (useConstants && additional == null || additional.length == 0) {
            List<Memory> constantParameters = new ArrayList<Memory>();

            for (ExprStmtToken param : parameters) {
                Memory paramMemory = writeExpression(param, true, true, false);
                if (paramMemory == null || paramMemory.isArray()) { // skip arrays.
                    break;
                }

                constantParameters.add(paramMemory);
            }

            if (constantParameters.size() == parameters.size()) {
                writePushConstantMemoryArray(constantParameters);
                return;
            }
        }

        writePushSmallInt(parameters.size() + (additional == null ? 0 : additional.length));
        code.add(new TypeInsnNode(ANEWARRAY, Type.getInternalName(Memory.class)));
        stackPop();
        stackPush(Memory.Type.REFERENCE);

        int i = 0;
        for (ExprStmtToken param : parameters) {
            writePushDup();
            writePushSmallInt(i);
            writeExpression(param, true, false);
            writePopBoxing();

            code.add(new InsnNode(AASTORE));
            stackPop();
            stackPop();
            stackPop();
            i++;
        }

        if (additional != null) {
            for (Memory m : additional) {
                writePushDup();
                writePushSmallInt(i);
                writePushMemory(m);
                writePopBoxing();

                code.add(new InsnNode(AASTORE));
                stackPop();
                stackPop();
                stackPop();
                i++;
            }
        }
    }

    private boolean php8CallFrame() {
        return compiler.getModule().getPhpLanguageProfile() == php.runtime.common.PhpLanguageProfile.PHP_8_5;
    }

    private int argumentSourceKind(ExprStmtToken expression) {
        ExprStmtToken asm = expression.getAsmExpr();
        Token last = (asm == null ? expression : asm).getLast();
        if (org.develnext.jphp.core.compiler.common.NullsafeChain.isResult(expression)
                && !(last instanceof CallExprToken)) return PhpCallFrame.VALUE;
        if (last instanceof VariableExprToken || last instanceof org.develnext.jphp.core.tokenizer.token.expr.DollarExprToken
                || last instanceof ArrayGetExprToken || last instanceof ArrayPushExprToken || last instanceof DynamicAccessExprToken
                || last instanceof StaticAccessExprToken && argumentStaticProperty((StaticAccessExprToken)last)
                || last instanceof StaticAccessOperatorExprToken
                    && argumentStaticProperty(((StaticAccessOperatorExprToken)last).getOrigin())) {
            return PhpCallFrame.VARIABLE;
        }
        if (last instanceof ArrayExprToken) return PhpCallFrame.VALUE;
        if (last instanceof CallExprToken) {
            CallExprToken call = (CallExprToken) last;
            if (call.getName() instanceof NameToken) {
                String name = ((NameToken) call.getName()).getName().toLowerCase(Locale.ROOT);
                // PHP compiles these internal calls as temporary-value opcodes, not callable results.
                if (compiler.getScope().findCompileFunction(name,compiler.getModule().getPhpLanguageProfile()) != null && !hasUnpackedArguments(call.getParameters())
                        && nativeTemporaryResult(name, call.getParameters().size()))
                    return PhpCallFrame.VALUE;
            }
            return PhpCallFrame.CALL_RESULT;
        }
        return last instanceof CallOperatorToken || last instanceof NewExprToken
                ? PhpCallFrame.CALL_RESULT : PhpCallFrame.VALUE;
    }

    private boolean hasUnpackedArguments(Collection<ExprStmtToken> parameters) {
        for (ExprStmtToken parameter : parameters) {
            ExprStmtToken asm = parameter.getAsmExpr();
            if ((asm == null ? parameter : asm).getLast() instanceof ArgumentUnpackExprToken) return true;
        }
        return false;
    }

    private boolean nativeTemporaryResult(String name, int count) {
        switch (name) {
            case "strlen": case "is_null": case "is_bool": case "is_long": case "is_int": case "is_integer":
            case "is_float": case "is_double": case "is_string": case "is_array": case "is_object":
            case "is_resource": case "is_scalar": case "boolval": case "floatval": case "doubleval":
            case "strval": case "gettype": return count == 1;
            case "count": case "sizeof": return count == 1 || count == 2;
            default: return false;
        }
    }

    private boolean argumentStaticProperty(StaticAccessExprToken token) {
        return token.isGetStaticField() || token.getFieldExpr() != null && !token.isDynamicConstant();
    }

    private Token argumentPropertyFetch(ExprStmtToken expression) {
        if (org.develnext.jphp.core.compiler.common.NullsafeChain.isResult(expression)) return null;
        ExprStmtToken asm=expression.getAsmExpr();
        Token last=(asm == null ? expression : asm).getLast();
        if (last instanceof StaticAccessOperatorExprToken) last=((StaticAccessOperatorExprToken)last).getOrigin();
        if (last != null && last.getClass() == DynamicAccessExprToken.class) return last;
        if (last != null && last.getClass() == StaticAccessExprToken.class
                && argumentStaticProperty((StaticAccessExprToken)last)) return last;
        if (last != null && (last.getClass() == ArrayGetExprToken.class || last.getClass() == ArrayPushExprToken.class))
            return last;
        return null;
    }

    private Memory writeArgumentExpression(ExprStmtToken argument, boolean reference, LocalVariable frame, boolean writeOpcode) {
        Token target=php8CallFrame() && reference ? argumentPropertyFetch(argument) : null;
        if (target == null) return writeExpression(argument,true,false,writeOpcode);
        Map<Token,LocalVariable> previous=new IdentityHashMap<>(referenceArgumentFetches);
        Set<Token> previousArrayProperties=Collections.newSetFromMap(new IdentityHashMap<>());
        previousArrayProperties.addAll(arrayArgumentPropertyFetches);
        referenceArgumentFetches.put(target,frame);
        if (target instanceof DynamicAccessExprToken || target instanceof ArrayGetExprToken || target instanceof ArrayPushExprToken) {
            ExprStmtToken asm=argument.getAsmExpr();
            List<Token> tokens=(asm == null ? argument : asm).getTokens();
            // FETCH_OBJ_W also applies to receiver properties. Do not export those
            // intermediate cells: an uninitialized receiver stays uninitialized.
            for (int i=tokens.size()-2;i>=0;i--) {
                Token token=tokens.get(i);
                if (token.getClass() == DynamicAccessExprToken.class || token.getClass() == ArrayGetExprToken.class
                        || token.getClass() == ArrayPushExprToken.class) referenceArgumentFetches.put(token,frame);
                else {
                    if (token instanceof StaticAccessOperatorExprToken) token=((StaticAccessOperatorExprToken)token).getOrigin();
                    if (token.getClass() == StaticAccessExprToken.class && argumentStaticProperty((StaticAccessExprToken)token))
                        referenceArgumentFetches.put(token,frame);
                    // Calls, arithmetic, conditionals and key expressions are independent reads.
                    break;
                }
            }
        }
        ExprStmtToken argumentAsm=argument.getAsmExpr();
        List<Token> argumentTokens=(argumentAsm == null ? argument : argumentAsm).getTokens();
        for (int i=0;i<argumentTokens.size()-1;i++) {
            Token token=argumentTokens.get(i),next=argumentTokens.get(i+1);
            if (token instanceof StaticAccessOperatorExprToken) token=((StaticAccessOperatorExprToken)token).getOrigin();
            if (referenceArgumentFetches.containsKey(token)
                    && (next.getClass() == ArrayGetExprToken.class || next.getClass() == ArrayPushExprToken.class))
                arrayArgumentPropertyFetches.add(token);
        }
        try {return writeExpression(argument,true,false,writeOpcode);}
        finally {
            referenceArgumentFetches.clear();referenceArgumentFetches.putAll(previous);
            arrayArgumentPropertyFetches.clear();arrayArgumentPropertyFetches.addAll(previousArrayProperties);
        }
    }

    public boolean isArgumentArrayProperty(Token token) {return arrayArgumentPropertyFetches.contains(token);}

    public boolean writeArgumentReferenceMode(Token token) {
        if (!referenceArgumentFetches.containsKey(token)) return false;
        LocalVariable frame=referenceArgumentFetches.get(token);
        if (frame == null) writePushConstBoolean(true);
        else {
            writeVarLoad(frame);
            writeSysDynamicCall(PhpCallFrame.class,"nextArgumentIsReference",boolean.class);
        }
        return true;
    }

    private boolean hasBoundArguments(Collection<ExprStmtToken> parameters, List<CallArgument> arguments) {
        if (arguments != null) for (CallArgument argument : arguments)
            if (argument.getKind() != CallArgument.Kind.POSITIONAL) return true;
        return hasUnpackedArguments(parameters);
    }

    public void writeFrameArguments(Collection<ExprStmtToken> parameters, List<CallArgument> arguments,
                                    boolean returnValue) {
        writeFrameArguments(parameters,arguments,returnValue,null);
    }
    private void writeFrameArguments(Collection<ExprStmtToken> parameters,List<CallArgument> arguments,
                                     boolean returnValue,Token call) {
        // Capture semantic use before nested argument expressions change their own AST scope.
        boolean discarded=semanticDiscarded(call,returnValue);
        if (!php8CallFrame() || !hasBoundArguments(parameters, arguments)) {
            writeFrameArguments(parameters,returnValue,discarded);
            return;
        }
        writeProtectedFrameArguments(parameters,arguments,returnValue,discarded);
    }

    /** Construction must retire its actual pre-argument shell if any argument expression fails. */
    public void writeConstructorFrameArguments(Collection<ExprStmtToken> parameters, List<CallArgument> arguments,
                                               boolean returnValue) {
        writeProtectedFrameArguments(parameters, arguments, returnValue);
    }

    private ExprStmtToken unpackedArgumentExpression(ExprStmtToken argument) {
        ExprStmtToken asm = argument.getAsmExpr();
        List<Token> tokens = (asm == null ? argument : asm).getTokens();
        if (tokens.isEmpty() || !(tokens.get(tokens.size() - 1) instanceof ArgumentUnpackExprToken))
            return argument;
        // The descriptor supplies SEND_UNPACK. Do not emit the old VariadicMemory wrapper opcode.
        ExprStmtToken value = new ExprStmtToken(null, null, new ArrayList<>(tokens.subList(0, tokens.size() - 1)));
        value.setAsmExpr(value);
        return value;
    }

    private void writeProtectedFrameArguments(Collection<ExprStmtToken> parameters,List<CallArgument> arguments,
                                              boolean returnValue) {
        writeProtectedFrameArguments(parameters,arguments,returnValue,!returnValue);
    }
    private void writeProtectedFrameArguments(Collection<ExprStmtToken> parameters,List<CallArgument> arguments,
                                              boolean returnValue,boolean discarded) {
        if (hasBoundArguments(parameters, arguments))
            method.ownership().runtimeUnsupported("named-or-unpacked-call-without-destination-custody");
        if (arguments != null && arguments.size() != parameters.size())
            throw new IllegalArgumentException("Call argument metadata differs from expression count");
        int id = method.nextStatementIndex(PhpCallFrame.class);
        LocalVariable frame = method.addLocalVariable("~boundArgFrame" + id, null, PhpCallFrame.class);
        LocalVariable result = method.addLocalVariable("~boundArgResult" + id, null, Memory.class);
        LocalVariable failure = method.addLocalVariable("~boundArgFailure" + id, null, Throwable.class);
        code.add(new VarInsnNode(ASTORE, frame.index)); stackPop();
        LabelNode start = new LabelNode(), end = new LabelNode(), handler = new LabelNode(), joined = new LabelNode();
        // Register before nested argument emission: inner cleanup handlers must retain first priority.
        method.node.tryCatchBlocks.add(0, new TryCatchBlockNode(start, end, handler, "java/lang/Throwable"));
        code.add(start);
        int index = 0;
        for (ExprStmtToken parameter : parameters) {
            CallArgument argument = arguments == null ? null : arguments.get(index++);
            ExprStmtToken asm = parameter.getAsmExpr();
            boolean unpack = argument == null
                    ? (asm == null ? parameter : asm).getLast() instanceof ArgumentUnpackExprToken
                    : argument.getKind() == CallArgument.Kind.UNPACK;
            if (unpack) {
                // A spread operand has no single pending destination and must use an ordinary VALUE fetch.
                writeVarLoad(frame);
                writeExpression(unpackedArgumentExpression(parameter), true, false);
                writePopBoxing();
                writeSysDynamicCall(PhpCallFrame.class, "sendSpread", void.class, Memory.class);
            } else {
                writeVarLoad(frame);
                if (argument != null && argument.getKind() == CallArgument.Kind.NAMED) {
                    writePushConstString(argument.getName());
                    writeSysDynamicCall(PhpCallFrame.class, "prepareNamed", void.class, String.class);
                } else writeSysDynamicCall(PhpCallFrame.class, "preparePositional", void.class);
                writeVarLoad(frame);
                // The exact prepared destination controls FETCH_*_FUNC_ARG, including reordered references.
                writeArgumentExpression(parameter, true, frame, true);
                writePopBoxing();
                writePushConstInt(argumentSourceKind(parameter));
                writeSysDynamicCall(PhpCallFrame.class, "send", void.class, Memory.class, Integer.TYPE);
            }
        }
        writeVarLoad(frame);
        writeSysDynamicCall(PhpCallFrame.class, discarded ? "invokeDiscarded" : "invoke", Memory.class);
        code.add(new VarInsnNode(ASTORE, result.index)); stackPop();
        code.add(end);
        writeVarLoad(frame);
        writeSysDynamicCall(PhpCallFrame.class, "close", void.class);
        code.add(new JumpInsnNode(GOTO, joined));
        code.add(handler);
        code.add(new VarInsnNode(ASTORE, failure.index));
        code.add(new VarInsnNode(ALOAD, frame.index));
        code.add(new VarInsnNode(ALOAD, failure.index));
        code.add(new MethodInsnNode(INVOKEVIRTUAL, Type.getInternalName(PhpCallFrame.class), "fail",
                "(Ljava/lang/Throwable;)Ljava/lang/Throwable;", false));
        code.add(new InsnNode(ATHROW));
        code.add(joined);
        code.add(new VarInsnNode(ALOAD, result.index)); stackPush(Memory.Type.REFERENCE);
        if (!returnValue) writePopAll(1);
    }

    public void writeFrameArguments(Collection<ExprStmtToken> parameters,boolean returnValue) {
        writeFrameArguments(parameters,returnValue,!returnValue);
    }
    private void writeFrameArguments(Collection<ExprStmtToken> parameters,boolean returnValue,boolean discarded) {
        int ownershipCall = stackPeek().ownershipCallId;
        LocalVariable frame=null;
        for (ExprStmtToken parameter : parameters) if (argumentPropertyFetch(parameter) != null) {
            frame=method.addLocalVariable("~referenceArgFrame"+method.nextStatementIndex(PhpCallFrame.class),makeLabel(),PhpCallFrame.class);
            writePushDup();
            writeVarStore(frame,false,false);
            break;
        }
        for (ExprStmtToken parameter : parameters) {
            writePushDup();
            writeArgumentExpression(parameter, true, frame, true);
            writePopBoxing();
            int argumentEval = stackPeek().ownershipEvalId; stackPeek().ownershipEvalId = -1;
            writePushConstInt(argumentSourceKind(parameter));
            if (ownershipCall >= 0) {
                method.ownership().sendArgument(code, argumentEval); stackPop(); stackPop(); stackPop();
            } else writeSysDynamicCall(PhpCallFrame.class, "send", void.class, Memory.class, Integer.TYPE);
        }
        if (ownershipCall >= 0) {
            method.ownership().invokeToEval(code, ownershipCall, discarded); stackPop(); stackPush(Memory.Type.REFERENCE); stackPeek().ownershipEvalId = ownershipCall;
        } else writeSysDynamicCall(PhpCallFrame.class, discarded ? "invokeDiscarded" : "invoke", Memory.class);
        if (!returnValue) writePopAll(1);
    }

    private void writeAcquiredOrCalledFrame(CallExprToken call,boolean returnValue) {
        if (call.isFirstClassCallable()) {
            writeSysDynamicCall(PhpCallFrame.class,"capture",Memory.class);
            if (!returnValue) writePopAll(1);
        } else writeFrameArguments(call.getParameters(),call.getArguments(),returnValue,call);
    }

    private void writeCallableFrame(Collection<ExprStmtToken> parameters, Token call, boolean returnValue) {
        writeCallableFrame(parameters,call,returnValue,false);
    }

    private void writeCallableFrame(Collection<ExprStmtToken> parameters, Token call, boolean returnValue,boolean acquisition) {
        if (!acquisition && parameters.isEmpty() && writeRuntimeCallableCv(call,returnValue)) return;
        writePopBoxing();
        writePushEnv();
        writePushTraceInfo(call);
        if (acquisition) writeSysStaticCall(PhpCallFrame.class, "acquireCallable", PhpCallFrame.class,
                Memory.class, Environment.class, TraceInfo.class);
        else {
            writePushConstInt(parameters.size());
            writeSysStaticCall(PhpCallFrame.class, "callable", PhpCallFrame.class,
                    Memory.class, Environment.class, TraceInfo.class, Integer.TYPE);
        }
        if (acquisition) {
            writeSysDynamicCall(PhpCallFrame.class,"capture",Memory.class);
            if (!returnValue) writePopAll(1);
        } else writeFrameArguments(parameters,call instanceof CallExprToken
                ? ((CallExprToken)call).getArguments() : ((CallOperatorToken)call).getArguments(),returnValue,call);
    }

    /** Zero-argument lexical CALL consumes a compiler-witnessed, physically published CV. */
    private boolean writeRuntimeCallableCv(Token call,boolean returnValue) {
        if (!method.ownership().runtimeEnabled() || !(call instanceof CallExprToken)
                || !(((CallExprToken)call).getName() instanceof VariableExprToken)) return false;
        VariableExprToken token=(VariableExprToken)((CallExprToken)call).getName();
        LocalVariable local=method.getLocalVariable(token.getName());
        if (local == null) return false;
        String closure=local.getRuntimeClosureInternalName();int producer=local.getRuntimeClosureProducerId();
        if (closure == null || producer < 0 || stackPeek().runtimeClosureProducerId != producer
                || !closure.equals(stackPeek().runtimeClosureInternalName)) return false;
        writePopBoxing();
        int id=method.ownership().reserveRuntimeEval();method.ownership().runtimeProducer(id,"CALL_RESULT");
        LocalVariable operand=method.addLocalVariable("~runtimeCallable"+id,null,Memory.class);
        LocalVariable trace=method.addLocalVariable("~runtimeCallableTrace"+id,null,TraceInfo.class);
        LocalVariable packet=method.addLocalVariable("~runtimeCallablePacket"+id,null,Object.class);
        code.add(new VarInsnNode(ASTORE,operand.index));stackPop();
        writePushTraceInfo(call);code.add(new VarInsnNode(ASTORE,trace.index));stackPop();
        LabelNode start=new LabelNode(),fallback=new LabelNode(),join=new LabelNode();
        code.add(new VarInsnNode(ALOAD,method.ownership().runtimeFrameIndex()));code.add(new JumpInsnNode(IFNULL,fallback));code.add(start);
        code.add(new VarInsnNode(ALOAD,method.ownership().runtimeFrameIndex()));code.add(new LdcInsnNode(id));
        code.add(new VarInsnNode(ALOAD,operand.index));code.add(new LdcInsnNode(local.index));code.add(new LdcInsnNode(-1));
        code.add(new VarInsnNode(ALOAD,trace.index));code.add(new LdcInsnNode(0));
        MethodInsnNode factory=new MethodInsnNode(INVOKEVIRTUAL,"php/runtime/PhpRuntimeOwnership$Frame","callable",
                "(ILphp/runtime/Memory;IILphp/runtime/env/TraceInfo;I)Lphp/runtime/PhpRuntimeOwnership$CallPacket;",false);code.add(factory);
        code.add(new VarInsnNode(ASTORE,packet.index));code.add(new VarInsnNode(ALOAD,packet.index));code.add(new LdcInsnNode(id));
        MethodInsnNode invoke=new MethodInsnNode(INVOKEVIRTUAL,"php/runtime/PhpRuntimeOwnership$CallPacket",semanticDiscarded(call,returnValue)?"invokeToEvalDiscarded":"invokeToEval","(I)Lphp/runtime/Memory;",false);code.add(invoke);
        code.add(new JumpInsnNode(GOTO,join));code.add(fallback);
        code.add(new VarInsnNode(ALOAD,operand.index));code.add(new VarInsnNode(ALOAD,method.getLocalVariable("~env").index));
        code.add(new VarInsnNode(ALOAD,trace.index));code.add(new InsnNode(ICONST_0));
        code.add(new MethodInsnNode(INVOKESTATIC,"php/runtime/invoke/PhpCallFrame","callable",
                "(Lphp/runtime/Memory;Lphp/runtime/env/Environment;Lphp/runtime/env/TraceInfo;I)Lphp/runtime/invoke/PhpCallFrame;",false));
        code.add(new MethodInsnNode(INVOKEVIRTUAL,"php/runtime/invoke/PhpCallFrame",semanticDiscarded(call,returnValue)?"invokeDiscarded":"invoke","()Lphp/runtime/Memory;",false));code.add(join);
        method.ownership().runtimeFallback(fallback,join);
        method.ownership().runtimeCallSite(id,local.index,-1,producer,closure,0,factory,invoke,start,fallback,join);
        stackPush(Memory.Type.REFERENCE);stackPeek().runtimeEvaluationId=id;
        if (!returnValue) writePopAll(1);return true;
    }

    Memory writePushParentDynamicMethod(CallExprToken function, boolean returnValue, boolean writeOpcode,
                                        PushCallStatistic statistic) {
        if (!writeOpcode)
            return null;

        StaticAccessExprToken dynamic = (StaticAccessExprToken) function.getName();
        writeLineNumber(function);

        writePushThis();
        if (dynamic.getField() != null) {
            if (dynamic.getField() instanceof NameToken) {
                String name = ((NameToken) dynamic.getField()).getName();
                writePushConstString(name);
                writePushConstString(name.toLowerCase());
            } else {
                writePush(dynamic.getField(), true, false);
                writePopString();
                writePushDupLowerCase();
            }
        } else {
            writeExpression(dynamic.getFieldExpr(), true, false);
            writePopString();
            writePushDupLowerCase();
        }

        writePushEnv();
        writePushTraceInfo(dynamic);
        writePushParameters(function.getParameters());

        writeSysStaticCall(
                ObjectInvokeHelper.class, "invokeParentMethod",
                Memory.class,
                Memory.class, String.class, String.class, Environment.class, TraceInfo.class, Memory[].class
        );

        if (!returnValue)
            writePopAll(1);

        if (statistic != null)
            statistic.returnType = StackItem.Type.REFERENCE;

        return null;
    }

    Memory writePushDynamicMethod(CallExprToken function, boolean returnValue, boolean writeOpcode,
                                  PushCallStatistic statistic) {
        if (!writeOpcode)
            return null;

        DynamicAccessExprToken access = (DynamicAccessExprToken) function.getName();
        if (access instanceof DynamicAccessAssignExprToken)
            unexpectedToken(access);

        writeLineNumber(function);
        if (php8CallFrame()) {
            StackItem receiver = stackPop();
            writePush(receiver);
            writePopBoxing();
            if (!(receiver.getToken() instanceof VariableExprToken)) writePopImmutable();
            writePhp8MethodName(access.getField(), access.getFieldExpr());
            writePushEnv();
            writePhp8MethodTraceInfo(access.getField(), access.getFieldExpr(), access);
            writePushConstInt(function.getParameters().size());
            writeSysStaticCall(PhpCallFrame.class, "method", PhpCallFrame.class,
                    Memory.class, Memory.class, Environment.class, TraceInfo.class, Integer.TYPE);
            writeAcquiredOrCalledFrame(function, returnValue);
            if (statistic != null) statistic.returnType = StackItem.Type.REFERENCE;
            return null;
        }
        writeDynamicAccessPrepare(access, true);
        writePushParameters(function.getParameters());

        writeSysStaticCall(
                ObjectInvokeHelper.class, "invokeMethod",
                Memory.class,
                Memory.class, String.class, String.class, Environment.class, TraceInfo.class, Memory[].class
        );
        if (!returnValue)
            writePopAll(1);

        if (statistic != null)
            statistic.returnType = StackItem.Type.REFERENCE;

        return null;
    }

    /** PHP method-call errors point at the method name, including a braced name expression. */
    private void writePhp8MethodTraceInfo(ValueExprToken field, ExprStmtToken fieldExpression, Token fallback) {
        writePushTraceInfo(field != null ? field : fieldExpression != null ? fieldExpression : fallback);
    }

    private void writePhp8MethodName(ValueExprToken field, ExprStmtToken fieldExpression) {
        if (field instanceof NameToken) writePushString(((NameToken) field).getName());
        else if (field != null) writePush(field, true, false);
        else writeExpression(fieldExpression, true, false);
        writePopBoxing();
    }

    private Memory writePhp8StaticMethod(CallExprToken function, boolean returnValue, PushCallStatistic statistic) {
        StaticAccessExprToken access = function.getName() instanceof StaticAccessOperatorExprToken
                ? ((StaticAccessOperatorExprToken) function.getName()).getOrigin() : (StaticAccessExprToken) function.getName();
        ValueExprToken clazz = access.getClazz();
        boolean constantClass = clazz instanceof NameToken && !(clazz instanceof ParentExprToken);
        boolean forwarding = clazz instanceof SelfExprToken || clazz instanceof ParentExprToken;
        LocalVariable capturedClass = null;
        if (clazz == null) {
            writePush(stackPop());
            writePopBoxing();
            capturedClass = method.addLocalVariable("~acquiredClass" + method.nextStatementIndex(PhpCallFrame.class), makeLabel(), Memory.class);
            writeVarStore(capturedClass, false, false);
        }
        writePushEnv();
        writePhp8MethodTraceInfo(access.getField(), access.getFieldExpr(), access);
        if (constantClass) writePushConstString(((NameToken) clazz).getName());
        else if (clazz instanceof ParentExprToken || clazz instanceof StaticExprToken) {
            writePushEnv();
            writePushTraceInfo(access);
            writeSysStaticCall(php.runtime.invoke.PhpMethodResolver.class,
                    clazz instanceof ParentExprToken ? "parentClass" : "staticClass", ClassEntity.class,
                    Environment.class, TraceInfo.class);
        } else {
            if (clazz instanceof SelfExprToken) {
                writePushEnv();
                writePushTraceInfo(access);
                writeSysDynamicCall(Environment.class, "__getSelf", String.class, TraceInfo.class);
            } else if (clazz instanceof StaticExprToken) writePushStatic();
            else if (capturedClass != null) writeVarLoad(capturedClass);
            else writePush(clazz, true, false);
            writePopBoxing();
            writePushEnv();
            writePushTraceInfo(access);
            writeSysStaticCall(php.runtime.invoke.PhpMethodResolver.class, "resolveClass", ClassEntity.class,
                    Memory.class, Environment.class, TraceInfo.class);
        }
        writePhp8MethodName(access.getField(), access.getFieldExpr());
        if (method.clazz.statement.isTrait()) {
            writePushConstNull();
            writePushConstInt(0);
        } else {
            writeGetStatic("$CALL_METH_CACHE", MethodCallCache.class);
            writePushConstInt(method.clazz.getAndIncCallMethCount());
        }
        writePushConstBoolean(forwarding);
        writePushConstInt(function.getParameters().size());
        writeSysStaticCall(PhpCallFrame.class, constantClass ? "staticMethod" : "staticResolved", PhpCallFrame.class,
                Environment.class, TraceInfo.class, constantClass ? String.class : ClassEntity.class, Memory.class,
                MethodCallCache.class, Integer.TYPE, Boolean.TYPE, Integer.TYPE);
        writeAcquiredOrCalledFrame(function, returnValue);
        if (statistic != null) statistic.returnType = StackItem.Type.REFERENCE;
        return null;
    }

    boolean writePushFastStaticMethod(CallExprToken function, boolean returnValue) {
        StaticAccessExprToken access = (StaticAccessExprToken) function.getName();

        CompileClass compileClass = compiler.getEnvironment().scope.findCompileClass(access.getClazz().getWord());
        if (compileClass == null)
            return false;

        ClassEntity classEntity = compiler.getEnvironment().fetchClass(compileClass.getNativeClass());
        MethodEntity methodEntity = classEntity.findMethod(access.getField().getWord().toLowerCase());
        if (methodEntity != null && methodEntity.getNativeMethod() != null
                && methodEntity.getNativeMethod().isAnnotationPresent(Runtime.FastMethod.class)) {
            int cnt = methodEntity.getRequiredParamCount();

            if (cnt > function.getParameters().size()) {
                writeWarning(function, Messages.ERR_EXPECT_EXACTLY_PARAMS.fetch(
                        methodEntity.getClazzName() + "::" + methodEntity.getName(),
                        cnt, function.getParameters().size()
                ));
                if (returnValue)
                    writePushNull();
                return true;
            }

            List<Memory> additional = new ArrayList<Memory>();
            for (ParameterEntity param : methodEntity.getParameters()) {
                if (param.getDefaultValue() != null)
                    additional.add(param.getDefaultValue());
                else if (!additional.isEmpty())
                    throw new IllegalStateException("Arguments with default values must be located at the end");
            }

            writePushEnv();
            writePushParameters(function.getParameters(), additional.toArray(new Memory[0]));
            writeSysStaticCall(
                    compileClass.getNativeClass(),
                    methodEntity.getName(),
                    Memory.class,
                    Environment.class, Memory[].class
            );
            if (!returnValue)
                writePopAll(1);
            return true;
        }
        return false;
    }

    Memory writePushStaticMethod(CallExprToken function, boolean returnValue, boolean writeOpcode,
                                 PushCallStatistic statistic) {
        StaticAccessExprToken access = (StaticAccessExprToken) function.getName();

        if (!writeOpcode)
            return null;

        writeLineNumber(function);

        if (php8CallFrame()) return writePhp8StaticMethod(function, returnValue, statistic);
        if (writePushFastStaticMethod(function, returnValue))
            return null;

        writePushEnv();
        writePushTraceInfo(function.getName());
        String methodName = null;

        ValueExprToken clazz = access.getClazz();
        boolean forwarding = compiler.getModule().getPhpLanguageProfile() == php.runtime.common.PhpLanguageProfile.PHP_8_5
                && (clazz instanceof SelfExprToken || clazz instanceof ParentExprToken);
        if ((clazz instanceof NameToken || (clazz instanceof SelfExprToken && !method.clazz.entity.isTrait()
                && !(method.clazz.isClosure() && compiler.getModule().getPhpLanguageProfile() == php.runtime.common.PhpLanguageProfile.PHP_8_5)))
                && access.getField() instanceof NameToken) {
            String className;
            if (clazz instanceof SelfExprToken)
                className = getMacros(clazz).toString();
            else
                className = ((NameToken) clazz).getName();

            writePushString(className.toLowerCase());

            methodName = ((NameToken) access.getField()).getName();
            writePushString(methodName.toLowerCase());

            writePushString(className);
            writePushString(methodName);

            writePushParameters(function.getParameters());

            if (method.clazz.statement.isTrait()) {
                writePushConstNull();
                writePushConstInt(0);
            } else {
                int cacheIndex = method.clazz.getAndIncCallMethCount();
                writeGetStatic("$CALL_METH_CACHE", MethodCallCache.class);
                writePushConstInt(cacheIndex);
            }

            writePushConstBoolean(forwarding);
            writeSysStaticCall(
                    InvokeHelper.class, "callStatic", Memory.class,
                    Environment.class, TraceInfo.class,
                    String.class, String.class, // lower sign name
                    String.class, String.class, // origin names
                    Memory[].class,
                    MethodCallCache.class, Integer.TYPE, Boolean.TYPE
            );
        } else {
            if (clazz instanceof NameToken) {
                writePushString(((NameToken) clazz).getName());
                writePushDupLowerCase();
            } else if (clazz instanceof SelfExprToken) {
                writePushSelf(true);
            } else if (clazz instanceof StaticExprToken) {
                writePushStatic();
                writePushDupLowerCase();
            } else {
                writePush(clazz, true, false);
                writePopString();
                writePushDupLowerCase();
            }

            if (access.getField() != null) {
                ValueExprToken field = access.getField();
                if (field instanceof NameToken) {
                    writePushString(((NameToken) field).getName());
                    writePushString(((NameToken) field).getName().toLowerCase());
                } else if (field instanceof ClassExprToken) {
                    unexpectedToken(field);
                } else {
                    writePush(access.getField(), true, false);
                    writePopString();
                    writePushDupLowerCase();
                }
            } else {
                writeExpression(access.getFieldExpr(), true, false);
                writePopString();
                writePushDupLowerCase();
            }

            writePushParameters(function.getParameters());

            if (clazz instanceof StaticExprToken || method.clazz.statement.isTrait()
                    || clazz instanceof VariableExprToken) {
                writePushConstNull();
                writePushConstInt(0);
            } else {
                int cacheIndex = method.clazz.getAndIncCallMethCount();
                writeGetStatic("$CALL_METH_CACHE", MethodCallCache.class);
                writePushConstInt(cacheIndex);
            }

            writePushConstBoolean(forwarding);
            writeSysStaticCall(
                    InvokeHelper.class, "callStaticDynamic", Memory.class,
                    Environment.class, TraceInfo.class,
                    String.class, String.class,
                    String.class, String.class,
                    Memory[].class,
                    MethodCallCache.class, Integer.TYPE, Boolean.TYPE
            );
        }
        if (statistic != null)
            statistic.returnType = StackItem.Type.REFERENCE;

        return null;
    }

    public static class PushCallStatistic {
        public StackItem.Type returnType = StackItem.Type.REFERENCE;
    }

    private void writeOwnedNamedFunctionFrame(CallExprToken function, String name, boolean returnValue) {
        method.entity.setImmutable(false);
        int index = method.clazz.getAndIncCallFuncCount();
        writePushEnv(); writePushTraceInfo(function); writePushString(name.toLowerCase()); writePushString(name);
        writeGetStatic("$CALL_FUNC_CACHE", FunctionCallCache.class); writePushConstInt(index); writePushConstInt(function.getParameters().size());
        int callId = method.ownership().functionFrame(code, name, function.getParameters().size());
        for (int i = 0; i < 7; i++) stackPop();
        stackPush(null, StackItem.Type.REFERENCE); stackPeek().ownershipCallId = callId;
        writeFrameArguments(function.getParameters(),returnValue,semanticDiscarded(function,returnValue));
    }

    private Memory writeCompileFunctionWithOwnership(CallExprToken function, CompileFunction compiled, boolean returnValue,
                                                    boolean writeOpcode, PushCallStatistic statistic) {
        if (!writeOpcode || !method.ownership().enabled()) return writePushCompileFunction(function, compiled, returnValue, writeOpcode, statistic);
        Memory constant = writePushCompileFunction(function, compiled, returnValue, false, statistic);
        if (constant != null) return constant;
        int before = method.getStackCount();
        LabelNode legacy = method.ownership().guardActive(code), end = new LabelNode();
        LabelNode activeStart=new LabelNode(),activeEnd=new LabelNode();code.add(activeStart);
        StackItem result;method.ownership().beginRuntimeActiveEmission();
        try {
            writeOwnedNamedFunctionFrame(function, ((NameToken) function.getName()).getName(), returnValue);
            result=returnValue ? stackPop() : null;
        } finally {method.ownership().endRuntimeActiveEmission();}
        code.add(activeEnd);method.ownership().runtimeActiveBranch(activeStart,activeEnd);
        code.add(new JumpInsnNode(GOTO, end)); code.add(legacy); method.ownership().beginPassive();
        try {
            Memory passive = writePushCompileFunction(function, compiled, returnValue, true, statistic);
            if (returnValue) {
                if (passive != null) writePushMemory(passive);writePopBoxing();
                result.runtimeEvaluationId=stackPeek().runtimeEvaluationId;stackPop();
            }
            else writePopAll(method.getStackCount() - before);
        } finally { method.ownership().endPassive(legacy, end); }
        code.add(end); if (result != null) stackPush(result);
        if (statistic != null) statistic.returnType = StackItem.Type.REFERENCE;
        return null;
    }

    private boolean runtimeNamedEmission;
    private Token discardedCallWitness;
    private boolean semanticDiscarded(Token call,boolean physicalReturnValue) {
        return !physicalReturnValue||call!=null&&call==discardedCallWitness;
    }
    private boolean writeRuntimeNamedFunctionFrame(CallExprToken function,String name,boolean returnValue) {
        if(runtimeNamedEmission||!method.ownership().runtimeEnabled()||function.isFirstClassCallable()
                ||hasBoundArguments(function.getParameters(),function.getArguments())||function.getParameters().size()!=1)return false;
        var entity=compiler.findFunction(name);if(entity==null||entity.isReturnReference())return false;
        var params=entity.getParameters();if(params==null||params.length!=1||!params[0].isReference()||params[0].getTypeChecker()!=null||params[0].isVariadic()||params[0].getDefaultValue()!=null||params[0].getDefaultInitializer()!=null)return false;
        ExprStmtToken argument=function.getParameters().iterator().next();if(!argument.isSingle()||!(argument.getSingle() instanceof VariableExprToken))return false;
        LocalVariable local=method.getLocalVariable(((VariableExprToken)argument.getSingle()).getName());if(local==null||!local.isReference()||local.name.equals("GLOBALS"))return false;
        int id=method.ownership().reserveRuntimeEval();method.ownership().runtimeProducer(id,"FUNCTION_RESULT");
        LocalVariable trace=method.addLocalVariable("~runtimeFunctionTrace"+id,null,TraceInfo.class),packet=method.addLocalVariable("~runtimeFunctionPacket"+id,null,Object.class);
        writePushTraceInfo(function);code.add(new VarInsnNode(ASTORE,trace.index));stackPop();
        LabelNode start=new LabelNode(),fallback=new LabelNode(),join=new LabelNode();
        code.add(new VarInsnNode(ALOAD,method.ownership().runtimeFrameIndex()));code.add(new JumpInsnNode(IFNULL,fallback));code.add(start);
        code.add(new VarInsnNode(ALOAD,method.ownership().runtimeFrameIndex()));code.add(new LdcInsnNode(id));code.add(new LdcInsnNode(name.toLowerCase(Locale.ROOT)));code.add(new VarInsnNode(ALOAD,trace.index));
        MethodInsnNode factory=new MethodInsnNode(INVOKEVIRTUAL,"php/runtime/PhpRuntimeOwnership$Frame","functionPacket","(ILjava/lang/String;Lphp/runtime/env/TraceInfo;)Lphp/runtime/PhpRuntimeOwnership$FunctionPacket;",false);code.add(factory);
        code.add(new VarInsnNode(ASTORE,packet.index));code.add(new VarInsnNode(ALOAD,packet.index));code.add(new InsnNode(ICONST_0));
        VarInsnNode read=new VarInsnNode(ALOAD,local.index);code.add(read);code.add(new LdcInsnNode(local.index));
        MethodInsnNode send=new MethodInsnNode(INVOKEVIRTUAL,"php/runtime/PhpRuntimeOwnership$FunctionPacket","sendCell","(ILphp/runtime/Memory;I)Lphp/runtime/Memory;",false);code.add(send);code.add(new InsnNode(POP));
        code.add(new VarInsnNode(ALOAD,packet.index));code.add(new LdcInsnNode(id));MethodInsnNode invoke=new MethodInsnNode(INVOKEVIRTUAL,"php/runtime/PhpRuntimeOwnership$FunctionPacket",semanticDiscarded(function,returnValue)?"invokeToEvalDiscarded":"invokeToEval","(I)Lphp/runtime/Memory;",false);code.add(invoke);code.add(new JumpInsnNode(GOTO,join));code.add(fallback);
        Token previousWitness=discardedCallWitness;boolean previousEmission=runtimeNamedEmission;
        boolean discarded=semanticDiscarded(function,returnValue);runtimeNamedEmission=true;
        if(discarded)discardedCallWitness=function;
        try{writePushCall(function,true,true,null);stackPop();}
        finally{runtimeNamedEmission=previousEmission;discardedCallWitness=previousWitness;}
        code.add(join);method.ownership().runtimeFallback(fallback,join);
        method.ownership().runtimeFunctionCall(id,name.toLowerCase(Locale.ROOT),entity.getInternalName(),"__invoke","(Lphp/runtime/env/Environment;[Lphp/runtime/Memory;)Lphp/runtime/Memory;",local.index,factory,send,read,invoke,start,fallback,join);
        stackPush(Memory.Type.REFERENCE);stackPeek().runtimeEvaluationId=id;if(!returnValue)writePopAll(1);return true;
    }

    Memory writePushCall(CallExprToken function, boolean returnValue, boolean writeOpcode,
                         PushCallStatistic statistic) {
        if (function.isFirstClassCallable() && !writeOpcode) return null;
        Token name = function.getName();
        if (name instanceof NameToken) {
            String realName = ((NameToken) name).getName();
            CompileFunction compileFunction = compiler.getScope().findCompileFunction(realName,compiler.getModule().getPhpLanguageProfile());

            // try find system function, like max, sin, cos, etc.
            if (compileFunction == null
                    && name instanceof FulledNameToken
                    && compiler.getEnvironment().fetchFunction(realName) == null
                    && compiler.findFunction(realName) == null) {
                String tryName = ((FulledNameToken) name).getLastName().getName();
                compileFunction = compiler.getScope().findCompileFunction(tryName,compiler.getModule().getPhpLanguageProfile());
            }

            if (compileFunction != null && php8CallFrame()
                    && (function.getParameters().size()<compileFunction.getMinArgs() || function.getParameters().size()>compileFunction.getMaxArgs())) {
                FunctionEntity declaration=compiler.getScope().fetchPhp85Function(compileFunction.name);
                if(declaration==null)declaration=compiler.getScope().findUserFunction(compileFunction.name);
                if(declaration instanceof php.runtime.reflection.CompileFunctionEntity
                        && ((php.runtime.reflection.CompileFunctionEntity)declaration).hasCompletePhpSignature())
                    compileFunction=null; // Existing call frame evaluates every SEND before reporting arity.
            }
            if (compileFunction != null && php8CallFrame() && (function.isFirstClassCallable() || hasBoundArguments(function.getParameters(),function.getArguments())))
                compileFunction = null;

            if (compileFunction != null) {
                return writeCompileFunctionWithOwnership(function, compileFunction, returnValue, writeOpcode, statistic);
            } else {
                if (!writeOpcode)
                    return null;
                if(writeRuntimeNamedFunctionFrame(function,realName,returnValue))return null;
                method.entity.setImmutable(false);
                int index = method.clazz.getAndIncCallFuncCount();

                writePushEnv();
                writePushTraceInfo(function);
                writePushString(realName.toLowerCase());
                writePushString(realName);
                if (php8CallFrame()) {
                    writeGetStatic("$CALL_FUNC_CACHE", FunctionCallCache.class);
                    writePushConstInt(index);
                    writePushConstInt(function.getParameters().size());
                    if (method.ownership().enabled() && !function.isFirstClassCallable()
                            && !hasBoundArguments(function.getParameters(),function.getArguments())) {
                        int callId = method.ownership().functionFrame(code, realName, function.getParameters().size());
                        for (int i = 0; i < 7; i++) stackPop();
                        stackPush(null, StackItem.Type.REFERENCE); stackPeek().ownershipCallId = callId;
                    } else writeSysStaticCall(PhpCallFrame.class, "function", PhpCallFrame.class,
                            Environment.class, TraceInfo.class, String.class, String.class,
                            FunctionCallCache.class, Integer.TYPE, Integer.TYPE);
                    writeAcquiredOrCalledFrame(function, returnValue);
                } else {
                    writePushParameters(function.getParameters());
                    writeGetStatic("$CALL_FUNC_CACHE", FunctionCallCache.class);
                    writePushConstInt(index);
                    writeSysStaticCall(
                            InvokeHelper.class, "call", Memory.class,
                            Environment.class, TraceInfo.class, String.class, String.class, Memory[].class,
                            FunctionCallCache.class, Integer.TYPE
                    );
                    if (!returnValue) writePopAll(1);
                }
            }
        } else if(name instanceof ParentPropertyHookExprToken) {
            if(!writeOpcode)return null;
            method.entity.setImmutable(false);method.ownership().runtimeUnsupported("parent-property-hook-call");
            var access=(ParentPropertyHookExprToken)name;
            LocalVariable body=method.getLocalVariable("~propertyHook");
            if(body==null)throw new IllegalArgumentException("Parent hook call lost its compiler-owned body witness");
            writeVarLoad(body);writeVarLoad(method.getGeneratorEntity()==null?"~this":"~propertyHookThis");writePushEnv();writePushTraceInfo(function);
            writePushConstString(access.getProperty());writePushConstInt(access.getHookKind());
            writePushConstInt(function.getParameters().size());
            writeSysStaticCall(php.runtime.PhpPropertyHookParentCall.class,"frame",PhpCallFrame.class,
                    php.runtime.reflection.support.PhpPropertyHooks.Hook.class,IObject.class,Environment.class,TraceInfo.class,
                    String.class,Integer.TYPE,Integer.TYPE);
            writeProtectedFrameArguments(function.getParameters(),function.getArguments(),returnValue,semanticDiscarded(function,returnValue));
            return null;
        } else if (name instanceof StaticAccessOperatorExprToken && php8CallFrame()) {
            method.entity.setImmutable(false);
            if (!writeOpcode) return null;
            return writePhp8StaticMethod(function, returnValue, statistic);
        } else if (name instanceof StaticAccessExprToken) {
            method.entity.setImmutable(false);
            if (((StaticAccessExprToken) name).isAsParent() && !php8CallFrame())
                return writePushParentDynamicMethod(function, returnValue, writeOpcode, statistic);
            else
                return writePushStaticMethod(function, returnValue, writeOpcode, statistic);
        } else if (name instanceof DynamicAccessExprToken) {
            method.entity.setImmutable(false);
            return writePushDynamicMethod(function, returnValue, writeOpcode, statistic);
        } else {
            if (!writeOpcode)
                return null;
            method.entity.setImmutable(false);
            writeLineNumber(function);

            writePush((ValueExprToken) function.getName(), true, false);
            if (php8CallFrame()) {
                writeCallableFrame(function.getParameters(), function, returnValue,function.isFirstClassCallable());
                return null;
            }
            writePopBoxing();
            writePushParameters(function.getParameters());

            writePushEnv();
            writePushTraceInfo(function);

            writeSysStaticCall(
                    InvokeHelper.class, "callAny", Memory.class,
                    Memory.class, Memory[].class, Environment.class, TraceInfo.class
            );
            if (!returnValue)
                writePopAll(1);
        }
        return null;
    }

    public void writeDefineVariables(Collection<VariableExprToken> values) {
        for (VariableExprToken value : values)
            writeDefineVariable(value);
    }

    public void writeUndefineVariables(Collection<VariableExprToken> values) {
        LabelNode end = new LabelNode();
        for (VariableExprToken value : values)
            writeUndefineVariable(value, end);
    }

    /** A literal global declaration imports only its attested physical global address. */
    public boolean writeNamedGlobalImport(LocalVariable local) {
        if (!method.ownership().runtimeEnabled() || method.isExternal() || !php8CallFrame()) return false;
        LabelNode start=new LabelNode(),fallback=new LabelNode(),end=new LabelNode();
        code.add(new VarInsnNode(ALOAD,method.ownership().runtimeFrameIndex()));code.add(new JumpInsnNode(IFNULL,fallback));
        code.add(start);code.add(new VarInsnNode(ALOAD,method.ownership().runtimeFrameIndex()));stackPush(Memory.Type.REFERENCE);
        writePushConstInt(local.index);writePushConstString(local.name);writePushEnv();
        writeSysStaticCall(php.runtime.PhpRuntimeOwnership.class,"namedGlobal",Memory.class,
                php.runtime.PhpRuntimeOwnership.Frame.class,int.class,String.class,Environment.class);
        MethodInsnNode helper=(MethodInsnNode)code.getLast();
        writeVarStore(local,false,false);code.add(new JumpInsnNode(GOTO,end));code.add(fallback);
        writePushEnv();writePushConstString(local.name);
        writeSysDynamicCall(Environment.class,"getOrCreateGlobal",Memory.class,String.class);
        writeSysStaticCall(ReferenceMemory.class,"exportReference",Memory.class,Memory.class);
        if (method.statement.isDynamicLocal()) {
            writeVarLoad(local);writeSysStaticCall(Memory.class,"assignRefRight",Memory.class,Memory.class,Memory.class);writePopAll(1);
        } else {
            writeSysStaticCall(ReferenceMemory.class,"valueOf",Memory.class,Memory.class);writeVarStore(local,false,false);
        }
        code.add(end);method.ownership().runtimeFallback(fallback,end);
        method.ownership().runtimeNamedGlobal(local.index,local.name,helper,start,fallback,end);
        return true;
    }

    public void writeDefineGlobalVar(String name) {
        LocalVariable local=method.getLocalVariable(name);
        if("GLOBALS".equals(name)&&method.ownership().runtimeEnabled()) {
            LabelNode start=new LabelNode(),fallback=new LabelNode(),join=new LabelNode();
            code.add(new VarInsnNode(ALOAD,method.ownership().runtimeFrameIndex()));code.add(new JumpInsnNode(IFNULL,fallback));
            code.add(start);code.add(new VarInsnNode(ALOAD,method.ownership().runtimeFrameIndex()));code.add(new LdcInsnNode(local.index));
            MethodInsnNode address=new MethodInsnNode(INVOKEVIRTUAL,"php/runtime/PhpRuntimeOwnership$Frame","globalsAddress",
                    "(I)Lphp/runtime/memory/ReferenceMemory;",false);code.add(address);
            code.add(new JumpInsnNode(GOTO,join));code.add(fallback);
            writePushEnv();
            if ("GLOBALS".equals(name) && compiler.getModule().getPhpLanguageProfile() == php.runtime.common.PhpLanguageProfile.PHP_8_5) {
                writeSysDynamicCall(Environment.class,"getGlobals",ArrayMemory.class);
                writeSysStaticCall(ReferenceMemory.class,"valueOf",Memory.class,Memory.class);
            } else {
                writePushConstString(name);
                writeSysDynamicCall(Environment.class,"getOrCreateGlobal",Memory.class,String.class);
            }
            code.add(join);method.ownership().runtimeFallback(fallback,join);
            method.ownership().runtimeGlobalImport(local.index,address,start,fallback,join);
        } else {
            writePushEnv();
            if ("GLOBALS".equals(name) && compiler.getModule().getPhpLanguageProfile() == php.runtime.common.PhpLanguageProfile.PHP_8_5) {
                writeSysDynamicCall(Environment.class,"getGlobals",ArrayMemory.class);
                writeSysStaticCall(ReferenceMemory.class,"valueOf",Memory.class,Memory.class);
            } else {
                writePushConstString(name);
                writeSysDynamicCall(Environment.class,"getOrCreateGlobal",Memory.class,String.class);
            }
        }
        makeVarStore(local);stackPop();
    }

    public void invalidateGlobalVariable(String normalizedName) {
        if(!method.isExternal())return;
        LocalVariable local=method.getLocalVariable(normalizedName);
        if(local!=null){local.setValue(null);local.setImmutable(false);local.clearRuntimeClosureWitness();}
    }

    public void writePushThis() {
        if (method.clazz.isClosure() || method.getGeneratorEntity() != null) {
            writeVarLoad("~this");
            writeGetDynamic("self", Memory.class);
        } else {
            if (method.getLocalVariable("this") == null) {
                if (!methodStatement.isStatic()) {
                    LabelNode label = writeLabel(node);
                    LocalVariable local = method.addLocalVariable("this", label, Memory.class);
                    writeDefineThis(local);
                } else {
                    writePushNull();
                    return;
                }
            }

            writeVarLoad("this");
        }
    }

    protected void writeDefineThis(LocalVariable variable) {
        LabelNode sessionFallback = method.ownership().receiverImport(code, variable);
        if (sessionFallback != null) {
            LabelNode end = new LabelNode();
            code.add(new JumpInsnNode(GOTO, end)); code.add(sessionFallback);
            method.ownership().beginPassive();
            try { writeDefineThisFallback(variable); }
            finally { method.ownership().endPassive(sessionFallback, end); }
            code.add(end);
        } else writeDefineThisFallback(variable);
    }

    private void writeDefineThisFallback(LocalVariable variable) {
        if (!method.ownership().runtimeLifecycleEnabled() || method.getGeneratorEntity() != null) {
            writeDefineThisLegacy(variable);
            return;
        }
        LabelNode start = new LabelNode(), fallback = new LabelNode(), end = new LabelNode();
        code.add(start);
        code.add(new VarInsnNode(ALOAD, method.ownership().runtimeFrameIndex()));
        code.add(new JumpInsnNode(IFNULL, fallback));
        code.add(new VarInsnNode(ALOAD, method.ownership().runtimeFrameIndex()));
        code.add(new LdcInsnNode(variable.index));
        VarInsnNode receiver = new VarInsnNode(ALOAD, method.getLocalVariable("~this").index);
        code.add(receiver);
        MethodInsnNode boxing = new MethodInsnNode(INVOKESTATIC, "php/runtime/memory/ObjectMemory", "valueOf",
                "(Lphp/runtime/lang/IObject;)Lphp/runtime/Memory;", false);
        code.add(boxing);
        MethodInsnNode helper = new MethodInsnNode(INVOKEVIRTUAL, "php/runtime/PhpRuntimeOwnership$Frame", "importLifecycleReceiver",
                "(ILphp/runtime/Memory;)Lphp/runtime/Memory;", false);
        code.add(helper);
        VarInsnNode store = new VarInsnNode(ASTORE, variable.index);
        code.add(store);
        code.add(new JumpInsnNode(GOTO, end));
        code.add(fallback);
        method.ownership().beginPassive();
        try { writeDefineThisLegacy(variable); }
        finally { method.ownership().endPassive(fallback, end); }
        code.add(end);
        method.ownership().runtimeFallback(fallback, end);
        method.ownership().runtimeLifecycleReceiver(variable.index, receiver, boxing, helper, store, start, fallback, end);
    }

    private void writeDefineThisLegacy(LocalVariable variable) {
        if (method.clazz.isClosure() || method.getGeneratorEntity() != null) {
            writeVarLoad("~this");
            writeGetDynamic("self", Memory.class);
            makeVarStore(variable);
            stackPop();
        } else {
            LabelNode endLabel = new LabelNode();
            LabelNode elseLabel = new LabelNode();

            if (methodStatement.isStatic()) {
                writePushMemory(Memory.UNDEFINED);
                writeVarStore(variable, false, false);
            } else {
                writeVarLoad("~this");
                writeSysDynamicCall(IObject.class, "isMock", Boolean.TYPE);

                code.add(new JumpInsnNode(IFEQ, elseLabel));
                stackPop();

                if (variable.isReference()) {
                    writePushMemory(Memory.UNDEFINED);
                    writeSysStaticCall(ReferenceMemory.class, "valueOf", Memory.class, Memory.class);
                } else {
                    writePushMemory(Memory.UNDEFINED);
                }
                writeVarStore(variable, false, false);

                code.add(new JumpInsnNode(GOTO, endLabel));
                code.add(elseLabel);

                writeVarLoad("~this");
                writeSysStaticCall(ObjectMemory.class, "valueOf", Memory.class, IObject.class);
                makeVarStore(variable);
                stackPop();

                code.add(endLabel);
            }
        }

        variable.pushLevel();
        variable.setValue(null);
        variable.setImmutable(false);
        variable.setReference(true);
    }

    protected void writeDefineVariable(VariableExprToken value) {
        if (methodStatement.isUnusedVariable(value))
            return;

        LocalVariable variable = method.getLocalVariable(value.getName());
        if (variable == null) {
            LabelNode label = writeLabel(node, value.getMeta().getStartLine());
            variable = method.addLocalVariable(value.getName(), label, Memory.class);
            method.ownership().manage(variable);

            if (methodStatement.isReference(value) || compiler.getScope().superGlobals.contains(value.getName())) {
                variable.setReference(true);
            } else {
                variable.setReference(false);
            }

            if (variable.name.equals("this") && method.getLocalVariable("~this") != null) { // $this
                writeDefineThis(variable);
            } else if (compiler.getScope().superGlobals.contains(value.getName())) { // super-globals
                writeDefineGlobalVar(value.getName());
            } else if (methodStatement.isDynamicLocal()) { // ref-local variables
                LabelNode ownershipEnd = method.ownership().address(code, variable);
                writePushLocal();
                writePushConstString(value.getName());
                writeSysDynamicCall(Memory.class, "refOfIndex", Memory.class, String.class);
                if (ownershipEnd != null) code.add(ownershipEnd);
                makeVarStore(variable);
                stackPop();

                variable.pushLevel();
                variable.setValue(null);
                variable.setReference(true);
            } else { // simple local variables
                LabelNode ownershipEnd = variable.isReference() ? method.ownership().address(code, variable) : null;
                boolean captureState=php8CallFrame() && methodStatement.variable(value).isExplicitValueCapture();
                if (variable.isReference()) {
                    if(captureState) {
                        writePushMemory(Memory.UNDEFINED);
                        writeSysStaticCall(ReferenceMemory.class,"valueOf",Memory.class,Memory.class);
                    } else writePushNewObject(ReferenceMemory.class);
                } else if(captureState)writePushMemory(Memory.UNDEFINED);
                else writePushNull();

                if (ownershipEnd != null) code.add(ownershipEnd);
                makeVarStore(variable);

                stackPop();
                variable.pushLevel();
            }
        } else
            variable.pushLevel();
    }

    protected void writeUndefineVariable(VariableExprToken value, LabelNode end) {
        LocalVariable variable = method.getLocalVariable(value.getName());
        if (variable != null)
            variable.popLevel();
    }

    public void writeQuietly(Collection<ExprStmtToken> expressions, Runnable emission) {
        List<Token> added = new ArrayList<>();
        for (ExprStmtToken expression : expressions) {
            Token first = expression.getAsmExpr().getTokens().getFirst();
            if (first instanceof VariableExprToken && ((VariableExprToken) first).getName().equals("this")
                    && quietThisReads.add(first)) added.add(first);
        }
        try {emission.run();} finally {quietThisReads.removeAll(added);}
    }

    public void writePushVariable(VariableExprToken value) {
        LocalVariable variable = method.getLocalVariable(value.getName());
        if (variable == null || variable.getClazz() == null)
            writePushNull();
        else {
            writeVarLoad(variable);
        }
        if (php8CallFrame() && value.getName().equals("this") && !quietThisReads.contains(value)) {
            LabelNode present = new LabelNode();
            method.ownership().receiverReadGuard(code, variable, present);
            writePushDup();
            writeSysDynamicCall(Memory.class, "isNull", boolean.class);
            code.add(new JumpInsnNode(IFEQ, present)); stackPop();
            writePushEnv(); writePushTraceInfo(value);
            code.add(new LdcInsnNode(Type.getType(php.runtime.lang.exception.BaseError.class)));
            stackPush(null, StackItem.Type.REFERENCE);
            writePushConstString("Using $this when not in object context"); writePushConstNull();
            writeSysDynamicCall(Environment.class, "exception", void.class, TraceInfo.class, Class.class, String.class, Object[].class);
            code.add(present);
        }
    }

    public Memory tryWritePushVariable(VariableExprToken value, boolean heavyObjects) {
        if (methodStatement.isUnstableVariable(value))
            return null;

        if (compiler.getScope().superGlobals.contains(value.getName()))
            return null;

        LocalVariable variable = method.getLocalVariable(value.getName());
        if (variable == null || variable.getClazz() == null || methodStatement.isUnusedVariable(value)) {
            return Memory.NULL;
        } else {
            if (methodStatement.variable(value).isPassed())
                return null;

            Memory mem = variable.getValue();
            if (mem != null) {
                if (!heavyObjects) {
                    if (mem.isArray() || mem.toString().length() > 100) {
                        return null;
                    }
                }
                return mem;
            }
        }
        return null;
    }

    /** A fresh ARRAY owns an EVAL before evaluating its first element. Scratch JVM locals only borrow it. */
    private void stageRuntimeArray() {
        if (!method.ownership().runtimeEnabled()) return;
        int id=method.ownership().reserveRuntimeEval();
        method.ownership().runtimeProducer(id,"ARRAY_LITERAL");
        LocalVariable scratch=method.addLocalVariable("~runtimeArray"+id,null,ArrayMemory.class);
        code.add(new TypeInsnNode(CHECKCAST,Type.getInternalName(ArrayMemory.class)));
        code.add(new VarInsnNode(ASTORE,scratch.index));
        code.add(new VarInsnNode(ALOAD,method.ownership().runtimeFrameIndex()));code.add(new LdcInsnNode(id));
        code.add(new VarInsnNode(ALOAD,scratch.index));
        code.add(new MethodInsnNode(INVOKESTATIC,"php/runtime/PhpOwnershipArrays","runtimeFresh",
                "(Lphp/runtime/PhpRuntimeOwnership$Frame;ILphp/runtime/memory/ArrayMemory;)Lphp/runtime/memory/ArrayMemory;",false));
        stackPeek().runtimeEvaluationId=id;stackPeek().runtimeArrayValueProducer=true;
    }
    public void releaseRuntimeEvaluation(int id,String route) {
        if (id < 0) return;
        method.ownership().runtimeConsumer(id,route);
        LabelNode end=new LabelNode();code.add(new VarInsnNode(ALOAD,method.ownership().runtimeFrameIndex()));
        code.add(new JumpInsnNode(IFNULL,end));code.add(new VarInsnNode(ALOAD,method.ownership().runtimeFrameIndex()));
        code.add(new LdcInsnNode(id));code.add(new MethodInsnNode(INVOKEVIRTUAL,"php/runtime/PhpRuntimeOwnership$Frame","releaseEval","(I)V",false));code.add(end);
    }

    public Memory writePushArray(ArrayExprToken array, boolean returnMemory, boolean writeOpcode) {
        boolean php8 = compiler.getModule().getPhpLanguageProfile() == php.runtime.common.PhpLanguageProfile.PHP_8_5;
        if (array.getParameters().isEmpty()) {
            if (returnMemory)
                return new ArrayMemory();
            else if (writeOpcode) {
                writeSysStaticCall(ArrayMemory.class, "valueOf", Memory.class);stageRuntimeArray();
            }
        } else {
            ArrayMemory ret = returnMemory ? new ArrayMemory() : null;

            if (ret == null) {
                boolean map = false;

                for (ExprStmtToken token : array.getParameters()) {
                    for (Token sub : token.getTokens()) {
                        if (sub instanceof KeyValueExprToken) {
                            map = true;
                            break;
                        }
                    }

                    if (map) break;
                }

                writePushConstInt(array.getParameters().size());
                if (map) {
                    writeSysStaticCall(ArrayMemory.class, "createHashed", ArrayMemory.class, int.class);
                } else {
                    writeSysStaticCall(ArrayMemory.class, "createListed", ArrayMemory.class, int.class);
                }
                stageRuntimeArray();
            }

            int parameterIndex = 0;
            for (ExprStmtToken param : array.getParameters()) {
                boolean unpack = array.isUnpacked(parameterIndex++);
                int parentRuntimeEval=ret==null?stackPeek().runtimeEvaluationId:-1;
                if (ret == null)
                    writePushDup();

                Memory result = writeExpression(param, true, true, ret == null);

                if (result != null) {
                    if (unpack && !result.isArray()) {
                        compiler.getEnvironment().error(param.toTraceInfo(compiler.getContext()), ErrorType.E_COMPILE_ERROR,
                                "Only arrays and Traversables can be unpacked");
                    }
                    if (ret != null) {
                        if (unpack) {
                            try {
                                ret.unpack(result, compiler.getEnvironment(), param.toTraceInfo(compiler.getContext()), php8);
                            } catch (php.runtime.lang.exception.BaseError error) {
                                if (!php8) throw error;
                                if (!writeOpcode) return null;
                                return writePushArray(array, false, true);
                            }
                        } else {
                            if (php8 && !(result instanceof KeyValueMemory) && !ret.canAppendPhp8()) {
                                if (!writeOpcode) return null;
                                return writePushArray(array, false, true);
                            }
                            if (php8) ret.addPhp8(result, compiler.getEnvironment(), param.toTraceInfo(compiler.getContext()));
                            else ret.add(result);
                        }
                        continue;
                    } else {
                        writePushMemory(result);
                    }
                } else {
                    if (!writeOpcode) {
                        return null;
                    }

                    ret = null;
                }

                if (result == null && returnMemory) {
                    return writePushArray(array, false, writeOpcode);
                }

                writePopBoxing();
                MethodInsnNode literalPair=runtimeLiteralPairs.remove(stackPeek());
                writePopImmutable();
                int childRuntimeEval=stackPeek().runtimeEvaluationId;
                if (unpack) {
                    writePushEnv();
                    writePushTraceInfo(param);
                    writeSysDynamicCall(ArrayMemory.class, "unpack", void.class, Memory.class, Environment.class, TraceInfo.class);
                } else {
                    if (php8) {
                        writePushEnv();
                        writePushTraceInfo(param);
                        if(literalPair!=null&&parentRuntimeEval>=0) {
                            code.add(new VarInsnNode(ALOAD,method.ownership().runtimeFrameIndex()));stackPush(Memory.Type.REFERENCE);
                            writePushConstInt(parentRuntimeEval);
                            writeSysStaticCall(php.runtime.PhpRuntimeOwnership.class,"literalEntry",ReferenceMemory.class,
                                    ArrayMemory.class,Memory.class,Environment.class,TraceInfo.class,php.runtime.PhpRuntimeOwnership.Frame.class,int.class);
                            method.ownership().runtimeLiteralEntry(parentRuntimeEval,literalPair,(MethodInsnNode)code.getLast());
                        } else writeSysDynamicCall(ArrayMemory.class, "addPhp8", ReferenceMemory.class, Memory.class, Environment.class, TraceInfo.class);
                    } else writeSysDynamicCall(ArrayMemory.class, "add", ReferenceMemory.class, Memory.class);
                    writePopAll(1);
                }
                releaseRuntimeEvaluation(childRuntimeEval,"ENTRY");
            }

            if (ret != null)
                return ret;
        }
        setStackPeekAsImmutable();
        return null;
    }

    public int writePushTraceInfo(Token token) {
        return writePushTraceInfo(token.getMeta().getStartLine(), token.getMeta().getStartPosition());
    }

    /** Compact capture helpers consume the exact same trace slot without a generated AALOAD. */
    public void writePushCaptureTrace(Token token) {
        int index=method.clazz.addTraceInfo(token.getMeta().getStartLine(),token.getMeta().getStartPosition());
        writeGetStatic("$TRC",TraceInfo[].class);writePushSmallInt(index);
    }

    void writePushGetFromArray(int index, Class clazz) {
        writePushSmallInt(index);
        code.add(new InsnNode(AALOAD));
        stackPop();
        stackPop();
        stackPush(null, StackItem.Type.valueOf(clazz));
    }

    void writePushGetArrayLength() {
        code.add(new InsnNode(ARRAYLENGTH));
        stackPop();
        stackPush(null, StackItem.Type.INT);
    }

    int createTraceInfo(Token token) {
        return method.clazz.addTraceInfo(token);
    }

    int writePushTraceInfo(int line, int position) {
        int index = method.clazz.addTraceInfo(line, position);
        writePushTraceInfo(index);
        return index;
    }

    void writePushTraceInfo(int traceIndex) {
        writeGetStatic("$TRC", TraceInfo[].class);
        writePushGetFromArray(traceIndex, TraceInfo.class);
    }

    /** A copied hook keeps the exact donor TraceInfo, including its file/profile identity. */
    void writePushImportedHookTrace(String owner,int index) {
        code.add(new FieldInsnNode(GETSTATIC,owner,"$TRC",Type.getDescriptor(TraceInfo[].class)));
        stackPush(null,StackItem.Type.valueOf(TraceInfo[].class));setStackPeekAsImmutable();
        writePushGetFromArray(index,TraceInfo.class);
    }
    void writePushCreateTraceInfo(int line, int position) {
        writeGetStatic("$FN", String.class);
        writePushMemory(LongMemory.valueOf(line));
        writePushMemory(LongMemory.valueOf(position));
        writePushConstString(compiler.getModule().getInternalName());
        writeSysStaticCall(TraceInfo.class, "valueOf", TraceInfo.class, String.class, Long.TYPE, Long.TYPE, String.class);
    }

    int writePushConstantMemory(Memory memory) {
        int index = method.clazz.addMemoryConstant(memory);

        writeGetStatic("$MEM", Memory[].class);
        writePushGetFromArray(index, Memory.class);

        return index;
    }

    int writePushConstantMemoryArray(Collection<Memory> memories) {
        int index = method.clazz.addMemoryArray(memories);

        writeGetStatic("$AMEM", Memory[][].class);
        writePushGetFromArray(index, Memory[].class);

        return index;
    }

    Memory tryWritePushMacro(MacroToken macro, boolean writeOpcode) {
        if (macro instanceof LineMacroToken) {
            return LongMemory.valueOf(macro.getMeta().getStartLine() + 1);
        } else if (macro instanceof FileMacroToken) {
            return new StringMemory(compiler.getSourceFile());
        } else if (macro instanceof DirMacroToken) {
            String sourceFile = compiler.getSourceFile();
            int scheme = sourceFile.indexOf("://");
            if (scheme > 1) {
                int separator = sourceFile.lastIndexOf('/');
                return new StringMemory(sourceFile.substring(0, Math.max(scheme + 3, separator)));
            }
            String parent = new File(sourceFile).getParent();

            // Fix issue #198.
            if (sourceFile.startsWith(parent + "//") && parent.endsWith(":")) {
                parent += "//";
            }

            return new StringMemory(parent);
        } else if (macro instanceof FunctionMacroToken) {
            if (method.isInitializer() && php8CallFrame()
                    && (!method.isAttributeInitializer() || method.getAttributeCallableName().isEmpty())) return Memory.CONST_EMPTY_STRING;
            if (method.clazz == null) {
                return Memory.CONST_EMPTY_STRING;
            }

            if (method.clazz.isClosure()) {
                return new StringMemory("{closure}");
            }

            if (StringUtils.isEmpty(method.clazz.getFunctionName()))
                return method.clazz.isSystem()
                        ? Memory.CONST_EMPTY_STRING
                        : method.getRealName() == null
                        ? Memory.CONST_EMPTY_STRING : new StringMemory(method.getRealName());
            else {
                return method.clazz.getFunctionName() == null
                        ? Memory.CONST_EMPTY_STRING
                        : new StringMemory(method.clazz.getFunctionName());
            }

        } else if (macro instanceof MethodMacroToken) {
            if (method.isInitializer() && php8CallFrame()
                    && (!method.isAttributeInitializer() || method.getAttributeCallableName().isEmpty())) return Memory.CONST_EMPTY_STRING;
            if (method.clazz == null) {
                return Memory.CONST_EMPTY_STRING;
            }

            if (method.clazz.isClosure()) {
                return new StringMemory("{closure}");
            }

            if (method.clazz.isSystem())
                return method.clazz.getFunctionName() != null
                        ? new StringMemory(method.clazz.getFunctionName()) : Memory.NULL;
            else
                return new StringMemory(
                        method.clazz.entity.getName()
                                + (method.getRealName() == null ? "" : "::" + method.getRealName())
                );

        } else if (macro instanceof ClassMacroToken) {
            if (php8CallFrame() && method.clazz.isClosure() && method.clazz.getClassContext() != null
                    && method.clazz.getClassContext().isTrait()) {
                if (writeOpcode) {
                    writePushEnv(); writePushTraceInfo(macro);
                    writeSysDynamicCall(Environment.class, "__getSelf", String.class, TraceInfo.class);
                    writeSysStaticCall(StringMemory.class, "valueOf", Memory.class, String.class);
                }
                return null;
            }
            if (method.clazz.entity.isTrait()) {
                if (writeOpcode) {
                    writePushEnv();
                    if (method.isInitializer() && php8CallFrame()) {
                        writePushTraceInfo(macro);
                        writeSysDynamicCall(Environment.class, "__getSelf", String.class, TraceInfo.class);
                        writeSysStaticCall(StringMemory.class, "valueOf", Memory.class, String.class);
                    } else writeSysDynamicCall(Environment.class, "__getMacroClass", Memory.class);
                }
                return null;
            } else {
                if (method.clazz.getClassContext() != null) {
                    return new StringMemory(method.clazz.getClassContext().getFulledName());
                } else {
                    return new StringMemory(method.clazz.isSystem() ? "" : method.clazz.entity.getName());
                }
            }
        } else if (macro instanceof NamespaceMacroToken) {
            return new StringMemory(
                    compiler.getNamespace() == null || compiler.getNamespace().getName() == null
                            ? ""
                            : compiler.getNamespace().getName().getName()
            );
        } else if (macro instanceof TraitMacroToken) {
            if (php8CallFrame() && method.clazz.getClassContext() != null && method.clazz.getClassContext().isTrait())
                return new StringMemory(method.clazz.getClassContext().getFulledName());
            if (method.clazz.entity.isTrait())
                return new StringMemory(method.clazz.entity.getName());
            else
                return Memory.CONST_EMPTY_STRING;
        } else
            throw new IllegalArgumentException("Unsupported macro value: " + macro.getWord());
    }

    Memory writePushName(NameToken token, boolean returnMemory, boolean writeOpcode) {
        CompileConstant constant = compiler.getScope().findCompileConstant(token.getName(),
                compiler.getModule().getPhpLanguageProfile());
        if (constant != null && !(compiler.getModule().getPhpLanguageProfile()
                == php.runtime.common.PhpLanguageProfile.PHP_8_5 && "E_STRICT".equals(token.getName()))) {
            if (returnMemory)
                return constant.value;
            else if (writeOpcode)
                writePushMemory(constant.value);
        } else {
            ConstantEntity constantEntity = compiler.getModule().getPhpLanguageProfile()
                    == php.runtime.common.PhpLanguageProfile.PHP_8_5 ? null : compiler.findConstant(token.getName());
            /*if (constantEntity == null)   // TODO: maybe it's not needed! we should search a namespaced constant in local context
                constantEntity = compiler.getScope().findUserConstant(token.getName()); */

            if (constantEntity != null) {
                if (returnMemory)
                    return constantEntity.getValue();
                else if (writeOpcode) {
                    writePushMemory(constantEntity.getValue());
                    setStackPeekAsImmutable();
                }
            } else {
                if (!writeOpcode)
                    return null;
                writePushEnv();
                writePushString(token.getName());
                writePushString(token.getName().toLowerCase(Locale.ROOT));

                // writePushDupLowerCase();
                writePushTraceInfo(token);
                if (compiler.getModule().getPhpLanguageProfile() == php.runtime.common.PhpLanguageProfile.PHP_8_5) {
                    writePushConstBoolean(token instanceof FulledNameToken && ((FulledNameToken) token).isConstantFallback());
                    // Leading NUL cannot collide with user-static keys (which start with a JVM class name).
                    writePushConstString("\0jphp.constant.fetch\0" + compiler.getModule().getInternalName()
                            + "\0" + method.getMethodId() + "\0" + compiler.nextConstantFetchSite());
                    writeSysStaticCall(php.runtime.PhpConstantReads.class, "fetch",
                            Memory.class, Environment.class, String.class, String.class, TraceInfo.class, boolean.class, String.class);
                    method.ownership().constantRead((MethodInsnNode)code.getLast());
                } else {
                    writeSysDynamicCall(Environment.class, "__getConstant",
                            Memory.class, String.class, String.class, TraceInfo.class);
                }
                setStackPeekAsImmutable();
            }
        }
        return null;
    }

    Memory tryWritePushReference(StackItem item, boolean writeOpcode, boolean toStack, boolean heavyObjects) {
        if (item.getToken() instanceof VariableExprToken) {
            writePushVariable((VariableExprToken) item.getToken());
            return null;
        }

        return tryWritePush(item, writeOpcode, toStack, heavyObjects);
    }

    Memory tryWritePush(StackItem item, boolean writeOpcode, boolean toStack, boolean heavyObjects) {
        if (item.getToken() != null)
            return tryWritePush(item.getToken(), true, writeOpcode, heavyObjects);
        else if (item.getMemory() != null) {
            return item.getMemory();
        } else if (toStack) {
            stackPush(null, item.type);
            stackPeek().ownershipEvalId = item.ownershipEvalId;
            stackPeek().runtimeEvaluationId = item.runtimeEvaluationId;
            stackPeek().runtimeSourceLocalId = item.runtimeSourceLocalId;
            stackPeek().runtimeSourceRead = item.runtimeSourceRead;
            stackPeek().runtimeArrayValueProducer=item.runtimeArrayValueProducer;
            stackPeek().ownershipCallId = item.ownershipCallId;
        }
        return null;
    }

    StackItem.Type tryGetType(StackItem item) {
        if (item.getToken() != null) {
            return tryGetType(item.getToken());
        } else if (item.getMemory() != null) {
            return item.getMemory() instanceof PhpByteStringMemory || php8CallFrame() && item.getMemory() instanceof BinaryMemory
                    ? StackItem.Type.REFERENCE : StackItem.Type.valueOf(item.getMemory().type);
        } else
            return item.type;
    }

    public boolean tryIsImmutable(StackItem item) {
        if (item.getToken() != null) {
            return tryIsImmutable(item.getToken());
        } else if (item.getMemory() != null)
            return true;
        else
            return item.type.isConstant();
    }

    public Memory tryWritePush(StackItem item) {
        return tryWritePush(item, true, true, true);
    }

    public void writePush(StackItem item) {
        writePush(item, true, false);
    }

    public void writePush(StackItem item, boolean tryGetMemory, boolean heavyObjects) {
        if (tryGetMemory) {
            Memory memory = tryWritePushReference(item, true, true, heavyObjects);
            if (memory != null)
                writePushMemory(memory);
        } else {
            tryWritePushReference(item, true, true, heavyObjects);
        }
    }

    public void writePush(StackItem item, StackItem.Type castType) {
        Memory memory = tryWritePush(item);
        if (memory != null) {
            switch (castType) {
                case DOUBLE:
                    writePushConstDouble(memory.toDouble());
                    return;
                case FLOAT:
                    writePushConstFloat((float) memory.toDouble());
                    return;
                case LONG:
                    writePushConstLong(memory.toLong());
                    return;
                case INT:
                    writePushConstInt((int) memory.toLong());
                    return;
                case SHORT:
                    writePushConstInt((short) memory.toLong());
                    return;
                case BYTE:
                    writePushConstByte((byte) memory.toLong());
                    return;
                case BOOL:
                    writePushConstBoolean(memory.toBoolean());
                    return;
                case STRING:
                    writePushConstString(memory.toString());
                    return;
            }
            writePushMemory(memory);
        }
        writePop(castType.toClass(), true, true);
    }

    boolean tryIsImmutable(ValueExprToken value) {
        if (value instanceof IntegerExprToken)
            return true;
        else if (value instanceof DoubleExprToken)
            return true;
        else if (value instanceof StringExprToken)
            return true;
        else if (value instanceof LineMacroToken)
            return true;
        else if (value instanceof MacroToken)
            return true;
        else if (value instanceof ArrayExprToken)
            return true;
        else if (value instanceof StringBuilderExprToken)
            return true;
        else if (value instanceof NameToken)
            return true;
        else if (value instanceof CallExprToken) {
            PushCallStatistic statistic = new PushCallStatistic();
            writePushCall((CallExprToken) value, true, false, statistic);
            return statistic.returnType.isConstant();
        }

        return false;
    }

    StackItem.Type tryGetType(ValueExprToken value) {
        if (value instanceof IntegerExprToken)
            return StackItem.Type.LONG;
        else if (value instanceof DoubleExprToken)
            return StackItem.Type.DOUBLE;
        else if (value instanceof StringExprToken)
            return ((StringExprToken)value).hasRawBytes() ? StackItem.Type.REFERENCE : StackItem.Type.STRING;
        else if (value instanceof LineMacroToken)
            return StackItem.Type.LONG;
        else if (value instanceof MacroToken)
            return StackItem.Type.STRING;
        else if (value instanceof ArrayExprToken)
            return StackItem.Type.ARRAY;
        else if (value instanceof StringBuilderExprToken)
            return php8CallFrame() ? StackItem.Type.STRING_VALUE : StackItem.Type.STRING;
        else if (value instanceof CallExprToken) {
            PushCallStatistic statistic = new PushCallStatistic();
            writePushCall((CallExprToken) value, true, false, statistic);
            return statistic.returnType;
        } else if (value instanceof NameToken) {
            Memory tmpMemory = writePushName((NameToken) value, true, false);
            return tmpMemory == null ? StackItem.Type.REFERENCE : StackItem.Type.valueOf(tmpMemory.type);
        } else
            return StackItem.Type.REFERENCE;
    }

    Memory tryWritePush(ValueExprToken value, boolean returnValue, boolean writeOpcode, boolean heavyObjects) {
        if (!writeOpcode && php8CallFrame() && method.isInitializer() && value instanceof StaticAccessExprToken)
            return PhpConstantExpression.constantValue((StaticAccessExprToken) value, this, null);
        if (writeOpcode) {
            BaseStatementCompiler compiler = getCompiler(value.getClass());
            if (compiler != null) {
                if (compiler instanceof BaseExprCompiler) {
                    ((BaseExprCompiler) compiler).write(value, returnValue);
                } else
                    throw new IllegalStateException("Compiler must be extended by " + BaseExprCompiler.class.getName());

                return null;
            }
        }

        if (value instanceof NameToken) {
            return writePushName((NameToken) value, returnValue, writeOpcode);
        }
        if (value instanceof ArrayExprToken) {
            return writePushArray((ArrayExprToken) value, returnValue, writeOpcode);
        } else if (value instanceof CallExprToken) {
            return writePushCall((CallExprToken) value, returnValue, writeOpcode, null);
        } else if (value instanceof MacroToken) {
            return tryWritePushMacro((MacroToken) value, writeOpcode);
        } else if (value instanceof VariableExprToken) {
            if (writeOpcode)
                writePushVariable((VariableExprToken) value);

            /*if (returnValue)
                return tryWritePushVariable((VariableExprToken) value, heavyObjects);  */
        }

        return null;
    }

    public void writePush(ValueExprToken value, boolean returnValue, boolean heavyObjects) {
        if (value instanceof VariableExprToken) {
            writePushVariable((VariableExprToken) value);
            return;
        }

        Memory memory = tryWritePush(value, returnValue, true, heavyObjects);
        if (memory != null)
            writePushMemory(memory);
    }

    @SuppressWarnings("unchecked")
    boolean methodExists(Class clazz, String method, Class... paramClasses) {
        try {
            clazz.getDeclaredMethod(method, paramClasses);
            return true;
        } catch (java.lang.NoSuchMethodException e) {
            return false;
        }
    }

    void writeSysCall(String internalClassName, int INVOKE_TYPE, String method, Class returnClazz, Class... paramClasses) {
        Type[] args = new Type[paramClasses.length];
        if (INVOKE_TYPE == INVOKEVIRTUAL || INVOKE_TYPE == INVOKEINTERFACE)
            stackPop(); // this

        for (int i = 0; i < args.length; i++) {
            args[i] = Type.getType(paramClasses[i]);
            stackPop();
        }

        code.add(new MethodInsnNode(
                INVOKE_TYPE, internalClassName, method, Type.getMethodDescriptor(Type.getType(returnClazz), args),
                false
        ));

        if (returnClazz != void.class) {
            stackPush(null, StackItem.Type.valueOf(returnClazz));
        }
    }

    @SuppressWarnings("unchecked")
    void writeSysCall(Class clazz, int INVOKE_TYPE, String method, Class returnClazz, Class... paramClasses) {
        if (INVOKE_TYPE != INVOKESPECIAL && clazz != null) {
            if (compiler.getScope().isDebugMode()) {
                if (!methodExists(clazz, method, paramClasses)) {
                    throw new NoSuchMethodException(clazz, method, paramClasses);
                }
            }
        }

        Type[] args = new Type[paramClasses.length];
        if (INVOKE_TYPE == INVOKEVIRTUAL || INVOKE_TYPE == INVOKEINTERFACE)
            stackPop(); // this

        for (int i = 0; i < args.length; i++) {
            args[i] = Type.getType(paramClasses[i]);
            stackPop();
        }

        String owner = clazz == null ? this.method.clazz.node.name : Type.getInternalName(clazz);
        if (clazz == null && this.method.clazz.entity.isTrait())
            throw new CriticalException("[Compiler Error] Cannot use current classname in Trait");

        code.add(new MethodInsnNode(
                INVOKE_TYPE, owner, method, Type.getMethodDescriptor(Type.getType(returnClazz), args),
                clazz != null && clazz.isInterface()
        ));

        if (returnClazz != void.class) {
            stackPush(null, StackItem.Type.valueOf(returnClazz));
        }
    }

    public void writeTickTrigger(Token token) {
        writeTickTrigger(token.toTraceInfo(getCompiler().getContext()));
    }

    public void writeTickTrigger(TraceInfo trace) {
        if (compiler.getScope().isDebugMode() && method.getLocalVariable("~local") != null) {
            int line = trace.getStartLine();

            if (method.registerTickTrigger(line)) {
                writePushEnv();
                writePushTraceInfo(trace.getStartLine(), trace.getStartPosition());
                writePushLocal();
                if (method.ownership().enabled()) { method.ownership().tick(code); stackPop(); stackPop(); stackPop(); }
                else writeSysDynamicCall(Environment.class, "__tick", void.class, TraceInfo.class, ArrayMemory.class);
            }
        }
    }

    public void writeSysDynamicCall(Class clazz, String method, Class returnClazz, Class... paramClasses)
            throws NoSuchMethodException {
        writeSysCall(
                clazz, clazz != null && clazz.isInterface() ? INVOKEINTERFACE : INVOKEVIRTUAL,
                method, returnClazz, paramClasses
        );
    }

    public void writeSysStaticCall(Class clazz, String method, Class returnClazz, Class... paramClasses)
            throws NoSuchMethodException {
        writeSysCall(clazz, INVOKESTATIC, method, returnClazz, paramClasses);
    }

    public void writePopImmutable() {
        if (stackPeek().type==StackItem.Type.STRING_VALUE) return;
        if (!stackPeek().immutable) {
            int evalId = stackPeek().ownershipEvalId;
            int runtimeEval=stackPeek().runtimeEvaluationId;
            int sourceLocal=stackPeek().runtimeSourceLocalId;
            VarInsnNode sourceRead=stackPeek().runtimeSourceRead;
            boolean arrayProducer=stackPeek().runtimeArrayValueProducer;
            String closureName=stackPeek().runtimeClosureInternalName;int closureProducer=stackPeek().runtimeClosureProducerId;
            writeSysDynamicCall(Memory.class,
                    compiler.getModule().getPhpLanguageProfile() == php.runtime.common.PhpLanguageProfile.JPHP_LEGACY
                            ? "toImmutableForLegacy" : "toImmutable", Memory.class);
            stackPeek().ownershipEvalId = evalId;
            stackPeek().runtimeEvaluationId = runtimeEval;
            stackPeek().runtimeSourceLocalId=sourceLocal;
            stackPeek().runtimeSourceRead=sourceRead;
            stackPeek().runtimeArrayValueProducer=arrayProducer;
            stackPeek().runtimeClosureInternalName=closureName;stackPeek().runtimeClosureProducerId=closureProducer;
            setStackPeekAsImmutable();
        }
    }

    public void writeVarStore(LocalVariable variable, boolean returned, boolean asImmutable) {
        writePopBoxing();

        if (asImmutable)
            writePopImmutable();

        if (returned) {
            writePushDup();
        }

        int runtimeEval=stackPeek().runtimeEvaluationId;
        makeVarStore(variable);
        stackPop();
        if (runtimeEval >= 0) method.ownership().runtimeConsumer(runtimeEval,"CV");
        if (!returned) releaseRuntimeEvaluation(runtimeEval,"CV");
    }

    public void checkAssignableVar(VariableExprToken var) {
        if (method.clazz.isClosure() || !method.clazz.isSystem()) {
            if (var.getName().equals("this"))
                compiler.getEnvironment().error(var.toTraceInfo(compiler.getContext()), "Cannot re-assign $this");
        }
    }

    public void writeVarAssign(LocalVariable variable, VariableExprToken token, boolean returned, boolean asImmutable) {
        if (token != null)
            checkAssignableVar(token);

        writePopBoxing(asImmutable);

        variable.clearRuntimeClosureWitness();
        if (variable.isReference()) {
            StackItem value = stackPeek();
            if (method.ownership().assign(code, variable, false, value.ownershipEvalId,value.runtimeEvaluationId,returned)) {
                if (!returned) recordRuntimeClosureCv(variable,token,value,value.runtimeEvaluationId);
                value.ownershipEvalId = -1;
                if (!returned) value.runtimeEvaluationId = -1;
                if (!returned) writePopAll(1);
                return;
            }
            int runtimeEval=value.runtimeEvaluationId;
            writeVarLoad(variable);
            writeSysStaticCall(Memory.class, "assignRight", Memory.class, Memory.class, Memory.class);
            stackPeek().runtimeEvaluationId=runtimeEval;
            if (runtimeEval >= 0) method.ownership().runtimeConsumer(runtimeEval,"CV");
            if (!returned)
                writePopAll(1);
        } else {
            if (returned) {
                writePushDup();
            }

            int runtimeEval=stackPeek().runtimeEvaluationId;
            makeVarStore(variable);
            stackPop();
            if (runtimeEval >= 0) method.ownership().runtimeConsumer(runtimeEval,"CV");
            if (!returned) releaseRuntimeEvaluation(runtimeEval,"CV");
        }
    }

    public void writeVarStore(LocalVariable variable, boolean returned) {
        writeVarStore(variable, returned, false);
    }

    public void writeVarLoad(LocalVariable variable) {
        stackPush(Memory.Type.valueOf(variable.getClazz()));
        makeVarLoad(variable);
        stackPeek().runtimeClosureInternalName=variable.getRuntimeClosureInternalName();
        stackPeek().runtimeClosureProducerId=variable.getRuntimeClosureProducerId();
        stackPeek().runtimeSourceLocalId=variable.index;
        stackPeek().runtimeSourceRead=(VarInsnNode)code.getLast();
    }

    public void writeVarLoad(String name) {
        LocalVariable local = method.getLocalVariable(name);
        if (local == null)
            throw new IllegalArgumentException("Variable '" + name + "' is not registered");

        writeVarLoad(local);
    }

    public void writePutStatic(Class clazz, String name, Class fieldClass) {
        code.add(new FieldInsnNode(PUTSTATIC, Type.getInternalName(clazz), name, Type.getDescriptor(fieldClass)));
        stackPop();
    }

    public void writePutStatic(String name, Class fieldClass) {
        code.add(new FieldInsnNode(
                PUTSTATIC,
                method.clazz.node.name,
                name,
                Type.getDescriptor(fieldClass)
        ));
        stackPop();
    }

    public void writePutDynamic(String name, Class fieldClass) {
        code.add(new FieldInsnNode(
                PUTFIELD,
                method.clazz.node.name,
                name,
                Type.getDescriptor(fieldClass)
        ));
        stackPop();
    }

    public void writeGetStatic(Class clazz, String name, Class fieldClass) {
        code.add(new FieldInsnNode(GETSTATIC, Type.getInternalName(clazz), name, Type.getDescriptor(fieldClass)));
        stackPush(null, StackItem.Type.valueOf(fieldClass));
        setStackPeekAsImmutable();
    }

    public void writeGetStatic(String name, Class fieldClass) {
        code.add(new FieldInsnNode(
                GETSTATIC,
                method.clazz.node.name,
                name,
                Type.getDescriptor(fieldClass)
        ));
        stackPush(null, StackItem.Type.valueOf(fieldClass));
        setStackPeekAsImmutable();
    }

    public void writeGetDynamic(String name, Class fieldClass) {
        stackPop();
        code.add(new FieldInsnNode(
                GETFIELD,
                method.clazz.node.name,
                name,
                Type.getDescriptor(fieldClass)
        ));
        stackPush(null, StackItem.Type.valueOf(fieldClass));
        setStackPeekAsImmutable();
    }

    void writeGetEnum(Enum enumInstance) {
        writeGetStatic(enumInstance.getDeclaringClass(), enumInstance.name(), enumInstance.getDeclaringClass());
    }

    void writeVariableAssign(VariableExprToken variable, StackItem R, AssignExprToken operator, boolean returnValue) {
        LocalVariable local = method.getLocalVariable(variable.getName());
        checkAssignableVar(variable);

        Memory value = R.getMemory();
        String closureName=null;int closureProducer=-1;
        local.clearRuntimeClosureWitness();

        writeLineNumber(variable);

        if (local.isReference() && local.isOwnershipLocal() && (method.ownership().enabled() || method.ownership().runtimeEnabled())) {
            Memory result;
            if (!R.isKnown()) { stackPush(R); result = null; }
            else result = tryWritePush(R);
            if (result != null) { writePushMemory(result); value = result; }
            writePopBoxing();
            if (!operator.isAsReference()) writePopImmutable();
            StackItem candidate = stackPeek();
            boolean published=method.ownership().assign(code, local, operator.isAsReference(), candidate.ownershipEvalId,candidate.runtimeEvaluationId,returnValue);
            if (published && !returnValue && !operator.isAsReference()
                    && recordRuntimeClosureCv(local,variable,candidate,candidate.runtimeEvaluationId)) {
                closureName=candidate.runtimeClosureInternalName;closureProducer=candidate.runtimeClosureProducerId;
            }
            candidate.ownershipEvalId = -1;
            if (!returnValue) candidate.runtimeEvaluationId = -1;
            if (!returnValue) writePopAll(1);
        } else if (local.isReference()) {
            String name = "assign";
            if (operator.isAsReference())
                name = "assignRef";
            if (!R.isKnown()) {
                stackPush(R);
                writePopBoxing();
                writePopImmutable();
                writePushVariable(variable);
                writeSysStaticCall(Memory.class, name + "Right", Memory.class, stackPeek().type.toClass(), Memory.class);
            } else {
                writePushVariable(variable);
                Memory tmp = tryWritePush(R);
                if (tmp != null) {
                    value = tmp;
                    writePushMemory(value);
                }
                writePopBoxing();
                writePopImmutable();
                writeSysDynamicCall(Memory.class, name, Memory.class, stackPeek().type.toClass());
            }
            if (!returnValue)
                writePopAll(1);
        } else {
            Memory result = tryWritePush(R);
            if (result != null) {
                writePushMemory(result);
                value = result;
            }
            writePopBoxing();
            writeVarStore(local, returnValue, true);
        }

        /*if (!methodStatement.variable(variable).isPassed())
            local.setValue(value);

        if (methodStatement.isDynamicLocal())   */
        local.setValue(null);
        if (closureName != null) local.setRuntimeClosureWitness(closureName,closureProducer);
    }

    private boolean writeRuntimeCloneCv(StackItem operand,Token token,OperatorExprToken operator,boolean returnValue) {
        if (!(token instanceof VariableExprToken)) return false;
        LocalVariable local=method.getLocalVariable(((VariableExprToken)token).getName());
        if (local == null || local.getRuntimeClosureInternalName() == null || local.getRuntimeClosureProducerId() < 0) return false;
        String closure=local.getRuntimeClosureInternalName();int producer=local.getRuntimeClosureProducerId();
        writePush(operand);writePopBoxing();
        int id=method.ownership().reserveRuntimeEval();method.ownership().runtimeProducer(id,"CLONE");
        LocalVariable raw=method.addLocalVariable("~runtimeCloneSource"+id,null,Memory.class);
        LocalVariable trace=method.addLocalVariable("~runtimeCloneTrace"+id,null,TraceInfo.class);
        code.add(new VarInsnNode(ASTORE,raw.index));stackPop();
        writePushTraceInfo(operator);code.add(new VarInsnNode(ASTORE,trace.index));stackPop();
        LabelNode start=new LabelNode(),fallback=new LabelNode(),join=new LabelNode();
        code.add(new VarInsnNode(ALOAD,method.ownership().runtimeFrameIndex()));code.add(new JumpInsnNode(IFNULL,fallback));code.add(start);
        code.add(new VarInsnNode(ALOAD,method.ownership().runtimeFrameIndex()));code.add(new LdcInsnNode(id));code.add(new LdcInsnNode(local.index));
        code.add(new VarInsnNode(ALOAD,raw.index));code.add(new VarInsnNode(ALOAD,method.getLocalVariable("~env").index));code.add(new VarInsnNode(ALOAD,trace.index));
        MethodInsnNode helper=new MethodInsnNode(INVOKESTATIC,"php/runtime/PhpOwnershipClosures","cloneLocalRuntime",
                "(Lphp/runtime/PhpRuntimeOwnership$Frame;IILphp/runtime/Memory;Lphp/runtime/env/Environment;Lphp/runtime/env/TraceInfo;)Lphp/runtime/Memory;",false);code.add(helper);
        code.add(new JumpInsnNode(GOTO,join));code.add(fallback);
        code.add(new InsnNode(ACONST_NULL));code.add(new LdcInsnNode(id));code.add(new VarInsnNode(ALOAD,raw.index));
        code.add(new VarInsnNode(ALOAD,method.getLocalVariable("~env").index));code.add(new VarInsnNode(ALOAD,trace.index));
        code.add(new MethodInsnNode(INVOKESTATIC,"php/runtime/PhpOwnershipClosures","cloneRuntime",
                "(Lphp/runtime/PhpRuntimeOwnership$Frame;ILphp/runtime/Memory;Lphp/runtime/env/Environment;Lphp/runtime/env/TraceInfo;)Lphp/runtime/Memory;",false));code.add(join);
        method.ownership().runtimeFallback(fallback,join);method.ownership().runtimeCloneSite(id,local.index,producer,closure,helper,start,fallback,join);
        stackPush(Memory.Type.REFERENCE);stackPeek().runtimeEvaluationId=id;
        stackPeek().runtimeClosureInternalName=closure;stackPeek().runtimeClosureProducerId=id;setStackPeekAsImmutable();
        if (!returnValue) writePopAll(1);return true;
    }

    /** Compiler provenance is recorded only at the actual nonreturned VALUE publication. */
    private boolean recordRuntimeClosureCv(LocalVariable local,VariableExprToken token,StackItem value,int eval) {
        if (eval < 0 || value.runtimeClosureProducerId != eval || value.runtimeClosureInternalName == null
                || token == null || methodStatement.variable(token).isReference() || methodStatement.variable(token).isUnstable()) return false;
        MethodInsnNode publication=method.ownership().runtimeAddressPublication(eval,local.index);
        if (publication == null) return false;
        method.ownership().runtimeClosureCv(eval,local.index,value.runtimeClosureInternalName,publication);
        local.setRuntimeClosureWitness(value.runtimeClosureInternalName,eval);return true;
    }

    void writeScalarOperator(StackItem L, StackItem.Type Lt,
                             StackItem R, StackItem.Type Rt,
                             OperatorExprToken operator,
                             Class operatorResult, String operatorName) {
        boolean isInvert = !R.isKnown();
        if (!R.isKnown() && !L.isKnown() && R.getLevel() > L.getLevel())
            isInvert = false;

        if (operator instanceof ConcatExprToken) {
            if (isInvert) {
                stackPush(R);
                writePopString();
                writePush(L, StackItem.Type.STRING);
                writeSysStaticCall(OperatorUtils.class, "concatRight", String.class, String.class, String.class);
            } else {
                writePush(L, StackItem.Type.STRING);
                writePush(R, StackItem.Type.STRING);
                writeSysDynamicCall(String.class, "concat", String.class, String.class);
            }
            return;
        } else if (operator instanceof PlusExprToken || operator instanceof MinusExprToken
                || operator instanceof MulExprToken) {
            if (operator instanceof MinusExprToken && isInvert) {
                // nothing
            } else if (Lt.isLikeNumber() && Rt.isLikeNumber()) {
                StackItem.Type cast = StackItem.Type.LONG;
                if (Lt.isLikeDouble() || Rt.isLikeDouble())
                    cast = StackItem.Type.DOUBLE;

                writePush(L, cast);
                writePush(R, cast);

                if (cast == StackItem.Type.LONG) {
                    writeSysStaticCall(OperatorUtils.class, operatorName, Memory.class, Long.TYPE, Long.TYPE);
                    return;
                }

                code.add(new InsnNode(CompilerUtils.getOperatorOpcode(operator, cast)));

                stackPop();
                stackPop();
                stackPush(null, cast);
                return;
            }
        }

        if (isInvert) {
            stackPush(R);
            writePopBoxing();
            writePush(L);
            if (operator.isSide())
                operatorName += "Right";

            writeSysDynamicCall(Memory.class, operatorName, operatorResult, stackPeek().type.toClass());
        } else {
            writePush(L);
            writePopBoxing();
            writePush(R);
            writeSysDynamicCall(Memory.class, operatorName, operatorResult, stackPeek().type.toClass());
        }
    }

    public void writePop(Class clazz, boolean boxing, boolean asImmutable) {
        if (clazz == String.class)
            writePopString();
        else if (clazz == Character.TYPE)
            writePopChar();
        else if (clazz == Boolean.TYPE)
            writePopBoolean();
        else if (clazz == Memory.class) {
            if (boxing)
                writePopBoxing(asImmutable);
        } else if (clazz == Double.TYPE)
            writePopDouble();
        else if (clazz == Float.TYPE)
            writePopFloat();
        else if (clazz == Long.TYPE)
            writePopLong();
        else if (clazz == Integer.TYPE || clazz == Byte.TYPE || clazz == Short.TYPE)
            writePopInteger();
        else
            throw new IllegalArgumentException("Cannot pop this class: " + clazz.getName());
    }

    public void writePopInteger() {
        writePopLong();
        code.add(new InsnNode(L2I));
        stackPop();
        stackPush(null, StackItem.Type.INT);
    }

    public void writePopFloat() {
        writePopDouble();
        code.add(new InsnNode(D2F));
        stackPop();
        stackPush(null, StackItem.Type.FLOAT);
    }

    public void writePopLong() {
        switch (stackPeek().type) {
            case STRING_VALUE:
                writeSysStaticCall(php.runtime.PhpByteStringValues.class,"toLong",long.class,Object.class);break;
            case LONG:
                break;
            case FLOAT: {
                code.add(new InsnNode(L2F));
                stackPop();
                stackPush(Memory.Type.INT);
            }
            break;
            case BYTE:
            case SHORT:
            case BOOL:
            case CHAR:
            case INT: {
                code.add(new InsnNode(I2L));
                stackPop();
                stackPush(Memory.Type.INT);
            }
            break;
            case DOUBLE: {
                code.add(new InsnNode(D2L));
                stackPop();
                stackPush(Memory.Type.INT);
            }
            break;
            case STRING: {
                writeSysStaticCall(StringMemory.class, "toNumeric", Memory.class, String.class);
                writeSysDynamicCall(Memory.class, "toLong", Long.TYPE);
            }
            break;
            default: {
                writeSysDynamicCall(Memory.class, "toLong", Long.TYPE);
            }
        }
    }

    public void writePopDouble() {
        switch (stackPeek().type) {
            case STRING_VALUE:
                writeSysStaticCall(php.runtime.PhpByteStringValues.class,"toDouble",double.class,Object.class);break;
            case DOUBLE:
                break;
            case BYTE:
            case SHORT:
            case BOOL:
            case CHAR:
            case INT: {
                code.add(new InsnNode(I2D));
                stackPop();
                stackPush(Memory.Type.DOUBLE);
            }
            break;
            case LONG: {
                code.add(new InsnNode(L2D));
                stackPop();
                stackPush(Memory.Type.DOUBLE);
            }
            break;
            case STRING: {
                writeSysStaticCall(StringMemory.class, "toNumeric", Memory.class, String.class);
                writeSysDynamicCall(Memory.class, "toDouble", Double.TYPE);
            }
            break;
            default: {
                writeSysDynamicCall(Memory.class, "toDouble", Double.TYPE);
            }
        }
    }

    public void writePopString() {
        StackItem.Type peek = stackPeek().type;
        switch (peek) {
            case STRING_VALUE:
                writeSysStaticCall(php.runtime.PhpByteStringValues.class,"toJavaString",String.class,Object.class);break;
            case STRING:
                break;
            default:
                if (peek.isConstant()) {
                    if (peek == StackItem.Type.BOOL)
                        writeSysStaticCall(Memory.class, "boolToString", String.class, peek.toClass());
                    else
                        writeSysStaticCall(String.class, "valueOf", String.class, peek.toClass());
                } else
                    writeSysDynamicCall(Memory.class, "toString", String.class);
        }
    }

    public void writePopChar() {
        StackItem.Type peek = stackPeek().type;
        if (peek == StackItem.Type.STRING_VALUE) {
            writeSysStaticCall(php.runtime.PhpByteStringValues.class,"toChar",char.class,Object.class);return;
        }
        if (peek == StackItem.Type.CHAR)
            return;

        if (peek.isConstant()) {
            writeSysStaticCall(OperatorUtils.class, "toChar", Character.TYPE, peek.toClass());
        } else {
            writePopBoxing();
            writeSysDynamicCall(Memory.class, "toChar", Character.TYPE);
        }
    }

    public void writePopBooleanAsObject() {
        StackItem.Type peek = stackPeek().type;
        writeSysStaticCall(TrueMemory.class, "valueOf", Memory.class, peek.toClass());
        setStackPeekAsImmutable();
    }

    public void writePopBoolean() {
        int runtimeEval=stackPeek().runtimeEvaluationId;
        StackItem.Type peek = stackPeek().type;
        switch (peek) {
            case STRING_VALUE:
                writeSysStaticCall(php.runtime.PhpByteStringValues.class,"toBoolean",boolean.class,Object.class);break;
            case BOOL:
                break;
            case BYTE:
            case INT:
            case SHORT:
            case CHAR:
            case LONG: {
                writeSysStaticCall(OperatorUtils.class, "toBoolean", Boolean.TYPE, peek.toClass());
            }
            break;
            case DOUBLE: {
                writeSysStaticCall(OperatorUtils.class, "toBoolean", Boolean.TYPE, Double.TYPE);
            }
            break;
            case STRING: {
                writeSysStaticCall(OperatorUtils.class, "toBoolean", Boolean.TYPE, String.class);
            }
            break;
            case REFERENCE: {
                writeSysDynamicCall(Memory.class, "toBoolean", Boolean.TYPE);
            }
            break;
        }
        releaseRuntimeEvaluation(runtimeEval,"POP");
    }

    public void writeDynamicAccessInfo(DynamicAccessExprToken dynamic, boolean addLowerName) {
        if (dynamic.getField() != null) {
            if (dynamic.getField() instanceof NameToken) {
                String name = ((NameToken) dynamic.getField()).getName();
                writePushString(name);
                if (addLowerName)
                    writePushString(name.toLowerCase());
            } else {
                writePush(dynamic.getField(), true, false);
                writePopString();
                if (addLowerName) {
                    writePushDupLowerCase();
                }
            }
        } else {
            writeExpression(dynamic.getFieldExpr(), true, false);
            writePopString();
            if (addLowerName) {
                writePushDupLowerCase();
            }
        }
        writePushEnv();
        writePushTraceInfo(dynamic);
    }

    public void writeDynamicAccessPrepare(DynamicAccessExprToken dynamic, boolean addLowerName) {
        if (stackEmpty(true))
            unexpectedToken(dynamic);

        StackItem o = stackPop();
        writePush(o);

        if (stackPeek().isConstant())
            unexpectedToken(dynamic);
        writePopBoxing();

        if (dynamic instanceof DynamicAccessAssignExprToken) {
            DynamicAccessAssignExprToken assignment=(DynamicAccessAssignExprToken) dynamic;
            if (assignment.getValue() != null) {
                boolean fieldBinding=assignment.getAssignOperator() instanceof AssignExprToken
                        && ((AssignExprToken) assignment.getAssignOperator()).isAsReference();
                if (fieldBinding) {
                    if (method.ownership().runtimeEnabled()) method.ownership().runtimeUnsupported("property-reference-field-binding");
                    runtimeFieldReferenceBindingDepth++;
                }
                try {writeExpression(assignment.getValue(), true, false);}
                finally {if (fieldBinding) runtimeFieldReferenceBindingDepth--;}
                writePopBoxing(true);
            }
        }

        writeDynamicAccessInfo(dynamic, addLowerName);
    }

    private void prepareRuntimeArrayVariable(VariableExprToken variable) {
        if (!method.ownership().runtimeEnabled()) return;
        LocalVariable local=method.getLocalVariable(variable.getName());
        if (local == null || local.isReference() || !local.isOwnershipLocal()) return;
        LabelNode end=new LabelNode();
        code.add(new VarInsnNode(ALOAD,method.ownership().runtimeFrameIndex()));code.add(new JumpInsnNode(IFNULL,end));
        code.add(new VarInsnNode(ALOAD,local.index));code.add(new MethodInsnNode(INVOKEVIRTUAL,"php/runtime/Memory","isArray","()Z",false));
        code.add(new JumpInsnNode(IFEQ,end));
        LocalVariable publication=method.addLocalVariable("~runtimeArrayWrite"+method.ownership().reserveRuntimeEval(),null,Object.class);
        code.add(new VarInsnNode(ALOAD,method.ownership().runtimeFrameIndex()));code.add(new LdcInsnNode(local.index));
        code.add(new MethodInsnNode(INVOKEVIRTUAL,"php/runtime/PhpRuntimeOwnership$Frame","prepareArrayWrite","(I)Lphp/runtime/PhpRuntimeOwnership$Publication;",false));
        code.add(new VarInsnNode(ASTORE,publication.index));code.add(new VarInsnNode(ALOAD,publication.index));
        code.add(new MethodInsnNode(INVOKEVIRTUAL,"php/runtime/PhpRuntimeOwnership$Publication","next","()Lphp/runtime/Memory;",false));
        code.add(new VarInsnNode(ASTORE,local.index));code.add(new VarInsnNode(ALOAD,publication.index));
        code.add(new MethodInsnNode(INVOKEVIRTUAL,"php/runtime/PhpRuntimeOwnership$Publication","afterLocalStore","()V",false));
        code.add(new VarInsnNode(ALOAD,publication.index));code.add(new MethodInsnNode(INVOKEVIRTUAL,"php/runtime/PhpRuntimeOwnership$Publication","commit","()V",false));
        code.add(end);
    }

    /** Named native-table reads publish a real VALUE/CELL RESULT, never an ARRAY view owner. */
    private boolean writeRuntimeGlobalRead(StackItem operand, ArrayGetExprToken operator) {
        if (!method.ownership().runtimeEnabled() || !(operand.getToken() instanceof VariableExprToken)
                || !"GLOBALS".equals(((VariableExprToken)operand.getToken()).getName())
                || (operator.getClass()!=ArrayGetExprToken.class && operator.getClass()!=ArrayGetRefExprToken.class)
                || operator.getParameters().size()!=1) return false;
        ExprStmtToken parameter=operator.getParameters().get(0);
        if (!parameter.isSingle()) return false;
        Token literal=parameter.getSingle();String key;
        if (literal instanceof StringExprToken && ((StringExprToken)literal).hasRawBytes()) return false;
        if (literal instanceof StringExprToken) key=((StringExprToken)literal).getValue();
        else if (literal instanceof IntegerExprToken && !((IntegerExprToken)literal).isBigValue())
            key=String.valueOf(((IntegerExprToken)literal).getValue());
        else return false;
        LocalVariable root=method.getLocalVariable("GLOBALS");
        if (root==null) return false;
        boolean cell=operator instanceof ArrayGetRefExprToken;
        int id=method.ownership().reserveRuntimeEval();method.ownership().runtimeProducer(id,"GLOBAL_RESULT");
        code.add(new VarInsnNode(ALOAD,method.ownership().runtimeFrameIndex()));stackPush(Memory.Type.REFERENCE);
        writePushConstInt(id);writePushConstInt(root.index);
        VarInsnNode read=new VarInsnNode(ALOAD,root.index);code.add(read);stackPush(Memory.Type.REFERENCE);
        writePushConstString(key);writePushConstBoolean(cell);writePushEnv();writePushTraceInfo(operator);
        writeSysStaticCall(php.runtime.PhpRuntimeOwnership.class,"globalRead",Memory.class,
                php.runtime.PhpRuntimeOwnership.Frame.class,int.class,int.class,Memory.class,String.class,
                boolean.class,Environment.class,TraceInfo.class);
        method.ownership().runtimeGlobalRead(id,root.index,key,cell,(MethodInsnNode)code.getLast(),read);
        stackPeek().runtimeEvaluationId=id;
        return true;
    }

    /** Reads a witnessed address as VALUE without promoting or exporting its CELL. */
    private boolean writeRuntimeConditionalLocal() {
        StackItem value=stackPeek();int localId=value.runtimeSourceLocalId;
        VarInsnNode read=value.runtimeSourceRead;
        if(value.runtimeEvaluationId>=0||localId<0||read==null||read!=code.getLast()||read.getOpcode()!=ALOAD) return false;
        LocalVariable local=method.getLocalVariables().values().stream().filter(candidate->candidate.index==localId).findFirst().orElse(null);
        if(local==null||!local.isReference()||!local.isOwnershipLocal()||"GLOBALS".equals(local.name))return false;
        int id=method.ownership().reserveRuntimeEval();method.ownership().runtimeProducer(id,"CV_READ_RESULT");
        code.remove(read);code.add(new VarInsnNode(ALOAD,method.ownership().runtimeFrameIndex()));
        code.add(new LdcInsnNode(id));code.add(new LdcInsnNode(localId));code.add(read);
        MethodInsnNode helper=new MethodInsnNode(INVOKESTATIC,"php/runtime/PhpRuntimeOwnership","conditionalLocal",
                "(Lphp/runtime/PhpRuntimeOwnership$Frame;IILphp/runtime/Memory;)Lphp/runtime/Memory;",false);code.add(helper);
        method.ownership().runtimeConditionalLocal(id,localId,helper,read);value.runtimeEvaluationId=id;return true;
    }

    /** Each selected arm transfers its actual producer into a single result root. */
    private void writeRuntimeConditional(ValueIfElseToken operator) {
        writePopBoxing();int source=stackPeek().runtimeEvaluationId;
        boolean retain=operator.getValue()==null;
        int kind=operator instanceof ValueNullCoalesceIfElseToken?0:1;
        int id=method.ownership().reserveRuntimeEval();method.ownership().runtimeProducer(id,"JOIN_RESULT");
        LocalVariable scratch=method.addLocalVariable("~runtimeConditional"+id,null,Memory.class);
        if(kind==0) {
            MethodInsnNode read=method.ownership().runtimeGlobalReadHelper(source);
            if(read!=null)method.ownership().runtimeQuietGlobalRead(source,read);
        }
        code.add(new VarInsnNode(ASTORE,scratch.index));stackPop();
        code.add(new VarInsnNode(ALOAD,method.ownership().runtimeFrameIndex()));code.add(new LdcInsnNode(id));
        code.add(new LdcInsnNode(source));code.add(new VarInsnNode(ALOAD,scratch.index));
        code.add(new InsnNode(kind==0?ICONST_0:ICONST_1));code.add(new InsnNode(retain?ICONST_1:ICONST_0));
        MethodInsnNode test=new MethodInsnNode(INVOKESTATIC,"php/runtime/PhpRuntimeOwnership","conditionalTest",
                "(Lphp/runtime/PhpRuntimeOwnership$Frame;IILphp/runtime/Memory;IZ)Z",false);code.add(test);
        LabelNode alternative=new LabelNode(),end=new LabelNode();
        JumpInsnNode branch=new JumpInsnNode(IFEQ,alternative);code.add(branch);
        int trueSource;
        if(retain) {
            code.add(new VarInsnNode(ALOAD,scratch.index));code.add(new VarInsnNode(ASTORE,scratch.index));trueSource=source;
        }else trueSource=writeRuntimeConditionalArm(operator.getValue(),scratch);
        MethodInsnNode yes=writeRuntimeConditionalResult(id,trueSource,scratch,true);
        JumpInsnNode jump=new JumpInsnNode(GOTO,end);code.add(jump);code.add(alternative);
        int falseSource=writeRuntimeConditionalArm(operator.getAlternative(),scratch);
        MethodInsnNode no=writeRuntimeConditionalResult(id,falseSource,scratch,false);code.add(end);
        if(trueSource>=-1&&falseSource>=-1)
            method.ownership().runtimeConditional(id,source,kind,retain,trueSource,falseSource,test,branch,yes,jump,no,alternative,end,scratch.index);
        else method.ownership().runtimeUnsupported("conditional-selected-arm-unlowered");
        stackPush(Memory.Type.REFERENCE);stackPeek().runtimeEvaluationId=id;
    }

    private int writeRuntimeConditionalArm(ExprStmtToken arm,LocalVariable scratch) {
        Token literal=arm.isSingle()?arm.getSingle():null;
        if(literal instanceof NullExprToken) {
            code.add(new FieldInsnNode(GETSTATIC,"php/runtime/Memory","NULL","Lphp/runtime/Memory;"));
            code.add(new VarInsnNode(ASTORE,scratch.index));return -1;
        }
        if(literal instanceof StringExprToken&&!((StringExprToken)literal).hasRawBytes()&&((StringExprToken)literal).getSegments().isEmpty()) {
            code.add(new LdcInsnNode(((StringExprToken)literal).getValue()));
            code.add(new MethodInsnNode(INVOKESTATIC,"php/runtime/memory/StringMemory","valueOf","(Ljava/lang/String;)Lphp/runtime/Memory;",false));
            code.add(new VarInsnNode(ASTORE,scratch.index));return -1;
        }
        writeExpression(arm,true,false);writePopBoxing();int source=stackPeek().runtimeEvaluationId;
        code.add(new VarInsnNode(ASTORE,scratch.index));stackPop();return source<0?-2:source;
    }

    private MethodInsnNode writeRuntimeConditionalResult(int id,int source,LocalVariable scratch,boolean trueArm) {
        code.add(new VarInsnNode(ALOAD,method.ownership().runtimeFrameIndex()));code.add(new LdcInsnNode(id));
        code.add(new LdcInsnNode(id));code.add(new LdcInsnNode(source));code.add(new VarInsnNode(ALOAD,scratch.index));
        code.add(new InsnNode(trueArm?ICONST_1:ICONST_0));
        MethodInsnNode result=new MethodInsnNode(INVOKESTATIC,"php/runtime/PhpRuntimeOwnership","conditionalResult",
                "(Lphp/runtime/PhpRuntimeOwnership$Frame;IIILphp/runtime/Memory;Z)Lphp/runtime/Memory;",false);code.add(result);return result;
    }

    private boolean writeRuntimeArrayIncrement(ArrayGetRefExprToken target,OperatorExprToken increment,boolean returned) {
        if (!method.ownership().runtimeEnabled() || !incrementArrayTargets.contains(target)
                || target.getParameters().size()!=1 || !(stackPeek().getToken() instanceof VariableExprToken)) return false;
        String name=((VariableExprToken)stackPeek().getToken()).getName();
        if ("GLOBALS".equals(name)) return false;
        LocalVariable root=method.getLocalVariable(name);
        if (root==null || !root.isReference() || !root.isOwnershipLocal()) return false;
        ExprStmtToken parameter=target.getParameters().get(0);if (!parameter.isSingle()) return false;
        Token literal=parameter.getSingle();String key;
        if (literal instanceof StringExprToken && ((StringExprToken)literal).hasRawBytes()) return false;
        if (literal instanceof StringExprToken) key=((StringExprToken)literal).getValue();
        else if (literal instanceof IntegerExprToken && !((IntegerExprToken)literal).isBigValue())
            key=String.valueOf(((IntegerExprToken)literal).getValue());
        else return false;
        stackPop();int id=method.ownership().reserveRuntimeEval();
        method.ownership().runtimeProducer(id,"ARRAY_INCREMENT_RESULT");writeLineNumber(increment);
        code.add(new VarInsnNode(ALOAD,method.ownership().runtimeFrameIndex()));stackPush(Memory.Type.REFERENCE);
        writePushConstInt(id);writePushConstInt(root.index);
        VarInsnNode read=new VarInsnNode(ALOAD,root.index);code.add(read);stackPush(Memory.Type.REFERENCE);
        boolean decrement=increment instanceof DecExprToken,post=increment.getAssociation()==Association.LEFT;
        writePushConstString(key);writePushConstBoolean(decrement);writePushConstBoolean(post);
        writePushEnv();writePushTraceInfo(parameter);writePushTraceInfo(parameter);
        writeSysStaticCall(php.runtime.PhpArithmetic.class,"arrayIncrement",Memory.class,
                php.runtime.PhpRuntimeOwnership.Frame.class,int.class,int.class,Memory.class,String.class,
                boolean.class,boolean.class,Environment.class,TraceInfo.class,TraceInfo.class);
        method.ownership().runtimeArrayIncrement(id,root.index,key,decrement,post,(MethodInsnNode)code.getLast(),read);
        stackPeek().runtimeEvaluationId=id;if (!returned) writePopAll(1);return true;
    }

    private boolean writeRuntimeArrayReference(StackItem operand,ArrayGetExprToken operator) {
        if (!method.ownership().runtimeEnabled() || !(operand.getToken() instanceof VariableExprToken)
                || operator.getClass()!=ArrayGetRefExprToken.class || !((ArrayGetRefExprToken)operator).isShortcut()
                || operator.getParameters().size()!=1) return false;
        String name=((VariableExprToken)operand.getToken()).getName();
        if ("GLOBALS".equals(name)) return false;
        LocalVariable root=method.getLocalVariable(name);
        if (root==null || !root.isReference() || !root.isOwnershipLocal()) return false;
        ExprStmtToken parameter=operator.getParameters().get(0);
        if (!parameter.isSingle()) return false;
        Token literal=parameter.getSingle();String key;
        if (literal instanceof StringExprToken && ((StringExprToken)literal).hasRawBytes()) return false;
        if (literal instanceof StringExprToken) key=((StringExprToken)literal).getValue();
        else if (literal instanceof IntegerExprToken && !((IntegerExprToken)literal).isBigValue())
            key=String.valueOf(((IntegerExprToken)literal).getValue());
        else return false;
        int id=method.ownership().reserveRuntimeEval();method.ownership().runtimeProducer(id,"ARRAY_REF_RESULT");
        code.add(new VarInsnNode(ALOAD,method.ownership().runtimeFrameIndex()));stackPush(Memory.Type.REFERENCE);
        writePushConstInt(id);writePushConstInt(root.index);
        VarInsnNode read=new VarInsnNode(ALOAD,root.index);code.add(read);stackPush(Memory.Type.REFERENCE);
        writePushConstString(key);writePushEnv();writePushTraceInfo(operator);
        writeSysStaticCall(php.runtime.PhpOwnershipArrays.class,"arrayReference",Memory.class,
                php.runtime.PhpRuntimeOwnership.Frame.class,int.class,int.class,Memory.class,String.class,Environment.class,TraceInfo.class);
        method.ownership().runtimeArrayReference(id,root.index,key,(MethodInsnNode)code.getLast(),read);
        stackPeek().runtimeEvaluationId=id;
        return true;
    }

    void writeArrayGet(ArrayGetExprToken operator, boolean returnValue) {
        StackItem o = stackPop();
        if (referenceArgumentFetches.containsKey(operator)) {
            if (o.getToken() instanceof VariableExprToken) writePushVariable((VariableExprToken) o.getToken());
            else writePush(o);
            writePopBoxing();
            for (ExprStmtToken key : operator.getParameters()) {
                writeExpression(key, true, false);
                writePopBoxing();
                writePushEnv();
                writePushTraceInfo(operator);
                writeArgumentReferenceMode(operator);
                writeSysStaticCall(PhpCallFrame.class, "argumentIndex", Memory.class,
                        Memory.class, Memory.class, Environment.class, TraceInfo.class, boolean.class);
            }
            return;
        }
        boolean incrementArray = incrementArrayTargets.contains(operator);
        if (incrementArray || operator instanceof ArrayGetRefExprToken && ((ArrayGetRefExprToken) operator).isArithmeticTarget()) {
            List<LocalVariable> preparedKeys=null;
            if (method.ownership().enabled() && o.getToken() instanceof VariableExprToken) {
                LocalVariable local=method.getLocalVariable(((VariableExprToken)o.getToken()).getName());
                if (local != null && !local.isReference()) {
                    preparedKeys=new ArrayList<>();
                    for(ExprStmtToken key:operator.getParameters()) {
                        writeExpression(key,true,false);writePopBoxing();
                        LocalVariable saved=method.addLocalVariable("~runtimeIndex"+method.ownership().reserveRuntimeEval(),null,Memory.class);
                        code.add(new VarInsnNode(ASTORE,saved.index));stackPop();preparedKeys.add(saved);
                    }
                    prepareRuntimeArrayVariable((VariableExprToken)o.getToken());
                }
            }
            if (o.getToken() instanceof VariableExprToken) writePushVariable((VariableExprToken) o.getToken());
            else writePush(o);
            writePopBoxing();
            writePushEnv();
            writePushTraceInfo(operator);
            writeSysStaticCall(php.runtime.PhpArithmetic.class, incrementArray ? "arrayIncrementTarget" : "arrayTarget",
                    php.runtime.PhpArithmetic.ArrayTarget.class,
                    Memory.class, Environment.class, TraceInfo.class);
            int keyIndex=0;
            for (ExprStmtToken key : operator.getParameters()) {
                if (preparedKeys == null) {writeExpression(key, true, false);writePopBoxing();}
                else writeVarLoad(preparedKeys.get(keyIndex++));
                writeSysDynamicCall(php.runtime.PhpArithmetic.ArrayTarget.class, "index", php.runtime.PhpArithmetic.ArrayTarget.class, Memory.class);
            }
            return;
        }
        if (writeRuntimeGlobalRead(o,operator)) return;
        if (writeRuntimeArrayReference(o,operator)) return;
        writePush(o);
        writePopBoxing();

        String methodName = operator instanceof ArrayGetRefExprToken ? "refOfIndex" : "valueOfIndex";
        boolean isShortcut = operator instanceof ArrayGetRefExprToken && ((ArrayGetRefExprToken) operator).isShortcut();
        int i = 0;
        int size = operator.getParameters().size();

        int traceIndex = createTraceInfo(operator);
        for (ExprStmtToken param : operator.getParameters()) {
            writePushTraceInfo(traceIndex); // push dup trace
            writeExpression(param, true, false);
            if (i == 0 && method.ownership().enabled() && o.getToken() instanceof VariableExprToken
                    && (operator instanceof ArrayGetRefExprToken || operator instanceof ArrayGetUnsetExprToken)) {
                LocalVariable local=method.getLocalVariable(((VariableExprToken)o.getToken()).getName());
                if (local != null && !local.isReference()) {
                    writePopBoxing();
                    LocalVariable key=method.addLocalVariable("~runtimeWriteKey"+method.ownership().reserveRuntimeEval(),null,Memory.class);
                    LocalVariable trace=method.addLocalVariable("~runtimeWriteTrace"+method.ownership().reserveRuntimeEval(),null,TraceInfo.class);
                    LabelNode end=new LabelNode();
                    code.add(new VarInsnNode(ALOAD,method.ownership().runtimeFrameIndex()));code.add(new JumpInsnNode(IFNULL,end));
                    code.add(new VarInsnNode(ASTORE,key.index));code.add(new VarInsnNode(ASTORE,trace.index));code.add(new InsnNode(POP));
                    prepareRuntimeArrayVariable((VariableExprToken)o.getToken());
                    code.add(new VarInsnNode(ALOAD,local.index));code.add(new VarInsnNode(ALOAD,trace.index));code.add(new VarInsnNode(ALOAD,key.index));code.add(end);
                }
            }
            if (operator instanceof ArrayGetUnsetExprToken && i == size - 1) {
                writePopBoxing();
                writeSysDynamicCall(Memory.class, "unsetOfIndex", void.class, TraceInfo.class, stackPeek().type.toClass());
                if (returnValue)
                    writePushNull();
            } else if (operator instanceof ArrayGetIssetExprToken && i == size - 1) {
                writePopBoxing();
                if (php8CallFrame()) {
                    writePushEnv();
                    writeSysStaticCall(php.runtime.PhpStringOffsets.class,"isset",Memory.class,
                            Memory.class,TraceInfo.class,Memory.class,Environment.class);
                    i++;continue;
                }
                writeSysDynamicCall(Memory.class, "issetOfIndex", Memory.class, TraceInfo.class, stackPeek().type.toClass());
            } else if (operator instanceof ArrayGetEmptyExprToken && i == size - 1) {
                writePopBoxing();
                if (php8CallFrame()) {
                    writePushEnv();
                    writeSysStaticCall(php.runtime.PhpStringOffsets.class,"empty",Memory.class,
                            Memory.class,TraceInfo.class,Memory.class,Environment.class);
                    i++;continue;
                }
                writeSysDynamicCall(Memory.class, "emptyOfIndex", Memory.class, TraceInfo.class, stackPeek().type.toClass());
            } else {
                if (getCompiler().getModule().getPhpLanguageProfile() == php.runtime.common.PhpLanguageProfile.PHP_8_5) {
                    writePopBoxing();
                    writePushEnv();
                    writeSysStaticCall(php.runtime.PhpStringOffsets.class,
                            operator instanceof ArrayGetRefExprToken ? (isShortcut && i == size - 1 ? "shortcut" : "reference") : "read",
                            Memory.class, Memory.class, TraceInfo.class, Memory.class, Environment.class);
                    i++;
                    continue;
                }
                // TODO: Remove.
                // PHP CMP: $hack = &$arr[0];
                // boolean isMemory = stackPeek().type.toClass() == Memory.class;
                //if (isMemory)
                /*if (compiler.isPhpMode()){
                    if (i == size - 1 && isShortcut){
                        writePopBoxing();
                        writeSysDynamicCall(Memory.class, methodName + "AsShortcut", Memory.class, TraceInfo.class, Memory.class);
                        continue;
                    }
                }*/

                if (stackPeek().type==StackItem.Type.STRING_VALUE) writePopBoxing();
                writeSysDynamicCall(Memory.class, methodName, Memory.class, TraceInfo.class, stackPeek().type.toClass());
                i++;
            }
        }
    }

    Memory writeUnaryOperator(OperatorExprToken operator, boolean returnValue, boolean writeOpcode) {
        if (stackEmpty(true))
            unexpectedToken(operator);

        StackItem o = stackPop();
        ValueExprToken L = o.getToken();

        Memory mem = tryWritePush(o, false, false, true);
        StackItem.Type type = tryGetType(o);

        boolean modernIncrement = php8CallFrame()
                && (operator instanceof IncExprToken || operator instanceof DecExprToken);
        if (mem != null && !modernIncrement) {
            Memory result = CompilerUtils.calcUnary(getCompiler().getEnvironment(), operator.toTraceInfo(getCompiler().getContext()), mem, operator,
                    getCompiler().getModule().getPhpLanguageProfile());

            if (operator instanceof ValueIfElseToken) {
                ValueIfElseToken valueIfElseToken = (ValueIfElseToken) operator;
                ExprStmtToken ret = valueIfElseToken.getValue();
                if (mem.toBoolean()) {
                    if (ret == null)
                        result = mem;
                    else
                        result = writeExpression(ret, true, true, false);
                } else {
                    result = writeExpression(valueIfElseToken.getAlternative(), true, true, false);
                }
            } else if (!writeOpcode && php8CallFrame() && method.isInitializer()
                    && operator.getClass() == ArrayGetExprToken.class) {
                Memory array = mem;
                for (ExprStmtToken expr : ((ArrayGetExprToken) operator).getParameters()) {
                    Memory key = tryCalculateExpression(expr);
                    if (key == null || !(key.getRealType() == Memory.Type.INT || key.isString()) || !array.isArray()) {array = null; break;}
                    array = array.toValue(ArrayMemory.class).get(key);
                    if (array == null) break;
                }
                if (array != null) result = array.toImmutable();
            }

            if (result != null) {
                stackPush(result);
                setStackPeekAsImmutable();
                return result;
            }
        }

        if (!writeOpcode)
            return null;

        writeLineNumber(operator);

        String name = operator.getCode();
        Class operatorResult = operator.getResultClass();

        LocalVariable variable = null;
        if (L instanceof VariableExprToken) {
            variable = method.getLocalVariable(((VariableExprToken) L).getName());
            if (operator instanceof ArrayPushExprToken || operator instanceof ArrayGetRefExprToken)
                variable.setValue(null);
        }

        if (operator instanceof IncExprToken || operator instanceof DecExprToken) {
            if (modernIncrement && method.ownership().runtimeEnabled() && variable!=null && variable.isReference()
                    && variable.isOwnershipLocal() && !"GLOBALS".equals(variable.name)) {
                int id=method.ownership().reserveRuntimeEval();method.ownership().runtimeProducer(id,"LOCAL_INCREMENT_RESULT");
                code.add(new VarInsnNode(ALOAD,method.ownership().runtimeFrameIndex()));stackPush(Memory.Type.REFERENCE);
                writePushConstInt(id);writePushConstInt(variable.index);
                VarInsnNode read=new VarInsnNode(ALOAD,variable.index);code.add(read);stackPush(Memory.Type.REFERENCE);
                boolean decrement=operator instanceof DecExprToken,post=operator.getAssociation()==Association.LEFT;
                writePushConstBoolean(decrement);writePushConstBoolean(post);writePushEnv();writePushTraceInfo(operator);
                writeSysStaticCall(php.runtime.PhpArithmetic.class,"localIncrement",Memory.class,
                        php.runtime.PhpRuntimeOwnership.Frame.class,int.class,int.class,Memory.class,boolean.class,boolean.class,Environment.class,TraceInfo.class);
                method.ownership().runtimeLocalIncrement(id,variable.index,decrement,post,(MethodInsnNode)code.getLast(),read);
                stackPeek().runtimeEvaluationId=id;if(!returnValue)writePopAll(1);
            } else if (modernIncrement) {
                writePush(o);
                if (stackPeek().type.isConstant()) unexpectedToken(operator);
                writePopBoxing();
                boolean post = operator.getAssociation() == Association.LEFT;
                if (variable == null || variable.isReference()) {
                    writePushConstBoolean(operator instanceof DecExprToken);
                    writePushConstBoolean(post);
                    writePushEnv();
                    writePushTraceInfo(operator);
                    writeSysStaticCall(php.runtime.PhpArithmetic.class, "incrementTarget", Memory.class,
                            Memory.class, boolean.class, boolean.class, Environment.class, TraceInfo.class);
                    if (!returnValue) writePopAll(1);
                } else {
                    if (post && returnValue) {
                        writePushDup();
                        writePopImmutable();
                        code.add(new InsnNode(SWAP));
                    }
                    writePushConstBoolean(operator instanceof DecExprToken);
                    writePushEnv();
                    writePushTraceInfo(operator);
                    writeSysStaticCall(php.runtime.PhpArithmetic.class, "increment", Memory.class,
                            Memory.class, boolean.class, Environment.class, TraceInfo.class);
                    writeVarStore(variable, !post && returnValue);
                }
                if (variable != null) variable.setValue(null);
            } else if (variable == null || variable.isReference()) {
                if (operator.getAssociation() == Association.LEFT && returnValue) {
                    writePush(o);
                    if (stackPeek().type.isConstant())
                        unexpectedToken(operator);

                    writePushDup();
                    writePopImmutable();
                    code.add(new InsnNode(SWAP));
                    writePushDup();
                } else {
                    writePush(o);
                    if (stackPeek().type.isConstant())
                        unexpectedToken(operator);

                    writePushDup();
                }
                writeSysDynamicCall(Memory.class, name, operatorResult);
                writeSysDynamicCall(Memory.class, "assign", Memory.class, operatorResult);
                if (!returnValue || operator.getAssociation() == Association.LEFT) {
                    writePopAll(1);
                }
            } else {
                writePush(o);
                if (stackPeek().type.isConstant())
                    unexpectedToken(operator);

                if (operator.getAssociation() == Association.LEFT && returnValue) {
                    writeVarLoad(variable);
                }

                writeSysDynamicCall(Memory.class, name, operatorResult);
                variable.setValue(null); // TODO for constant values
                if (operator.getAssociation() == Association.RIGHT)
                    writeVarStore(variable, returnValue);
                else {
                    writeVarStore(variable, false);
                }
            }
        } else if (operator instanceof CloneExprToken && method.ownership().runtimeEnabled()
                && writeRuntimeCloneCv(o,L,operator,returnValue)) {
            // The exact CV producer and cloned result were published by the guarded helper.
        } else if (operator instanceof CloneExprToken && method.ownership().runtimeEnabled()) {
            writePush(o);writePopBoxing();
            int sourceEval=stackPeek().runtimeEvaluationId;
            int resultEval=method.ownership().reserveRuntimeEval();
            method.ownership().runtimeProducer(resultEval,"CLONE");
            if (!(L instanceof ClosureStmtToken))
                method.ownership().runtimeUnsupported("clone-source-without-certified-closure-provenance");
            LocalVariable source=method.addLocalVariable("~runtimeCloneSource"+resultEval,null,Memory.class);
            code.add(new VarInsnNode(ASTORE,source.index));
            code.add(new VarInsnNode(ALOAD,method.ownership().runtimeFrameIndex()));code.add(new LdcInsnNode(resultEval));
            code.add(new VarInsnNode(ALOAD,source.index));writePushEnv();writePushTraceInfo(operator);
            code.add(new MethodInsnNode(INVOKESTATIC,"php/runtime/PhpOwnershipClosures","cloneRuntime",
                    "(Lphp/runtime/PhpRuntimeOwnership$Frame;ILphp/runtime/Memory;Lphp/runtime/env/Environment;Lphp/runtime/env/TraceInfo;)Lphp/runtime/Memory;",false));
            stackPop();stackPop();stackPeek().runtimeEvaluationId=resultEval;setStackPeekAsImmutable();
            releaseRuntimeEvaluation(sourceEval,"POP");
            if (!returnValue) writePopAll(1);
        } else if (operator instanceof AmpersandRefToken) {
            writePush(o, false, false);
            if (compiler.getModule().getPhpLanguageProfile() == php.runtime.common.PhpLanguageProfile.PHP_8_5) {
                writePopBoxing(false);
                int runtimeEval=stackPeek().runtimeEvaluationId,activeEval=stackPeek().ownershipEvalId;
                int sourceLocal=stackPeek().runtimeSourceLocalId;
                VarInsnNode sourceRead=stackPeek().runtimeSourceRead;
                writeSysStaticCall(ReferenceMemory.class, "exportReference", Memory.class, Memory.class);
                stackPeek().runtimeEvaluationId=runtimeEval;stackPeek().ownershipEvalId=activeEval;
                stackPeek().runtimeSourceLocalId=sourceLocal;
                stackPeek().runtimeSourceRead=sourceRead;
            }
            setStackPeekAsImmutable();
            Token token = o.getToken();
            if (token instanceof VariableExprToken) {
                LocalVariable local = method.getLocalVariable(((VariableExprToken) token).getName());
                local.setValue(null);
            }

        } else if (operator instanceof SilentToken) {
            writePushEnv();
            writeSysDynamicCall(Environment.class, "__pushSilent", void.class);

            writePush(o);

            writePushEnv();
            writeSysDynamicCall(Environment.class, "__popSilent", void.class);

        } else if (operator instanceof ValueIfElseToken) {
            boolean quiet = operator instanceof ValueNullCoalesceIfElseToken && o.getToken() instanceof VariableExprToken
                    && ((VariableExprToken) o.getToken()).getName().equals("this") && quietThisReads.add(o.getToken());
            try {writePush(o);} finally {if (quiet) quietThisReads.remove(o.getToken());}
            ValueIfElseToken valueIfElseToken = (ValueIfElseToken) operator;
            if(method.ownership().runtimeEnabled())writeRuntimeConditionalLocal();
            if(method.ownership().runtimeEnabled()&&stackPeek().runtimeEvaluationId>=0) {
                writeRuntimeConditional(valueIfElseToken);setStackPeekAsImmutable(false);return null;
            }
            if(method.ownership().runtimeEnabled())method.ownership().runtimeUnsupported("conditional-test-source-unlowered");

            LabelNode end = new LabelNode();
            LabelNode elseL = new LabelNode();

            if (valueIfElseToken.getValue() == null) {
                StackItem.Type dup = stackPeek().type;

                writePushDup();

                if (operator instanceof ValueNullCoalesceIfElseToken) {
                    writePopBoxing();
                    writeSysDynamicCall(Memory.class, "isNotNull", Boolean.TYPE);
                } else {
                    writePopBoolean();
                }

                code.add(new JumpInsnNode(Opcodes.IFEQ, elseL));
                stackPop();

                writePopBoxing();
                stackPop();

                code.add(new JumpInsnNode(Opcodes.GOTO, end));
                code.add(elseL);
                makePop(dup); // remove duplicate of condition value , IMPORTANT!!!

                writeExpression(valueIfElseToken.getAlternative(), true, false);
                writePopBoxing();

                code.add(end);
            } else {
                writePopBoolean();

                code.add(new JumpInsnNode(Opcodes.IFEQ, elseL));
                stackPop();
                writeExpression(valueIfElseToken.getValue(), true, false);

                writePopBoxing();
                stackPop();

                code.add(new JumpInsnNode(Opcodes.GOTO, end)); // goto end

                // else
                code.add(elseL);
                writeExpression(valueIfElseToken.getAlternative(), true, false);
                writePopBoxing();
                code.add(end);
            }

            setStackPeekAsImmutable(false);
        } else if (operator instanceof ArrayGetExprToken) {
            stackPush(o);
            writeArrayGet((ArrayGetExprToken) operator, returnValue);
        } else if (operator instanceof CallOperatorToken) {
            if (php8CallFrame()) writePush(o);
            else stackPush(o);

            CallOperatorToken call = (CallOperatorToken) operator;
            if (php8CallFrame()) {
                writeCallableFrame(call.getParameters(), operator, returnValue,call.isFirstClassCallable());
            } else {
                writePushParameters(call.getParameters());
                writePushEnv();
                writePushTraceInfo(operator);
                writeSysStaticCall(
                        InvokeHelper.class, "callAny", Memory.class,
                        Memory.class, Memory[].class, Environment.class, TraceInfo.class
                );
                if (!returnValue) writePopAll(1);
            }
        } else {
            writePush(o);
            writePopBoxing();

            if (operator instanceof org.develnext.jphp.core.tokenizer.token.expr.operator.cast.VoidCastExprToken && php8CallFrame()) {
                writePopAll(1);
                if (returnValue) writePushNull();
                return null;
            }

            if (operator instanceof org.develnext.jphp.core.tokenizer.token.expr.operator.cast.ObjectCastExprToken
                    && stackPeek().runtimeEvaluationId>=0 && method.ownership().runtimeArrayCastSource(stackPeek().runtimeEvaluationId)) {
                int source=stackPeek().runtimeEvaluationId,result=method.ownership().reserveRuntimeEval();
                method.ownership().runtimeProducer(result,"OBJECT_CAST_RESULT");
                code.add(new VarInsnNode(ALOAD,method.ownership().runtimeFrameIndex()));stackPush(Memory.Type.REFERENCE);
                writePushConstInt(result);writePushConstInt(source);writePushEnv();
                writeSysStaticCall(php.runtime.PhpRuntimeOwnership.class,"objectCast",Memory.class,
                        Memory.class,php.runtime.PhpRuntimeOwnership.Frame.class,int.class,int.class,Environment.class);
                method.ownership().runtimeObjectCast(result,source,(MethodInsnNode)code.getLast());
                method.ownership().runtimeConsumer(source,"CAST");stackPeek().runtimeEvaluationId=result;
            } else if (operator instanceof org.develnext.jphp.core.tokenizer.token.expr.operator.cast.StringCastExprToken && php8CallFrame()) {
                writePushEnv();writePushTraceInfo(operator);
                writeSysStaticCall(php.runtime.PhpByteStrings.class,"stringValue",Memory.class,Memory.class,Environment.class,TraceInfo.class);
            } else if (operator instanceof UnaryNumericExprToken) {
                writePushConstBoolean(((UnaryNumericExprToken) operator).isNegative());
                writePushEnv();
                writePushTraceInfo(operator);
                writeSysStaticCall(php.runtime.PhpArithmetic.class, "unary", Memory.class,
                        Memory.class, boolean.class, Environment.class, TraceInfo.class);
            } else if (getCompiler().getModule().getPhpLanguageProfile() == php.runtime.common.PhpLanguageProfile.PHP_8_5
                    && (operator instanceof org.develnext.jphp.core.tokenizer.token.expr.operator.cast.IntCastExprToken
                    || operator instanceof org.develnext.jphp.core.tokenizer.token.expr.operator.cast.DoubleCastExprToken)) {
                writePushEnv();
                writePushTraceInfo(operator);
                writeSysStaticCall(php.runtime.PhpNumericConversions.class, name, operatorResult,
                        Memory.class, Environment.class, TraceInfo.class);
            } else if (operator instanceof ArrayPushExprToken
                    && compiler.getModule().getPhpLanguageProfile() == php.runtime.common.PhpLanguageProfile.PHP_8_5) {
                writePushEnv();
                writePushTraceInfo(operator);
                if (writeArgumentReferenceMode(operator))
                    writeSysStaticCall(PhpCallFrame.class, "argumentAppend", Memory.class,
                            Memory.class, Environment.class, TraceInfo.class, boolean.class);
                else writeSysStaticCall(ArrayMemory.class, "appendReferencePhp8", Memory.class,
                        Memory.class, Environment.class, TraceInfo.class);
            } else if (operator.isEnvironmentNeeded() && operator.isTraceNeeded()) {
                writePushEnv();
                writePushTraceInfo(operator);
                writeSysDynamicCall(Memory.class, name, operatorResult, Environment.class, TraceInfo.class);
            } else if (operator.isEnvironmentNeeded()) {
                writePushEnv();
                writeSysDynamicCall(Memory.class, name, operatorResult, Environment.class);
            } else if (operator.isTraceNeeded()) {
                writePushTraceInfo(operator);
                writeSysDynamicCall(Memory.class, name, operatorResult, TraceInfo.class);
            } else
                writeSysDynamicCall(Memory.class, name, operatorResult);

            if (!returnValue) {
                writePopAll(1);
            }
        }

        return null;
    }

    private Memory writePipeOperator(PipeExprToken operator, boolean returnValue, boolean writeOpcode) {
        if (stackEmpty(true)) unexpectedToken(operator);
        if (!writeOpcode) return null; // A pipe invokes PHP and is never a constant calculation.
        writeLineNumber(operator);
        StackItem left = stackPop();
        writePush(left); writePopBoxing(); writePopImmutable();
        // Capture the LHS before any RHS expression can mutate its source.
        writeExpression(operator.getRightValue(), true, false); writePopBoxing();
        writePushEnv(); writePushTraceInfo(operator);
        writeSysStaticCall(php.runtime.invoke.PhpPipe.class, "apply", Memory.class,
                Memory.class, Memory.class, Environment.class, TraceInfo.class);
        setStackPeekAsImmutable();
        if (!returnValue) writePopAll(1);
        return null;
    }

    Memory writeLogicOperator(LogicOperatorExprToken operator, boolean returnValue, boolean writeOpcode) {
        if (stackEmpty(true))
            unexpectedToken(operator);

        if (!writeOpcode) {
            StackItem top = stackPeek();
            Memory one = tryWritePush(top, false, false, true);
            if (one == null)
                return null;

            Memory two = writeExpression(operator.getRightValue(), true, true, false);
            if (two == null)
                return null;

            stackPop();

            Memory r = operator.calc(getCompiler().getEnvironment(), operator.toTraceInfo(getCompiler().getContext()), one, two);

            stackPush(r);
            return r;
        }

        writeLineNumber(operator);

        StackItem o = stackPop();

        writePush(o);
        writePopBoolean();

        LabelNode end = new LabelNode();
        LabelNode next = new LabelNode();

        if (operator instanceof BooleanOrExprToken || operator instanceof BooleanOr2ExprToken) {
            code.add(new JumpInsnNode(IFEQ, next));
            stackPop();
            if (returnValue) {
                writePushBooleanAsMemory(true);
                stackPop();
            }
        } else if (operator instanceof BooleanAndExprToken || operator instanceof BooleanAnd2ExprToken) {
            code.add(new JumpInsnNode(IFNE, next));
            stackPop();
            if (returnValue) {
                writePushBooleanAsMemory(false);
                stackPop();
            }
        }

        code.add(new JumpInsnNode(GOTO, end));

        code.add(next);
        writeExpression(operator.getRightValue(), returnValue, false);
        if (returnValue) {
            if (operator.getLast() instanceof ValueIfElseToken)
                writePopBoxing();
            else
                writePopBooleanAsObject();
        }

        code.add(end);
        return null;
    }

    /** Marks the finished Java String / immutable-byte carrier without claiming it is Memory. */
    public void markStringValue() {
        stackPop();stackPush(null,StackItem.Type.STRING_VALUE);setStackPeekAsImmutable();
    }

    private Class byteConcatOperandClass() {
        StackItem.Type type=stackPeek().type;
        if(type==StackItem.Type.FLOAT){writePopDouble();return double.class;}
        if(type==StackItem.Type.INT || type==StackItem.Type.SHORT || type==StackItem.Type.BYTE){writePopLong();return long.class;}
        if(type==StackItem.Type.LONG)return long.class;
        if(type==StackItem.Type.DOUBLE)return double.class;
        if(type==StackItem.Type.BOOL)return boolean.class;
        if(type==StackItem.Type.CHAR)return char.class;
        return Object.class;
    }

    private void writeByteConcatOperands(StackItem left,StackItem right,Token token,boolean address) {
        // Save evaluated operands before the first coercion. Reference operands remain live.
        LocalVariable savedRight=null;Class rightClass=null;StackItem rightItem=null;
        if(!right.isKnown()) {
            stackPush(right);rightClass=byteConcatOperandClass();rightItem=stackPop();
            savedRight=method.addLocalVariable("~byteConcatRight"+method.nextStatementIndex(Object.class),null,rightClass);
            code.add(new VarInsnNode(Type.getType(rightClass).getOpcode(ISTORE),savedRight.index));
        }
        writePush(left);Class leftClass=byteConcatOperandClass();StackItem leftItem=stackPop();
        LocalVariable savedLeft=method.addLocalVariable("~byteConcatLeft"+method.nextStatementIndex(Object.class),null,leftClass);
        code.add(new VarInsnNode(Type.getType(leftClass).getOpcode(ISTORE),savedLeft.index));
        if(savedRight==null) {
            writePush(right);rightClass=byteConcatOperandClass();rightItem=stackPop();
            savedRight=method.addLocalVariable("~byteConcatRight"+method.nextStatementIndex(Object.class),null,rightClass);
            code.add(new VarInsnNode(Type.getType(rightClass).getOpcode(ISTORE),savedRight.index));
        }
        code.add(new VarInsnNode(Type.getType(leftClass).getOpcode(ILOAD),savedLeft.index));stackPush(leftItem);
        code.add(new VarInsnNode(Type.getType(rightClass).getOpcode(ILOAD),savedRight.index));stackPush(rightItem);
        writePushEnv();writePushTraceInfo(token);
        writeSysStaticCall(php.runtime.PhpByteStringValues.class,address ? "assign" : "concat",address ? Memory.class : Object.class,
                address ? Memory.class : leftClass,rightClass,Environment.class,TraceInfo.class);
        if(!address)markStringValue();else setStackPeekAsImmutable();
    }

    private void writeBoxedBinaryOperands(StackItem left, StackItem right, boolean immutableRight) {
        if (right.isKnown()) {
            writePush(left);
            writePopBoxing();
            writePush(right);
            writePopBoxing();
            if (immutableRight) writePopImmutable();
        } else {
            stackPush(right);
            writePopBoxing();
            if (immutableRight) writePopImmutable();
            StackItem boxedRight = stackPop();
            if (left.isKnown()) {
                writePush(left);
            } else {
                // Both operands already exist on the JVM stack. Move the boxed
                // right operand below the left before boxing a category-2 value.
                if (left.size == 2) {
                    code.add(new InsnNode(DUP_X2));
                    code.add(new InsnNode(POP));
                } else {
                    code.add(new InsnNode(SWAP));
                }
                stackPush(left);
            }
            writePopBoxing();
            code.add(new InsnNode(SWAP));
            stackPush(boxedRight);
        }
    }

    private void writeRuntimeComparison(StackItem left,StackItem right,String name,OperatorExprToken operator) {
        writeBoxedBinaryOperands(left,right,false);
        StackItem rightValue=stackPop(),leftValue=stackPop();
        int id=method.ownership().reserveRuntimeEval();
        LocalVariable rightRaw=method.addLocalVariable("~comparisonRight"+id,null,Memory.class);
        LocalVariable leftRaw=method.addLocalVariable("~comparisonLeft"+id,null,Memory.class);
        code.add(new VarInsnNode(ASTORE,rightRaw.index));code.add(new VarInsnNode(ASTORE,leftRaw.index));
        Method selected;
        try {selected=php.runtime.PhpComparisonCalls.class.getMethod(name,Memory.class,Memory.class,Environment.class,TraceInfo.class);}
        catch(java.lang.NoSuchMethodException absent){throw new IllegalStateException("Comparison BODY disappeared",absent);}
        LabelNode managed=new LabelNode(),joined=new LabelNode();
        code.add(new VarInsnNode(ALOAD,method.ownership().runtimeFrameIndex()));code.add(new JumpInsnNode(IFNONNULL,managed));
        code.add(new VarInsnNode(ALOAD,leftRaw.index));stackPush(Memory.Type.REFERENCE);
        code.add(new VarInsnNode(ALOAD,rightRaw.index));stackPush(Memory.Type.REFERENCE);
        Method direct=selected;
        if(selected.getReturnType()==Boolean.TYPE)try{direct=php.runtime.PhpComparison.class.getMethod(name,Memory.class,Memory.class,Environment.class,TraceInfo.class);}catch(java.lang.NoSuchMethodException absent){throw new IllegalStateException("Ordinary comparison BODY disappeared",absent);}
        writePushEnv();writePushTraceInfo(operator);writePushStaticCall(direct);
        MethodInsnNode ordinary=(MethodInsnNode)code.getLast();
        stackPop();code.add(new JumpInsnNode(GOTO,joined));code.add(managed);
        LocalVariable packet=beginRuntimeNativePacket(new CompileFunction.Method(selected,2,false),2,id);
        method.ownership().runtimeComparisonFallback(id,ordinary,null);
        code.add(new VarInsnNode(ALOAD,leftRaw.index));stackPush(Memory.Type.REFERENCE);
        sendRuntimeNativeBorrow(packet,0,leftValue.runtimeEvaluationId,id,false,false);
        code.add(new VarInsnNode(ALOAD,rightRaw.index));stackPush(Memory.Type.REFERENCE);
        sendRuntimeNativeBorrow(packet,1,rightValue.runtimeEvaluationId,id,false,false);
        writePushEnv();writePushTraceInfo(operator);writeRuntimeNativeBody(selected,packet,id);finishRuntimeNative(packet,id);
        if(selected.getReturnType()==Boolean.TYPE){writeSysDynamicCall(Memory.class,"toBoolean",Boolean.TYPE);stackPeek().runtimeEvaluationId=id;}
        code.add(joined);
    }

    private void writeNumericBinaryOperands(StackItem left, StackItem right, StackItem.Type type) {
        if (right.isKnown()) {
            writePush(left, type);
            writePush(right, type);
        } else {
            stackPush(right);
            writePop(type.toClass(), false, false);
            StackItem convertedRight = stackPop();
            if (left.isKnown()) {
                writePush(left, type);
            } else {
                // Move the converted category-2 RHS below the original LHS,
                // then convert the LHS while it is at the top of the JVM stack.
                code.add(new InsnNode(left.size == 2 ? DUP2_X2 : DUP2_X1));
                code.add(new InsnNode(POP2));
                stackPush(left);
                writePop(type.toClass(), false, false);
            }
            code.add(new InsnNode(DUP2_X2));
            code.add(new InsnNode(POP2));
            stackPush(convertedRight);
        }
    }

    Memory writeOperator(OperatorExprToken operator, boolean returnValue, boolean writeOpcode) {
        if (writeOpcode) {
            BaseExprCompiler compiler = (BaseExprCompiler) getCompiler(operator.getClass());
            if (compiler != null) {
                if (writeOpcode) {
                    writeLineNumber(operator);
                }

                compiler.write(operator, returnValue);
                return null;
            }
        }

        if (operator instanceof PipeExprToken)
            return writePipeOperator((PipeExprToken) operator, returnValue, writeOpcode);

        if (operator instanceof LogicOperatorExprToken) {
            return writeLogicOperator((LogicOperatorExprToken) operator, returnValue, writeOpcode);
        }

        if (!operator.isBinary()) {
            return writeUnaryOperator(operator, returnValue, writeOpcode);
        }

        if (stackEmpty(true))
            unexpectedToken(operator);

        StackItem o1 = stackPop();
        if (o1.isInvalidForOperations())
            unexpectedToken(operator);

        if (stackEmpty(true))
            unexpectedToken(operator);

        StackItem o2 = stackPeek();
        ValueExprToken L = stackPopToken();

        if (o2.isInvalidForOperations())
            unexpectedToken(operator);

        boolean deferredArithmetic = false;
        if (!(operator instanceof AssignExprToken || operator instanceof AssignOperatorExprToken))
            if (o1.getMemory() != null && o2.getMemory() != null) {
                Memory result = CompilerUtils.calcBinary(
                        getCompiler().getEnvironment(), operator.toTraceInfo(getCompiler().getContext()), o2.getMemory(), o1.getMemory(), operator, false,
                        getCompiler().getModule().getPhpLanguageProfile()
                );
                if (result != null) { stackPush(result); return result; }
                deferredArithmetic = true;
            }

        LocalVariable variable = null;
        if (L instanceof VariableExprToken) {
            variable = method.getLocalVariable(((VariableExprToken) L).getName());
        }

        if (operator instanceof AssignExprToken) {
            if (L instanceof VariableExprToken) {
                if (!writeOpcode)
                    return null;
                writeVariableAssign((VariableExprToken) L, o1, (AssignExprToken) operator, returnValue);
                return null;
            }
        }

        Memory value1 = operator instanceof AssignableOperatorToken
                ? null
                : tryWritePush(o2, false, false, true); // LEFT

        Memory value2 = tryWritePush(o1, false, false, true); // RIGHT
        if (value1 != null && value2 != null && !deferredArithmetic) {
            stackPush(value1);
            stackPush(value2);
            return writeOperator(operator, returnValue, writeOpcode);
        }
        if (!returnValue && CompilerUtils.isOperatorAlwaysReturn(operator)) {
            unexpectedToken(operator);
        }

        if (!writeOpcode) {
            stackPush(o2);
            stackPush(o1);
            return null;
        }

        if (writeOpcode) {
            writeLineNumber(operator);
        }

        StackItem.Type Lt = tryGetType(o2);
        StackItem.Type Rt = tryGetType(o1);

        String name = operator.getCode();
        Class operatorResult = operator.getResultClass();

        boolean isInvert = false;
        boolean sideOperator = operator.isSide();

        if (variable != null && !variable.isReference()) {
            if (operator instanceof AssignOperatorExprToken) {
                name = ((AssignOperatorExprToken) operator).getOperatorCode();

                if (operator instanceof AssignConcatExprToken)
                    operatorResult = String.class;
                if (operator instanceof AssignPlusExprToken || operator instanceof AssignMulExprToken)
                    sideOperator = false;
            }
        }

        if (operator instanceof AssignableOperatorToken && tryIsImmutable(o2))
            unexpectedToken(operator);

        if (operator instanceof AssignExprToken && (L instanceof ArrayAssignTargetExprToken || o2.capturedArrayAssignment)) {
            if (!returnValue && o2.runtimeGlobalSiteId>=0 && !((AssignExprToken)operator).isAsReference()
                    && o1.runtimeEvaluationId<0 && o1.runtimeSourceLocalId<0
                    && (o1.getMemory()==Memory.NULL || o1.getToken() instanceof NullExprToken)) {
                // Preserve target/key effects; the exact scalar has its own producer before physical STORE.
                writePush(o2);writePopBoxing();
                int scalar=method.ownership().reserveRuntimeEval();
                method.ownership().runtimeProducer(scalar,"NULL_LITERAL");
                code.add(new VarInsnNode(ALOAD,method.ownership().runtimeFrameIndex()));stackPush(Memory.Type.REFERENCE);
                writePushConstInt(scalar);
                writeSysStaticCall(php.runtime.PhpRuntimeOwnership.class,"globalNull",Memory.class,
                        php.runtime.PhpRuntimeOwnership.Frame.class,int.class);
                method.ownership().runtimeGlobalNull(scalar,(MethodInsnNode)code.getLast());
                stackPeek().runtimeEvaluationId=scalar;
            } else if (!returnValue && o2.runtimeGlobalSiteId>=0 && !((AssignExprToken)operator).isAsReference()
                    && o1.runtimeEvaluationId<0 && o1.runtimeSourceLocalId<0
                    && (o1.getMemory()!=null && o1.getMemory().getClass()==LongMemory.class
                    || o1.getToken() instanceof IntegerExprToken && !((IntegerExprToken)o1.getToken()).isBigValue())) {
                // Target effects already ran; this exact literal now has its own real producer before STORE.
                writePush(o2);writePopBoxing();
                int scalar=method.ownership().reserveRuntimeEval();long value=o1.getMemory()!=null?o1.getMemory().toLong():((IntegerExprToken)o1.getToken()).getValue();
                method.ownership().runtimeProducer(scalar,"SCALAR_LITERAL");
                code.add(new VarInsnNode(ALOAD,method.ownership().runtimeFrameIndex()));stackPush(Memory.Type.REFERENCE);
                writePushConstInt(scalar);writePushConstLong(value);
                writeSysStaticCall(php.runtime.PhpRuntimeOwnership.class,"globalScalar",Memory.class,
                        php.runtime.PhpRuntimeOwnership.Frame.class,int.class,long.class);
                method.ownership().runtimeGlobalScalar(scalar,value,(MethodInsnNode)code.getLast());
                stackPeek().runtimeEvaluationId=scalar;
            } else if (!returnValue && o2.runtimeGlobalSiteId<0 && o2.runtimeArraySiteId>=0
                    && "assign".equals(operator.getCode()) && !((AssignExprToken)operator).isAsReference()
                    && o1.runtimeEvaluationId<0 && o1.runtimeSourceLocalId<0
                    && (o1.getMemory()!=null && o1.getMemory().isString()
                    && (o1.getMemory() instanceof PhpByteStringMemory?((PhpByteStringMemory)o1.getMemory()).byteLength():o1.getMemory().toString().length())<=16000
                    || o1.getMemory()==null && o1.getToken() instanceof StringExprToken && ((StringExprToken)o1.getToken()).getSegments().isEmpty()
                    && (((StringExprToken)o1.getToken()).hasRawBytes()?((StringExprToken)o1.getToken()).getPhp85Bytes().length:((StringExprToken)o1.getToken()).getValue().length())<=16000)) {
                writePush(o2);writePopBoxing();
                int scalar=method.ownership().reserveRuntimeEval();
                Memory literal=o1.getMemory();
                boolean raw=literal instanceof PhpByteStringMemory || literal==null && ((StringExprToken)o1.getToken()).hasRawBytes();
                String value=literal!=null?(raw?literal.toBinaryString():literal.toString()):raw
                        ?new String(((StringExprToken)o1.getToken()).getPhp85Bytes(),java.nio.charset.StandardCharsets.ISO_8859_1)
                        :((StringExprToken)o1.getToken()).getValue();
                method.ownership().runtimeProducer(scalar,"ENTRY_STRING_LITERAL");
                code.add(new VarInsnNode(ALOAD,method.ownership().runtimeFrameIndex()));stackPush(Memory.Type.REFERENCE);
                writePushConstInt(scalar);writePushConstString(value);writePushConstBoolean(raw);
                writeSysStaticCall(php.runtime.PhpRuntimeOwnership.class,"entryString",Memory.class,
                        php.runtime.PhpRuntimeOwnership.Frame.class,int.class,String.class,boolean.class);
                method.ownership().runtimeEntryString(scalar,value,raw,(MethodInsnNode)code.getLast());
                stackPeek().runtimeEvaluationId=scalar;stackPeek().runtimeArrayValueProducer=true;
            } else if (!returnValue && o2.runtimeGlobalSiteId<0 && o2.runtimeArraySiteId>=0
                    && "assign".equals(operator.getCode()) && !((AssignExprToken)operator).isAsReference()
                    && o1.runtimeEvaluationId<0 && o1.runtimeSourceLocalId<0
                    && (o1.getMemory()!=null && o1.getMemory().getClass()==LongMemory.class
                    || o1.getToken() instanceof IntegerExprToken && !((IntegerExprToken)o1.getToken()).isBigValue())) {
                // The already evaluated target precedes the literal EVAL and its physical ENTRY publication.
                writePush(o2);writePopBoxing();
                int scalar=method.ownership().reserveRuntimeEval();long value=o1.getMemory()!=null?o1.getMemory().toLong():((IntegerExprToken)o1.getToken()).getValue();
                method.ownership().runtimeProducer(scalar,"ENTRY_SCALAR_LITERAL");
                code.add(new VarInsnNode(ALOAD,method.ownership().runtimeFrameIndex()));stackPush(Memory.Type.REFERENCE);
                writePushConstInt(scalar);writePushConstLong(value);
                writeSysStaticCall(php.runtime.PhpRuntimeOwnership.class,"entryScalar",Memory.class,
                        php.runtime.PhpRuntimeOwnership.Frame.class,int.class,long.class);
                method.ownership().runtimeEntryScalar(scalar,value,(MethodInsnNode)code.getLast());
                stackPeek().runtimeEvaluationId=scalar;stackPeek().runtimeArrayValueProducer=true;
            } else writeBoxedBinaryOperands(o2, o1, false);
            StackItem entryValue=stackPeek();
            if(!returnValue&&o2.runtimeGlobalSiteId>=0&&(entryValue.runtimeEvaluationId>=0||entryValue.runtimeSourceLocalId>=0)) {
                int sourceEval=entryValue.runtimeEvaluationId,sourceLocal=sourceEval>=0?-1:entryValue.runtimeSourceLocalId;
                int mode=((AssignExprToken)operator).isAsReference()?1:0;
                code.add(new VarInsnNode(ALOAD,method.ownership().runtimeFrameIndex()));stackPush(Memory.Type.REFERENCE);
                writePushConstInt(sourceEval);writePushConstInt(sourceLocal);writePushConstInt(mode);
                writeSysDynamicCall(php.runtime.NullCoalesceAssignment.class,"assignGlobalRuntime",Memory.class,
                        Memory.class,php.runtime.PhpRuntimeOwnership.Frame.class,int.class,int.class,int.class);
                method.ownership().runtimeGlobalAccess(o2.runtimeGlobalSiteId,o2.runtimeGlobalRootLocalId,o2.runtimeGlobalKey,
                        sourceEval,sourceLocal,mode,o2.runtimeGlobalFactory,o2.runtimeGlobalIndex,(MethodInsnNode)code.getLast(),
                        sourceEval>=0?null:entryValue.runtimeSourceRead);
                if(sourceEval>=0)method.ownership().runtimeConsumer(sourceEval,"GLOBAL");
                invalidateGlobalVariable(o2.runtimeGlobalKey);
            } else if (!returnValue && "assign".equals(operator.getCode()) && o2.runtimeArraySiteId>=0 && entryValue.runtimeEvaluationId>=0 && entryValue.runtimeArrayValueProducer) {
                int sourceEval=entryValue.runtimeEvaluationId;
                code.add(new VarInsnNode(ALOAD,method.ownership().runtimeFrameIndex()));stackPush(Memory.Type.REFERENCE);
                writePushConstInt(sourceEval);
                writeSysDynamicCall(php.runtime.NullCoalesceAssignment.class,"assignRuntime",Memory.class,
                        Memory.class,php.runtime.PhpRuntimeOwnership.Frame.class,int.class);
                method.ownership().runtimeArrayEntry(o2.runtimeArraySiteId,sourceEval,o2.runtimeArrayRootLocalId,
                        o2.runtimeArrayAppend,o2.runtimeArrayFactory,o2.runtimeArrayStep,(MethodInsnNode)code.getLast());
                method.ownership().runtimeConsumer(sourceEval,"ENTRY");
            } else writeSysDynamicCall(php.runtime.NullCoalesceAssignment.class, operator.getCode(), Memory.class, Memory.class);
            setStackPeekAsImmutable();
            if (!returnValue) writePopAll(1);
        } else if (operator instanceof KeyValueExprToken && ((KeyValueExprToken) operator).isArrayEntry()) {
            writeBoxedBinaryOperands(o2, o1, !((KeyValueExprToken) operator).isValueReference());
            int valueRuntimeEval=stackPeek().runtimeEvaluationId;
            writeSysStaticCall(KeyValueMemory.class, "valueOf", Memory.class, Memory.class, Memory.class);
            stackPeek().runtimeEvaluationId=valueRuntimeEval;
            if(method.ownership().runtimeEnabled()) {
                Memory key=o2.getMemory();
                if(key!=null&&(key.isString()||key.type==Memory.Type.INT)||o2.getToken() instanceof StringExprToken||o2.getToken() instanceof IntegerExprToken)runtimeLiteralPairs.put(stackPeek(),(MethodInsnNode)code.getLast());
                else method.ownership().runtimeUnsupported("literal-entry-key-conversion");
            }
        } else if (getCompiler().getModule().getPhpLanguageProfile() == php.runtime.common.PhpLanguageProfile.PHP_8_5
                && (operator instanceof PlusExprToken || operator instanceof MinusExprToken || operator instanceof MulExprToken)
                && Lt.isLikeNumber() && Rt.isLikeNumber() && Lt != StackItem.Type.FLOAT && Rt != StackItem.Type.FLOAT) {
            StackItem.Type numericType = Lt == StackItem.Type.DOUBLE || Rt == StackItem.Type.DOUBLE
                    ? StackItem.Type.DOUBLE : StackItem.Type.LONG;
            writeNumericBinaryOperands(o2, o1, numericType);
            if (numericType == StackItem.Type.LONG) {
                writeSysStaticCall(OperatorUtils.class, name, Memory.class, Long.TYPE, Long.TYPE);
            } else {
                code.add(new InsnNode(CompilerUtils.getOperatorOpcode(operator, numericType)));
                stackPop(); stackPop(); stackPush(null, numericType);
            }
        } else if (getCompiler().getModule().getPhpLanguageProfile() == php.runtime.common.PhpLanguageProfile.PHP_8_5
                && CompilerUtils.isPhpArithmetic(operator)) {
            writeBoxedBinaryOperands(o2, o1, false);
            writePushConstString(CompilerUtils.arithmeticCode(operator));
            writePushEnv();
            writePushTraceInfo(operator);
            boolean assign = operator instanceof AssignOperatorExprToken;
            writeSysStaticCall(php.runtime.PhpArithmetic.class,
                    assign && (variable == null || variable.isReference()) ? "assign" : "binary", Memory.class,
                    Memory.class, Memory.class, String.class, Environment.class, TraceInfo.class);
            setStackPeekAsImmutable();
            if (assign) {
                if (variable == null || variable.isReference()) {
                    if (!returnValue) writePopAll(1);
                } else {
                    if (returnValue) writePushDup(StackItem.Type.REFERENCE);
                    makeVarStore(variable);
                    variable.setValue(null);
                    stackPop();
                }
            }
        } else if (getCompiler().getModule().getPhpLanguageProfile() == php.runtime.common.PhpLanguageProfile.PHP_8_5
                && CompilerUtils.isPhpComparison(operator)) {
            if(method.ownership().runtimeEnabled())writeRuntimeComparison(o2,o1,name,operator);
            else {
                writeBoxedBinaryOperands(o2, o1, false);
                writePushEnv();writePushTraceInfo(operator);
                writeSysStaticCall(php.runtime.PhpComparison.class,name,operatorResult,
                        Memory.class,Memory.class,Environment.class,TraceInfo.class);
            }
        } else if (!php8CallFrame() && (operator instanceof ConcatExprToken || operator instanceof AssignConcatExprToken)
                && (!(Lt == StackItem.Type.STRING && Rt == StackItem.Type.STRING) || operator instanceof AssignConcatExprToken)) {
            boolean assignment = operator instanceof AssignConcatExprToken;
            boolean address = assignment && (variable == null || variable.isReference());
            writeBoxedBinaryOperands(o2, o1, false);
            writeSysStaticCall(php.runtime.PhpByteStrings.class, address ? "assignConcatLegacy" : "concatLegacy",
                    Memory.class, Memory.class, Memory.class);
            setStackPeekAsImmutable();
            if (assignment && !address) {
                if (returnValue) writePushDup();
                makeVarStore(variable); variable.setValue(null); stackPop();
            } else if (assignment && !returnValue) writePopAll(1);
        } else if (php8CallFrame() && (operator instanceof ConcatExprToken || operator instanceof AssignConcatExprToken)
                && (!(Lt==StackItem.Type.STRING && Rt==StackItem.Type.STRING) || operator instanceof AssignConcatExprToken)) {
            boolean assignment=operator instanceof AssignConcatExprToken;
            boolean address=assignment && (variable==null || variable.isReference());
            writeByteConcatOperands(o2,o1,operator,address);
            if (assignment && !address) {
                writePopBoxing();if(returnValue)writePushDup();makeVarStore(variable);variable.setValue(null);stackPop();
            } else if (assignment && !returnValue) writePopAll(1);
        } else if (Lt==StackItem.Type.STRING_VALUE || Rt==StackItem.Type.STRING_VALUE) {
            writeBoxedBinaryOperands(o2,o1,false);
            writeSysDynamicCall(Memory.class,name,operatorResult,Memory.class);
        } else if ((CompilerUtils.isPhpComparison(operator) || operator instanceof IdenticalExprToken
                || operator instanceof NotIdenticalExprToken) && !o1.isKnown() && !o2.isKnown()) {
            // Both evaluated operands already occupy the JVM stack, including mixed scalar/Memory values.
            writeBoxedBinaryOperands(o2,o1,false);
            writeSysDynamicCall(Memory.class,name,operatorResult,Memory.class);
        } else if (Lt.isConstant() && Rt.isConstant()) {
            if (operator instanceof AssignableOperatorToken)
                unexpectedToken(operator);

            writeScalarOperator(o2, Lt, o1, Rt, operator, operatorResult, name);
        } else {
            isInvert = !o1.isKnown();
            if (!o1.isKnown() && !o2.isKnown() && o1.getLevel() > o2.getLevel())
                isInvert = false;

            if (Lt.isConstant() && !isInvert) {
                writePush(o2);
                if (methodExists(OperatorUtils.class, name, Lt.toClass(), Rt.toClass())) {
                    writePush(o1);
                    writeSysStaticCall(OperatorUtils.class, name, operatorResult, Lt.toClass(), Rt.toClass());
                } else {
                    writePopBoxing();
                    writePush(o1);

                    writeSysDynamicCall(Memory.class, name, operatorResult, Rt.toClass());
                }
            } else {
                if (isInvert) {
                    stackPush(o1);
                    if (o2.isKnown())
                        writePopBoxing(false);

                    writePush(o2);

                    if (!o2.isKnown() && !o2.type.isReference()) {
                        writeSysStaticCall(OperatorUtils.class, name, operatorResult, Lt.toClass(), Rt.toClass());
                        name = null;
                    } else if (sideOperator) {
                        name += "Right";
                    }

                    /*if (cloneValue){
                        writeSysDynamicCall(Memory.class, name, operatorResult, isInvert ? Lt.toClass() : Rt.toClass());
                        writePush(o2);
                        name = null;
                    }*/
                } else {
                    writePush(o2);
                    writePopBoxing(false);

                    /*if (cloneValue)
                        writePushDup();*/

                    writePush(o1);

                    if (Rt.isReference()) {
                        writePopBoxing(false);
                    } else if (Rt.isLikeInt()) { // fix bug call int operator.
                        writePopLong();
                        Rt = StackItem.Type.LONG;
                    }

                    if (!o1.immutable && !operator.isMutableArguments())
                        writePopImmutable();
                }

                if (name != null) {
                    writeSysDynamicCall(Memory.class, name, operatorResult, isInvert ? Lt.toClass() : Rt.toClass());
                }

                if (operator.getCheckerCode() != null) {
                    writePopBoxing();
                    writePushEnv();
                    writePushTraceInfo(operator);
                    writeSysDynamicCall(Memory.class, operator.getCheckerCode(), Memory.class, Environment.class, TraceInfo.class);
                }
            }
            setStackPeekAsImmutable();

            if (operator instanceof AssignOperatorExprToken) {
                if (variable == null || variable.isReference()) {
                    /*if (isInvert)
                        writeSysStaticCall(Memory.class, "assignRight", Memory.class, Rt.toClass(), Memory.class);
                    else
                        writeSysDynamicCall(Memory.class, "assign", Memory.class, stackPeek().type.toClass());
                    */
                    if (!returnValue)
                        writePopAll(1);
                } else {
                    if (returnValue)
                        writePushDup(StackItem.Type.valueOf(operatorResult));

                    writePopBoxing(operatorResult);
                    makeVarStore(variable);
                    variable.setValue(!stackPeek().isConstant() ? null : stackPeek().getMemory());
                    stackPop();
                }
            }
        }
        return null;
    }

    void writeDebugMessage(String message) {
        writePushEnv();
        writePushConstString(message);
        writeSysDynamicCall(Environment.class, "echo", void.class, String.class);
    }

    public void writeConditional(ExprStmtToken condition, LabelNode successLabel) {
        writeExpression(condition, true, false);
        writePopBoolean();
        code.add(new JumpInsnNode(IFEQ, successLabel));
        stackPop();
    }

    public Memory writeExpression(ExprStmtToken expression, boolean returnValue, boolean returnMemory) {
        return writeExpression(expression, returnValue, returnMemory, true);
    }

    public Memory tryCalculateExpression(ExprStmtToken expression) {
        return writeExpression(expression, true, true, false);
    }

    private void writeNullsafeReceiver() {
        writePush(stackPop());writePopBoxing();
    }

    private void writeNullsafeEnd(LabelNode missing) {
        writeNullsafeReceiver();
        LabelNode end=new LabelNode();
        code.add(new JumpInsnNode(GOTO,end));code.add(missing);
        // Every short-circuit edge has exactly its boxed receiver above the same outer stack.
        code.add(new InsnNode(POP));
        code.add(new FieldInsnNode(GETSTATIC,Type.getInternalName(Memory.class),"NULL",Type.getDescriptor(Memory.class)));
        code.add(end);
    }

    @SuppressWarnings("unchecked")
    public Memory writeExpression(ExprStmtToken expression,boolean returnValue,boolean returnMemory,boolean writeOpcode) {
        Token previous=discardedCallWitness;
        ExprStmtToken prepared=expression.isStmtList()?expression:expression.getAsmExpr();
        List<Token> tokens=prepared==null?Collections.emptyList():prepared.getTokens();
        Token terminal=tokens.isEmpty()?null:tokens.getLast();
        // A nullsafe branch join still needs Memory. Only its terminal call's semantic use is discarded.
        // A void cast consumes the preceding result, and argument expressions get independent scopes.
        discardedCallWitness=!returnValue&&!(terminal instanceof org.develnext.jphp.core.tokenizer.token.expr.operator.cast.VoidCastExprToken)?terminal:null;
        try{return writeExpressionBody(expression,returnValue,returnMemory,writeOpcode);}
        finally{discardedCallWitness=previous;}
    }
    /** Locate the whole postfix operand, including eager receiver/call/argument effects. */
    private int silentOperandStart(List<Token> tokens,int end) {
        int operands=1;
        for(int i=end-1;i>=0;i--) {
            Token token=tokens.get(i);if(token==null)continue;
            if(token instanceof OperatorExprToken operator) {
                if(operator.isBinary())operands++;
            } else if(!(token instanceof CallExprToken call && call.getName() instanceof OperatorExprToken)
                    && --operands==0)return i;
        }
        throw new CriticalException("Missing suppression operand");
    }
    /** END_SILENCE preserves an explicit mask change made inside the operand. */
    private void restoreSilentMask(LocalVariable saved) {
        int env=method.getLocalVariable("~env").index;
        LabelNode changed=new LabelNode();
        code.add(new VarInsnNode(ILOAD,saved.index));code.add(new LdcInsnNode(~4437));code.add(new InsnNode(IAND));
        code.add(new JumpInsnNode(IFEQ,changed)); // No END_SILENCE restoration if suppression was already active.
        code.add(new VarInsnNode(ALOAD,env));
        code.add(new MethodInsnNode(INVOKEVIRTUAL,Type.getInternalName(Environment.class),"getErrorFlags","()I",false));
        code.add(new LdcInsnNode(~4437));code.add(new InsnNode(IAND));
        code.add(new JumpInsnNode(IFNE,changed)); // Preserve an explicit nonfatal mask enabled by the operand.
        code.add(new VarInsnNode(ALOAD,env));code.add(new VarInsnNode(ILOAD,saved.index));
        code.add(new MethodInsnNode(INVOKEVIRTUAL,Type.getInternalName(Environment.class),"setErrorFlags","(I)V",false));
        code.add(changed);
    }
    private void writeSilentOperand(List<Token> tokens) {
        int id=method.nextStatementIndex(SilentToken.class),env=method.getLocalVariable("~env").index;
        LocalVariable saved=method.addLocalVariable("~silentMask"+id,null,int.class);
        LocalVariable failure=method.addLocalVariable("~silentFailure"+id,null,Throwable.class);
        code.add(new VarInsnNode(ALOAD,env));
        code.add(new MethodInsnNode(INVOKEVIRTUAL,Type.getInternalName(Environment.class),"getErrorFlags","()I",false));
        code.add(new VarInsnNode(ISTORE,saved.index));
        code.add(new VarInsnNode(ALOAD,env));code.add(new VarInsnNode(ILOAD,saved.index));
        // PHP 8 keeps E_ERROR|E_PARSE|E_CORE_ERROR|E_COMPILE_ERROR|E_USER_ERROR|E_RECOVERABLE_ERROR.
        code.add(new LdcInsnNode(4437));code.add(new InsnNode(IAND));
        code.add(new MethodInsnNode(INVOKEVIRTUAL,Type.getInternalName(Environment.class),"setErrorFlags","(I)V",false));
        LabelNode start=new LabelNode(),end=new LabelNode(),handler=new LabelNode(),joined=new LabelNode();
        // Inner call/finally cleanup must retain priority over this outer suppression cleanup.
        method.node.tryCatchBlocks.add(0,new TryCatchBlockNode(start,end,handler,"java/lang/Throwable"));
        code.add(start);
        ExprStmtToken operand=new ExprStmtToken(null,null,new ArrayList<>(tokens));operand.setAsmExpr(operand);
        writeExpression(operand,true,false,true);
        code.add(end);restoreSilentMask(saved);code.add(new JumpInsnNode(GOTO,joined));
        code.add(handler);code.add(new VarInsnNode(ASTORE,failure.index));restoreSilentMask(saved);
        code.add(new VarInsnNode(ALOAD,failure.index));code.add(new InsnNode(ATHROW));code.add(joined);
    }
    private Memory writeExpressionBody(ExprStmtToken expression,boolean returnValue,boolean returnMemory,boolean writeOpcode) {
        int initStackSize = method.getStackCount();
        exprStackInit.push(initStackSize);

        if (!expression.isStmtList()) {
            if (expression.getAsmExpr() == null) {
                throw new CriticalException("Invalid expression token without asm expr, on line " + expression.getMeta().getStartLine() + ", expr = " + expression.getWord());
            }

            expression = expression.getAsmExpr(); // new ASMExpression(compiler.getEnvironment(), compiler.getContext(), expression).getResult();
        }

        List<Token> tokens = expression.getTokens();
        Map<Integer,Integer> silentOperands=null;
        if(php8CallFrame() && writeOpcode)for(int i=0;i<tokens.size();i++)if(tokens.get(i) instanceof SilentToken) {
            if(silentOperands==null)silentOperands=new HashMap<>();
            // The outermost @ at a shared start owns the range; nested @ is lowered recursively.
            silentOperands.put(silentOperandStart(tokens,i),i);
        }
        for(Token token:tokens)if(token instanceof org.develnext.jphp.core.tokenizer.token.expr.operator.cast.VoidCastExprToken
                && (returnValue || tokens.getLast()!=token))throw new php.runtime.exceptions.ParseException("syntax error, unexpected token \"(void)\"",token.toTraceInfo(compiler.getContext()));
        List<ArrayGetRefExprToken> markedIncrementArrays = new ArrayList<>();
        if (php8CallFrame()) {
            Token previous = null;
            for (Token token : tokens) {
                if ((token instanceof IncExprToken || token instanceof DecExprToken)
                        && previous instanceof ArrayGetRefExprToken
                        && incrementArrayTargets.add((ArrayGetRefExprToken) previous)) {
                    markedIncrementArrays.add((ArrayGetRefExprToken) previous);
                }
                previous = token;
            }
        }
        // Calls/allocations to the left must run before a nullsafe receiver; ordinary CV reads keep their roles.
        int firstNullsafe = -1;
        if (php8CallFrame() && writeOpcode) for (int i=0;i<tokens.size();i++) {
            DynamicAccessExprToken access=org.develnext.jphp.core.compiler.common.NullsafeChain.access(tokens.get(i));
            if (access!=null && access.isNullsafe()) { firstNullsafe=i; break; }
        }
        int eagerEffectBoundary=firstNullsafe;
        if(silentOperands!=null)for(int start:silentOperands.keySet())
            eagerEffectBoundary=Math.max(eagerEffectBoundary,start);
        int operatorCount = 0;
        for (Token token : tokens) {
            if (token instanceof OperatorExprToken)
                operatorCount++;
        }

        boolean invalid = false;
        LabelNode nullsafeMissing=null;
        int nullsafeEnd=-1;
        try {
            for (int tokenIndex=0;tokenIndex<tokens.size();tokenIndex++) {
                Token token=tokens.get(tokenIndex);
                if (nullsafeMissing!=null && tokenIndex==nullsafeEnd) {
                    writeNullsafeEnd(nullsafeMissing);nullsafeMissing=null;
                }
                if (token == null) continue;
                Integer silentEnd=silentOperands==null?null:silentOperands.get(tokenIndex);
                if(silentEnd!=null) {
                    writeSilentOperand(tokens.subList(tokenIndex,silentEnd));
                    for(int i=tokenIndex;i<=silentEnd;i++)if(tokens.get(i) instanceof OperatorExprToken)operatorCount--;
                    tokenIndex=silentEnd;method.entity.setImmutable(false);continue;
                }
                DynamicAccessExprToken nullsafe=org.develnext.jphp.core.compiler.common.NullsafeChain.access(token);
                if (php8CallFrame() && nullsafe!=null && nullsafe.isNullsafe()) {
                    if (!writeOpcode) {invalid=true;break;}
                    if (nullsafeMissing==null) {
                        nullsafeMissing=new LabelNode();nullsafeEnd=tokenIndex+1;
                        while (nullsafeEnd<tokens.size() && org.develnext.jphp.core.compiler.common.NullsafeChain.step(tokens.get(nullsafeEnd)))
                            nullsafeEnd++;
                    }
                    writeNullsafeReceiver();writePushDup();
                    writeSysDynamicCall(Memory.class,"isNull",boolean.class);
                    code.add(new JumpInsnNode(IFNE,nullsafeMissing));stackPop();
                }

                writeTickTrigger(token);

                if (writeOpcode) {
                    if (token instanceof StmtToken) {
                        if (!(token instanceof ReturnStmtToken))
                            method.entity.setImmutable(false);
                    }

                    BaseStatementCompiler cmp = getCompiler(token.getClass());
                    if (cmp != null && !(cmp instanceof BaseExprCompiler)) {
                        cmp.write(token);
                        continue;
                    }
                }

                if (token instanceof ValueExprToken) {  // mixed, calls, numbers, strings, vars, etc.
                    if (token instanceof ArrayAssignTargetExprToken
                            || token instanceof CallExprToken && ((CallExprToken) token).getName() instanceof OperatorExprToken) {
                        if (writeOpcode) {
                            boolean callResult = token instanceof CallExprToken && tokenIndex == tokens.size() - 1 && nullsafeMissing == null ? returnValue : true;
                            writePush((ValueExprToken) token, callResult, true);
                            method.entity.setImmutable(false);
                        } else
                            break;
                    } else if (tokenIndex<eagerEffectBoundary && (token instanceof CallExprToken || token instanceof NewExprToken
                            || token instanceof ArrayExprToken || token instanceof ClosureStmtToken)) {
                        writePush((ValueExprToken) token, true, true);
                    } else
                        stackPush((ValueExprToken) token);
                } else if (token instanceof OperatorExprToken) { // + - * / % && || or ! and == > < etc.
                    operatorCount--;
                    if (writeOpcode && token instanceof ArrayGetRefExprToken && tokenIndex+1<tokens.size()
                            && (tokens.get(tokenIndex+1) instanceof IncExprToken || tokens.get(tokenIndex+1) instanceof DecExprToken)
                            && writeRuntimeArrayIncrement((ArrayGetRefExprToken)token,(OperatorExprToken)tokens.get(tokenIndex+1),
                            operatorCount>1 || nullsafeMissing!=null || returnValue)) {
                        operatorCount--;tokenIndex++;method.entity.setImmutable(false);continue;
                    }
                    if (operatorCount >= 0) {
                        Memory result;
                        if (operatorCount == 0) {
                            result = writeOperator((OperatorExprToken) token, nullsafeMissing!=null || returnValue, writeOpcode);
                        } else
                            result = writeOperator((OperatorExprToken) token, true, writeOpcode);

                        if (!writeOpcode && result == null) {
                            invalid = true;
                            break;
                        }

                        if (result == null)
                            method.entity.setImmutable(false);
                    }
                } else
                    break;

            }
            if (nullsafeMissing!=null) writeNullsafeEnd(nullsafeMissing);

        } finally {
            incrementArrayTargets.removeAll(markedIncrementArrays);
        }

        Memory result = null;
        if (!invalid && returnMemory && returnValue && !stackEmpty(false) && stackPeek().isConstant()) {
            result = stackPop().memory;
            invalid = true;
        }

        if (!invalid) {
            if (returnValue && !stackEmpty(false) && stackPeek().isKnown()) {
                if (returnMemory)
                    result = tryWritePush(stackPop(), writeOpcode, returnValue, true);
                else
                    writePush(stackPop());
            } else if (method.getStackCount() > 0) {
                if (php8CallFrame() && writeOpcode && stackPeekToken() != null && !stackPeek().isConstant()) {
                    // Evaluate discarded reads, while preserving the result-use witness of a call.
                    Token pending = stackPeekToken();
                    writePush(stackPopToken(), pending instanceof CallableExprToken ? returnValue : true, true);
                } else if (stackPeekToken() instanceof CallableExprToken) {
                    if (returnMemory)
                        result = tryWritePush(stackPopToken(), returnValue, writeOpcode, true);
                    else
                        writePush(stackPopToken(), returnValue, true);
                } else if (stackPeek().isConstant()) {
                    stackPop();
                }
            }
        }

        if (!returnValue && writeOpcode) {
            writePopAll(method.getStackCount() - initStackSize);
        } else if (!writeOpcode) {
            int count = method.getStackCount() - initStackSize;
            for (int i = 0; i < count; i++) {
                stackPop();
            }
        }

        exprStackInit.pop();

        return result;
    }

    void makePop(StackItem.Type type) {
        switch (type.size()) {
            case 2:
                code.add(new InsnNode(POP2));
                break;
            case 1:
                code.add(new InsnNode(POP));
                break;
            default:
                throw new IllegalArgumentException("Invalid of size StackItem: " + type.size());
        }
    }

    public void writePopAll(int count) {
        int i = 0;
        while (method.getStackCount() > 0 && i < count) {
            i++;
            StackItem o = stackPop();
            ValueExprToken token = o.getToken();
            StackItem.Type type = o.type;

            if (token == null) {
                switch (type.size()) {
                    case 2:
                        code.add(new InsnNode(POP2));
                        break;
                    case 1:
                        if (type==StackItem.Type.REFERENCE || type==StackItem.Type.ARRAY || type==StackItem.Type.OBJECT
                                || type==StackItem.Type.STRING || "STRING_VALUE".equals(type.name()) || type==StackItem.Type.CLASS || type==StackItem.Type.KEY_VALUE)
                            method.primitiveCells().discard(code);
                        code.add(new InsnNode(POP));
                        break;
                    default:
                        throw new IllegalArgumentException("Invalid of size StackItem: " + type.size());
                }
            } else/* if (o.isInvalidForOperations())*/
                unexpectedToken(token);
            method.ownership().releaseEvaluation(code, o.ownershipEvalId); o.ownershipEvalId = -1;
            releaseRuntimeEvaluation(o.runtimeEvaluationId,"POP");o.runtimeEvaluationId=-1;
        }
    }

    @Override
    public Entity compile() {
        writeDefineVariables(methodStatement.getLocal());
        writeExpression(expression, false, false);
        method.popAll();
        return null;
    }

    public static class NoSuchMethodException extends RuntimeException {
        public NoSuchMethodException(Class clazz, String method, Class... parameters) {
            super("No such method " + clazz.getName() + "." + method + "(" + StringUtils.join(parameters, ", ") + ")");
        }
    }

    public static class UnsupportedTokenException extends RuntimeException {
        protected final Token token;

        public UnsupportedTokenException(Token token) {
            this.token = token;
        }
    }
}
