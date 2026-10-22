package org.develnext.jphp.zend.ext.standard.date;

import php.runtime.Memory;
import php.runtime.common.HintType;
import php.runtime.env.*;
import php.runtime.lang.*;
import php.runtime.lang.support.*;
import php.runtime.memory.*;
import php.runtime.reflection.ClassEntity;
import java.time.*;
import static php.runtime.annotation.Reflection.*;

@Name("DateTime")
public class PhpDateTime extends BaseObject implements PhpDateTimeInterface,PhpDateCarrier,php.runtime.lang.support.IPhpNativeDateView,IComparableObject<IObject> {
    private PhpDateValue date;
    public PhpDateTime(Environment env,ClassEntity clazz){super(env,clazz);}
    @Ignore public PhpDateValue dateValue(){return date;}
    @Ignore public void dateValue(PhpDateValue next){date=next;}
    @Ignore public boolean dateImmutable(){return false;}
    @Signature({@Arg(value="datetime",type=HintType.STRING,optional=@Optional("now")),@Arg(value="timezone",typeClass="DateTimeZone",nullable=true,optional=@Optional("null"))})
    public Memory __construct(Environment env,Memory...args){return PhpDateApi.construct(this,env,args);}
    @Signature(value=@Arg(value="format",type=HintType.STRING),result=@Arg(type=HintType.STRING))
    public Memory format(Environment env,Memory...args){return PhpDateApi.format(this,env,args[0]);}
    @Signature(result=@Arg(type=HintType.INT)) public Memory getTimestamp(Environment env,Memory...args){return LongMemory.valueOf(PhpDateApi.value(this,env).instant().getEpochSecond());}
    @Signature(result=@Arg(type=HintType.INT)) public Memory getMicrosecond(Environment env,Memory...args){return LongMemory.valueOf(PhpDateApi.value(this,env).instant().getNano()/1000);}
    @Signature(result=@Arg(type=HintType.INT)) public Memory getOffset(Environment env,Memory...args){return LongMemory.valueOf(PhpDateApi.value(this,env).offset());}
    @Signature(returnType="DateTimeZone|false") public Memory getTimezone(Environment env,Memory...args){return PhpDateApi.timezone(this,env);}
    @Signature(value=@Arg(value="modifier",type=HintType.STRING),returnType="DateTime")
    public Memory modify(Environment env,Memory...args){return PhpDateApi.modify(this,env,args[0]);}
    @Signature(value=@Arg(value="interval",typeClass="DateInterval"),returnType="DateTime")
    public Memory add(Environment env,Memory...args){return PhpDateApi.interval(this,env,args[0],false);}
    @Signature(value=@Arg(value="interval",typeClass="DateInterval"),returnType="DateTime")
    public Memory sub(Environment env,Memory...args){return PhpDateApi.interval(this,env,args[0],true);}
    @Signature(value={@Arg(value="targetObject",typeClass="DateTimeInterface"),@Arg(value="absolute",type=HintType.BOOLEAN,optional=@Optional("false"))},returnType="DateInterval")
    public Memory diff(Environment env,Memory...args){return PhpDateApi.diff(this,env,args[0],args.length>1&&args[1].toBoolean());}
    @Signature(value={@Arg(value="year",type=HintType.INT),@Arg(value="month",type=HintType.INT),@Arg(value="day",type=HintType.INT)},returnType="DateTime")
    public Memory setDate(Environment env,Memory...args) {
        try{return PhpDateApi.change(this,env,before->before.date(args[0].toLong(),args[1].toLong(),args[2].toLong()));}
        catch(DateTimeException|ArithmeticException failure){env.exception(env.trace(),PhpDateRangeError.class,"Date is outside supported range");return Memory.NULL;}
    }
    @Signature(value={@Arg(value="year",type=HintType.INT),@Arg(value="week",type=HintType.INT),@Arg(value="dayOfWeek",type=HintType.INT,optional=@Optional("1"))},returnType="DateTime")
    public Memory setISODate(Environment env,Memory...args) {
        try {
            var jan4=LocalDate.of(Math.toIntExact(args[0].toLong()),1,4);
            var day=jan4.minusDays(jan4.getDayOfWeek().getValue()-1).plusWeeks(Math.subtractExact(args[1].toLong(),1)).plusDays(Math.subtractExact(args[2].toLong(),1));
            return PhpDateApi.change(this,env,before->before.withLocal(LocalDateTime.of(day,before.local().toLocalTime())));
        }catch(DateTimeException|ArithmeticException failure){env.exception(env.trace(),PhpDateRangeError.class,"ISO date is outside supported range");return Memory.NULL;}
    }
    @Signature(value={@Arg(value="hour",type=HintType.INT),@Arg(value="minute",type=HintType.INT),@Arg(value="second",type=HintType.INT,optional=@Optional("0")),@Arg(value="microsecond",type=HintType.INT,optional=@Optional("0"))},returnType="DateTime")
    public Memory setTime(Environment env,Memory...args) {
        try{return PhpDateApi.change(this,env,before->before.time(args[0].toLong(),args[1].toLong(),args[2].toLong(),args[3].toLong()));}
        catch(IllegalArgumentException failure){env.exception(env.trace(),php.runtime.lang.exception.BaseValueError.class,"%s",failure.getMessage());return Memory.NULL;}
        catch(DateTimeException|ArithmeticException failure){env.exception(env.trace(),PhpDateRangeError.class,"Time is outside supported range");return Memory.NULL;}
    }
    @Signature(value=@Arg(value="timestamp",type=HintType.INT),returnType="DateTime")
    public Memory setTimestamp(Environment env,Memory...args) {
        try{return PhpDateApi.change(this,env,before->new PhpDateValue(Instant.ofEpochSecond(args[0].toLong()),before.zone()));}
        catch(DateTimeException failure){env.exception(env.trace(),PhpDateRangeError.class,"Timestamp is outside supported range");return Memory.NULL;}
    }
    @Signature(value=@Arg(value="microsecond",type=HintType.INT),returnType="DateTime")
    public Memory setMicrosecond(Environment env,Memory...args) {
        long micro=args[0].toLong();if(micro<0||micro>999999)env.exception(env.trace(),php.runtime.lang.exception.BaseValueError.class,"DateTime::setMicrosecond(): Argument #1 ($microsecond) must be between 0 and 999999");
        return PhpDateApi.change(this,env,before->new PhpDateValue(Instant.ofEpochSecond(before.instant().getEpochSecond(),micro*1000),before.zone()));
    }
    @Signature(value=@Arg(value="timezone",typeClass="DateTimeZone"),returnType="DateTime")
    public Memory setTimezone(Environment env,Memory...args){return PhpDateApi.change(this,env,before->before.withZone(args[0].toObject(PhpDateTimeZone.class).value(env)));}
    private static ClassEntity called(Environment env){ClassEntity type=env.getLateStaticClass();return type==null||type.getNativeClass()==null||!PhpDateCarrier.class.isAssignableFrom(type.getNativeClass())?PhpDateApi.type(env,"DateTime"):type;}
    @Signature(value={@Arg(value="format",type=HintType.STRING),@Arg(value="datetime",type=HintType.STRING),@Arg(value="timezone",typeClass="DateTimeZone",nullable=true,optional=@Optional("null"))},returnType="DateTime|false")
    public static Memory createFromFormat(Environment env,Memory...args){return PhpDateApi.explicit(env,called(env),args);}
    @Signature(value=@Arg(value="timestamp",typeDeclaration="int|float"),returnType="static")
    public static Memory createFromTimestamp(Environment env,Memory...args) {
        double number=args[0].toDouble();if(!Double.isFinite(number))env.exception(env.trace(),php.runtime.lang.exception.BaseValueError.class,"DateTime::createFromTimestamp(): Argument #1 ($timestamp) must be a finite number");
        try{PhpDateCarrier object=called(env).newObjectWithoutConstruct(env);object.dateValue(PhpDateValue.epoch(args[0].getRealType()==Memory.Type.DOUBLE?java.math.BigDecimal.valueOf(number).toPlainString():Long.toString(args[0].toLong())));return ObjectMemory.valueOf(object);}
        catch(RuntimeException failure){env.exception(env.trace(),PhpDateRangeError.class,"Timestamp is outside supported range");return Memory.NULL;}
    }
    @Signature(value=@Arg(value="object",typeClass="DateTimeInterface"),returnType="DateTime")
    public static Memory createFromInterface(Environment env,Memory...args){PhpDateCarrier source=(PhpDateCarrier)args[0].toValue(ObjectMemory.class).value;PhpDateCarrier copy=called(env).newObjectWithoutConstruct(env);copy.dateValue(PhpDateApi.value(source,env));return ObjectMemory.valueOf(copy);}
    @Signature(value=@Arg(value="object",typeClass="DateTimeImmutable"),returnType="DateTime")
    public static Memory createFromImmutable(Environment env,Memory...args){return createFromInterface(env,args);}
    @Signature(returnType="array|false") public static Memory getLastErrors(Environment env,Memory...args){return PhpDateEnvironment.errors(env);}
    @Signature(result=@Arg(type=HintType.ARRAY)) public Memory __serialize(Environment env,Memory...args){return PhpDateApi.serialize(this,env);}
    @Signature(@Arg(value="data",type=HintType.ARRAY)) public Memory __unserialize(Environment env,Memory...args){return PhpDateApi.restore(this,env,args[0]);}
    @Signature(value=@Arg(value="array",type=HintType.ARRAY),returnType="DateTime")
    public static Memory __set_state(Environment env,Memory...args){PhpDateCarrier copy=called(env).newObjectWithoutConstruct(env);PhpDateApi.restore(copy,env,args[0]);return ObjectMemory.valueOf(copy);}
    @Signature(result=@Arg(type=HintType.ARRAY)) public Memory __debugInfo(Environment env,Memory...args){return PhpDateApi.export(this,env);}
    @Ignore public ArrayMemory phpDateExport(Environment env){return PhpDateApi.export(this,env);}
    @Ignore public boolean phpDateComparable(IObject other){return other instanceof PhpDateCarrier;}
    @Ignore public void phpDateCopyStateTo(IObject clone){if(clone.getReflection()!=getReflection()||!(clone instanceof PhpDateCarrier copy))throw new IllegalArgumentException("Foreign native date clone");copy.dateValue(date);}
    @Ignore private int compare(IObject other){return other instanceof PhpDateCarrier date?PhpDateApi.value(this,getEnvironment()).instant().compareTo(PhpDateApi.value(date,date.getEnvironment()).instant()):1;}
    @Ignore public boolean __equal(IObject other){return other instanceof PhpDateCarrier&&compare(other)==0;}
    @Ignore public boolean __identical(IObject other){return this==other;}
    @Ignore public boolean __greater(IObject other){return compare(other)>0;}
    @Ignore public boolean __greaterEq(IObject other){return compare(other)>=0;}
    @Ignore public boolean __smaller(IObject other){return compare(other)<0;}
    @Ignore public boolean __smallerEq(IObject other){return compare(other)<=0;}
}
