package org.develnext.jphp.pcre;

import java.io.ByteArrayOutputStream;
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
import php.runtime.exceptions.support.ErrorType;
import php.runtime.memory.ArrayMemory;
import php.runtime.memory.LongMemory;
import php.runtime.memory.PhpByteStringMemory;

/** Byte-oriented replacement. Array views and native handles stay local to one PHP call. */
public final class PregReplaceFunctions extends FunctionsContainer {
    @Signature(value={@Arg(value="pattern",typeDeclaration="array|string",readOnly=true),@Arg(value="replacement",typeDeclaration="array|string",readOnly=true),@Arg(value="subject",typeDeclaration="array|string",readOnly=true),@Arg(value="limit",type=HintType.INT,readOnly=true,optional=@Optional("-1")),@Arg(value="count",reference=true,optional=@Optional("NULL"))},returnType="array|string|null")
    public static Memory preg_replace(Environment env,TraceInfo trace,Memory pattern,Memory replacement,Memory subject,Memory limit,@Reference Memory count)throws Throwable {
        return run(env,trace,"preg_replace",pattern,replacement,subject,limit,count,true,false);
    }
    @Signature(value={@Arg(value="pattern",typeDeclaration="array|string",readOnly=true),@Arg(value="replacement",typeDeclaration="array|string",readOnly=true),@Arg(value="subject",typeDeclaration="array|string",readOnly=true),@Arg(value="limit",type=HintType.INT,readOnly=true,optional=@Optional("-1")),@Arg(value="count",reference=true,optional=@Optional("NULL"))},returnType="array|string|null")
    public static Memory preg_replace(Environment env,TraceInfo trace,Memory pattern,Memory replacement,Memory subject,Memory limit)throws Throwable {
        return run(env,trace,"preg_replace",pattern,replacement,subject,limit,Memory.NULL,false,false);
    }
    @Signature(value={@Arg(value="pattern",typeDeclaration="array|string",readOnly=true),@Arg(value="replacement",typeDeclaration="array|string",readOnly=true),@Arg(value="subject",typeDeclaration="array|string",readOnly=true),@Arg(value="limit",type=HintType.INT,readOnly=true,optional=@Optional("-1")),@Arg(value="count",reference=true,optional=@Optional("NULL"))},returnType="array|string|null")
    public static Memory preg_replace(Environment env,TraceInfo trace,Memory pattern,Memory replacement,Memory subject)throws Throwable {
        return run(env,trace,"preg_replace",pattern,replacement,subject,LongMemory.valueOf(-1),Memory.NULL,false,false,
                !php.runtime.invoke.NativeFunctionContext.isInternalCall(env,trace,"preg_replace"));
    }
    @Signature(value={@Arg(value="pattern",typeDeclaration="array|string",readOnly=true),@Arg(value="replacement",typeDeclaration="array|string",readOnly=true),@Arg(value="subject",typeDeclaration="array|string",readOnly=true),@Arg(value="limit",type=HintType.INT,readOnly=true,optional=@Optional("-1")),@Arg(value="count",reference=true,optional=@Optional("NULL"))},returnType="array|string|null")
    public static Memory preg_filter(Environment env,TraceInfo trace,Memory pattern,Memory replacement,Memory subject,Memory limit,@Reference Memory count)throws Throwable {
        return run(env,trace,"preg_filter",pattern,replacement,subject,limit,count,true,true);
    }
    @Signature(value={@Arg(value="pattern",typeDeclaration="array|string",readOnly=true),@Arg(value="replacement",typeDeclaration="array|string",readOnly=true),@Arg(value="subject",typeDeclaration="array|string",readOnly=true),@Arg(value="limit",type=HintType.INT,readOnly=true,optional=@Optional("-1")),@Arg(value="count",reference=true,optional=@Optional("NULL"))},returnType="array|string|null")
    public static Memory preg_filter(Environment env,TraceInfo trace,Memory pattern,Memory replacement,Memory subject,Memory limit)throws Throwable {
        return run(env,trace,"preg_filter",pattern,replacement,subject,limit,Memory.NULL,false,true);
    }
    @Signature(value={@Arg(value="pattern",typeDeclaration="array|string",readOnly=true),@Arg(value="replacement",typeDeclaration="array|string",readOnly=true),@Arg(value="subject",typeDeclaration="array|string",readOnly=true),@Arg(value="limit",type=HintType.INT,readOnly=true,optional=@Optional("-1")),@Arg(value="count",reference=true,optional=@Optional("NULL"))},returnType="array|string|null")
    public static Memory preg_filter(Environment env,TraceInfo trace,Memory pattern,Memory replacement,Memory subject)throws Throwable {
        return run(env,trace,"preg_filter",pattern,replacement,subject,LongMemory.valueOf(-1),Memory.NULL,false,true);
    }
    private static Memory run(Environment env,TraceInfo trace,String function,Memory patternArg,Memory replacementArg,Memory subjectArg,Memory limitArg,Memory count,boolean capture,boolean filter)throws Throwable {
        return run(env,trace,function,patternArg,replacementArg,subjectArg,limitArg,count,capture,filter,false);
    }
    private static Memory run(Environment env,TraceInfo trace,String function,Memory patternArg,Memory replacementArg,Memory subjectArg,Memory limitArg,Memory count,boolean capture,boolean filter,boolean frameless)throws Throwable {
        // Normal calls capture arrays before coercion. PHP borrows arrays in direct three-argument replacement.
        try(var inputs=new Inputs(!frameless,patternArg,replacementArg,subjectArg)){
            Memory pattern=arrayOrString(env,trace,function,inputs.values[0],1,"pattern");
            Memory replacement=arrayOrString(env,trace,function,inputs.values[1],2,"replacement");
            Memory subject=arrayOrString(env,trace,function,inputs.values[2],3,"subject");
            long limit=PregFunctions.integer(env,trace,function,limitArg,4,"limit");
            if(replacement.isArray()&&!pattern.isArray())env.exception(trace,php.runtime.lang.exception.BaseTypeError.class,
                    "%s(): Argument #1 ($pattern) must be of type array when argument #2 ($replacement) is an array, string given",function);
            Counter replaced=new Counter();
            try(CompiledPatterns compiledPatterns=subject.isArray()&&subject.toValue(ArrayMemory.class).size()>1?new CompiledPatterns():null){
                if(!subject.isArray()){
                    byte[] result=replaceSubject(env,trace,function,pattern,replacement,bytes(subject),limit,replaced,compiledPatterns);
                    return result==null||filter&&replaced.value==0?Memory.NULL:new PhpByteStringMemory(result);
                }
                ArrayMemory output=new ArrayMemory();var iterator=subject.toValue(ArrayMemory.class).getNewIterator(env,false,false);
                while(iterator.next()){
                    long before=replaced.value;
                    byte[] text=bytes(php.runtime.PhpByteStrings.stringValue(iterator.getValue(),env,trace));
                    byte[] result=replaceSubject(env,trace,function,pattern,replacement,text,limit,replaced,compiledPatterns);
                    if(result!=null&&(!filter||replaced.value!=before))output.put(iterator.getMemoryKey(),new PhpByteStringMemory(result));
                }
                return output;
            }finally{
                // PHP writes the final count even when a body conversion/warning handler throws.
                if(capture)count.assign(LongMemory.valueOf(replaced.value));
            }
        }
    }
    private static byte[] replaceSubject(Environment env,TraceInfo trace,String function,Memory pattern,Memory replacement,byte[] subject,long limit,Counter count,CompiledPatterns compiledPatterns)throws Throwable {
        if(!pattern.isArray())return replaceOne(env,trace,function,bytes(pattern),bytes(replacement),subject,limit,count,compiledPatterns);
        var patterns=pattern.toValue(ArrayMemory.class).getNewIterator(env,false,false);
        var replacements=replacement.isArray()?replacement.toValue(ArrayMemory.class).getNewIterator(env,false,false):null;
        while(patterns.next()){
            byte[] regex=bytes(php.runtime.PhpByteStrings.stringValue(patterns.getValue(),env,trace));
            byte[] text=replacements==null?bytes(replacement):replacements.next()
                    ?bytes(php.runtime.PhpByteStrings.stringValue(replacements.getValue(),env,trace)):new byte[0];
            subject=replaceOne(env,trace,function,regex,text,subject,limit,count,compiledPatterns);
            if(subject==null)break;
        }
        return subject;
    }
    private static byte[] replaceOne(Environment env,TraceInfo trace,String function,byte[] pattern,byte[] replacement,byte[] subject,long limit,Counter count,CompiledPatterns compiledPatterns)throws Throwable {
        return replaceOne(env,trace,function,pattern,replacement,subject,limit,count,null,compiledPatterns);
    }
    interface CallbackReplacement {byte[] replace(Pcre2Bytes.Compiled compiled,Pcre2Bytes.Match match)throws Throwable;}
    static byte[] replaceOne(Environment env,TraceInfo trace,String function,byte[] pattern,byte[] replacement,byte[] subject,long limit,Counter count,CallbackReplacement callback)throws Throwable {
        return replaceOne(env,trace,function,pattern,replacement,subject,limit,count,callback,null);
    }
    private static byte[] replaceOne(Environment env,TraceInfo trace,String function,byte[] pattern,byte[] replacement,byte[] subject,long limit,Counter count,CallbackReplacement callback,CompiledPatterns compiledPatterns)throws Throwable {
        PcreState state=PcreState.of(env);PregPattern parsed;
        try{parsed=PregPattern.parse(pattern);}catch(PregPattern.Problem bad){PregFunctions.warning(env,trace,function,state,bad.getMessage());return null;}
        Pcre2Bytes engine=state.engine();boolean jit=env.getConfigValue("pcre.jit",LongMemory.valueOf(1)).toBoolean();
        Pcre2Bytes.Compiled compiled=compiledPatterns==null?null:compiledPatterns.get(pattern,jit);
        boolean retained=compiled!=null;
        if(compiled==null){
            try{compiled=engine.compile(parsed.bytes(),parsed.options(),parsed.extraOptions(),jit);}
            catch(Pcre2Bytes.CompileFailure bad){PregFunctions.warning(env,trace,function,state,"Compilation failed: "+PregFunctions.compileErrorText(engine,bad)+" at offset "+bad.byteOffset);return null;}
            if(compiledPatterns!=null)retained=compiledPatterns.add(pattern,jit,compiled);
        }
        try(Pcre2Bytes.Compiled temporary=retained?null:compiled){
            state.error=0;
            if(limit==0)return subject; // PHP ignores the initial execution result when no replacements are permitted.
            int matchLimit=(int)env.getConfigValue("pcre.backtrack_limit",LongMemory.valueOf(1000000)).toLong();
            int depthLimit=(int)env.getConfigValue("pcre.recursion_limit",LongMemory.valueOf(100000)).toLong();
            long remaining=limit<0?Long.MAX_VALUE:limit,start=0,last=0;int options=0;boolean retry=false;
            ByteArrayOutputStream output=null;
            try(var session=compiled.scan(subject,matchLimit,depthLimit)){
                while(true){
                    if(callback!=null)session.limits((int)env.getConfigValue("pcre.backtrack_limit",LongMemory.valueOf(1000000)).toLong(),
                            (int)env.getConfigValue("pcre.recursion_limit",LongMemory.valueOf(100000)).toLong());
                    Pcre2Bytes.Match match=session.match(start,options);
                    if(match.result()==-1){
                        if(!retry||start==subject.length)break;
                        int next=(int)start+1;
                        if(session.utf)while(next<subject.length&&(subject[next]&0xc0)==0x80)next++;
                        start=next;retry=false;options=0x40000000;continue;
                    }
                    if(match.result()<0){state.error=PregFunctions.executionError(match.result());return null;}
                    long begin=match.offsets()[0],end=match.offsets()[1];
                    if(end<begin){state.error=1;return null;}
                    if(begin<last||end>subject.length)throw new IllegalStateException("Invalid PCRE2 replacement span");
                    count.value++;
                    if(output==null)output=new ByteArrayOutputStream();
                    output.write(subject,(int)last,(int)(begin-last));
                    if(callback==null)appendReplacement(output,replacement,subject,match);
                    else{
                        try{output.writeBytes(callback.replace(compiled,match));}
                        catch(Throwable failure){state.error=1;throw failure;}
                    }
                    last=start=end;
                    if(--remaining==0)break;
                    retry=begin==end;options=0x40000000|(retry?0x80000008:0);
                }
            }
            if(output==null)return subject;
            output.write(subject,(int)last,subject.length-(int)last);
            return output.toByteArray();
        }
    }
    private static void appendReplacement(ByteArrayOutputStream output,byte[] replacement,byte[] subject,Pcre2Bytes.Match match){
        for(int at=0;at<replacement.length;){
            int c=replacement[at]&255;
            if(c==92&&at+1<replacement.length&&(replacement[at+1]==92||replacement[at+1]==36)){
                output.write(replacement[at+1]);at+=2;continue;
            }
            if(c==92||c==36){
                int digit=at+1;boolean braces=c==36&&digit<replacement.length&&replacement[digit]==123;
                if(braces)digit++;
                if(digit<replacement.length&&replacement[digit]>=48&&replacement[digit]<=57){
                    int group=replacement[digit++]-48;
                    if(digit<replacement.length&&replacement[digit]>=48&&replacement[digit]<=57)group=group*10+replacement[digit++]-48;
                    if(!braces||digit<replacement.length&&replacement[digit]==125){
                        if(braces)digit++;
                        int available=match.result()==0?match.offsets().length/2:match.result();
                        if(group<available){
                            long begin=match.offsets()[group*2],end=match.offsets()[group*2+1];
                            if(begin!=-1){
                                if(begin<0||end<begin||end>subject.length)throw new IllegalStateException("Invalid PCRE2 replacement capture");
                                output.write(subject,(int)begin,(int)(end-begin));
                            }
                        }
                        at=digit;continue;
                    }
                }
            }
            output.write(c);at++;
        }
    }
    private static byte[] bytes(Memory value){return value.getBinaryBytes(StandardCharsets.UTF_8);}
    static Memory arrayOrString(Environment env,TraceInfo trace,String function,Memory argument,int index,String name)throws Throwable {
        Memory value=argument.toValue();if(value.isArray())return value;if(value.isString())return value.toImmutable();
        if(!php.runtime.invoke.NativeFunctionContext.isStrict(env,trace,function)){
            if(value.isNull()){
                env.error(trace,ErrorType.E_DEPRECATED,"%s(): Passing null to parameter #%d ($%s) of type array|string is deprecated",function,index,name);
                return Memory.CONST_EMPTY_STRING;
            }
            if(value.isNumber()||value.getRealType()==Memory.Type.BOOL||value.isObject()&&!value.isResource()&&value.toValue(php.runtime.memory.ObjectMemory.class).getReflection().methodMagicToString!=null)
                return php.runtime.PhpByteStrings.stringValue(value,env,trace);
        }
        PregFunctions.typeError(env,trace,function,value,index,name,"array|string");return Memory.CONST_EMPTY_STRING;
    }
    /** Successful code only, borrowed by sequential plain replacements in this one call. */
    private static final class CompiledPatterns implements AutoCloseable {
        private final java.util.ArrayList<PatternCode> entries=new java.util.ArrayList<>();
        private int keyBytes;
        Pcre2Bytes.Compiled get(byte[] pattern,boolean jit){
            for(PatternCode entry:entries)if(entry.jit==jit&&java.util.Arrays.equals(entry.pattern,pattern))return entry.compiled;
            return null;
        }
        boolean add(byte[] pattern,boolean jit,Pcre2Bytes.Compiled compiled)throws Throwable {
            if(entries.size()==8||pattern.length>65536-keyBytes)return false;
            try{
                entries.add(new PatternCode(pattern.clone(),jit,compiled));keyBytes+=pattern.length;return true;
            }catch(Throwable failure){
                try{compiled.close();}catch(Throwable cleanup){if(cleanup!=failure)failure.addSuppressed(cleanup);}
                throw failure;
            }
        }
        @Override public void close(){
            RuntimeException failure=null;
            for(int i=entries.size()-1;i>=0;i--)try{entries.get(i).compiled.close();}
            catch(RuntimeException cleanup){if(failure==null)failure=cleanup;else if(cleanup!=failure)failure.addSuppressed(cleanup);}
            entries.clear();if(failure!=null)throw failure;
        }
        private record PatternCode(byte[] pattern,boolean jit,Pcre2Bytes.Compiled compiled){}
    }
    static final class Counter {long value;}
    static final class Inputs implements AutoCloseable {
        final Memory[] values;private final ArrayMemory[] snapshots;
        Inputs(boolean snapshot,Memory... arguments){
            values=arguments.clone();snapshots=new ArrayMemory[values.length];
            for(int i=0;i<values.length;i++){
                Memory value=values[i].toValue();
                if(snapshot&&value.isArray())values[i]=snapshots[i]=value.toValue(ArrayMemory.class).toImmutableForNativeRead();
            }
        }
        @Override public void close(){for(int i=snapshots.length-1;i>=0;i--)if(snapshots[i]!=null)snapshots[i].unset();}
    }
}
