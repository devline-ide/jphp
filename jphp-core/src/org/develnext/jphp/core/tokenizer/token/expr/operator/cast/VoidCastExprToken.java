package org.develnext.jphp.core.tokenizer.token.expr.operator.cast;
import org.develnext.jphp.core.tokenizer.TokenMeta;
import org.develnext.jphp.core.tokenizer.TokenType;
import php.runtime.Memory;
import php.runtime.env.Environment;
import php.runtime.env.TraceInfo;
public final class VoidCastExprToken extends CastExprToken {
    public VoidCastExprToken(TokenMeta meta){super(meta,TokenType.T_J_VOID_CAST);}
    @Override public Class<?> getResultClass(){return Memory.class;}
    @Override public Memory calc(Environment env,TraceInfo trace,Memory left,Memory right){return Memory.NULL;}
    @Override public String getCode(){return "toUnset";}
}
