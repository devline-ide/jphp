package org.develnext.jphp.zend.ext.json;
import php.runtime.annotation.Reflection.Name;
import php.runtime.env.Environment;
import php.runtime.reflection.ClassEntity;
@Name("JsonException")
public class Php85JsonException extends php.runtime.lang.BaseException {
    public Php85JsonException(Environment env){super(env);}
    public Php85JsonException(Environment env,ClassEntity clazz){super(env,clazz);}
}
