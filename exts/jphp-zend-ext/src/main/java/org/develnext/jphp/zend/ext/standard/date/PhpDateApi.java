/*
 * Relative calendar normalization and explicit-format diagnostic rules follow timelib's MIT contract.
 * Primary sources: php-src/php-8.5.11/ext/date/lib/{interval.c,tm2unixtime.c,parse_date.re}.
 *
 * Copyright (c) 2015-2023 Derick Rethans
 * Copyright (c) 2018 MongoDB, Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package org.develnext.jphp.zend.ext.standard.date;

import php.runtime.Memory;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.*;
import php.runtime.lang.BaseObject;
import php.runtime.memory.*;
import php.runtime.reflection.ClassEntity;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.temporal.ChronoUnit;

/** Shared operations, with state publication only after complete parse/coercion/normalization. */
final class PhpDateApi {
    static PhpDateValue value(PhpDateCarrier object,Environment env) {
        PhpDateValue value=object.dateValue();
        if(value==null) {
            String nativeName=object.dateImmutable()?"DateTimeImmutable":"DateTime",actual=object.getReflection().getName();
            if(actual.equals(nativeName))env.exception(env.trace(),PhpDateObjectError.class,"The %s object has not been correctly initialized by its constructor",nativeName);
            else env.exception(env.trace(),PhpDateObjectError.class,"Object of type %s (inheriting %s) has not been correctly initialized by calling parent::__construct() in its constructor",actual,nativeName);
        }
        return value;
    }
    static ClassEntity type(Environment env,String name){return env.fetchClass(name,true,PhpLanguageProfile.PHP_8_5);}
    static Memory construct(PhpDateCarrier object,Environment env,Memory[] args) {
        PhpDateZone zone=args.length>1&&!args[1].isNull()?args[1].toObject(PhpDateTimeZone.class).value(env):PhpDateEnvironment.zone(env);
        byte[] text=args.length==0?"now".getBytes(StandardCharsets.US_ASCII):args[0].getBinaryBytes(StandardCharsets.UTF_8);
        object.dateValue(parse(env,text,zone,PhpDateValue.now(zone)));return Memory.NULL;
    }
    static PhpDateValue parse(Environment env,byte[] text,PhpDateZone zone,PhpDateValue base) {
        try{var parsed=PhpDateParser.parse(text,zone,base);PhpDateEnvironment.parsed(env,parsed.warnings());return parsed.value();}
        catch(PhpDateParser.Failure failure) {
            PhpDateEnvironment.failed(env,failure.position,failure.getMessage());
            if(failure.unsupported)env.exception(env.trace(),PhpDateMalformedStringException.class,"Unsupported date grammar in initial provider: %s",failure.getMessage());
            else {
                String input=new String(text,StandardCharsets.ISO_8859_1);String bad=failure.position>=0&&failure.position<input.length()?input.substring(failure.position,failure.position+1):"";
                env.exception(env.trace(),PhpDateMalformedStringException.class,"Failed to parse time string (%s) at position %s (%s): %s",input,failure.position,bad,failure.getMessage());
            }
        }catch(RuntimeException failure) {
            PhpDateEnvironment.failed(env,0,"Failed to parse date/time");env.exception(env.trace(),PhpDateMalformedStringException.class,"Failed to parse date/time: %s",failure.getMessage());
        }
        throw new IllegalStateException("PHP date parse error unexpectedly returned");
    }
    static Memory change(PhpDateCarrier object,Environment env,java.util.function.UnaryOperator<PhpDateValue> operation) {
        if(!object.dateImmutable()){PhpDateValue next=operation.apply(value(object,env));object.dateValue(next);return ObjectMemory.valueOf(object);}
        value(object,env); // Native source must be initialized before a user clone callback is allowed.
        try {
            PhpDateCarrier copy=object.getReflection().cloneObject(object,env,env.trace());
            PhpDateValue next=operation.apply(value(copy,env));copy.dateValue(next);return ObjectMemory.valueOf(copy);
        }catch(Throwable failure){return PhpDateApi.<RuntimeException,Memory>raise(failure);}
    }
    static Memory format(PhpDateCarrier object,Environment env,Memory format){return new PhpByteStringMemory(PhpDateFormat.format(value(object,env),format.getBinaryBytes(StandardCharsets.UTF_8)));}
    static Memory timezone(PhpDateCarrier object,Environment env){return ObjectMemory.valueOf(PhpDateTimeZone.create(env,value(object,env).zone()));}
    static Memory modify(PhpDateCarrier object,Environment env,Memory text) {
        byte[] bytes=text.getBinaryBytes(StandardCharsets.UTF_8);
        return change(object,env,before->parse(env,bytes,before.zone(),before));
    }
    static Memory explicit(Environment env,ClassEntity called,Memory[] args) {
        PhpDateZone zone=args.length>2&&!args[2].isNull()?args[2].toObject(PhpDateTimeZone.class).value(env):PhpDateEnvironment.zone(env);
        byte[] input=args[1].getBinaryBytes(StandardCharsets.UTF_8);
        for(byte b:input)if(b==0)env.exception(env.trace(),php.runtime.lang.exception.BaseValueError.class,"%s::createFromFormat(): Argument #2 ($datetime) must not contain any null bytes",called.getName());
        try {
            var parsed=PhpDateParser.explicit(args[0].getBinaryBytes(StandardCharsets.UTF_8),input,zone,PhpDateValue.now(zone));
            PhpDateEnvironment.parsed(env,parsed.warnings());PhpDateCarrier result=called.newObjectWithoutConstruct(env);result.dateValue(parsed.value());return ObjectMemory.valueOf(result);
        }catch(PhpDateParser.Failure failure){PhpDateEnvironment.failed(env,failure);return Memory.FALSE;}
        catch(RuntimeException failure){PhpDateEnvironment.failed(env,0,"Invalid date/time data");return Memory.FALSE;}
    }
    static Memory interval(PhpDateCarrier object,Environment env,Memory argument,boolean subtract) {
        PhpDateInterval interval=argument.toObject(PhpDateInterval.class);
        try{return change(object,env,before->before.interval(interval.value(),subtract));}
        catch(DateTimeException|ArithmeticException|IllegalArgumentException failure){env.exception(env.trace(),PhpDateRangeError.class,"Date interval arithmetic is out of range");return Memory.NULL;}
    }
    static Memory diff(PhpDateCarrier object,Environment env,Memory argument,boolean absolute) {
        if(!(argument.toValue() instanceof ObjectMemory raw)||!(raw.value instanceof PhpDateCarrier target))
            env.exception(env.trace(),php.runtime.lang.exception.BaseTypeError.class,"DateTimeInterface target lacks an actual native date value");
        PhpDateValue a=value(object,env),b=value((PhpDateCarrier)((ObjectMemory)argument.toValue()).value,env);
        var fields=difference(a,b,absolute);
        var result=new PhpDateInterval(env,type(env,"DateInterval"));result.store(fields);return ObjectMemory.valueOf(result);
    }
    /** Directional calendar borrowing follows the public timelib relative-normalization contract.
     * Month-end intervals are deliberately asymmetric; java.time Period cannot represent this contract. */
    private static PhpDateInterval.Value difference(PhpDateValue a,PhpDateValue b,boolean absolute) {
        boolean regional=a.zone().kind()==3&&b.zone().kind()==3&&a.zone().name().equals(b.zone().name());
        boolean invert=regional?b.local().isBefore(a.local()):b.instant().isBefore(a.instant());
        PhpDateValue one=invert?b:a,two=invert?a:b;LocalDateTime first=one.local(),last=two.local();
        long[] v={last.getYear()-(long)first.getYear(),last.getMonthValue()-first.getMonthValue(),last.getDayOfMonth()-first.getDayOfMonth(),
                last.getHour()-first.getHour(),last.getMinute()-first.getMinute(),last.getSecond()-first.getSecond(),(last.getNano()-first.getNano())/1000};
        long elapsed=two.instant().getEpochSecond()-one.instant().getEpochSecond(),correction=two.offset()-(long)one.offset();
        if(!regional)v[5]-=correction;
        else if(elapsed<0){long flipped=Math.abs(v[4]*60+v[5]-correction);v[3]=flipped/3600;v[4]=flipped%3600/60;v[5]=flipped%60;invert=!invert;}
        carry(v,6,5,1000000);carry(v,5,4,60);carry(v,4,3,60);carry(v,3,2,24);carry(v,1,0,12);
        YearMonth borrow=YearMonth.from(invert?first:last);
        while(v[2]<0){if(!invert)borrow=borrow.minusMonths(1);v[2]+=borrow.lengthOfMonth();v[1]--;if(invert)borrow=borrow.plusMonths(1);}
        carry(v,1,0,12);
        if(regional) {
            boolean firstDst=one.zone().daylight(one.instant()),lastDst=two.zone().daylight(two.instant());
            var rules=two.zone().region().getRules();var transition=rules.previousTransition(two.instant().plusNanos(1));
            long transitionTime=transition==null?Long.MIN_VALUE:transition.getInstant().getEpochSecond();
            if(firstDst&&!lastDst&&elapsed+correction<86400){v[3]-=correction/3600;v[4]-=correction%3600/60;}
            else if(!firstDst&&lastDst&&transition!=null&&!(one.instant().getEpochSecond()+86400>transitionTime&&one.instant().getEpochSecond()+86400<=transitionTime+correction)
                    &&two.instant().getEpochSecond()>=transitionTime&&(elapsed+correction)%86400>two.instant().getEpochSecond()-transitionTime){v[3]-=correction/3600;v[4]-=correction%3600/60;}
            else if(firstDst==lastDst&&elapsed>=86400){
                Instant probe=two.instant().minusSeconds(two.offset());var nearby=rules.previousTransition(probe.plusNanos(1));
                if(nearby!=null){long edge=nearby.getInstant().getEpochSecond(),offset=one.offset()-(long)rules.getOffset(probe).getTotalSeconds();
                    if(two.instant().getEpochSecond()>=edge-offset&&two.instant().getEpochSecond()<edge){v[2]--;v[3]=24;}}
            }
        }
        boolean sameZone=regional||a.zone().kind()!=3&&b.zone().kind()!=3&&a.offset()==b.offset();long days;
        if(sameZone){PhpDateValue early=a.instant().isBefore(b.instant())?a:b,late=early==a?b:a;
            days=Math.abs(ChronoUnit.DAYS.between(a.local().toLocalDate(),b.local().toLocalDate()));
            if(late.local().toLocalTime().isBefore(early.local().toLocalTime())&&days>0)days--;
        }else days=Math.abs(a.instant().getEpochSecond()-b.instant().getEpochSecond())/86400;
        return new PhpDateInterval.Value(v[0],v[1],v[2],v[3],v[4],v[5],v[6],invert&&!absolute,days);
    }
    private static void carry(long[] fields,int from,int into,long radix){fields[into]+=Math.floorDiv(fields[from],radix);fields[from]=Math.floorMod(fields[from],radix);}

    private static ArrayMemory nativeFields(PhpDateCarrier object,Environment env) {
        PhpDateValue value=value(object,env);var data=new ArrayMemory();
        data.refOfIndex("date").assign(PhpDateFormat.text(value,"Y-m-d H:i:s.u"));data.refOfIndex("timezone_type").assign(value.zone().kind());data.refOfIndex("timezone").assign(value.zone().name());return data;
    }
    static ArrayMemory export(PhpDateCarrier object,Environment env) {
        var data=new ArrayMemory();var own=object.getProperties().foreachIterator(false,false);
        while(own.next())data.put(own.getKey(),own.getValue());
        if(object.dateValue()!=null){var nativeView=nativeFields(object,env).foreachIterator(false,false);
            while(nativeView.next())data.put(nativeView.getKey(),nativeView.getValue());}return data;
    }
    static ArrayMemory serialize(PhpDateCarrier object,Environment env) {
        var data=nativeFields(object,env);var props=object.getProperties().foreachIterator(false,false);
        while(props.next()){String key=props.getKey().toString();if(!key.equals("date")&&!key.equals("timezone_type")&&!key.equals("timezone"))data.put(props.getKey(),props.getValue());}return data;
    }
    static Memory restore(PhpDateCarrier object,Environment env,Memory input) {
        ArrayMemory data=input.toValue(ArrayMemory.class);Memory date=data.valueOfIndex("date"),kind=data.valueOfIndex("timezone_type"),name=data.valueOfIndex("timezone");
        if(!date.isString()||!kind.isNumber()||!name.isString()||kind.toLong()<1||kind.toLong()>3)
            env.exception(env.trace(),PhpDateObjectError.class,"Invalid serialization data for DateTime object");
        PhpDateValue next;
        try {
            PhpDateZone parsed=PhpDateZone.parse(name.toString());
            if(parsed.kind()!=kind.toLong())throw new IllegalArgumentException();
            next=PhpDateParser.parse(date.getBinaryBytes(StandardCharsets.UTF_8),parsed,PhpDateValue.now(parsed)).value();
        }catch(RuntimeException failure){env.exception(env.trace(),PhpDateObjectError.class,"Invalid serialization data for DateTime object");return Memory.NULL;}
        object.dateValue(next);
        var iterator=data.foreachIterator(false,false);while(iterator.next()) {
            String key=iterator.getKey().toString();if(!key.equals("date")&&!key.equals("timezone_type")&&!key.equals("timezone"))object.getProperties().put(iterator.getKey(),iterator.getValue());
        }
        return Memory.NULL;
    }
    @SuppressWarnings("unchecked") private static <T extends Throwable,R>R raise(Throwable failure)throws T{throw(T)failure;}
}
