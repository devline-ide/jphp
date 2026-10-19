package php.runtime;

import php.runtime.env.Environment;
import php.runtime.env.TraceInfo;
import php.runtime.exceptions.support.ErrorType;
import php.runtime.lang.exception.BaseError;
import php.runtime.lang.exception.BaseTypeError;
import php.runtime.memory.*;
import java.nio.charset.StandardCharsets;

/** PHP85 byte offsets; compiler dispatch keeps legacy context-free Java signatures intact. */
public final class PhpStringOffsets {
    private PhpStringOffsets() {}
    public static Memory read(Memory root, TraceInfo trace, Memory key, Environment env) {
        Memory value=root.toValue();
        if(!value.isString())return root.valueOfIndex(trace,key);
        long offset=index(key,env,trace);
        return byteAt(value,offset,env,trace);
    }
    private static Memory byteAt(Memory value,long offset,Environment env,TraceInfo trace) {
        if(value instanceof PhpByteStringMemory raw) {
            long position=offset<0 ? raw.byteLength()+offset : offset;
            if(position>=0 && position<raw.byteLength())return raw.byteAt(offset);
        }else {
            String text=value.toString();boolean ascii=true;
            if(offset>=0) {
                for(int i=0;i<text.length() && i<=offset;i++)if(text.charAt(i)>127){ascii=false;break;}
            }else {
                for(int i=text.length()-1;i>=0 && (long)i>=text.length()+offset;i--)if(text.charAt(i)>127){ascii=false;break;}
            }
            if(ascii) {
                long position=offset<0 ? text.length()+offset : offset;
                if(position>=0 && position<text.length())return StringMemory.valueOf(text.charAt((int)position));
            }else {
                byte[] bytes=value.getBinaryBytes(StandardCharsets.UTF_8);long position=offset<0 ? bytes.length+offset : offset;
                if(position>=0 && position<bytes.length) {
                    byte selected=bytes[(int)position];return selected>=0 ? StringMemory.valueOf((char)selected) : new PhpByteStringMemory(bytes,(int)position,1);
                }
            }
        }
        if(env!=null)env.error(trace,ErrorType.E_WARNING,"Uninitialized string offset %s",offset);
        return Memory.CONST_EMPTY_STRING;
    }
    public static Memory shortcut(Memory root, TraceInfo trace, Memory key, Environment env) {
        return root.toValue().isString() ? reference(root,trace,key,env) : root.refOfIndexAsShortcut(trace,key);
    }
    public static Memory isset(Memory root,TraceInfo trace,Memory key,Environment env) {
        if(!root.toValue().isString())return root.issetOfIndex(trace,key);
        return quiet(root.toValue(),key,trace);
    }
    public static Memory empty(Memory root,TraceInfo trace,Memory key,Environment env) {
        if(!root.toValue().isString())return root.emptyOfIndex(trace,key);
        return quiet(root.toValue(),key,trace);
    }
    private static Memory quiet(Memory value,Memory key,TraceInfo trace) {
        key=key.toValue();long offset;
        if(key.isString()) {
            Memory parsed=PhpNumericString.parseStrict(PhpByteStrings.numericText(key));
            if(parsed==null || parsed.type!=Memory.Type.INT)return Memory.NULL;offset=parsed.toLong();
        }else if(key.type==Memory.Type.INT || key.type==Memory.Type.BOOL || key.isNull())offset=key.toLong();
        else if(key.type==Memory.Type.DOUBLE)offset=PhpNumericConversions.floatToLong(key.toDouble());
        else return Memory.NULL;
        Memory selected=byteAt(value,offset,null,trace);
        return selected==Memory.CONST_EMPTY_STRING ? Memory.NULL : selected;
    }
    public static Memory reference(Memory root, TraceInfo trace, Memory key, Environment env) {
        Memory value=root.toValue();
        if (!value.isString()) return root.refOfIndex(trace,key);
        if (root instanceof CharMemory) env.exception(trace,BaseError.class,"Cannot use string offset as an array");
        if (!(root instanceof ReferenceMemory)) throw new IllegalArgumentException("String offset write needs its actual reference");
        long offset=index(key,env,trace);
        return new ByteOffset((ReferenceMemory)root,value,offset,env,trace);
    }
    static long index(Memory key,Environment env,TraceInfo trace) {
        key=key.toValue();
        if(key.isString()) {
            String text=PhpByteStrings.numericText(key);Memory number=PhpNumericString.parseStrict(text);
            if(number!=null && number.type==Memory.Type.INT)return number.toLong();
            Memory leading=PhpNumericString.parseLeading(text);
            if(number==null && PhpNumericString.hasLeadingNumber(text) && leading.type==Memory.Type.INT) {
                env.error(trace,ErrorType.E_WARNING,"Illegal string offset \"%s\"",text);return leading.toLong();
            }
        }else if(key.type==Memory.Type.INT)return key.toLong();
        else if(key.type==Memory.Type.DOUBLE || key.type==Memory.Type.BOOL || key.isNull()) {
            env.error(trace,ErrorType.E_WARNING,"String offset cast occurred");
            return key.type==Memory.Type.DOUBLE ? PhpNumericConversions.floatToLong(key.toDouble()) : key.toLong();
        }
        env.exception(trace,BaseTypeError.class,"Cannot access offset of type %s on string",php.runtime.reflection.support.PhpTypeDeclaration.valueType(key));
        return 0;
    }
    /** Existing CharMemory identity retains the native prohibition on references and assign-op/increment. */
    private static final class ByteOffset extends CharMemory {
        private final long offset;
        private final Memory selected;
        private final Environment env;
        private final TraceInfo trace;
        ByteOffset(ReferenceMemory origin,Memory selected,long offset,Environment env,TraceInfo trace) {
            super("",origin,0);this.selected=selected;this.offset=offset;this.env=env;this.trace=trace;
        }
        private Memory read() {return byteAt(selected,offset,env,trace);}
        @Override public Memory toImmutable(){return read();}
        @Override public String toString(){return read().toString();}
        @Override public byte[] getBinaryBytes(java.nio.charset.Charset charset){return read().getBinaryBytes(charset);}
        @Override public Memory assignRef(Memory value) {
            env.exception(trace,BaseError.class,"Cannot create references to/from string offsets");return Memory.NULL;
        }
        @Override public Memory assign(Memory value) {
            try {
                if(origin.toValue()!=selected)return Memory.NULL;
                byte[] bytes=selected.getBinaryBytes(StandardCharsets.UTF_8);long position=offset<0 ? bytes.length+offset : offset;
                if(position<0) {env.error(trace,ErrorType.E_WARNING,"Illegal string offset %s",offset);return Memory.NULL;}
                Memory converted=PhpByteStrings.stringValue(value,env,trace);byte[] replacement=converted.getBinaryBytes(StandardCharsets.UTF_8);
                if(replacement.length==0)env.exception(trace,BaseError.class,"Cannot assign an empty string to a string offset");
                if(replacement.length>1)env.error(trace,ErrorType.E_WARNING,"Only the first byte will be assigned to the string offset");
                if(origin.toValue()!=selected)return Memory.NULL;
                if(position>Integer.MAX_VALUE-1)throw new IllegalArgumentException("String offset exceeds Java array capacity");
                byte[] result=java.util.Arrays.copyOf(bytes,(int)Math.max(bytes.length,position+1));
                if(position>=bytes.length)java.util.Arrays.fill(result,bytes.length,(int)position,(byte)' ');
                result[(int)position]=replacement[0];origin.assign(new PhpByteStringMemory(result));
                return replacement[0]>=0 ? StringMemory.valueOf((char)replacement[0]) : new PhpByteStringMemory(replacement,0,1);
            }catch(Throwable failure){PhpStringOffsets.<RuntimeException>raise(failure);return Memory.NULL;}
        }
        @Override public Memory assign(String value){return assign(StringMemory.valueOf(value));}
        @Override public Memory assign(long value){return assign(LongMemory.valueOf(value));}
        @Override public Memory assign(double value){return assign(DoubleMemory.valueOf(value));}
        @Override public Memory assign(boolean value){return assign(value ? Memory.TRUE : Memory.FALSE);}
    }
    public static void requireAssignOp(Memory target,Environment env,TraceInfo trace) {
        if(target instanceof CharMemory)env.exception(trace,BaseError.class,"Cannot use assign-op operators with string offsets");
    }
    @SuppressWarnings("unchecked") private static <T extends Throwable> void raise(Throwable failure)throws T{throw (T)failure;}
}
