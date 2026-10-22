package php.runtime.lang.exception;
import php.runtime.env.Environment;
import php.runtime.reflection.ClassEntity;
import static php.runtime.annotation.Reflection.*;

@Name("FiberError")
public class PhpFiberError extends BaseError {
    public PhpFiberError(Environment env,ClassEntity clazz) {super(env,clazz);}
}
