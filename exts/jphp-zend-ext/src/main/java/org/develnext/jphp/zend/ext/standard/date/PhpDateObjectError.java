package org.develnext.jphp.zend.ext.standard.date;
import php.runtime.annotation.Reflection.Name;
import php.runtime.env.Environment;
import php.runtime.reflection.ClassEntity;
@Name("DateObjectError")
public class PhpDateObjectError extends PhpDateError {
    public PhpDateObjectError(Environment env,ClassEntity clazz){super(env,clazz);}
}
