package org.develnext.jphp.zend.ext.standard.date;
import php.runtime.annotation.Reflection.Name;
import php.runtime.env.Environment;
import php.runtime.reflection.ClassEntity;
@Name("DateException")
public class PhpDateException extends php.runtime.lang.BaseException {
    public PhpDateException(Environment env,ClassEntity clazz){super(env,clazz);}
}
