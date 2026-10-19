package standard;

import org.develnext.jphp.core.compiler.jvm.JvmCompiler;
import org.develnext.jphp.zend.ext.standard.StandardExtension;
import org.junit.Test;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.CompileScope;
import php.runtime.env.Context;
import php.runtime.env.Environment;
import php.runtime.ext.CoreExtension;
import php.runtime.ext.SPLExtension;
import php.runtime.loader.dump.ModuleDumper;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import static org.junit.Assert.*;

public class Php85StandardFunctionsTest {
    private void verify(String group) throws Throwable {
        try (var resource=getClass().getResourceAsStream("/standard/php85_functions.tsv")) {
            assertNotNull(resource);
            for (String line:new String(resource.readAllBytes(),StandardCharsets.UTF_8).split("\\r?\\n")) {
                if (line.startsWith("#") || line.isEmpty()) continue;
                var row=line.split("\\t",-1);assertEquals(5,row.length);
                if (!row[0].equals(group)) continue;
                String source=new String(Base64.getDecoder().decode(row[3]),StandardCharsets.UTF_8);
                String expected=new String(Base64.getDecoder().decode(row[4]),StandardCharsets.UTF_8);
                for (boolean debug:new boolean[]{false,true}) for (boolean dumped:new boolean[]{false,true}) {
                    var scope=new CompileScope();scope.registerExtension(new CoreExtension());scope.registerExtension(new SPLExtension());
                    scope.registerExtension(new StandardExtension());scope.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);scope.setDebugMode(debug);
                    var env=new Environment(scope,new ByteArrayOutputStream());
                    try {
                        var context=new Context(source);context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
                        var module=new JvmCompiler(env,context).compile(false);
                        if (dumped) {
                            var dumper=new ModuleDumper(context,env,true);var output=new ByteArrayOutputStream();dumper.save(module,output);
                            module=dumper.load(new ByteArrayInputStream(output.toByteArray()));
                        }
                        scope.loadModule(module);scope.addUserModule(module);env.registerModule(module);
                        assertEquals(row[1]+" strict="+row[2]+" debug="+debug+" dump="+dumped,expected,module.include(env).toString());
                    } finally {env.doFinal();}
                }
            }
        }
    }
    @Test public void binaryStringsEmptyNeedlesAndRepeatedPrefixes() throws Throwable {verify("binary");}
    @Test public void weakStrictTypesAndNullDeprecations() throws Throwable {verify("types");}
    @Test public void stringableConversionAndOriginalExceptions() throws Throwable {verify("stringable");}
    @Test public void directIndirectAndCapturedCalls() throws Throwable {verify("routes");}
    @Test public void exactArityErrors() throws Throwable {verify("arity");}
    @Test public void listKeysInsertionOrderAndEmptyArrays() throws Throwable {verify("arrays");}
    @Test public void internalArrayPointerIsPreserved() throws Throwable {verify("pointer");}
    @Test public void phpDeclarationReflection() throws Throwable {verify("reflection");}
    @Test public void returnedValuesSeparateReferencesAndPreserveObjects() throws Throwable {verify("returns");}
}
