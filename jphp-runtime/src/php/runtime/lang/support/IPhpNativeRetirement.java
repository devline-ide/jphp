package php.runtime.lang.support;
import php.runtime.env.Environment;
import php.runtime.lang.IObject;

/** Typed native-only lifetime callback; it does not declare a PHP __destruct method. */
@php.runtime.annotation.Reflection.Ignore
public interface IPhpNativeRetirement extends IObject {
    void retireNative(Environment environment) throws Throwable;
}
