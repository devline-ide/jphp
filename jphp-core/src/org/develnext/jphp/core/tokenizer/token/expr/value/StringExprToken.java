package org.develnext.jphp.core.tokenizer.token.expr.value;

import org.develnext.jphp.core.tokenizer.TokenType;
import org.develnext.jphp.core.tokenizer.TokenMeta;
import org.develnext.jphp.core.tokenizer.token.expr.ValueExprToken;
import org.develnext.jphp.core.tokenizer.token.stmt.ExprStmtToken;

import java.util.ArrayList;
import java.util.List;

public class StringExprToken extends ValueExprToken {

    public enum Quote { SINGLE, DOUBLE, SHELL, DOC;

        public boolean isMagic() {
            return this == DOUBLE || this == SHELL;
        }
    }

    public static class Segment {
        public final int from;
        public final int to;
        public final boolean isVariable;

        public Segment(int from, int to, boolean isVariable) {
            this.from = from;
            this.to = to;
            this.isVariable = isVariable;
        }
    }

    /** Physical source origin only for modern DOC interpolations; ordinary Segment layout is unchanged. */
    public static final class SourceSegment extends Segment {
        public final int sourceLine;
        public final int sourcePosition;
        public SourceSegment(int from, int to, boolean variable, int line, int position) {
            super(from, to, variable);sourceLine = line;sourcePosition = position;
        }
    }

    protected final Quote quote;
    private String value;
    private java.util.BitSet rawBytePositions;

    protected List<Segment> segments = new ArrayList<Segment>();
    protected ExprStmtToken expression;

    protected boolean binary;

    public StringExprToken(TokenMeta meta, Quote quote) {
        super(meta, TokenType.T_CONSTANT_ENCAPSED_STRING);
        this.quote = quote;
        this.value = meta.getWord();
    }

    public List<Segment> getSegments() {
        return segments;
    }

    public void setSegments(List<Segment> segments) {
        this.segments = segments;
    }

    public Quote getQuote() {
        return quote;
    }

    public String getValue() {
        return value;
    }

    @Override
    public Object toNumeric(){
        int len = value.length();
        boolean real = false;
        int i = 0;
        for(; i < len; i++){
            char ch = value.charAt(i);
            if (!('9' >= ch && ch >= '0')){
                if (ch == '.'){
                    if (real)
                        break;
                    real = true;
                    continue;
                }
                if (i == 0)
                    return 0;
                else
                    break;
            }
        }
        if (real) {
            if (len == i)
                return Double.parseDouble(value);
            else
                return Double.parseDouble(value.substring(0, i));
        } else {
            if (len == i)
                return Long.parseLong(value);
            else
                return Long.parseLong(value.substring(0, i));
        }
    }

    public ExprStmtToken getExpression() {
        return expression;
    }

    public void setExpression(ExprStmtToken expression) {
        this.expression = expression;
    }

    @Override
    public String toString() {
        return value;
    }

    @Override
    public boolean isConstant() {
        return true;
    }


    /** Raw escape positions in the UTF16 projection; ordinary tokens have no descriptor. */
    public boolean hasRawBytes() {
        return rawBytePositions != null;
    }

    public void setRawBytePositions(java.util.BitSet positions) {
        if (positions == null || positions.isEmpty()) {
            rawBytePositions = null;
            return;
        }
        if (positions.length() > value.length()) throw new IllegalArgumentException("Raw position outside literal");
        for (int i = positions.nextSetBit(0); i >= 0; i = positions.nextSetBit(i + 1))
            if (value.charAt(i) > 255) throw new IllegalArgumentException("Raw position is not a byte");
        rawBytePositions = (java.util.BitSet) positions.clone();
    }

    /** Exact bytes of the lexer projection; interpolation expressions are not evaluated here. */
    public byte[] getPhp85Bytes() {
        if (rawBytePositions == null) return value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream(value.length());
        int from = 0;
        for (int i = rawBytePositions.nextSetBit(0); i >= 0; i = rawBytePositions.nextSetBit(i + 1)) {
            if (from < i) bytes.writeBytes(value.substring(from, i).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            bytes.write(value.charAt(i));
            from = i + 1;
        }
        if (from < value.length()) bytes.writeBytes(value.substring(from).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return bytes.toByteArray();
    }

    /** A literal slice retains the honest enclosing source span and rebases projection offsets. */
    public StringExprToken slice(int from, int to) {
        if (from < 0 || to < from || to > value.length()) throw new IndexOutOfBoundsException();
        for (Segment segment : segments)
            if (segment.from < to && segment.to > from && (segment.from < from || segment.to > to))
                throw new IllegalArgumentException("Slice splits an interpolation expression");
        StringExprToken result = new StringExprToken(TokenMeta.of(value.substring(from, to), this), Quote.SINGLE);
        result.binary = binary;
        if (rawBytePositions != null) {
            int first = rawBytePositions.nextSetBit(from);
            if (first >= 0 && first < to) result.rawBytePositions = rawBytePositions.get(from, to);
        }
        for (Segment segment : segments)
            if (segment.from >= from && segment.to <= to)
                result.segments.add(segment instanceof SourceSegment origin
                        ? new SourceSegment(segment.from - from, segment.to - from, segment.isVariable, origin.sourceLine, origin.sourcePosition)
                        : new Segment(segment.from - from, segment.to - from, segment.isVariable));
        return result;
    }

    public boolean isBinary() {
        return binary;
    }

    public void setBinary(boolean binary) {
        this.binary = binary;
    }
}
