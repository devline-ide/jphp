package org.develnext.jphp.core.compiler.jvm;

import org.junit.Test;
import php.runtime.Memory;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.Context;
import php.runtime.env.Environment;
import php.runtime.env.TraceInfo;
import php.runtime.ext.spl.SPLFunctions;
import php.runtime.lang.StdClass;
import php.runtime.loader.dump.ModuleDumper;
import php.runtime.memory.ArrayMemory;
import php.runtime.memory.ObjectMemory;

import java.io.*;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.*;

/** The native Java interface must be checked on the actual IObject, never its Memory wrapper. */
public class PhpSplTraversableActualObjectTest extends JvmCompilerCase {
    private php.runtime.reflection.ModuleEntity compile(Environment env, String source,
                                                       PhpLanguageProfile profile, boolean phb) throws Throwable {
        var context = new Context(source); context.setPhpLanguageProfile(profile);
        var module = new JvmCompiler(env, context).compile(false);
        if (phb) {
            var bytes = new ByteArrayOutputStream(); var dumper = new ModuleDumper(context, env, true);
            dumper.save(module, bytes); module = dumper.load(new ByteArrayInputStream(bytes.toByteArray()));
        }
        env.getScope().loadModule(module); env.getScope().addUserModule(module); env.registerModule(module);
        return module;
    }

    private void output(String source, String expected) throws Throwable {
        for (var profile : new PhpLanguageProfile[]{PhpLanguageProfile.JPHP_LEGACY, PhpLanguageProfile.PHP_8_5})
            for (boolean debug : new boolean[]{false, true}) for (boolean phb : new boolean[]{false, true}) {
                var scope = super.newScope(); scope.setPhpLanguageProfile(profile); scope.setDebugMode(debug);
                var out = new ByteArrayOutputStream(); var env = new Environment(scope, out);
                try {
                    compile(env, source, profile, phb).include(env); env.doFinal();
                    assertEquals(profile + " debug=" + debug + " PHB=" + phb, expected, out.toString(StandardCharsets.UTF_8));
                } finally { try { env.doFinal(); } finally { scope.getClassLoader().close(); } }
            }
    }

    @Test(timeout = 60000) public void actualGeneratorAndNativeArrayIteratorPassTraversableConsumersSourceAndDump() throws Throwable {
        output("function g(){yield 1;yield 2;}"
                + "$g=iterator_to_array(g());$a=iterator_to_array(new ArrayIterator([3,4]));"
                + "echo $g[0],$g[1],$a[0],$a[1],iterator_count(g());", "12342");
    }

    @Test(timeout = 60000) public void inheritedPhpIteratorBridgePassesItsActualNativeContractSourceAndDump() throws Throwable {
        output("class ParentIter implements Iterator{private $i=0;"
                + "function current(){return $this->i+5;}function key(){return $this->i;}"
                + "function next(){++$this->i;}function rewind(){$this->i=0;}function valid(){return $this->i<2;}}"
                + "class ChildIter extends ParentIter{}$r=iterator_to_array(new ChildIter);echo $r[0],$r[1];", "56");
    }

    @Test(timeout = 60000) public void unrelatedActualObjectRetainsExistingWarningAndNullReturnSourceAndDump() throws Throwable {
        for (var profile : new PhpLanguageProfile[]{PhpLanguageProfile.JPHP_LEGACY, PhpLanguageProfile.PHP_8_5})
            for (boolean debug : new boolean[]{false, true}) for (boolean phb : new boolean[]{false, true}) {
                var scope = super.newScope(); scope.setPhpLanguageProfile(profile); scope.setDebugMode(debug);
                var env = new Environment(scope, new ByteArrayOutputStream());
                try {
                    compile(env, "$messages=[];set_error_handler(function($n,$s)use(&$messages){$messages[]=$s;return true;});",
                            profile, phb).include(env);
                    // Direct Java helper contract preserves its historical warning; this is not a PHP8 TypeError oracle.
                    Memory rejected = SPLFunctions.iterator_to_array(env, TraceInfo.UNKNOWN, new ObjectMemory(new StdClass(env)), true);
                    assertSame(Memory.NULL, rejected);
                    var messages = env.getGlobals().valueOfIndex(TraceInfo.UNKNOWN, "messages").toValue(ArrayMemory.class);
                    assertEquals(1, messages.size());
                    assertEquals("expects parameter 1 must implement interface Traversable, stdClass given",
                            messages.valueOfIndex(TraceInfo.UNKNOWN, 0).toString());
                    assertSame(Memory.NULL, SPLFunctions.iterator_to_array(env, TraceInfo.UNKNOWN, Memory.NULL, true));
                    assertEquals(2, messages.size());
                    assertEquals("expects parameter 1 must implement interface Traversable, NULL given",
                            messages.valueOfIndex(TraceInfo.UNKNOWN, 1).toString());
                } finally { try { env.doFinal(); } finally { scope.getClassLoader().close(); } }
            }
    }
}
