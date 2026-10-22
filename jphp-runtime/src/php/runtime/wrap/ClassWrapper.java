package php.runtime.wrap;

import php.runtime.Memory;
import php.runtime.annotation.Reflection;
import php.runtime.common.HintType;
import php.runtime.env.CompileScope;
import php.runtime.env.Environment;
import php.runtime.exceptions.CriticalException;
import php.runtime.ext.support.Extension;
import php.runtime.lang.BaseWrapper;
import php.runtime.lang.IObject;
import php.runtime.memory.support.MemoryOperation;
import php.runtime.memory.support.MemoryUtils;
import php.runtime.reflection.*;
import php.runtime.reflection.support.ReflectionUtils;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

@Reflection.Signature
public class ClassWrapper {
    protected final Extension extension;
    protected final CompileScope scope;
    protected final Class<?> nativeClass;

    public ClassWrapper(CompileScope scope, Class<?> clazz) {
        this(null, scope, clazz);
    }

    public ClassWrapper(Extension extension, CompileScope scope, Class<?> clazz) {
        this.extension = extension;
        this.scope = scope;
        this.nativeClass = clazz;
    }

    public Extension getExtension() {
        return extension;
    }

    public CompileScope getScope() {
        return scope;
    }

    public Class<?> getNativeClass() {
        return nativeClass;
    }

    protected void onWrapName(ClassEntity classEntity) {
        classEntity.setName(ReflectionUtils.getClassName(nativeClass));
        classEntity.setInternalName(nativeClass.getName().replace('.', '/'));
    }

    protected void onWrapConstants(ClassEntity classEntity) {
        for(Field field : nativeClass.getDeclaredFields()){
            if ((scope == null || !scope.isModernClassLookup()) && nativeClass == php.runtime.ext.core.reflection.ReflectionClass.class
                    && (field.getName().equals("SKIP_INITIALIZATION_ON_SERIALIZE") || field.getName().equals("SKIP_DESTRUCTOR"))) continue;
            int mod = field.getModifiers();
            if (field.isAnnotationPresent(Reflection.Ignore.class))
                continue;

            if (Modifier.isFinal(mod) && Modifier.isStatic(mod) && !Modifier.isPrivate(mod)){
                try {
                    field.setAccessible(true);
                    ConstantEntity constant = new ConstantEntity(field.getName(), MemoryUtils.valueOf(field.get(null)), true);
                    if (nativeClass == php.runtime.lang.spl.iterator.ArrayObject.class)
                        constant.setDeclaredType(new php.runtime.reflection.support.PhpTypeDeclaration(java.util.List.of(java.util.List.of("int"))));
                    classEntity.addConstant(constant);
                } catch (IllegalAccessException e) {
                    throw new RuntimeException(e);
                }
            }
        }
    }

    protected void onWrapProperty(ClassEntity classEntity, Reflection.Arg arg) {
        PropertyEntity entity = new PropertyEntity(classEntity.getContext());
        entity.setClazz(classEntity);
        entity.setModifier(arg.modifier());
        entity.setName(arg.value());
        entity.setStatic(false);

        if (arg.optional().exists()){
            entity.setDefaultValue(arg.optional().undefined() ? Memory.UNDEFINED : MemoryUtils.valueOf(arg.optional().value(), arg.optional().type()));
        } else {
            entity.setDefaultValue(null);
        }

        classEntity.addProperty(entity);
    }

    protected void onWrapCompileProperty(ClassEntity classEntity, Field field, Reflection.Property property) {
        CompilePropertyEntity entity = new CompilePropertyEntity(classEntity.getContext(), field);
        entity.setClazz(classEntity);
        entity.setHiddenInDebugInfo(property.hiddenInDebugInfo());

        if (property.value().isEmpty()) {
            entity.setName(field.getName());
        } else {
            entity.setName(property.value());
        }

        classEntity.addProperty(entity);
    }

    protected void onWrapProperties(ClassEntity classEntity) {
        Reflection.Signature signature = nativeClass.getAnnotation(Reflection.Signature.class);
        if (signature != null){
            for(Reflection.Arg arg : signature.value()){
                onWrapProperty(classEntity, arg);
            }
        }

        for(Field field : nativeClass.getDeclaredFields()) {
            Reflection.Property property = field.getAnnotation(Reflection.Property.class);

            if (property != null) {
                onWrapCompileProperty(classEntity, field, property);
            }
        }

        Reflection.WrapInterface interfaces = nativeClass.getAnnotation(Reflection.WrapInterface.class);

        if (interfaces != null && interfaces.wrapFields() && BaseWrapper.class.isAssignableFrom(nativeClass)) {
            Class<?> bindClass = MemoryOperation.getClassOfWrapper(
                    (Class<? extends php.runtime.lang.BaseWrapper<Object>>) nativeClass
            );

            for (Class _interface : interfaces.value()) {
                for (Field field : _interface.getDeclaredFields()) {
                    try {
                        Field _field = bindClass.getDeclaredField(field.getName());
                        int mods = _field.getModifiers();

                        if ((/*Modifier.isProtected(mods) || */Modifier.isPublic(mods)) && !Modifier.isStatic(mods)) {
                            if (interfaces.skipConflicts()
                                    && MemoryOperation.get(_field.getType(), _field.getGenericType()) == null) {
                                continue;
                            }

                            PropertyEntity entity = new WrapCompilePropertyEntity(classEntity.getContext(), _field);
                            entity.setName(_field.getName());

                            classEntity.addProperty(entity);
                        }
                    } catch (NoSuchFieldException e) {
                        throw new CriticalException(e);
                    }
                }
            }
        }
    }

    protected void onWrapArgument(ParameterEntity param, Reflection.Arg arg) {
        param.setReference(arg.reference());
        param.setVariadic(arg.variadic());
        param.setType(arg.type());
        param.setName(arg.value());
        param.setNullable(arg.nullable());

        if (arg.typeEnum() != Enum.class) {
            param.setTypeEnum(arg.typeEnum());
        }

        param.setDefaultValue(null);
        if (arg.optional().exists()
                || !arg.optional().value().isEmpty()
                || (arg.type() != HintType.STRING && !arg.optional().value().isEmpty())){
            param.setDefaultValue(arg.optional().undefined() ? Memory.UNDEFINED : MemoryUtils.valueOf(arg.optional().value(), arg.optional().type()));
        }

        if (!arg.typeClass().isEmpty())
            param.setTypeClass(arg.typeClass());
        else if (arg.nativeType() != IObject.class) {
            param.setTypeClass(ReflectionUtils.getClassName(arg.nativeType()));
        }
    }

    protected PropertyEntity getPropertyOfMethod(MethodEntity entity, String name) {
        PropertyEntity propertyEntity;

        if (entity.isStatic()) {
            throw new CriticalException("Cannot use static methods for Getters and Setters");
        } else {
            propertyEntity = entity.getClazz().findProperty(name);
        }

        if (propertyEntity == null || propertyEntity.getClazz() != entity.getClazz()) {
            PropertyEntity prototype = propertyEntity;

            propertyEntity = new PropertyEntity(entity.getContext());
            propertyEntity.setClazz(entity.getClazz());
            propertyEntity.setTrace(entity.getTrace());

            propertyEntity.setPrototype(prototype);
            propertyEntity.setName(name);

            propertyEntity.setStatic(entity.isStatic());

            entity.getClazz().addProperty(propertyEntity).check(null);
        }

        return propertyEntity;
    }

    protected void onWrapGetterSetterProperty(MethodEntity entity, Method method) {
        if (method.isAnnotationPresent(Reflection.Getter.class)) {
            Reflection.Getter getter = method.getAnnotation(Reflection.Getter.class);
            String name = getter.value();

            if (name.isEmpty()) {
                name = method.getName();

                if (name.startsWith("__get")) {
                    name = name.substring(5);
                } else if (name.startsWith("get")) {
                    name = name.substring(3);
                } else if (name.startsWith("is") && (method.getReturnType() == Boolean.TYPE || method.getReturnType() == Boolean.class)) {
                    name = name.substring(2);
                }

                name = name.substring(0, 1).toLowerCase() + name.substring(1);
            }

            PropertyEntity propertyEntity = getPropertyOfMethod(
                    entity, name
            );

            method.setAccessible(true);
            propertyEntity.setGetter(entity);
            propertyEntity.setHiddenInDebugInfo(getter.hiddenInDebugInfo());

            if (entity.isPublic()) {
                entity.setModifier(php.runtime.common.Modifier.PROTECTED);
            }
        }

        if (method.isAnnotationPresent(Reflection.Setter.class)) {
            String name = method.getAnnotation(Reflection.Setter.class).value();

            if (name.isEmpty()) {
                name = method.getName();

                if (name.startsWith("__set")) {
                    name = name.substring(5);
                } else if (name.startsWith("set")) {
                    name = name.substring(3);
                }

                name = name.substring(0, 1).toLowerCase() + name.substring(1);
            }

            PropertyEntity propertyEntity = getPropertyOfMethod(
                    entity, name
            );

            method.setAccessible(true);
            propertyEntity.setSetter(entity);

            if (entity.isPublic()) {
                entity.setModifier(php.runtime.common.Modifier.PROTECTED);
            }
        }
    }

    protected Reflection.Signature getSignature(Method method) {
        Reflection.Signature signature = method.getAnnotation(Reflection.Signature.class);

        if (signature == null) {
            try {
                Class<?> parent = method.getDeclaringClass().getSuperclass();

                if (parent != null) {
                    method = parent.getDeclaredMethod(method.getName(), method.getParameterTypes());

                    if (method != null) {
                        signature = getSignature(method);

                        if (signature != null) {
                            return signature;
                        }
                    }
                }

            } catch (NoSuchMethodException e) {
                return null;
            }
        }

        return signature;
    }

    protected void onWrapMethod(ClassEntity classEntity, Method method) {
        MethodEntity entity = new MethodEntity(extension, method);
        entity.setClazz(classEntity);
        entity.setNativeMethod(method);
        entity.setAbstract(Modifier.isAbstract(method.getModifiers()));

        entity.setFinal(false);
        // entity.setFinal(Modifier.isFinal(method.getModifiers()));  Disable native final.

        entity.setStatic(Modifier.isStatic(method.getModifiers()));

        if (classEntity.isInterface()){
            entity.setAbstractable(true);
            entity.setAbstract(false);
        }

        if (entity.isAbstract())
            entity.setAbstractable(true);

        if (method.isAnnotationPresent(Reflection.Final.class)) {
            entity.setFinal(true);
        }

        if (method.isAnnotationPresent(Reflection.Abstract.class)) {
            entity.setAbstract(true);
            entity.setAbstractable(true);
        }

        entity.setInternalName(method.getName());

        Reflection.Name name = method.getAnnotation(Reflection.Name.class);
        entity.setName(name == null ? method.getName() : name.value());

        Reflection.Signature sign = getSignature(method);

        /*if (sign == null) {
            sign = getClass().getAnnotation(Reflection.Signature.class);
        }*/

        ParameterEntity[] params = new ParameterEntity[sign.value().length];

        int i = 0;
        for (Reflection.Arg arg : sign.value()){
            ParameterEntity param = new ParameterEntity(classEntity.getContext());
            onWrapArgument(param, arg);
            params[i++] = param;
        }

        entity.setParameters(params);
        classEntity.addMethod(entity, null);

        onWrapGetterSetterProperty(entity, method);
    }

    private static String effectivePhpName(Method method) {
        Reflection.Name name = method.getAnnotation(Reflection.Name.class);
        return name == null ? method.getName() : name.value();
    }

    protected MethodEntity onWrapWrapCompileMethod(ClassEntity classEntity, Method method, Method interfaceMethod, boolean skipConflicts) {
        String name = effectivePhpName(interfaceMethod.isAnnotationPresent(Reflection.Property.class) ? method : interfaceMethod);
        MethodEntity _entity = classEntity.findMethod(name.toLowerCase(java.util.Locale.ROOT));

        WrapCompileMethodEntity entity;
        if (_entity instanceof WrapCompileMethodEntity) {
            entity = (WrapCompileMethodEntity) _entity;
        } else {
            entity = new WrapCompileMethodEntity(classEntity.getExtension());
        }

        entity.addMethod(method, skipConflicts);
        entity.setName(name);
        if (_entity == null) {
            entity.setClazz(classEntity);
            classEntity.addMethod(entity, null);
        }

        onWrapGetterSetterProperty(entity, interfaceMethod);

        return entity;
    }


    protected void onWrapCompileMethod(ClassEntity classEntity, Method method) {
        String name = effectivePhpName(method);

        MethodEntity _entity = classEntity.findMethod(name.toLowerCase(java.util.Locale.ROOT));

        CompileMethodEntity entity;
        if (_entity instanceof CompileMethodEntity) {
            entity = (CompileMethodEntity) _entity;
        } else {
            entity = new CompileMethodEntity(classEntity.getExtension());
        }

        if (classEntity.isInterface()){
            entity.setAbstractable(true);
            entity.setAbstract(false);
        }

        if (entity instanceof WrapCompileMethodEntity)
            ((WrapCompileMethodEntity) entity).addDirectMethod(method, false);
        else
            entity.addMethod(method, false);

        if (_entity == null) {
            entity.setClazz(classEntity);
            classEntity.addMethod(entity, null);
        }

        onWrapGetterSetterProperty(entity, method);
    }

    protected void onWrapMethods(ClassEntity classEntity) {
        Reflection.WrapInterface interfaces = nativeClass.getAnnotation(Reflection.WrapInterface.class);

        Set<Class<?>> wrapInterfaces = new HashSet<Class<?>>();

        if (interfaces != null) {
            wrapInterfaces.addAll(Arrays.asList(interfaces.value()));
        }

        for (Class<?> cls : nativeClass.getDeclaredClasses()) {
            if (cls.getSimpleName().equals("WrappedInterface") && cls.getDeclaringClass() == nativeClass) {
                if (!cls.isInterface()) {
                    throw new CriticalException("WrappedInterface class must be interface");
                }

                if (!BaseWrapper.class.isAssignableFrom(nativeClass)) {
                    throw new CriticalException("To use WrappedInterface, your class must be inheritances from the BaseWrapper class");
                }

                wrapInterfaces.add(cls);
                break;
            }
        }

        if (!wrapInterfaces.isEmpty() && BaseWrapper.class.isAssignableFrom(nativeClass)) {
            Class<?> bindClass = MemoryOperation.getClassOfWrapper(
                    (Class<? extends php.runtime.lang.BaseWrapper<Object>>) nativeClass
            );

            for (Class _interface : wrapInterfaces) {
                for (Method method : _interface.getMethods()) {

                    // PHP native overload selection is by arity. Explicit signatures own only their slot.
                    boolean overridden = Arrays.stream(nativeClass.getDeclaredMethods()).anyMatch(declared ->
                            getSignature(declared) != null &&
                            effectivePhpName(declared).equalsIgnoreCase(effectivePhpName(method)) &&
                            (declared.getName().equals(method.getName()) &&
                                    Arrays.equals(declared.getParameterTypes(), method.getParameterTypes()) ||
                                    !method.isAnnotationPresent(Reflection.Property.class) &&
                                            !declared.isVarArgs() && !method.isVarArgs() &&
                                            CompileMethodEntity.getPhpArgumentCount(declared) == CompileMethodEntity.getPhpArgumentCount(method)));
                    if (overridden) continue;

                    try {
                        if (method.isAnnotationPresent(Reflection.Property.class)) {
                            String name = method.getName();

                            MethodEntity getterEntity;
                            try {
                                getterEntity = onWrapWrapCompileMethod(
                                        classEntity, bindClass.getMethod("get" + name.substring(0, 1).toUpperCase() + name.substring(1)), method,
                                        false
                                );
                            } catch (NoSuchMethodException e) {
                                if (method.getReturnType() == Boolean.TYPE || method.getReturnType() == Boolean.class) {
                                    getterEntity = onWrapWrapCompileMethod(
                                            classEntity, bindClass.getMethod("is" + name.substring(0, 1).toUpperCase() + name.substring(1)), method,
                                            false
                                    );
                                } else {
                                    throw e;
                                }
                            }

                            MethodEntity setterEntity = null;
                            try {
                                setterEntity = onWrapWrapCompileMethod(
                                        classEntity, bindClass.getMethod("set" + name.substring(0, 1).toUpperCase() + name.substring(1), method.getReturnType()), method,
                                        false
                                );
                            } catch (NoSuchMethodException e) {
                                // nop
                            }

                            String propertyName = method.getAnnotation(Reflection.Property.class).value();

                            if (propertyName.isEmpty()) {
                                propertyName = name;
                            }

                            PropertyEntity propertyEntity = getPropertyOfMethod(getterEntity, propertyName);
                            propertyEntity.setGetter(getterEntity);
                            propertyEntity.setSetter(setterEntity);

                            if (setterEntity != null && method.isAnnotationPresent(Reflection.Nullable.class)) {
                                ParameterEntity setterArg = setterEntity.getParameters(1)[0];
                                setterArg.setNullable(true);
                            }

                            if (_interface.isInterface()) {
                                getterEntity.setAbstractable(false);
                                getterEntity.setAbstract(false);

                                if (getterEntity.isPublic()) {
                                    getterEntity.setModifier(php.runtime.common.Modifier.PROTECTED);
                                }

                                if (setterEntity != null) {
                                    setterEntity.setAbstractable(false);
                                    setterEntity.setAbstract(false);

                                    if (setterEntity.isPublic()) {
                                        setterEntity.setModifier(php.runtime.common.Modifier.PROTECTED);
                                    }
                                }
                            }

                            classEntity.addProperty(propertyEntity).check(null);
                        } else {
                            MethodEntity entity = onWrapWrapCompileMethod(
                                    classEntity, bindClass.getMethod(method.getName(), method.getParameterTypes()), method,
                                    interfaces != null && interfaces.skipConflicts()
                            );

                            if (_interface.isInterface()) {
                                entity.setAbstractable(false);
                                entity.setAbstract(false);
                            }
                        }
                    } catch (NoSuchMethodException e) {
                        boolean _throw = true;

                        if (interfaces != null && interfaces.skipConflicts()) {
                            for (Class<?> aClass : interfaces.value()) {
                                if (aClass == _interface) {
                                    _throw = false;
                                }
                            }
                        }

                        if (_throw) {
                            throw new CriticalException(e);
                        }
                    }
                }
            }
        }

        for (Method method : nativeClass.getDeclaredMethods()) {
            if ((scope == null || !scope.isModernClassLookup()) && php.runtime.PhpLazyObjects.modernReflectionMember(nativeClass,method.getName())) continue;
            if (method.isAnnotationPresent(Reflection.Ignore.class)) continue;
            Reflection.Signature signature = getSignature(method);

            if (signature != null
                    || method.isAnnotationPresent(Reflection.Getter.class)
                    || method.isAnnotationPresent(Reflection.Setter.class)){
                Class<?>[] types = method.getParameterTypes();

                if (method.getReturnType() == Memory.class
                        && types.length == 2 && types[0] == Environment.class && types[1] == Memory[].class) {
                    onWrapMethod(classEntity, method);
                } else {
                    onWrapCompileMethod(classEntity, method);
                }
                if (signature != null && (nativeClass.isAnnotationPresent(Reflection.NativePhpSignature.class)
                        || scope != null && scope.isModernClassLookup() && php.runtime.PhpLazyObjects.modernReflectionSignature(nativeClass,method.getName()))) {
                    Reflection.Name named = method.getAnnotation(Reflection.Name.class);
                    MethodEntity wrapped = classEntity.findMethod((named == null ? method.getName() : named.value()).toLowerCase(java.util.Locale.ROOT));
                    Reflection.Arg[] arguments=signature.value();
                    boolean explicitParameters=java.util.Arrays.stream(arguments).anyMatch(arg->!arg.typeDeclaration().isEmpty());
                    if(explicitParameters) {
                        ParameterEntity[] parameters=wrapped.getParameters();
                        if(parameters==null || parameters.length!=arguments.length)
                            throw new IllegalArgumentException("Explicit native PHP parameters require exact declared method arity");
                        for(int i=0;i<arguments.length;i++) if(!arguments[i].typeDeclaration().isEmpty()) {
                            Reflection.Arg argument=arguments[i];
                            if(argument.type()!=HintType.ANY || !argument.typeClass().isEmpty()
                                    || argument.typeEnum()!=Enum.class || argument.nativeType()!=IObject.class)
                                throw new IllegalArgumentException("Conflicting explicit native PHP parameter type");
                            var declaration=nativeSignatureDeclaration(argument.typeDeclaration(),false);
                            parameters[i].setTypeChecker(php.runtime.reflection.support.PhpTypeDeclaration.fromChecker(declaration,argument.nullable()));
                        }
                    }
                    Reflection.Arg result = signature.result();
                    String explicit=signature.returnType().isEmpty()?result.typeDeclaration():signature.returnType();
                    if(!signature.returnType().isEmpty() && !result.typeDeclaration().isEmpty())
                        throw new IllegalArgumentException("Conflicting explicit native PHP return type");
                    if(!explicit.isEmpty()) {
                        if(result.type()!=HintType.ANY || !result.typeClass().isEmpty() || result.typeEnum()!=Enum.class || result.nativeType()!=IObject.class)
                            throw new IllegalArgumentException("Conflicting explicit native PHP return type");
                        wrapped.setReturnTypeChecker(php.runtime.reflection.support.PhpTypeDeclaration.fromChecker(nativeSignatureDeclaration(explicit,true),result.nullable()));
                        wrapped.setReturnTypeNullable(result.nullable());
                    } else if (result.type() != HintType.ANY || !result.typeClass().isEmpty()) {
                        var checker=result.typeClass().isEmpty()?php.runtime.reflection.support.TypeChecker.of(result.type())
                                :php.runtime.reflection.support.TypeChecker.of(result.typeClass());
                        wrapped.setReturnTypeChecker(php.runtime.reflection.support.PhpTypeDeclaration.fromChecker(checker,result.nullable()));
                        wrapped.setReturnTypeNullable(result.nullable());
                    }
                }
            }
        }
    }

    /** Annotation names are already resolved; parse their complete PHP union/intersection shape before relation checks. */
    private static php.runtime.reflection.support.PhpTypeDeclaration nativeSignatureDeclaration(String text,boolean result) {
        if(text==null || text.isEmpty() || text.length()>32768)throw new IllegalArgumentException("Invalid native PHP type declaration");
        text=text.trim();boolean nullable=text.startsWith("?");if(nullable)text=text.substring(1);
        String[] branches=text.split("\\|",-1);
        if(branches.length>64 || nullable && (branches.length!=1 || text.indexOf('&')>=0))
            throw new IllegalArgumentException("Invalid native PHP nullable type shape");
        java.util.List<java.util.List<String>> alternatives=new java.util.ArrayList<>();java.util.Set<String> seen=new java.util.HashSet<>();
        for(String raw:branches) {
            String branch=raw.trim();boolean grouped=branch.startsWith("(") && branch.endsWith(")");
            if(grouped)branch=branch.substring(1,branch.length()-1).trim();
            String[] members=branch.split("&",-1);
            if(members.length>32 || grouped && (branches.length<2 || members.length<2) || !grouped && branches.length>1 && members.length>1)
                throw new IllegalArgumentException("Invalid native PHP DNF type shape");
            java.util.List<String> conjunction=new java.util.ArrayList<>();java.util.Set<String> names=new java.util.HashSet<>();
            for(String value:members) {
                String name=value.trim();
                if(!name.matches("\\\\?[a-zA-Z_\\x80-\\uFFFF][a-zA-Z0-9_\\x80-\\uFFFF]*(?:\\\\[a-zA-Z_\\x80-\\uFFFF][a-zA-Z0-9_\\x80-\\uFFFF]*)*"))
                    throw new IllegalArgumentException("Invalid native PHP resolved type name");
                if(name.startsWith("\\"))name=name.substring(1);
                String lower=name.toLowerCase(java.util.Locale.ROOT);
                if(php.runtime.reflection.support.PhpTypeDeclaration.BUILTINS.contains(lower) || lower.equals("self") || lower.equals("parent") || lower.equals("static"))name=lower;
                if(!names.add(php.runtime.common.PhpClassNames.canonicalKey(name)))throw new IllegalArgumentException("Duplicate native PHP intersection type");
                boolean builtin=php.runtime.reflection.support.PhpTypeDeclaration.BUILTINS.contains(name);
                if(members.length>1 && (builtin || name.equals("self") || name.equals("parent") || name.equals("static")))
                    throw new IllegalArgumentException("Invalid native PHP intersection member");
                if((name.equals("mixed") || name.equals("void") || name.equals("never")) && (branches.length>1 || nullable)
                        || nullable && name.equals("null")
                        || !result && (name.equals("void") || name.equals("never") || name.equals("static")))
                    throw new IllegalArgumentException("Invalid native PHP declaration type context");
                conjunction.add(name);
            }
            java.util.List<String> identity=new java.util.ArrayList<>(names);java.util.Collections.sort(identity);
            if(!seen.add(String.join("&",identity)))throw new IllegalArgumentException("Duplicate native PHP union type");
            alternatives.add(conjunction);
        }
        var declaration=new php.runtime.reflection.support.PhpTypeDeclaration(alternatives);
        if(declaration.has("true") && declaration.has("false") || declaration.has("bool") && (declaration.has("true") || declaration.has("false")))
            throw new IllegalArgumentException("Redundant native PHP boolean union");
        return php.runtime.reflection.support.PhpTypeDeclaration.fromChecker(declaration,nullable);
    }

    protected void onWrapExtend(ClassEntity classEntity) {
        if (nativeClass.isAnnotationPresent(Reflection.BaseType.class)) {
            return;
        }

        Class<?> extend = nativeClass.getSuperclass();
        if (extend != null && !extend.isAnnotationPresent(Reflection.Ignore.class)){
            String name = ReflectionUtils.getClassName(extend);

            ClassEntity entity = scope.fetchUserClass(name);
            if (extend.isAssignableFrom(IObject.class) && entity == null)
                throw new IllegalArgumentException("Class '"+name+"' not registered for '" + classEntity.getName() + "'");
            if (entity == null)
                return;

            ClassEntity.ExtendsResult result = classEntity.setParent(entity, false);
            result.check(null);
            classEntity.updateParentBody();
        }
    }

    protected void onWrapImplement(ClassEntity classEntity) {
        for (Class<?> interface_ : nativeClass.getInterfaces()){
            if (interface_.isAnnotationPresent(Reflection.Ignore.class)) continue;
            if (interface_.getPackage().getName().startsWith("java.")) continue;

            String name = ReflectionUtils.getClassName(interface_);

            ClassEntity entity = scope.fetchUserClass(name);
            /*if (entity == null || !entity.isInterface())
                throw new IllegalArgumentException("Interface '"+name+"' not registered");*/
            if (entity == null)
                return;

            boolean fullNativeSignature = nativeClass.isAnnotationPresent(Reflection.NativePhpSignature.class)
                    && entity.getNativeClass() != null
                    && entity.getNativeClass().isAnnotationPresent(Reflection.NativePhpSignature.class);
            ClassEntity.ImplementsResult result = fullNativeSignature
                    ? classEntity.addInterface(entity, (implementation, contract) -> implementation.isPublic()
                            && php.runtime.reflection.support.PhpMethodCompatibility.compatibleNative(implementation, contract, scope))
                    : classEntity.addInterface(entity);
            result.check(null);
        }
    }

    public void onWrap(ClassEntity classEntity) {
        int mod = nativeClass.getModifiers();

        classEntity.setInternal(true);
        classEntity.setNativeClazz(nativeClass);
        classEntity.setExtension(extension);

        classEntity.setType(nativeClass.isInterface() ? ClassEntity.Type.INTERFACE : ClassEntity.Type.CLASS);
        if (nativeClass.isAnnotationPresent(Reflection.Trait.class)) {
            classEntity.setType(ClassEntity.Type.TRAIT);
        }

        classEntity.setAbstract(Modifier.isAbstract(mod));
        classEntity.setFinal(Modifier.isFinal(mod));
        classEntity.setNotRuntime(nativeClass.isAnnotationPresent(Reflection.NotRuntime.class));

        if (nativeClass.isAnnotationPresent(Reflection.Final.class)) {
            classEntity.setFinal(true);
        }

        if (nativeClass.isAnnotationPresent(Reflection.Abstract.class)) {
            classEntity.setAbstract(true);
        }

        this.onWrapName(classEntity);
        if (!classEntity.isTrait()) {
            this.onWrapConstants(classEntity);
        }

        this.onWrapProperties(classEntity);

        // ---
        classEntity.setNativeClazz(nativeClass);
        // ---

        this.onWrapMethods(classEntity);

        if (this.scope != null)
            this.onWrapExtend(classEntity);

        this.onWrapImplement(classEntity);

        // An explicitly ignored override also hides the inherited PHP method.
        for (Method method : nativeClass.getDeclaredMethods()) if (method.isAnnotationPresent(Reflection.Ignore.class)) {
            Reflection.Name name = method.getAnnotation(Reflection.Name.class);
            classEntity.getMethods().remove((name == null ? method.getName() : name.value()).toLowerCase(java.util.Locale.ROOT));
        }
        classEntity.doneDeclare();
    }
}
