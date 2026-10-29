package org.develnext.jphp.core.compiler.jvm.statement;

import org.develnext.jphp.core.compiler.jvm.node.MethodNodeImpl;
import org.develnext.jphp.core.tokenizer.token.Token;
import org.develnext.jphp.core.tokenizer.token.expr.OperatorExprToken;
import org.develnext.jphp.core.tokenizer.token.expr.operator.*;
import org.develnext.jphp.core.tokenizer.token.expr.value.*;
import org.develnext.jphp.core.tokenizer.token.stmt.*;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;
import php.runtime.common.HintType;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.common.PhpOptimizationProfile;
import php.runtime.reflection.support.PhpTypeDeclaration;

import java.util.*;

import static org.objectweb.asm.Opcodes.*;

/** Conservative, effect-free scalar siblings. Original PHP methods and ownership certificates remain intact. */
public final class ScalarMethodCompiler {
    public static final String ENTRY_PREFIX = "__$scalarEntry$";
    private static final String MEMORY = "php/runtime/Memory";
    private static final String PHP_DESCRIPTOR = "(Lphp/runtime/env/Environment;[Lphp/runtime/Memory;)Lphp/runtime/Memory;";
    private ScalarMethodCompiler() {}

    public enum Kind {
        LONG("J", LLOAD, LSTORE, LRETURN, "INT", "toLong", "php/runtime/memory/LongMemory"),
        DOUBLE("D", DLOAD, DSTORE, DRETURN, "DOUBLE", "toDouble", "php/runtime/memory/DoubleMemory"),
        BOOLEAN("Z", ILOAD, ISTORE, IRETURN, "BOOL", "toBoolean", "php/runtime/memory/TrueMemory");
        final String descriptor, memoryType, conversion, box;
        final int load, store, returns;
        Kind(String descriptor, int load, int store, int returns, String memoryType, String conversion, String box) {
            this.descriptor=descriptor;this.load=load;this.store=store;this.returns=returns;
            this.memoryType=memoryType;this.conversion=conversion;this.box=box;
        }
        int width() { return this==BOOLEAN ? 1 : 2; }
    }
    record Local(String name, Kind kind, int slot) {}
    interface Value { Kind kind(); void emit(InsnList code, boolean wrapping); }
    private record Read(Local local) implements Value {
        public Kind kind() { return local.kind; }
        public void emit(InsnList code, boolean wrapping) { code.add(new VarInsnNode(local.kind.load,local.slot)); }
    }
    private record Literal(Kind kind,Object value) implements Value {
        public void emit(InsnList code, boolean wrapping) {
            code.add(kind==Kind.BOOLEAN ? new InsnNode(Boolean.TRUE.equals(value)?ICONST_1:ICONST_0) : new LdcInsnNode(value));
        }
    }
    private record Unary(Kind kind,String operator,Value value) implements Value {
        public void emit(InsnList code, boolean wrapping) {
            value.emit(code,wrapping);
            if(operator.equals("not")){code.add(new InsnNode(ICONST_1));code.add(new InsnNode(IXOR));}
            else if(operator.equals("bitNot")){code.add(new LdcInsnNode(-1L));code.add(new InsnNode(LXOR));}
            else if(operator.equals("negative")) {
                if(kind==Kind.DOUBLE) code.add(new InsnNode(DNEG));
                else if(wrapping) code.add(new InsnNode(LNEG));
                else code.add(new MethodInsnNode(INVOKESTATIC,"java/lang/Math","negateExact","(J)J",false));
            }
        }
    }
    private record Binary(Kind kind,String operator,Value left,Value right) implements Value {
        public void emit(InsnList code, boolean wrapping) {
            Kind arithmetic=left.kind()==Kind.DOUBLE || right.kind()==Kind.DOUBLE ? Kind.DOUBLE : left.kind();
            emitAs(code,left,arithmetic,wrapping);emitAs(code,right,arithmetic,wrapping);
            if(operator.equals("bitAnd") || operator.equals("bitOr") || operator.equals("bitXor")) {
                code.add(new InsnNode(operator.equals("bitAnd")?LAND:operator.equals("bitOr")?LOR:LXOR));return;
            }
            if(operator.equals("bitShl") || operator.equals("bitShr")) {
                // Admit only the common range. Larger/negative distances retain this fork's ordinary rule.
                LabelNode normal=new LabelNode(),invalid=new LabelNode(),end=new LabelNode();
                code.add(new InsnNode(DUP2));code.add(new InsnNode(LCONST_0));code.add(new InsnNode(LCMP));code.add(new JumpInsnNode(IFLT,invalid));
                code.add(new InsnNode(DUP2));code.add(new LdcInsnNode(64L));code.add(new InsnNode(LCMP));code.add(new JumpInsnNode(IFGE,invalid));
                code.add(normal);code.add(new InsnNode(L2I));
                code.add(new InsnNode(operator.equals("bitShl")?LSHL:LSHR));code.add(new JumpInsnNode(GOTO,end));
                code.add(invalid);code.add(new InsnNode(POP2));code.add(new InsnNode(POP2));
                code.add(new TypeInsnNode(NEW,"java/lang/ArithmeticException"));code.add(new InsnNode(DUP));
                code.add(new MethodInsnNode(INVOKESPECIAL,"java/lang/ArithmeticException","<init>","()V",false));code.add(new InsnNode(ATHROW));code.add(end);return;
            }
            if(operator.equals("plus") || operator.equals("minus") || operator.equals("mul")) {
                if(arithmetic==Kind.DOUBLE)code.add(new InsnNode(operator.equals("plus")?DADD:operator.equals("minus")?DSUB:DMUL));
                else if(wrapping)code.add(new InsnNode(operator.equals("plus")?LADD:operator.equals("minus")?LSUB:LMUL));
                else code.add(new MethodInsnNode(INVOKESTATIC,"java/lang/Math",operator.equals("plus")?"addExact":operator.equals("minus")?"subtractExact":"multiplyExact","(JJ)J",false));
                return;
            }
            if(arithmetic==Kind.DOUBLE)code.add(new InsnNode(operator.equals("smaller")||operator.equals("smallerEq")?DCMPG:DCMPL));
            else if(arithmetic==Kind.LONG)code.add(new InsnNode(LCMP));
            else code.add(new InsnNode(ISUB));
            int jump=switch(operator){case "equal", "identical"->IFEQ;case "notEqual", "notIdentical"->IFNE;
                case "smaller"->IFLT;case "smallerEq"->IFLE;case "greater"->IFGT;case "greaterEq"->IFGE;default->throw new IllegalStateException(operator);};
            LabelNode yes=new LabelNode(),end=new LabelNode();code.add(new JumpInsnNode(jump,yes));code.add(new InsnNode(ICONST_0));
            code.add(new JumpInsnNode(GOTO,end));code.add(yes);code.add(new InsnNode(ICONST_1));code.add(end);
        }
    }
    private record Logical(Value left,Value right,boolean and) implements Value {
        public Kind kind(){return Kind.BOOLEAN;}
        public void emit(InsnList code,boolean wrapping){
            LabelNode shortCircuit=new LabelNode(),end=new LabelNode();
            ScalarControlFlowCompiler.truth(code,left,wrapping);code.add(new JumpInsnNode(and?IFEQ:IFNE,shortCircuit));
            ScalarControlFlowCompiler.truth(code,right,wrapping);code.add(new JumpInsnNode(GOTO,end));
            code.add(shortCircuit);code.add(new InsnNode(and?ICONST_0:ICONST_1));code.add(end);
        }
    }
    private record Call(Plan target,List<Value> arguments) implements Value {
        public Kind kind(){return target.result;}
        public void emit(InsnList code,boolean wrapping){
            for(int i=0;i<arguments.size();i++)emitAs(code,arguments.get(i),target.parameters.get(i).kind,wrapping);
            code.add(new MethodInsnNode(INVOKESTATIC,target.owner,target.kernelName,target.descriptor,false));
        }
    }
    public static final class Plan {
        public final String owner,kernelName,descriptor;
        public final Kind result;
        private final List<Local> parameters;
        private final ScalarControlFlowCompiler.Block body;
        private final List<Local> locals;
        private Plan(String owner,String kernelName,Kind result,List<Local> parameters,ScalarControlFlowCompiler.Block body,List<Local> locals){
            this.owner=owner;this.kernelName=kernelName;this.result=result;this.parameters=List.copyOf(parameters);
            this.body=body;this.locals=List.copyOf(locals);
            this.descriptor="("+parameters.stream().map(p->p.kind.descriptor).reduce("",String::concat)+")"+result.descriptor;
        }
    }
    static final class Unsupported extends RuntimeException {
        private Unsupported(){super(null,null,false,false);}
    }
    static void require(boolean valid){if(!valid)throw new Unsupported();}
    private static Kind kind(HintType hint,PhpTypeDeclaration declaration){
        if(declaration!=null){
            require(declaration.getAlternatives().size()==1 && declaration.getAlternatives().getFirst().size()==1);
            return switch(declaration.getAlternatives().getFirst().getFirst()){
                case "int"->Kind.LONG;case "float"->Kind.DOUBLE;case "bool"->Kind.BOOLEAN;default->throw new Unsupported();};
        }
        require(hint!=null);
        return switch(hint){case INT->Kind.LONG;case DOUBLE->Kind.DOUBLE;case BOOLEAN->Kind.BOOLEAN;default->throw new Unsupported();};
    }
    static void emitAs(InsnList code,Value value,Kind target,boolean wrapping){
        value.emit(code,wrapping);if(value.kind()==Kind.LONG && target==Kind.DOUBLE)code.add(new InsnNode(L2D));
    }
    static boolean assignable(Kind actual,Kind expected){return actual==expected || actual==Kind.LONG && expected==Kind.DOUBLE;}
    static Value read(Local local){return new Read(local);}
    static Value one(){return new Literal(Kind.LONG,1L);}
    static Value binary(String operator,Value left,Value right){
        boolean bitwise=Set.of("bitAnd","bitOr","bitXor","bitShl","bitShr").contains(operator);
        require(left.kind()!=Kind.BOOLEAN && right.kind()!=Kind.BOOLEAN);
        if(bitwise)require(left.kind()==Kind.LONG && right.kind()==Kind.LONG);
        return new Binary(bitwise?Kind.LONG:left.kind()==Kind.DOUBLE||right.kind()==Kind.DOUBLE?Kind.DOUBLE:Kind.LONG,operator,left,right);
    }

    private static final class Analysis {
        final ClassStmtCompiler clazz;
        final Set<MethodStmtToken> visiting=Collections.newSetFromMap(new IdentityHashMap<>());
        final Map<MethodStmtToken,Plan> recursiveSignatures=new IdentityHashMap<>();
        final Map<MethodStmtToken,Plan> completed=new IdentityHashMap<>();
        Analysis(ClassStmtCompiler clazz){this.clazz=clazz;}
        Plan method(MethodStmtToken statement){
            Plan known=completed.get(statement);if(known!=null)return known;
            if(visiting.contains(statement)) {
                Plan recursive=recursiveSignatures.get(statement);require(recursive!=null);return recursive;
            }
            require(visiting.size()<16 && visiting.add(statement));
            try {
                require(statement!=null && statement.getClazz()==clazz.statement && !statement.isAbstract() && !statement.isGenerator()
                        && !statement.isReturnReference() && !statement.isReturnOptional() && !statement.isDynamicLocal()
                        && statement.getBody()!=null && statement.getPropertyHookName()==null
                        && (statement.getUses()==null || statement.getUses().isEmpty())
                        && (statement.getPhpAttributes()==null || statement.getPhpAttributes().isEmpty())
                        && !statement.getName().getName().startsWith("__") && statement.getArguments().size()<=16);
                Kind result=statement.getReturnHintType()==null && statement.getDeclaredReturnType()==null
                        ? null : kind(statement.getReturnHintType(),statement.getDeclaredReturnType());
                LinkedHashMap<String,Local> locals=new LinkedHashMap<>();List<Local> parameters=new ArrayList<>();int slot=0;
                for(ArgumentStmtToken argument:statement.getArguments()){
                    require(!argument.isReference() && !argument.isVariadic() && !argument.isOptional() && argument.getValue()==null && argument.getHintTypeClass()==null
                            && argument.getPhpAttributes().isEmpty() && !clazz.getCompiler().getScope().superGlobals.contains(argument.getName().getName()));
                    // Untyped parameters may specialize only the exact LongMemory shape.
                    // The entry guard retains ordinary PHP evaluation for every other value.
                    Kind type=argument.getHintType()==null && argument.getDeclaredType()==null
                            ? Kind.LONG : kind(argument.getHintType(),argument.getDeclaredType());
                    Local local=new Local(argument.getName().getName(),type,slot);
                    require(locals.putIfAbsent(local.name,local)==null);parameters.add(local);slot+=type.width();
                }
                // A declared scalar result closes the recursive call signature. Every
                // participating body must still pass the same complete purity analysis.
                if(result!=null)recursiveSignatures.put(statement,new Plan(clazz.node.name,
                        "__$scalar$"+php.runtime.common.PhpClassNames.canonicalKey(statement.getName().getName())+"$"+clazz.getCompiler().getModule().getOptimizationProfile().getId(),
                        result,parameters,null,new ArrayList<>(locals.values())));
                ScalarControlFlowCompiler flow=new ScalarControlFlowCompiler(locals,result,this::expression,this::tokens,clazz.getCompiler().getScope().superGlobals);
                Value arraySum = clazz.getCompiler().getModule().isArrayOptimizationEnabled()
                        ? ScalarArraySumCompiler.analyze(statement.getBody(), locals, this::tokens, this::expression,
                                clazz.getCompiler().getScope().superGlobals) : null;
                BodyStmtToken projection = arraySum == null
                        ? JvmOptimizationProvider.projectScalarBody(clazz, statement) : null;
                // Optional projections still pass the complete scalar/receiver-free proof.
                require(statement.isStatic() || arraySum != null || projection != null);
                ScalarControlFlowCompiler.Block body = arraySum == null ? flow.analyze(projection == null ? statement.getBody() : projection)
                        : flow.reduction(arraySum, statement.getMeta().getStartLine());
                require(!body.fallsThrough() && flow.result()!=null);
                Plan plan=new Plan(clazz.node.name,"__$scalar$"+php.runtime.common.PhpClassNames.canonicalKey(statement.getName().getName())+"$"+clazz.getCompiler().getModule().getOptimizationProfile().getId(),flow.result(),parameters,body,flow.locals());
                completed.put(statement,plan);return plan;
            }finally{visiting.remove(statement);recursiveSignatures.remove(statement);}
        }
        List<Token> tokens(ExprStmtToken expression){
            require(expression!=null);if(expression.getAsmExpr()==null)expression.updateAsmExpr(clazz.getCompiler().getEnvironment(),clazz.getCompiler().getContext());
            require(expression.getAsmExpr()!=null);List<Token> result=expression.getAsmExpr().getTokens();require(result.size()<=512);return result;
        }
        Value expression(ExprStmtToken expression,Map<String,Local> locals){return expression(tokens(expression),locals);}
        Value expression(List<Token> tokens,Map<String,Local> locals){
            Deque<Value> stack=new ArrayDeque<>();
            for(Token token:tokens){
                if(token instanceof VariableExprToken variable){Local local=locals.get(variable.getName());require(local!=null);stack.push(new Read(local));}
                else if(token instanceof IntegerExprToken integer){require(!integer.isBigValue());stack.push(new Literal(Kind.LONG,integer.getValue()));}
                else if(token instanceof DoubleExprToken number)stack.push(new Literal(Kind.DOUBLE,number.getValue()));
                else if(token instanceof BooleanExprToken bool)stack.push(new Literal(Kind.BOOLEAN,bool.getValue()));
                else if(token instanceof CallExprToken call){
                    ScalarCallResolver.Target target=ScalarCallResolver.resolve(clazz,call);require(target!=null);Plan plan=method(target.method());
                    require(target.arguments().size()==plan.parameters.size());List<Value> arguments=new ArrayList<>();
                    for(int i=0;i<target.arguments().size();i++){
                        Value value=expression(target.arguments().get(i),locals);require(assignable(value.kind(),plan.parameters.get(i).kind));arguments.add(value);
                    }
                    stack.push(new Call(plan,List.copyOf(arguments)));
                }else if(token instanceof OperatorExprToken operator){
                    if(operator instanceof LogicOperatorExprToken logic){
                        require(!stack.isEmpty());Value left=stack.pop();
                        boolean and=operator.getClass()==BooleanAndExprToken.class || operator.getClass()==BooleanAnd2ExprToken.class;
                        require(and || operator.getClass()==BooleanOrExprToken.class || operator.getClass()==BooleanOr2ExprToken.class);
                        stack.push(new Logical(left,expression(logic.getRightValue(),locals),and));
                    }else if(operator instanceof UnaryNumericExprToken sign){
                        require(!stack.isEmpty());Value value=stack.pop();require(value.kind()!=Kind.BOOLEAN);
                        stack.push(new Unary(value.kind(),sign.isNegative()?"negative":"positive",value));
                    }else if(operator.getClass()==UnarMinusExprToken.class){
                        require(!stack.isEmpty());Value value=stack.pop();require(value.kind()!=Kind.BOOLEAN);stack.push(new Unary(value.kind(),"negative",value));
                    }else if(operator.getClass()==BooleanNotExprToken.class){
                        require(!stack.isEmpty());Value value=stack.pop();
                        stack.push(new Unary(Kind.BOOLEAN,"not",new Value(){public Kind kind(){return Kind.BOOLEAN;}public void emit(InsnList code,boolean wrapping){ScalarControlFlowCompiler.truth(code,value,wrapping);}}));
                    }else if(operator.getClass()==NotExprToken.class){
                        require(!stack.isEmpty());Value value=stack.pop();require(value.kind()==Kind.LONG);stack.push(new Unary(Kind.LONG,"bitNot",value));
                    }else{
                        require(stack.size()>=2);Value right=stack.pop(),left=stack.pop();String code=operator.getCode();
                        boolean arithmetic=operator.getClass()==PlusExprToken.class || operator.getClass()==MinusExprToken.class || operator.getClass()==MulExprToken.class;
                        boolean bitwise=operator.getClass()==AndExprToken.class || operator.getClass()==OrExprToken.class || operator.getClass()==XorExprToken.class
                                || operator.getClass()==ShiftLeftExprToken.class || operator.getClass()==ShiftRightExprToken.class;
                        boolean comparison=operator.getClass()==EqualExprToken.class || operator.getClass()==BooleanNotEqualExprToken.class
                                || operator.getClass()==IdenticalExprToken.class || operator.getClass()==NotIdenticalExprToken.class
                                || operator.getClass()==SmallerExprToken.class || operator.getClass()==SmallerOrEqualToken.class
                                || operator.getClass()==GreaterExprToken.class || operator.getClass()==GreaterOrEqualExprToken.class;
                        require(arithmetic || comparison || bitwise);require(left.kind()!=Kind.BOOLEAN && right.kind()!=Kind.BOOLEAN || comparison && left.kind()==right.kind());
                        if(bitwise){stack.push(binary(code,left,right));continue;}
                        if(operator instanceof IdenticalExprToken || operator instanceof NotIdenticalExprToken)require(left.kind()==right.kind());
                        Kind type=arithmetic ? left.kind()==Kind.DOUBLE || right.kind()==Kind.DOUBLE ? Kind.DOUBLE : Kind.LONG : Kind.BOOLEAN;
                        stack.push(new Binary(type,code,left,right));
                    }
                }else throw new Unsupported();
            }
            require(stack.size()==1);return stack.pop();
        }
    }

    public static void emit(MethodStmtCompiler method){
        ClassStmtCompiler clazz=method.clazz;
        if(method.getCompiler().getModule().getOwnershipMode()==php.runtime.common.PhpRuntimeOwnershipMode.CHECKED
                || method.statement==null || method.getCompiler().getScope().isDebugMode() || clazz.isSystem() || clazz.isClosure()
                || clazz.statement==null || clazz.statement.isTrait() || clazz.statement.isInterface()
                || method.getCompiler().getModule().getPhpLanguageProfile()!=PhpLanguageProfile.PHP_8_5
                || !method.node.desc.equals(PHP_DESCRIPTOR))return;
        Plan plan;
        try{plan=new Analysis(clazz).method(method.statement);}catch(Unsupported ignored){return;}
        if(clazz.node.methods.stream().anyMatch(node->node.name.equals(plan.kernelName)))return;
        boolean wrapping=method.getCompiler().getModule().getOptimizationProfile()==PhpOptimizationProfile.FAST_INT64_WRAP;
        MethodNode kernel=new MethodNodeImpl();kernel.access=ACC_PUBLIC|ACC_STATIC|ACC_SYNTHETIC;kernel.name=plan.kernelName;kernel.desc=plan.descriptor;
        LabelNode start=new LabelNode(),end=new LabelNode();kernel.instructions.add(start);
        plan.body.emit(kernel.instructions,wrapping,plan.result);
        kernel.instructions.add(end);
        for(Local local:plan.locals)kernel.localVariables.add(new LocalVariableNode(local.name,local.kind.descriptor,null,start,end,local.slot));
        clazz.node.methods.add(kernel);
        MethodNode entry=new MethodNodeImpl();entry.access=ACC_PUBLIC|ACC_SYNTHETIC|(method.statement.isStatic()?ACC_STATIC:0);entry.name=ENTRY_PREFIX+method.node.name;entry.desc=PHP_DESCRIPTOR;
        entry.visibleAnnotations=new ArrayList<>();
        entry.visibleAnnotations.add(new AnnotationNode("Lphp/runtime/annotation/ReadOnlyArguments;"));
        LabelNode fallback=new LabelNode(),invokeStart=new LabelNode(),invokeEnd=new LabelNode(),overflow=new LabelNode();InsnList code=entry.instructions;
        int environmentSlot = method.statement.isStatic() ? 0 : 1;
        int argumentsSlot = environmentSlot + 1;
        code.add(new VarInsnNode(ALOAD,argumentsSlot));code.add(new JumpInsnNode(IFNULL,fallback));
        code.add(new VarInsnNode(ALOAD,argumentsSlot));code.add(new InsnNode(ARRAYLENGTH));code.add(new LdcInsnNode(plan.parameters.size()));code.add(new JumpInsnNode(IF_ICMPNE,fallback));
        for(int i=0;i<plan.parameters.size();i++){
            code.add(new VarInsnNode(ALOAD,argumentsSlot));code.add(new LdcInsnNode(i));code.add(new InsnNode(AALOAD));code.add(new JumpInsnNode(IFNULL,fallback));
            code.add(new VarInsnNode(ALOAD,argumentsSlot));code.add(new LdcInsnNode(i));code.add(new InsnNode(AALOAD));
            code.add(new MethodInsnNode(INVOKEVIRTUAL,"java/lang/Object","getClass","()Ljava/lang/Class;",false));
            code.add(new LdcInsnNode(Type.getObjectType(plan.parameters.get(i).kind.box)));
            if(plan.parameters.get(i).kind==Kind.BOOLEAN){
                LabelNode valid=new LabelNode();code.add(new JumpInsnNode(IF_ACMPEQ,valid));
                code.add(new VarInsnNode(ALOAD,argumentsSlot));code.add(new LdcInsnNode(i));code.add(new InsnNode(AALOAD));
                code.add(new MethodInsnNode(INVOKEVIRTUAL,"java/lang/Object","getClass","()Ljava/lang/Class;",false));
                code.add(new LdcInsnNode(Type.getObjectType("php/runtime/memory/FalseMemory")));code.add(new JumpInsnNode(IF_ACMPNE,fallback));code.add(valid);
            }else code.add(new JumpInsnNode(IF_ACMPNE,fallback));
        }
        code.add(invokeStart);
        for(int i=0;i<plan.parameters.size();i++){
            Kind kind=plan.parameters.get(i).kind;code.add(new VarInsnNode(ALOAD,argumentsSlot));code.add(new LdcInsnNode(i));code.add(new InsnNode(AALOAD));
            code.add(new MethodInsnNode(INVOKEVIRTUAL,MEMORY,kind.conversion,"()"+kind.descriptor,false));
        }
        code.add(new MethodInsnNode(INVOKESTATIC,plan.owner,plan.kernelName,plan.descriptor,false));
        code.add(new MethodInsnNode(INVOKESTATIC,plan.result.box,"valueOf","("+plan.result.descriptor+")Lphp/runtime/Memory;",false));
        code.add(invokeEnd);code.add(new InsnNode(ARETURN));
        entry.tryCatchBlocks.add(new TryCatchBlockNode(invokeStart,invokeEnd,overflow,"java/lang/ArithmeticException"));code.add(overflow);code.add(new InsnNode(POP));
        code.add(fallback);
        if (!method.statement.isStatic()) code.add(new VarInsnNode(ALOAD,0));
        code.add(new VarInsnNode(ALOAD,environmentSlot));code.add(new VarInsnNode(ALOAD,argumentsSlot));
        LabelNode fallbackArguments=new LabelNode();
        code.add(new InsnNode(DUP));code.add(new JumpInsnNode(IFNULL,fallbackArguments));
        code.add(new MethodInsnNode(INVOKEVIRTUAL,"[Lphp/runtime/Memory;","clone","()Ljava/lang/Object;",false));
        code.add(new TypeInsnNode(CHECKCAST,"[Lphp/runtime/Memory;"));code.add(fallbackArguments);
        code.add(new MethodInsnNode(method.statement.isStatic()?INVOKESTATIC:INVOKEVIRTUAL,clazz.node.name,method.node.name,PHP_DESCRIPTOR,false));code.add(new InsnNode(ARETURN));
        clazz.node.methods.add(entry);
    }
}
