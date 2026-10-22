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

import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.*;
import java.util.regex.*;

/** Finite documented grammar. Unsupported timelib forms are explicit debt, not a successful partial parse. */
final class PhpDateParser {
    record Diagnostic(int position,String message) {}
    record Parsed(PhpDateValue value,List<Diagnostic> warnings) {Parsed{warnings=List.copyOf(warnings);}}
    static final class Failure extends IllegalArgumentException {
        final int position;final boolean unsupported;final List<Diagnostic> errors,warnings;
        Failure(int position,String message,boolean unsupported){super(message);this.position=position;this.unsupported=unsupported;this.errors=List.of(new Diagnostic(position,message));this.warnings=List.of();}
        Failure(List<Diagnostic> errors,List<Diagnostic>warnings){super(errors.get(0).message());this.position=errors.get(0).position();this.unsupported=false;this.errors=List.copyOf(errors);this.warnings=List.copyOf(warnings);}
    }
    private static final Pattern ISO=Pattern.compile("([+-]?\\d{4,9})-(\\d{1,2})-(\\d{1,2})(?:[Tt ](\\d{1,2}):(\\d{2})(?::(\\d{2})(?:[.](\\d{1,9}))?)?)?(?:\\s*(Z|[+-]\\d{2}:?\\d{2}(?::?\\d{2})?|[A-Za-z][A-Za-z0-9_+./-]*))?");
    private static final Pattern UNIT=Pattern.compile("([+-]?\\d+)\\s*(years?|months?|weeks?|days?|hours?|minutes?|seconds?|microseconds?)",Pattern.CASE_INSENSITIVE);
    private static final Pattern WEEKDAY=Pattern.compile("(next|last|previous) (monday|tuesday|wednesday|thursday|friday|saturday|sunday)",Pattern.CASE_INSENSITIVE);
    private static final Pattern MONTH_EDGE=Pattern.compile("(first|last) day of (this|next|last|previous) month",Pattern.CASE_INSENSITIVE);
    static Parsed parse(byte[] bytes,PhpDateZone zone,PhpDateValue base) {
        String input=new String(bytes,StandardCharsets.ISO_8859_1),s=input.trim();
        if(s.isEmpty()||s.equalsIgnoreCase("now"))return new Parsed(new PhpDateValue(base.instant(),zone),List.of());
        if(s.charAt(0)=='@') {
            try{return new Parsed(PhpDateValue.epoch(s.substring(1)),List.of());}
            catch(RuntimeException bad){throw new Failure(1,"Invalid Unix timestamp",false);}
        }
        Matcher match=ISO.matcher(s);
        if(match.matches()) {
            long year=Long.parseLong(match.group(1)),month=Long.parseLong(match.group(2)),day=Long.parseLong(match.group(3));
            int hour=group(match,4),minute=group(match,5),second=group(match,6),nano=fraction(match.group(7));
            if(month>12||day>31||hour>24||minute>59||second>60)throw new Failure(firstBad(match,month,day,hour,minute,second),"Unexpected character",false);
            PhpDateZone selected=match.group(8)==null?zone:PhpDateZone.parse(match.group(8));
            LocalDate normalized=PhpDateValue.normalizedDate(year,month,day);
            boolean warning=month==0||day==0||normalized.getMonthValue()!=month||normalized.getDayOfMonth()!=day;
            LocalDateTime local=normalized.atStartOfDay().plusHours(hour).plusMinutes(minute).plusSeconds(second).plusNanos(nano);
            return new Parsed(new PhpDateValue(selected.resolve(local,null),selected),warning?List.of(new Diagnostic(input.length()+1,"The parsed date was invalid")):List.of());
        }
        try {
            var rfc=java.time.ZonedDateTime.parse(s,DateTimeFormatter.RFC_1123_DATE_TIME);
            var selected=PhpDateZone.offset(rfc.getOffset().getTotalSeconds());return new Parsed(new PhpDateValue(rfc.toInstant(),selected),List.of());
        }catch(java.time.format.DateTimeParseException ignored){}
        return new Parsed(relative(s,new PhpDateValue(base.instant(),zone)),List.of());
    }
    private static int group(Matcher m,int index){return m.group(index)==null?0:Integer.parseInt(m.group(index));}
    private static int fraction(String digits){return digits==null?0:Integer.parseInt((digits+"000000").substring(0,6))*1000;}
    private static int firstBad(Matcher m,long month,long day,int hour,int minute,int second){return month>12?m.start(2):day>31?m.start(3):hour>24?m.start(4):minute>59?m.start(5):m.start(6);}
    static PhpDateValue relative(String text,PhpDateValue base) {
        String s=text.trim().toLowerCase(Locale.ROOT);LocalDateTime local=base.local();
        switch(s) {
            case "today","midnight":return base.withLocal(local.toLocalDate().atStartOfDay());
            case "tomorrow":return base.withLocal(local.toLocalDate().plusDays(1).atStartOfDay());
            case "yesterday":return base.withLocal(local.toLocalDate().minusDays(1).atStartOfDay());
            case "noon":return base.withLocal(local.toLocalDate().atTime(12,0));
            case "now","":return base;
        }
        Matcher edge=MONTH_EDGE.matcher(s);
        if(edge.matches()) {
            long delta=edge.group(2).equals("next")?1:edge.group(2).equals("this")?0:-1;
            LocalDate date=local.toLocalDate().withDayOfMonth(1).plusMonths(delta);
            if(edge.group(1).equals("last"))date=date.withDayOfMonth(date.lengthOfMonth());
            return base.withLocal(LocalDateTime.of(date,local.toLocalTime()));
        }
        Matcher week=WEEKDAY.matcher(s);
        if(week.matches()) {
            DayOfWeek day=DayOfWeek.valueOf(week.group(2).toUpperCase(Locale.ROOT));
            LocalDate date=local.toLocalDate().with(week.group(1).equals("next")?TemporalAdjusters.next(day):TemporalAdjusters.previous(day));
            return base.withLocal(date.atStartOfDay());
        }
        boolean ago=s.endsWith(" ago");if(ago)s=s.substring(0,s.length()-4).trim();
        Matcher unit=UNIT.matcher(s);int at=0;boolean consumed=false;PhpDateValue result=base;
        while(at<s.length()) {
            while(at<s.length()&&Character.isWhitespace(s.charAt(at)))at++;
            if(at==s.length())break;
            unit.region(at,s.length());if(!unit.lookingAt())throw new Failure(at,"Unsupported timelib relative grammar in initial date provider",true);
            long amount=Long.parseLong(unit.group(1));if(ago)amount=Math.negateExact(amount);String name=unit.group(2);LocalDateTime d=result.local();
            if(name.startsWith("year"))result=result.date(Math.addExact(d.getYear(),amount),d.getMonthValue(),d.getDayOfMonth());
            else if(name.startsWith("month"))result=result.date(d.getYear(),Math.addExact(d.getMonthValue(),amount),d.getDayOfMonth());
            else if(name.startsWith("week"))result=result.withLocal(d.plusDays(Math.multiplyExact(amount,7)));
            else if(name.startsWith("day"))result=result.withLocal(d.plusDays(amount));
            else if(name.startsWith("hour"))result=result.withLocal(d.plusHours(amount));
            else if(name.startsWith("minute"))result=result.withLocal(d.plusMinutes(amount));
            else if(name.startsWith("second"))result=result.withLocal(d.plusSeconds(amount));
            else result=result.withLocal(d.plusNanos(Math.multiplyExact(amount,1000)));
            consumed=true;at=unit.end();
        }
        if(!consumed)throw new Failure(0,"Unsupported timelib grammar in initial date provider",true);return result;
    }
    static Parsed explicit(byte[] formatBytes,byte[] inputBytes,PhpDateZone zone,PhpDateValue base) {
        String format=new String(formatBytes,StandardCharsets.ISO_8859_1),input=new String(inputBytes,StandardCharsets.ISO_8859_1);
        if(input.indexOf('\0')>=0)throw new Failure(input.indexOf('\0'),"NUL byte in createFromFormat input",false);
        LocalDateTime initial=base.withZone(zone).local();long y=initial.getYear(),m=initial.getMonthValue(),d=initial.getDayOfMonth();
        long h=initial.getHour(),i=initial.getMinute(),second=initial.getSecond(),micro=0;
        Long epoch=null;boolean anyDate=false,anyTime=false,civilTime=false,fractionExplicit=false,trailing=false;Set<Character> fields=new HashSet<>();int at=0;PhpDateZone selected=zone;List<Diagnostic>warnings=new ArrayList<>(),errors=new ArrayList<>();
        for(int f=0;f<format.length();f++) {
            char token=format.charAt(f);
            if(token=='!'){y=1970;m=d=1;h=i=second=micro=0;continue;}
            if(token=='+'){trailing=true;continue;}
            if(token=='|'){if(!fields.contains('Y'))y=1970;if(!fields.contains('m'))m=1;if(!fields.contains('d'))d=1;if(!fields.contains('H'))h=0;if(!fields.contains('i'))i=0;if(!fields.contains('s'))second=0;if(!fields.contains('u'))micro=0;continue;}
            if(at>=input.length()){errors.add(new Diagnostic(at,"Not enough data available to satisfy format"));break;}
            if(token=='\\'&&f+1<format.length()){token=format.charAt(++f);if(at>=input.length()||input.charAt(at++)!=token)throw new Failure(at,"The format separator does not match",false);continue;}
            if("YymndjHGhgisuvU".indexOf(token)>=0) {
                int max=token=='Y'?4:token=='y'?2:token=='u'?6:token=='v'?3:token=='U'?19:2,start=at;
                boolean signed=token=='U';
                if(!isDigit(input.charAt(at))&&!(signed&&(input.charAt(at)=='+'||input.charAt(at)=='-')))errors.add(new Diagnostic(start,"Unexpected data found."));
                while(at<input.length()&&!isDigit(input.charAt(at))&&!(signed&&(input.charAt(at)=='+'||input.charAt(at)=='-')))at++;
                if(at<input.length()&&signed&&(input.charAt(at)=='+'||input.charAt(at)=='-'))at++;
                int digits=at;while(at<input.length()&&at-digits<max&&input.charAt(at)>='0'&&input.charAt(at)<='9')at++;
                if(at==digits){errors.add(new Diagnostic(start,numericMissing(token)));continue;}
                if((token=='i'||token=='s')&&at-digits!=2){errors.add(new Diagnostic(start,numericMissing(token)));continue;}
                long value=Long.parseLong(input.substring(signed&&digits>0&&(input.charAt(digits-1)=='+'||input.charAt(digits-1)=='-')?digits-1:digits,at));
                if("HGhgisuv".indexOf(token)>=0&&!anyTime){h=i=second=micro=0;anyTime=true;}
                if("HGhgis".indexOf(token)>=0)civilTime=true;
                if(token=='u'||token=='v')fractionExplicit=true;
                fields.add("Yy".indexOf(token)>=0?'Y':"mn".indexOf(token)>=0?'m':"dj".indexOf(token)>=0?'d':"HGhg".indexOf(token)>=0?'H':token=='v'?'u':token);
                switch(token) {
                    case 'Y':y=value;anyDate=true;break;case 'y':y=value<70?2000+value:1900+value;anyDate=true;break;
                    case 'm':case 'n':m=value;anyDate=true;break;case 'd':case 'j':d=value;anyDate=true;break;
                    case 'H':case 'G':case 'h':case 'g':h=value;anyTime=true;break;case 'i':i=value;anyTime=true;break;
                    case 's':second=value;anyTime=true;break;case 'u':micro=value*(long)Math.pow(10,6-(at-digits));anyTime=true;break;
                    case 'v':micro=value*1000;anyTime=true;break;
                    case 'U':epoch=value;break;
                }
                continue;
            }
            if(token=='a'||token=='A') {
                if(at+2>input.length())throw new Failure(at,"A meridian could not be found",false);
                String meridian=input.substring(at,at+2).toLowerCase(Locale.ROOT);if(!meridian.equals("am")&&!meridian.equals("pm"))throw new Failure(at,"A meridian could not be found",false);
                h=h%12+(meridian.equals("pm")?12:0);at+=2;continue;
            }
            if("OPpeT".indexOf(token)>=0) {
                int start=at;
                if(token=='O'||token=='P'||token=='p') {
                    if(at<input.length()&&input.charAt(at)=='Z')at++;
                    else {if(at<input.length()&&(input.charAt(at)=='+'||input.charAt(at)=='-'))at++;while(at<input.length()&&(Character.isDigit(input.charAt(at))||input.charAt(at)==':'))at++;}
                }else while(at<input.length()&&(Character.isLetterOrDigit(input.charAt(at))||"_+-/".indexOf(input.charAt(at))>=0))at++;
                if(start==at)throw new Failure(at,"The timezone could not be found",false);selected=PhpDateZone.parse(input.substring(start,at));continue;
            }
            if(Character.isLetter(token))throw new Failure(at,"Unsupported createFromFormat token in initial date provider: "+token,true);
            if(Character.isWhitespace(token)){while(at<input.length()&&Character.isWhitespace(input.charAt(at)))at++;continue;}
            if(input.charAt(at)!=token){errors.add(new Diagnostic(at,";:/.,-()".indexOf(token)>=0?"The separation symbol could not be found":"The format separator does not match"));if(";:/.,-()".indexOf(token)<0)at++;}else at++;
        }
        if(at<input.length()) {if(trailing)warnings.add(new Diagnostic(at,"Trailing data"));else errors.add(new Diagnostic(at,"Trailing data"));}
        if(!errors.isEmpty())throw new Failure(errors,warnings);
        if(epoch!=null) {
            if(anyDate||civilTime)throw new Failure(at,"Unsupported mixed Unix/civil createFromFormat fields",true);
            return new Parsed(new PhpDateValue(Instant.ofEpochSecond(epoch,fractionExplicit?micro*1000:0),PhpDateZone.offset(0)),warnings);
        }
        LocalDate normalized=PhpDateValue.normalizedDate(y,m,d);
        if(m<1||m>12||d<1||d>normalized.lengthOfMonth()||normalized.getMonthValue()!=m||normalized.getDayOfMonth()!=d)
            warnings.add(new Diagnostic(at,"The parsed date was invalid"));
        if(h>23||i>59||second>59)warnings.add(new Diagnostic(at,"The parsed time was invalid"));
        LocalDateTime local=normalized.atStartOfDay().plusHours(h).plusMinutes(i).plusSeconds(second).plusNanos(micro*1000);
        return new Parsed(new PhpDateValue(selected.resolve(local,null),selected),warnings);
    }
    private static boolean isDigit(char value){return value>='0'&&value<='9';}
    private static String numericMissing(char token){return switch(token){
        case 'Y'->"A four digit year could not be found";case 'y'->"A two digit year could not be found";
        case 'm','n'->"A two digit month could not be found";case 'd','j'->"A two digit day could not be found";
        case 'H','G','h','g'->"A two digit hour could not be found";case 'i'->"A two digit minute could not be found";
        case 's'->"A two digit second could not be found";case 'u'->"A six digit microsecond could not be found";
        case 'v'->"A three digit millisecond could not be found";default->"A signed number could not be found";};}

}
