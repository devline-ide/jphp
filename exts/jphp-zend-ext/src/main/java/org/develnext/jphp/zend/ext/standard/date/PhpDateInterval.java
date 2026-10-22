package org.develnext.jphp.zend.ext.standard.date;

import php.runtime.Memory;
import php.runtime.env.*;
import php.runtime.lang.BaseObject;
import php.runtime.memory.*;
import php.runtime.reflection.ClassEntity;
import java.util.regex.*;
import static php.runtime.annotation.Reflection.*;
import php.runtime.common.HintType;

@Name("DateInterval")
@Signature({@Arg("y"),@Arg("m"),@Arg("d"),@Arg("h"),@Arg("i"),@Arg("s"),@Arg("f"),@Arg("invert"),@Arg("days"),@Arg("from_string")})
public class PhpDateInterval extends BaseObject {
    record Value(long years,long months,long days,long hours,long minutes,long seconds,long micros,boolean invert,Long totalDays) {}
    private static final Pattern ISO=Pattern.compile("P(?:(\\d+)Y)?(?:(\\d+)M)?(?:(\\d+)W)?(?:(\\d+)D)?(?:T(?:(\\d+)H)?(?:(\\d+)M)?(?:(\\d+)S)?)?");
    public PhpDateInterval(Environment env,ClassEntity clazz){super(env,clazz);}
    static Value parse(String text) {
        Matcher m=ISO.matcher(text);if(!m.matches()||text.equals("P")||text.equals("PT"))throw new IllegalArgumentException("Unknown or bad format ("+text+")");
        long[] n=new long[7];for(int i=0;i<n.length;i++)n[i]=m.group(i+1)==null?0:Long.parseLong(m.group(i+1));
        return new Value(n[0],n[1],Math.addExact(Math.multiplyExact(n[2],7),n[3]),n[4],n[5],n[6],0,false,null);
    }
    @Signature(@Arg(value="duration",type=HintType.STRING))
    public Memory __construct(Environment env,Memory...args) {
        try{store(parse(args[0].toString()));}
        catch(IllegalArgumentException error){env.exception(env.trace(),PhpDateMalformedIntervalStringException.class,"%s",error.getMessage());}
        return Memory.NULL;
    }
    @Ignore void store(Value v) {
        String[] keys={"y","m","d","h","i","s"};long[] values={v.years,v.months,v.days,v.hours,v.minutes,v.seconds};
        for(int i=0;i<keys.length;i++)getProperties().refOfIndex(keys[i]).assign(values[i]);
        getProperties().refOfIndex("f").assign(v.micros/1000000.0);getProperties().refOfIndex("invert").assign(v.invert?1:0);
        getProperties().refOfIndex("days").assign(v.totalDays==null?Memory.FALSE:LongMemory.valueOf(v.totalDays));
        getProperties().refOfIndex("from_string").assign(false);
    }
    @Ignore Value value() {
        var p=getProperties();Memory fraction=p.valueOfIndex("f");double f=fraction.toDouble();
        if(!Double.isFinite(f)||f>Long.MAX_VALUE/1000000.0||f<Long.MIN_VALUE/1000000.0)throw new IllegalArgumentException("Invalid DateInterval fraction");
        Memory days=p.valueOfIndex("days");return new Value(p.valueOfIndex("y").toLong(),p.valueOfIndex("m").toLong(),p.valueOfIndex("d").toLong(),
                p.valueOfIndex("h").toLong(),p.valueOfIndex("i").toLong(),p.valueOfIndex("s").toLong(),(long)(f*1000000),p.valueOfIndex("invert").toBoolean(),days.getRealType()==Memory.Type.BOOL?null:days.toLong());
    }
    @Signature(value=@Arg(value="format",type=HintType.STRING),result=@Arg(type=HintType.STRING))
    public Memory format(Environment env,Memory...args) {
        Value v=value();byte[] input=args[0].getBinaryBytes(java.nio.charset.StandardCharsets.UTF_8);var out=new java.io.ByteArrayOutputStream();
        for(int at=0;at<input.length;at++) {
            if(input[at]!='%'||at+1==input.length){out.write(input[at]);continue;}
            int code=input[++at]&255;long n=switch(Character.toLowerCase(code)){case 'y'->v.years;case 'm'->v.months;case 'd'->v.days;case 'h'->v.hours;case 'i'->v.minutes;case 's'->v.seconds;case 'f'->v.micros;default->-1;};
            String text=code=='a'?(v.totalDays==null?"(unknown)":v.totalDays.toString()):code=='R'?(v.invert?"-":"+"):code=='r'?(v.invert?"-":""):code=='%'?"%":
                    n>=0?(Character.isUpperCase(code)?PhpDateFormat.pad(n,code=='F'?6:2):Long.toString(n)):null;
            if(text==null){out.write('%');out.write(code);}else out.writeBytes(text.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        }
        return new PhpByteStringMemory(out.toByteArray());
    }
    @Signature(result=@Arg(type=HintType.ARRAY)) public Memory __serialize(Environment env,Memory...args){return getProperties().toImmutable();}
    @Signature(@Arg(value="data",type=HintType.ARRAY)) public Memory __unserialize(Environment env,Memory...args) {
        ArrayMemory data=args[0].toValue(ArrayMemory.class);var iterator=data.foreachIterator(false,false);
        while(iterator.next())getProperties().put(iterator.getKey(),iterator.getValue());return Memory.NULL;
    }
}
