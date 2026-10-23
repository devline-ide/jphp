package php.runtime;
import java.io.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import org.junit.Test;
import php.runtime.env.*;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.loader.dump.ModuleDumper;
import org.develnext.jphp.core.compiler.jvm.JvmCompiler;
import static org.junit.Assert.*;
public class PhpReceiverOrdinaryFallbackTest {
    @Test public void ordinaryReceiverStaticErrorAndBoundLexicalSelfSourceAndRealPhb()throws Throwable {
        Path file=Path.of(System.getProperty("qa.receiver.fallback"));
        byte[] expected=Files.readAllBytes(file.resolveSibling("receiver-fallback.stdout"));
        assertEquals(3,new String(expected,StandardCharsets.UTF_8).lines().count());
        for(boolean debug:new boolean[]{false,true})for(boolean phb:new boolean[]{false,true}) {
            var scope=new CompileScope();scope.setDebugMode(debug);scope.setPhpLanguageProfile(PhpLanguageProfile.JPHP_LEGACY);
            scope.registerExtension(new php.runtime.ext.CoreExtension());scope.registerExtension(new php.runtime.ext.SPLExtension());
            scope.registerExtension(new org.develnext.jphp.zend.ext.standard.StandardExtension());
            var output=new ByteArrayOutputStream();var env=new Environment(scope,output);Throwable primary=null;
            try {
                assertNull(PhpOwnershipExecution.currentEntry(env));
                var context=new Context(Files.readString(file,StandardCharsets.UTF_8),file.toFile());context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
                var module=new JvmCompiler(env,context).compile(false);
                assertEquals(PhpLanguageProfile.JPHP_LEGACY,scope.getPhpLanguageProfile());assertEquals(PhpLanguageProfile.PHP_8_5,module.getPhpLanguageProfile());
                if(phb) {
                    var wire=new ByteArrayOutputStream();var dumper=new ModuleDumper(context,env,debug);dumper.save(module,wire);
                    assertEquals(29,ModuleDumper.readHeader(new ByteArrayInputStream(wire.toByteArray())).getCompilerAbiVersion());
                    module=dumper.load(new ByteArrayInputStream(wire.toByteArray()));assertTrue(module.isCompiledArtifact());
                }
                scope.loadModule(module);scope.addUserModule(module);env.registerModule(module);module.include(env);env.flushAll();
                assertArrayEquals("ordinary fallback PHB="+phb+" debug="+debug,expected,output.toByteArray());
                assertNull(PhpOwnershipExecution.currentEntry(env));
            } catch(Throwable failure){primary=failure;throw failure;}
            finally {try{env.doFinal();}catch(Throwable cleanup){if(primary==null)throw cleanup;else if(primary!=cleanup)primary.addSuppressed(cleanup);}}
        }
    }
}
