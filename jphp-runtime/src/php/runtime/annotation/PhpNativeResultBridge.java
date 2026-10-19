package php.runtime.annotation;

import java.lang.annotation.*;

/** Cold contract, never state on an ordinary PHP call. */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface PhpNativeResultBridge {
    String discarded();
}
