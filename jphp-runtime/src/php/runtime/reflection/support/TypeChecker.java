package php.runtime.reflection.support;

import php.runtime.common.PhpClassNames;

import php.runtime.Memory;
import php.runtime.common.HintType;
import php.runtime.common.StringUtils;
import php.runtime.env.Environment;
import php.runtime.invoke.Invoker;
import php.runtime.lang.BaseWrapper;
import php.runtime.lang.IObject;
import php.runtime.lang.Closure;
import php.runtime.memory.*;
import php.runtime.reflection.ClassEntity;

import java.lang.reflect.Field;

abstract public class TypeChecker {
    abstract public String getSignature();
    abstract public String getHumanString();

    abstract public boolean check(Environment env, Memory value, boolean nullable, String staticClassName);
    abstract public Memory apply(Environment env, Memory value, boolean nullable, boolean strict);
    public boolean isBuiltin() {
        return false;
    }

    public static TypeChecker of(HintType type) {
        return new Simple(type);
    }

    public static TypeChecker of(String className) {
        return new ClassName(className);
    }

    public static TypeChecker of(Class<?> typeNativeClass) {
        return new NativeClass(typeNativeClass);
    }

    public static TypeChecker ofEnum(Class<? extends Enum> enumClass) {
        return new EnumClass(enumClass);
    }

    public static class Simple extends TypeChecker {
        protected HintType type;

        public Simple(HintType type) {
            this.type = type;
        }

        public HintType getType() {
            return type;
        }

        @Override
        public String getSignature() {
            return type.toString();
        }

        @Override
        public String getHumanString() {
            return "the type " + type.toString();
        }

        @Override
        public boolean isBuiltin() {
            return type != HintType.SELF && type != HintType.PARENT && type != HintType.STATIC;
        }

        @Override
        public Memory apply(Environment env, Memory value, boolean nullable, boolean strict) {
            if (nullable && value.isNull()) {
                return value;
            }

            if (strict) {
                switch (type) {
                    case DOUBLE:
                        if (value.isNumber()) {
                            return DoubleMemory.valueOf(value.toDouble());
                        }
                    default:
                        return null;
                }
            }

            switch (type) {
                case INT:
                    Memory numeric = value.isString() ? StringMemory.toNumericStrict(value.toString())
                            : value.isNumber() || value.getRealType() == Memory.Type.BOOL ? value.toValue() : null;
                    if (numeric == null) return null;
                    if (numeric.getRealType() == Memory.Type.DOUBLE) {
                        double number = numeric.toDouble();
                        if (!Double.isFinite(number) || number < -0x1p63 || number >= 0x1p63) return null;
                    }
                    return LongMemory.valueOf(numeric.toLong());
                case STRING:
                    if (!value.isString() && !value.isNumber() && value.getRealType() != Memory.Type.BOOL
                            && !(value.isObject() && value.toValue(ObjectMemory.class).getReflection().methodMagicToString != null)) {
                        return null;
                    }
                    return StringMemory.valueOf(value.toString());
                case BOOLEAN:
                    if (!value.isString() && !value.isNumber() && value.getRealType() != Memory.Type.BOOL) return null;
                    return TrueMemory.valueOf(value.toBoolean());
                case DOUBLE:
                    Memory number = value.isString() ? StringMemory.toNumericStrict(value.toString())
                            : value.isNumber() || value.getRealType() == Memory.Type.BOOL ? value.toValue() : null;
                    if (number == null) return null;
                    return DoubleMemory.valueOf(number.toDouble());
            }

            return null;
        }

        @Override
        public boolean check(Environment env, Memory value, boolean nullable, String staticClassName) {
            if (type == HintType.VOID) {
                // VOID must check compiler!
                return true;
            }

            if (nullable && value.isNull())
                return true;

            switch (type){
                case MIXED: return true;
                case NEVER: return false;
                case STATIC:
                    ClassEntity calledClass = env.getLateStaticClass();
                    return value.isObject() && calledClass != null
                            && value.toValue(ObjectMemory.class).getReflection().isInstanceOf(calledClass);
                case PARENT:
                    if (!value.isObject()) return false;
                    String ownerName = staticClassName;
                    if (ownerName == null) {
                        IObject lateObject = env.getLateObject();
                        ownerName = lateObject instanceof Closure && ((Closure) lateObject).hasPhp8Binding()
                                ? ((Closure) lateObject).getScope() : env.__getMacroClass().toString();
                        if (lateObject instanceof Closure && ((Closure) lateObject).hasPhp8Binding() && ownerName == null)
                            env.error(env.trace(), "Cannot access parent when no class scope is active");
                    }
                    ClassEntity owner = ownerName == null ? null : env.fetchClass(ownerName, false);
                    if (ownerName != null && ownerName.isEmpty()) env.error(env.trace(), "Cannot access parent when no class scope is active");
                    if (owner != null && owner.getParent() == null) env.error(env.trace(), "Cannot access parent when current class scope has no parent");
                    return owner != null && owner.getParent() != null && value.isObject()
                            && value.toValue(ObjectMemory.class).getReflection().isInstanceOf(owner.getParent());
                /*case VOID:
                    return value.isUndefined();*/
                case OBJECT: return value.isObject();
                case NUMBER: return value.isNumber();
                case DOUBLE: return value.getRealType() == Memory.Type.DOUBLE;
                case INT: return value.getRealType() == Memory.Type.INT;
                case STRING: return value.isString();
                case BOOLEAN: return value.getRealType() == Memory.Type.BOOL;
                case ARRAY:
                    return value.isArray();
                case TRAVERSABLE:
                    return value.isTraversable();
                case ITERABLE:
                    return value.isTraversable();
                case CALLABLE:
                    Invoker invoker = Invoker.valueOf(env, null, value);
                    return invoker != null && invoker.canAccess(env) == 0;
                case SELF:
                    if (!value.isObject()) {
                        return false;
                    }

                    if (staticClassName == null) {
                        IObject lateObject = env.getLateObject();
                        if (lateObject instanceof Closure && ((Closure) lateObject).hasPhp8Binding()) {
                            staticClassName = ((Closure) lateObject).getScope();
                            if (staticClassName == null) env.error(env.trace(), "Cannot access self when no class scope is active");
                        } else {
                            Memory memory = env.__getMacroClass();
                            staticClassName = memory.isNull() ? null : memory.toString();
                        }
                    }

                    if (staticClassName != null) {
                        if (staticClassName.isEmpty()) env.error(env.trace(), "Cannot access self when no class scope is active");
                        return value.toValue(ObjectMemory.class)
                                .getReflection()
                                .isInstanceOf(staticClassName);
                    }

                    return true;
                default:
                    return true;
            }
        }
    }

    public static class ClassName extends TypeChecker {
        protected String typeClass;
        protected String typeClassLower;

        public ClassName(String typeClass) {
            this.typeClass = typeClass;
            this.typeClassLower = typeClass.toLowerCase();
        }

        public String getTypeClass() {
            return typeClass;
        }

        public String getTypeClassLower() {
            return typeClassLower;
        }

        @Override
        public String getSignature() {
            return typeClass;
        }

        @Override
        public String getHumanString() {
            return "an instance of " + getTypeClass();
        }

        @Override
        public boolean check(Environment env, Memory value, boolean nullable, String staticClassName) {
            if (nullable && value.isNull())
                return true;

            if (!value.isObject())
                return false;

            ObjectMemory object = value.toValue(ObjectMemory.class);
            ClassEntity oEntity = object.getReflection();

            return PhpClassNames.isModern(env) || PhpClassNames.isModern(oEntity)
                    ? PhpClassNames.matches(oEntity, typeClass, env) : oEntity.isInstanceOfLower(typeClassLower);
        }

        @Override
        public Memory apply(Environment env, Memory value, boolean nullable, boolean strict) {
            return null;
        }
    }

    public static class NativeClass extends TypeChecker {
        protected Class<?> typeNativeClass;

        public NativeClass(Class<?> typeNativeClass) {
            this.typeNativeClass = typeNativeClass;
        }

        @Override
        public String getSignature() {
            return ReflectionUtils.getClassName(typeNativeClass);
        }

        @Override
        public String getHumanString() {
            return "an instance of " + ReflectionUtils.getClassName(typeNativeClass);
        }

        @Override
        public boolean check(Environment env, Memory value, boolean nullable, String staticClassName) {
            if (nullable && value.isNull()) {
                return true;
            }

            if (value.isObject()) {
                IObject object = value.toObject(IObject.class);

                if (object instanceof BaseWrapper) {
                    return typeNativeClass.isAssignableFrom(((BaseWrapper) object).getWrappedObject().getClass());
                }

                return false;
            } else {
                return false;
            }
        }

        @Override
        public Memory apply(Environment env, Memory value, boolean nullable, boolean strict) {
            return null;
        }
    }

    public static class EnumClass extends TypeChecker {
        protected Class<? extends Enum> typeEnum;

        public EnumClass(Class<? extends Enum> typeEnum) {
            this.typeEnum = typeEnum;
        }

        public Class<? extends Enum> getTypeEnum() {
            return typeEnum;
        }

        @Override
        public String getSignature() {
            return null;
        }

        @Override
        public String getHumanString() {
            Field[] fields = getTypeEnum().getFields();
            String[] names = new String[fields.length];

            for (int i = 0; i < names.length; i++) {
                names[i] = fields[i].getName();
            }

            return "one of range [" + StringUtils.join(names, ", ") + "] as string";
        }

        @Override
        public boolean check(Environment env, Memory value, boolean nullable, String staticClassName) {
            try {
                if (nullable && value.isNull()) {
                    return true;
                }

                Enum.valueOf(typeEnum, value.toString());
                return true;
            } catch (IllegalArgumentException e) {
                return false;
            }
        }

        @Override
        public Memory apply(Environment env, Memory value, boolean nullable, boolean strict) {
            return null;
        }
    }
}
