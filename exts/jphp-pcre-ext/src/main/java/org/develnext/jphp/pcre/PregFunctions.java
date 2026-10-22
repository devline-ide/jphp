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
import php.runtime.exceptions.support.ErrorType;
import php.runtime.memory.ArrayMemory;
import php.runtime.memory.LongMemory;
import php.runtime.memory.PhpByteStringMemory;
import php.runtime.memory.StringMemory;

/** PHP matching, filtering, splitting, quoting and the Environment-local error API. */
public final class PregFunctions extends FunctionsContainer {
    @Signature(value={@Arg(value="pattern",type=HintType.STRING,readOnly=true),@Arg(value="subject",type=HintType.STRING,readOnly=true),@Arg(value="matches",reference=true,optional=@Optional("NULL")),@Arg(value="flags",type=HintType.INT,readOnly=true,optional=@Optional("0")),@Arg(value="offset",type=HintType.INT,readOnly=true,optional=@Optional("0"))},returnType="int|false")
    public static Memory preg_match(Environment env,TraceInfo trace,Memory pattern,Memory subject,@Reference Memory matches,Memory flags,Memory offset)throws Throwable {
        return run(env,trace,pattern,subject,matches,flags,offset,true);
    }
    @Signature(value={@Arg(value="pattern",type=HintType.STRING,readOnly=true),@Arg(value="subject",type=HintType.STRING,readOnly=true),@Arg(value="matches",reference=true,optional=@Optional("NULL")),@Arg(value="flags",type=HintType.INT,readOnly=true,optional=@Optional("0")),@Arg(value="offset",type=HintType.INT,readOnly=true,optional=@Optional("0"))},returnType="int|false")
    public static Memory preg_match(Environment env,TraceInfo trace,Memory pattern,Memory subject,@Reference Memory matches,Memory flags)throws Throwable {
        return run(env,trace,pattern,subject,matches,flags,LongMemory.valueOf(0),true);
    }
    @Signature(value={@Arg(value="pattern",type=HintType.STRING,readOnly=true),@Arg(value="subject",type=HintType.STRING,readOnly=true),@Arg(value="matches",reference=true,optional=@Optional("NULL")),@Arg(value="flags",type=HintType.INT,readOnly=true,optional=@Optional("0")),@Arg(value="offset",type=HintType.INT,readOnly=true,optional=@Optional("0"))},returnType="int|false")
    public static Memory preg_match(Environment env,TraceInfo trace,Memory pattern,Memory subject,@Reference Memory matches)throws Throwable {
        return run(env,trace,pattern,subject,matches,LongMemory.valueOf(0),LongMemory.valueOf(0),true);
    }
    @Signature(value={@Arg(value="pattern",type=HintType.STRING,readOnly=true),@Arg(value="subject",type=HintType.STRING,readOnly=true),@Arg(value="matches",reference=true,optional=@Optional("NULL")),@Arg(value="flags",type=HintType.INT,readOnly=true,optional=@Optional("0")),@Arg(value="offset",type=HintType.INT,readOnly=true,optional=@Optional("0"))},returnType="int|false")
    public static Memory preg_match(Environment env,TraceInfo trace,Memory pattern,Memory subject)throws Throwable {
        return run(env,trace,pattern,subject,Memory.NULL,LongMemory.valueOf(0),LongMemory.valueOf(0),false);
    }
    @Signature(value={@Arg(value="pattern",type=HintType.STRING,readOnly=true),@Arg(value="subject",type=HintType.STRING,readOnly=true),@Arg(value="matches",reference=true,optional=@Optional("NULL")),@Arg(value="flags",type=HintType.INT,readOnly=true,optional=@Optional("0")),@Arg(value="offset",type=HintType.INT,readOnly=true,optional=@Optional("0"))},returnType="int|false")
    public static Memory preg_match_all(Environment env,TraceInfo trace,Memory pattern,Memory subject,@Reference Memory matches,Memory flags,Memory offset)throws Throwable {
        return runAll(env,trace,pattern,subject,matches,flags,offset,true);
    }
    @Signature(value={@Arg(value="pattern",type=HintType.STRING,readOnly=true),@Arg(value="subject",type=HintType.STRING,readOnly=true),@Arg(value="matches",reference=true,optional=@Optional("NULL")),@Arg(value="flags",type=HintType.INT,readOnly=true,optional=@Optional("0")),@Arg(value="offset",type=HintType.INT,readOnly=true,optional=@Optional("0"))},returnType="int|false")
    public static Memory preg_match_all(Environment env,TraceInfo trace,Memory pattern,Memory subject,@Reference Memory matches,Memory flags)throws Throwable {
        return runAll(env,trace,pattern,subject,matches,flags,LongMemory.valueOf(0),true);
    }
    @Signature(value={@Arg(value="pattern",type=HintType.STRING,readOnly=true),@Arg(value="subject",type=HintType.STRING,readOnly=true),@Arg(value="matches",reference=true,optional=@Optional("NULL")),@Arg(value="flags",type=HintType.INT,readOnly=true,optional=@Optional("0")),@Arg(value="offset",type=HintType.INT,readOnly=true,optional=@Optional("0"))},returnType="int|false")
    public static Memory preg_match_all(Environment env,TraceInfo trace,Memory pattern,Memory subject,@Reference Memory matches)throws Throwable {
        return runAll(env,trace,pattern,subject,matches,LongMemory.valueOf(0),LongMemory.valueOf(0),true);
    }
    @Signature(value={@Arg(value="pattern",type=HintType.STRING,readOnly=true),@Arg(value="subject",type=HintType.STRING,readOnly=true),@Arg(value="matches",reference=true,optional=@Optional("NULL")),@Arg(value="flags",type=HintType.INT,readOnly=true,optional=@Optional("0")),@Arg(value="offset",type=HintType.INT,readOnly=true,optional=@Optional("0"))},returnType="int|false")
    public static Memory preg_match_all(Environment env,TraceInfo trace,Memory pattern,Memory subject)throws Throwable {
        return runAll(env,trace,pattern,subject,Memory.NULL,LongMemory.valueOf(0),LongMemory.valueOf(0),false);
    }
    @Signature(value={@Arg(value="pattern",type=HintType.STRING,readOnly=true),@Arg(value="array",type=HintType.ARRAY,readOnly=true),@Arg(value="flags",type=HintType.INT,readOnly=true,optional=@Optional("0"))},returnType="array|false")
    public static Memory preg_grep(Environment env,TraceInfo trace,Memory pattern,Memory array,Memory flags)throws Throwable {
        return runGrep(env,trace,pattern,array,flags);
    }
    @Signature(value={@Arg(value="pattern",type=HintType.STRING,readOnly=true),@Arg(value="array",type=HintType.ARRAY,readOnly=true),@Arg(value="flags",type=HintType.INT,readOnly=true,optional=@Optional("0"))},returnType="array|false")
    public static Memory preg_grep(Environment env,TraceInfo trace,Memory pattern,Memory array)throws Throwable {
        return runGrep(env,trace,pattern,array,LongMemory.valueOf(0));
    }
    @Signature(value={@Arg(value="pattern",type=HintType.STRING,readOnly=true),@Arg(value="subject",type=HintType.STRING,readOnly=true),@Arg(value="limit",type=HintType.INT,readOnly=true,optional=@Optional("-1")),@Arg(value="flags",type=HintType.INT,readOnly=true,optional=@Optional("0"))},returnType="array|false")
    public static Memory preg_split(Environment env,TraceInfo trace,Memory pattern,Memory subject,Memory limit,Memory flags)throws Throwable {
        return runSplit(env,trace,pattern,subject,limit,flags);
    }
    @Signature(value={@Arg(value="pattern",type=HintType.STRING,readOnly=true),@Arg(value="subject",type=HintType.STRING,readOnly=true),@Arg(value="limit",type=HintType.INT,readOnly=true,optional=@Optional("-1")),@Arg(value="flags",type=HintType.INT,readOnly=true,optional=@Optional("0"))},returnType="array|false")
    public static Memory preg_split(Environment env,TraceInfo trace,Memory pattern,Memory subject,Memory limit)throws Throwable {
        return runSplit(env,trace,pattern,subject,limit,LongMemory.valueOf(0));
    }
    @Signature(value={@Arg(value="pattern",type=HintType.STRING,readOnly=true),@Arg(value="subject",type=HintType.STRING,readOnly=true),@Arg(value="limit",type=HintType.INT,readOnly=true,optional=@Optional("-1")),@Arg(value="flags",type=HintType.INT,readOnly=true,optional=@Optional("0"))},returnType="array|false")
    public static Memory preg_split(Environment env,TraceInfo trace,Memory pattern,Memory subject)throws Throwable {
        return runSplit(env,trace,pattern,subject,LongMemory.valueOf(-1),LongMemory.valueOf(0));
    }
    @Signature(value={@Arg(value="str",type=HintType.STRING,readOnly=true),@Arg(value="delimiter",type=HintType.STRING,nullable=true,readOnly=true,optional=@Optional("NULL"))},result=@Arg(type=HintType.STRING))
    public static Memory preg_quote(Environment env,TraceInfo trace,Memory str,Memory delimiter)throws Throwable {
        byte[] input=string(env,trace,"preg_quote",str,1,"str");
        byte[] delim=string(env,trace,"preg_quote",delimiter,2,"delimiter",true);
        int delimiterByte=delim.length==0?0:delim[0]&255;
        long length=input.length;
        for(byte b:input){int c=b&255;length+=c==0?3:quoteByte(c,delimiterByte)?1:0;}
        if(length==input.length)return str.toValue().isString()?str.toValue().toImmutable():new PhpByteStringMemory(input);
        if(length>Integer.MAX_VALUE)throw new OutOfMemoryError("Quoted PHP string exceeds JVM byte array capacity");
        byte[] output=new byte[(int)length];int at=0;
        for(byte b:input){
            int c=b&255;
            if(c==0){output[at++]=92;output[at++]=48;output[at++]=48;output[at++]=48;}
            else{if(quoteByte(c,delimiterByte))output[at++]=92;output[at++]=b;}
        }
        return new PhpByteStringMemory(output);
    }
    @Signature(value={@Arg(value="str",type=HintType.STRING,readOnly=true),@Arg(value="delimiter",type=HintType.STRING,nullable=true,readOnly=true,optional=@Optional("NULL"))},result=@Arg(type=HintType.STRING))
    public static Memory preg_quote(Environment env,TraceInfo trace,Memory str)throws Throwable {
        return preg_quote(env,trace,str,Memory.NULL);
    }
    private static boolean quoteByte(int c,int delimiter){
        return c==delimiter||switch(c){case '.',92,'+','*','?','[','^',']','$','(',')','{','}','=','!','>','<','|',':','-','#'->true;default->false;};
    }
    @Signature(result=@Arg(type=HintType.INT))
    public static int preg_last_error(Environment env){return PcreState.of(env).error;}
    @Signature(result=@Arg(type=HintType.STRING))
    public static Memory preg_last_error_msg(Environment env){return StringMemory.valueOf(errorText(PcreState.of(env).error));}
    private static Memory run(Environment env,TraceInfo trace,Memory patternArg,Memory subjectArg,Memory matches,Memory flagsArg,Memory offsetArg,boolean capture)throws Throwable {
        String functionName="preg_match";
        byte[] pattern=string(env,trace,functionName,patternArg,1,"pattern"),subject=string(env,trace,functionName,subjectArg,2,"subject");
        long flags=integer(env,trace,functionName,flagsArg,4,"flags"),offset=integer(env,trace,functionName,offsetArg,5,"offset");
        PcreState state=PcreState.of(env);Pcre2Bytes engine=state.engine();PregPattern parsed;
        try{parsed=PregPattern.parse(pattern);}catch(PregPattern.Problem bad){return warning(env,trace,functionName,state,bad.getMessage());}
        Pcre2Bytes.Compiled compiled;
        try{compiled=engine.compile(parsed.bytes(),parsed.options(),parsed.extraOptions(),env.getConfigValue("pcre.jit",LongMemory.valueOf(1)).toBoolean());}
        catch(Pcre2Bytes.CompileFailure bad){return warning(env,trace,functionName,state,"Compilation failed: "+compileErrorText(engine,bad)+" at offset "+bad.byteOffset);}
        try(compiled){
            if(offset==Long.MIN_VALUE)env.exception(trace,php.runtime.lang.exception.BaseValueError.class,
                    "%s(): Argument #5 ($offset) must be greater than -9223372036854775808",functionName);
            // PHP clears a supplied reference only after compilation, even before a flags ValueError.
            if(capture)matches.assign(new ArrayMemory());
            if((flags&255)!=0)env.exception(trace,php.runtime.lang.exception.BaseValueError.class,"preg_match(): Argument #4 ($flags) must be a PREG_* constant");
            long start=offset<0?(offset<-(long)subject.length?0:subject.length+offset):offset;
            if(start>subject.length){state.error=1;return Memory.FALSE;}
            int matchLimit=(int)env.getConfigValue("pcre.backtrack_limit",LongMemory.valueOf(1000000)).toLong();
            int depthLimit=(int)env.getConfigValue("pcre.recursion_limit",LongMemory.valueOf(100000)).toLong();
            Pcre2Bytes.Match result=compiled.match(subject,start,0,matchLimit,depthLimit);
            if(result.result()==-1){state.error=0;return LongMemory.valueOf(0);}
            if(result.result()<0){state.error=executionError(result.result());return Memory.FALSE;}
            if(capture){
                ArrayMemory output=new ArrayMemory();boolean nulls=(flags&512)!=0,offsets=(flags&256)!=0;
                int size=nulls?compiled.captureCount+1:result.result();
                java.util.Set<Object> seenNames=new java.util.HashSet<>();
                for(int i=0;i<size;i++){
                    long begin=result.offsets()[i*2],end=result.offsets()[i*2+1];boolean unmatched=begin==-1;
                    if(!unmatched&&(begin<0||end<begin||end>subject.length))throw new IllegalStateException("Invalid PCRE2 byte capture");
                    Memory value=unmatched?(nulls?Memory.NULL:StringMemory.valueOf("")):new PhpByteStringMemory(subject,(int)begin,(int)(end-begin));
                    if(offsets){ArrayMemory pair=new ArrayMemory();pair.add(value);pair.add(LongMemory.valueOf(unmatched?-1:begin));value=pair;}
                    for(Pcre2Bytes.NamedCapture name:compiled.names)if(name.number()==i){
                        PhpByteStringMemory key=new PhpByteStringMemory(name.name());
                        // Duplicate named groups retain a matched alternative over a later unmatched one.
                        if(seenNames.add(ArrayMemory.toKey(key))||!unmatched)output.refOfIndex(key).assign(value.toImmutable());
                    }
                    output.refOfIndex(LongMemory.valueOf(i)).assign(value);
                }
                appendMark(output,result.mark());
                matches.assign(output);
            }
            state.error=0;return LongMemory.valueOf(1);
        }
    }
    private static Memory runAll(Environment env,TraceInfo trace,Memory patternArg,Memory subjectArg,Memory matches,Memory flagsArg,Memory offsetArg,boolean capture)throws Throwable {
        String functionName="preg_match_all";
        byte[] pattern=string(env,trace,functionName,patternArg,1,"pattern"),subject=string(env,trace,functionName,subjectArg,2,"subject");
        long flags=integer(env,trace,functionName,flagsArg,4,"flags"),offset=integer(env,trace,functionName,offsetArg,5,"offset");
        PcreState state=PcreState.of(env);Pcre2Bytes engine=state.engine();PregPattern parsed;
        try{parsed=PregPattern.parse(pattern);}catch(PregPattern.Problem bad){return warning(env,trace,functionName,state,bad.getMessage());}
        Pcre2Bytes.Compiled compiled;
        try{compiled=engine.compile(parsed.bytes(),parsed.options(),parsed.extraOptions(),env.getConfigValue("pcre.jit",LongMemory.valueOf(1)).toBoolean());}
        catch(Pcre2Bytes.CompileFailure bad){return warning(env,trace,functionName,state,"Compilation failed: "+compileErrorText(engine,bad)+" at offset "+bad.byteOffset);}
        try(compiled){
            if(offset==Long.MIN_VALUE)env.exception(trace,php.runtime.lang.exception.BaseValueError.class,
                    "%s(): Argument #5 ($offset) must be greater than -9223372036854775808",functionName);
            if(capture)matches.assign(new ArrayMemory());
            int order=(int)(flags&255);if(order==0)order=1;
            if(order!=1&&order!=2)env.exception(trace,php.runtime.lang.exception.BaseValueError.class,
                    "%s(): Argument #4 ($flags) must be a PREG_* constant",functionName);
            long start=offset<0?(offset<-(long)subject.length?0:subject.length+offset):offset;
            if(start>subject.length){state.error=1;return Memory.FALSE;}
            int matchLimit=(int)env.getConfigValue("pcre.backtrack_limit",LongMemory.valueOf(1000000)).toLong();
            int depthLimit=(int)env.getConfigValue("pcre.recursion_limit",LongMemory.valueOf(100000)).toLong();
            boolean nulls=(flags&512)!=0,offsets=(flags&256)!=0;
            ArrayMemory output=capture?new ArrayMemory():null,marks=null;
            ArrayMemory[] columns=capture&&order==1?new ArrayMemory[compiled.captureCount+1]:null;
            if(columns!=null)for(int i=0;i<columns.length;i++)columns[i]=new ArrayMemory();
            long matched=0;state.error=0;
            // MatchSession copies the native subject exactly once and reuses context/data for the whole call.
            try(var session=compiled.scan(subject,matchLimit,depthLimit)){
                boolean retryEmpty=false;int options=0;
                while(true){
                    Pcre2Bytes.Match result=session.match(start,options);
                    if(result.result()==-1){
                        if(!retryEmpty||start==subject.length)break;
                        int next=(int)start+1;
                        if(session.utf)while(next<subject.length&&(subject[next]&0xc0)==0x80)next++;
                        start=next;retryEmpty=false;options=0x40000000;continue; // PCRE2_NO_UTF_CHECK after a successful initial match.
                    }
                    if(result.result()<0){state.error=executionError(result.result());break;}
                    int available=result.result()==0?compiled.captureCount+1:result.result();
                    long begin=result.offsets()[0],end=result.offsets()[1];
                    if(begin<0||end<begin||end>subject.length)throw new IllegalStateException("Invalid PCRE2 global match span");
                    if(capture){
                        if(columns!=null){
                            for(int i=0;i<columns.length;i++)columns[i].add(globalValue(subject,result,i,available,nulls,offsets));
                            if(result.mark()!=null){
                                if(marks==null)marks=new ArrayMemory();
                                marks.refOfIndex(LongMemory.valueOf(matched)).assign(new PhpByteStringMemory(result.mark()));
                            }
                        }else output.add(globalRow(subject,compiled,result,available,nulls,offsets));
                    }
                    matched++;
                    start=end;retryEmpty=begin==end;
                    options=0x40000000|(retryEmpty?0x80000008:0); // NO_UTF_CHECK; empty retry is ANCHORED|NOTEMPTY_ATSTART.
                }
            }
            if(capture){
                if(columns!=null){
                    for(int i=0;i<columns.length;i++){
                        // Pattern order aliases the complete last-numbered column for duplicate names.
                        for(Pcre2Bytes.NamedCapture name:compiled.names)if(name.number()==i)
                            output.refOfIndex(new PhpByteStringMemory(name.name())).assign(columns[i].toImmutable());
                        output.refOfIndex(LongMemory.valueOf(i)).assign(columns[i]);
                    }
                    if(marks!=null)output.refOfIndex(StringMemory.valueOf("MARK")).assign(marks);
                }
                // Preserve already collected rows/columns when a later native iteration reports an error.
                matches.assign(output);
            }
            return state.error==0?LongMemory.valueOf(matched):Memory.FALSE;
        }
    }
    private static Memory runGrep(Environment env,TraceInfo trace,Memory patternArg,Memory arrayArg,Memory flagsArg)throws Throwable {
        String functionName="preg_grep";
        byte[] pattern=string(env,trace,functionName,patternArg,1,"pattern");
        Memory value=arrayArg.toValue();
        if(!value.isArray())typeError(env,trace,functionName,value,2,"array","array");
        long flags=integer(env,trace,functionName,flagsArg,3,"flags");
        PcreState state=PcreState.of(env);Pcre2Bytes engine=state.engine();PregPattern parsed;
        try{parsed=PregPattern.parse(pattern);}catch(PregPattern.Problem bad){return warning(env,trace,functionName,state,bad.getMessage());}
        Pcre2Bytes.Compiled compiled;
        try{compiled=engine.compile(parsed.bytes(),parsed.options(),parsed.extraOptions(),env.getConfigValue("pcre.jit",LongMemory.valueOf(1)).toBoolean());}
        catch(Pcre2Bytes.CompileFailure bad){return warning(env,trace,functionName,state,"Compilation failed: "+compileErrorText(engine,bad)+" at offset "+bad.byteOffset);}
        try(compiled){
            ArrayMemory output=new ArrayMemory();boolean invert=(flags&1)!=0;state.error=0;
            Memory defaultMatch=LongMemory.valueOf(1000000),defaultDepth=LongMemory.valueOf(100000);
            // Direct native sends also need a COW view across Stringable/warning callbacks.
            ArrayMemory snapshot=value.toValue(ArrayMemory.class).toImmutableForNativeRead();
            try{
                var iterator=snapshot.getNewIterator(env,false,false);
                try(var session=compiled.filter()){
                while(iterator.next()){
                    Memory entry=iterator.getValue();
                    byte[] subject=php.runtime.PhpByteStrings.stringValue(entry,env,trace).getBinaryBytes(StandardCharsets.UTF_8);
                    // Stringable and warning callbacks may change INI limits or the Environment-local error.
                    int matchLimit=(int)env.getConfigValue("pcre.backtrack_limit",defaultMatch).toLong();
                    int depthLimit=(int)env.getConfigValue("pcre.recursion_limit",defaultDepth).toLong();
                    int result=session.test(subject,matchLimit,depthLimit);
                    if(result<0&&result!=-1){state.error=executionError(result);break;}
                    if((result>=0)!=invert)output.put(iterator.getMemoryKey(),entry.isReference()?entry:entry.toImmutable());
                }
                }
            }finally{snapshot.unset();}
            // Native execution errors retain earlier entries; successful tests do not erase nested PCRE errors.
            return output;
        }
    }
    private static Memory runSplit(Environment env,TraceInfo trace,Memory patternArg,Memory subjectArg,Memory limitArg,Memory flagsArg)throws Throwable {
        String functionName="preg_split";
        byte[] pattern=string(env,trace,functionName,patternArg,1,"pattern"),subject=string(env,trace,functionName,subjectArg,2,"subject");
        long limit=integer(env,trace,functionName,limitArg,3,"limit"),flags=integer(env,trace,functionName,flagsArg,4,"flags");
        PcreState state=PcreState.of(env);Pcre2Bytes engine=state.engine();PregPattern parsed;
        try{parsed=PregPattern.parse(pattern);}catch(PregPattern.Problem bad){return warning(env,trace,functionName,state,bad.getMessage());}
        Pcre2Bytes.Compiled compiled;
        try{compiled=engine.compile(parsed.bytes(),parsed.options(),parsed.extraOptions(),env.getConfigValue("pcre.jit",LongMemory.valueOf(1)).toBoolean());}
        catch(Pcre2Bytes.CompileFailure bad){return warning(env,trace,functionName,state,"Compilation failed: "+compileErrorText(engine,bad)+" at offset "+bad.byteOffset);}
        try(compiled){
            boolean noEmpty=(flags&1)!=0,captures=(flags&2)!=0,offsets=(flags&4)!=0;
            ArrayMemory output=new ArrayMemory();long last=0;state.error=0;
            if(limit==0)limit=-1;
            // Negative limits other than -1, like 1, return the whole subject without executing a match.
            if(limit==-1||limit>1){
                int matchLimit=(int)env.getConfigValue("pcre.backtrack_limit",LongMemory.valueOf(1000000)).toLong();
                int depthLimit=(int)env.getConfigValue("pcre.recursion_limit",LongMemory.valueOf(100000)).toLong();
                try(var session=compiled.scan(subject,matchLimit,depthLimit)){
                    long start=0;boolean retryEmpty=false;int options=0;
                    while(true){
                        Pcre2Bytes.Match result=session.match(start,options);
                        if(result.result()==-1){
                            if(!retryEmpty||start==subject.length)break;
                            int next=(int)start+1;
                            if(session.utf)while(next<subject.length&&(subject[next]&0xc0)==0x80)next++;
                            start=next;retryEmpty=false;options=0x40000000;continue;
                        }
                        if(result.result()<0){state.error=executionError(result.result());return Memory.FALSE;}
                        long begin=result.offsets()[0],end=result.offsets()[1];
                        if(end<begin){state.error=1;return Memory.FALSE;}
                        if(begin<last||end>subject.length)throw new IllegalStateException("Invalid PCRE2 split span");
                        if(!noEmpty||begin!=last){output.add(splitValue(subject,last,begin,offsets));if(limit!=-1)limit--;}
                        if(captures){
                            int available=result.result()==0?compiled.captureCount+1:result.result();
                            for(int i=1;i<available;i++){
                                long from=result.offsets()[2*i],to=result.offsets()[2*i+1];
                                if(!noEmpty||from!=to)output.add(splitValue(subject,from,to,offsets));
                            }
                        }
                        last=start=end;
                        if(limit!=-1&&limit<=1)break;
                        retryEmpty=begin==end;options=0x40000000|(retryEmpty?0x80000008:0);
                    }
                }
            }
            if(!noEmpty||last<subject.length)output.add(splitValue(subject,last,subject.length,offsets));
            return output;
        }
    }
    private static Memory splitValue(byte[] subject,long begin,long end,boolean offsets){
        boolean unmatched=begin==-1&&end==-1;
        if(!unmatched&&(begin<0||end<begin||end>subject.length))throw new IllegalStateException("Invalid PCRE2 split capture");
        Memory value=unmatched?StringMemory.valueOf(""):new PhpByteStringMemory(subject,(int)begin,(int)(end-begin));
        if(offsets){ArrayMemory pair=new ArrayMemory();pair.add(value);pair.add(LongMemory.valueOf(begin));return pair;}
        return value;
    }
    private static Memory globalValue(byte[] subject,Pcre2Bytes.Match result,int group,int available,boolean nulls,boolean offsets){
        long begin=group<available?result.offsets()[group*2]:-1,end=group<available?result.offsets()[group*2+1]:-1;
        boolean unmatched=begin==-1;
        if(!unmatched&&(begin<0||end<begin||end>subject.length))throw new IllegalStateException("Invalid PCRE2 byte capture");
        Memory value=unmatched?(nulls?Memory.NULL:StringMemory.valueOf("")):new PhpByteStringMemory(subject,(int)begin,(int)(end-begin));
        if(offsets){ArrayMemory pair=new ArrayMemory();pair.add(value);pair.add(LongMemory.valueOf(unmatched?-1:begin));return pair;}
        return value;
    }
    static ArrayMemory globalRow(byte[] subject,Pcre2Bytes.Compiled compiled,Pcre2Bytes.Match result,int available,boolean nulls,boolean offsets){
        ArrayMemory row=new ArrayMemory();int size=nulls?compiled.captureCount+1:available;
        java.util.Set<Object> seenNames=new java.util.HashSet<>();
        for(int i=0;i<size;i++){
            Memory value=globalValue(subject,result,i,available,nulls,offsets);
            boolean unmatched=i>=available||result.offsets()[i*2]==-1;
            for(Pcre2Bytes.NamedCapture name:compiled.names)if(name.number()==i){
                PhpByteStringMemory key=new PhpByteStringMemory(name.name());
                if(seenNames.add(ArrayMemory.toKey(key))||!unmatched)row.refOfIndex(key).assign(value.toImmutable());
            }
            row.refOfIndex(LongMemory.valueOf(i)).assign(value);
        }
        appendMark(row,result.mark());
        return row;
    }
    private static void appendMark(ArrayMemory output,byte[] mark){
        if(mark==null)return;
        Memory value=new PhpByteStringMemory(mark);
        // PHP appends a physical bucket even when a named capture already uses MARK.
        if(output.getByScalar("MARK")!=null)output.insertBucket("MARK",value);
        else output.refOfIndex(StringMemory.valueOf("MARK")).assign(value);
    }
    static String compileErrorText(Pcre2Bytes engine,Pcre2Bytes.CompileFailure failure)throws Throwable {
        return failure.error==183?"using \\C is incompatible with the 'u' modifier":engine.errorMessage(failure.error);
    }
    static Memory warning(Environment env,TraceInfo trace,String functionName,PcreState state,String text){
        try{env.error(trace,ErrorType.E_WARNING,"%s(): %s",functionName,text);}
        finally{state.error=1;}
        return Memory.FALSE;
    }
    static int executionError(int code){
        if(code<=-3&&code>=-23)return 4;
        return switch(code){case -47->2;case -53->3;case -36->5;case -46->6;default->1;};
    }
    static String errorText(int code){return switch(code){
        case 0->"No error";case 1->"Internal error";case 2->"Backtrack limit exhausted";case 3->"Recursion limit exhausted";
        case 4->"Malformed UTF-8 characters, possibly incorrectly encoded";
        case 5->"The offset did not correspond to the beginning of a valid UTF-8 code point";case 6->"JIT stack limit exhausted";default->"Unknown error";};}
    private static byte[] string(Environment env,TraceInfo trace,String functionName,Memory argument,int index,String name)throws Throwable {
        return string(env,trace,functionName,argument,index,name,false);
    }
    private static byte[] string(Environment env,TraceInfo trace,String functionName,Memory argument,int index,String name,boolean nullable)throws Throwable {
        Memory value=argument.toValue();if(value.isString())return value.getBinaryBytes(StandardCharsets.UTF_8);
        if(nullable&&value.isNull())return new byte[0];
        if(!php.runtime.invoke.NativeFunctionContext.isStrict(env,trace,functionName)){
            if(value.isNull()){
                env.error(trace,ErrorType.E_DEPRECATED,"%s(): Passing null to parameter #%d ($%s) of type string is deprecated",functionName,index,name);return new byte[0];
            }
            if(value.isNumber()||value.getRealType()==Memory.Type.BOOL||value.isObject()&&!value.isResource()&&value.toValue(php.runtime.memory.ObjectMemory.class).getReflection().methodMagicToString!=null)
                return php.runtime.PhpByteStrings.stringValue(value,env,trace).getBinaryBytes(StandardCharsets.UTF_8);
        }
        typeError(env,trace,functionName,value,index,name,nullable?"?string":"string");return new byte[0];
    }
    static long integer(Environment env,TraceInfo trace,String functionName,Memory argument,int index,String name){
        Memory value=argument.toValue();if(value.getRealType()==Memory.Type.INT)return value.toLong();
        if(!php.runtime.invoke.NativeFunctionContext.isStrict(env,trace,functionName)){
            if(value.isNull()){env.error(trace,ErrorType.E_DEPRECATED,"%s(): Passing null to parameter #%d ($%s) of type int is deprecated",functionName,index,name);return 0;}
            Memory converted=php.runtime.reflection.support.TypeChecker.of(HintType.INT).apply(env,value,false,false);
            if(converted!=null){Memory number=value.isString()?php.runtime.PhpNumericString.parseStrict(php.runtime.PhpByteStrings.numericText(value)):value;if(number!=null)php.runtime.PhpNumericConversions.warnIntegerPrecisionLoss(value,number,env,trace);return converted.toLong();}
        }
        typeError(env,trace,functionName,value,index,name,"int");return 0;
    }
    static void typeError(Environment env,TraceInfo trace,String functionName,Memory value,int index,String name,String expected){
        String given=value.isResource()?"resource":value.isObject()?value.toValue(php.runtime.memory.ObjectMemory.class).getReflection().getName():value.isNull()?"null":value.getRealType()==Memory.Type.INT?"int":value.getRealType()==Memory.Type.BOOL?(value.toBoolean()?"true":"false"):value.getRealType().toString();
        env.exception(trace,php.runtime.lang.exception.BaseTypeError.class,"%s(): Argument #%d ($%s) must be of type %s, %s given",functionName,index,name,expected,given);
    }
}
