package org.develnext.jphp.pcre;

import java.nio.charset.StandardCharsets;
import php.runtime.Memory;
import php.runtime.annotation.Reflection.Arg;
import php.runtime.annotation.Reflection.Optional;
import php.runtime.annotation.Reflection.Signature;
import php.runtime.annotation.Runtime.Reference;
import php.runtime.common.HintType;
import php.runtime.env.Environment;
import php.runtime.env.TraceInfo;
import php.runtime.ext.support.compile.FunctionsContainer;
import php.runtime.invoke.PhpCallFrame;
import php.runtime.invoke.PhpMethodResolver.InvalidCallback;
import php.runtime.memory.ArrayMemory;
import php.runtime.memory.LongMemory;
import php.runtime.memory.PhpByteStringMemory;

/** Internal PHP callbacks retain their target; all captures and native handles stay within the call. */
public final class PregCallbackFunctions extends FunctionsContainer {
    @Signature(value={@Arg(value="pattern",typeDeclaration="array|string",readOnly=true),@Arg(value="callback",type=HintType.CALLABLE,readOnly=true),@Arg(value="subject",typeDeclaration="array|string",readOnly=true),@Arg(value="limit",type=HintType.INT,readOnly=true,optional=@Optional("-1")),@Arg(value="count",reference=true,optional=@Optional("NULL")),@Arg(value="flags",type=HintType.INT,readOnly=true,optional=@Optional("0"))},returnType="array|string|null")
    public static Memory preg_replace_callback(Environment env,TraceInfo trace,Memory pattern,Memory callback,Memory subject,Memory limit,@Reference Memory count,Memory flags)throws Throwable {
        return run(env,trace,pattern,callback,subject,limit,count,flags,true);
    }
    @Signature(value={@Arg(value="pattern",typeDeclaration="array|string",readOnly=true),@Arg(value="callback",type=HintType.CALLABLE,readOnly=true),@Arg(value="subject",typeDeclaration="array|string",readOnly=true),@Arg(value="limit",type=HintType.INT,readOnly=true,optional=@Optional("-1")),@Arg(value="count",reference=true,optional=@Optional("NULL")),@Arg(value="flags",type=HintType.INT,readOnly=true,optional=@Optional("0"))},returnType="array|string|null")
    public static Memory preg_replace_callback(Environment env,TraceInfo trace,Memory pattern,Memory callback,Memory subject,Memory limit,@Reference Memory count)throws Throwable {
        return run(env,trace,pattern,callback,subject,limit,count,LongMemory.valueOf(0),true);
    }
    @Signature(value={@Arg(value="pattern",typeDeclaration="array|string",readOnly=true),@Arg(value="callback",type=HintType.CALLABLE,readOnly=true),@Arg(value="subject",typeDeclaration="array|string",readOnly=true),@Arg(value="limit",type=HintType.INT,readOnly=true,optional=@Optional("-1")),@Arg(value="count",reference=true,optional=@Optional("NULL")),@Arg(value="flags",type=HintType.INT,readOnly=true,optional=@Optional("0"))},returnType="array|string|null")
    public static Memory preg_replace_callback(Environment env,TraceInfo trace,Memory pattern,Memory callback,Memory subject,Memory limit)throws Throwable {
        return run(env,trace,pattern,callback,subject,limit,Memory.NULL,LongMemory.valueOf(0),false);
    }
    @Signature(value={@Arg(value="pattern",typeDeclaration="array|string",readOnly=true),@Arg(value="callback",type=HintType.CALLABLE,readOnly=true),@Arg(value="subject",typeDeclaration="array|string",readOnly=true),@Arg(value="limit",type=HintType.INT,readOnly=true,optional=@Optional("-1")),@Arg(value="count",reference=true,optional=@Optional("NULL")),@Arg(value="flags",type=HintType.INT,readOnly=true,optional=@Optional("0"))},returnType="array|string|null")
    public static Memory preg_replace_callback(Environment env,TraceInfo trace,Memory pattern,Memory callback,Memory subject)throws Throwable {
        return run(env,trace,pattern,callback,subject,LongMemory.valueOf(-1),Memory.NULL,LongMemory.valueOf(0),false);
    }
    @Signature(value={@Arg(value="pattern",type=HintType.ARRAY,readOnly=true),@Arg(value="subject",typeDeclaration="array|string",readOnly=true),@Arg(value="limit",type=HintType.INT,readOnly=true,optional=@Optional("-1")),@Arg(value="count",reference=true,optional=@Optional("NULL")),@Arg(value="flags",type=HintType.INT,readOnly=true,optional=@Optional("0"))},returnType="array|string|null")
    public static Memory preg_replace_callback_array(Environment env,TraceInfo trace,Memory pattern,Memory subject,Memory limit,@Reference Memory count,Memory flags)throws Throwable {
        return runMap(env,trace,pattern,subject,limit,count,flags,true);
    }
    @Signature(value={@Arg(value="pattern",type=HintType.ARRAY,readOnly=true),@Arg(value="subject",typeDeclaration="array|string",readOnly=true),@Arg(value="limit",type=HintType.INT,readOnly=true,optional=@Optional("-1")),@Arg(value="count",reference=true,optional=@Optional("NULL")),@Arg(value="flags",type=HintType.INT,readOnly=true,optional=@Optional("0"))},returnType="array|string|null")
    public static Memory preg_replace_callback_array(Environment env,TraceInfo trace,Memory pattern,Memory subject,Memory limit,@Reference Memory count)throws Throwable {
        return runMap(env,trace,pattern,subject,limit,count,LongMemory.valueOf(0),true);
    }
    @Signature(value={@Arg(value="pattern",type=HintType.ARRAY,readOnly=true),@Arg(value="subject",typeDeclaration="array|string",readOnly=true),@Arg(value="limit",type=HintType.INT,readOnly=true,optional=@Optional("-1")),@Arg(value="count",reference=true,optional=@Optional("NULL")),@Arg(value="flags",type=HintType.INT,readOnly=true,optional=@Optional("0"))},returnType="array|string|null")
    public static Memory preg_replace_callback_array(Environment env,TraceInfo trace,Memory pattern,Memory subject,Memory limit)throws Throwable {
        return runMap(env,trace,pattern,subject,limit,Memory.NULL,LongMemory.valueOf(0),false);
    }
    @Signature(value={@Arg(value="pattern",type=HintType.ARRAY,readOnly=true),@Arg(value="subject",typeDeclaration="array|string",readOnly=true),@Arg(value="limit",type=HintType.INT,readOnly=true,optional=@Optional("-1")),@Arg(value="count",reference=true,optional=@Optional("NULL")),@Arg(value="flags",type=HintType.INT,readOnly=true,optional=@Optional("0"))},returnType="array|string|null")
    public static Memory preg_replace_callback_array(Environment env,TraceInfo trace,Memory pattern,Memory subject)throws Throwable {
        return runMap(env,trace,pattern,subject,LongMemory.valueOf(-1),Memory.NULL,LongMemory.valueOf(0),false);
    }
    private static Memory run(Environment env,TraceInfo trace,Memory patternArg,Memory callbackArg,Memory subjectArg,Memory limitArg,Memory count,Memory flagsArg,boolean capture)throws Throwable {
        String function="preg_replace_callback";
        try(var inputs=new PregReplaceFunctions.Inputs(true,patternArg,callbackArg,subjectArg)){
            Memory pattern=PregReplaceFunctions.arrayOrString(env,trace,function,inputs.values[0],1,"pattern");
            try(var callback=callback(env,trace,inputs.values[1],false)){
                Memory subject=PregReplaceFunctions.arrayOrString(env,trace,function,inputs.values[2],3,"subject");
                long limit=PregFunctions.integer(env,trace,function,limitArg,4,"limit");
                long flags=PregFunctions.integer(env,trace,function,flagsArg,6,"flags");
                var replaced=new PregReplaceFunctions.Counter();
                try{return subjects(env,trace,function,pattern,callback,subject,limit,flags,replaced);}
                finally{if(capture)count.assign(LongMemory.valueOf(replaced.value));}
            }
        }
    }
    private static Memory runMap(Environment env,TraceInfo trace,Memory patternArg,Memory subjectArg,Memory limitArg,Memory count,Memory flagsArg,boolean capture)throws Throwable {
        String function="preg_replace_callback_array";
        try(var inputs=new PregReplaceFunctions.Inputs(true,patternArg,subjectArg)){
            Memory pattern=inputs.values[0].toValue();
            if(!pattern.isArray())PregFunctions.typeError(env,trace,function,pattern,1,"pattern","array");
            Memory subject=PregReplaceFunctions.arrayOrString(env,trace,function,inputs.values[1],2,"subject");
            long limit=PregFunctions.integer(env,trace,function,limitArg,3,"limit");
            long flags=PregFunctions.integer(env,trace,function,flagsArg,5,"flags");
            var replaced=new PregReplaceFunctions.Counter();
            Memory result=subject;
            var patterns=pattern.toValue(ArrayMemory.class).getNewIterator(env,false,false);
            while(patterns.next()){
                Memory regex=patterns.getMemoryKey().toValue();
                if(!regex.isString())env.exception(trace,php.runtime.lang.exception.BaseTypeError.class,
                        "%s(): Argument #1 ($pattern) must contain only string patterns as keys",function);
                try(var callback=callback(env,trace,patterns.getValue(),true)){
                    result=subjects(env,trace,function,regex,callback,result,limit,flags,replaced);
                    if(result.isNull())return Memory.NULL;
                }
            }
            // PHP publishes this count only after every pattern/callback pair completes successfully.
            if(capture)count.assign(LongMemory.valueOf(replaced.value));
            return result==subject?result.toImmutable():result;
        }
    }
    private static PhpCallFrame callback(Environment env,TraceInfo trace,Memory value,boolean array)throws Throwable {
        try{return PhpCallFrame.acquireInternalRetainedCallable(value,env,trace);}
        catch(InvalidCallback invalid){
            throw invalid.typeError(env,trace,array
                    ?"preg_replace_callback_array(): Argument #1 ($pattern) must contain only valid callbacks"
                    :"preg_replace_callback(): Argument #2 ($callback) must be a valid callback, "+(invalid.getMessage()==null?"(null)":invalid.getMessage()));
        }
    }
    private static Memory subjects(Environment env,TraceInfo trace,String function,Memory pattern,PhpCallFrame callback,Memory subject,long limit,long flags,PregReplaceFunctions.Counter count)throws Throwable {
        if(!subject.isArray()){
            byte[] result=patterns(env,trace,function,pattern,callback,bytes(subject),limit,flags,count);
            return result==null?Memory.NULL:new PhpByteStringMemory(result);
        }
        ArrayMemory output=new ArrayMemory();var iterator=subject.toValue(ArrayMemory.class).getNewIterator(env,false,false);
        while(iterator.next()){
            byte[] text=bytes(php.runtime.PhpByteStrings.stringValue(iterator.getValue(),env,trace));
            byte[] result=patterns(env,trace,function,pattern,callback,text,limit,flags,count);
            if(result!=null)output.put(iterator.getMemoryKey(),new PhpByteStringMemory(result));
        }
        return output;
    }
    private static byte[] patterns(Environment env,TraceInfo trace,String function,Memory pattern,PhpCallFrame callback,byte[] subject,long limit,long flags,PregReplaceFunctions.Counter count)throws Throwable {
        if(!pattern.isArray())return one(env,trace,function,bytes(pattern),callback,subject,limit,flags,count);
        var iterator=pattern.toValue(ArrayMemory.class).getNewIterator(env,false,false);
        while(iterator.next()){
            byte[] regex=bytes(php.runtime.PhpByteStrings.stringValue(iterator.getValue(),env,trace));
            subject=one(env,trace,function,regex,callback,subject,limit,flags,count);
            if(subject==null)break;
        }
        return subject;
    }
    private static byte[] one(Environment env,TraceInfo trace,String function,byte[] pattern,PhpCallFrame callback,byte[] subject,long limit,long flags,PregReplaceFunctions.Counter count)throws Throwable {
        return PregReplaceFunctions.replaceOne(env,trace,function,pattern,null,subject,limit,count,(compiled,match)->{
            int available=match.result()==0?compiled.captureCount+1:match.result();
            ArrayMemory captures=PregFunctions.globalRow(subject,compiled,match,available,(flags&512)!=0,(flags&256)!=0);
            Memory result=callback.invokeInternal(new Memory[]{captures});
            return bytes(php.runtime.PhpByteStrings.stringValue(result,env,trace));
        });
    }
    private static byte[] bytes(Memory value){return value.getBinaryBytes(StandardCharsets.UTF_8);}
}
