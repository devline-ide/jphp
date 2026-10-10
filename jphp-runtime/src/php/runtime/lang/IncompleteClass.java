package php.runtime.lang;

import php.runtime.annotation.Reflection.Name;
import php.runtime.env.Environment;
import php.runtime.reflection.ClassEntity;

@Name("__PHP_Incomplete_Class")
public class IncompleteClass extends BaseObject {
    public IncompleteClass(Environment env, ClassEntity clazz) {
        super(env, clazz);
    }
}
