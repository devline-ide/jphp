package org.develnext.jphp.core.tokenizer;

import org.develnext.jphp.core.common.TokenizeGrammarUtils;
import org.develnext.jphp.core.tokenizer.token.*;
import org.develnext.jphp.core.tokenizer.token.expr.ValueExprToken;
import org.develnext.jphp.core.tokenizer.token.expr.value.StringExprToken;
import org.develnext.jphp.core.tokenizer.token.stmt.EchoRawToken;
import php.runtime.common.Directive;
import php.runtime.common.Messages;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.Context;
import php.runtime.env.TraceInfo;
import php.runtime.exceptions.ParseException;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class Tokenizer {
    protected Context context;
    protected TokenFinder tokenFinder;

    private int currentPosition;
    private int startRelativePosition;
    private int relativePosition;
    private int currentLine;

    private final int codeLength;
    protected final String code;

    protected Token prevToken;

    protected boolean rawMode;
    private PhpLanguageProfile phpLanguageProfile = PhpLanguageProfile.JPHP_LEGACY;

    protected Map<String, Directive> directives = new HashMap<String, Directive>();

    private php.runtime.env.Environment compileEnvironment;
    private List<php.runtime.env.message.SystemMessage> compileWarnings;

    public void setCompileEnvironment(php.runtime.env.Environment environment) {
        compileEnvironment = environment;
    }

    public List<php.runtime.env.message.SystemMessage> getCompileWarnings() {
        return compileWarnings == null ? java.util.Collections.emptyList() : List.copyOf(compileWarnings);
    }

    private TraceInfo escapeTrace(int start, int end) {
        int lineStart = code.lastIndexOf('\n', start - 1) + 1;
        return new TraceInfo(context, currentLine, currentLine, start - lineStart, end - lineStart);
    }

    private void octalOverflow(int start, int end, String spelling) {
        TraceInfo trace = escapeTrace(start, end);
        String message = "Octal escape sequence overflow \\" + spelling + " is greater than \\377";
        var diagnostic = new php.runtime.env.message.CustomSystemMessage(
                php.runtime.exceptions.support.ErrorType.E_COMPILE_WARNING,
                new php.runtime.env.CallStackItem(trace), new Messages.Item(message));
        if (compileWarnings == null) compileWarnings = new ArrayList<>();
        compileWarnings.add(diagnostic);
        if (compileEnvironment != null) compileEnvironment.reportPhp85CompileWarning(diagnostic);
    }

    private static int asciiHex(char digit) {
        if (digit >= '0' && digit <= '9') return digit - '0';
        if (digit >= 'a' && digit <= 'f') return digit - 'a' + 10;
        if (digit >= 'A' && digit <= 'F') return digit - 'A' + 10;
        return -1;
    }

    private static java.util.BitSet markByte(java.util.BitSet positions, int offset, int value) {
        if (value >= 128) {
            if (positions == null) positions = new java.util.BitSet();
            positions.set(offset);
        }
        return positions;
    }


    public Tokenizer(Context context) throws IOException {
        this.context = context;
        this.code = context.getContent();
        this.codeLength = code.length();
        this.rawMode = context.isLikeFile();
        reset();
    }

    public Tokenizer(String code, Context context){
        this.context = context;
        this.currentPosition = -1;
        this.currentLine = 0;
        this.relativePosition = 0;
        this.code = code;
        this.codeLength = code.length();
        this.tokenFinder = new TokenFinder();
        this.rawMode = false;
    }

    public void reset(){
        this.currentPosition = -1;
        this.currentLine = 0;
        this.relativePosition = -1;
        this.tokenFinder = new TokenFinder();
        if (!rawMode)
            this.relativePosition = 0;

        this.directives.clear();
        compileWarnings = null;
    }

    public boolean hasDirective(String name){
        return directives.get(name) != null;
    }

    public Directive getDirective(String name){
        Directive value = directives.get(name);
        if (value == null)
            return null;
        else
            return value;
    }

    public String getCode() {
        return code;
    }

    public void setPhpLanguageProfile(PhpLanguageProfile profile) {
        phpLanguageProfile = profile;
    }

    protected final static Map<Class<?>, Constructor> tokenConstructors = new ConcurrentHashMap<Class<?>, Constructor>();

    private static final ClassValue<Constructor<?>> extensionTokenConstructors = new ClassValue<Constructor<?>>() {
        @Override
        protected Constructor<?> computeValue(Class<?> type) {
            try {
                return type.getConstructor(TokenMeta.class);
            } catch (NoSuchMethodException exception) {
                throw new TokenConstructorLookupFailure(exception);
            }
        }
    };

    private static final class TokenConstructorLookupFailure extends RuntimeException {
        private final NoSuchMethodException exception;

        private TokenConstructorLookupFailure(NoSuchMethodException exception) {
            this.exception = exception;
        }
    }

    private static Constructor<?> extensionTokenConstructor(Class<?> type) throws NoSuchMethodException {
        try {
            return extensionTokenConstructors.get(type);
        } catch (TokenConstructorLookupFailure failure) {
            throw failure.exception;
        }
    }


    @SuppressWarnings("unchecked")
    protected <T extends Token> T buildToken(Class<T> clazz, TokenMeta meta){
        if (clazz == org.develnext.jphp.core.tokenizer.token.expr.value.NameToken.class
                && phpLanguageProfile == PhpLanguageProfile.PHP_8_5 && meta.getWord().equalsIgnoreCase("match"))
            return (T)(prevToken = new org.develnext.jphp.core.tokenizer.token.expr.value.MatchExprToken(meta));
        Constructor<T> constructor = tokenConstructors.get(clazz);
        if (constructor == null){
            try {
                if (clazz.getClassLoader() == Tokenizer.class.getClassLoader()) {
                    tokenConstructors.put(clazz, constructor = clazz.getConstructor(TokenMeta.class));
                } else {
                    constructor = (Constructor<T>) extensionTokenConstructor(clazz);
                }
            } catch (NoSuchMethodException e) {
                throw new RuntimeException(e);
            }
        }

        try {
            return (T) (prevToken = constructor.newInstance(meta));
        } catch (InvocationTargetException e) {
            throw new RuntimeException("Unable build " + clazz.getSimpleName() + " token: " + e.getTargetException());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    protected TokenMeta buildMeta(int startPosition, int startLine){
        String word = getWord(startPosition, currentPosition);
        if (word == null)
            return null;

        TokenMeta meta = new TokenMeta(word, startLine, currentLine, startRelativePosition, relativePosition);
        int length = word.length();
        meta.setStartIndex(currentPosition - length);
        meta.setEndIndex(currentPosition);

        if (length == 1 && TokenizeGrammarUtils.isDelimiter(word.charAt(0))) {
            meta.setStartIndex(currentPosition);
            meta.setEndIndex(currentPosition + 1);
        }

        return meta;
    }

    protected String getWord(int startPosition, int endPosition){
        if (endPosition < startPosition)
            return null;

        if (endPosition == startPosition){
            if (startPosition >= codeLength)
                return null;
            return code.substring(startPosition, startPosition + 1);
        }

        if (endPosition > codeLength)
            endPosition = codeLength - 1;

        return code.substring(startPosition, endPosition);
    }

    protected Token tryNextToken(){
        int len = 0;
        char ch;
        if (currentPosition + 1 < codeLength){
            ch = code.charAt(currentPosition + 1);
            if (TokenizeGrammarUtils.isDelimiter(ch) && !TokenizeGrammarUtils.isSpace(ch)){
                len = 1;
                if (currentPosition + 2 < codeLength){
                    ch = code.charAt(currentPosition + 2);
                    if (TokenizeGrammarUtils.isDelimiter(ch) && !TokenizeGrammarUtils.isSpace(ch)){
                        len = 2;
                    }
                }
            }
        }

        if (len == 0)
            return null;

        String word = getWord(currentPosition, currentPosition + len + 1);
        Class<? extends Token> tokenClass = tokenFinder.find(word);
        if (len == 2 && tokenClass == null){
            len -= 1;
            word = getWord(currentPosition, currentPosition + len + 1);
            tokenClass = tokenFinder.find(word);
        }

        if (tokenClass != null){
            int startPosition = currentPosition;
            currentPosition += len + 1;
            Token token = buildToken(tokenClass, buildMeta(startPosition, currentLine));
            currentPosition -= 1;
            return token;
        }
        return null;
    }

    private void incCurrentPosition(int value){
        if (value < 0){
            while (currentPosition > 0 && value < 0){
                currentPosition--;
                value++;
                if (TokenizeGrammarUtils.isNewline(code.charAt(currentPosition)))
                    currentLine--;
            }
        }
    }

    protected boolean checkNewLine(char ch, boolean invert){
        if (TokenizeGrammarUtils.isNewline(ch)){
            if (invert) {
                currentLine--;
            } else {
                currentLine++;
            }

            relativePosition = 0;
            startRelativePosition = 0;
            return true;
        }
        
        return false;
    }

    protected boolean checkNewLine(char ch){
        return checkNewLine(ch, false);
    }

    /** Set the original origin of a freshly created interpolation lexer without rewriting its source. */
    public void setSourceOrigin(int line, int position) {
        if (currentPosition != -1 || line < 0 || position < 0) throw new IllegalArgumentException("Interpolation lexer has already advanced");
        currentLine = line;
        relativePosition = position;
        startRelativePosition = position;
    }

    private int findDocTerminator(int start, String label) {
        for (int line = start; line < codeLength;) {
            int token = line;
            while (token < codeLength && (code.charAt(token) == ' ' || code.charAt(token) == '\t')) token++;
            if (isDocTerminator(token, label)) return token;
            int newline = code.indexOf('\n', token);
            if (newline < 0) break;
            line = newline + 1;
        }
        return -1;
    }

    private void validateDocIndent(int body, int closing, int indentation, int bodyLine) {
        int closingLine = code.lastIndexOf('\n', closing - 1) + 1;
        char type = code.charAt(closingLine);
        for (int at = closingLine; at < closing; at++)
            if (code.charAt(at) != type) throw new ParseException("Invalid indentation - tabs and spaces cannot be mixed",
                    new TraceInfo(context, bodyLine, bodyLine, 0, indentation));
        int sourceLine = bodyLine;
        for (int line = body; line < closingLine; sourceLine++) {
            int at = line, removed = 0;
            while (at < closingLine && removed < indentation && (code.charAt(at) == ' ' || code.charAt(at) == '\t')) {
                if (code.charAt(at) != type) throw new ParseException("Invalid indentation - tabs and spaces cannot be mixed",
                        new TraceInfo(context, sourceLine, sourceLine, at - line, at - line + 1));
                at++; removed++;
            }
            boolean blank = at >= codeLength || code.charAt(at) == '\n' || code.charAt(at) == '\r' && at + 1 < codeLength && code.charAt(at + 1) == '\n';
            if (removed < indentation && !blank) throw new ParseException("Invalid body indentation level (expecting an indentation level of at least " + indentation + ")",
                    new TraceInfo(context, sourceLine, sourceLine, 0, removed));
            int newline = code.indexOf('\n', at);
            if (newline < 0) break;
            line = newline + 1;
        }
    }

    private StringExprToken.Segment stringSegment(int from, int to, boolean variable, int sourceIndex, int sourceLine, boolean doc) {
        return doc ? new StringExprToken.SourceSegment(from, to, variable, sourceLine, sourceIndex - code.lastIndexOf('\n', sourceIndex - 1) - 1)
                : new StringExprToken.Segment(from, to, variable);
    }

    private boolean isDocTerminator(int start, String label) {
        if (!code.startsWith(label, start)) return false;
        int end = start + label.length();
        if (phpLanguageProfile == PhpLanguageProfile.PHP_8_5)
            return end == codeLength || !TokenizeGrammarUtils.isNameChar(code.charAt(end));
        if (end < codeLength && code.charAt(end) == ';') end++;
        return end == codeLength || code.charAt(end) == '\n'
                || code.charAt(end) == '\r' && end + 1 < codeLength && code.charAt(end + 1) == '\n';
    }

    protected ValueExprToken readString(StringExprToken.Quote quote, int startPosition, int startLine){
        int i = currentPosition + 1, pos = relativePosition + 1;
        StringExprToken.Quote ch_quote = null;
        boolean slash = false;
        StringBuilder sb = new StringBuilder();
        boolean php85 = phpLanguageProfile == PhpLanguageProfile.PHP_8_5;
        java.util.BitSet rawBytes = null;

        boolean isMagic = quote != null && quote.isMagic();
        String endString = null;
        int docTerminator = -1, docIndentation = 0, docBody = -1;

        int startIndex = currentPosition + 1;

        if (quote == StringExprToken.Quote.DOC){
            StringBuilder tmp = new StringBuilder();
            StringExprToken.Quote docType = null;

            for(; i < codeLength; i++){
                char ch = code.charAt(i);
                pos++;

                if (docType == null && TokenizeGrammarUtils.isQuote(ch) != null) {
                    docType = TokenizeGrammarUtils.isQuote(ch);
                } else if (docType != null && docType == TokenizeGrammarUtils.isQuote(ch)) {
                    int newline = i + 1;
                    if (newline + 1 < codeLength && code.charAt(newline) == '\r' && code.charAt(newline + 1) == '\n') newline++;
                    if (newline >= codeLength || !TokenizeGrammarUtils.isNewline(code.charAt(newline))) {
                        throw new ParseException(
                                Messages.ERR_PARSE_UNEXPECTED_END_OF_STRING.fetch(),
                                new TraceInfo(context, currentLine, currentLine, pos + 1, pos + 1)
                        );
                    }
                    i = newline;
                    checkNewLine(code.charAt(i));
                    pos = 0;
                    break;
                } else if (tmp.length() == 0 && (ch == ' ' || ch == '\t')) {
                    //nop
                } else if (TokenizeGrammarUtils.isEngLetter(ch) || ch == '_' || (tmp.length() != 0 && Character.isDigit(ch))){
                    tmp.append(ch);
                } else if (tmp.length() > 0 && (TokenizeGrammarUtils.isNewline(ch)
                        || ch == '\r' && i + 1 < codeLength && code.charAt(i + 1) == '\n')) {
                    if (ch == '\r') i++;
                    checkNewLine(code.charAt(i));
                    pos = 0;
                    break;
                } else {
                    String error = Messages.ERR_PARSE_UNEXPECTED_X.fetch(ch);
                    if (TokenizeGrammarUtils.isNewline(ch))
                        error = Messages.ERR_PARSE_UNEXPECTED_END_OF_STRING.fetch();

                    throw new ParseException(
                            error,
                            new TraceInfo(context, currentLine, currentLine, pos, pos)
                    );
                }
            }

            currentPosition = i;
            i += 1; // skip \n

            isMagic = (docType == null || docType.isMagic());
            endString = tmp.toString();
            if (php85) {
                docBody = i;
                docTerminator = findDocTerminator(i, endString);
                if (docTerminator >= 0) {
                    docIndentation = docTerminator - code.lastIndexOf('\n', docTerminator - 1) - 1;
                    if (docIndentation > 0) validateDocIndent(docBody, docTerminator, docIndentation, currentLine);
                }
            }
        }

        List<StringExprToken.Segment> segments = new ArrayList<StringExprToken.Segment>();


        for(; i < codeLength; i++){
            if (php85 && docIndentation > 0 && (i == docBody || i > 0 && code.charAt(i - 1) == '\n')) {
                int removed = 0;
                while (removed < docIndentation && i < codeLength && (code.charAt(i) == ' ' || code.charAt(i) == '\t')) { i++; removed++; }
                pos += removed;
            }
            char ch = code.charAt(i);

            if (endString != null && (php85 ? i == docTerminator : i == currentPosition + 1 && isDocTerminator(i, endString))) {
                currentPosition = i + endString.length() - 1;
                relativePosition = docIndentation + endString.length();
                ch_quote = StringExprToken.Quote.DOC;
                break;
            }
            pos++;
            ch_quote = TokenizeGrammarUtils.isQuote(ch);
            if (endString == null && (ch_quote == quote && !slash)){
                currentPosition  = i;
                relativePosition = pos;
                break;
            }

            if (checkNewLine(ch)) {
                pos = 0;
                if (endString != null){
                    if (php85 ? i + 1 + docIndentation == docTerminator : isDocTerminator(i + 1, endString)) {
                        if (i > 0 && code.charAt(i - 1) == '\r') sb.setLength(sb.length() - 1);
                        currentPosition = i + docIndentation + endString.length();
                        relativePosition = docIndentation + endString.length();
                        ch_quote = StringExprToken.Quote.DOC;
                        break;
                    }
                }
            }

            if (!isMagic){
                switch (ch) {
                    case '\\':
                        if (!slash || endString != null)
                            sb.append(ch);
                        slash = !slash;
                        break;
                    case '\'':
                        if (endString == null)
                            sb.deleteCharAt(sb.length() - 1); // remove slash
                    default:
                        sb.append(ch);
                        slash = false;
                }
            } else {
                int dynamic = 0;
                if (!slash){
                    if (ch == '$' && (i + 1 < codeLength && code.charAt(i + 1) == '{') ) {
                        dynamic = 2;
                    }
                    if (ch == '{' && (i + 1 < codeLength && code.charAt(i + 1) == '$') )
                        dynamic = 1;
                }

                if (dynamic > 0) {
                    if (dynamic == 2 ||  i + 1 < codeLength && code.charAt(i + 1) == '$') {
                        slash = false;
                        int segmentLine = currentLine;
                        int opened = dynamic == 2 ? 0 : 1;
                        int j;
                        for(j = i + 1; j < codeLength; j++){
                            switch (code.charAt(j)){
                                case '{': opened++; break;
                                case '}': opened--; break;
                            }
                            checkNewLine(code.charAt(j));

                            if (opened == 0)
                                break;
                        }

                        if (opened != 0)
                            throw new ParseException(
                                    Messages.ERR_PARSE_UNEXPECTED_END_OF_STRING.fetch(),
                                    new TraceInfo(context, startLine, 0, relativePosition, 0)
                            );

                        String sub = code.substring(i, j + 1);
                        segments.add(stringSegment(sb.length(), sb.length() + sub.length(), dynamic == 2, i, segmentLine, php85 && endString != null));
                        /*segments.add(new StringExprToken.Segment(
                                i - currentPosition - 1, j - currentPosition, dynamic == 2
                        ));*/
                        sb.append(sub);
                        i = j;
                        continue;
                    }
                }

                if (slash && php85) {
                    if (ch >= '0' && ch <= '7') {
                        int end = i, decoded = 0;
                        while (end < codeLength && end < i + 3 && code.charAt(end) >= '0' && code.charAt(end) <= '7')
                            decoded = (decoded << 3) | (code.charAt(end++) - '0');
                        if (decoded > 255) octalOverflow(i - 1, end, code.substring(i, end));
                        int value = decoded & 255;
                        rawBytes = markByte(rawBytes, sb.length(), value);
                        sb.append((char) value);
                        pos += end - i - 1; i = end - 1; slash = false;
                        continue;
                    }
                    if (ch == 'x' || ch == 'X') {
                        int end = i + 1, decoded = 0;
                        while (end < codeLength && end < i + 3 && asciiHex(code.charAt(end)) >= 0)
                            decoded = (decoded << 4) | asciiHex(code.charAt(end++));
                        if (end == i + 1) sb.append('\\').append(ch);
                        else {
                            rawBytes = markByte(rawBytes, sb.length(), decoded);
                            sb.append((char) decoded);
                        }
                        pos += end - i - 1; i = end - 1; slash = false;
                        continue;
                    }
                    if (ch == 'u' && i + 1 < codeLength && code.charAt(i + 1) == '{') {
                        int end = i + 2, decoded = 0; boolean tooLarge = false;
                        while (end < codeLength && asciiHex(code.charAt(end)) >= 0) {
                            if (decoded > (0x10FFFF - asciiHex(code.charAt(end))) / 16) tooLarge = true;
                            if (!tooLarge) decoded = decoded * 16 + asciiHex(code.charAt(end));
                            end++;
                        }
                        if (end == i + 2 || end >= codeLength || code.charAt(end) != '}')
                            throw new ParseException("Invalid UTF-8 codepoint escape sequence", escapeTrace(i - 1, end));
                        if (tooLarge) throw new ParseException("Invalid UTF-8 codepoint escape sequence: Codepoint too large", escapeTrace(i - 1, end + 1));
                        if (decoded >= 0xD800 && decoded <= 0xDFFF) {
                            int first = 0xE0 | (decoded >>> 12), second = 0x80 | ((decoded >>> 6) & 63), third = 0x80 | (decoded & 63);
                            rawBytes = markByte(rawBytes, sb.length(), first); sb.append((char) first);
                            rawBytes = markByte(rawBytes, sb.length(), second); sb.append((char) second);
                            rawBytes = markByte(rawBytes, sb.length(), third); sb.append((char) third);
                        } else sb.appendCodePoint(decoded);
                        pos += end - i; i = end; slash = false;
                        continue;
                    }
                    boolean recognized = ch == 'r' || ch == 'n' || ch == 't' || ch == 'e' || ch == 'v' || ch == 'f'
                            || ch == '\\' || ch == '$' || ch == '"' && quote == StringExprToken.Quote.DOUBLE;
                    if (!recognized) {
                        if (ch != '`' || quote != StringExprToken.Quote.SHELL) sb.append('\\');
                        sb.append(ch); slash = false;
                        continue;
                    }
                }

                if (slash){
                    switch (ch){
                        case 'r': sb.append('\r'); slash = false; break;
                        case 'n': sb.append('\n'); slash = false; break;
                        case 't': sb.append('\t'); slash = false; break;
                        case 'e': sb.append((char)0x1B); slash = false; break;
                        case 'v': sb.append((char)0x0B); slash = false; break;
                        case 'f': sb.append('\f'); slash = false; break;
                        case '\\':
                            sb.append(ch);
                            slash = !slash;
                            break;
                        case '0':case '1':case '2':case '3':case '4':case '5':case '6':case '7':
                            // \[0-7]{1,3}
                            int k = i + 1;
                            for(int j = 1; j < 3; j++){
                                k = i + j;
                                if (k < codeLength){
                                    char digit = code.charAt(k);
                                    if (digit >= '0' && digit <= '7'){
                                       // nop
                                    } else
                                        break;
                                } else
                                    break;
                            }

                            String s = code.substring(i, k);
                            if (s.isEmpty()){
                                sb.append(ch);
                            } else {
                                int val = Integer.parseInt(s, 8);
                                sb.append((char)val);
                            }

                            i = k - 1;
                            slash = false;
                            break;
                        case 'x':
                            int t = i + 1;
                            for(int j = 1; j < 5; j++){
                                t = i + j;
                                if (t < codeLength){
                                    char digit = code.charAt(t);
                                    if (Character.isDigit(digit) || (digit >= 'A' && digit <= 'F') || (digit >= 'a' && digit <= 'f')){
                                        // nop;
                                    } else {
                                        break;
                                    }
                                } else
                                    break;
                            }

                            String s16 = code.substring(i + 1, t);
                            if (s16.isEmpty()){
                                sb.append(ch);
                            } else  {
                                int val16 = Integer.parseInt(s16, 16);
                                sb.append((char)val16);
                            }
                            i = t - 1;
                            slash = false;
                            break;
                        case '$':
                        case '"':
                        default:
                            slash = false;
                            sb.append(ch); break;
                    }
                } else {
                    switch (ch){
                        case '\\':
                            slash = true;
                        break;
                        case '$':
                            int k = i + 1;
                            boolean done = false;
                            int opened = 0;
                            int complex = 0;
                            if (k < codeLength) {
                                char first = code.charAt(k);
                                if (TokenizeGrammarUtils.isEngLetter(first) || first == '_'){
                                    k++;
                                    done = true;
                                    for(; i < codeLength; k++){
                                        if (k < codeLength){
                                            first = code.charAt(k);
                                            if (Character.isDigit(first) || TokenizeGrammarUtils.isEngLetter(first) || first == '_') {
                                                // nop
                                            } else if (complex == 1 && TokenizeGrammarUtils.isVariableChar(first) && code.charAt(k - 1) == '[') {
                                                // nop
                                            } else if (complex == 0 && first == '[') {
                                                opened++;
                                                complex = 1;
                                            } else if (complex == 1 && opened != 0 && first == ']') {
                                                opened--;
                                                if (opened <= 0) {
                                                    k++;
                                                    break;
                                                }
                                            } else if (complex == 0 && first == '-'){
                                                if (k + 1 < codeLength && code.charAt(k + 1) == '>'){
                                                    k++;
                                                    complex = 2;
                                                } else
                                                    break;
                                            } else
                                                break;
                                        } else
                                            break;
                                    }
                                }
                            }

                            if (done){
                                if (opened != 0)
                                    throw new ParseException(
                                            Messages.ERR_PARSE_UNEXPECTED_END_OF_STRING.fetch(),
                                            new TraceInfo(context, startLine, 0, pos, 0)
                                    );

                                String s = code.substring(i, k);
                                segments.add(stringSegment(sb.length(), sb.length() + s.length(), true, i, currentLine, php85 && endString != null));
                                sb.append(s);
                            } else
                                sb.append(ch);

                            i = k - 1;
                            break;
                        default:
                            sb.append(ch);
                    }
                }
            }
        }

        if (ch_quote != quote || slash){
            throw new ParseException(
                    Messages.ERR_PARSE_UNEXPECTED_END_OF_STRING.fetch(),
                    new TraceInfo(context, currentLine, currentLine, pos, pos)
            );
        }

        TokenMeta meta = buildMeta(startPosition + 1, startLine);
        meta.setStartIndex(php85 && quote == StringExprToken.Quote.DOC ? startPosition : startIndex - 1);

        if (quote == StringExprToken.Quote.DOC) {
            meta.setEndIndex(currentPosition + (php85 ? 1 : 3));
        } else {
            meta.setEndIndex(currentPosition + 1);
        }

        meta.setWord(sb.toString());

        StringExprToken expr = new StringExprToken(meta, quote);
        expr.setSegments(segments);
        if (rawBytes != null) expr.setRawBytePositions(rawBytes);
        return expr;
    }

    protected Token readComment(CommentToken.Kind kind, int startPosition, int startLine){
        int i, pos = relativePosition, k = 0;

        boolean isOldComment = code.charAt(currentPosition) == '#';

        for(i = currentPosition + 1; i < codeLength; i++, k++){
            char ch = code.charAt(i);
            pos++;

            if (checkNewLine(ch))
                pos = 0;

            char prev_ch = i > 0 ? code.charAt(i - 1) : '\0';

            boolean closed = false;
            switch (kind){
                case SIMPLE:
                    closed = (TokenizeGrammarUtils.isNewline(ch));
                    if (TokenizeGrammarUtils.isCloseTag(prev_ch, ch)) {
                        i -= 2;
                        closed = true;
                    }

                    break;
                case DOCTYPE:
                case BLOCK:
                    closed = k != 0 && (TokenizeGrammarUtils.isCloseComment(String.valueOf(new char[]{prev_ch, ch}))); break;
            }

            closed = closed || i == codeLength - 1;
            if (closed){
                String text = code.substring(
                        startPosition,
                        kind == CommentToken.Kind.SIMPLE ? i : i - 1
                );

                TokenMeta meta = new TokenMeta(
                        text,
                        startLine, currentLine, startRelativePosition, relativePosition
                );

                meta.setStartIndex(currentPosition - 1);

                if (isOldComment || kind == CommentToken.Kind.DOCTYPE) {
                    meta.setStartIndex(currentPosition);
                }

                if (kind == CommentToken.Kind.BLOCK || kind == CommentToken.Kind.DOCTYPE) {
                    meta.setEndIndex(i + 1);
                } else {
                    meta.setEndIndex(i);
                }

                currentPosition = i;
                relativePosition = pos;
                Token result = buildToken(CommentToken.class, meta);

                if (kind == CommentToken.Kind.SIMPLE && text.startsWith("//")){
                    String directive = text.substring(2).trim();
                    if (directive.startsWith("@@")){
                        int p = directive.indexOf(' ');
                        if (p != -1){
                            String name = directive.substring(2, p);
                            String value = p + 1 < directive.length() ? directive.substring(p + 1) : "";
                            if (!directives.containsKey(name.toLowerCase()))
                                directives.put(name.toLowerCase(), new Directive(value, result.toTraceInfo(context)));
                        }
                    }
                }

                return result;
            }
        }
        assert false;
        return null;
    }

    protected Token readNumber(int startPosition, int startLine){
        if (phpLanguageProfile == PhpLanguageProfile.PHP_8_5) return readPhpNumber(startPosition, startLine);
        int i;
        boolean dot = false;
        boolean e_char = false;

        i = currentPosition;
        boolean isHex = code.charAt(i) == '0'
                && (i + 1 < codeLength && Character.toLowerCase(code.charAt(i + 1)) == 'x');
        boolean isBinary = code.charAt(i) == '0' && (i + 1 < codeLength && code.charAt(i + 1) == 'b');
        if (isHex || isBinary)
            i += 2;

        for(; i < codeLength; i++){
            char ch = code.charAt(i);

            if (!isHex && TokenizeGrammarUtils.isFloatDot(ch)){
                if (dot)
                    break;
                dot = true;
            } else if (!isHex && (ch == 'e' || ch == 'E')){
                if (e_char)
                    break;

                if (i + 1 >= codeLength){
                    break;
                } else {
                    if (code.charAt(i + 1) == '-' || code.charAt(i + 1) == '+' ||
                            (i + 2 >= codeLength || Character.isDigit(code.charAt(i + 2)))) {
                        if (i + 2 >= codeLength || !Character.isDigit(code.charAt(i + 2))) {
                            break;
                        } else {
                            i++;
                        }
                    }
                }

                e_char = true;
            } else if (isHex && ((ch >= 'A' && ch <= 'F') || (ch >= 'a' && ch <= 'f'))) {
                // nop
            } else if (isBinary && (ch == '0' || ch == '1')) {
                // nop
            } else if (!Character.isDigit(ch))
                break;
        }

        currentPosition = i;
        TokenMeta meta = buildMeta(startPosition, startLine);
        Class<? extends Token> tokenClazz = tokenFinder.find(meta);

        currentPosition -= 1;
        return buildToken(tokenClazz, meta);
    }

    private Token readPhpNumber(int startPosition, int startLine) {
        int start = currentPosition, i = start;
        char prefix = i + 1 < codeLength && code.charAt(i) == '0' ? Character.toLowerCase(code.charAt(i + 1)) : 0;
        boolean based = prefix == 'x' || prefix == 'b' || prefix == 'o';
        if (based) i += 2;
        boolean dot = false, exponent = false;
        for (; i < codeLength; i++) {
            char ch = code.charAt(i);
            if (ch >= '0' && ch <= '9' || ch == '_' || prefix == 'x' && (ch >= 'a' && ch <= 'f' || ch >= 'A' && ch <= 'F')) continue;
            if (!based && !dot && !exponent && ch == '.') { dot = true; continue; }
            if (!based && !exponent && (ch == 'e' || ch == 'E')) {
                int digit = i + 1;
                if (digit < codeLength && (code.charAt(digit) == '+' || code.charAt(digit) == '-')) digit++;
                if (digit < codeLength && code.charAt(digit) >= '0' && code.charAt(digit) <= '9') {
                    exponent = true; i = digit - 1; continue;
                }
            }
            break;
        }
        relativePosition += i - start - 1;
        currentPosition = i;
        TokenMeta meta = buildMeta(startPosition, startLine);
        currentPosition--;
        return prevToken = PhpNumericLiteral.parse(meta, context);
    }

    public Token nextToken(){
        boolean init = false;
        boolean splitVariable = false;
        char ch = '\0';
        char prev_ch = '\0';
        int startPosition = currentPosition + 1;
        startRelativePosition = relativePosition;
        int startLine = currentLine;

        StringExprToken.Quote string = null;
        CommentToken.Kind comment = null;

        if (codeLength == 0) {
            return null;
        }

        boolean first = true;
        while (currentPosition < codeLength){
            currentPosition++;
            relativePosition++;

            if (currentPosition == codeLength) {
                break;
            }

            ch = code.charAt(currentPosition);
            
            if (currentPosition > 0 && init) {
                prev_ch = code.charAt(currentPosition - 1);
            }

            checkNewLine(ch);

            if (rawMode){
                if (TokenizeGrammarUtils.isOpenTag(prev_ch, ch)){
                    TokenMeta meta = new TokenMeta(
                            code.substring(startPosition, currentPosition - 1), startLine, currentLine,
                            startRelativePosition, relativePosition
                    );
                    rawMode = false;
                    startLine = currentLine;
                    startRelativePosition = relativePosition;
                    EchoRawToken token = buildToken(EchoRawToken.class, meta);

                    if (codeLength >= currentPosition + 4 &&
                            code.substring(currentPosition + 1, currentPosition + 4).equals("php")){
                        relativePosition += 4;
                        currentPosition += 3;
                        token.setShort(false);
                    } else {
                        token.setShort(true);
                    }
                    return token;
                } else {
                    init = true;
                    first = true;
                    continue;
                }
            }

            if (ch == '=' && prevToken != null && prevToken instanceof EchoRawToken && ((EchoRawToken) prevToken).isShort()){
                return buildToken(OpenEchoTagToken.class, buildMeta(startPosition, startLine));
            }

            if (first && (!init || prevToken == null)){
                // numbers: integers, doubles, hex
                if (Character.isDigit(ch)
                        || (ch == '.' && (prevToken == null || phpLanguageProfile == PhpLanguageProfile.PHP_8_5)
                                && currentPosition + 1 < codeLength
                                && Character.isDigit(code.charAt(currentPosition + 1)))){
                    return readNumber(startPosition, startLine);
                }

                // comments
                if (ch == '#' && currentPosition + 1 < codeLength && code.charAt(currentPosition + 1) == '['
                        && phpLanguageProfile == PhpLanguageProfile.PHP_8_5) {
                    currentPosition++;
                    relativePosition++;
                    return new org.develnext.jphp.core.tokenizer.token.stmt.AttributeStartToken(
                            new TokenMeta("#[", startLine, currentLine, startRelativePosition, relativePosition));
                }
                comment = CommentToken.Kind.isComment(ch, prev_ch);
                if (comment != null) {
                    return readComment(comment, startPosition, startLine);
                }

                // strings, herdoc, etc.
                string = TokenizeGrammarUtils.isQuote(ch);
                if (string != null) {
                    return readString(string, startPosition, startLine);
                }
            }
            
            init = true;
            first = false;

            if (TokenizeGrammarUtils.isDelimiter(ch)) {
                if (startPosition == currentPosition && TokenizeGrammarUtils.isSpace(ch)){
                    startPosition = currentPosition + 1;
                    startLine = currentLine;
                    startRelativePosition = relativePosition;
                    prevToken = null;
                    first = true;
                    continue;
                }

                if (startPosition == currentPosition){
                    Token token = tryNextToken();
                    if (token instanceof BreakToken){
                        rawMode = true;
                    }

                    if (token instanceof CommentToken){
                        comment = ((CommentToken)token).getKind();
                        return readComment(comment, startPosition, startLine);
                        //continue;
                    }

                    if (token instanceof StringStartDocToken){
                        string = StringExprToken.Quote.DOC;
                        return readString(string, startPosition, startLine);
                    }

                    if (token != null) {
                        return token;
                    }
                }

                break;
            } else if (TokenizeGrammarUtils.isVariableChar(ch)){
                if (phpLanguageProfile == PhpLanguageProfile.PHP_8_5 && currentPosition > startPosition
                        && !TokenizeGrammarUtils.isVariableChar(prev_ch)) {
                    splitVariable = true;
                    break;
                }
                if (TokenizeGrammarUtils.isVariableChar(prev_ch)){
                    currentPosition -= 1;
                    break;
                }
            }
        }

        TokenMeta meta = buildMeta(startPosition, startLine);
        
        if (currentPosition != startPosition && (TokenizeGrammarUtils.isDelimiter(ch) || splitVariable)) {
            checkNewLine(ch, true);
            currentPosition -= 1;
            relativePosition -= 1;
        }

        if (meta == null)
            return null;

        //currentPosition -= 1;
        Class<? extends Token> tokenClazz = rawMode ? EchoRawToken.class : tokenFinder.find(meta);
        if (tokenClazz == null){
            return prevToken = new Token(meta, TokenType.T_J_CUSTOM);
        } else {
            return buildToken(tokenClazz, meta);
        }
    }

    public List<Token> fetchAll(){
        List<Token> result = new ArrayList<Token>();
        Token token;
        while ((token = nextToken()) != null)
            result.add(token);

        return result;
    }

    public Context getContext() {
        return context;
    }
}
