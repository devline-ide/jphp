package org.develnext.jphp.core.compiler.jvm;

import java.io.*;
import java.nio.charset.StandardCharsets;
import org.junit.Test;
import php.runtime.Memory;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.*;
import php.runtime.loader.dump.ModuleDumper;
import php.runtime.memory.ArrayMemory;
import php.runtime.reflection.ModuleEntity;
import static org.junit.Assert.*;

/** Exit effects must survive immutable-return shortcuts, through real source/PHB invocations. */
public class PhpExitSideEffectTest extends JvmCompilerCase {
    private void check(String source, Integer exitCode, String expectedOutput) throws Throwable {
        for (PhpLanguageProfile profile : PhpLanguageProfile.values())
            for (boolean strict : profile == PhpLanguageProfile.PHP_8_5 ? new boolean[]{false, true} : new boolean[]{false})
                for (boolean debug : new boolean[]{false, true}) for (boolean dump : new boolean[]{false, true}) {
                    CompileScope scope = newScope(); scope.setPhpLanguageProfile(profile); scope.setDebugMode(debug);
                    ByteArrayOutputStream output = new ByteArrayOutputStream(); Environment env = new Environment(scope, output);
                    env.getDefaultBuffer().setChunkSize(0);
                    String mode = profile + " strict=" + strict + " debug=" + debug + " dump=" + dump;
                    try {
                        Context context = new Context((strict ? "declare(strict_types=1);" : "") + source); context.setPhpLanguageProfile(profile);
                        ModuleEntity module = new JvmCompiler(env, context).compile(false);
                        if (dump) { ByteArrayOutputStream bytes = new ByteArrayOutputStream(); ModuleDumper codec = new ModuleDumper(context, env, debug); codec.save(module, bytes); module = codec.load(new ByteArrayInputStream(bytes.toByteArray())); }
                        scope.loadModule(module); scope.addUserModule(module); env.registerModule(module);
                        if (exitCode != null) {
                            ModuleEntity invoked = module;
                            DieException failure = assertThrows(mode, DieException.class, () -> invoked.include(env));
                            assertEquals(mode, exitCode.intValue(), failure.getExitCode());
                        } else {
                            Memory result = module.include(env); ArrayMemory values = result.toValue(ArrayMemory.class);
                            assertEquals(mode, 4, values.size());
                            for (int index = 0; index < 4; index++) assertEquals(mode, 7, values.valueOfIndex(TraceInfo.UNKNOWN, index).toLong());
                        }
                        env.getDefaultBuffer().flush(); assertEquals(mode, expectedOutput, output.toString(StandardCharsets.UTF_8));
                    } finally { try { env.doFinal(); } finally { scope.getClassLoader().close(); } }
                }
    }
    @Test public void directFunctionExitStopsCallerAndNeverReturnsConstant() throws Throwable {
        check("function stop(){exit(37);}echo 'before;';stop();echo 'after;';", 37, "before;");
        check("function stop(){exit(37);return 9;}echo 'before;';stop();echo 'after;';", 37, "before;");
    }
    @Test public void instanceAndStaticMethodExitExecuteTheirBody() throws Throwable {
        check("class Stop{function run(){exit(37);}}echo 'before;';(new Stop)->run();echo 'after;';", 37, "before;");
        check("class Stop{static function run(){exit(37);}}echo 'before;';Stop::run();echo 'after;';", 37, "before;");
    }
    @Test public void pureClosureExitHandlesCodeStringAndDieAlias() throws Throwable {
        check("$stop=function(){exit(37);};echo 'before;';$stop();echo 'after;';", 37, "before;");
        check("$stop=function(){die(41);};echo 'before;';$stop();echo 'after;';", 41, "before;");
        check("$stop=function(){exit;};echo 'before;';$stop();echo 'after;';", 0, "before;");
        check("$stop=function(){exit('stopped');};echo 'before;';$stop();echo 'after;';", 0, "before;stopped");
    }
    @Test public void exitSkipsPhpCatchAndFinally() throws Throwable {
        check("function stop(){try{exit(37);}catch(Throwable $e){echo 'caught;';}finally{echo 'finally;';}}echo 'before;';stop();echo 'after;';", 37, "before;");
    }
    @Test public void realConstantReturnsKeepExistingResults() throws Throwable {
        check("function answer(){return 7;}class Answer{function value(){return 7;}static function number(){return 7;}}$f=function(){return 7;};return [answer(),(new Answer)->value(),Answer::number(),$f()];", null, "");
    }
}
