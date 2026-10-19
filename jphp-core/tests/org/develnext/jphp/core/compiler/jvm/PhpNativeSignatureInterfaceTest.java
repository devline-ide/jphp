package org.develnext.jphp.core.compiler.jvm;

import java.util.List;
import org.junit.Test;
import php.runtime.Memory;
import php.runtime.annotation.Reflection.*;
import php.runtime.common.HintType;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.*;
import php.runtime.lang.*;
import php.runtime.memory.ObjectMemory;
import php.runtime.reflection.*;
import php.runtime.reflection.support.PhpTypeDeclaration;
import php.runtime.wrap.ClassWrapper;
import static org.junit.Assert.*;

/** Complete native PHP declarations must exist before actual interface compatibility; ordinary legacy wrappers are unchanged. */
public class PhpNativeSignatureInterfaceTest {
    @Name("HookNSContract") @NativePhpSignature
    public interface Contract extends IObject {
        @Signature(value=@Arg(value="value",typeDeclaration="string|int"),result=@Arg(typeClass="static",nullable=true))
        Memory choose(Environment env,Memory...arguments);
    }
    @Name("HookNSGood") @NativePhpSignature
    public static class Good extends BaseObject implements Contract {
        public Good(Environment env,ClassEntity clazz){super(env,clazz);}
        @Signature(value=@Arg(value="value",typeDeclaration="string|int"),result=@Arg(typeClass="static",nullable=true))
        public Memory choose(Environment env,Memory...arguments){return ObjectMemory.valueOf(this);}
    }
    @Name("HookNSWider") @NativePhpSignature
    public static class Wider extends BaseObject implements Contract {
        public Wider(Environment env,ClassEntity clazz){super(env,clazz);}
        @Signature(value=@Arg(value="value",typeDeclaration="mixed"),result=@Arg(typeClass="static"))
        public Memory choose(Environment env,Memory...arguments){return ObjectMemory.valueOf(this);}
    }
    @Name("HookNSAnimal") public static class Animal extends BaseObject {
        public Animal(Environment env,ClassEntity clazz){super(env,clazz);}
    }
    @Name("HookNSDog") public static class Dog extends Animal {
        public Dog(Environment env,ClassEntity clazz){super(env,clazz);}
    }
    @Name("HookNSNamedContract") @NativePhpSignature public interface NamedContract extends IObject {
        @Signature(value=@Arg(value="value",typeDeclaration="HookNSDog"),returnType="HookNSAnimal")
        Memory choose(Environment env,Memory...arguments);
    }
    @Name("HookNSNamedGood") @NativePhpSignature public static class NamedGood extends BaseObject implements NamedContract {
        public NamedGood(Environment env,ClassEntity clazz){super(env,clazz);}
        @Signature(value=@Arg(value="value",typeDeclaration="HookNSAnimal"),returnType="HookNSDog")
        public Memory choose(Environment env,Memory...arguments){return Memory.NULL;}
    }
    @Name("HookNSBadParameter") @NativePhpSignature
    public static class BadParameter extends BaseObject implements Contract {
        public BadParameter(Environment env,ClassEntity clazz){super(env,clazz);}
        @Signature(value=@Arg(value="value",typeDeclaration="int"),result=@Arg(typeClass="static",nullable=true))
        public Memory choose(Environment env,Memory...arguments){return ObjectMemory.valueOf(this);}
    }
    @Name("HookNSBadReturn") @NativePhpSignature
    public static class BadReturn extends BaseObject implements Contract {
        public BadReturn(Environment env,ClassEntity clazz){super(env,clazz);}
        @Signature(value=@Arg(value="value",typeDeclaration="string|int"),result=@Arg(type=HintType.STRING))
        public Memory choose(Environment env,Memory...arguments){return Memory.NULL;}
    }
    @Name("HookNSConflicting") @NativePhpSignature
    public static class Conflicting extends BaseObject {
        public Conflicting(Environment env,ClassEntity clazz){super(env,clazz);}
        @Signature(value=@Arg(value="value",type=HintType.INT,typeDeclaration="string|int"))
        public Memory conflicting(Environment env,Memory...arguments){return Memory.NULL;}
    }
    @Name("HookNSLegacyAlias")
    public static class LegacyAlias extends BaseObject {
        public LegacyAlias(Environment env,ClassEntity clazz){super(env,clazz);}
        @Name("alias") @Signature(value=@Arg(value="value",typeDeclaration="string|int"),result=@Arg(typeClass="static",nullable=true))
        public Memory javaName(Environment env,Memory...arguments){return Memory.NULL;}
    }
    @Name("HookNSDnf") @NativePhpSignature
    public static class Dnf extends BaseObject {
        public Dnf(Environment env,ClassEntity clazz){super(env,clazz);}
        @Signature(value=@Arg(value="value",typeDeclaration="(One&Two)|null"),returnType="string|false")
        public Memory declaration(Environment env,Memory...arguments){return Memory.FALSE;}
    }
    @Name("HookNSMalformed") @NativePhpSignature
    public static class Malformed extends BaseObject {
        public Malformed(Environment env,ClassEntity clazz){super(env,clazz);}
        @Signature(value=@Arg(value="value",typeDeclaration="mixed|string"))
        public Memory declaration(Environment env,Memory...arguments){return Memory.NULL;}
    }
    private CompileScope scope() {
        var scope=new CompileScope();scope.registerLazyClass(null,Contract.class);
        scope.registerLazyClass(null,Animal.class);scope.registerLazyClass(null,Dog.class);scope.registerLazyClass(null,NamedContract.class);return scope;
    }
    private ClassEntity wrap(CompileScope scope,Class<?> nativeClass){return new ClassEntity(new ClassWrapper(scope,nativeClass));}
    @Test public void fullParameterUnionAndNullableStaticResultPrecedeNativeInterfaceValidation() {
        var scope=scope();ClassEntity good=wrap(scope,Good.class);assertTrue(good.isInstanceOfAsciiName("HookNSContract"));
        MethodEntity child=good.findMethod("choose");assertEquals("string|int",child.getParameters()[0].getTypeChecker().getSignature());
        assertTrue(child.getReturnTypeChecker() instanceof PhpTypeDeclaration);assertTrue(((PhpTypeDeclaration)child.getReturnTypeChecker()).has("static"));
        assertTrue(((PhpTypeDeclaration)child.getReturnTypeChecker()).allowsNull());assertTrue(child.isReturnTypeNullable());
        assertSame(Good.class,child.getNativeMethod().getDeclaringClass());assertEquals("choose",child.getNativeMethod().getName());
        assertNotNull(child.getPrototype());assertEquals("string|int",child.getPrototype().getParameters()[0].getTypeChecker().getSignature());
        assertTrue(((PhpTypeDeclaration)child.getPrototype().getReturnTypeChecker()).allowsNull());
        MethodEntity wider=wrap(scope,Wider.class).findMethod("choose");assertEquals("mixed",wider.getParameters()[0].getTypeChecker().getSignature());
        assertFalse(((PhpTypeDeclaration)wider.getReturnTypeChecker()).allowsNull());
        MethodEntity named=wrap(scope,NamedGood.class).findMethod("choose");assertEquals("HookNSAnimal",named.getParameters()[0].getTypeChecker().getSignature());
        assertEquals("HookNSDog",named.getReturnTypeChecker().getSignature());assertEquals("HookNSDog",named.getPrototype().getParameters()[0].getTypeChecker().getSignature());
    }
    @Test public void nativeParameterNarrowingAndIncompatibleReturnRemainRejected() {
        for(Class<?> bad:List.of(BadParameter.class,BadReturn.class)) {
            try{wrap(scope(),bad);fail("Invalid native interface implementation accepted "+bad.getName());}
            catch(php.runtime.exceptions.FatalException expected){assertTrue(expected.getMessage(),expected.getMessage().contains("must be compatible with HookNSContract::choose"));}
        }
    }
    @Test public void explicitDnfReturnAndMalformedOrConflictingAnnotationsHaveRealMetadata() {
        var scope=scope();MethodEntity method=wrap(scope,Dnf.class).findMethod("declaration");
        var parameter=(PhpTypeDeclaration)method.getParameters()[0].getTypeChecker();assertEquals(List.of(List.of("One","Two"),List.of("null")),parameter.getAlternatives());
        assertEquals("string|false",method.getReturnTypeChecker().getSignature());
        for(Class<?> bad:List.of(Conflicting.class,Malformed.class)) {
            try{wrap(scope,bad);fail("Malformed/conflicting native type accepted");}catch(IllegalArgumentException expected){assertTrue(expected.getMessage().contains("native PHP"));}
        }
    }
    @Test public void nonNativePhpSignatureLegacyAliasesRetainOriginalUntypedMetadataBothProfiles() {
        for(PhpLanguageProfile profile:PhpLanguageProfile.values()) {
            var scope=scope();scope.setPhpLanguageProfile(profile);ClassEntity legacy=wrap(scope,LegacyAlias.class);MethodEntity method=legacy.findMethod("alias");
            assertNotNull(method);assertNull(legacy.findMethod("javaname"));assertTrue(method.getParameters()[0].getTypeChecker() instanceof php.runtime.reflection.support.TypeChecker.Simple);assertEquals(HintType.ANY,method.getParameters()[0].getType());assertEquals(HintType.ANY.toString(),method.getParameters()[0].getTypeChecker().getSignature());assertNull(method.getReturnTypeChecker());
            assertEquals("javaName",method.getInternalName());assertSame(LegacyAlias.class,method.getNativeMethod().getDeclaringClass());
        }
    }
}
