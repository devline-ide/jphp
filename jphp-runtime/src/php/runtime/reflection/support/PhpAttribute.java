package php.runtime.reflection.support;
import java.util.List;
import php.runtime.Memory;
import php.runtime.env.Environment;
import php.runtime.env.TraceInfo;
import php.runtime.exceptions.CriticalException;
import php.runtime.memory.ArrayMemory;

/** Immutable declaration metadata. Never attached to ordinary PHP values or objects. */
public record PhpAttribute(String name, TraceInfo trace, int target, boolean strictTypes, List<Argument> arguments) {
    public static final int CLASS=1,FUNCTION=2,METHOD=4,PROPERTY=8,CLASS_CONSTANT=16,PARAMETER=32,CONSTANT=64,ALL=127,REPEATABLE=128;
    public record Initializer(String moduleName,String javaClass,String method,String declarationScope) {
        public Initializer {
            if (moduleName==null || moduleName.isEmpty() || javaClass==null || javaClass.isEmpty()
                    || method==null || !method.startsWith("__$attributeArgument")) throw new IllegalArgumentException("Invalid attribute initializer");
        }
    }
    public record Argument(String name,Initializer initializer,Integer nativeInteger) {
        public Argument(String name,Initializer initializer) {this(name,initializer,null);}
        public Argument {
            if((initializer==null)==(nativeInteger==null)) throw new IllegalArgumentException("An attribute argument must have exactly one declaration producer");
        }
        public static Argument nativeInteger(int value) {return new Argument(null,null,value);}
    }
    public PhpAttribute {
        java.util.Objects.requireNonNull(name);java.util.Objects.requireNonNull(trace);
        if (name.isEmpty() || Integer.bitCount(target)!=1 || (target & ALL)!=target) throw new IllegalArgumentException("Invalid attribute target");
        arguments=List.copyOf(arguments);
    }
    public PhpAttribute bindInitializers(String oldPhysical,String actualPhysical,String scope) {
        var bound=new java.util.ArrayList<Argument>(arguments.size());
        for(Argument argument:arguments) {
            if(argument.nativeInteger()!=null) {bound.add(argument);continue;}
            Initializer original=argument.initializer();
            String physical=original.javaClass().replace('.','/').equals(oldPhysical.replace('.','/')) ? actualPhysical : original.javaClass();
            bound.add(new Argument(argument.name(),new Initializer(original.moduleName(),physical,original.method(),
                    scope==null ? original.declarationScope() : scope)));
        }
        return new PhpAttribute(name,trace,target,strictTypes,bound);
    }
    public ArrayMemory evaluateArguments(Environment env) {return evaluateArguments(env,null);}
    public ArrayMemory evaluateArguments(Environment env,Entity declaration) {
        String scope=declaration instanceof php.runtime.reflection.MethodEntity method
                && !(method.getClazz() instanceof php.runtime.reflection.helper.ClosureEntity) ? method.getClazz().getName()
                : declaration instanceof php.runtime.reflection.PropertyEntity property && property.getClazz()!=null ? property.getClazz().getName()
                : declaration instanceof php.runtime.reflection.ConstantEntity constant && constant.getClazz()!=null ? constant.getClazz().getName() : null;
        ArrayMemory result=new ArrayMemory();
        for(Argument argument:arguments) {
            if(argument.nativeInteger()!=null) {
                result.add(php.runtime.memory.LongMemory.valueOf(argument.nativeInteger()));continue;
            }
            Initializer init=argument.initializer();
            if(env.getScope().getClassLoader().getModule(init.moduleName())==null)
                throw new CriticalException("Attribute initializer module is not loaded");
            String actualScope=scope==null ? init.declarationScope() : scope;
            env.pushCall(trace,null,null,"{attribute-argument}",actualScope,actualScope);
            try {
                Class<?> source=env.getScope().getClassLoader().loadClass(init.javaClass());
                Memory value=(Memory)source.getDeclaredMethod(init.method(),Environment.class).invoke(null,env);
                if(argument.name()==null) result.add(value); else result.refOfIndex(argument.name()).assign(value);
            } catch(java.lang.reflect.InvocationTargetException failure) {env.__throwException(failure);}
            catch(ReflectiveOperationException failure) {throw new CriticalException(failure);}
            finally {env.popCall();}
        }
        return result;
    }
}
