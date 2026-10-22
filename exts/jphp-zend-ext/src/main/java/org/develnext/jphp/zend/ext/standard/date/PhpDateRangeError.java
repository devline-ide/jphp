package org.develnext.jphp.zend.ext.standard.date;
import php.runtime.annotation.Reflection.Name;
import php.runtime.env.Environment;
import php.runtime.reflection.ClassEntity;
@Name("DateRangeError")
public class PhpDateRangeError extends PhpDateError {
    public PhpDateRangeError(Environment env,ClassEntity clazz){super(env,clazz);}
}
