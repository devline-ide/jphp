package php.runtime.reflection;

import php.runtime.Memory;
import php.runtime.common.Messages;
import php.runtime.common.Modifier;
import php.runtime.env.Context;
import php.runtime.env.Environment;
import php.runtime.env.TraceInfo;
import php.runtime.exceptions.support.ErrorType;
import php.runtime.invoke.ObjectInvokeHelper;
import php.runtime.lang.IObject;
import php.runtime.memory.ArrayMemory;
import php.runtime.reflection.support.Entity;

import java.lang.reflect.Field;

public class PropertyEntity extends Entity {
    protected ClassEntity clazz;
    private php.runtime.reflection.support.PhpPropertyHooks phpHooks;
    public php.runtime.reflection.support.PhpPropertyHooks getPhpHooks() {return phpHooks;}
    public void setPhpHooks(php.runtime.reflection.support.PhpPropertyHooks hooks) {
        if (hooks!=null) hooks.validateFor(this);
        phpHooks=hooks;
    }
    /** Compiler-only initial catalog; final inheritance/storage validation precedes module publication. */
    public void installPhpHookDeclaration(php.runtime.reflection.support.PhpPropertyHooks hooks) {
        if(phpHooks!=null || hooks==null) throw new IllegalArgumentException("Hook declaration is already installed or absent");
        hooks.validateDeclarationFor(this);
        phpHooks=hooks;
    }
    protected ClassEntity trait;
    protected Modifier modifier = Modifier.PUBLIC;
    private Memory defaultValue;
    protected DocumentComment docComment;

    protected boolean isStatic;
    private boolean isFinal;
    private boolean promoted;
    private php.runtime.common.Modifier phpSetModifier;
    public php.runtime.common.Modifier getPhpSetModifier() {return phpSetModifier;}
    public void setPhpSetModifier(php.runtime.common.Modifier value) {phpSetModifier=value;}
    private boolean phpReadonly;
    public boolean isPhpReadonly() {return phpReadonly;}
    public void setPhpReadonly(boolean value) {phpReadonly=value;}
    public boolean isPromoted() {return promoted;}
    public void setPromoted(boolean value) {promoted=value;}
    private php.runtime.reflection.support.PhpTypeDeclaration declaredType;
    public Modifier effectiveSetModifier() {return phpSetModifier!=null?phpSetModifier:phpReadonly && modifier==Modifier.PUBLIC?Modifier.PROTECTED:modifier;}
    public boolean isPrivateSet() {return effectiveSetModifier()==Modifier.PRIVATE && modifier!=Modifier.PRIVATE;}
    public boolean isProtectedSet() {return effectiveSetModifier()==Modifier.PROTECTED && modifier==Modifier.PUBLIC;}
    public boolean isFinal() {return isFinal || phpSetModifier==Modifier.PRIVATE && modifier!=Modifier.PRIVATE;}
    public void setFinal(boolean value) {isFinal = value;}
    public php.runtime.reflection.support.PhpTypeDeclaration getDeclaredType() {return declaredType;}
    public void setDeclaredType(php.runtime.reflection.support.PhpTypeDeclaration type) {declaredType = type;}
    public boolean hasDefaultValue() {return declaredType == null || isDefault;}
    public Memory validateDefaultValue(Environment env, Memory value, ClassEntity owner, boolean compileTime) {
        if (declaredType == null || !isDefault) return value;
        Memory valid = declaredType.strictValue(env, value, owner);
        if (valid != null) return valid;
        String type = declaredType.getErrorSignature();
        if (compileTime && value.isNull() && (declaredType.getAlternatives().size() > 1 || declaredType.getAlternatives().getFirst().size() == 1)) {
            String nullable = declaredType.getAlternatives().size() == 1 && !declaredType.has("iterable") ? "?" + type : type + "|null";
            env.error(trace, ErrorType.E_COMPILE_ERROR,
                    "Default value for property of type %s may not be null. Use the nullable type %s to allow null default value", type, nullable);
        }
        String given = value.getRealType() == Memory.Type.BOOL ? (value.toBoolean() ? "true" : "false")
                : php.runtime.reflection.support.PhpTypeDeclaration.valueType(value);
        if (compileTime) env.error(trace, ErrorType.E_COMPILE_ERROR,
                "Cannot use %s as default value for property %s::$%s of type %s", given, owner.getName(), name, type);
        else env.exception(trace, php.runtime.lang.exception.BaseTypeError.class,
                "Cannot assign %s to property %s::$%s of type %s", given, owner.getName(), name, declaredType.bind(owner).getErrorSignature());
        return Memory.NULL;
    }
    protected Field field;

    protected String specificName;
    protected PropertyEntity prototype;
    protected boolean isDefault;

    protected MethodEntity getter;
    protected MethodEntity setter;

    protected boolean hiddenInDebugInfo = false;

    public PropertyEntity(Context context) {
        super(context);
    }

    public PropertyEntity getPrototype() {
        return prototype;
    }

    public void setPrototype(PropertyEntity prototype) {
        this.prototype = prototype;
    }

    public boolean isDefault() {
        return isDefault;
    }

    public void setDefault(boolean aDefault) {
        isDefault = aDefault;
    }

    public Field getField() {
        return field;
    }

    public void setField(Field field) {
        field.setAccessible(true);
        this.field = field;
    }

    public DocumentComment getDocComment() {
        return docComment;
    }

    public void setDocComment(DocumentComment docComment) {
        this.docComment = docComment;
    }

    public boolean isHiddenInDebugInfo() {
        return hiddenInDebugInfo;
    }

    public void setHiddenInDebugInfo(boolean hiddenInDebugInfo) {
        this.hiddenInDebugInfo = hiddenInDebugInfo;
    }

    public Memory getDefaultValue(){
        return defaultValue;
    }

    public String getDynamicDefaultName() {
        return isStatic && clazz != null && clazz.hasDeferredInitialization() ? "\0property-default:" + specificName
                : isStatic ? specificName : internalName;
    }

    public Memory getDefaultValue(Environment env) {
        if (clazz != null) clazz.initializeRuntimeState(env);
        Memory value;
        if (defaultValue == null) {
            Memory r = env.getStatic(getDynamicDefaultName());
            value = r == null ? Memory.NULL : clazz != null && clazz.hasDeferredInitialization() ? r.toValue() : r;
        } else {
            value = defaultValue;
        }
        // Defaults follow the declaring module's strict rules, regardless of the caller.
        if (declaredType != null && isDefault && clazz != null && clazz.getModule() != null
                && clazz.getModule().getPhpLanguageProfile() == php.runtime.common.PhpLanguageProfile.PHP_8_5) {
            if (value instanceof php.runtime.memory.helper.ConstantMemory
                    || value instanceof php.runtime.memory.helper.ClassConstantMemory)
                value = value.toImmutable(env, trace);
            return validateDefaultValue(env, value.toValue(), clazz, false);
        }
        return value;
    }

    public void setDefaultValue(Memory defaultValue) {
        this.defaultValue = defaultValue;
    }

    public Modifier getModifier() {
        return modifier;
    }

    public boolean isPrivate(){
        return modifier == Modifier.PRIVATE;
    }

    public boolean isProtected(){
        return modifier == Modifier.PROTECTED;
    }

    public boolean isPublic(){
        return modifier == Modifier.PUBLIC;
    }

    public void setModifier(Modifier modifier) {
        this.modifier = modifier;
        updateSpecificName();
    }

    public boolean isStatic() {
        return isStatic;
    }

    public void setStatic(boolean aStatic) {
        isStatic = aStatic;
    }

    public ClassEntity getClazz() {
        return clazz;
    }

    public void setClazz(ClassEntity clazz) {
        this.clazz = clazz;
        if(phpHooks!=null && clazz!=null) phpHooks.bindOwned(this);
        updateSpecificName();
    }

    public boolean isDeprecated(){
        return false; // TODO
    }

    public void updateSpecificName(){
        switch (modifier){
            case PRIVATE: if (clazz != null) { specificName = "\0" + clazz.getName() + "\0" + name; } break;
            case PROTECTED: specificName = "\0*\0" + name; break;
            default:
                specificName = name;
        }

        if (isStatic && clazz != null)
            specificName = "\0" + php.runtime.common.PhpClassNames.canonicalKey(clazz.getName()) + "#" + specificName;

        if (clazz != null)
            internalName = "\0" + php.runtime.common.PhpClassNames.canonicalKey(clazz.getName()) + "\0#" + name;
    }

    @Override
    public void setName(String name) {
        super.setName(name);
        updateSpecificName();
    }

    public String getSpecificName() {
        return specificName;
    }

    public ClassEntity getTrait() {
        return trait;
    }

    public void setTrait(ClassEntity trait) {
        this.trait = trait;
    }

    public boolean isOwned(ClassEntity entity){
        return clazz.getId() == entity.getId();
    }

    public int canAccess(Environment env) {
        return canAccess(env, null, false);
    }

    public MethodEntity getGetter() {
        return getter;
    }

    public void setGetter(MethodEntity getter) {
        this.getter = getter;
    }

    public MethodEntity getSetter() {
        return setter;
    }

    public void setSetter(MethodEntity setter) {
        this.setter = setter;
    }


    public int canAccess(Environment env, ClassEntity context) {
        return canAccess(env, context, false);
    }

    /**
     * 0 - success
     * 1 - invalid protected
     * 2 - invalid private
     * @param env
     * @return
     */
    public int canAccess(Environment env, ClassEntity context, boolean lateStaticCall) {
        switch (modifier){
            case PUBLIC: return 0;
            case PRIVATE:
                ClassEntity cl = context == null ? (lateStaticCall ? env.getLateStaticClass() : env.getLastClassOnStack()) : context;
                return cl != null && cl.getId() == this.clazz.getId() ? 0 : 2;
            case PROTECTED:
                ClassEntity clazz = context == null ? (lateStaticCall ? env.getLateStaticClass() : env.getLastClassOnStack()) : context;
                if (clazz == null)
                    return 1;

                long id = this.clazz.getId();
                do {
                    if (clazz.getId() == id)
                        return 0;
                    clazz = clazz.parent;
                } while (clazz != null);

                return 1;
        }

        return 2;
    }

    public boolean canAccessAsNonStatic(Environment env, TraceInfo trace){
        if (isStatic){
            env.error(
                    trace, ErrorType.E_STRICT, Messages.ERR_ACCESSING_STATIC_PROPERTY_AS_NON_STATIC,
                    getClazz().getName(), name
            );
            return false;
        }
        return true;
    }

    public boolean isReadOnly() {
        return getter == null && setter != null;
    }

    public PropertyEntity duplicate() {
        PropertyEntity propertyEntity = new PropertyEntity(context);
        propertyEntity.setPhpAttributes(getPhpAttributes());
        propertyEntity.setStatic(isStatic);
        propertyEntity.setFinal(isFinal);
        propertyEntity.setPromoted(promoted);
        propertyEntity.setPhpReadonly(phpReadonly);
        propertyEntity.setPhpSetModifier(phpSetModifier);
        propertyEntity.setDeclaredType(declaredType);
        propertyEntity.setDocComment(docComment);
        propertyEntity.setName(name);
        propertyEntity.setDefault(isDefault);
        propertyEntity.setDefaultValue(defaultValue);
        propertyEntity.setModifier(modifier);
        propertyEntity.setPrototype(propertyEntity);
        propertyEntity.setTrace(trace);
        propertyEntity.setGetter(getter);
        propertyEntity.setSetter(setter);
        propertyEntity.setPhpHooks(phpHooks);
        return propertyEntity;
    }

    public Memory assignValue(Environment env, TraceInfo trace, Object object, String name, Memory value) {
        if(object instanceof php.runtime.lang.IPhpNativePropertyGuard guard) guard.validateNativePropertyWrite(env,trace,name);
        object=php.runtime.PhpLazyObjects.backingUnchecked(env,(IObject)object,specificName);
        php.runtime.memory.ReferenceMemory reference=(php.runtime.memory.ReferenceMemory)((IObject) object).getProperties().refOfIndex(name);
        reference.attachPropertySource(env, this,(IObject)object);
        return phpReadonly ? php.runtime.PhpReadonlyProperty.reflectAssign(env,trace,this,(IObject)object,reference,value) : reference.assign(value);
    }

    public Memory getStaticValue(Environment env, TraceInfo trace) {
        var value=env.getOrCreateStatic(specificName, getDefaultValue(env).toImmutable());
        ((php.runtime.memory.ReferenceMemory)value).attachPropertySource(env, this);
        return value;
    }

    public Memory readInitialized(Environment env, TraceInfo trace, Memory value) {
        if (declaredType != null && (value == null || value.isUndefined()))
            env.exception(trace, php.runtime.lang.exception.BaseError.class,
                    "Typed %sproperty %s::$%s must not be accessed before initialization", isStatic ? "static " : "", clazz.getName(), name);
        return value == null ? Memory.NULL : value;
    }

    public Memory getValue(Environment env, TraceInfo trace, Object object) throws Throwable {
        if (getter != null && object instanceof IObject) {
            return ObjectInvokeHelper.invokeMethod((IObject) object, getter, env, trace, null, false);
        }

        ArrayMemory props = php.runtime.PhpLazyObjects.backing(env,(IObject)object,specificName).getProperties();

        Memory result = props.getByScalar(specificName);
        if (result == null && declaredType == null) {
            result = props.getByScalar(name);
        }
        return result;
    }
}
