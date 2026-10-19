package org.develnext.jphp.core.compiler.jvm;

import com.google.gson.*;
import org.junit.Test;
import php.runtime.*;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.*;
import php.runtime.lang.exception.BaseError;
import php.runtime.loader.dump.ModuleDumper;
import php.runtime.reflection.ModuleEntity;
import java.io.*;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.*;

/** Native match order/value controls, dump transport and isolated legacy/admission boundaries. */
public class PhpMatchExpressionTest extends JvmCompilerCase {
    private JsonArray controls()throws IOException {
        try(var input=getClass().getClassLoader().getResourceAsStream("resources/type_hinting/php85_match_controls.json")) {
            assertNotNull(input);return new Gson().fromJson(new String(input.readAllBytes(),StandardCharsets.UTF_8),JsonObject.class).getAsJsonArray("cases");
        }
    }
    private ModuleEntity compile(Environment env,String source,PhpLanguageProfile profile,boolean dump)throws Throwable {
        var context=new Context(source);context.setPhpLanguageProfile(profile);
        var module=new JvmCompiler(env,context).compile(false);
        if(dump){var bytes=new ByteArrayOutputStream();var dumper=new ModuleDumper(context,env,true);dumper.save(module,bytes);module=dumper.load(new ByteArrayInputStream(bytes.toByteArray()));}
        env.getScope().loadModule(module);env.getScope().addUserModule(module);env.registerModule(module);return module;
    }
    @Test public void nativeIdentityOrderReferencesCowThrowAndGeneratorSourceAndDump()throws Throwable {
        for(var item:controls()) {
            var sample=item.getAsJsonObject();if(sample.get("exit").getAsInt()!=0)continue;
            for(boolean debug:new boolean[]{false,true})for(boolean dump:new boolean[]{false,true})for(boolean strict:new boolean[]{false,true}) {
                var scope=newScope();scope.setDebugMode(debug);scope.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
                var output=new ByteArrayOutputStream();var env=new Environment(scope,output);env.getDefaultBuffer().setChunkSize(0);
                try {
                    String source=(strict?"declare(strict_types=1);":"")+sample.get("code").getAsString();
                    var module=compile(env,source,PhpLanguageProfile.PHP_8_5,dump);assertNull(env.getRuntimeOwnership());
                    module.include(env);env.getDefaultBuffer().flush();
                    assertEquals(sample.get("id").getAsString()+" debug="+debug+" dump="+dump+" strict="+strict,sample.get("stdout").getAsString(),output.toString(StandardCharsets.UTF_8));
                }finally{env.doFinal();scope.getClassLoader().close();}
            }
        }
    }
    @Test public void invalidNativeFormsRejectDuringCompileBeforeEffects()throws Throwable {
        for(var item:controls()) {
            var sample=item.getAsJsonObject();if(sample.get("exit").getAsInt()==0)continue;
            for(boolean debug:new boolean[]{false,true}) {
                var scope=newScope();scope.setDebugMode(debug);scope.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
                var output=new ByteArrayOutputStream();var env=new Environment(scope,output);
                try {
                    var context=new Context(sample.get("code").getAsString());context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
                    Throwable failure=assertThrows(Throwable.class,()->new JvmCompiler(env,context).compile(false));
                    assertTrue(failure.toString(),failure instanceof BaseError || failure instanceof php.runtime.exceptions.support.ErrorException diagnostic
                            && (diagnostic.getType()==php.runtime.exceptions.support.ErrorType.E_PARSE || diagnostic.getType()==php.runtime.exceptions.support.ErrorType.E_COMPILE_ERROR));
                    env.getDefaultBuffer().flush();assertEquals(sample.get("id").getAsString(),"",output.toString(StandardCharsets.UTF_8));
                }finally{env.doFinal();scope.getClassLoader().close();}
            }
        }
    }
    @Test public void legacyMatchIdentifierRemainsAFunctionSourceAndDump()throws Throwable {
        for(boolean debug:new boolean[]{false,true})for(boolean dump:new boolean[]{false,true}) {
            var scope=newScope();scope.setDebugMode(debug);scope.setPhpLanguageProfile(PhpLanguageProfile.JPHP_LEGACY);
            var output=new ByteArrayOutputStream();var env=new Environment(scope,output);
            try {
                compile(env,"function match($x){return $x+1;}echo match(2);",PhpLanguageProfile.JPHP_LEGACY,dump).include(env);
                env.getDefaultBuffer().flush();assertEquals("3",output.toString(StandardCharsets.UTF_8));
            }finally{env.doFinal();scope.getClassLoader().close();}
        }
    }
    @Test public void unfinishedOwnedMatchAndThrowPlansRejectBeforeModuleEffects()throws Throwable {
        for(String source:new String[]{"echo 'effect';return match(1){1=>2};","echo 'effect';return throw new Exception('x');"})
            for(boolean dump:new boolean[]{false,true}) {
                var scope=newScope();scope.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
                var output=new ByteArrayOutputStream();var env=new Environment(scope,output);var domain=new PhpOwnershipDomain();
                try {
                    var module=compile(env,source,PhpLanguageProfile.PHP_8_5,dump);var method=module.getNativeMethod();
                    var plan=PhpRuntimeOwnership.readRuntimePlan(module.getData(),method.getName(),PhpOwnershipExecution.descriptor(method));
                    assertTrue(plan.unsupported().toString(),plan.unsupported().stream().anyMatch(s->s.equals(source.contains("match")?"match-expression":"throw-expression")));
                    var authority=new PhpRuntimeOwnership.Authority(env,domain);
                    try(var execution=authority.enter()) {assertThrows(IllegalArgumentException.class,()->authority.registerModule(module,module.getData(),(p,m,b)->{}));}
                    env.getDefaultBuffer().flush();assertEquals("",output.toString(StandardCharsets.UTF_8));
                }finally{env.doFinal();scope.getClassLoader().close();}
            }
    }
}
