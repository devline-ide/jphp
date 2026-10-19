package org.develnext.jphp.core.compiler.jvm.statement.expr;

import org.develnext.jphp.core.compiler.jvm.misc.LocalVariable;
import org.develnext.jphp.core.compiler.jvm.statement.ExpressionStmtCompiler;
import org.develnext.jphp.core.tokenizer.token.stmt.StaticStmtToken;
import org.objectweb.asm.tree.*;
import org.develnext.jphp.core.compiler.jvm.statement.TraitStaticSitesCompiler;
import static org.objectweb.asm.Opcodes.ASTORE;
import php.runtime.Memory;
import php.runtime.env.Environment;

import static org.objectweb.asm.Opcodes.IFNONNULL;

public class StaticDefinitionCompiler extends BaseStatementCompiler<StaticStmtToken> {
    public StaticDefinitionCompiler(ExpressionStmtCompiler exprCompiler) {
        super(exprCompiler);
    }

    TraitStaticSitesCompiler.Key writePushNameForStaticVariable(LocalVariable local) {
        String name = method.clazz.isClosure()
                ? local.name
                : method.getEntity().getClazz().getInternalName() + "\0" + local.name + "\0" + method.getMethodId();

        if (method.getEntity().getClazz().isTrait()) {
            expr.writePushSelf(false);
            expr.writePushConstString(name);
            expr.writeSysDynamicCall(String.class, "concat", String.class, String.class);
            MethodInsnNode concat=(MethodInsnNode)expr.getMethodNode().instructions.getLast();
            LdcInsnNode literal=(LdcInsnNode)concat.getPrevious();
            MethodInsnNode self=(MethodInsnNode)literal.getPrevious();
            return new TraitStaticSitesCompiler.Key(self,literal,concat);
        } else {
            expr.writePushConstString(name);
        }
        return null;
    }


    @Override
    public void write(StaticStmtToken token) {
        LocalVariable local = method.getLocalVariable(token.getVariable().getName());
        assert local != null;
        if (method.clazz.isClosure()) method.primitiveCells().staticLocal(local.index,local.name);

        LabelNode end = new LabelNode();
        boolean isClosure = method.clazz.isClosure();
        LabelNode runtimeStart = isClosure && method.ownership().runtimeEnabled() ? new LabelNode() : null;
        if (runtimeStart != null) add(runtimeStart);

        if (isClosure)
            expr.writeVarLoad("~this");
        else
            expr.writePushEnv();

        AbstractInsnNode getterEnv=expr.getMethodNode().instructions.getLast();
        TraitStaticSitesCompiler.Key getter=writePushNameForStaticVariable(local);

        expr.writeSysDynamicCall(isClosure ? null : Environment.class, "getStatic", Memory.class, String.class);
        AbstractInsnNode getterCall=expr.getMethodNode().instructions.getLast();
        if (isClosure) method.primitiveCells().staticGetter((MethodInsnNode)getterCall);
        expr.writePushDup();

        add(new JumpInsnNode(IFNONNULL, end));
        expr.stackPop();

        expr.writePopAll(1);
        if (isClosure)
            expr.writeVarLoad("~this");
        else
            expr.writePushEnv();

        LocalVariable keyScratch=null,envScratch=null,valueScratch=null;
        VarInsnNode envStore=null,keyStore=null,valueStore=null,envRead=null,keyRead=null,valueRead=null;
        if(getter!=null) {
            String suffix=method.getMethodId()+"~"+method.traitStaticSites().size();
            envScratch=method.addLocalVariable("~traitStaticEnv"+suffix,null,Environment.class);
            keyScratch=method.addLocalVariable("~traitStaticKey"+suffix,null,String.class);
            valueScratch=method.addLocalVariable("~traitStaticValue"+suffix,null,Memory.class);
            envStore=new VarInsnNode(ASTORE,envScratch.index);add(envStore);expr.stackPop();
        }
        TraitStaticSitesCompiler.Key creator=writePushNameForStaticVariable(local);
        if(getter!=null){keyStore=new VarInsnNode(ASTORE,keyScratch.index);add(keyStore);expr.stackPop();}

        if (token.getInitValue() != null){
            expr.writeExpression(token.getInitValue(), true, false, true);
        } else {
            expr.writePushNull();
        }
        expr.writePopBoxing(true);
        if(getter!=null) {
            valueStore=new VarInsnNode(ASTORE,valueScratch.index);add(valueStore);expr.stackPop();
            expr.writeVarLoad(envScratch);envRead=(VarInsnNode)expr.getMethodNode().instructions.getLast();
            expr.writeVarLoad(keyScratch);keyRead=(VarInsnNode)expr.getMethodNode().instructions.getLast();
            expr.writeVarLoad(valueScratch);valueRead=(VarInsnNode)expr.getMethodNode().instructions.getLast();
        }
        expr.writeSysDynamicCall(isClosure ? null : Environment.class, "getOrCreateStatic",
                Memory.class,
                String.class, Memory.class);
        if(isClosure)method.primitiveCells().staticGetter((MethodInsnNode)expr.getMethodNode().instructions.getLast());
        if(getter!=null)method.traitStaticSites().record(local.name,getter,creator,getterEnv,getterCall,
                envStore,keyStore,valueStore,envRead,keyRead,valueRead,expr.getMethodNode().instructions.getLast());

        add(end);
        if (compiler.getModule().getPhpLanguageProfile() == php.runtime.common.PhpLanguageProfile.PHP_8_5 && !method.isExternal()) {
            // A closure static CV borrows the table slot until PHP actually exports a reference.
            // Promoting it here makes ordinary statics share a CELL across closure clones.
            if (!isClosure)
                expr.writeSysStaticCall(php.runtime.memory.ReferenceMemory.class, "exportReference", Memory.class, Memory.class);
            if (methodStatement.isDynamicLocal()) {
                if (isClosure && method.ownership().runtimeEnabled()) {
                    method.ownership().runtimeBindStaticLocal(expr.getMethodNode().instructions,local);
                    method.primitiveCells().staticBorrow((MethodInsnNode)expr.getMethodNode().instructions.getLast(),local.index);
                }
                else {
                    expr.writeVarLoad(local);
                    expr.writeSysStaticCall(Memory.class, "assignRefRight", Memory.class, Memory.class, Memory.class);
                }
                expr.writePopAll(1);
            } else {
                expr.writeSysStaticCall(php.runtime.memory.ReferenceMemory.class, "valueOf", Memory.class, Memory.class);
                if (isClosure) method.primitiveCells().staticBorrow((MethodInsnNode)expr.getMethodNode().instructions.getLast(),local.index);
                expr.writeVarStore(local, false, false);
            }
        } else expr.writeVarStore(local, false, false);
        if (runtimeStart != null) {
            LabelNode runtimeEnd = new LabelNode();add(runtimeEnd);
            method.ownership().runtimeStaticAddress(runtimeStart,runtimeEnd);
        }
    }
}
