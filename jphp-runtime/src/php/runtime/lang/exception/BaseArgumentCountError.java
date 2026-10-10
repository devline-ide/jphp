package php.runtime.lang.exception;

import php.runtime.annotation.Reflection.Name;
import php.runtime.env.Environment;
import php.runtime.exceptions.support.ErrorType;
import php.runtime.reflection.ClassEntity;

@Name("ArgumentCountError")
public class BaseArgumentCountError extends BaseTypeError {
    public BaseArgumentCountError(Environment env, ErrorType errorType) {
        super(env, errorType);
    }

    public BaseArgumentCountError(Environment env, ClassEntity clazz) {
        super(env, clazz);
    }
}
