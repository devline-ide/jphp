package php.runtime.ext.core.reflection;

import php.runtime.Memory;
import php.runtime.annotation.Reflection.*;
import php.runtime.env.Environment;
import php.runtime.memory.*;
import php.runtime.reflection.ClassEntity;
import php.runtime.reflection.support.PhpTypeDeclaration;
import java.util.List;

@Name("ReflectionIntersectionType")
public class ReflectionIntersectionType extends ReflectionType {
    private PhpTypeDeclaration type;
    public ReflectionIntersectionType(Environment env, PhpTypeDeclaration type) {
        super(env, type.getSignature(), type.allowsNull(), false);
        this.type = type;
    }
    public ReflectionIntersectionType(Environment env, ClassEntity clazz) { super(env, clazz); }
    @Override @Signature public Memory allowsNull(Environment env, Memory... args) { initialized(env, type); return super.allowsNull(env, args); }
    @Override @Ignore public Memory __construct(Environment env, Memory... args) { return Memory.UNDEFINED; }
    @Override @Signature public Memory __toString(Environment env, Memory... args) { return StringMemory.valueOf(initialized(env, type).getSignature()); }
    @Override @Ignore public Memory getName() { return super.getName(); }
    @Override @Ignore public Memory isBuiltin(Environment env, Memory... args) { return super.isBuiltin(env, args); }
    @Signature public Memory getTypes(Environment env) {
        initialized(env, type);
        ArrayMemory result = new ArrayMemory();
        for (String name : type.getAlternatives().getFirst()) result.add(ObjectMemory.valueOf(new ReflectionNamedType(env, new PhpTypeDeclaration(List.of(List.of(name))))));
        return result.toConstant();
    }
}
