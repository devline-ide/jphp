package org.develnext.jphp.zend.ext.standard.date;

import java.time.*;
import java.time.zone.ZoneOffsetTransition;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** PHP timezone identity is distinct from the offset used to resolve an instant. */
record PhpDateZone(int kind,String name,ZoneId region,int fixedSeconds) {
    private static final Pattern OFFSET=Pattern.compile("([+-])(\\d{1,2})(?::?(\\d{2}))?(?::?(\\d{2}))?");
    // Deliberately finite initial abbreviation grammar; this is not the full timelib abbreviation database.
    private static final Map<String,Integer> ABBREVIATIONS=Map.of("GMT",0,"CET",3600,"CEST",7200,
            "EST",-18000,"EDT",-14400,"PST",-28800,"PDT",-25200);
    static PhpDateZone utc(){return new PhpDateZone(3,"UTC",ZoneId.of("UTC"),0);}
    static PhpDateZone offset(int seconds){return new PhpDateZone(1,offsetText(seconds,true),null,seconds);}
    static PhpDateZone parse(String text) {
        if(text.equals("Z"))return offset(0);
        Matcher m=OFFSET.matcher(text);
        if(m.matches()) {
            int hours=Integer.parseInt(m.group(2)),minutes=m.group(3)==null?0:Integer.parseInt(m.group(3));
            int seconds=m.group(4)==null?0:Integer.parseInt(m.group(4));
            if(minutes>59||seconds>59)throw new IllegalArgumentException("Invalid numeric timezone offset");
            return offset((m.group(1).equals("-")?-1:1)*(hours*3600+minutes*60+seconds));
        }
        Integer abbreviation=ABBREVIATIONS.get(text.toUpperCase(java.util.Locale.ROOT));
        if(abbreviation!=null)return new PhpDateZone(2,text.toUpperCase(java.util.Locale.ROOT),null,abbreviation);
        ZoneId id=ZoneId.of(text);return new PhpDateZone(3,text,id,0);
    }
    int offsetAt(Instant instant){return region==null?fixedSeconds:region.getRules().getOffset(instant).getTotalSeconds();}
    LocalDateTime local(Instant instant){return LocalDateTime.ofInstant(instant.plusSeconds(offsetAt(instant)),ZoneOffset.UTC);}
    Instant resolve(LocalDateTime local,Integer preferredOffset) {
        int seconds=fixedSeconds;
        if(region!=null) {
            var rules=region.getRules();List<ZoneOffset> offsets=rules.getValidOffsets(local);
            if(offsets.isEmpty()) {
                ZoneOffsetTransition transition=rules.getTransition(local);
                local=local.plusSeconds(transition.getDuration().getSeconds());seconds=transition.getOffsetAfter().getTotalSeconds();
            }else {
                // Native constructor chooses the later overlap offset. Mutators may retain a still-valid prior offset.
                seconds=offsets.get(offsets.size()-1).getTotalSeconds();
                if(preferredOffset!=null)for(ZoneOffset offset:offsets)if(offset.getTotalSeconds()==preferredOffset)seconds=preferredOffset;
            }
        }
        return Instant.ofEpochSecond(Math.subtractExact(local.toEpochSecond(ZoneOffset.UTC),seconds),local.getNano());
    }
    String abbreviation(Instant instant) {
        if(kind==2)return name;
        if(kind==1)return "GMT"+offsetText(fixedSeconds,false,false);
        if(name.equals("UTC")||name.equals("Etc/UTC"))return "UTC";
        // java.time has English short zone text; exact PHP abbreviation coverage remains a native-tested subset.
        return java.time.format.DateTimeFormatter.ofPattern("z",java.util.Locale.US).format(instant.atZone(region));
    }
    boolean daylight(Instant instant){return kind==2&&(name.equals("CEST")||name.equals("EDT")||name.equals("PDT"))||region!=null&&!region.getRules().getDaylightSavings(instant).isZero();}
    static String offsetText(int seconds,boolean colon) {return offsetText(seconds,colon,true);}
    static String offsetText(int seconds,boolean colon,boolean includeSeconds) {
        long n=Math.abs((long)seconds);String result=(seconds<0?"-":"+")+two(n/3600)+(colon?":":"")+two(n/60%60);
        return !includeSeconds||n%60==0?result:result+(colon?":":"")+two(n%60);
    }
    private static String two(long n){return n<10?"0"+n:Long.toString(n);}
}
