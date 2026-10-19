package php.runtime.ext.core;
import php.runtime.annotation.Reflection.*;
import php.runtime.common.HintType;
import php.runtime.env.Environment;
import php.runtime.ext.support.compile.FunctionsContainer;
import php.runtime.reflection.ClassEntity;
public final class Php85EnumFunctions extends FunctionsContainer {
    @Signature({@Arg(value="enum",type=HintType.STRING),@Arg(value="autoload",type=HintType.BOOLEAN,optional=@Optional("true"))})
    public static boolean enum_exists(Environment env,String name,boolean autoload) {
        ClassEntity clazz=env.fetchClass(name,autoload,php.runtime.common.PhpLanguageProfile.PHP_8_5);return clazz!=null && clazz.isEnum();
    }
    @Signature({@Arg(value="enum",type=HintType.STRING),@Arg(value="autoload",type=HintType.BOOLEAN,optional=@Optional("true"))})
    public static boolean enum_exists(Environment env,String name) {return enum_exists(env,name,true);}
}
