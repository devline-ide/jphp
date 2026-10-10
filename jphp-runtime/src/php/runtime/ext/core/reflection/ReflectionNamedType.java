package php.runtime.ext.core.reflection;

import php.runtime.Memory;
import php.runtime.annotation.Reflection.*;
import php.runtime.env.Environment;
import php.runtime.memory.*;
import php.runtime.reflection.ClassEntity;
import php.runtime.reflection.support.PhpTypeDeclaration;
import java.util.List;

@Name("ReflectionNamedType")
public class ReflectionNamedType extends ReflectionType {
    private PhpTypeDeclaration type;
    public ReflectionNamedType(Environment env, PhpTypeDeclaration type) {
        super(env, type.getNamedType(), type.allowsNull(), type.isBuiltin());
        this.type = type;
    }
    public ReflectionNamedType(Environment env, ClassEntity clazz) { super(env, clazz); }
    @Override @Signature public Memory allowsNull(Environment env, Memory... args) { initialized(env, type); return super.allowsNull(env, args); }
    @Override @Signature public Memory getName() { initialized(getEnvironment(), type); return super.getName(); }
    @Override @Signature public Memory isBuiltin(Environment env, Memory... args) { initialized(env, type); return super.isBuiltin(env, args); }
    @Override @Ignore public Memory __construct(Environment env, Memory... args) { return Memory.UNDEFINED; }
    @Override @Signature public Memory __toString(Environment env, Memory... args) { return StringMemory.valueOf(initialized(env, type).getSignature()); }
}
