package php.runtime.lang.exception;

import php.runtime.annotation.Reflection.Name;
import php.runtime.env.Environment;
import php.runtime.exceptions.support.ErrorType;
import php.runtime.reflection.ClassEntity;

@Name("UnhandledMatchError")
public class BaseUnhandledMatchError extends BaseError {
    public BaseUnhandledMatchError(Environment env, ErrorType errorType) { super(env,errorType); }
    public BaseUnhandledMatchError(Environment env, ClassEntity clazz) { super(env,clazz); }
}
