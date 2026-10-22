package org.develnext.jphp.zend.ext.standard.date;
import php.runtime.annotation.Reflection.Name;
import php.runtime.env.Environment;
import php.runtime.reflection.ClassEntity;
@Name("DateInvalidTimeZoneException")
public class PhpDateInvalidTimeZoneException extends PhpDateException {
    public PhpDateInvalidTimeZoneException(Environment env,ClassEntity clazz){super(env,clazz);}
}
