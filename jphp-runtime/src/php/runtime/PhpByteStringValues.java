package php.runtime;

import php.runtime.env.Environment;
import php.runtime.env.TraceInfo;
import php.runtime.exceptions.support.ErrorType;
import php.runtime.memory.BinaryMemory;
import php.runtime.memory.PhpByteStringMemory;
import php.runtime.memory.StringMemory;

/** Compiler STRING_VALUE union: Java String or immutable PHP bytes, never a Memory address. */
public final class PhpByteStringValues {
    private PhpByteStringValues() {}
    public static Memory box(Object value) {
        if (value instanceof String text) return StringMemory.valueOf(text);
        if (value instanceof PhpByteStringMemory raw) return raw;
        throw new IllegalArgumentException("Invalid PHP string value union");
    }
    public static String toJavaString(Object value) {
        if (value instanceof String text) return text;
        if (value instanceof PhpByteStringMemory raw) return raw.toString();
        throw new IllegalArgumentException("Invalid PHP string value union");
    }
    public static long toLong(Object value) {
        return value instanceof String text ? StringMemory.toNumeric(text).toLong() : box(value).toLong();
    }
    public static double toDouble(Object value) {
        return value instanceof String text ? StringMemory.toNumeric(text).toDouble() : box(value).toDouble();
    }
    public static boolean toBoolean(Object value) {
        return value instanceof String text ? !text.isEmpty() && !text.equals("0") : box(value).toBoolean();
    }
    public static char toChar(Object value) {
        return value instanceof String text ? (text.isEmpty() ? 0 : text.charAt(0)) : box(value).toChar();
    }
    public static Object append(Object builder, Object value, Environment env, TraceInfo trace) throws Throwable {
        if (value instanceof String text) return PhpByteStringBuilders.append(builder,text,env,trace);
        return PhpByteStringBuilders.append(builder,box(value),env,trace);
    }
    private static Object stringObject(Object value, Environment env, TraceInfo trace) throws Throwable {
        if (value instanceof String) return value;
        if (!(value instanceof Memory)) throw new IllegalArgumentException("Concat operand is neither Java text nor PHP Memory");
        Memory actual=((Memory)value).toValue();
        if (actual instanceof PhpByteStringMemory) return actual;
        if (actual.isString() && !(actual instanceof BinaryMemory)) return actual.toString();
        if (actual.type==Memory.Type.INT) return Long.toString(actual.toLong());
        if (actual.type==Memory.Type.DOUBLE) return doubleString(actual.toDouble(),env,trace);
        if (actual.type==Memory.Type.BOOL) return actual.toBoolean() ? "1" : "";
        if (actual.isNull()) return "";
        Memory result=PhpByteStrings.stringValue(actual,env,trace);
        return result instanceof PhpByteStringMemory ? result : result.toString();
    }
    private static String doubleString(double value, Environment env, TraceInfo trace) {
        String text=PhpByteStrings.floatString(value,env);
        if (env!=null && Double.isNaN(value)) env.error(trace,ErrorType.E_WARNING,"unexpected NAN value was coerced to string");
        return text;
    }
    /** The existing private property callback cannot declare checked exceptions. */
    public static Memory concatPropertyValue(Memory left,Memory right,Environment env,TraceInfo trace) {
        try {return box(concat(left,right,env,trace));}
        catch(Throwable failure){return PhpByteStringValues.<RuntimeException>rethrow(failure);}
    }
    private static <T extends Throwable> Memory rethrow(Throwable failure)throws T {throw (T)failure;}
    private static Object join(Object left,Object right) {
        if (left instanceof String a && right instanceof String b) return a.concat(b);
        PhpByteStringBuilder builder=new PhpByteStringBuilder();
        if (left instanceof String text) builder.append(text);else builder.appendStringValue((Memory)left);
        if (right instanceof String text) builder.append(text);else builder.appendStringValue((Memory)right);
        return builder.toImmutable();
    }
    public static Object concat(Object left,Object right,Environment env,TraceInfo trace)throws Throwable {
        Object a=stringObject(left,env,trace);Object b=stringObject(right,env,trace);return join(a,b);
    }
    public static Object concat(Object left,long right,Environment env,TraceInfo trace)throws Throwable {return join(stringObject(left,env,trace),Long.toString(right));}
    public static Object concat(long left,Object right,Environment env,TraceInfo trace)throws Throwable {return join(Long.toString(left),stringObject(right,env,trace));}
    public static Object concat(Object left,double right,Environment env,TraceInfo trace)throws Throwable {Object a=stringObject(left,env,trace);return join(a,doubleString(right,env,trace));}
    public static Object concat(double left,Object right,Environment env,TraceInfo trace)throws Throwable {String a=doubleString(left,env,trace);return join(a,stringObject(right,env,trace));}
    public static Object concat(Object left,boolean right,Environment env,TraceInfo trace)throws Throwable {return join(stringObject(left,env,trace),right ? "1" : "");}
    public static Object concat(boolean left,Object right,Environment env,TraceInfo trace)throws Throwable {return join(left ? "1" : "",stringObject(right,env,trace));}
    public static Object concat(Object left,char right,Environment env,TraceInfo trace)throws Throwable {return join(stringObject(left,env,trace),String.valueOf(right));}
    public static Object concat(char left,Object right,Environment env,TraceInfo trace)throws Throwable {return join(String.valueOf(left),stringObject(right,env,trace));}
    public static Memory assign(Memory left,Object right,Environment env,TraceInfo trace)throws Throwable {PhpStringOffsets.requireAssignOp(left,env,trace);return left.assign(box(concat(left,right,env,trace)));}
    public static Memory assign(Memory left,long right,Environment env,TraceInfo trace)throws Throwable {PhpStringOffsets.requireAssignOp(left,env,trace);return left.assign(box(concat(left,right,env,trace)));}
    public static Memory assign(Memory left,double right,Environment env,TraceInfo trace)throws Throwable {PhpStringOffsets.requireAssignOp(left,env,trace);return left.assign(box(concat(left,right,env,trace)));}
    public static Memory assign(Memory left,boolean right,Environment env,TraceInfo trace)throws Throwable {PhpStringOffsets.requireAssignOp(left,env,trace);return left.assign(box(concat(left,right,env,trace)));}
    public static Memory assign(Memory left,char right,Environment env,TraceInfo trace)throws Throwable {PhpStringOffsets.requireAssignOp(left,env,trace);return left.assign(box(concat(left,right,env,trace)));}
    public static Object concat(long left,long right,Environment env,TraceInfo trace){String a=Long.toString(left);String b=Long.toString(right);return a.concat(b);}
    public static Object concat(long left,double right,Environment env,TraceInfo trace){String a=Long.toString(left);String b=doubleString(right,env,trace);return a.concat(b);}
    public static Object concat(long left,boolean right,Environment env,TraceInfo trace){String a=Long.toString(left);String b=(right ? "1" : "");return a.concat(b);}
    public static Object concat(long left,char right,Environment env,TraceInfo trace){String a=Long.toString(left);String b=String.valueOf(right);return a.concat(b);}
    public static Object concat(double left,long right,Environment env,TraceInfo trace){String a=doubleString(left,env,trace);String b=Long.toString(right);return a.concat(b);}
    public static Object concat(double left,double right,Environment env,TraceInfo trace){String a=doubleString(left,env,trace);String b=doubleString(right,env,trace);return a.concat(b);}
    public static Object concat(double left,boolean right,Environment env,TraceInfo trace){String a=doubleString(left,env,trace);String b=(right ? "1" : "");return a.concat(b);}
    public static Object concat(double left,char right,Environment env,TraceInfo trace){String a=doubleString(left,env,trace);String b=String.valueOf(right);return a.concat(b);}
    public static Object concat(boolean left,long right,Environment env,TraceInfo trace){String a=(left ? "1" : "");String b=Long.toString(right);return a.concat(b);}
    public static Object concat(boolean left,double right,Environment env,TraceInfo trace){String a=(left ? "1" : "");String b=doubleString(right,env,trace);return a.concat(b);}
    public static Object concat(boolean left,boolean right,Environment env,TraceInfo trace){String a=(left ? "1" : "");String b=(right ? "1" : "");return a.concat(b);}
    public static Object concat(boolean left,char right,Environment env,TraceInfo trace){String a=(left ? "1" : "");String b=String.valueOf(right);return a.concat(b);}
    public static Object concat(char left,long right,Environment env,TraceInfo trace){String a=String.valueOf(left);String b=Long.toString(right);return a.concat(b);}
    public static Object concat(char left,double right,Environment env,TraceInfo trace){String a=String.valueOf(left);String b=doubleString(right,env,trace);return a.concat(b);}
    public static Object concat(char left,boolean right,Environment env,TraceInfo trace){String a=String.valueOf(left);String b=(right ? "1" : "");return a.concat(b);}
    public static Object concat(char left,char right,Environment env,TraceInfo trace){String a=String.valueOf(left);String b=String.valueOf(right);return a.concat(b);}
}
