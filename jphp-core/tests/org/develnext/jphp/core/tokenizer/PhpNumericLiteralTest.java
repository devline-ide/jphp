package org.develnext.jphp.core.tokenizer;

import org.develnext.jphp.core.tokenizer.token.Token;
import org.develnext.jphp.core.tokenizer.token.expr.value.IntegerExprToken;
import org.junit.Test;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.Context;
import php.runtime.exceptions.ParseException;

import static org.junit.Assert.*;

public class PhpNumericLiteralTest {
    @Test public void testEofAndOriginalMetadata() throws Exception {
        for (PhpLanguageProfile profile : PhpLanguageProfile.values()) {
            Tokenizer tokenizer = new Tokenizer(new Context("0"));
            tokenizer.setPhpLanguageProfile(profile);
            assertEquals(0, ((IntegerExprToken) tokenizer.nextToken()).getValue());
            assertNull(tokenizer.nextToken());
        }
        String source = "  1_000;\n 0O7_7;";
        Tokenizer tokenizer = new Tokenizer(new Context(source));
        tokenizer.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
        Token first = tokenizer.nextToken();
        assertEquals("1_000", first.getWord());
        assertEquals(2, first.getMeta().getStartIndex());
        assertEquals(7, first.getMeta().getEndIndex());
        assertEquals(2, first.getMeta().getStartPosition());
        assertEquals(7, first.getMeta().getEndPosition());
        assertEquals(";", tokenizer.nextToken().getWord());
        Token second = tokenizer.nextToken();
        assertEquals("0O7_7", second.getWord());
        assertEquals(1, second.getMeta().getStartLine());
        assertEquals(1, second.getMeta().getStartPosition());
        assertEquals(6, second.getMeta().getEndPosition());
        assertEquals(63, ((IntegerExprToken) second).getValue());
    }

    @Test public void testManySeparatorsDoNotUseJavaCallStack() throws Exception {
        for (String prefix : new String[] {"", "0x", "0b", "0o"}) {
            String literal = prefix + "0_".repeat(20000) + "1";
            Tokenizer tokenizer = new Tokenizer(new Context(literal));
            tokenizer.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
            assertEquals(1, ((IntegerExprToken) tokenizer.nextToken()).getValue());
            assertNull(tokenizer.nextToken());
            try {
                PhpNumericLiteral.parse(TokenMeta.of(literal + "_"), new Context(""));
                fail("Trailing separator accepted");
            } catch (ParseException expected) { assertNotNull(expected.getTraceInfo()); }
        }
    }
}
