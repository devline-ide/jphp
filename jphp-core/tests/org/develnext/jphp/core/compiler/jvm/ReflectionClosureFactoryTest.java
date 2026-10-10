package org.develnext.jphp.core.compiler.jvm;

import org.develnext.jphp.zend.ext.standard.StandardExtension;
import org.develnext.jphp.zend.ext.json.JsonExtension;
import org.junit.Test;
import php.runtime.Memory;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.*;
import php.runtime.lang.Closure;
import php.runtime.lang.IObject;
import php.runtime.loader.dump.ModuleDumper;
import php.runtime.memory.StringMemory;
import php.runtime.reflection.ModuleEntity;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import static org.junit.Assert.*;

/** Fresh reflection closures expose the actual receiver and never live in shared metadata. */
public class ReflectionClosureFactoryTest extends JvmCompilerCase {
    private String resource(String suffix) throws IOException {
        try (InputStream input = getClass().getClassLoader().getResourceAsStream("resources/type_hinting/php85_reflection_closure_factories." + suffix)) {
            assertNotNull(input);return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
    private ModuleEntity compile(Environment env,String source,PhpLanguageProfile profile,boolean dumped) throws Throwable {
        Context context=new Context(source.startsWith("<?php") ? source.substring(5) : source);context.setPhpLanguageProfile(profile);
        ModuleEntity module=new JvmCompiler(env,context).compile(false);
        if (dumped) {
            ModuleDumper dumper=new ModuleDumper(context,env,true);ByteArrayOutputStream output=new ByteArrayOutputStream();
            dumper.save(module,output);module=dumper.load(new ByteArrayInputStream(output.toByteArray()));
        }
        env.scope.loadModule(module);env.scope.addUserModule(module);env.registerModule(module);return module;
    }
    @Test public void nativeFactoriesBindingAndReferenceReturnSourceAndDump() throws Throwable {
        String source=resource("php"),expected=resource("expected");ArrayList<String> differences=new ArrayList<>();
        for (boolean debug:new boolean[]{false,true}) for (boolean dumped:new boolean[]{false,true}) {
            var scope=newScope();scope.setDebugMode(debug);scope.registerExtension(new StandardExtension());scope.registerExtension(new JsonExtension());
            ByteArrayOutputStream output=new ByteArrayOutputStream();Environment env=new Environment(scope,output);
            try {
                compile(env,source,PhpLanguageProfile.PHP_8_5,dumped).include(env);env.getDefaultBuffer().flush();
                if (!expected.equals(output.toString(StandardCharsets.UTF_8))) differences.add("debug="+debug+" dumped="+dumped+" actual="+output.toString(StandardCharsets.UTF_8));
            } catch (Throwable error) {
                differences.add("debug="+debug+" dumped="+dumped+" error="+error
                        +(error instanceof php.runtime.exceptions.support.ErrorException phpError ? " line="+(phpError.getTraceInfo().getStartLine()+1) : ""));
            } finally {env.doFinal();}
        }
        assertTrue(String.join("\n",differences),differences.isEmpty());
    }
    private void sharedMetadata(boolean parent) throws Throwable {
        for (PhpLanguageProfile profile:PhpLanguageProfile.values()) for (boolean debug:new boolean[]{false,true}) for (boolean dumped:new boolean[]{false,true}) {
            var scope=newScope();scope.setDebugMode(debug);Environment first=new Environment(scope,new ByteArrayOutputStream()),second=null;
            try {
                ModuleEntity module=compile(first,"class FactoryOwner { public $name; function __construct($n){$this->name=$n;} function value(){return $this->name;} static function stat(){return 'static';}} function factory_function(){return 'function';}",profile,dumped);
                module.include(first);second=parent ? new Environment(first,new ByteArrayOutputStream()) : new Environment(scope,new ByteArrayOutputStream());
                if (!parent) second.registerModule(module);
                var clazz=module.findClass("FactoryOwner");var method=clazz.findMethod("value");var function=module.findFunction("factory_function");
                assertSame(clazz,second.fetchClass("FactoryOwner",false));assertSame(function,second.fetchFunction("factory_function"));
                IObject a=clazz.newObject(first,TraceInfo.UNKNOWN,true,new StringMemory("A")),b=clazz.newObject(second,TraceInfo.UNKNOWN,true,new StringMemory("B"));
                Closure ca=method.getClosure(first,a),cb=method.getClosure(second,b);
                assertNotSame(ca,cb);assertNotSame(ca,method.getClosure(first,a));assertSame(a,ca.getSelf().toObject(IObject.class));assertSame(b,cb.getSelf().toObject(IObject.class));
                assertSame(method,ca.getReflectedMethod());assertSame(method,cb.getReflectedMethod());assertEquals("A",ca.__invoke(first).toString());assertEquals("B",cb.__invoke(second).toString());
                Closure fa=function.getClosure(first),fb=function.getClosure(second);
                assertNotSame(fa,fb);assertNotSame(fa,function.getClosure(first));assertTrue(fa.getSelf().isNull());assertTrue(fb.getSelf().isNull());
                assertNull(fa.getScope());assertNull(fb.getScope());assertNull(fa.getCalledClass());assertNull(fb.getCalledClass());assertNull(fa.getReflectedMethod());
                first.pushCall(TraceInfo.UNKNOWN,null,null,"factory_function",null,null);second.pushCall(TraceInfo.UNKNOWN,null,null,"factory_function",null,null);
                try {assertEquals("function",fa.__invoke(first).toString());assertEquals("function",fb.__invoke(second).toString());}
                finally {second.popCall();first.popCall();}
                Closure stat=clazz.findMethod("stat").getClosure(second,b);assertTrue(stat.getSelf().isNull());assertTrue(stat.isStaticClosure());assertEquals("static",stat.__invoke(second).toString());
            } finally {if (second!=null)second.doFinal();first.doFinal();}
        }
    }
    @Test public void parentEnvironmentSharedMetadataDoesNotShareRuntimeClosures() throws Throwable {sharedMetadata(true);}
    @Test public void independentEnvironmentSharedMetadataDoesNotShareRuntimeClosures() throws Throwable {sharedMetadata(false);}
    @Test public void legacyStaticReflectionReceiverRejectionRemains() throws Throwable {
        var scope=newScope();Environment env=new Environment(scope,new ByteArrayOutputStream());
        try {
            var module=compile(env,"class LegacyFactory {static function value(){return 1;}} return new ReflectionMethod('LegacyFactory','value');",PhpLanguageProfile.JPHP_LEGACY,false);
            var reflected=module.include(env).toObject(php.runtime.ext.core.reflection.ReflectionMethod.class);
            IObject object=module.findClass("LegacyFactory").newObject(env,TraceInfo.UNKNOWN,false);
            try {reflected.getClosure(env,new php.runtime.memory.ObjectMemory(object));fail("Legacy static receiver became accepted");}
            catch (php.runtime.lang.exception.BaseBaseException expected) {assertTrue(expected.getMessage(env).toString().contains("Cannot use method as non static"));}
        } finally {env.doFinal();}
    }
    @Test public void nativeInvalidReflectionMethodReceiversSourceAndDump() throws Throwable {
        String source, expected;
        try (InputStream input = getClass().getClassLoader().getResourceAsStream("resources/type_hinting/php85_reflection_closure_receivers.php")) {
            assertNotNull(input);source = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        try (InputStream input = getClass().getClassLoader().getResourceAsStream("resources/type_hinting/php85_reflection_closure_receivers.expected")) {
            assertNotNull(input);expected = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        ArrayList<String> differences = new ArrayList<>();
        for (boolean debug : new boolean[]{false, true}) for (boolean dumped : new boolean[]{false, true}) {
            var scope = newScope();scope.setDebugMode(debug);scope.registerExtension(new StandardExtension());
            ByteArrayOutputStream output = new ByteArrayOutputStream();Environment env = new Environment(scope, output);
            try {
                compile(env, source, PhpLanguageProfile.PHP_8_5, dumped).include(env);env.getDefaultBuffer().flush();
                String actual = output.toString(StandardCharsets.UTF_8);
                if (!expected.equals(actual)) differences.add("debug=" + debug + " dumped=" + dumped + " actual=" + actual);
            } catch (Throwable error) {
                differences.add("debug=" + debug + " dumped=" + dumped + " error=" + error);
            } finally {env.doFinal();}
        }
        assertTrue(String.join("\n", differences), differences.isEmpty());
    }
}
