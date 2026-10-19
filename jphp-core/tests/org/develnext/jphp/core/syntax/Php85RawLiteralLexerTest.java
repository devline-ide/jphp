package org.develnext.jphp.core.syntax;

import org.develnext.jphp.core.tokenizer.Tokenizer;
import org.develnext.jphp.core.tokenizer.token.Token;
import org.develnext.jphp.core.tokenizer.token.expr.value.StringExprToken;
import org.develnext.jphp.core.tokenizer.token.expr.value.StringBuilderExprToken;
import org.develnext.jphp.core.tokenizer.token.stmt.ExprStmtToken;
import org.junit.Test;
import php.runtime.Memory;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.Context;
import php.runtime.env.Environment;
import php.runtime.env.handler.ErrorHandler;
import php.runtime.env.handler.ErrorReportHandler;
import php.runtime.env.message.SystemMessage;
import php.runtime.exceptions.ParseException;
import php.runtime.exceptions.support.ErrorException;
import php.runtime.exceptions.support.ErrorType;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.Assert.*;

/** Lexer projection controls only; runtime byte carriers and PHB emission are separate root-owned work. */
public class Php85RawLiteralLexerTest {
    private Tokenizer tokenizer(String source, PhpLanguageProfile profile) throws Exception {
        Context context = new Context(source);context.setPhpLanguageProfile(profile);
        Tokenizer tokenizer = new Tokenizer(context);tokenizer.setPhpLanguageProfile(profile);return tokenizer;
    }
    private StringExprToken first(Tokenizer tokenizer) {
        Token token;while ((token=tokenizer.nextToken())!=null) if (token instanceof StringExprToken string) return string;
        throw new AssertionError("Missing string token");
    }
    @Test public void nativePinnedLiteralProjections() throws Exception {
        int cases=0;
        try(var input=getClass().getClassLoader().getResourceAsStream("resources/strings/php85_raw_literal_lexer.tsv")) {
            assertNotNull(input);
            for(String row:new String(input.readAllBytes(),StandardCharsets.UTF_8).split("\\R")) {
                if(row.isEmpty())continue;String[] fields=row.split("\t");
                String source=new String(Base64.getDecoder().decode(fields[1]),StandardCharsets.UTF_8);
                var lexer=tokenizer(source,PhpLanguageProfile.PHP_8_5);var token=first(lexer);
                assertEquals(fields[0],fields[2],HexFormat.of().formatHex(token.getPhp85Bytes()));
                assertEquals(fields[0],Boolean.parseBoolean(fields[3]),token.hasRawBytes());
                assertEquals(fields[0],Integer.parseInt(fields[4]),lexer.getCompileWarnings().size());cases++;
            }
        }
        assertEquals(26,cases);
    }
    @Test public void ordinaryTokensHaveNoOriginDescriptorAndRawAccessDoesNotLeak() throws Exception {
        var field=StringExprToken.class.getDeclaredField("rawBytePositions");field.setAccessible(true);
        for(String source:List.of("\"ordinary\"","\"éЖ🙂\"","\"\\x4142\"","\"\\1234\"","'\\xff'")) {
            var token=first(tokenizer(source,PhpLanguageProfile.PHP_8_5));assertFalse(token.hasRawBytes());assertNull(field.get(token));
        }
        var token=first(tokenizer("\"é\\xff🙂\"",PhpLanguageProfile.PHP_8_5));
        byte[] bytes=token.getPhp85Bytes();Arrays.fill(bytes,(byte)0);
        assertEquals("c3a9fff09f9982",HexFormat.of().formatHex(token.getPhp85Bytes()));
        var positions=new BitSet();positions.set(0);
        var explicit=new StringExprToken(org.develnext.jphp.core.tokenizer.TokenMeta.of("\u00ff"),StringExprToken.Quote.DOUBLE);
        explicit.setRawBytePositions(positions);positions.clear();assertEquals("ff",HexFormat.of().formatHex(explicit.getPhp85Bytes()));
    }
    @Test public void slicesKeepByteOriginsSegmentsAndEnclosingSourceCoordinates() throws Exception {
        String source="\n\n\"é\\xff{$x}\\377🙂\"";
        var token=first(tokenizer(source,PhpLanguageProfile.PHP_8_5));assertEquals(1,token.getSegments().size());
        var segment=token.getSegments().getFirst();assertEquals(2,segment.from);assertEquals(6,segment.to);
        var before=token.slice(0,segment.from);var after=token.slice(segment.to,token.getValue().length());
        assertEquals("c3a9ff",HexFormat.of().formatHex(before.getPhp85Bytes()));
        assertEquals("fff09f9982",HexFormat.of().formatHex(after.getPhp85Bytes()));
        assertTrue(after.hasRawBytes());assertEquals(2,token.getMeta().getStartLine());
        for(var part:List.of(before,after)) {
            assertEquals(token.getMeta().getStartLine(),part.getMeta().getStartLine());
            assertEquals(token.getMeta().getStartPosition(),part.getMeta().getStartPosition());
            assertEquals(token.getMeta().getStartIndex(),part.getMeta().getStartIndex());
            assertEquals(token.getMeta().getEndIndex(),part.getMeta().getEndIndex());
        }
        var whole=token.slice(0,token.getValue().length());assertEquals(segment.from,whole.getSegments().getFirst().from);
        assertThrows(IllegalArgumentException.class,()->token.slice(segment.from+1,segment.to));
        var text=after.slice(1,after.getValue().length());assertFalse(text.hasRawBytes());assertEquals("f09f9982",HexFormat.of().formatHex(text.getPhp85Bytes()));
    }
    @Test public void analyzerUsesOriginPreservingLiteralSlices() throws Exception {
        var lexer=tokenizer("$v=\"é\\xff{$x}\\377🙂\";",PhpLanguageProfile.PHP_8_5);
        var analyzer=new SyntaxAnalyzer(null,lexer);
        var expression=(ExprStmtToken)analyzer.getTree().getFirst();
        var builder=(StringBuilderExprToken)expression.getTokens().getLast();
        var before=(StringExprToken)builder.getExpression().getFirst();var after=(StringExprToken)builder.getExpression().getLast();
        assertEquals("c3a9ff",HexFormat.of().formatHex(before.getPhp85Bytes()));
        assertEquals("fff09f9982",HexFormat.of().formatHex(after.getPhp85Bytes()));
    }
    @Test public void overflowWarningsAreLexicalNonfatalAndBypassPhpErrorHandler() throws Exception {
        var env=new Environment();List<SystemMessage> reports=new ArrayList<>();
        env.setErrorReportHandler(new ErrorReportHandler() {
            public boolean onError(SystemMessage message){reports.add(message);return true;}
            public boolean onFatal(ErrorException failure){throw new AssertionError("Compile warning became fatal",failure);}
        });
        env.setErrorHandler(new ErrorHandler(null,Memory.NULL,128) {
            @Override public boolean onError(Environment environment,SystemMessage message){throw new AssertionError("Lexical warning reached PHP handler");}
        });
        String source="\nif(false){$v=\"\\777\";}";
        var lexer=tokenizer(source,PhpLanguageProfile.PHP_8_5);new SyntaxAnalyzer(env,lexer);
        assertEquals(1,reports.size());assertEquals(1,lexer.getCompileWarnings().size());
        var warning=reports.getFirst();assertSame(warning,env.getLastMessage());
        assertEquals(ErrorType.E_COMPILE_WARNING,warning.getType());
        assertEquals("Octal escape sequence overflow \\777 is greater than \\377",warning.getMessage());
        assertEquals(1,warning.getTrace().trace.getStartLine());
        assertEquals(source.indexOf('\\')-source.lastIndexOf('\n',source.indexOf('\\'))-1,warning.getTrace().trace.getStartPosition());
        assertThrows(UnsupportedOperationException.class,()->lexer.getCompileWarnings().clear());
        var invalid=tokenizer("\"\\777\\u{}\"",PhpLanguageProfile.PHP_8_5);invalid.setCompileEnvironment(env);
        ParseException failure=assertThrows(ParseException.class,()->first(invalid));
        assertEquals("Invalid UTF-8 codepoint escape sequence",failure.getMessage());assertEquals(2,reports.size());
        assertSame(reports.getLast(),env.getLastMessage());
    }
    @Test public void legacyProjectionAndNonMagicQuotesRemainHistorical() throws Exception {
        var lexer=tokenizer("\"\\x4142\\377\\Xff\\u{D800}\\q\"",PhpLanguageProfile.JPHP_LEGACY);
        var token=first(lexer);assertFalse(token.hasRawBytes());assertEquals("\u04142\u001f7Xffu{D800}q",token.getValue());
        assertTrue(lexer.getCompileWarnings().isEmpty());
        assertEquals("5c7866665c333737",HexFormat.of().formatHex(first(tokenizer("'\\xff\\377'",PhpLanguageProfile.PHP_8_5)).getPhp85Bytes()));
        assertEquals("c3a95c786666d0965c333737f09f9982",HexFormat.of().formatHex(first(tokenizer("<<<'END'\né\\xffЖ\\377🙂\nEND;",PhpLanguageProfile.PHP_8_5)).getPhp85Bytes()));
    }
    @Test public void simpleInterpolationAndCrLfWarningsKeepOrigins() throws Exception {
        var token=first(tokenizer("\"\\xff$x\\377\"",PhpLanguageProfile.PHP_8_5));
        var segment=token.getSegments().getFirst();assertEquals(1,segment.from);assertEquals(3,segment.to);
        assertEquals("ff",HexFormat.of().formatHex(token.slice(0,segment.from).getPhp85Bytes()));
        assertEquals("ff",HexFormat.of().formatHex(token.slice(segment.to,token.getValue().length()).getPhp85Bytes()));
        String source="<<<END\r\nabc\r\n\\777\r\nEND;";
        var lexer=tokenizer(source,PhpLanguageProfile.PHP_8_5);var heredoc=first(lexer);
        assertEquals("6162630d0aff",HexFormat.of().formatHex(heredoc.getPhp85Bytes()));
        var trace=lexer.getCompileWarnings().getFirst().getTrace().trace;
        assertEquals(2,trace.getStartLine());assertEquals(0,trace.getStartPosition());assertEquals(4,trace.getEndPosition());
        lexer.reset();assertTrue(lexer.getCompileWarnings().isEmpty());
    }
    @Test public void malformedUnicodeEscapeKeepsNativeLexicalDiagnostic() throws Exception {
        for(String source:List.of("\"\\u{}\"","\"\\u{xyz}\"")) {
            var failure=assertThrows(ParseException.class,()->first(tokenizer(source,PhpLanguageProfile.PHP_8_5)));
            assertEquals("Invalid UTF-8 codepoint escape sequence",failure.getMessage());
        }
        var failure=assertThrows(ParseException.class,()->first(tokenizer("\"\\u{110000}\"",PhpLanguageProfile.PHP_8_5)));
        assertEquals("Invalid UTF-8 codepoint escape sequence: Codepoint too large",failure.getMessage());
    }
}
