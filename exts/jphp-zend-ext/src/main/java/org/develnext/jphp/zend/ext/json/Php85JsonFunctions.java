package org.develnext.jphp.zend.ext.json;

import php.runtime.Memory;
import php.runtime.annotation.Reflection.*;
import php.runtime.annotation.Runtime.Reference;
import php.runtime.common.HintType;
import php.runtime.env.Environment;
import php.runtime.env.TraceInfo;
import php.runtime.ext.support.compile.FunctionsContainer;
import php.runtime.memory.StringMemory;

/** Modern JSON dispatch keeps the legacy Java String overload contract intact. */
public final class Php85JsonFunctions extends FunctionsContainer {
    // Fixed Memory parameters use existing native SEND capture before any diagnostic callback.
    @Signature(value={@Arg(value="json",type=HintType.STRING),@Arg(value="depth",type=HintType.INT,optional=@Optional("512")),@Arg(value="flags",type=HintType.INT,optional=@Optional("0"))},result=@Arg(type=HintType.BOOLEAN))
    public static boolean json_validate(Environment env,TraceInfo trace,Memory json,Memory depth,Memory flags)throws Throwable {
        byte[] bytes=jsonBytes(env,trace,json,"json_validate");
        long limit=jsonInteger(env,trace,depth,2,"depth","json_validate");
        long options=jsonInteger(env,trace,flags,3,"flags","json_validate");
        if(options!=0&&options!=JsonConstants.JSON_INVALID_UTF8_IGNORE)
            env.exception(trace,php.runtime.lang.exception.BaseValueError.class,
                    "json_validate(): Argument #3 ($flags) must be a valid flag (allowed flags: JSON_INVALID_UTF8_IGNORE)");
        if(bytes.length==0){JsonFunctions.setError(env,JsonConstants.JSON_ERROR_SYNTAX);return false;}
        JsonFunctions.setError(env,JsonConstants.JSON_ERROR_NONE);
        if(limit<=0)env.exception(trace,php.runtime.lang.exception.BaseValueError.class,"json_validate(): Argument #2 ($depth) must be greater than 0");
        if(limit>Integer.MAX_VALUE)env.exception(trace,php.runtime.lang.exception.BaseValueError.class,"json_validate(): Argument #2 ($depth) must be less than 2147483647");
        int error=Php85JsonValidator.validate(bytes,(int)limit,options!=0);
        JsonFunctions.setError(env,error);return error==JsonConstants.JSON_ERROR_NONE;
    }
    @Signature(value={@Arg(value="json",type=HintType.STRING),@Arg(value="depth",type=HintType.INT,optional=@Optional("512")),@Arg(value="flags",type=HintType.INT,optional=@Optional("0"))},result=@Arg(type=HintType.BOOLEAN))
    public static boolean json_validate(Environment env,TraceInfo trace,Memory json,Memory depth)throws Throwable {
        return json_validate(env,trace,json,depth,php.runtime.memory.LongMemory.valueOf(0));
    }
    @Signature(value={@Arg(value="json",type=HintType.STRING),@Arg(value="depth",type=HintType.INT,optional=@Optional("512")),@Arg(value="flags",type=HintType.INT,optional=@Optional("0"))},result=@Arg(type=HintType.BOOLEAN))
    public static boolean json_validate(Environment env,TraceInfo trace,Memory json)throws Throwable {
        return json_validate(env,trace,json,php.runtime.memory.LongMemory.valueOf(512),php.runtime.memory.LongMemory.valueOf(0));
    }
    private static byte[] jsonBytes(Environment env,TraceInfo trace,Memory argument,String function)throws Throwable {
        Memory value=argument.toValue();
        if(value.isString())return value.getBinaryBytes(java.nio.charset.StandardCharsets.UTF_8);
        if(!php.runtime.invoke.NativeFunctionContext.isStrict(env,trace,function)) {
            if(value.isNull()) {
                env.error(trace,php.runtime.exceptions.support.ErrorType.E_DEPRECATED,"%s(): Passing null to parameter #1 ($json) of type string is deprecated",function);
                return new byte[0];
            }
            if(value.isNumber()||value.getRealType()==Memory.Type.BOOL
                    ||value.isObject()&&!value.isResource()&&value.toValue(php.runtime.memory.ObjectMemory.class).getReflection().methodMagicToString!=null)
                return php.runtime.PhpByteStrings.stringValue(value,env,trace).getBinaryBytes(java.nio.charset.StandardCharsets.UTF_8);
        }
        jsonTypeError(env,trace,value,1,"json","string",function);return new byte[0];
    }
    private static long jsonInteger(Environment env,TraceInfo trace,Memory argument,int index,String name,String function) {
        Memory value=argument.toValue();if(value.getRealType()==Memory.Type.INT)return value.toLong();
        if(!php.runtime.invoke.NativeFunctionContext.isStrict(env,trace,function)) {
            if(value.isNull()) {
                env.error(trace,php.runtime.exceptions.support.ErrorType.E_DEPRECATED,"%s(): Passing null to parameter #%d ($%s) of type int is deprecated",function,index,name);return 0;
            }
            Memory converted=php.runtime.reflection.support.TypeChecker.of(HintType.INT).apply(env,value,false,false);
            if(converted!=null) {
                Memory number=value.isString()?php.runtime.PhpNumericString.parseStrict(php.runtime.PhpByteStrings.numericText(value)):value;
                if(number!=null)php.runtime.PhpNumericConversions.warnIntegerPrecisionLoss(value,number,env,trace);
                return converted.toLong();
            }
        }
        jsonTypeError(env,trace,value,index,name,"int",function);return 0;
    }
    private static void jsonTypeError(Environment env,TraceInfo trace,Memory value,int index,String name,String expected,String function) {
        String given=value.isResource()?"resource":value.isObject()?value.toValue(php.runtime.memory.ObjectMemory.class).getReflection().getName():value.isNull()?"null"
                :value.getRealType()==Memory.Type.INT?"int":value.getRealType()==Memory.Type.BOOL?"bool":value.getRealType().toString();
        env.exception(trace,php.runtime.lang.exception.BaseTypeError.class,"%s(): Argument #%d ($%s) must be of type %s, %s given",function,index,name,expected,given);
    }

    @Signature(value={@Arg(value="json",type=HintType.STRING),@Arg(value="associative",type=HintType.BOOLEAN,nullable=true,optional=@Optional("NULL")),@Arg(value="depth",type=HintType.INT,optional=@Optional("512")),@Arg(value="flags",type=HintType.INT,optional=@Optional("0"))},result=@Arg(type=HintType.MIXED))
    public static Memory json_decode(Environment env,TraceInfo trace,Memory json,Memory associative,Memory depth,Memory flags)throws Throwable {
        byte[] bytes=jsonBytes(env,trace,json,"json_decode");
        Memory association=jsonAssociative(env,trace,associative);
        long limit=jsonInteger(env,trace,depth,3,"depth","json_decode");
        long options=jsonInteger(env,trace,flags,4,"flags","json_decode");
        boolean throwsError=(options&JsonConstants.JSON_THROW_ON_ERROR)!=0;
        // Native argument diagnostics finish before changing shared JSON error state.
        if(!throwsError)JsonFunctions.setError(env,JsonConstants.JSON_ERROR_NONE);
        if(bytes.length==0)return jsonDecodeError(env,trace,JsonConstants.JSON_ERROR_SYNTAX,throwsError);
        if(limit<=0)env.exception(trace,php.runtime.lang.exception.BaseValueError.class,"json_decode(): Argument #3 ($depth) must be greater than 0");
        if(limit>Integer.MAX_VALUE)env.exception(trace,php.runtime.lang.exception.BaseValueError.class,"json_decode(): Argument #3 ($depth) must be less than 2147483647");
        boolean assoc=association.isNull()?(options&JsonConstants.JSON_OBJECT_AS_ARRAY)!=0:association.toBoolean();
        Php85JsonDecoder.Result result=Php85JsonDecoder.decode(env,bytes,(int)limit,(int)options,assoc);
        if(result.error()!=JsonConstants.JSON_ERROR_NONE)return jsonDecodeError(env,trace,result.error(),throwsError);
        return result.value();
    }
    @Signature(value={@Arg(value="json",type=HintType.STRING),@Arg(value="associative",type=HintType.BOOLEAN,nullable=true,optional=@Optional("NULL")),@Arg(value="depth",type=HintType.INT,optional=@Optional("512")),@Arg(value="flags",type=HintType.INT,optional=@Optional("0"))},result=@Arg(type=HintType.MIXED))
    public static Memory json_decode(Environment env,TraceInfo trace,Memory json,Memory associative,Memory depth)throws Throwable {
        return json_decode(env,trace,json,associative,depth,php.runtime.memory.LongMemory.valueOf(0));
    }
    @Signature(value={@Arg(value="json",type=HintType.STRING),@Arg(value="associative",type=HintType.BOOLEAN,nullable=true,optional=@Optional("NULL")),@Arg(value="depth",type=HintType.INT,optional=@Optional("512")),@Arg(value="flags",type=HintType.INT,optional=@Optional("0"))},result=@Arg(type=HintType.MIXED))
    public static Memory json_decode(Environment env,TraceInfo trace,Memory json,Memory associative)throws Throwable {
        return json_decode(env,trace,json,associative,php.runtime.memory.LongMemory.valueOf(512),php.runtime.memory.LongMemory.valueOf(0));
    }
    @Signature(value={@Arg(value="json",type=HintType.STRING),@Arg(value="associative",type=HintType.BOOLEAN,nullable=true,optional=@Optional("NULL")),@Arg(value="depth",type=HintType.INT,optional=@Optional("512")),@Arg(value="flags",type=HintType.INT,optional=@Optional("0"))},result=@Arg(type=HintType.MIXED))
    public static Memory json_decode(Environment env,TraceInfo trace,Memory json)throws Throwable {
        return json_decode(env,trace,json,Memory.NULL,php.runtime.memory.LongMemory.valueOf(512),php.runtime.memory.LongMemory.valueOf(0));
    }
    private static Memory jsonAssociative(Environment env,TraceInfo trace,Memory argument) {
        Memory value=argument.toValue();
        if(value.isNull()||value.getRealType()==Memory.Type.BOOL)return value;
        if(!php.runtime.invoke.NativeFunctionContext.isStrict(env,trace,"json_decode")&&(value.isNumber()||value.isString()))
            return php.runtime.PhpNumericConversions.checkedScalarCoercion(value,value.toBoolean()?Memory.TRUE:Memory.FALSE,env,trace);
        jsonTypeError(env,trace,value,2,"associative","?bool","json_decode");return Memory.NULL;
    }
    private static Memory jsonDecodeError(Environment env,TraceInfo trace,int error,boolean throwsError)throws Throwable {
        if(throwsError) {
            var exception=(Php85JsonException)env.fetchClass(Php85JsonException.class).newObjectWithoutConstruct(env);
            exception.__construct(env,StringMemory.valueOf(JsonFunctions.errorMessage(error)),php.runtime.memory.LongMemory.valueOf(error));
            env.__throwException(trace,new php.runtime.memory.ObjectMemory(exception));
        }else JsonFunctions.setError(env,error);
        return Memory.NULL;
    }

    @Name("json_encode")
    @Signature(value={@Arg(value="value",type=HintType.MIXED),@Arg(value="flags",type=HintType.INT,optional=@Optional("0")),@Arg(value="depth",type=HintType.INT,optional=@Optional("512"))},returnType="string|false")
    public static Memory jsonEncode(Environment env,TraceInfo trace,@Reference Memory value,Memory flags,Memory depth)throws Throwable {
        long options=jsonInteger(env,trace,flags,2,"flags","json_encode");
        long limit=jsonInteger(env,trace,depth,3,"depth","json_encode");
        boolean partial=(options&JsonConstants.JSON_PARTIAL_OUTPUT_ON_ERROR)!=0;
        boolean throwsError=(options&JsonConstants.JSON_THROW_ON_ERROR)!=0;
        Php85JsonEncoder encoder=new Php85JsonEncoder(env,trace,(int)options,(int)limit);
        Memory encoded;
        try {encoded=encoder.encode(value);}
        finally {
            // Native publication also runs after callbacks throw; THROW can replace that exception
            // when an earlier non-finite value already set this encoder's error.
            if(!throwsError||partial)JsonFunctions.setError(env,encoder.error());
            else if(encoder.error()!=JsonConstants.JSON_ERROR_NONE)jsonDecodeError(env,trace,encoder.error(),true);
        }
        return encoder.error()!=JsonConstants.JSON_ERROR_NONE&&!partial?Memory.FALSE:encoded;
    }
    @Name("json_encode")
    @Signature(value={@Arg(value="value",type=HintType.MIXED),@Arg(value="flags",type=HintType.INT,optional=@Optional("0")),@Arg(value="depth",type=HintType.INT,optional=@Optional("512"))},returnType="string|false")
    public static Memory jsonEncode(Environment env,TraceInfo trace,@Reference Memory value,Memory flags)throws Throwable {
        return jsonEncode(env,trace,value,flags,php.runtime.memory.LongMemory.valueOf(512));
    }
    @Name("json_encode")
    @Signature(value={@Arg(value="value",type=HintType.MIXED),@Arg(value="flags",type=HintType.INT,optional=@Optional("0")),@Arg(value="depth",type=HintType.INT,optional=@Optional("512"))},returnType="string|false")
    public static Memory jsonEncode(Environment env,TraceInfo trace,@Reference Memory value)throws Throwable {
        return jsonEncode(env,trace,value,php.runtime.memory.LongMemory.valueOf(0),php.runtime.memory.LongMemory.valueOf(512));
    }

    // Existing ABI27 PHB may still link these pre-coerced Java descriptors.
    @Ignore
    public static Memory json_encode(Environment env,TraceInfo trace,Memory value,int flags,int depth) {
        try {return jsonEncode(env,trace,value,php.runtime.memory.LongMemory.valueOf(flags),php.runtime.memory.LongMemory.valueOf(depth));}
        catch(Throwable failure){return Php85JsonFunctions.<Memory,RuntimeException>encodeFailure(failure);}
    }
    @Ignore
    public static Memory json_encode(Environment env,TraceInfo trace,Memory value,int flags){return json_encode(env,trace,value,flags,512);}
    @Ignore
    public static Memory json_encode(Environment env,TraceInfo trace,Memory value){return json_encode(env,trace,value,0,512);}
    @SuppressWarnings("unchecked") private static <R,T extends Throwable> R encodeFailure(Throwable failure)throws T{throw(T)failure;}
}
