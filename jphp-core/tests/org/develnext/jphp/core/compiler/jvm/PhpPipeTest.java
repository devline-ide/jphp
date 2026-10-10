package org.develnext.jphp.core.compiler.jvm;

import org.junit.Test;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.Context;
import php.runtime.env.Environment;
import php.runtime.env.TraceInfo;
import php.runtime.loader.dump.ModuleDumper;
import php.runtime.memory.ArrayMemory;
import php.runtime.memory.StringMemory;
import php.runtime.memory.output.serialization.PhpSerialization;
import java.io.*;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.*;

/** Physical PHP 8.5.11 oracle for ordered pipe evaluation and callable SEND semantics. */
public class PhpPipeTest extends JvmCompilerCase {
    private Environment environment(boolean debug) {
        var scope=newScope();scope.setDebugMode(debug);
        scope.registerExtension(new org.develnext.jphp.zend.ext.standard.StandardExtension());
        return new Environment(scope,new ByteArrayOutputStream());
    }
    private String resource(String suffix) throws Exception {
        try(InputStream in=getClass().getClassLoader().getResourceAsStream("resources/php85_pipe."+suffix)) {
            assertNotNull(in);return new String(in.readAllBytes(),StandardCharsets.UTF_8);
        }
    }
    private void nativeOracle(boolean debug) throws Throwable {
        for(boolean strict:new boolean[]{false,true}) for(boolean dumped:new boolean[]{false,true}) {
            Environment env=environment(debug);
            try {
                Context context=new Context((strict?"declare(strict_types=1);":"")+resource("php").substring(5));
                context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
                var module=new JvmCompiler(env,context).compile(false);
                if(dumped) {
                    var dumper=new ModuleDumper(context,env,true);var bytes=new ByteArrayOutputStream();
                    dumper.save(module,bytes);module=dumper.load(new ByteArrayInputStream(bytes.toByteArray()));
                }
                env.getScope().loadModule(module);env.getScope().addUserModule(module);env.registerModule(module);
                var trace=TraceInfo.valueOf(module.getContext().getFileName(),0,0,module.getInternalName());
                ArrayMemory actual=module.include(env).toValue(ArrayMemory.class);
                ArrayMemory expected=PhpSerialization.unserialize(env,trace,StringMemory.valueOf(resource(strict?"strict.expected":"weak.expected"))).toValue(ArrayMemory.class);
                assertTrue(expected.size()>=40);assertEquals(expected.size(),actual.size());
                for(int row=0;row<expected.size();row++)
                    assertEquals("debug="+debug+" strict="+strict+" dump="+dumped+" row="+row,
                            PhpSerialization.serialize(env,trace,expected.valueOfIndex(row)).toString(),
                            PhpSerialization.serialize(env,trace,actual.valueOfIndex(row)).toString());
            } finally {env.doFinal();}
        }
    }
    @Test public void nativePipeDebugSourceAndDump() throws Throwable {nativeOracle(true);}
    @Test public void nativePipeOptimizedSourceAndDump() throws Throwable {nativeOracle(false);}
    @Test public void pipeAstNeedsNoEnvironmentOrRuntimeEvaluation() throws Throwable {
        Context context=new Context("3 |> 'missingPipeFunction' |> 'anotherMissingFunction';");
        context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
        var analyzer=new org.develnext.jphp.core.syntax.SyntaxAnalyzer(null,new org.develnext.jphp.core.tokenizer.Tokenizer(context));
        assertNull(analyzer.getEnvironment());assertEquals(1,analyzer.getTree().size());
        var expression=(org.develnext.jphp.core.tokenizer.token.stmt.ExprStmtToken)analyzer.getTree().getFirst();
        assertNull(expression.getAsmExpr());
        assertEquals(2,expression.getTokens().stream().filter(t->t instanceof org.develnext.jphp.core.tokenizer.token.expr.operator.PipeExprToken).count());
    }
    @Test public void pipeRejectsLegacyUnparenthesizedArrowAndConstants() throws Throwable {
        for(String source:new String[]{"return 3 |> fn($x)=>$x;","return 3 |> static fn($x)=>$x;","const P = 3 |> 'strlen';"}) {
            Environment env=environment(false);
            try {
                Context context=new Context(source);context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
                try {new JvmCompiler(env,context).compile(false);fail(source);}
                catch(php.runtime.exceptions.support.ErrorException expected) {assertNotNull(expected.getMessage());}
            } finally {env.doFinal();}
        }
        Environment env=environment(false);
        try {
            Context context=new Context("return 3 |> 'pipePlus';");context.setPhpLanguageProfile(PhpLanguageProfile.JPHP_LEGACY);
            try {new JvmCompiler(env,context).compile(false);fail("Legacy pipe must be rejected");}
            catch(php.runtime.exceptions.support.ErrorException expected) {assertNotNull(expected.getMessage());}
        } finally {env.doFinal();}
    }
}
