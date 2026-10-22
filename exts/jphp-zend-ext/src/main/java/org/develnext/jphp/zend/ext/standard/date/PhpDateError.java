package org.develnext.jphp.zend.ext.standard.date;
import php.runtime.annotation.Reflection.Name;
import php.runtime.env.Environment;
import php.runtime.reflection.ClassEntity;
@Name("DateError")
public class PhpDateError extends php.runtime.lang.exception.BaseError {
    public PhpDateError(Environment env,ClassEntity clazz){super(env,clazz);}
}
