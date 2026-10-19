package php.runtime.loader;

import org.junit.Test;
import php.runtime.env.Environment;
import php.runtime.lang.BaseObject;
import php.runtime.reflection.ClassEntity;

import java.io.InputStream;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.constantpool.StringEntry;
import java.lang.constant.ClassDesc;
import java.lang.constant.MethodTypeDesc;
import java.lang.reflect.Modifier;
import java.util.Arrays;

import static java.lang.constant.ConstantDescs.CD_String;
import static java.lang.constant.ConstantDescs.CD_void;
import static org.junit.Assert.*;

/** Executes linked definitions; the input deliberately shares descriptor UTF8 with String constants. */
public class PhpJvmLinkedClassBytecodeTest {
    private static final ClassDesc VOID = CD_void;
    private static final ClassFile FILE = ClassFile.of();
    private static final ClassDesc BASE = ClassDesc.of("php.runtime.lang.BaseObject");
    private static final MethodTypeDesc CTOR = MethodTypeDesc.ofDescriptor(
            "(Lphp/runtime/env/Environment;Lphp/runtime/reflection/ClassEntity;)V");
    private static final String BINARY_LITERAL = "A\000\u20ac\ud83d\ude00Z";

    public static class ParentA extends BaseObject {
        public final String parentMarker;
        public ParentA(Environment env, ClassEntity entity) {
            super(env, entity);
            parentMarker = "A";
        }
    }

    public static class ParentB extends BaseObject {
        public final String parentMarker;
        public ParentB(Environment env, ClassEntity entity) {
            super(env, entity);
            parentMarker = "B";
        }
    }

    /** javac fixture: real constructor, self descriptors, self-owned static initialization and PHP-like strings. */
    public static class CompiledSelf extends BaseObject {
        public CompiledSelf peer;
        public CompiledSelf[] peers;
        public static CompiledSelf cached = new CompiledSelf(null, null);
        public CompiledSelf(Environment env, ClassEntity entity) { super(env, entity); }
        public CompiledSelf identity(CompiledSelf value) { return value; }
        public CompiledSelf self() { return this; }
        public CompiledSelf setPeer(CompiledSelf value) { peer = value; return peer; }
        public CompiledSelf invokeSelf() { return identity(this); }
        public static CompiledSelf cachedValue() { return cached; }
        public CompiledSelf[] array(CompiledSelf[] value) { return value; }
        public static String descriptorLiteral() {
            return "Lphp/runtime/loader/PhpJvmLinkedClassBytecodeTest$CompiledSelf;";
        }
        public static String nameLiteral() {
            return "php/runtime/loader/PhpJvmLinkedClassBytecodeTest$CompiledSelf";
        }
        public static String binaryLiteral() { return "A\000\u20ac\ud83d\ude00Z"; }
    }

    private static final class Definitions extends ClassLoader {
        Definitions() { super(PhpJvmLinkedClassBytecodeTest.class.getClassLoader()); }
        Class<?> define(byte[] bytes) { return defineClass(null, bytes, 0, bytes.length); }
    }

    @Test public void syntheticSharedUtf8StringsSurviveSelfAndParentLinking() throws Exception {
        String original = "php/runtime/loader/SyntheticPhpSelf";
        byte[] before = fixture(original, BASE, 1, false, BINARY_LITERAL);
        assertSharedDescriptor(before, original);
        assertSharedClassName(before, original);
        byte[] saved = before.clone();
        String renamed = original + "$linkedA";
        byte[] after = PhpJvmInheritanceBytecode.bind(before, internal(ParentA.class), renamed);
        assertArrayEquals(saved, before);
        Class<?> actual = new Definitions().define(after);
        assertLinkedBehavior(actual, ParentA.class, original, "A");
        assertEquals(renamed, FILE.parse(after).thisClass().asInternalName());
    }

    @Test public void compiledJavaSelfFieldsMethodsAndCachedAllocationUseTheVariant() throws Exception {
        String original = internal(CompiledSelf.class);
        byte[] before;
        try (InputStream stream = CompiledSelf.class.getResourceAsStream("/" + original + ".class")) {
            assertNotNull(stream);
            before = stream.readAllBytes();
        }
        assertSharedDescriptor(before, original);
        assertSharedClassName(before, original);
        byte[] after = PhpJvmInheritanceBytecode.bind(before, internal(ParentB.class), original + "$linkedB");
        Class<?> actual = new Definitions().define(after);
        Object cached = actual.getField("cached").get(null);
        assertSame(actual, cached.getClass());
        assertEquals("B", actual.getField("parentMarker").get(cached));
        Class<?> arrayType = java.lang.reflect.Array.newInstance(actual, 0).getClass();
        assertSame(arrayType, actual.getField("peers").getType());
        Object array = java.lang.reflect.Array.newInstance(actual, 1);
        assertSame(array, actual.getMethod("array", arrayType).invoke(cached, array));
        assertLinkedBehavior(actual, ParentB.class, original, "B");
    }

    @Test public void wrongSuperclassAndUnknownOrMissingOrDuplicateConstructorAreRejected() {
        String name = "php/runtime/loader/InvalidPhpSelf";
        reject(() -> PhpJvmInheritanceBytecode.bind(
                fixture(name, ClassDesc.of("java.lang.Object"), 1, false, BINARY_LITERAL), internal(ParentA.class)),
                "original BaseObject template");
        reject(() -> PhpJvmInheritanceBytecode.bind(
                fixture(name, BASE, 1, true, BINARY_LITERAL), internal(ParentA.class)), "Unknown deferred superclass constructor");
        reject(() -> PhpJvmInheritanceBytecode.bind(
                fixture(name, BASE, 0, false, BINARY_LITERAL), internal(ParentA.class)), "exactly one");
        reject(() -> PhpJvmInheritanceBytecode.bind(
                fixture(name, BASE, 2, false, BINARY_LITERAL), internal(ParentA.class)), "exactly one");
    }

    @Test public void runtimeLoaderBindsTwoParentsAndRequiresExactDefinitionWitness() throws Exception {
        String name = "php/runtime/loader/LoaderPhpSelf";
        byte[] source = fixture(name, BASE, 1, false, BINARY_LITERAL);
        try (RuntimeClassLoader loader = new RuntimeClassLoader(PhpJvmLinkedClassBytecodeTest.class.getClassLoader());
             RuntimeClassLoader foreign = new RuntimeClassLoader(PhpJvmLinkedClassBytecodeTest.class.getClassLoader())) {
            ClassEntity a = linkedEntity(name, source, ParentA.class);
            ClassEntity b = linkedEntity(name, source, ParentB.class);
            Class<?> first = loader.loadClass(a, true);
            Class<?> second = loader.loadClass(b, true);
            assertNotSame(first, second);
            assertSame(ParentA.class, first.getSuperclass());
            assertSame(ParentB.class, second.getSuperclass());
            assertEquals(name, a.getCompiledInternalName());
            assertEquals(first.getName().replace('.', '/'), a.getInternalName());
            assertArrayEquals(source, a.getData());
            assertTrue(loader.matchesDefinedBytecode(first, source));
            assertTrue(loader.matchesDefinedBytecode(second, source));
            assertFalse(loader.matchesDefinedBytecode(ParentA.class, source));
            assertFalse(loader.matchesDefinedBytecode(first, null));
            Class<?> foreignClass = foreign.loadClass(linkedEntity(name, source, ParentA.class), true);
            assertFalse(loader.matchesDefinedBytecode(foreignClass, source));
            assertSame(first, loader.loadClass(linkedEntity(name, source, ParentA.class), true));
            byte[] changed = fixture(name, BASE, 1, false, "different PHP literal");
            assertFalse(Arrays.equals(source, changed));
            assertFalse(loader.matchesDefinedBytecode(first, changed));
            reject(() -> loader.loadClass(linkedEntity(name, changed, ParentA.class), true), "Different");
            ClassEntity unresolved = entity(name + "$unresolved", source);
            unresolved.setDeferredParentName("MissingPhpParent");
            reject(() -> loader.loadClass(unresolved, true), "Unresolved PHP class");
            ClassEntity template = entity(name + "$catalog", source);
            template.setDeclarationId(0);
            reject(() -> loader.loadClass(template, true), "catalog template");
            assertNull(template.getNativeClass());
        }
    }

    private static ClassEntity entity(String name, byte[] source) {
        ClassEntity entity = new ClassEntity((php.runtime.env.Context) null);
        entity.setName(name);
        entity.setInternalName(name);
        entity.setData(source);
        return entity;
    }

    private static ClassEntity linkedEntity(String name, byte[] source, Class<?> actualParent) {
        ClassEntity parent = entity(internal(actualParent), null);
        parent.setNativeClazz(actualParent);
        ClassEntity child = entity(name, source);
        child.setDeferredParentName(parent.getName());
        child.setParent(parent, false);
        return child;
    }

    private static void assertLinkedBehavior(Class<?> actual, Class<?> parent, String original, String marker) throws Exception {
        assertSame(parent, actual.getSuperclass());
        ClassEntity entity = new ClassEntity((php.runtime.env.Context) null);
        Object value = actual.getConstructor(Environment.class, ClassEntity.class).newInstance(null, entity);
        assertSame(entity, ((BaseObject) value).getReflection());
        assertEquals(marker, actual.getField("parentMarker").get(value));
        assertSame(actual, actual.getField("peer").getType());
        actual.getField("peer").set(value, value);
        assertSame(value, actual.getField("peer").get(value));
        assertSame(actual, actual.getMethod("identity", actual).getReturnType());
        assertSame(value, actual.getMethod("identity", actual).invoke(value, value));
        assertSame(value, actual.getMethod("self").invoke(value));
        assertSame(value, actual.getMethod("setPeer", actual).invoke(value, value));
        assertSame(value, actual.getMethod("invokeSelf").invoke(value));
        actual.getField("cached").set(null, value);
        assertSame(value, actual.getField("cached").get(null));
        assertSame(value, actual.getMethod("cachedValue").invoke(null));
        assertEquals("L" + original + ";", actual.getMethod("descriptorLiteral").invoke(null));
        assertEquals(original, actual.getMethod("nameLiteral").invoke(null));
        assertEquals(BINARY_LITERAL, actual.getMethod("binaryLiteral").invoke(null));
    }

    private static void assertSharedDescriptor(byte[] data, String original) {
        ClassModel model = FILE.parse(data);
        int descriptor = model.fields().stream().filter(field -> field.fieldName().equalsString("peer"))
                .findFirst().orElseThrow().fieldType().index();
        boolean shared = false;
        for (var entry : model.constantPool()) {
            if (entry instanceof StringEntry text && text.equalsString("L" + original + ";")
                    && text.utf8().index() == descriptor) shared = true;
        }
        assertTrue("fixture must share descriptor UTF8 with a real String constant", shared);
    }

    private static void assertSharedClassName(byte[] data, String original) {
        ClassModel model = FILE.parse(data);
        int name = model.thisClass().name().index();
        boolean shared = false;
        for (var entry : model.constantPool()) {
            if (entry instanceof StringEntry text && text.equalsString(original)
                    && text.utf8().index() == name) shared = true;
        }
        assertTrue("fixture must share Class-name UTF8 with a real String constant", shared);
    }

    private static byte[] fixture(String internal, ClassDesc superclass, int ctorCalls, boolean unknownCtor, String binary) {
        ClassDesc self = ClassDesc.ofInternalName(internal);
        return ClassFile.of(ClassFile.StackMapsOption.DROP_STACK_MAPS).build(self, builder -> {
            builder.withVersion(49, 0).withFlags(Modifier.PUBLIC | 0x0020).withSuperclass(superclass);
            builder.withField("peer", self, Modifier.PUBLIC);
            builder.withField("cached", self, Modifier.PUBLIC | Modifier.STATIC);
            builder.withMethod("<init>", CTOR, Modifier.PUBLIC, method -> method.withCode(code -> {
                for (int i = 0; i < ctorCalls; i++) {
                    code.aload(0);
                    if (!unknownCtor) code.aload(1).aload(2);
                    code.invokespecial(BASE, "<init>", unknownCtor ? MethodTypeDesc.of(CD_void) : CTOR);
                }
                code.return_();
            }));
            builder.withMethod("identity", MethodTypeDesc.of(self, self), Modifier.PUBLIC,
                    method -> method.withCode(code -> code.aload(1).areturn()));
            builder.withMethod("self", MethodTypeDesc.of(self), Modifier.PUBLIC,
                    method -> method.withCode(code -> code.aload(0).areturn()));
            builder.withMethod("setPeer", MethodTypeDesc.of(self, self), Modifier.PUBLIC,
                    method -> method.withCode(code -> code.aload(0).aload(1).putfield(self, "peer", self)
                            .aload(0).getfield(self, "peer", self).areturn()));
            builder.withMethod("invokeSelf", MethodTypeDesc.of(self), Modifier.PUBLIC,
                    method -> method.withCode(code -> code.aload(0).aload(0)
                            .invokevirtual(self, "identity", MethodTypeDesc.of(self, self)).areturn()));
            builder.withMethod("cachedValue", MethodTypeDesc.of(self), Modifier.PUBLIC | Modifier.STATIC,
                    method -> method.withCode(code -> code.getstatic(self, "cached", self).areturn()));
            stringMethod(builder, "descriptorLiteral", "L" + internal + ";");
            stringMethod(builder, "nameLiteral", internal);
            stringMethod(builder, "binaryLiteral", binary);
        });
    }

    private static void stringMethod(java.lang.classfile.ClassBuilder builder, String name, String value) {
        builder.withMethod(name, MethodTypeDesc.of(CD_String), Modifier.PUBLIC | Modifier.STATIC,
                method -> method.withCode(code -> code.ldc(value).areturn()));
    }

    private static String internal(Class<?> type) { return type.getName().replace('.', '/'); }
    @FunctionalInterface private interface Checked { void run() throws Exception; }
    private static void reject(Checked operation, String diagnostic) {
        try { operation.run(); fail("Expected LinkageError: " + diagnostic); }
        catch (LinkageError expected) { assertTrue(expected.toString(), expected.getMessage().contains(diagnostic)); }
        catch (Exception other) { throw new AssertionError("Wrong rejection type", other); }
    }
}
