package php.runtime.lang.exception;

import php.runtime.annotation.Reflection.Name;
import php.runtime.env.Environment;
import php.runtime.exceptions.support.ErrorType;
import php.runtime.reflection.ClassEntity;

@Name("ValueError")
public class BaseValueError extends BaseError {
    public BaseValueError(Environment env, ErrorType errorType) {
        super(env, errorType);
    }

    public BaseValueError(Environment env, ClassEntity clazz) {
        super(env, clazz);
    }
}
