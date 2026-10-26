package standard;

import org.develnext.jphp.zend.ext.standard.StringFunctions;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class MalformedUrlEscapeTest {
    @Test public void incompleteAndNonHexEscapesRemainLiteral() throws Exception {
        for (String value : new String[]{"%", "%2", "%GG", "%0x", "a%4z/", "%ＦＦ", "%٢١"}) {
            assertEquals(value, StringFunctions.urldecode(value));
            assertEquals(value, StringFunctions.rawurldecode(value));
        }
        assertEquals("%A|%2A|%GG", StringFunctions.urldecode("%%41|%2%41|%25GG"));
        assertEquals("%A|%2A|%GG", StringFunctions.rawurldecode("%%41|%2%41|%25GG"));
    }

    @Test public void validEscapesUnicodeAndPlusKeepTheirOwnContracts() throws Exception {
        String input = "%D1%82%D0%B5%D1%81%D1%82%2F%GG+%2B%25%";
        assertEquals("тест/%GG +%%", StringFunctions.urldecode(input));
        assertEquals("тест/%GG++%%", StringFunctions.rawurldecode(input));
        assertEquals("% 1", StringFunctions.urldecode("%+1"));
        assertEquals("%+1", StringFunctions.rawurldecode("%+1"));
    }

    @Test public void phpCallsAndCallbacksAcceptMalformedEscapesSourceAndPhb() throws Throwable {
        StandardFunctionTestSupport.assertResult(
                "$input = '%%41+%2B%GG%'; return urldecode($input) . '|' . rawurldecode($input)"
                        + " . '|' . call_user_func('urldecode', $input) . '|' . call_user_func('rawurldecode', $input);",
                "%A +%GG%|%A++%GG%|%A +%GG%|%A++%GG%");
    }
}
