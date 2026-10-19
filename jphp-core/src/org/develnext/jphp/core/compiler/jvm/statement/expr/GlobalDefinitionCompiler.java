package org.develnext.jphp.core.compiler.jvm.statement.expr;

import org.develnext.jphp.core.compiler.jvm.misc.LocalVariable;
import org.develnext.jphp.core.compiler.jvm.statement.ExpressionStmtCompiler;
import org.develnext.jphp.core.tokenizer.token.expr.ValueExprToken;
import org.develnext.jphp.core.tokenizer.token.expr.value.GetVarExprToken;
import org.develnext.jphp.core.tokenizer.token.expr.value.VariableExprToken;
import org.develnext.jphp.core.tokenizer.token.stmt.GlobalStmtToken;
import php.runtime.Memory;
import php.runtime.env.Environment;
import php.runtime.exceptions.CriticalException;

public class GlobalDefinitionCompiler extends BaseStatementCompiler<GlobalStmtToken> {
    public GlobalDefinitionCompiler(ExpressionStmtCompiler exprCompiler) {
        super(exprCompiler);
    }

    @Override
    public void write(GlobalStmtToken token) {
        for(ValueExprToken variable : token.getVariables()){
            if (variable instanceof VariableExprToken) {
                LocalVariable local = method.getLocalVariable(((VariableExprToken) variable).getName());
                assert local != null;
                if (expr.writeNamedGlobalImport(local)) continue;

                expr.writePushEnv();
                expr.writePushConstString(local.name);
                expr.writeSysDynamicCall(Environment.class, "getOrCreateGlobal", Memory.class, String.class);
                if (compiler.getModule().getPhpLanguageProfile() == php.runtime.common.PhpLanguageProfile.PHP_8_5 && !method.isExternal()) {
                    expr.writeSysStaticCall(php.runtime.memory.ReferenceMemory.class, "exportReference", Memory.class, Memory.class);
                    if (methodStatement.isDynamicLocal()) {
                        expr.writeVarLoad(local);
                        expr.writeSysStaticCall(Memory.class, "assignRefRight", Memory.class, Memory.class, Memory.class);
                        expr.writePopAll(1);
                    } else {
                        expr.writeSysStaticCall(php.runtime.memory.ReferenceMemory.class, "valueOf", Memory.class, Memory.class);
                        expr.writeVarStore(local, false, false);
                    }
                } else expr.writeVarStore(local, false, false);
            } else if (variable instanceof GetVarExprToken) {
                BaseExprCompiler<GetVarExprToken> compiler =
                        (BaseExprCompiler<GetVarExprToken>) expr.getCompiler(GetVarExprToken.class);

                if (compiler == null)
                    throw new CriticalException("Cannot find a valid compiler for " + GetVarExprToken.class.getName());

                compiler.write((GetVarExprToken)variable, true);

                expr.writePushEnv();
                Memory name = expr.writeExpression(((GetVarExprToken)variable).getName(), true, true, true);
                if (name != null) {
                    expr.writePushConstString(name.toString());
                } else {
                    expr.writePopString();
                }
                expr.writeSysDynamicCall(Environment.class, "getOrCreateGlobal", Memory.class, String.class);

                boolean modern = this.compiler.getModule().getPhpLanguageProfile() == php.runtime.common.PhpLanguageProfile.PHP_8_5;
                if (modern) expr.writeSysStaticCall(php.runtime.memory.ReferenceMemory.class, "exportReference", Memory.class, Memory.class);
                expr.writeSysDynamicCall(Memory.class, modern ? "assignRef" : "assign", Memory.class, Memory.class);
                expr.writePopAll(1);
            }
        }
    }
}
