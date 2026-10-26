package org.develnext.jphp.core.compiler.jvm.statement;

import org.develnext.jphp.core.tokenizer.token.Token;
import org.develnext.jphp.core.tokenizer.token.expr.operator.*;
import org.develnext.jphp.core.tokenizer.token.expr.value.VariableExprToken;
import org.develnext.jphp.core.tokenizer.token.stmt.*;
import org.objectweb.asm.tree.*;

import java.util.*;
import java.util.function.BiFunction;
import java.util.function.Function;

import static org.develnext.jphp.core.compiler.jvm.statement.ScalarMethodCompiler.*;
import static org.objectweb.asm.Opcodes.*;

/** Structured scalar CFG and definite assignment. No PHP values, effects or callbacks are introduced here. */
final class ScalarControlFlowCompiler {
    private interface Statement { void emit(Emission output); }
    record Block(List<Statement> statements,boolean fallsThrough) {
        Block { statements=List.copyOf(statements); }
        void emit(InsnList code,boolean wrapping,Kind result){emit(new Emission(code,wrapping,result));}
        private void emit(Emission output){statements.forEach(statement->statement.emit(output));}
    }
    private record JumpTarget(LabelNode exit,LabelNode iteration) {}
    private static final class Emission {
        final InsnList code;final boolean wrapping;final Kind result;
        final Deque<JumpTarget> loops=new ArrayDeque<>();
        Emission(InsnList code,boolean wrapping,Kind result){this.code=code;this.wrapping=wrapping;this.result=result;}
        void line(int number){LabelNode line=new LabelNode();code.add(line);code.add(new LineNumberNode(Math.max(1,number),line));}
    }
    private record Store(int line,Local destination,Value value) implements Statement {
        public void emit(Emission output){output.line(line);emitAs(output.code,value,destination.kind(),output.wrapping);output.code.add(new VarInsnNode(destination.kind().store,destination.slot()));}
    }
    private record Return(int line,Value value) implements Statement {
        public void emit(Emission output){output.line(line);emitAs(output.code,value,output.result,output.wrapping);output.code.add(new InsnNode(output.result.returns));}
    }
    private record Branch(int line,Value condition,Block yes,Block no) implements Statement {
        public void emit(Emission output){
            LabelNode alternative=new LabelNode(),end=new LabelNode();output.line(line);truth(output.code,condition,output.wrapping);
            output.code.add(new JumpInsnNode(IFEQ,alternative));yes.emit(output);
            if(yes.fallsThrough)output.code.add(new JumpInsnNode(GOTO,end));
            output.code.add(alternative);no.emit(output);if(yes.fallsThrough || no.fallsThrough)output.code.add(end);
        }
    }
    private record Loop(int line,Block initialize,Value condition,Block body,Block update,boolean doFirst) implements Statement {
        public void emit(Emission output){
            initialize.emit(output);LabelNode start=new LabelNode(),iteration=new LabelNode(),exit=new LabelNode();
            output.code.add(start);output.line(line);
            if(!doFirst && condition!=null){truth(output.code,condition,output.wrapping);output.code.add(new JumpInsnNode(IFEQ,exit));}
            output.loops.push(new JumpTarget(exit,iteration));body.emit(output);output.loops.pop();
            output.code.add(iteration);update.emit(output);
            if(doFirst && condition!=null){truth(output.code,condition,output.wrapping);output.code.add(new JumpInsnNode(IFNE,start));}
            else output.code.add(new JumpInsnNode(GOTO,start));
            output.code.add(exit);
        }
    }
    private record Jump(int line,int level,boolean continuing) implements Statement {
        public void emit(Emission output){
            output.line(line);JumpTarget target=output.loops.stream().skip(level-1).findFirst().orElseThrow();
            output.code.add(new JumpInsnNode(GOTO,continuing?target.iteration:target.exit));
        }
    }

    private final LinkedHashMap<String,Local> allocated;
    private final Map<String,Local> entry;
    private final BiFunction<List<Token>,Map<String,Local>,Value> expression;
    private final Function<ExprStmtToken,List<Token>> tokens;
    private final Set<String> superGlobals;
    private Kind result;
    private final boolean declaredResult;
    private int nextSlot,nodes,depth,loops;
    ScalarControlFlowCompiler(Map<String,Local> parameters,Kind result,
                              BiFunction<List<Token>,Map<String,Local>,Value> expression,
                              Function<ExprStmtToken,List<Token>> tokens,Set<String> superGlobals){
        this.allocated=new LinkedHashMap<>(parameters);this.entry=new LinkedHashMap<>(parameters);this.result=result;
        this.declaredResult=result!=null;
        this.expression=expression;this.tokens=tokens;this.superGlobals=superGlobals;
        nextSlot=parameters.values().stream().mapToInt(local->local.slot()+local.kind().width()).max().orElse(0);
    }
    Kind result(){return result;}
    List<Local> locals(){return List.copyOf(allocated.values());}
    Block analyze(BodyStmtToken body){return block(body,new LinkedHashMap<>(entry));}
    private Value value(ExprStmtToken token,Map<String,Local> definite){return expression.apply(tokens.apply(token),definite);}
    private Block block(BodyStmtToken body,Map<String,Local> definite){
        if(body==null)return new Block(List.of(),true);
        require(++depth<=32);try{
            require(body.getInstructions()!=null);List<Statement> statements=new ArrayList<>();boolean falls=true;
            for(ExprStmtToken instruction:body.getInstructions()){
                require(falls && ++nodes<=1024);
                if(instruction.isSingle() && instruction.getSingle() instanceof ReturnStmtToken token){
                    require(token.getValue()!=null && !token.isImplicit());Value returned=value(token.getValue(),definite);
                    if(result==null)result=returned.kind();require(declaredResult?assignable(returned.kind(),result):returned.kind()==result);
                    statements.add(new Return(token.getMeta().getStartLine(),returned));falls=false;
                }else if(instruction.isSingle() && instruction.getSingle() instanceof IfStmtToken token){
                    Value condition=value(token.getCondition(),definite);Map<String,Local> yesState=new LinkedHashMap<>(definite),noState=new LinkedHashMap<>(definite);
                    Block yes=block(token.getBody(),yesState),no=block(token.getElseBody(),noState);
                    definite.clear();
                    if(yes.fallsThrough && no.fallsThrough){yesState.forEach((name,local)->{if(noState.get(name)==local)definite.put(name,local);});}
                    else if(yes.fallsThrough)definite.putAll(yesState);else if(no.fallsThrough)definite.putAll(noState);
                    falls=yes.fallsThrough || no.fallsThrough;statements.add(new Branch(token.getMeta().getStartLine(),condition,yes,no));
                }else if(instruction.isSingle() && instruction.getSingle() instanceof ForStmtToken token){
                    Block initialize=updates(token.getInitExpr(),definite);Map<String,Local> beforeLoop=new LinkedHashMap<>(definite);
                    List<ExprStmtToken> conditions=token.getConditionExpr();require(conditions!=null && conditions.size()<=1);
                    Value condition=conditions.isEmpty()?null:value(conditions.getFirst(),definite);
                    Block update=updates(token.getIterationExpr(),new LinkedHashMap<>(beforeLoop));
                    require(++loops<=16);Block loopBody;try{loopBody=block(token.getBody(),new LinkedHashMap<>(beforeLoop));}finally{loops--;}
                    statements.add(new Loop(token.getMeta().getStartLine(),initialize,condition,loopBody,update,false));
                    definite.clear();definite.putAll(beforeLoop);
                }else if(instruction.isSingle() && instruction.getSingle() instanceof WhileStmtToken token){
                    // Even do-while conditions must use entry-defined variables: a continue may bypass body stores.
                    Value condition=value(token.getCondition(),definite);Map<String,Local> beforeLoop=new LinkedHashMap<>(definite);
                    require(++loops<=16);Block loopBody;try{loopBody=block(token.getBody(),new LinkedHashMap<>(beforeLoop));}finally{loops--;}
                    Block empty=new Block(List.of(),true);
                    statements.add(new Loop(token.getMeta().getStartLine(),empty,condition,loopBody,empty,token.isDo()));
                    definite.clear();definite.putAll(beforeLoop);
                }else if(instruction.isSingle() && instruction.getSingle() instanceof DoStmtToken token){
                    Value condition=value(token.getCondition(),definite);Map<String,Local> beforeLoop=new LinkedHashMap<>(definite);
                    require(++loops<=16);Block loopBody;try{loopBody=block(token.getBody(),new LinkedHashMap<>(beforeLoop));}finally{loops--;}
                    Block empty=new Block(List.of(),true);
                    statements.add(new Loop(token.getMeta().getStartLine(),empty,condition,loopBody,empty,true));
                    definite.clear();definite.putAll(beforeLoop);
                }else if(instruction.isSingle() && instruction.getSingle() instanceof JumpStmtToken jump){
                    require((jump instanceof BreakStmtToken || jump instanceof ContinueStmtToken) && jump.getLevel()>0 && jump.getLevel()<=loops);
                    statements.add(new Jump(jump.getMeta().getStartLine(),jump.getLevel(),jump instanceof ContinueStmtToken));falls=false;
                }else statements.add(update(instruction,definite));
            }
            return new Block(statements,falls);
        }finally{depth--;}
    }
    private Block updates(List<ExprStmtToken> expressions,Map<String,Local> definite){
        require(expressions!=null && expressions.size()<=32);List<Statement> statements=new ArrayList<>();
        for(ExprStmtToken expr:expressions){require(++nodes<=1024);statements.add(update(expr,definite));}
        return new Block(statements,true);
    }
    private Store update(ExprStmtToken instruction,Map<String,Local> definite){
        List<Token> expressionTokens=tokens.apply(instruction);require(expressionTokens.size()>=2 && expressionTokens.getFirst() instanceof VariableExprToken);
        String name=((VariableExprToken)expressionTokens.getFirst()).getName();
        require(!name.equals("this") && !name.equals("GLOBALS") && !superGlobals.contains(name));
        Token operation=expressionTokens.getLast();Value assigned;
        if(operation.getClass()==AssignExprToken.class){
            require(!((AssignExprToken)operation).isAsReference() && expressionTokens.size()>=3);
            assigned=expression.apply(expressionTokens.subList(1,expressionTokens.size()-1),definite);
        }else{
            Local local=definite.get(name);require(local!=null && local.kind()!=Kind.BOOLEAN);
            if(operation.getClass()==IncExprToken.class || operation.getClass()==DecExprToken.class){
                require(expressionTokens.size()==2);assigned=binary(operation.getClass()==IncExprToken.class?"plus":"minus",read(local),one());
            }else{
                require(expressionTokens.size()>=3);
                String operator=operation.getClass()==AssignPlusExprToken.class?"plus":operation.getClass()==AssignMinusExprToken.class?"minus"
                        :operation.getClass()==AssignMulExprToken.class?"mul":operation.getClass()==AssignAndExprToken.class?"bitAnd"
                        :operation.getClass()==AssignOrExprToken.class?"bitOr":operation.getClass()==AssignXorExprToken.class?"bitXor"
                        :operation.getClass()==AssignShiftLeftExprToken.class?"bitShl":operation.getClass()==AssignShiftRightExprToken.class?"bitShr":null;
                require(operator!=null);assigned=binary(operator,read(local),expression.apply(expressionTokens.subList(1,expressionTokens.size()-1),definite));
            }
        }
        Local destination=allocated.get(name);
        if(destination==null){require(allocated.size()<256);destination=new Local(name,assigned.kind(),nextSlot);nextSlot+=assigned.kind().width();allocated.put(name,destination);}
        require(destination.kind()==assigned.kind());definite.put(name,destination);
        return new Store(instruction.getMeta().getStartLine(),destination,assigned);
    }

    static void truth(InsnList code,Value value,boolean wrapping){
        value.emit(code,wrapping);if(value.kind()==Kind.BOOLEAN)return;
        if(value.kind()==Kind.LONG){code.add(new InsnNode(LCONST_0));code.add(new InsnNode(LCMP));}
        else {code.add(new InsnNode(DCONST_0));code.add(new InsnNode(DCMPL));}
        LabelNode zero=new LabelNode(),end=new LabelNode();code.add(new JumpInsnNode(IFEQ,zero));
        code.add(new InsnNode(ICONST_1));code.add(new JumpInsnNode(GOTO,end));code.add(zero);code.add(new InsnNode(ICONST_0));code.add(end);
    }
}
