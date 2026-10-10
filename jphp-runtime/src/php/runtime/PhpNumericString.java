package php.runtime;

import php.runtime.memory.DoubleMemory;
import php.runtime.memory.LongMemory;

/** Decimal numeric-string grammar; distinct from source-code numeric literals. */
public final class PhpNumericString {
    private static final long REAL = 1L << 32, FULL = 1L << 33;
    private PhpNumericString() {}

    public static boolean isNumeric(String value) { return (scan(value) & FULL) != 0; }
    public static boolean hasLeadingNumber(String value) { return scan(value) != 0; }

    public static Memory parseStrict(String value) {
        long scanned = scan(value);
        return (scanned & FULL) == 0 ? null : number(value, scanned);
    }

    public static Memory parseLeading(String value) {
        long scanned = scan(value);
        return scanned == 0 ? Memory.CONST_INT_0 : number(value, scanned);
    }

    public static double leadingDouble(String value) {
        long scanned = scan(value);
        if (scanned == 0) return 0;
        int start = 0, end = (int) scanned;
        while (start < end && whitespace(value.charAt(start))) start++;
        return Double.parseDouble(start == 0 && end == value.length() ? value : value.substring(start, end));
    }

    private static long scan(String value) {
        if (value == null) return 0;
        int length = value.length(), i = 0;
        while (i < length && whitespace(value.charAt(i))) i++;
        if (i < length && (value.charAt(i) == '+' || value.charAt(i) == '-')) i++;
        int digits = i;
        while (i < length && digit(value.charAt(i))) i++;
        boolean hasDigits = i > digits, real = false;
        if (i < length && value.charAt(i) == '.') {
            real = true;
            digits = ++i;
            while (i < length && digit(value.charAt(i))) i++;
            hasDigits |= i > digits;
        }
        if (!hasDigits) return 0;
        if (i < length && (value.charAt(i) == 'e' || value.charAt(i) == 'E')) {
            int exponent = i, next = i + 1;
            if (next < length && (value.charAt(next) == '+' || value.charAt(next) == '-')) next++;
            digits = next;
            while (next < length && digit(value.charAt(next))) next++;
            if (next > digits) { i = next; real = true; }
            else i = exponent; // The incomplete exponent belongs to the nonnumeric tail.
        }
        int end = i;
        while (i < length && whitespace(value.charAt(i))) i++;
        return end | (real ? REAL : 0) | (i == length ? FULL : 0);
    }

    private static Memory number(String value, long scanned) {
        int start = 0, end = (int) scanned;
        while (start < end && whitespace(value.charAt(start))) start++;
        if ((scanned & REAL) == 0) {
            int i = start;
            boolean negative = value.charAt(i) == '-';
            if (negative || value.charAt(i) == '+') i++;
            long limit = negative ? Long.MIN_VALUE : -Long.MAX_VALUE, result = 0;
            long threshold = limit / 10;
            for (; i < end; i++) {
                int digit = value.charAt(i) - '0';
                if (result < threshold || result * 10 < limit + digit) {
                    return DoubleMemory.valueOf(Double.parseDouble(value.substring(start, end)));
                }
                result = result * 10 - digit;
            }
            return LongMemory.valueOf(negative ? result : -result);
        }
        return DoubleMemory.valueOf(Double.parseDouble(start == 0 && end == value.length()
                ? value : value.substring(start, end)));
    }

    public static long integerInBase(String value, int base) {
        if (base != 0 && (base < 2 || base > 36)) return 0;
        int i = 0, length = value.length();
        while (i < length && whitespace(value.charAt(i))) i++;
        boolean negative = i < length && value.charAt(i) == '-';
        if (i < length && (negative || value.charAt(i) == '+')) i++;
        if (i < length && value.charAt(i) == '0') {
            char next = i + 1 < length ? value.charAt(i + 1) : 0;
            if ((base == 0 || base == 16) && (next == 'x' || next == 'X')) { base = 16; i += 2; }
            else if ((base == 0 || base == 2) && (next == 'b' || next == 'B')) { base = 2; i += 2; }
            else if (base == 0) base = 8;
        } else if (base == 0) base = 10;
        long limit = negative ? Long.MIN_VALUE : -Long.MAX_VALUE, result = 0, threshold = limit / base;
        for (; i < length; i++) {
            char ch = value.charAt(i);
            int digit = ch >= '0' && ch <= '9' ? ch - '0' : ch >= 'a' && ch <= 'z' ? ch - 'a' + 10
                    : ch >= 'A' && ch <= 'Z' ? ch - 'A' + 10 : -1;
            if (digit < 0 || digit >= base) break;
            if (result < threshold || result * base < limit + digit) return negative ? Long.MIN_VALUE : Long.MAX_VALUE;
            result = result * base - digit;
        }
        return negative ? result : -result;
    }

    private static boolean digit(char ch) { return ch >= '0' && ch <= '9'; }
    private static boolean whitespace(char ch) { return ch == ' ' || (ch >= '\t' && ch <= '\r'); }
}
