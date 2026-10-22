package org.develnext.jphp.zend.ext.standard.date;
import php.runtime.Memory;
import php.runtime.common.HintType;
import php.runtime.env.Environment;
import php.runtime.env.TraceInfo;
import php.runtime.ext.support.compile.FunctionsContainer;
import php.runtime.exceptions.support.ErrorType;
import static php.runtime.annotation.Reflection.*;
public final class PhpDateFunctions extends FunctionsContainer {
    @Signature(result=@Arg(type=HintType.STRING))
    public static String date_default_timezone_get(Environment env){return PhpDateEnvironment.zone(env).name();}
    @Signature(value=@Arg(value="timezoneId",type=HintType.STRING),result=@Arg(type=HintType.BOOLEAN))
    public static boolean date_default_timezone_set(Environment env,TraceInfo trace,Memory argument) {
        PhpDateZone next;
        try{next=PhpDateZone.parse(argument.toString());if(next.kind()!=3)throw new IllegalArgumentException();}
        catch(RuntimeException bad){env.error(trace,ErrorType.E_NOTICE,"date_default_timezone_set(): Timezone ID '%s' is invalid",argument);return false;}
        PhpDateEnvironment.zone(env,next);return true;
    }
}
