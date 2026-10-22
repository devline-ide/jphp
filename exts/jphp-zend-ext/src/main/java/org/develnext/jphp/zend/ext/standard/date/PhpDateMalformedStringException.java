package org.develnext.jphp.zend.ext.standard.date;
import php.runtime.annotation.Reflection.Name;
import php.runtime.env.Environment;
import php.runtime.reflection.ClassEntity;
@Name("DateMalformedStringException")
public class PhpDateMalformedStringException extends PhpDateException {
    public PhpDateMalformedStringException(Environment env,ClassEntity clazz){super(env,clazz);}
}
