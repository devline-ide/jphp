package org.develnext.jphp.zend.ext.json;

import com.google.gson.*;
import org.develnext.jphp.json.gson.MemorySerializer;
import php.runtime.Memory;
import php.runtime.annotation.Reflection.*;
import php.runtime.common.HintType;
import php.runtime.env.Environment;
import php.runtime.env.TraceInfo;
import php.runtime.ext.support.compile.FunctionsContainer;
import php.runtime.memory.StringMemory;

/** Modern JSON dispatch keeps the legacy Java String overload contract intact. */
public final class Php85JsonFunctions extends FunctionsContainer {
    @Signature({@Arg("value"),@Arg(value="flags",type=HintType.INT,optional=@Optional("0")),@Arg(value="depth",type=HintType.INT,optional=@Optional("512"))})
    public static Memory json_encode(Environment env,TraceInfo trace,Memory value,int flags,int depth) {
        if(depth<=0)env.exception(trace,php.runtime.lang.exception.BaseValueError.class,"json_encode(): Argument #3 ($depth) must be greater than 0");
        MemorySerializer serializer=new MemorySerializer();serializer.setEnv(env);serializer.setUsePhpArrayKeys(true);
        boolean partial=(flags&JsonConstants.JSON_PARTIAL_OUTPUT_ON_ERROR)!=0,throwsError=(flags&JsonConstants.JSON_THROW_ON_ERROR)!=0;
        serializer.setEnumPartialOutput(partial);
        GsonBuilder builder=JsonExtension.createGsonBuilder(serializer);
        if((flags&JsonConstants.JSON_HEX_TAG)==0)builder.disableHtmlEscaping();
        if((flags&JsonConstants.JSON_PRETTY_PRINT)!=0)builder.setPrettyPrinting();
        serializer.setForceObject((flags&JsonConstants.JSON_FORCE_OBJECT)!=0);
        serializer.setNumericCheck((flags&JsonConstants.JSON_NUMERIC_CHECK)!=0);
        try {
            String output=builder.create().toJson(value,Memory.class);
            if(!throwsError || partial)env.setUserValue(JsonFunctions.class.getName()+"#error",serializer.hasEnumError()?JsonConstants.JSON_ERROR_NON_BACKED_ENUM:null);
            return StringMemory.valueOf(output);
        } catch(MemorySerializer.NonBackedEnumException failure) {
            if(throwsError && !partial)env.exception(trace,Php85JsonException.class,failure.getMessage());
            env.setUserValue(JsonFunctions.class.getName()+"#error",JsonConstants.JSON_ERROR_NON_BACKED_ENUM);
            return Memory.FALSE;
        }
    }
    @Signature({@Arg("value"),@Arg(value="flags",type=HintType.INT,optional=@Optional("0")),@Arg(value="depth",type=HintType.INT,optional=@Optional("512"))})
    public static Memory json_encode(Environment env,TraceInfo trace,Memory value,int flags){return json_encode(env,trace,value,flags,512);}
    @Signature({@Arg("value"),@Arg(value="flags",type=HintType.INT,optional=@Optional("0")),@Arg(value="depth",type=HintType.INT,optional=@Optional("512"))})
    public static Memory json_encode(Environment env,TraceInfo trace,Memory value){return json_encode(env,trace,value,0,512);}
}
