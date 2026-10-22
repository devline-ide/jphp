package org.develnext.jphp.zend.ext.standard.date;
import php.runtime.annotation.Reflection.Name;
import php.runtime.env.Environment;
import php.runtime.reflection.ClassEntity;
@Name("DateMalformedIntervalStringException")
public class PhpDateMalformedIntervalStringException extends PhpDateException {
    public PhpDateMalformedIntervalStringException(Environment env,ClassEntity clazz){super(env,clazz);}
}
