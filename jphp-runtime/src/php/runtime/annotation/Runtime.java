package php.runtime.annotation;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.PARAMETER;

public @interface Runtime {

    @Retention(RetentionPolicy.RUNTIME)
    @Target({METHOD, PARAMETER})
    public @interface Reference {}

    @Retention(RetentionPolicy.RUNTIME)
    @Target({METHOD})
    public @interface Immutable {
        boolean ignoreRefs() default false;
    }

    /** PHP known-native frameless entry arities; distinct from Java fast-method dispatch. */
    @Retention(RetentionPolicy.RUNTIME)
    @Target({METHOD})
    public @interface Frameless {
        int[] value();
    }

    /** Native BODY implements ordered string conversion over its actual parsed array snapshot. */
    @Retention(RetentionPolicy.RUNTIME)
    @Target({METHOD})
    public @interface StringJoin {}

    /** Unary STRING argument conversion with a checked Stringable receiver SEND; INT scalar result. */
    @Retention(RetentionPolicy.RUNTIME)
    @Target({METHOD})
    public @interface ScalarStringCallback {
        int argument();
    }

    /** Exact PHP frameless target for direct positional calls; dynamic calls keep their published entry. */
    @Retention(RetentionPolicy.RUNTIME)
    @Target({METHOD})
    public @interface DirectCall {
        String value();
        boolean liveArrayVariables() default false;
    }

    @Retention(RetentionPolicy.RUNTIME)
    @Target({METHOD})
    public @interface FastMethod {
    }

    @Retention(RetentionPolicy.RUNTIME)
    @Target({PARAMETER})
    public @interface MutableValue {
        boolean ignoreRefs() default false;
    }

    @Retention(RetentionPolicy.RUNTIME)
    @Target({PARAMETER})
    public @interface GetLocals {}
}
