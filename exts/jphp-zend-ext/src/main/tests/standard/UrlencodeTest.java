package standard;

import org.develnext.jphp.zend.ext.standard.StringFunctions;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class UrlencodeTest {
    @Test public void reservedCharactersAndSpaces() throws Exception {
        assertEquals("", StringFunctions.urlencode(""));
        assertEquals("%2A+%2B%25%7E%2F%3F%26%3D", StringFunctions.urlencode("* +%~/?&="));
        assertEquals("AZaz09-_.", StringFunctions.urlencode("AZaz09-_."));
        assertEquals("%D1%82%D0%B5%D1%81%D1%82", StringFunctions.urlencode("тест"));
    }

    @Test public void phpDirectAndCallbackCallsSourceAndPhb() throws Throwable {
        StandardFunctionTestSupport.assertResult(
                "return urlencode('* +%~') . '|' . call_user_func('urlencode', '* +%~');",
                "%2A+%2B%25%7E|%2A+%2B%25%7E");
    }
}
