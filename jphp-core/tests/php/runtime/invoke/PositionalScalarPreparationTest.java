package php.runtime.invoke;

import org.develnext.jphp.core.compiler.jvm.JvmCompiler;
import org.develnext.jphp.core.compiler.jvm.JvmCompilerCase;
import org.junit.Test;
import php.runtime.Memory;
import php.runtime.common.HintType;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.Context;
import php.runtime.env.Environment;
import php.runtime.env.TraceInfo;
import php.runtime.loader.dump.ModuleDumper;
import php.runtime.memory.DoubleMemory;
import php.runtime.memory.FalseMemory;
import php.runtime.memory.LongMemory;
import php.runtime.memory.StringMemory;
import php.runtime.reflection.ParameterEntity;
import php.runtime.reflection.support.PhpTypeDeclaration;
import php.runtime.reflection.support.TypeChecker;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;

import static org.junit.Assert.*;

public class PositionalScalarPreparationTest extends JvmCompilerCase {
    public enum Choice { ONE }
    private static ParameterEntity parameter(String name, HintType type) {
        var parameter = new ParameterEntity(null);
        parameter.setName(name); parameter.setType(type); parameter.setMutable(true);
        parameter.setTrace(TraceInfo.UNKNOWN);
        return parameter;
    }

    private static Memory[] prepare(Environment env, Memory[] arguments, ParameterEntity... parameters) {
        return InvokeArgumentHelper.makeArgumentsForTarget(env, arguments, new Object(), parameters,
                "probe", null, null, TraceInfo.UNKNOWN, false);
    }

    @Test public void scalarIdentityPreservesElementsButCopiesArbitraryCallerVectors() throws Throwable {
        var scope = newScope(); scope.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
        var env = new Environment(scope, new ByteArrayOutputStream());
        try {
            Memory[] arguments = {new LongMemory(300), new DoubleMemory(-0.0), Memory.TRUE, Memory.FALSE, new StringMemory("text")};
            HintType[] hints = {HintType.INT, HintType.DOUBLE, HintType.BOOLEAN, HintType.BOOLEAN, HintType.STRING};
            String[] names = {"int", "float", "bool", "bool", "string"};
            for (boolean declaration : new boolean[]{false, true}) {
                ParameterEntity[] parameters = new ParameterEntity[arguments.length];
                for (int i = 0; i < parameters.length; i++) {
                    parameters[i] = parameter("p" + i, hints[i]);
                    if (declaration) parameters[i].setTypeChecker(new PhpTypeDeclaration(List.of(List.of(names[i]))));
                }
                Memory[] prepared = prepare(env, arguments, parameters);
                assertNotSame(arguments, prepared);
                for (int i = 0; i < arguments.length; i++) assertSame(arguments[i], prepared[i]);
                Memory[] transferred = InvokeArgumentHelper.makeArgumentsForTarget(env, arguments, new Object(), parameters,
                        "probe", null, null, TraceInfo.UNKNOWN, false, true);
                assertSame(arguments, transferred);
            }
        } finally { env.doFinal(); }
    }

    @Test public void customCheckersValuesAndDefaultObserversStillRunExactlyOnce() throws Throwable {
        var scope = newScope(); scope.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
        var env = new Environment(scope, new ByteArrayOutputStream());
        try {
            int[] calls = {0};
            var customChecker = parameter("value", HintType.INT);
            customChecker.setTypeChecker(new TypeChecker.Simple(HintType.INT) {
                @Override public boolean check(Environment environment, Memory value, boolean nullable, String owner) {
                    calls[0]++; return true;
                }
            });
            prepare(env, new Memory[]{new LongMemory(3)}, customChecker);
            assertEquals(1, calls[0]);

            calls[0] = 0;
            var customParameter = new ParameterEntity(null) {
                @Override public boolean checkTypeHinting(Environment environment, Memory value, String owner) {
                    calls[0]++; return super.checkTypeHinting(environment, value, owner);
                }
            };
            customParameter.setName("custom"); customParameter.setType(HintType.INT);
            prepare(env, new Memory[]{new LongMemory(4)}, customParameter);
            assertEquals(1, calls[0]);

            calls[0] = 0;
            Memory customValue = new StringMemory("old") {
                @Override public Memory toImmutable() { calls[0]++; return new StringMemory("copied"); }
            };
            assertEquals("copied", prepare(env, new Memory[]{customValue}, parameter("text", HintType.STRING))[0].toString());
            assertEquals(1, calls[0]);

            calls[0] = 0;
            var observedDefault = parameter("value", HintType.INT);
            observedDefault.setDefaultValue(new LongMemory(9) {
                @Override public boolean isNull() { calls[0]++; return false; }
            });
            assertEquals(3, prepare(env, new Memory[]{new LongMemory(3)}, observedDefault)[0].toLong());
            assertEquals(1, calls[0]);

            calls[0] = 0;
            var enumParameter = parameter("enum", HintType.ANY);
            enumParameter.setTypeChecker(new TypeChecker.EnumClass(Choice.class) {
                @Override public Class<? extends Enum> getTypeEnum() {
                    calls[0]++; throw new AssertionError("Ordinary successful checking never needs this accessor");
                }
                @Override public boolean check(Environment environment, Memory value, boolean nullable, String owner) { return true; }
            });
            assertEquals(4, prepare(env, new Memory[]{new LongMemory(4)}, enumParameter)[0].toLong());
            assertEquals(0, calls[0]);

            var constructor = FalseMemory.class.getDeclaredConstructor(Memory.Type.class);
            constructor.setAccessible(true);
            Memory nullTaggedBooleanClass = constructor.newInstance(Memory.Type.NULL);
            assertThrows(Throwable.class,
                    () -> prepare(env, new Memory[]{nullTaggedBooleanClass}, parameter("flag", HintType.BOOLEAN)));
        } finally { env.doFinal(); }
    }

    @Test public void weakStrictAndDynamicArgumentRoutesKeepOrdinaryBehaviorSourceAndPhb() throws Throwable {
        String body = "function plain(int $a,float $b,bool $c,string $d){return [$a,$b,$c,$d];}"
                + "function optional(int $value=11){return $value;}function bump(&$value){$value+=2;}"
                + "function delayed($value){yield $value;}function change($values){$values[0]=9;return $values[0];}"
                + "$normal=plain(3,2.5,true,'ok');$coerced=false;try{$p=plain('3',2.5,true,'ok');$coerced=$p[0]===3;}"
                + "catch(\\TypeError $e){$coerced='strict';}$missing=false;try{plain(1,2.5,true);}"
                + "catch(\\ArgumentCountError $e){$missing=true;}"
                + "$named=plain(d:'named',c:false,b:1.25,a:7);"
                + "$unpacked=plain(...['a'=>8,'b'=>1.5,'c'=>true,'d'=>'unpacked']);"
                + "$reference=4;bump($reference);$values=[1];$changed=change($values);$generator=delayed(17);"
                + "$yielded=$generator->current();$generator->next();"
                + "return [$normal,$coerced,$missing,optional(),optional(12),optional(13,14),$reference,"
                + "$values[0],$changed,$named[0],$unpacked[0],$yielded];";
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
                var normal = result.valueOfIndex(null, 0);
                assertEquals(3, normal.valueOfIndex(null, 0).toLong());
                assertEquals(2.5, normal.valueOfIndex(null, 1).toDouble(), 0.0);
                assertTrue(normal.valueOfIndex(null, 2).toBoolean());
                assertEquals("ok", normal.valueOfIndex(null, 3).toString());
                assertEquals(strict ? "strict" : "1", result.valueOfIndex(null, 1).toString());
                assertTrue(result.valueOfIndex(null, 2).toBoolean());
                long[] expected = {11, 12, 13, 6, 1, 9, 7, 8, 17};
                for (int i = 0; i < expected.length; i++)
                    assertEquals("strict=" + strict + " PHB=" + dumped + " row " + (i + 3), expected[i], result.valueOfIndex(null, i + 3).toLong());
            } finally { env.doFinal(); }
        }
    }
}
