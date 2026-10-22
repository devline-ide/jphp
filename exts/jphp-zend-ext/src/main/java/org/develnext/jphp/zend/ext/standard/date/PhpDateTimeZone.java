package org.develnext.jphp.zend.ext.standard.date;

import php.runtime.Memory;
import php.runtime.common.HintType;
import php.runtime.env.*;
import php.runtime.lang.BaseObject;
import php.runtime.lang.support.ICloneableObject;
import php.runtime.memory.*;
import php.runtime.reflection.ClassEntity;
import java.time.*;
import static php.runtime.annotation.Reflection.*;

@Name("DateTimeZone")
public class PhpDateTimeZone extends BaseObject implements php.runtime.lang.support.IPhpNativeDateView {
    public static final int AFRICA=1,AMERICA=2,ANTARCTICA=4,ARCTIC=8,ASIA=16,ATLANTIC=32,AUSTRALIA=64,EUROPE=128,
            INDIAN=256,PACIFIC=512,UTC=1024,ALL=2047,ALL_WITH_BC=4095,PER_COUNTRY=4096;
    private PhpDateZone zone;
    public PhpDateTimeZone(Environment env,ClassEntity clazz){super(env,clazz);}
    @Ignore PhpDateZone value(Environment env){if(zone==null){String actual=getReflection().getName();if(actual.equals("DateTimeZone"))env.exception(env.trace(),PhpDateObjectError.class,"The DateTimeZone object has not been correctly initialized by its constructor");else env.exception(env.trace(),PhpDateObjectError.class,"Object of type %s (inheriting DateTimeZone) has not been correctly initialized by calling parent::__construct() in its constructor",actual);}return zone;}
    @Ignore static PhpDateTimeZone create(Environment env,PhpDateZone zone){var result=new PhpDateTimeZone(env,PhpDateApi.type(env,"DateTimeZone"));result.zone=zone;return result;}
    @Signature(@Arg(value="timezone",type=HintType.STRING)) public Memory __construct(Environment env,Memory...args) {
        try{zone=PhpDateZone.parse(args[0].toString());}
        catch(RuntimeException bad){env.exception(env.trace(),PhpDateInvalidTimeZoneException.class,"DateTimeZone::__construct(): Unknown or bad timezone (%s)",args[0]);}
        return Memory.NULL;
    }
    @Signature(result=@Arg(type=HintType.STRING)) public Memory getName(Environment env,Memory...args){return new StringMemory(value(env).name());}
    @Signature(value=@Arg(value="datetime",typeClass="DateTimeInterface"),result=@Arg(type=HintType.INT)) public Memory getOffset(Environment env,Memory...args) {
        PhpDateCarrier date=(PhpDateCarrier)args[0].toValue(ObjectMemory.class).value;return LongMemory.valueOf(value(env).offsetAt(PhpDateApi.value(date,env).instant()));
    }
    @Signature(value={@Arg(value="timestampBegin",type=HintType.INT,optional=@Optional("-9223372036854775808")),@Arg(value="timestampEnd",type=HintType.INT,optional=@Optional("9223372036854775807"))},returnType="array|false")
    public Memory getTransitions(Environment env,Memory...args) {
        PhpDateZone selected=value(env);if(selected.kind()!=3)return Memory.FALSE;
        long begin=args[0].toLong(),end=args[1].toLong();var result=new ArrayMemory();if(begin>=end)return result.toConstant();
        // java.time Instant does not support the entire PHP signed-64-bit range. Do not silently clamp it.
        Instant cursor;
        try{cursor=Instant.ofEpochSecond(begin);}catch(DateTimeException failure){env.exception(env.trace(),PhpDateRangeError.class,"Transition timestamp is outside supported java.time range");return Memory.NULL;}
        result.add(transition(selected,cursor));
        while(true) {
            var next=selected.region().getRules().nextTransition(cursor);if(next==null||next.getInstant().getEpochSecond()>=end)break;
            cursor=next.getInstant();result.add(transition(selected,cursor));
        }
        return result.toConstant();
    }
    private static Memory transition(PhpDateZone zone,Instant instant) {
        var result=new ArrayMemory();result.refOfIndex("ts").assign(instant.getEpochSecond());
        result.refOfIndex("time").assign(PhpDateFormat.text(new PhpDateValue(instant,PhpDateZone.offset(0)),"Y-m-d\\TH:i:sP"));
        result.refOfIndex("offset").assign(zone.offsetAt(instant));result.refOfIndex("isdst").assign(zone.daylight(instant));result.refOfIndex("abbr").assign(zone.abbreviation(instant));return result;
    }
    private ArrayMemory nativeFields(Environment env){var data=new ArrayMemory();data.refOfIndex("timezone_type").assign(value(env).kind());data.refOfIndex("timezone").assign(zone.name());return data;}
    @Ignore public ArrayMemory dateExport(Environment env){var data=new ArrayMemory();var own=getProperties().foreachIterator(false,false);while(own.next())data.put(own.getKey(),own.getValue());if(zone!=null){var nativeView=nativeFields(env).foreachIterator(false,false);while(nativeView.next())data.put(nativeView.getKey(),nativeView.getValue());}return data;}
    private ArrayMemory dateSerialize(Environment env){var data=nativeFields(env);var own=getProperties().foreachIterator(false,false);while(own.next()){String key=own.getKey().toString();if(!key.equals("timezone_type")&&!key.equals("timezone"))data.put(own.getKey(),own.getValue());}return data;}
    @Ignore public ArrayMemory phpDateExport(Environment env){return dateExport(env);}
    @Signature(result=@Arg(type=HintType.ARRAY)) public Memory __serialize(Environment env,Memory...args){return dateSerialize(env);}
    @Signature(@Arg(value="data",type=HintType.ARRAY)) public Memory __unserialize(Environment env,Memory...args) {
        ArrayMemory data=args[0].toValue(ArrayMemory.class);Memory kind=data.valueOfIndex("timezone_type"),name=data.valueOfIndex("timezone");
        try{PhpDateZone next=PhpDateZone.parse(name.toString());if(!kind.isNumber()||!name.isString()||next.kind()!=kind.toLong())throw new IllegalArgumentException();zone=next;
            var own=data.foreachIterator(false,false);while(own.next()){String key=own.getKey().toString();if(!key.equals("timezone_type")&&!key.equals("timezone"))getProperties().put(own.getKey(),own.getValue());}}
        catch(RuntimeException bad){env.exception(env.trace(),PhpDateObjectError.class,"Invalid serialization data for DateTimeZone object");}
        return Memory.NULL;
    }
    @Signature(result=@Arg(type=HintType.ARRAY)) public Memory __debugInfo(Environment env,Memory...args){return dateExport(env);}
    @Ignore public void phpDateCopyStateTo(php.runtime.lang.IObject clone){if(clone.getReflection()!=getReflection()||!(clone instanceof PhpDateTimeZone copy))throw new IllegalArgumentException("Foreign native timezone clone");copy.zone=zone;}
    @SuppressWarnings("unchecked") private static <T extends Throwable,R>R raise(Throwable failure)throws T{throw(T)failure;}
}
