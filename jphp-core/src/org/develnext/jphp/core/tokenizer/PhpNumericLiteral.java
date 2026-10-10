package org.develnext.jphp.core.tokenizer;

import org.develnext.jphp.core.tokenizer.token.Token;
import org.develnext.jphp.core.tokenizer.token.expr.value.DoubleExprToken;
import org.develnext.jphp.core.tokenizer.token.expr.value.IntegerExprToken;
import php.runtime.env.Context;
import php.runtime.exceptions.ParseException;

import java.util.regex.Pattern;

/** Source literals, deliberately separate from PHP's numeric-string grammar. */
final class PhpNumericLiteral {
    private static final String DIGITS = "[0-9]++(?:_[0-9]++)*+";
    private static final Pattern DECIMAL = Pattern.compile(DIGITS);
    private static final Pattern HEX = Pattern.compile("0[xX][0-9a-fA-F]++(?:_[0-9a-fA-F]++)*+");
    private static final Pattern BINARY = Pattern.compile("0[bB][01]++(?:_[01]++)*+");
    private static final Pattern OCTAL = Pattern.compile("0[oO][0-7]++(?:_[0-7]++)*+");
    private static final Pattern FLOAT = Pattern.compile("(?:" + DIGITS + "\\.(?:" + DIGITS + ")?|\\." + DIGITS
            + ")(?:[eE][+-]?" + DIGITS + ")?|" + DIGITS + "[eE][+-]?" + DIGITS);

    private PhpNumericLiteral() {}

    static Token parse(TokenMeta meta, Context context) {
        String word = meta.getWord();
        String normalized = word.replace("_", "");
        if (FLOAT.matcher(word).matches()) return new DoubleExprToken(meta, Double.parseDouble(normalized));
        int radix = 10, offset = 0;
        if (HEX.matcher(word).matches()) { radix = 16; offset = 2; }
        else if (BINARY.matcher(word).matches()) { radix = 2; offset = 2; }
        else if (OCTAL.matcher(word).matches()) { radix = 8; offset = 2; }
        else if (DECIMAL.matcher(word).matches()) {
            if (normalized.length() > 1 && normalized.charAt(0) == '0') radix = 8;
        } else throw invalid(meta, context);
        String digits = normalized.substring(offset);
        try { return new IntegerExprToken(meta, Long.parseLong(digits, radix)); }
        catch (NumberFormatException overflow) {
            if (radix == 10) return new DoubleExprToken(meta, Double.parseDouble(digits));
            double value = 0;
            // PHP converts overflowing base literals digit by digit. Preserve its
            // intermediate rounding, including character-to-digit subtraction.
            for (int i = 0; i < digits.length(); i++) {
                char ch = digits.charAt(i);
                int digit = Character.digit(ch, radix);
                if (digit < 0) throw invalid(meta, context);
                value = radix == 16 ? value * radix + digit : value * radix + ch - '0';
            }
            return new DoubleExprToken(meta, value);
        }
    }

    private static ParseException invalid(TokenMeta meta, Context context) {
        return new ParseException("Invalid numeric literal '" + meta.getWord() + "'", meta.toTraceInfo(context));
    }
}
