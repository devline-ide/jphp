package php.runtime;

import java.util.*;
import java.nio.charset.StandardCharsets;
import php.runtime.env.Environment;
import php.runtime.env.TraceInfo;
import php.runtime.exceptions.support.ErrorType;
import php.runtime.lang.BaseEnum;
import php.runtime.lang.exception.*;
import php.runtime.memory.*;
import php.runtime.reflection.*;
import php.runtime.reflection.support.PhpTypeDeclaration;

/** Declaration-driven enum operations. Canonical objects and initialization state belong to one Environment. */
public final class PhpEnums {
    private PhpEnums() {}
    public record Construction(ClassEntity owner, String name, Memory value) {}
    public static final class State {
        final LinkedHashMap<String,ObjectMemory> cases = new LinkedHashMap<>();
        final Map<Object,ObjectMemory> backing = new HashMap<>();
        final Set<String> initializing = new HashSet<>();
        Construction construction;
        boolean backingReady;
        boolean backingInitializing;
    }
    private static void requireUnownedExecution(Environment env) {
        if(PhpOwnershipExecution.current(env)!=null)throw new IllegalArgumentException("Canonical enum table has no explicit ownership publication protocol");
    }
    public static Construction construction(Environment env, ClassEntity clazz) {
        requireUnownedExecution(env);
        State state = env.getEnumState(clazz, false);
        if (state == null || state.construction == null || state.construction.owner() != clazz)
            throw new IllegalStateException("Enum cases can only be constructed by their active Environment capability");
        return state.construction;
    }
    public static Memory resolveCase(ConstantEntity declaration, Environment env) {
        requireUnownedExecution(env);
        State active=env.getEnumState(declaration.getClazz(),false);
        if(declaration.getClazz().getEnumBackingType()!=null && (active==null || !active.backingInitializing))
            backing(env,declaration.getClazz());
        return createCase(declaration,env);
    }
    private static Memory createCase(ConstantEntity declaration, Environment env) {
        ClassEntity clazz = declaration.getClazz();
        if (clazz == null || !clazz.isEnum() || !declaration.isEnumCase())
            throw new IllegalArgumentException("Expected an enum case declaration");
        synchronized (env.getClassInitializationLock()) {
            State state = env.getEnumState(clazz, true);
            ObjectMemory previous = state.cases.get(declaration.getName());
            if (previous != null) return previous;
            // Source expressions use PhpConstantInitializer's exact constant-reference recursion guard.
            boolean firstInitializer=state.initializing.add(declaration.getName());
            Construction before = state.construction;
            try {
                Memory backing = Memory.NULL;
                if (clazz.getEnumBackingType() != null) {
                    backing = declaration.evaluateInitializer(env, clazz).toValue();

                }
                ObjectMemory completed=state.cases.get(declaration.getName());
                if(completed!=null)return completed;
                state.construction = new Construction(clazz,declaration.getName(),backing);
                ObjectMemory result = new ObjectMemory(clazz.createEnumCase(env));
                state.cases.put(declaration.getName(),result);
                return result;
            } catch (Throwable failure) {env.forwardThrow(failure);return Memory.NULL;}
            finally {state.construction=before;if(firstInitializer)state.initializing.remove(declaration.getName());}
        }
    }
    public static Memory cases(Environment env, String className) {
        requireUnownedExecution(env);
        ClassEntity clazz = enumClass(env,className);
        ArrayMemory result = new ArrayMemory();
        for (ConstantEntity constant : clazz.getConstants())
            if (constant.isEnumCase() && constant.getClazz() == clazz) result.add(createCase(constant,env));
        return result.toConstant();
    }
    private static ClassEntity enumClass(Environment env, String className) {
        ClassEntity clazz = env.fetchClass(className,true,php.runtime.common.PhpLanguageProfile.PHP_8_5);
        if(clazz==null || !clazz.isEnum())throw new IllegalArgumentException("Missing enum declaration "+className);
        return clazz;
    }
    private static Object key(Memory value) {
        return value.isString() ? new ByteKey(value.getBinaryBytes(StandardCharsets.UTF_8)) : Long.valueOf(value.toLong());
    }
    private static final class ByteKey {
        final byte[] bytes;
        ByteKey(byte[] bytes) {this.bytes=bytes.clone();}
        @Override public int hashCode() {return Arrays.hashCode(bytes);}
        @Override public boolean equals(Object value) {return value instanceof ByteKey other && Arrays.equals(bytes,other.bytes);}
    }
    private static State backing(Environment env,ClassEntity clazz) {
        synchronized (env.getClassInitializationLock()) {
            State state=env.getEnumState(clazz,true);
            if(state.backingReady)return state;
            if(state.backingInitializing)env.exception(clazz.getTrace(),BaseError.class,"Cannot initialize self-referencing enum %s",clazz.getName());
            state.backingInitializing=true;
            Map<Object,ObjectMemory> checked=new HashMap<>();
            try {
                for(ConstantEntity constant:clazz.getConstants())if(constant.isEnumCase()) {
                    ObjectMemory actual=(ObjectMemory)createCase(constant,env);
                    BaseEnum object=(BaseEnum)actual.value;
                    Memory backing=object.enumBackingValue();
                    boolean valid=clazz.getEnumBackingType().equals("int")?backing.getRealType()==Memory.Type.INT:backing.isString();
                    if(!valid)env.exception(constant.getTrace(),BaseTypeError.class,"Enum case type %s does not match enum backing type %s",PhpTypeDeclaration.valueType(backing),clazz.getEnumBackingType());
                    ObjectMemory duplicate=checked.put(key(backing),actual);
                    if(duplicate!=null)env.exception(constant.getTrace(),BaseError.class,
                            "Duplicate value in enum %s for cases %s and %s",clazz.getName(),((BaseEnum)duplicate.value).enumCaseName(),object.enumCaseName());
                }
                state.backing.putAll(checked);state.backingReady=true;return state;
            } finally {state.backingInitializing=false;}
        }
    }
    public static Memory from(Environment env,String className,Memory[] arguments,boolean nullable) {
        ClassEntity clazz=enumClass(env,className);
        String method=nullable?"tryFrom":"from";
        // The dispatcher prepares the argument under the original caller's strictness before entering this body.
        Memory value=arguments[0];
        State state=backing(env,clazz);
        if(clazz.getEnumBackingType().equals("string") && value.getRealType()==Memory.Type.INT)value=StringMemory.valueOf(Long.toString(value.toLong()));
        ObjectMemory actual=state.backing.get(key(value));
        if(actual!=null)return actual;
        if(nullable)return Memory.NULL;
        String printed=value.isString()?"\""+value.toString()+"\"":value.toString();
        env.exception(env.trace(),BaseValueError.class,"%s is not a valid backing value for enum %s",printed,clazz.getName());
        return Memory.NULL;
    }
    public static boolean generatedMethod(MethodEntity method) {
        if(method==null || method.getClazz()==null || !method.getClazz().isEnum())return false;
        String name=method.getLowerName();
        return method.getInternalName().equals("__$enum$"+name)
                && (name.equals("cases") || method.getClazz().getEnumBackingType()!=null && (name.equals("from") || name.equals("tryfrom")));
    }
    public static Memory[] prepareArguments(Environment env,TraceInfo trace,MethodEntity method,Memory[] args,boolean internalCall) {
        int count=args==null?0:args.length,expected=method.getLowerName().equals("cases")?0:1;
        if(count!=expected)env.exception(trace,BaseArgumentCountError.class,"%s::%s() expects exactly %d argument%s, %d given",
                method.getClazz().getName(),method.getName(),expected,expected==1?"":"s",count);
        if(expected==0)return args==null?new Memory[0]:args;
        Memory input=args[0].toValue();
        ModuleEntity caller=env.getModuleManager().findModule(trace);
        boolean strict=!internalCall && caller!=null && caller.isStrictTypes();
        boolean integer=method.getClazz().getEnumBackingType().equals("int");
        String type=integer?"int":strict?"string":"string|int";
        Memory converted=input;
        boolean valid=integer?input.getRealType()==Memory.Type.INT:input.isString() || !strict && input.getRealType()==Memory.Type.INT;
        if(!valid && !strict) {
            if(input.isNull()) {
                env.error(trace,ErrorType.E_DEPRECATED,"%s::%s(): Passing null to parameter #1 ($value) of type string|int is deprecated",method.getClazz().getName(),method.getName());
                converted=LongMemory.valueOf(0);
            } else if(integer || !input.isString() && !input.isObject()) {
                converted=php.runtime.reflection.support.TypeChecker.of(php.runtime.common.HintType.INT).apply(env,input,false,false);
                if(converted!=null) {
                    converted=PhpNumericConversions.checkedScalarCoercion(input,converted,env,trace);
                    Memory number=input.isString()?PhpNumericString.parseStrict(input.toString()):input;
                    if(number!=null)PhpNumericConversions.warnIntegerPrecisionLoss(input,number,env,trace);
                }
            } else if(input.isObject())converted=php.runtime.reflection.support.TypeChecker.of(php.runtime.common.HintType.STRING).apply(env,input,false,false);
            valid=converted!=null;
        }
        if(!valid)env.exception(trace,BaseTypeError.class,"%s::%s(): Argument #1 ($value) must be of type %s, %s given",
                method.getClazz().getName(),method.getName(),type,input.getRealType()==Memory.Type.BOOL?(input.toBoolean()?"true":"false"):PhpTypeDeclaration.valueType(input));
        return new Memory[]{converted};
    }
    public static void validateDeclaration(ClassEntity clazz,Environment env) {
        if(!clazz.isEnum()) {
            if(clazz.getModule()==null || clazz.getModule().getPhpLanguageProfile()!=php.runtime.common.PhpLanguageProfile.PHP_8_5)return;
            if(clazz.isClass() && (clazz.isInstanceOfAsciiName("UnitEnum") || clazz.isInstanceOfAsciiName("BackedEnum")))
                env.error(clazz.getTrace(),ErrorType.E_COMPILE_ERROR,"Non-enum class %s cannot implement interface %s",clazz.getName(),clazz.isInstanceOfAsciiName("BackedEnum")?"BackedEnum":"UnitEnum");
            return;
        }
        if(clazz.getParent()!=null || clazz.getDeferredParentName()!=null || clazz.isAbstract() || clazz.isPhpReadonly())
            env.error(clazz.getTrace(),ErrorType.E_COMPILE_ERROR,"Enum %s cannot extend a class or be abstract/readonly",clazz.getName());
        Set<String> prohibited=Set.of("__construct","__destruct","__clone","__get","__set","__unset","__isset","__tostring","__sleep","__wakeup","__serialize","__unserialize","__debuginfo","__set_state");
        for(MethodEntity method:clazz.getMethods().values()) {
            if(prohibited.contains(method.getLowerName()))env.error(method.getTrace(),ErrorType.E_COMPILE_ERROR,"Enum %s cannot include magic method %s",clazz.getName(),method.getName());
            if(method.getLowerName().equals("cases") || clazz.getEnumBackingType()!=null && (method.getLowerName().equals("from")||method.getLowerName().equals("tryfrom")))
                if(!generatedMethod(method))env.error(method.getTrace(),ErrorType.E_COMPILE_ERROR,"Cannot redeclare %s::%s()",clazz.getName(),method.getName());
        }
        for(PropertyEntity property:clazz.getProperties())if(!syntheticProperty(clazz,property))
            env.error(property.getTrace(),ErrorType.E_COMPILE_ERROR,"Enum %s cannot include properties",clazz.getName());
        if(!clazz.getStaticProperties().isEmpty())env.error(clazz.getTrace(),ErrorType.E_COMPILE_ERROR,"Enum %s cannot include properties",clazz.getName());
        if(clazz.isInstanceOfAsciiName("Serializable"))env.error(clazz.getTrace(),ErrorType.E_COMPILE_ERROR,"Enum %s cannot implement the Serializable interface",clazz.getName());
        if(clazz.getEnumBackingType()==null && clazz.isInstanceOfAsciiName("BackedEnum"))env.error(clazz.getTrace(),ErrorType.E_COMPILE_ERROR,"Non-backed enum %s cannot implement interface BackedEnum",clazz.getName());
    }
    public static boolean syntheticProperty(ClassEntity clazz,PropertyEntity property) {
        return property.getClazz()==clazz && !property.isStatic() && property.isPublic() && property.isPhpReadonly() && !property.isDefault()
                && property.getDeclaredType()!=null && property.getGetter()==null && property.getSetter()==null
                && (property.getName().equals("name") && property.getDeclaredType().getSignature().equals("string")
                    || clazz.getEnumBackingType()!=null && property.getName().equals("value") && property.getDeclaredType().getSignature().equals(clazz.getEnumBackingType()));
    }
    public static void configureBuiltin(ClassEntity clazz) {
        Class<?> nativeClass=clazz.getNativeClass();
        if(nativeClass!=php.runtime.lang.UnitEnum.class && nativeClass!=php.runtime.lang.BackedEnum.class)return;
        for(String name:nativeClass==php.runtime.lang.UnitEnum.class?new String[]{"cases"}:new String[]{"from","tryFrom"}) {
            MethodEntity method=new MethodEntity(clazz.getContext());method.setName(name);method.setInternalName(name);method.setClazz(clazz);
            method.setStatic(true);method.setAbstract(true);method.setAbstractable(true);method.setModifier(php.runtime.common.Modifier.PUBLIC);
            if(name.equals("cases")){method.setParameters(new ParameterEntity[0]);method.setReturnTypeChecker(new PhpTypeDeclaration(List.of(List.of("array"))));}
            else {
                ParameterEntity parameter=new ParameterEntity(clazz.getContext());parameter.setName("value");
                parameter.setTypeChecker(new PhpTypeDeclaration(List.of(List.of("string"),List.of("int"))));method.setParameters(new ParameterEntity[]{parameter});
                method.setReturnTypeChecker(new PhpTypeDeclaration(name.equals("tryFrom")?List.of(List.of("static"),List.of("null")):List.of(List.of("static"))));
            }
            clazz.addMethod(method,null);
        }
    }
    public static void addProperties(ClassEntity clazz,Environment env) {
        for(String name:clazz.getEnumBackingType()==null?new String[]{"name"}:new String[]{"name","value"}) {
            PropertyEntity property=new PropertyEntity(clazz.getContext());property.setName(name);property.setClazz(clazz);
            property.setModifier(php.runtime.common.Modifier.PUBLIC);property.setPhpReadonly(true);property.setDefault(false);
            property.setDeclaredType(new PhpTypeDeclaration(List.of(List.of(name.equals("name")?"string":clazz.getEnumBackingType()))));
            clazz.addProperty(property).check(env);
        }
    }
}
