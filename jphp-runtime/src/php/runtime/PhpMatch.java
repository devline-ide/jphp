package php.runtime;

import php.runtime.env.Environment;
import php.runtime.env.TraceInfo;
import php.runtime.lang.exception.BaseUnhandledMatchError;
import php.runtime.memory.ObjectMemory;

/** The cold non-exhaustive-match path never calls PHP string conversion methods. */
public final class PhpMatch {
    private PhpMatch() {}
    public static Memory unhandled(Memory subject, Environment env, TraceInfo trace) {
        subject=subject.toValue();String value;
        switch(subject.getRealType()) {
            case NULL: value="NULL";break;
            case BOOL: value=subject.toBoolean()?"true":"false";break;
            case INT: value=subject.toString();break;
            case DOUBLE:
                double number=subject.toDouble();
                value=Double.isNaN(number)?"NAN":Double.isInfinite(number)?(number>0?"INF":"-INF"):Double.toString(number);
                int exponent=value.indexOf('E');
                if(exponent>=0 && value.charAt(exponent+1)!='-') value=value.substring(0,exponent+1)+"+"+value.substring(exponent+1);
                break;
            case STRING:
                String text=subject.toString();value="'"+(text.length()>15?text.substring(0,15)+"...":text)+"'";break;
            case OBJECT: value="of type "+((ObjectMemory)subject).value.getReflection().getName();break;
            default: value="of type "+subject.getRealType().toString().toLowerCase(java.util.Locale.ROOT);
        }
        env.exception(trace,BaseUnhandledMatchError.class,"Unhandled match case %s",value);
        return Memory.NULL;
    }
}
