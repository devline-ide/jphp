package org.develnext.jphp.core.compiler.jvm;

import org.junit.Test;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.*;
import php.runtime.loader.dump.ModuleDumper;
import java.io.*;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.*;

/** Deferred declaration ids must identify physical entities, even when PHP names are identical. */
public class PhpNestedFunctionDeclarationTest extends JvmCompilerCase {
    private void modes(String source, String expected, int duplicateCount) throws Throwable {
        for (var profile : PhpLanguageProfile.values())
        for (boolean debug : new boolean[]{false,true}) for (boolean dumped : new boolean[]{false,true}) {
            var scope=newScope();scope.setPhpLanguageProfile(profile);scope.setDebugMode(debug);
            var output=new ByteArrayOutputStream();var env=new Environment(scope,output);
            try {
                var context=new Context(source);context.setPhpLanguageProfile(profile);
                var module=new JvmCompiler(env,context).compile(false);
                if (dumped) {
                    var dumper=new ModuleDumper(context,env,true);var bytes=new ByteArrayOutputStream();
                    dumper.save(module,bytes);module=dumper.load(new ByteArrayInputStream(bytes.toByteArray()));
                }
                int duplicates=0;
                for (var function:module.getFunctions()) if (function.getName().equals("same")) duplicates++;
                assertEquals(duplicateCount,duplicates);
                scope.loadModule(module);scope.addUserModule(module);env.registerModule(module);
                module.include(env);env.doFinal();
                assertEquals("debug="+debug+" PHB="+dumped,expected,output.toString(StandardCharsets.UTF_8));
            } finally {env.doFinal();scope.getClassLoader().close();}
        }
    }
    @Test public void closureDeclarationBetweenStaticFunctionsUsesItsOwnIdSourceDump() throws Throwable {
        modes("function mark($tag,$v){echo $tag;return $v;}"
                + "$c=function(){function f($a,$b){return $a+$b;}return f(mark('B',2),mark('A',1));};"
                + "echo $c(),tail();function tail(){return 'T';}","BA3T",0);
    }
    @Test public void conditionalDeclarationsWithIdenticalNamesKeepDistinctIdsSourceDump() throws Throwable {
        modes("function first(){return 'X';}if(false){function same(){return 'A';}}"
                + "else{function same(){return 'B';}}echo first(),same();"
                + "function last(){return 'Z';}echo last();","XBZ",2);
        for(boolean debug:new boolean[]{false,true})for(boolean dumped:new boolean[]{false,true}) {
            var scope=newScope();scope.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);scope.setDebugMode(debug);
            var output=new ByteArrayOutputStream();var env=new Environment(scope,output);
            try {
                var context=new Context("#[Attribute] class DeclMark{public function __construct(public string $value){}}"
                        +"if(false){#[DeclMark('A')] function same(){return 'A';}}else{#[DeclMark('B')] function same(){return 'B';}}"
                        +"echo (new ReflectionFunction('same'))->getAttributes()[0]->getArguments()[0],same();");
                context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
                var module=new JvmCompiler(env,context).compile(false);
                if(dumped){var dumper=new ModuleDumper(context,env,true);var bytes=new ByteArrayOutputStream();dumper.save(module,bytes);module=dumper.load(new ByteArrayInputStream(bytes.toByteArray()));}
                assertEquals(2,module.getFunctions().stream().filter(function->function.getName().equals("same")).count());
                for(var function:module.getFunctions())if(function.getName().equals("same"))assertEquals(1,function.getPhpAttributes().size());
                scope.loadModule(module);scope.addUserModule(module);env.registerModule(module);module.include(env);env.flushAll();
                assertEquals("attributed duplicate names debug="+debug+" PHB="+dumped,"BB",output.toString(StandardCharsets.UTF_8));
            }finally{env.doFinal();scope.getClassLoader().close();}
        }
    }
}
