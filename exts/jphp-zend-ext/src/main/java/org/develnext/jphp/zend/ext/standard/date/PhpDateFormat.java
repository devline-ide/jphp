package org.develnext.jphp.zend.ext.standard.date;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.temporal.IsoFields;

/** PHP tokens and escaping, not Java DateTimeFormatter pattern syntax. Literal PHP bytes are preserved. */
final class PhpDateFormat {
    private static final String[] DAYS={"Monday","Tuesday","Wednesday","Thursday","Friday","Saturday","Sunday"};
    private static final String[] MONTHS={"January","February","March","April","May","June","July","August","September","October","November","December"};
    static byte[] format(PhpDateValue value,byte[] pattern) {
        var out=new ByteArrayOutputStream(pattern.length+32);
        LocalDateTime local=value.local();
        for(int i=0;i<pattern.length;i++) {
            int token=pattern[i]&255;
            if(token=='\\'&&i+1<pattern.length){out.write(pattern[++i]);continue;}
            String text=token(value,local,(char)token);
            if(text==null)out.write(token);else out.writeBytes(text.getBytes(StandardCharsets.US_ASCII));
        }
        return out.toByteArray();
    }
    static String text(PhpDateValue value,String pattern){return new String(format(value,pattern.getBytes(StandardCharsets.US_ASCII)),StandardCharsets.US_ASCII);}
    private static String token(PhpDateValue value,LocalDateTime d,char token) {
        int day=d.getDayOfMonth(),week=d.getDayOfWeek().getValue(),year=d.getYear();
        return switch(token) {
            case 'd'->pad(day,2);case 'D'->DAYS[week-1].substring(0,3);case 'j'->""+day;case 'l'->DAYS[week-1];
            case 'N'->""+week;case 'S'->day>=11&&day<=13?"th":switch(day%10){case 1->"st";case 2->"nd";case 3->"rd";default->"th";};
            case 'w'->""+(week%7);case 'z'->""+(d.getDayOfYear()-1);case 'W'->pad(d.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR),2);
            case 'F'->MONTHS[d.getMonthValue()-1];case 'm'->pad(d.getMonthValue(),2);case 'M'->MONTHS[d.getMonthValue()-1].substring(0,3);
            case 'n'->""+d.getMonthValue();case 't'->""+d.toLocalDate().lengthOfMonth();case 'L'->Year.isLeap(year)?"1":"0";
            case 'o'->year(d.get(IsoFields.WEEK_BASED_YEAR),false);case 'Y'->year(year,false);case 'y'->pad(Math.abs(year%100),2);
            case 'X'->(year>=0?"+":"-")+pad(Math.abs((long)year),4);case 'x'->year(year,year>=10000);
            case 'a'->d.getHour()<12?"am":"pm";case 'A'->d.getHour()<12?"AM":"PM";
            case 'B'->pad(Math.floorMod(value.instant().getEpochSecond()+3600,86400)*1000/86400,3);
            case 'g'->""+(d.getHour()%12==0?12:d.getHour()%12);case 'h'->pad(d.getHour()%12==0?12:d.getHour()%12,2);
            case 'G'->""+d.getHour();case 'H'->pad(d.getHour(),2);case 'i'->pad(d.getMinute(),2);case 's'->pad(d.getSecond(),2);
            case 'u'->pad(d.getNano()/1000,6);case 'v'->pad(d.getNano()/1000000,3);
            case 'e'->value.zone().name();case 'I'->value.zone().daylight(value.instant())?"1":"0";
            case 'O'->PhpDateZone.offsetText(value.offset(),false,false);case 'P'->PhpDateZone.offsetText(value.offset(),true,false);
            case 'p'->value.offset()==0?"Z":PhpDateZone.offsetText(value.offset(),true,false);
            case 'T'->value.zone().abbreviation(value.instant());case 'Z'->""+value.offset();
            case 'c'->text(value,"Y-m-d\\TH:i:sP");case 'r'->text(value,"D, d M Y H:i:s O");case 'U'->""+value.instant().getEpochSecond();
            default->null;
        };
    }
    private static String year(int value,boolean positiveSign){return (value<0?"-":positiveSign?"+":"")+pad(Math.abs((long)value),4);}
    static String pad(long value,int width){String text=Long.toString(value);return "0".repeat(Math.max(0,width-text.length()))+text;}
}
