package org.develnext.jphp.core.compiler.jvm;

import org.junit.Test;
import php.runtime.Memory;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.Context;
import php.runtime.env.Environment;
import php.runtime.loader.dump.ModuleDumper;
import php.runtime.memory.LongMemory;
import php.runtime.reflection.support.PhpTypeDeclaration;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;

import static org.junit.Assert.*;

public class PhpTypeDeclarationScalarCheckTest extends JvmCompilerCase {
    private static final class ObservedValue extends LongMemory {
        final StringBuilder events = new StringBuilder();
        Memory.Type realType;
        boolean nullValue, stringValue;
        RuntimeException nullFailure, typeFailure;

        ObservedValue(Memory.Type realType) { super(3); this.realType = realType; }
        @Override public boolean isNull() {
            events.append('N'); if (nullFailure != null) throw nullFailure; return nullValue;
        }
        @Override public Memory.Type getRealType() {
            events.append('R'); if (typeFailure != null) throw typeFailure; return realType;
        }
        @Override public boolean isString() { events.append('S'); return stringValue; }
        @Override public boolean isObject() { events.append('O'); return false; }
        @Override public Memory toValue() { throw new AssertionError("Type checking must not convert the value"); }
        @Override public boolean toBoolean() { throw new AssertionError("Type checking must not coerce the value"); }
    }

    @Test public void singleScalarChecksKeepCustomValueHookOrderAndFailures() {
        String[] names = {"int", "float", "bool", "string"};
        Memory.Type[] types = {Memory.Type.INT, Memory.Type.DOUBLE, Memory.Type.BOOL, Memory.Type.STRING};
        for (int i = 0; i < names.length; i++) {
            var declaration = new PhpTypeDeclaration(List.of(List.of(names[i])));
            var value = new ObservedValue(types[i]); value.stringValue = true;
            String hook = names[i].equals("string") ? "S" : "R";
            assertTrue(declaration.check(null, value, false, "UnusedOwner"));
            assertEquals(hook, value.events.toString());
            value.events.setLength(0);
            assertTrue(declaration.check(null, value, true, "UnusedOwner"));
            assertEquals("N" + hook, value.events.toString());
            value.events.setLength(0); value.nullValue = true;
            assertTrue(declaration.check(null, value, true, null));
            assertEquals("N", value.events.toString());
            value.events.setLength(0); value.nullValue = false;
            value.realType = Memory.Type.ARRAY; value.stringValue = false;
            assertFalse(declaration.check(null, value, false, null));
            assertEquals(hook, value.events.toString());
            value.events.setLength(0); value.nullFailure = new IllegalStateException("nullable observer");
            assertSame(value.nullFailure, assertThrows(IllegalStateException.class,
                    () -> declaration.check(null, value, true, null)));
            assertEquals("N", value.events.toString());
        }
        var declaration = new PhpTypeDeclaration(List.of(List.of("int")));
        var value = new ObservedValue(Memory.Type.INT);
        value.typeFailure = new IllegalArgumentException("type observer");
        assertSame(value.typeFailure, assertThrows(IllegalArgumentException.class,
                () -> declaration.check(null, value, false, null)));
        assertEquals("R", value.events.toString());
    }

    @Test public void compositeAndNonExactAtomsKeepTheirExistingPredicateWalk() {
        var value = new ObservedValue(Memory.Type.INT);
        assertTrue(new PhpTypeDeclaration(List.of(List.of("int"), List.of("string"))).check(null, value, false, null));
        assertEquals("SR", value.events.toString());
        value.events.setLength(0);
        assertTrue(new PhpTypeDeclaration(List.of(List.of("int", "int"))).check(null, value, false, null));
        assertEquals("RR", value.events.toString());
        value.events.setLength(0); value.realType = Memory.Type.NULL; value.nullValue = true;
        assertTrue(new PhpTypeDeclaration(List.of(List.of("int"), List.of("null"))).check(null, value, false, null));
        assertEquals("RN", value.events.toString());
        value.events.setLength(0);
        assertFalse(new PhpTypeDeclaration(List.of(List.of("INT"))).check(null, value, false, null));
        assertEquals("O", value.events.toString());
    }

    @Test public void strictWeakNullableUnionAndRelativeTypesKeepSourceAndPhbBehavior() throws Throwable {
        String body = "function probeInt(int $v):int{return $v;}function probeFloat(float $v):float{return $v;}"
                + "function probeBool(bool $v):bool{return $v;}function probeString(string $v):string{return $v;}"
                + "function returned($v):int{return $v;}function nullable(?int $v):?int{return $v;}"
                + "function unionValue(int|string $v):int|string{return $v;}"
                + "class ScalarBase {}class ScalarChild extends ScalarBase {"
                + "public function relative(self $a,parent $b):static{return $this;}}"
                + "$child=new ScalarChild();$base=new ScalarBase();"
                + "$input='';try{$input=probeInt('7');}catch(\\TypeError $e){$input='strict';}"
                + "$output='';try{$output=returned('8');}catch(\\TypeError $e){$output='strict';}"
                + "$rejected=false;try{probeInt(null);}catch(\\TypeError $e){$rejected=true;}"
                + "$wrongRelative=false;try{$child->relative($base,$base);}catch(\\TypeError $e){$wrongRelative=true;}"
                + "return [probeInt(3),probeFloat(2),probeBool(false),probeString('ok'),$input,$output,nullable(null),nullable(9),"
                + "unionValue('10'),unionValue(11),$child->relative($child,$base)===$child,$rejected,$wrongRelative];";
        for (boolean strict : new boolean[]{false, true}) for (boolean dumped : new boolean[]{false, true}) {
            var scope = newScope(); scope.setDebugMode(false); scope.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
            var env = new Environment(scope, new ByteArrayOutputStream());
            try {
                var context = new Context((strict ? "declare(strict_types=1);" : "") + body);
                context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
                var module = new JvmCompiler(env, context).compile(false);
                if (dumped) {
                    var dumper = new ModuleDumper(context, env, true);
                    var bytes = new ByteArrayOutputStream(); dumper.save(module, bytes);
                    module = dumper.load(new ByteArrayInputStream(bytes.toByteArray()));
                }
                scope.loadModule(module); scope.addUserModule(module); env.registerModule(module);
                var result = module.include(env);
                assertEquals(3, result.valueOfIndex(null, 0).toLong());
                assertEquals(Memory.Type.DOUBLE, result.valueOfIndex(null, 1).getRealType());
                assertEquals(2.0, result.valueOfIndex(null, 1).toDouble(), 0.0);
                assertFalse(result.valueOfIndex(null, 2).toBoolean());
                assertEquals("ok", result.valueOfIndex(null, 3).toString());
                assertEquals(strict ? "strict" : "7", result.valueOfIndex(null, 4).toString());
                assertEquals(strict ? "strict" : "8", result.valueOfIndex(null, 5).toString());
                assertTrue(result.valueOfIndex(null, 6).isNull());
                assertEquals(9, result.valueOfIndex(null, 7).toLong());
                assertEquals(Memory.Type.STRING, result.valueOfIndex(null, 8).getRealType());
                assertEquals("10", result.valueOfIndex(null, 8).toString());
                assertEquals(11, result.valueOfIndex(null, 9).toLong());
                for (int i = 10; i <= 12; i++) assertTrue(result.valueOfIndex(null, i).toBoolean());
            } finally { env.doFinal(); }
        }
    }
}
