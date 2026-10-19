package org.develnext.jphp.core.compiler.jvm;

import org.junit.Test;
import php.runtime.annotation.Reflection;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.CompileScope;
import php.runtime.env.Environment;
import php.runtime.ext.CoreExtension;
import php.runtime.ext.support.Extension;
import php.runtime.ext.support.compile.CompileFunctionSpec;
import php.runtime.lang.BaseObject;
import php.runtime.reflection.ClassEntity;
import php.runtime.reflection.CompileFunctionEntity;
import php.runtime.reflection.FunctionEntity;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

/** No PHP execution, but real scope registration/wrapping/cached native declarations. */
public class Php85NativeProviderRegistrationTest {
    public static class Provider extends Extension {
        private final String name;
        public Provider() { this("registration-provider"); }
        public Provider(String name) { this.name = name; }
        @Override public String getName() { return name; }
        @Override public Status getStatus() { return Status.EXPERIMENTAL; }
        @Override public void onRegister(CompileScope scope) { }
    }
    public static class OtherProvider extends Provider { }
    public static class NativeFunctions {
        public static int first(Environment environment) { return 1; }
        public static int second(Environment environment, String argument) { return 2; }
        public static int changed(Environment environment) { return 3; }
    }
    @Reflection.Name("RegistrationScopeBuiltin")
    public static class ScopeBuiltin extends BaseObject {
        public ScopeBuiltin(Environment environment, ClassEntity clazz) { super(environment, clazz); }
    }
    @Reflection.Name("RegistrationScopeBuiltin")
    public static class OtherScopeBuiltin extends BaseObject {
        public OtherScopeBuiltin(Environment environment, ClassEntity clazz) { super(environment, clazz); }
    }
    private CompileFunctionSpec declaration(String name, boolean immutable, boolean reverse) throws Exception {
        CompileFunctionSpec specification = new CompileFunctionSpec(name, immutable);
        var first = NativeFunctions.class.getDeclaredMethod("first", Environment.class);
        var second = NativeFunctions.class.getDeclaredMethod("second", Environment.class, String.class);
        specification.addMethod(reverse ? second : first);
        specification.addMethod(reverse ? first : second);
        return specification;
    }
    @Test public void repeatedCoreExtensionPreservesPublishedModernEntityAndCompileCache() {
        CompileScope scope = new CompileScope();
        FunctionEntity function = scope.fetchPhp85Function("enum_exists");
        assertNotNull(function);
        var compiled = ((CompileFunctionEntity) function).getCompileFunction();
        ClassEntity builtin = scope.fetchPhp85Class("UnitEnum");
        scope.registerExtension(new CoreExtension());
        scope.registerExtension(new CoreExtension());
        assertSame(function, scope.fetchPhp85Function("ENUM_EXISTS"));
        assertSame(compiled, scope.findCompileFunction("enum_exists", PhpLanguageProfile.PHP_8_5));
        assertSame(builtin, scope.fetchPhp85Class("UnitEnum"));
    }
    @Test public void equivalentNativeDeclarationsIgnoreMethodEnumerationOrder() throws Exception {
        CompileScope scope = new CompileScope();
        scope.registerPhp85Function(new Provider(), declaration("RegisteredFunction", false, false));
        FunctionEntity existing = scope.fetchPhp85Function("registeredfunction");
        scope.registerPhp85Function(new Provider(), declaration("REGISTEREDFUNCTION", false, true));
        assertSame(existing, scope.fetchPhp85Function("RegisteredFunction"));
    }
    @Test public void differentNativeMethodRemainsACollisionAndDoesNotReplaceExisting() throws Exception {
        CompileScope scope = new CompileScope();
        scope.registerPhp85Function(new Provider(), declaration("RegisteredFunction", false, false));
        FunctionEntity existing = scope.fetchPhp85Function("RegisteredFunction");
        CompileFunctionSpec changed = new CompileFunctionSpec("RegisteredFunction");
        changed.addMethod(NativeFunctions.class.getDeclaredMethod("changed", Environment.class));
        assertThrows(IllegalArgumentException.class, () -> scope.registerPhp85Function(new Provider(), changed));
        assertSame(existing, scope.fetchPhp85Function("RegisteredFunction"));
    }
    @Test public void changedFoldingMetadataRemainsACollision() throws Exception {
        CompileScope scope = new CompileScope();
        scope.registerPhp85Function(new Provider(), declaration("RegisteredFunction", false, false));
        FunctionEntity existing = scope.fetchPhp85Function("RegisteredFunction");
        CompileFunctionSpec changed = declaration("RegisteredFunction", true, false);
        assertThrows(IllegalArgumentException.class, () -> scope.registerPhp85Function(new Provider(), changed));
        assertSame(existing, scope.fetchPhp85Function("RegisteredFunction"));
    }
    @Test public void differentExtensionTypeOrNameRemainsACollision() throws Exception {
        CompileScope scope = new CompileScope();
        CompileFunctionSpec specification = declaration("RegisteredFunction", false, false);
        scope.registerPhp85Function(new Provider(), specification);
        FunctionEntity existing = scope.fetchPhp85Function("RegisteredFunction");
        assertThrows(IllegalArgumentException.class, () -> scope.registerPhp85Function(new OtherProvider(), specification));
        assertThrows(IllegalArgumentException.class, () -> scope.registerPhp85Function(new Provider("different-provider"), specification));
        assertSame(existing, scope.fetchPhp85Function("RegisteredFunction"));
    }
    @Test public void childInheritsModernFunctionAndCompileCacheWithoutReplacingLegacyDeclaration() throws Exception {
        CompileScope parent = new CompileScope();
        parent.registerPhp85Function(new Provider(), declaration("RegisteredFunction", false, false));
        FunctionEntity legacy = new FunctionEntity(null);
        legacy.setName("RegisteredFunction");
        parent.registerFunction(legacy);
        FunctionEntity modern = parent.fetchPhp85Function("RegisteredFunction");
        var compiled = ((CompileFunctionEntity) modern).getCompileFunction();
        CompileScope child = new CompileScope(parent);
        assertSame(legacy, child.findUserFunction("RegisteredFunction"));
        assertSame(modern, child.fetchPhp85Function("RegisteredFunction"));
        assertSame(compiled, child.findCompileFunction("RegisteredFunction", PhpLanguageProfile.PHP_8_5));
        child.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
        assertSame(modern, child.findUserFunction("RegisteredFunction"));
        child.registerPhp85Function(new Provider(), declaration("RegisteredFunction", false, true));
        assertSame(modern, child.fetchPhp85Function("RegisteredFunction"));
        child.registerPhp85Function(new Provider(), declaration("ChildOnlyFunction", false, false));
        assertNull(parent.fetchPhp85Function("ChildOnlyFunction"));
    }
    @Test public void childInheritsColdModernClassDeclarationAndInitializerInItsOwnCache() {
        CompileScope parent = new CompileScope();
        AtomicInteger initializations = new AtomicInteger();
        parent.registerPhp85LazyClass(new Provider(), ScopeBuiltin.class, clazz -> {
            initializations.incrementAndGet();
            clazz.setFinal(true);
        });
        CompileScope child = new CompileScope(parent);
        ClassEntity childClass = child.fetchPhp85Class("RegistrationScopeBuiltin");
        assertTrue(childClass.isFinal());
        assertTrue(child.isPhp85Builtin(childClass));
        assertFalse(parent.isPhp85Builtin(childClass));
        assertSame(childClass, child.fetchPhp85Class("registrationscopebuiltin"));
        assertEquals(1, initializations.get());
        ClassEntity parentClass = parent.fetchPhp85Class("RegistrationScopeBuiltin");
        assertNotSame(childClass, parentClass);
        assertTrue(parentClass.isFinal());
        assertEquals(2, initializations.get());
    }
    @Test public void childInheritsAlreadyWrappedModernClassWithoutRepeatingInitializer() {
        CompileScope parent = new CompileScope();
        AtomicInteger initializations = new AtomicInteger();
        parent.registerPhp85LazyClass(new Provider(), ScopeBuiltin.class, clazz -> initializations.incrementAndGet());
        ClassEntity parentClass = parent.fetchPhp85Class("RegistrationScopeBuiltin");
        CompileScope child = new CompileScope(parent);
        assertSame(parentClass, child.fetchPhp85Class("RegistrationScopeBuiltin"));
        assertTrue(child.isPhp85Builtin(parentClass));
        assertEquals(1, initializations.get());
    }
    @Test public void distinctNativeClassUnderSameModernNameRemainsACollision() {
        CompileScope scope = new CompileScope();
        scope.registerPhp85LazyClass(new Provider(), ScopeBuiltin.class);
        assertThrows(IllegalArgumentException.class,
                () -> scope.registerPhp85LazyClass(new Provider(), OtherScopeBuiltin.class));
    }
}
