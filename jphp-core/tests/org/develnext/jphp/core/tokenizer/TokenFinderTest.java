package org.develnext.jphp.core.tokenizer;

import org.develnext.jphp.core.tokenizer.token.Token;
import org.develnext.jphp.core.tokenizer.token.expr.value.IntegerExprToken;
import org.develnext.jphp.core.tokenizer.token.expr.value.NameToken;
import org.develnext.jphp.core.tokenizer.token.expr.value.VariableExprToken;
import org.develnext.jphp.core.tokenizer.token.stmt.IfStmtToken;
import org.junit.Test;
import php.runtime.env.Context;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.Assert.*;

public class TokenFinderTest {
    @Test
    public void testCaseClassificationDoesNotDependOnLocale() {
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            TokenFinder finder = new TokenFinder();
            for (int i = 0; i < 2; i++) {
                assertEquals(IfStmtToken.class, finder.find("IF"));
                assertEquals(IfStmtToken.class, finder.find("if"));
                assertEquals(VariableExprToken.class, finder.find("$MixedName"));
                assertEquals(NameToken.class, finder.find("MixedName"));
                assertEquals(IntegerExprToken.class, finder.find("0XFF"));
            }
        } finally {
            Locale.setDefault(previous);
        }
    }

    @Test
    public void testConcurrentTokenConstructionPreservesMetadata() throws Exception {
        String source = "IF ($MixedName === 0XFF) { RETURN $MixedName + 1; }";
        ExecutorService workers = Executors.newFixedThreadPool(8);
        try {
            List<Callable<String>> tasks = new ArrayList<>();
            for (int i = 0; i < 64; i++) {
                tasks.add(() -> {
                    Tokenizer tokenizer = new Tokenizer(new Context(source));
                    StringBuilder result = new StringBuilder();
                    Token token;
                    while ((token = tokenizer.nextToken()) != null) {
                        result.append(token.getClass().getName()).append(':')
                                .append(token.getWord()).append(':')
                                .append(token.getMeta().getStartPosition()).append(':')
                                .append(token.getMeta().getEndPosition()).append(';');
                    }
                    return result.toString();
                });
            }
            String expected = null;
            for (Future<String> future : workers.invokeAll(tasks)) {
                String result = future.get();
                if (expected == null) expected = result;
                assertEquals(expected, result);
            }
            assertTrue(expected.contains("$MixedName"));
        } finally {
            workers.shutdownNow();
        }
    }
}
