package standard;

import org.develnext.jphp.zend.ext.standard.StringFunctions;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class RawurldecodeTest {
    @Test public void literalPlusAndTildeArePreserved() throws Exception {
        assertEquals("", StringFunctions.rawurldecode(""));
        assertEquals("+ ~*+", StringFunctions.rawurldecode("+%20~%2A%2b"));
        assertEquals("тест/+", StringFunctions.rawurldecode("%D1%82%D0%B5%D1%81%D1%82%2F+"));
        assertEquals(" ", StringFunctions.urldecode("+"));
    }

    @Test public void percentEscapesAreNotDecodedTwice() throws Exception {
        assertEquals("%20|%2A|%2B|%7E", StringFunctions.rawurldecode("%2520|%252A|%252B|%257E"));
        assertEquals("a\0b", StringFunctions.rawurldecode("a%00b"));
    }

    @Test public void phpDirectAndCallbackCallsSourceAndPhb() throws Throwable {
        StandardFunctionTestSupport.assertResult(
                "return rawurldecode('+~%2520%252A%2B') . '|' . call_user_func('rawurldecode', '+~%2520%252A%2B');",
                "+~%20%2A+|+~%20%2A+");
    }
}
