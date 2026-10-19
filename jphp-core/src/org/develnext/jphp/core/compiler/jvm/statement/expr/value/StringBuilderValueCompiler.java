package org.develnext.jphp.core.compiler.jvm.statement.expr.value;

import org.develnext.jphp.core.compiler.common.misc.StackItem;
import org.develnext.jphp.core.compiler.jvm.statement.ExpressionStmtCompiler;
import org.develnext.jphp.core.compiler.jvm.statement.expr.BaseExprCompiler;
import org.develnext.jphp.core.tokenizer.token.Token;
import org.develnext.jphp.core.tokenizer.token.expr.ValueExprToken;
import org.develnext.jphp.core.tokenizer.token.expr.value.StringBuilderExprToken;
import org.develnext.jphp.core.tokenizer.token.stmt.ExprStmtToken;
import php.runtime.Memory;
import php.runtime.memory.BinaryMemory;

import java.util.List;

public class StringBuilderValueCompiler extends BaseExprCompiler<StringBuilderExprToken> {
    public StringBuilderValueCompiler(ExpressionStmtCompiler exprCompiler) {
        super(exprCompiler);
    }

    public void writeBuilder(List<Token> tokens) {
        writeBuilder(tokens, false);
    }

    public void writeBuilder(List<Token> tokens, boolean binary) {
        if(compiler.getModule().getPhpLanguageProfile()==php.runtime.common.PhpLanguageProfile.PHP_8_5) {
            writeModernBuilder(tokens);return;
        }
        expr.writePushNewObject(StringBuilder.class);

        for(Token el : tokens){
            //writePushDup();
            if (el instanceof ValueExprToken){
                expr.writePush((ValueExprToken) el, true, false);
            } else if (el instanceof ExprStmtToken){
                //unexpectedToken(el);
                expr.writeExpression((ExprStmtToken) el, true, false, true);
            } else
                expr.unexpectedToken(el);

            StackItem.Type peek = expr.stackPeek().type;
            if (!peek.isConstant()) {
                expr.writeSysDynamicCall(StringBuilder.class, "append", StringBuilder.class, Object.class);
            } else
                expr.writeSysDynamicCall(StringBuilder.class, "append", StringBuilder.class, peek.toClass());
        }

        expr.writeSysDynamicCall(StringBuilder.class, "toString", String.class);

        if (binary) {
            expr.writeSysStaticCall(BinaryMemory.class, "valueOf", Memory.class, String.class);
        }
    }

    private void writeModernBuilder(List<Token> tokens) {
        expr.writePushNewObject(StringBuilder.class);
        for(Token token:tokens) {
            if(token instanceof ValueExprToken)expr.writePush((ValueExprToken)token,true,false);
            else if(token instanceof ExprStmtToken)expr.writeExpression((ExprStmtToken)token,true,false,true);
            else expr.unexpectedToken(token);
            StackItem.Type type=expr.stackPeek().type;Class argument;
            if(type==StackItem.Type.STRING_VALUE)argument=Object.class;
            else if(type==StackItem.Type.STRING)argument=String.class;
            else if(type==StackItem.Type.LONG)argument=long.class;
            else if(type==StackItem.Type.DOUBLE)argument=double.class;
            else if(type==StackItem.Type.BOOL)argument=boolean.class;
            else if(type==StackItem.Type.CHAR)argument=char.class;
            else if(type==StackItem.Type.FLOAT){expr.writePopDouble();argument=double.class;}
            else if(type.isLikeInt()){expr.writePopLong();argument=long.class;}
            else {expr.writePopBoxing();argument=Memory.class;}
            expr.writePushEnv();expr.writePushTraceInfo(token);
            expr.writeSysStaticCall(argument==Object.class ? php.runtime.PhpByteStringValues.class : php.runtime.PhpByteStringBuilders.class,
                    "append",Object.class,Object.class,argument,php.runtime.env.Environment.class,php.runtime.env.TraceInfo.class);
        }
        expr.writeSysStaticCall(php.runtime.PhpByteStringBuilders.class,"finishObject",Object.class,Object.class);
        expr.markStringValue();
    }

    @Override
    public void write(StringBuilderExprToken value, boolean returnValue) {
        writeBuilder(value.getExpression(), value.isBinary());
    }
}
