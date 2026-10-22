package org.develnext.jphp.zend.ext.standard.date;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.*;

/** One immutable native value per date; no PHP references or process-wide mutable state. */
record PhpDateValue(Instant instant,PhpDateZone zone) {
    PhpDateValue {instant=instant.truncatedTo(java.time.temporal.ChronoUnit.MICROS);}
    static PhpDateValue now(PhpDateZone zone){return new PhpDateValue(Instant.now(),zone);}
    static PhpDateValue epoch(String decimal) {
        if(!decimal.matches("[+-]?\\d+(?:[.]\\d{1,6})?"))throw new IllegalArgumentException("Unsupported Unix timestamp syntax");
        BigDecimal value=new BigDecimal(decimal);long seconds=value.setScale(0,RoundingMode.FLOOR).longValueExact();
        int micros=value.subtract(BigDecimal.valueOf(seconds)).movePointRight(6).setScale(0,RoundingMode.DOWN).intValueExact();
        return new PhpDateValue(Instant.ofEpochSecond(seconds,micros*1000L),PhpDateZone.offset(0));
    }
    LocalDateTime local(){return zone.local(instant);}
    int offset(){return zone.offsetAt(instant);}
    PhpDateValue withZone(PhpDateZone next){return new PhpDateValue(instant,next);}
    PhpDateValue withLocal(LocalDateTime next){return new PhpDateValue(zone.resolve(next,offset()),zone);}
    static LocalDate normalizedDate(long year,long month,long day) {
        long normalizedYear=Math.addExact(year,Math.floorDiv(Math.subtractExact(month,1),12));
        int normalizedMonth=(int)Math.floorMod(Math.subtractExact(month,1),12)+1;
        return LocalDate.of(Math.toIntExact(normalizedYear),normalizedMonth,1).plusDays(Math.subtractExact(day,1));
    }
    PhpDateValue date(long year,long month,long day){return withLocal(LocalDateTime.of(normalizedDate(year,month,day),local().toLocalTime()));}
    PhpDateValue time(long hour,long minute,long second,long micro) {
        if(micro<0||micro>999999)throw new IllegalArgumentException("Microseconds must be between 0 and 999999");
        LocalDateTime next=local().toLocalDate().atStartOfDay().plusHours(hour).plusMinutes(minute).plusSeconds(second).plusNanos(micro*1000);
        return withLocal(next);
    }
    PhpDateValue interval(PhpDateInterval.Value interval,boolean subtract) {
        long sign=(subtract?-1:1)*(interval.invert()?-1:1);LocalDateTime old=local();
        LocalDate date=normalizedDate(Math.addExact(old.getYear(),Math.multiplyExact(sign,interval.years())),
                Math.addExact(old.getMonthValue(),Math.multiplyExact(sign,interval.months())),old.getDayOfMonth());
        date=date.plusDays(Math.multiplyExact(sign,interval.days()));
        Instant civil=zone.resolve(LocalDateTime.of(date,old.toLocalTime()),offset());
        long seconds=Math.addExact(Math.addExact(Math.multiplyExact(interval.hours(),3600),Math.multiplyExact(interval.minutes(),60)),interval.seconds());
        return new PhpDateValue(civil.plusSeconds(Math.multiplyExact(sign,seconds)).plusNanos(Math.multiplyExact(sign,Math.multiplyExact(interval.micros(),1000))),zone);
    }
}
