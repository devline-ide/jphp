package org.develnext.jphp.zend.ext.standard;

import php.runtime.Memory;
import php.runtime.annotation.Reflection.Arg;
import php.runtime.annotation.Reflection.Signature;
import php.runtime.common.HintType;
import php.runtime.env.Environment;
import php.runtime.env.TraceInfo;
import php.runtime.ext.support.compile.FunctionsContainer;
import php.runtime.invoke.PhpCallFrame;
import php.runtime.lang.exception.BaseArgumentCountError;
import php.runtime.lang.exception.BaseTypeError;
import php.runtime.memory.ArrayMemory;
import php.runtime.memory.ObjectMemory;

/** PHP8.4 searches share ordered traversal and genuine internal callback invocation. */
public class Php85ArrayFunctions extends FunctionsContainer {
    @Signature(value={@Arg(value="array",type=HintType.ARRAY,readOnly=true),@Arg(value="callback",type=HintType.CALLABLE)},result=@Arg(type=HintType.MIXED))
    public static Memory array_find(Environment env,TraceInfo trace,@php.runtime.annotation.Runtime.Reference Memory... args)throws Throwable {
        return scan(env,trace,"array_find",args,0);
    }
    @Signature(value={@Arg(value="array",type=HintType.ARRAY,readOnly=true),@Arg(value="callback",type=HintType.CALLABLE)},result=@Arg(type=HintType.MIXED))
    public static Memory array_find_key(Environment env,TraceInfo trace,@php.runtime.annotation.Runtime.Reference Memory... args)throws Throwable {
        return scan(env,trace,"array_find_key",args,1);
    }
    @Signature(value={@Arg(value="array",type=HintType.ARRAY,readOnly=true),@Arg(value="callback",type=HintType.CALLABLE)},result=@Arg(type=HintType.BOOLEAN))
    public static Memory array_any(Environment env,TraceInfo trace,@php.runtime.annotation.Runtime.Reference Memory... args)throws Throwable {
        return scan(env,trace,"array_any",args,2);
    }
    @Signature(value={@Arg(value="array",type=HintType.ARRAY,readOnly=true),@Arg(value="callback",type=HintType.CALLABLE)},result=@Arg(type=HintType.BOOLEAN))
    public static Memory array_all(Environment env,TraceInfo trace,@php.runtime.annotation.Runtime.Reference Memory... args)throws Throwable {
        return scan(env,trace,"array_all",args,3);
    }

    private static Memory scan(Environment env,TraceInfo trace,String function,Memory[] args,int mode)throws Throwable {
        int supplied=args==null?0:args.length;
        if(supplied!=2)env.exception(trace,BaseArgumentCountError.class,"%s() expects exactly 2 arguments, %d given",function,supplied);
        Memory input=args[0].toValue();
        if(!input.isArray()) {
            String given=input.isResource()?"resource":input.isObject()?input.toValue(ObjectMemory.class).getReflection().getName()
                    :input.isNull()?"null":input.getRealType()==Memory.Type.INT?"int"
                    :input.getRealType()==Memory.Type.BOOL?(input.toBoolean()?"true":"false"):input.getRealType().toString();
            env.exception(trace,BaseTypeError.class,"%s(): Argument #1 ($array) must be of type array, %s given",function,given);
        }
        ArrayMemory array=input.toValue(ArrayMemory.class);
        var callback=callback(env,trace,function,args[1]); // Validate even when the array is empty.
        var iterator=array.getNewIterator(env);
        while(iterator.next()) {
            Memory key=iterator.getMemoryKey();
            boolean match=callback.invokeInternal(new Memory[]{iterator.getValue().toImmutable(),key.toImmutable()}).toBoolean();
            if(mode==3) {if(!match)return Memory.FALSE;}
            else if(match) {
                if(mode==2)return Memory.TRUE;
                if(mode==1)return key.toImmutable();
                // A shared CELL may have changed inside the callback; read the physical entry now.
                return array.valueOfIndex(key).toImmutable();
            }
        }
        return mode==3?Memory.TRUE:mode==2?Memory.FALSE:Memory.NULL;
    }

    private static PhpCallFrame callback(Environment env,TraceInfo trace,String function,Memory value) {
        try {return PhpCallFrame.acquireInternalCallable(value,env,trace);}
        catch(php.runtime.invoke.PhpMethodResolver.InvalidCallback failure) {
            env.exception(trace,BaseTypeError.class,"%s(): Argument #2 ($callback) must be a valid callback, %s",function,failure.getMessage());
            return null;
        }
    }
}
