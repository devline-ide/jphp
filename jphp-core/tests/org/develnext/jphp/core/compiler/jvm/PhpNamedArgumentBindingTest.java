package org.develnext.jphp.core.compiler.jvm;

import org.junit.Test;
import php.runtime.Memory;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.*;
import php.runtime.invoke.PhpCallFrame;
import php.runtime.lang.exception.BaseBaseException;
import php.runtime.loader.dump.ModuleDumper;
import php.runtime.memory.*;
import php.runtime.reflection.ModuleEntity;
import java.io.*;
import static org.junit.Assert.*;

/** Resolved runtime binding; the separate PHP source corpus verifies frontend named syntax. */
public class PhpNamedArgumentBindingTest extends JvmCompilerCase {
    private interface Check {void run(Environment env, ModuleEntity module, String mode) throws Throwable;}
    private void modes(String source, Check check) throws Throwable {
        for (boolean debug : new boolean[]{false, true})
            for (boolean dumped : new boolean[]{false, true})
                for (boolean strict : new boolean[]{false, true}) {
                    var scope = newScope();scope.setDebugMode(debug);
                    scope.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
                    scope.registerExtension(new org.develnext.jphp.zend.ext.standard.StandardExtension());
                    var env = new Environment(scope, new ByteArrayOutputStream());
                    try {
                        var context = new Context((strict ? "declare(strict_types=1);" : "") + source);
                        context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
                        var module = new JvmCompiler(env, context).compile(false);
                        if (dumped) {
                            var dumper = new ModuleDumper(context, env, true);var bytes = new ByteArrayOutputStream();
                            dumper.save(module, bytes);module = dumper.load(new ByteArrayInputStream(bytes.toByteArray()));
                        }
                        scope.loadModule(module);scope.addUserModule(module);env.registerModule(module);module.include(env);
                        check.run(env, module, "debug=" + debug + " dump=" + dumped + " strict=" + strict);
                    } finally {env.doFinal();scope.getClassLoader().close();}
                }
    }
    private PhpCallFrame frame(Environment env, ModuleEntity module, String name, int capacity) {
        var function = module.findFunction(name);assertNotNull(name, function);
        return PhpCallFrame.resolvedFunction(env, function.getTrace(), function, capacity);
    }
    private Memory at(Memory array, long key) {return array.valueOfIndex(TraceInfo.UNKNOWN, key);}
    private Memory at(Memory array, String key) {return array.valueOfIndex(TraceInfo.UNKNOWN, key);}
    private interface Action {void run() throws Throwable;}
    private void error(Environment env, String expected, Action action) throws Throwable {
        try {action.run();fail("Expected " + expected);}
        catch (BaseBaseException failure) {assertEquals(expected, failure.getMessage(env).toString());}
    }

    @Test public void declarationOrderSnapshotsDefaultsAndIntrospectionSourceDump() throws Throwable {
        modes("function pair($a,$b){return [$a,$b];}"
                + "function inspect($a=1,$b=2,...$rest){return [func_num_args(),func_get_args(),$rest];}",
                (env, module, mode) -> {
                    var value = new ReferenceMemory(LongMemory.valueOf(1));
                    try (var call = frame(env, module, "pair", 2)) {
                        call.prepareNamed("b");call.send(value, PhpCallFrame.VARIABLE);
                        value.assign(LongMemory.valueOf(2));
                        call.prepareNamed("a");call.send(value, PhpCallFrame.VARIABLE);
                        var result = call.invoke();assertEquals(mode, 2, at(result, 0).toLong());
                        assertEquals(mode, 1, at(result, 1).toLong());
                    }
                    try (var call = frame(env, module, "inspect", 2)) {
                        call.prepareNamed("b");call.send(LongMemory.valueOf(8), PhpCallFrame.VALUE);
                        call.prepareNamed("z");call.send(LongMemory.valueOf(9), PhpCallFrame.VALUE);
                        var result = call.invoke();assertEquals(mode, 2, at(result, 0).toLong());
                        assertEquals(mode, 1, at(at(result, 1), 0).toLong());
                        assertEquals(mode, 8, at(at(result, 1), 1).toLong());
                        assertEquals(mode, 9, at(at(result, 2), "z").toLong());
                        assertEquals(mode, 1, at(result, 2).toValue(ArrayMemory.class).size());
                    }
                    try (var call = frame(env, module, "inspect", 1)) {
                        call.prepareNamed("a");call.send(Memory.NULL, PhpCallFrame.VALUE);
                        var result = call.invoke();assertTrue(mode, at(at(result, 1), 0).isNull());
                        assertEquals(mode, 1, at(result, 0).toLong());
                    }
                });
    }

    @Test public void pendingLookupErrorsCommitAfterCurrentExpressionAndUnwindSourceDump() throws Throwable {
        modes("class EmptyShell{}function pair($a,$b){return [$a,$b];}function required($a=1,$b){return [$a,$b];}", (env, module, mode) -> {
            try (var call = PhpCallFrame.function(env, TraceInfo.UNKNOWN, "str_repeat", "str_repeat", null, 0, 0)) {
                error(env, "str_repeat() expects exactly 2 arguments, 0 given", () -> call.invokeKeyed(new ArrayMemory(), true));
            }
            try (var call = PhpCallFrame.function(env, TraceInfo.UNKNOWN, "str_repeat", "str_repeat", null, 0, 3)) {
                var args = new ArrayMemory();args.add(StringMemory.valueOf("x"));args.add(LongMemory.valueOf(2));args.add(LongMemory.valueOf(3));
                error(env, "str_repeat() expects exactly 2 arguments, 3 given", () -> call.invokeKeyed(args, true));
            }
            try (var call = PhpCallFrame.constructResolved(module.findClass("EmptyShell"), env, TraceInfo.UNKNOWN, 1)) {
                call.preparePositional();call.send(LongMemory.valueOf(1), PhpCallFrame.VALUE);
                assertEquals(mode, "EmptyShell", call.invoke().toValue(ObjectMemory.class).getReflection().getName());
            }
            try (var call = PhpCallFrame.constructResolved(module.findClass("EmptyShell"), env, TraceInfo.UNKNOWN, 1)) {
                call.prepareNamed("a");assertFalse(mode, call.nextArgumentIsReference());
                error(env, "Unknown named parameter $a", () -> {
                    try {call.send(LongMemory.valueOf(1), PhpCallFrame.VALUE);}
                    catch (Throwable primary) {throw call.fail(primary);}
                });
            }
            var evaluated = new StringBuilder();
            try (var call = frame(env, module, "pair", 3)) {
                call.prepareNamed("unknown");assertFalse(mode, call.nextArgumentIsReference());
                evaluated.append("current");
                error(env, "Unknown named parameter $unknown", () -> call.send(Memory.NULL, PhpCallFrame.VALUE));
                call.fail(new IllegalStateException("argument failure"));
            }
            assertEquals(mode, "current", evaluated.toString());
            try (var call = frame(env, module, "pair", 3)) {
                call.prepareNamed("a");call.send(LongMemory.valueOf(1), PhpCallFrame.VALUE);
                call.prepareNamed("a");evaluated.append("duplicate");
                error(env, "Named parameter $a overwrites previous argument",
                        () -> call.send(LongMemory.valueOf(2), PhpCallFrame.VALUE));
            }
            assertEquals(mode, "currentduplicate", evaluated.toString());
            try (var call = frame(env, module, "pair", 1)) {
                call.prepareNamed("b");call.send(LongMemory.valueOf(2), PhpCallFrame.VALUE);
                error(env, "pair(): Argument #1 ($a) not passed", call::invoke);
            }
            try (var call = frame(env, module, "required", 1)) {
                call.prepareNamed("b");call.send(LongMemory.valueOf(2), PhpCallFrame.VALUE);
                error(env, "required(): Argument #1 ($a) not passed", call::invoke);
            }
            try (var call = frame(env, module, "pair", 2)) {
                call.prepareNamed("b");call.send(LongMemory.valueOf(2), PhpCallFrame.VALUE);
                call.prepareNamed("a");call.send(LongMemory.valueOf(1), PhpCallFrame.VALUE);
                assertEquals(mode, 1, at(call.invoke(), 0).toLong());
            }
        });
    }

    @Test public void namedVariadicAndUnpackKeepRealAliasesAndStringKeysSourceDump() throws Throwable {
        modes("function refs(&$a,&...$rest){$a=4;$rest['01']=7;return $rest;}"
                + "function spread(&$a,$b){$a=5;return $b;}"
                + "function binput(){yield 'b'=>1;yield 'a'=>2;}"
                + "function btarget($a=1,&$b){$b=9;return [$a,$b];}"
                + "function atarget(&$a,$b=2){$a=9;return [$a,$b];}"
                + "$GLOBALS['warnings']=[];set_error_handler(function($n,$s){$GLOBALS['warnings'][]=$s;return true;});",
                (env, module, mode) -> {
            Memory firstResult, secondResult;
            try (var source = frame(env, module, "binput", 0);var call = frame(env, module, "btarget", 1)) {
                call.sendSpread(source.invoke());firstResult = call.invoke();
            }
            try (var source = frame(env, module, "binput", 0);var call = frame(env, module, "atarget", 1)) {
                call.sendSpread(source.invoke());secondResult = call.invoke();
            }
            assertEquals(mode, 2, at(firstResult, 0).toLong());assertEquals(mode, 9, at(firstResult, 1).toLong());
            assertEquals(mode, 9, at(secondResult, 0).toLong());assertEquals(mode, 1, at(secondResult, 1).toLong());
            var warnings = env.getGlobals().valueOfIndex(TraceInfo.UNKNOWN, "warnings");
            assertEquals(mode, "Cannot pass by-reference argument 2 of btarget() by unpacking a Traversable, passing by-value instead", at(warnings, 0).toString());
            assertEquals(mode, "Cannot pass by-reference argument 1 of atarget() by unpacking a Traversable, passing by-value instead", at(warnings, 1).toString());
            var first = new ReferenceMemory(LongMemory.valueOf(1));
            var extra = new ReferenceMemory(LongMemory.valueOf(2));
            try (var call = frame(env, module, "refs", 2)) {
                call.prepareNamed("01");assertTrue(mode, call.nextArgumentIsReference());
                call.send(extra, PhpCallFrame.VARIABLE);
                call.prepareNamed("a");call.send(first, PhpCallFrame.VARIABLE);
                var result = call.invoke();assertEquals(mode, 4, first.toLong());
                assertEquals(mode, 7, extra.toLong());assertEquals(mode, 7, at(result, "01").toLong());
                assertEquals(mode, 1, result.toValue(ArrayMemory.class).size());
            }
            var args = new ArrayMemory();args.put("b", LongMemory.valueOf(9));args.put("a", LongMemory.valueOf(1));
            try (var call = frame(env, module, "spread", 1)) {
                call.sendSpread(args);assertEquals(mode, 9, call.invoke().toLong());
            }
            assertEquals(mode, 5, at(args, "a").toLong());
            var keyedCell = new ReferenceMemory(LongMemory.valueOf(1));
            var keyed = new ArrayMemory();keyed.put("a", ReferenceMemory.exportReference(keyedCell));
            keyed.put("b", LongMemory.valueOf(9));
            try (var call = frame(env, module, "spread", 1)) {
                assertEquals(mode, 9, call.invokeKeyed(keyed, true).toLong());
            }
            assertEquals(mode, 5, keyedCell.toLong());
            var plainKeyed = new ArrayMemory();plainKeyed.put("a", LongMemory.valueOf(1));
            plainKeyed.put("b", LongMemory.valueOf(9));
            try (var call = frame(env, module, "spread", 1)) {
                assertEquals(mode, 9, call.invokeKeyed(plainKeyed, true).toLong());
            }
            assertEquals(mode, 1, at(plainKeyed, "a").toLong());
            var invalid = new ArrayMemory();invalid.put("b", LongMemory.valueOf(9));invalid.add(LongMemory.valueOf(1));
            try (var call = frame(env, module, "spread", 1)) {
                error(env, "Cannot use positional argument after named argument during unpacking", () -> call.sendSpread(invalid));
            }
        });
    }
    @Test public void exactVectorOneShotScopeRejectsForeignOrMutatedCallsAndNestedPreparationSourceDump() throws Throwable {
        modes("function inner($x=3){return $x;}function outer($a,$b=2){return [$a,$b,inner()];}",
                (env, module, mode) -> {
                    try (var first = frame(env, module, "outer", 1);
                         var second = frame(env, module, "outer", 1)) {
                        first.prepareNamed("a");first.send(LongMemory.valueOf(8), PhpCallFrame.VALUE);
                        var bound = first.finishBinding();
                        try {second.invokeBound(bound);fail(mode + " accepted a foreign frame");}
                        catch (IllegalArgumentException expected) {assertEquals("Foreign bound invocation", expected.getMessage());}
                        var result = first.invokeBound(bound);
                        assertEquals(mode, 8, at(result, 0).toLong());
                        assertEquals(mode, 2, at(result, 1).toLong());
                        assertEquals(mode, 3, at(result, 2).toLong());
                    }
                    try (var call = frame(env, module, "outer", 1)) {
                        call.prepareNamed("a");call.send(LongMemory.valueOf(8), PhpCallFrame.VALUE);
                        var bound = call.finishBinding();bound.arguments()[0] = LongMemory.valueOf(9);
                        try {call.invokeBound(bound);fail(mode + " accepted a replaced SEND vector");}
                        catch (IllegalArgumentException expected) {
                            assertEquals("Bound vector changed after its SENDs", expected.getMessage());
                        }
                    }
                    try (var call = frame(env, module, "outer", 1)) {
                        call.prepareNamed("a");call.send(LongMemory.valueOf(1), PhpCallFrame.VALUE);
                        assertEquals(mode, 3, at(call.invoke(), 2).toLong());
                    }
                });
    }

}
