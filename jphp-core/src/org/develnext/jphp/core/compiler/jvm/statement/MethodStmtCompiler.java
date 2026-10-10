package org.develnext.jphp.core.compiler.jvm.statement;

import org.develnext.jphp.core.compiler.common.misc.StackItem;
import org.develnext.jphp.core.compiler.jvm.Constants;
import org.develnext.jphp.core.compiler.jvm.misc.JumpItem;
import org.develnext.jphp.core.compiler.jvm.misc.LocalVariable;
import org.develnext.jphp.core.compiler.jvm.node.MethodNodeImpl;
import org.develnext.jphp.core.tokenizer.TokenMeta;
import org.develnext.jphp.core.tokenizer.token.Token;
import org.develnext.jphp.core.tokenizer.token.expr.value.NameToken;
import org.develnext.jphp.core.tokenizer.token.expr.value.StaticAccessExprToken;
import org.develnext.jphp.core.tokenizer.token.stmt.*;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;
import php.runtime.Memory;
import php.runtime.common.Messages;
import php.runtime.env.Environment;
import php.runtime.env.TraceInfo;
import php.runtime.exceptions.support.ErrorType;
import php.runtime.lang.IObject;
import php.runtime.memory.ArrayMemory;
import php.runtime.memory.ObjectMemory;
import php.runtime.memory.helper.ClassConstantMemory;
import php.runtime.memory.helper.ConstantMemory;
import php.runtime.reflection.ClassEntity;
import php.runtime.reflection.DocumentComment;
import php.runtime.reflection.MethodEntity;
import php.runtime.reflection.ParameterEntity;
import php.runtime.reflection.helper.GeneratorEntity;
import php.runtime.reflection.support.TypeChecker;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.objectweb.asm.Opcodes.INVOKESPECIAL;
import static org.objectweb.asm.Opcodes.NEW;

public class MethodStmtCompiler extends StmtCompiler<MethodEntity> {
    public final ClassStmtCompiler clazz;
    public MethodStmtToken statement;
    public final MethodNode node;

    protected int statementIndex = 0;

    private Stack<StackItem> stack = new Stack<StackItem>();
    private final List<JumpItem> jumpStack = new ArrayList<JumpItem>();
    final Stack<TryCatchItem> tryStack = new Stack<TryCatchItem>();
    final List<BodyStmtToken> finallyBlocks = new ArrayList<>();

    private int stackLevel = 0;
    private int stackSize = 0;
    private int stackMaxSize = 0;

    private Map<String, LabelNode> gotoLabels;
    private Map<Token, JumpScope> lexicalJumpScopes;
    private final Stack<BodyStmtToken> activeFinallyBodies = new Stack<>();
    private final Stack<Map<String, LabelNode>> finallyGotoLabels = new Stack<>();
    private Map<String, LocalVariable> localVariables;
    private int nextLocalIndex;
    protected String realName;

    private boolean external = false;
    private boolean initializer;

    private long methodId;
    private LabelNode labelStart;

    private Map<Class<?>, AtomicInteger> statementIndexes = new HashMap<Class<?>, AtomicInteger>();
    private Set<Integer> lineTickHandled = new HashSet<>();

    private GeneratorEntity generatorEntity;

    public MethodStmtCompiler(ClassStmtCompiler clazz, MethodNode node){
        super(clazz.getCompiler());
        this.clazz = clazz;
        this.statement = null;
        this.node   = node;

        this.localVariables = new LinkedHashMap<>();

        entity = new MethodEntity(getCompiler().getContext());
        entity.setClazz(clazz.entity);
        entity.setName(node.name);
        realName = entity.getName();

        methodId = compiler.getScope().nextMethodIndex();
    }

    public MethodStmtCompiler(ClassStmtCompiler clazz, MethodStmtToken statement) {
        super(clazz.getCompiler());
        this.clazz = clazz;
        this.statement = statement;
        this.node  = new MethodNodeImpl();

        this.localVariables = new LinkedHashMap<>();

        entity = new MethodEntity(getCompiler().getContext());
        entity.setClazz(clazz.entity);
        if (statement != null)
            entity.setName(statement.getName().getName());

        realName = entity.getName();
        methodId = compiler.getScope().nextMethodIndex();
    }

    public long getMethodId() {
        return methodId;
    }

    public long getClassId(){
        return clazz.entity.getId();
    }

    public String getRealName() {
        return realName;
    }

    public void setRealName(String realName) {
        this.realName = realName;
    }

    public boolean isInitializer() { return initializer; }

    public void setInitializer(boolean initializer) { this.initializer = initializer; }

    public boolean isExternal() {
        return external;
    }

    public void setExternal(boolean external) {
        this.external = external;
    }

    public GeneratorEntity getGeneratorEntity() {
        return generatorEntity;
    }

    public void setGeneratorEntity(GeneratorEntity generatorEntity) {
        this.generatorEntity = generatorEntity;
    }

    public Stack<TryCatchItem> getTryStack() {
        return tryStack;
    }

    public Map<String, LocalVariable> getLocalVariables() {
        return localVariables;
    }

    public boolean registerTickTrigger(int line) {
        return lineTickHandled.add(line);
    }

    public int nextStatementIndex(Class<?> clazz){
        AtomicInteger atomic = statementIndexes.get(clazz);
        if (atomic == null)
            statementIndexes.put(clazz, atomic = new AtomicInteger());

        return atomic.getAndIncrement();
    }

    public int prevStatementIndex(Class<?> clazz){
        AtomicInteger atomic = statementIndexes.get(clazz);
        if (atomic == null)
            statementIndexes.put(clazz, atomic = new AtomicInteger());

        return atomic.getAndDecrement();
    }

    public void pushJump(LabelNode breakLabel, LabelNode continueLabel, int stackSize){
        List<TryStmtToken> scopes = new ArrayList<>();
        for (TryCatchItem item : tryStack) scopes.add(item.getToken());
        jumpStack.add(new JumpItem(breakLabel, continueLabel, stackSize, scopes, activeFinallyBodies));
    }

    public void pushJump(LabelNode breakLabel, LabelNode continueLabel){
        pushJump(breakLabel, continueLabel, 0);
    }

    public JumpItem getJump(int level){
        if (jumpStack.size() - level < 0)
            return null;
        if (jumpStack.size() - level >= jumpStack.size())
            return null;

        return jumpStack.get(jumpStack.size() - level);
    }

    public int getJumpStackSize(int level){
        int size = 0;
        for(int i = jumpStack.size(); i >= 0 && jumpStack.size() - i < level; i--){
            JumpItem item = getJump(i);
            size += item.stackSize;
        }
        return size;
    }

    public void popJump(){
        jumpStack.remove(jumpStack.size() - 1);
    }

    void push(StackItem item) {
        if (item.getLevel() == -1)
            item.setLevel(stackLevel++);

        stack.push(item);
        stackSize += item.size;
        if (stackMaxSize < stackSize)
            stackMaxSize = stackSize;
    }

    int getStackSize(){
        return stackSize;
    }

    int getStackCount(){
        return stack.size();
    }

    StackItem pop(){
        StackItem item = stack.pop();
        stackSize -= item.size;
        return item;
    }

    void popAll(){
        stackSize = 0;
        stack.clear();
    }

    StackItem peek(){
        return stack.peek();
    }

    public LabelNode getGotoLabel(String name) {
        return gotoLabels == null ? null : gotoLabels.get(name.toLowerCase());
    }

    public LabelNode getOrCreateGotoLabel(String name) {
        name = name.toLowerCase();
        for (int i = finallyGotoLabels.size() - 1; i >= 0; i--) {
            LabelNode local = finallyGotoLabels.get(i).get(name);
            if (local != null) return local;
        }
        if (gotoLabels == null)
            gotoLabels = new HashMap<String, LabelNode>();

        LabelNode label = gotoLabels.get(name);
        if (label == null)
            gotoLabels.put(name, label = new LabelNode());

        return label;
    }

    private static boolean containsIdentity(Collection<?> scopes, Object scope) {
        for (Object candidate : scopes) if (candidate == scope) return true;
        return false;
    }

    private static boolean sameIdentityScopes(List<?> left, List<?> right) {
        if (left.size() != right.size()) return false;
        for (int i = 0; i < left.size(); i++) if (left.get(i) != right.get(i)) return false;
        return true;
    }

    public LabelNode routeFinallyExit(LabelNode target, List<TryStmtToken> targetScopes) {
        // Build outer routes first: the source then enters the innermost exited finally.
        for (TryCatchItem item : tryStack) {
            if (!containsIdentity(targetScopes, item.getToken())) target = item.exitLabel(target);
        }
        return target;
    }

    public boolean isLeavingFinally(JumpItem target) {
        for (BodyStmtToken body : activeFinallyBodies)
            if (!containsIdentity(target.finallyBodies, body)) return true;
        return false;
    }

    public LabelNode routeGoto(GotoStmtToken token, LabelStmtToken label, LabelNode target) {
        ensureJumpScopes();
        JumpScope sourceScope = lexicalJumpScopes.get(token);
        JumpScope targetScope = lexicalJumpScopes.get(label);
        if (sourceScope == null || targetScope == null) throw new IllegalStateException("Missing goto lexical scope");
        if (compiler.getModule().getPhpLanguageProfile() == php.runtime.common.PhpLanguageProfile.PHP_8_5) {
            if (!sameIdentityScopes(sourceScope.finallyBodies, targetScope.finallyBodies)) {
                boolean leavesFinally = false;
                for (BodyStmtToken body : sourceScope.finallyBodies)
                    if (!containsIdentity(targetScope.finallyBodies, body)) leavesFinally = true;
                compiler.getEnvironment().error(token.toTraceInfo(compiler.getContext()), ErrorType.E_COMPILE_ERROR,
                        leavesFinally ? "jump out of a finally block is disallowed"
                                : "jump into a finally block is disallowed");
            }
            for (Token loop : targetScope.loops) if (!containsIdentity(sourceScope.loops, loop)) {
                compiler.getEnvironment().error(token.toTraceInfo(compiler.getContext()), ErrorType.E_COMPILE_ERROR,
                        "'goto' into loop or switch statement is disallowed");
            }
        }
        return routeFinallyExit(target, targetScope.tryScopes);
    }

    public void beginFinally(BodyStmtToken body) {
        ensureJumpScopes();
        Map<String, LabelNode> labels = new HashMap<>();
        for (Map.Entry<Token, JumpScope> entry : lexicalJumpScopes.entrySet()) {
            if (entry.getKey() instanceof LabelStmtToken && containsIdentity(entry.getValue().finallyBodies, body))
                labels.put(((LabelStmtToken) entry.getKey()).getName().toLowerCase(), new LabelNode());
        }
        activeFinallyBodies.push(body);
        finallyGotoLabels.push(labels);
    }

    public void endFinally() {
        finallyGotoLabels.pop();
        activeFinallyBodies.pop();
    }

    private static class JumpScope {
        final List<TryStmtToken> tryScopes;
        final List<BodyStmtToken> finallyBodies;
        final List<Token> loops;
        JumpScope(List<TryStmtToken> tryScopes, List<BodyStmtToken> finallyBodies, List<Token> loops) {
            this.tryScopes = new ArrayList<>(tryScopes);
            this.finallyBodies = new ArrayList<>(finallyBodies);
            this.loops = new ArrayList<>(loops);
        }
    }

    private void ensureJumpScopes() {
        if (lexicalJumpScopes != null) return;
        lexicalJumpScopes = new IdentityHashMap<>();
        if (statement != null) collectJumpScopes(statement.getBody(), new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
    }

    private void collectJumpScopes(BodyStmtToken body, List<TryStmtToken> tries,
                                   List<BodyStmtToken> finallies, List<Token> loops) {
        if (body == null || body.getInstructions() == null) return;
        for (ExprStmtToken line : body.getInstructions()) for (Token token : line.getTokens()) {
            if (token instanceof LabelStmtToken || token instanceof GotoStmtToken) {
                lexicalJumpScopes.put(token, new JumpScope(tries, finallies, loops));
            } else if (token instanceof TryStmtToken) {
                TryStmtToken attempt = (TryStmtToken) token;
                List<TryStmtToken> inner = new ArrayList<>(tries);
                if (attempt.getFinally() != null) inner.add(attempt);
                collectJumpScopes(attempt.getBody(), inner, finallies, loops);
                for (CatchStmtToken catcher : attempt.getCatches()) collectJumpScopes(catcher.getBody(), inner, finallies, loops);
                if (attempt.getFinally() != null) {
                    List<BodyStmtToken> insideFinally = new ArrayList<>(finallies);
                    insideFinally.add(attempt.getFinally());
                    collectJumpScopes(attempt.getFinally(), tries, insideFinally, loops);
                }
            } else if (token instanceof IfStmtToken) {
                collectJumpScopes(((IfStmtToken) token).getBody(), tries, finallies, loops);
                collectJumpScopes(((IfStmtToken) token).getElseBody(), tries, finallies, loops);
            } else if (token instanceof WhileStmtToken || token instanceof DoStmtToken || token instanceof ForStmtToken
                    || token instanceof ForeachStmtToken || token instanceof SwitchStmtToken) {
                List<Token> innerLoops = new ArrayList<>(loops);
                innerLoops.add(token);
                if (token instanceof WhileStmtToken) collectJumpScopes(((WhileStmtToken) token).getBody(), tries, finallies, innerLoops);
                else if (token instanceof DoStmtToken) collectJumpScopes(((DoStmtToken) token).getBody(), tries, finallies, innerLoops);
                else if (token instanceof ForStmtToken) collectJumpScopes(((ForStmtToken) token).getBody(), tries, finallies, innerLoops);
                else if (token instanceof ForeachStmtToken) collectJumpScopes(((ForeachStmtToken) token).getBody(), tries, finallies, innerLoops);
                else {
                    SwitchStmtToken selection = (SwitchStmtToken) token;
                    for (CaseStmtToken arm : selection.getCases()) collectJumpScopes(arm.getBody(), tries, finallies, innerLoops);
                    if (selection.getDefaultCase() != null) collectJumpScopes(selection.getDefaultCase().getBody(), tries, finallies, innerLoops);
                }
            } else if (token instanceof BodyStmtToken) {
                collectJumpScopes((BodyStmtToken) token, tries, finallies, loops);
            }
            // Nested functions/closures have their own MethodStmtCompiler and label namespace.
        }
    }

    public LocalVariable addLocalVariable(String variable, LabelNode label, Class clazz){
        // Lexical names may be replaced; their emitted slots remain occupied.
        LocalVariable result = new LocalVariable(variable, nextLocalIndex, label, clazz);
        nextLocalIndex += Type.getType(clazz == null ? Object.class : clazz).getSize();
        localVariables.put(variable, result);
        return result;
    }

    void reserveEmittedLocals() {
        // Late instrumentation must also account for slots emitted without named locals.
        nextLocalIndex = Math.max(nextLocalIndex, node.maxLocals);
        for (AbstractInsnNode instruction : node.instructions) {
            if (instruction instanceof VarInsnNode) {
                VarInsnNode variable = (VarInsnNode) instruction;
                int opcode = variable.getOpcode();
                int size = opcode == Opcodes.LLOAD || opcode == Opcodes.LSTORE
                        || opcode == Opcodes.DLOAD || opcode == Opcodes.DSTORE ? 2 : 1;
                nextLocalIndex = Math.max(nextLocalIndex, variable.var + size);
            } else if (instruction instanceof IincInsnNode) {
                nextLocalIndex = Math.max(nextLocalIndex, ((IincInsnNode) instruction).var + 1);
            }
        }
    }

    public LocalVariable getOrAddLocalVariable(String variable, LabelNode label, Class clazz){
        LocalVariable local = getLocalVariable(variable);
        if (local == null){
            local = addLocalVariable(variable, label, clazz);
        } else {
            if (local.getClazz() != clazz)
                throw new RuntimeException(
                        "Invalid class for " + variable + " variable, " + local.getClazz() + " != " + clazz.getClass()
                );
        }
        return local;
    }

    public LocalVariable addLocalVariable(String variable, LabelNode label){
        return addLocalVariable(variable, label, Memory.class);
    }

    public LocalVariable getLocalVariable(String variable){
        return localVariables.get(variable);
    }

    void writeHeader(){
        int access = 0;
        if (statement != null){
            if (compiler.getScope().isDebugMode()) {
                statement.setDynamicLocal(true);
            }

            switch (statement.getModifier()){
                case PRIVATE: access += Opcodes.ACC_PRIVATE; break;
                case PROTECTED: access += Opcodes.ACC_PROTECTED; break;
                case PUBLIC: access += Opcodes.ACC_PUBLIC; break;
            }

            if (statement.isStatic()) access += Opcodes.ACC_STATIC;
            //if (statement.isAbstract()) access += Opcodes.ACC_ABSTRACT;
            if (statement.isFinal()) access += Opcodes.ACC_FINAL;

            node.access = access;
            node.name = clazz.isSystem() || entity == null ? statement.getName().getName() : entity.getInternalName();
            node.desc = Type.getMethodDescriptor(
                    Type.getType(Memory.class),
                    Type.getType(Environment.class),
                    Type.getType(Memory[].class)
            );

            if (external){
                node.desc = Type.getMethodDescriptor(
                        Type.getType(Memory.class),
                        Type.getType(Environment.class),
                        Type.getType(Memory[].class),
                        Type.getType(ArrayMemory.class)
                );
            }
        }

        if (statement != null){
            LabelNode label = labelStart = writeLabel(node, statement.getMeta().getStartLine());

            if (!statement.isStatic())
                addLocalVariable("~this", label, Object.class);

            ExpressionStmtCompiler expressionCompiler = new ExpressionStmtCompiler(this, null);

            addLocalVariable("~env", label, Environment.class); // Environment env
            LocalVariable args = addLocalVariable("~args", label, Memory[].class);  // Memory[] arguments

            if (statement.isDynamicLocal()){
                if (external)
                    addLocalVariable("~passedLocal", label, ArrayMemory.class);

                LocalVariable local = addLocalVariable("~local", label, ArrayMemory.class);

                if (external){
                    expressionCompiler.writeVarLoad("~passedLocal");
                    expressionCompiler.writeSysStaticCall(ArrayMemory.class, "valueOfRef", ArrayMemory.class, ArrayMemory.class);
                    expressionCompiler.setStackPeekAsImmutable();
                    expressionCompiler.writeVarStore(local, false, true);
                } else {
                    expressionCompiler.writePushConstNull();
                    expressionCompiler.writeSysStaticCall(ArrayMemory.class, "valueOfRef", ArrayMemory.class, ArrayMemory.class);
                    expressionCompiler.setStackPeekAsImmutable();
                    expressionCompiler.writeVarStore(local, false, true);
                }
            }

            if (statement.getUses() != null && !statement.getUses().isEmpty()){
                int i = 0;
                expressionCompiler.writeVarLoad("~this");
                expressionCompiler.writeGetDynamic("uses", Memory[].class);

                for (ArgumentStmtToken argument : statement.getUses()) {
                    LocalVariable local;
                    if (statement.isDynamicLocal()) {
                        expressionCompiler.writeDefineVariable(argument.getName());
                        local = getLocalVariable(argument.getName().getName());
                    } else {
                        local = addLocalVariable(argument.getName().getName(), label, Memory.class);
                    }

                    if (argument.isReference()){
                        local.setReference(true);
                        statement.variable(argument.getName()).setUnstable(true);
                    }

                    expressionCompiler.writePushDup();
                    expressionCompiler.writePushGetFromArray(i, Memory.class);

                    if (statement.isDynamicLocal()) {
                        expressionCompiler.writeVarAssign(local, argument.getName(), false, false);
                    } else {
                        if (argument.isReference() && compiler.getModule().getPhpLanguageProfile() == php.runtime.common.PhpLanguageProfile.PHP_8_5)
                            expressionCompiler.writeSysStaticCall(php.runtime.memory.ReferenceMemory.class, "valueOf", Memory.class, Memory.class);
                        expressionCompiler.writeVarStore(local, false, false);
                    }

                    local.pushLevel();
                    i++;
                }
                expressionCompiler.writePopAll(1);
            }

            int i = 0;

            for(ArgumentStmtToken argument : statement.getArguments()){
                if (argument.isReference()){
                    statement.variable(argument.getName())
                            .setReference(true)
                            .setUnstable(true);
                }

                LabelNode next = new LabelNode();

                expressionCompiler.writeDefineVariable(argument.getName());
                LocalVariable local = getLocalVariable(argument.getName().getName());

                if (local != null) {
                    expressionCompiler.writeVarLoad(args);
                    expressionCompiler.writePushGetFromArray(i, Memory.class);
                    expressionCompiler.writeVarAssign(local, argument.getName(), true, false);

                    // if length <= i then undefined
                    node.instructions.add(new JumpInsnNode(Opcodes.IFNONNULL, next));
                    expressionCompiler.stackPop();

                    if (argument.getValue() == null)
                        expressionCompiler.writePushNull();
                    else
                        expressionCompiler.writeExpression(argument.getValue(), true, false);

                    expressionCompiler.writeVarAssign(local, argument.getName(), false, false);
                    node.instructions.add(next);

                    local.pushLevel();
                }

                i++;
            }
        } else {
            LabelNode label = labelStart = writeLabel(node, clazz.statement.getMeta().getStartLine());
        }
    }

    private ParameterEntity.DefaultInitializer writeParameterInitializer(ExprStmtToken value) {
        MethodNode initializer = new MethodNodeImpl();
        initializer.access = Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC;
        initializer.name = "__$parameterDefault" + clazz.entity.nextMethodIndex();
        initializer.desc = Type.getMethodDescriptor(Type.getType(Memory.class), Type.getType(Environment.class));
        MethodStmtCompiler method = new MethodStmtCompiler(clazz, initializer);
        method.setInitializer(true);
        ExpressionStmtCompiler expr = new ExpressionStmtCompiler(method, null);
        method.writeHeader();
        LabelNode start = expr.makeLabel();
        method.addLocalVariable("~env", start, Environment.class);
        expr.writeExpression(value, true, false, true);
        expr.writePopBoxing(true);
        initializer.instructions.add(new InsnNode(Opcodes.ARETURN));
        expr.stackPop();
        method.writeFooter();
        clazz.node.methods.add(initializer);
        return new ParameterEntity.DefaultInitializer(compiler.getModule().getInternalName(), clazz.node.name.replace('/', '.'),
                initializer.name, clazz.isSystem() ? null : clazz.entity.getName());
    }

    private void writePropertyWriteContext() {
        var env=getLocalVariable("~env");
        if (env == null || !Type.getReturnType(node.desc).equals(Type.getType(Memory.class)) || node.instructions.size() == 0) return;
        String context=Type.getInternalName(php.runtime.PhpPropertyWriteContext.class);
        String frame=Type.getInternalName(php.runtime.PhpPropertyWriteContext.Frame.class);
        LabelNode start=new LabelNode(), end=new LabelNode(), handler=new LabelNode();
        reserveEmittedLocals();
        var local=addLocalVariable("~propertyWriteContext", start, php.runtime.PhpPropertyWriteContext.Frame.class);
        var error=addLocalVariable("~propertyWriteError", handler, Throwable.class);
        InsnList prefix=new InsnList();
        prefix.add(new VarInsnNode(Opcodes.ALOAD, env.index));
        int traceIndex=clazz.addTraceInfo(statement == null ? 0 : statement.getMeta().getStartLine(),0);
        prefix.add(new FieldInsnNode(Opcodes.GETSTATIC,clazz.node.name,"$TRC",Type.getDescriptor(TraceInfo[].class)));
        prefix.add(new LdcInsnNode(traceIndex)); prefix.add(new InsnNode(Opcodes.AALOAD));
        prefix.add(new InsnNode(compiler.getModule().isStrictTypes() ? Opcodes.ICONST_1 : Opcodes.ICONST_0));
        prefix.add(new MethodInsnNode(Opcodes.INVOKESTATIC, context, "enter",
                "(Lphp/runtime/env/Environment;Lphp/runtime/env/TraceInfo;Z)L"+frame+";", false));
        prefix.add(new VarInsnNode(Opcodes.ASTORE, local.index)); prefix.add(start);
        for (AbstractInsnNode instruction : node.instructions.toArray()) if (instruction.getOpcode() == Opcodes.ARETURN) {
            InsnList cleanup=new InsnList(); cleanup.add(new VarInsnNode(Opcodes.ALOAD, local.index));
            cleanup.add(new MethodInsnNode(Opcodes.INVOKESTATIC, context, "leave", "(L"+frame+";)V", false));
            node.instructions.insertBefore(instruction, cleanup);
        }
        node.instructions.insert(prefix); node.instructions.add(end); node.instructions.add(handler);
        node.instructions.add(new VarInsnNode(Opcodes.ASTORE, error.index));
        node.instructions.add(new VarInsnNode(Opcodes.ALOAD, local.index));
        node.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, context, "leave", "(L"+frame+";)V", false));
        node.instructions.add(new VarInsnNode(Opcodes.ALOAD, error.index)); node.instructions.add(new InsnNode(Opcodes.ATHROW));
        node.tryCatchBlocks.add(new TryCatchBlockNode(start, end, handler, null));
    }

    @SuppressWarnings("unchecked")
    void writeFooter(){
        LabelNode endL = new LabelNode();
        node.instructions.add(endL);

        for(LocalVariable variable : localVariables.values()){
            String description = Type.getDescriptor(variable.getClazz() == null ? Object.class : variable.getClazz());
            if (variable.name.equals("~this")) {
                //if (variable.getClazz() != Memory.class && !clazz.statement.isTrait()) {
                    description = "L" + clazz.entity.getCompiledInternalName() + ";";
                //}
            }

            node.localVariables.add(new LocalVariableNode(
                    variable.name,
                    description,
                    null,
                    variable.label == null ? labelStart : variable.label,
                    variable.getEndLabel() == null ? endL : variable.getEndLabel(),
                    variable.index
            ));
        }

        //node.maxStack = this.stackMaxSize;  !!! we don't need this, see: ClassWriter.COMPUTE_FRAMES
        //node.maxLocals = this.localVariables.size();
    }

    @Override
    public MethodEntity compile() {
        if (statement != null){
            if (external)
                statement.setDynamicLocal(true);

            if (statement.getDocComment() != null)
                entity.setDocComment(new DocumentComment(statement.getDocComment().getComment()));

            entity.setAbstract(statement.isAbstract());
            entity.setAbstractable(statement.getBody() == null);
            entity.setFinal(statement.isFinal());
            entity.setStatic(statement.isStatic());
            entity.setModifier(statement.getModifier());
            entity.setReturnReference(statement.isReturnReference());
            entity.setTrace(statement.toTraceInfo(compiler.getContext()));
            entity.setImmutable(statement.getArguments().isEmpty() && !(statement.isReturnReference()
                    && compiler.getModule().getPhpLanguageProfile() == php.runtime.common.PhpLanguageProfile.PHP_8_5));
            entity.setGeneratorEntity(generatorEntity);

            if (statement.getDeclaredReturnType() != null) {
                entity.setReturnTypeChecker(statement.getDeclaredReturnType());
            } else if (statement.getReturnHintTypeClass() != null) {
                entity.setReturnTypeChecker(TypeChecker.of(statement.getReturnHintTypeClass().getName()));
            } else if (statement.getReturnHintType() != null) {
                entity.setReturnTypeChecker(TypeChecker.of(statement.getReturnHintType()));
            }

            entity.setReturnTypeNullable(statement.isReturnOptional());

            if (clazz.isSystem())
                entity.setInternalName(entity.getName());
            else
                entity.setInternalName(entity.getName() + "$" + clazz.entity.nextMethodIndex());

            ParameterEntity[] parameters = new ParameterEntity[statement.getArguments().size()];
            int i = 0;
            for(ArgumentStmtToken argument : statement.getArguments()){
                parameters[i] = new ParameterEntity(compiler.getContext());
                ParameterEntity parameter = parameters[i];

                parameter.setReference(argument.isReference());
                parameter.setName(argument.getName().getName());
                parameter.setTrace(argument.toTraceInfo(compiler.getContext()));
                parameter.setNullable(argument.isOptional());

                parameter.setMutable(
                        statement.isDynamicLocal() || statement.variable(argument.getName()).isMutable()
                );

                parameter.setUsed(
                        !statement.isUnusedVariable(argument.getName())
                );

                parameter.setVariadic(argument.isVariadic());

                parameter.setType(argument.getHintType());
                if (argument.getDeclaredType() != null) parameter.setTypeChecker(argument.getDeclaredType());

                if (argument.getHintTypeClass() != null) {
                    parameter.setTypeClass(argument.getHintTypeClass().getName());
                }

                ExpressionStmtCompiler expressionStmtCompiler = new ExpressionStmtCompiler(compiler);
                ExprStmtToken value = argument.getValue();

                if (value != null) {
                    Memory defaultValue = expressionStmtCompiler.writeExpression(value, true, true, false);

                    // try detect constant
                    if (value.isSingle()) {
                        if (value.getSingle() instanceof NameToken){
                            parameter.setDefaultValueConstName(((NameToken) value.getSingle()).getName());
                            if (defaultValue == null) {
                                defaultValue = (new ConstantMemory(((NameToken) value.getSingle()).getName()));
                                parameter.setMutable(true);
                            }
                        } else if (value.getSingle() instanceof StaticAccessExprToken){
                            StaticAccessExprToken access = (StaticAccessExprToken)value.getSingle();

                            if (access.getClazz() instanceof NameToken && access.getField() instanceof NameToken){
                                if (defaultValue == null)
                                    defaultValue = (new ClassConstantMemory(
                                            ((NameToken) access.getClazz()).getName(),
                                            ((NameToken) access.getField()).getName()
                                    ));

                                parameter.setDefaultValueConstName(
                                        ((NameToken) access.getClazz()).getName() + "::" +
                                                ((NameToken) access.getField()).getName()
                                );
                                parameter.setMutable(true);
                            }
                        }
                    }

                    if (defaultValue == null && compiler.getModule().getPhpLanguageProfile() == php.runtime.common.PhpLanguageProfile.PHP_8_5) {
                        defaultValue = PhpConstantExpression.check(value, expressionStmtCompiler);
                        if (defaultValue == null) {
                            parameter.setDefaultInitializer(writeParameterInitializer(value));
                            defaultValue = Memory.NULL;
                        }
                    }
                    if (defaultValue == null)
                        compiler.getEnvironment().error(
                                argument.toTraceInfo(compiler.getContext()), ErrorType.E_COMPILE_ERROR,
                                Messages.ERR_EXPECTED_CONST_VALUE, "$" + argument.getName().getName()
                        );

                    if (compiler.getModule().getPhpLanguageProfile() == php.runtime.common.PhpLanguageProfile.PHP_8_5
                            && parameter.getDefaultInitializer() == null
                            && parameter.getTypeChecker() != null && !defaultValue.isNull()
                            && !(defaultValue instanceof ConstantMemory) && !(defaultValue instanceof ClassConstantMemory)) {
                        var type = php.runtime.reflection.support.PhpTypeDeclaration.fromChecker(parameter.getTypeChecker(), parameter.isNullable());
                        Memory checked = type.strictValue(compiler.getEnvironment(), defaultValue, clazz.entity);
                        if (checked == null) compiler.getEnvironment().error(argument.toTraceInfo(compiler.getContext()), ErrorType.E_COMPILE_ERROR,
                                "Cannot use %s as default value for parameter $%s of type %s",
                                php.runtime.reflection.support.PhpTypeDeclaration.valueType(defaultValue), parameter.getName(), type.getErrorSignature());
                        defaultValue = checked;
                    }
                    parameter.setDefaultValue(defaultValue);
                }
                i++;
            }

            entity.setParameters(parameters);
        }

        if (statement != null && clazz.statement.isInterface()){
            if (!statement.isInterfacable()){
                compiler.getEnvironment().error(entity.getTrace(),
                        Messages.ERR_INTERFACE_FUNCTION_CANNOT_CONTAIN_BODY.fetch(entity.getSignatureString(false))
                );
            }
            if (statement.isAbstract() || statement.isFinal()){
                compiler.getEnvironment().error(entity.getTrace(),
                        Messages.ERR_ACCESS_TYPE_FOR_INTERFACE_METHOD.fetch(entity.getSignatureString(false))
                );
            }
        } else {
            writeHeader();

            if (statement.isGenerator()) {
                if (compiler.getModule().getPhpLanguageProfile() == php.runtime.common.PhpLanguageProfile.PHP_8_5) {
                    php.runtime.common.HintType hint = statement.getReturnHintType();
                    String name = statement.getReturnHintTypeClass() == null ? null : statement.getReturnHintTypeClass().getName();
                    boolean valid = name == null
                            ? hint == null || hint == php.runtime.common.HintType.MIXED
                            || hint == php.runtime.common.HintType.OBJECT || hint == php.runtime.common.HintType.ITERABLE
                            : name.equalsIgnoreCase("Generator") || name.equalsIgnoreCase("Iterator") || name.equalsIgnoreCase("Traversable");
                    if (statement.getDeclaredReturnType() != null) valid = statement.getDeclaredReturnType().getAlternatives().stream()
                            .anyMatch(arm -> arm.size() == 1 && java.util.Set.of("generator", "iterator", "traversable", "iterable", "object", "mixed")
                                    .contains(arm.getFirst().toLowerCase(java.util.Locale.ROOT)));
                    if (!valid) compiler.getEnvironment().error(entity.getTrace(), ErrorType.E_COMPILE_ERROR,
                            "Generator return type must be a supertype of Generator, %s given", name == null ? hint : name);
                }
                entity.setEmpty(false);
                entity.setImmutable(false);
                entity.setResult(null);
                GeneratorStmtCompiler generatorStmtCompiler = new GeneratorStmtCompiler(compiler, statement);
                entity.setGeneratorEntity(generatorStmtCompiler.compile());

                ExpressionStmtCompiler expr = new ExpressionStmtCompiler(this, null);

                expr.makeUnknown(new TypeInsnNode(NEW, entity.getGeneratorEntity().getInternalName()));
                expr.stackPush(Memory.Type.REFERENCE);
                expr.writePushDup();

                // env
                expr.writePushEnv();

                // classEntity
                expr.writePushDup();
                expr.writePushConstString(compiler.getModule().getInternalName());
                expr.writePushConstInt((int) entity.getGeneratorEntity().getId());
                expr.writeSysDynamicCall(Environment.class, "__getGenerator", ClassEntity.class, String.class, Integer.TYPE);

                // self
                expr.writePushThis();

                // uses
                expr.writeVarLoad("~args");

                expr.writeSysCall(
                        entity.getGeneratorEntity().getInternalName(), INVOKESPECIAL, Constants.INIT_METHOD, void.class,
                        Environment.class, ClassEntity.class, Memory.class, Memory[].class
                );
                expr.writeSysStaticCall(ObjectMemory.class, "valueOf", Memory.class, IObject.class);

                expr.makeUnknown(new InsnNode(Opcodes.ARETURN));
                expr.stackPop();
            } else {
                ExpressionStmtCompiler expr = new ExpressionStmtCompiler(this, null);

                entity.setEmpty(true);

                if (entity.getResult() == null) {
                    entity.setResult(Memory.UNDEFINED);
                }

                if (statement != null && statement.getBody() != null) {
                    expr.writeDefineVariables(statement.getLocal());
                    if (!ModuleBodyCompiler.write(expr, statement)) expr.write(statement.getBody());

                    if (!statement.getBody().getInstructions().isEmpty()) {
                        entity.setEmpty(false);

                        if (entity.getResult() != null && entity.getResult().isUndefined()) {
                            entity.setResult(null);
                        }
                    }
                }

                if (generatorEntity != null) {
                    expr.writeVarLoad("~this");
                    expr.writePushConstBoolean(false);
                    expr.writeSysDynamicCall(null, "_setValid", void.class, Boolean.TYPE);
                }

                if (entity.isReturnReference() && compiler.getModule().getPhpLanguageProfile() == php.runtime.common.PhpLanguageProfile.PHP_8_5)
                    entity.setEmpty(false);
                ReturnStmtToken token = new ReturnStmtToken(new TokenMeta("", 0, 0, 0, 0));
                token.setValue(null);
                token.setEmpty(true);
                token.setImplicit(true);
                expr.getCompiler(ReturnStmtToken.class).write(token);
            }

            writePropertyWriteContext();
            writeFooter();
        }
        return entity;
    }


    public static class TryCatchItem {
        private final TryStmtToken token;
        private final LabelNode returnLabel;
        private final Map<LabelNode, LabelNode> exitLabels = new LinkedHashMap<>();

        public TryCatchItem(TryStmtToken token, LabelNode returnLabel) {
            this.token = token;
            this.returnLabel = returnLabel;
        }

        public TryStmtToken getToken() {
            return token;
        }

        public LabelNode exitLabel(LabelNode target) {
            LabelNode route = exitLabels.get(target);
            if (route == null) exitLabels.put(target, route = new LabelNode());
            return route;
        }

        public Map<LabelNode, LabelNode> getExitLabels() {
            return exitLabels;
        }

        public LabelNode getReturnLabel() {
            return returnLabel;
        }
    }
}
