package org.develnext.jphp.zend.ext.standard.date;
import php.runtime.Memory;
import php.runtime.env.Environment;
import php.runtime.lang.IObject;
import static php.runtime.annotation.Reflection.*;
import php.runtime.common.HintType;

@Name("DateTimeInterface")
@InternalInterface
public interface PhpDateTimeInterface extends IObject {
    String ATOM="Y-m-d\\TH:i:sP",COOKIE="l, d-M-Y H:i:s T",ISO8601="Y-m-d\\TH:i:sO",
            ISO8601_EXPANDED="X-m-d\\TH:i:sP",RFC822="D, d M y H:i:s O",RFC850="l, d-M-y H:i:s T",
            RFC1036="D, d M y H:i:s O",RFC1123="D, d M Y H:i:s O",RFC7231="D, d M Y H:i:s \\G\\M\\T",
            RFC2822="D, d M Y H:i:s O",RFC3339="Y-m-d\\TH:i:sP",RFC3339_EXTENDED="Y-m-d\\TH:i:s.vP",
            RSS="D, d M Y H:i:s O",W3C="Y-m-d\\TH:i:sP";
    @Signature(value=@Arg(value="format",type=HintType.STRING),result=@Arg(type=HintType.STRING))
    Memory format(Environment env,Memory...args);
    @Signature(result=@Arg(type=HintType.INT)) Memory getTimestamp(Environment env,Memory...args);
    @Signature(result=@Arg(type=HintType.INT)) Memory getMicrosecond(Environment env,Memory...args);
    @Signature(result=@Arg(type=HintType.INT)) Memory getOffset(Environment env,Memory...args);
    @Signature(returnType="DateTimeZone|false") Memory getTimezone(Environment env,Memory...args);
    @Signature(value={@Arg(value="targetObject",typeClass="DateTimeInterface"),@Arg(value="absolute",type=HintType.BOOLEAN,optional=@Optional("false"))},returnType="DateInterval")
    Memory diff(Environment env,Memory...args);
}
