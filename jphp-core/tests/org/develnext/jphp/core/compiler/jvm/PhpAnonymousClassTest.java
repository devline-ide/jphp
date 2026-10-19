package org.develnext.jphp.core.compiler.jvm;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import org.junit.Test;
import php.runtime.*;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.*;
import php.runtime.loader.dump.ModuleDumper;
import static org.junit.Assert.*;

/** Pinned PHP 8.5 source/PHB controls; runtime class dependency resolution has its own regression. */
public class PhpAnonymousClassTest extends JvmCompilerCase {
    private JsonArray controls() throws IOException {
        try (var input=getClass().getClassLoader().getResourceAsStream("resources/type_hinting/php85_anonymous_class_controls.json")) {
            assertNotNull(input);
            return new Gson().fromJson(new String(input.readAllBytes(),StandardCharsets.UTF_8),JsonObject.class).getAsJsonArray("cases");
        }
    }
    private php.runtime.reflection.ModuleEntity compile(Environment env,String code,PhpLanguageProfile profile,boolean dumped) throws Throwable {
        var context=new Context(code);context.setPhpLanguageProfile(profile);
        var module=new JvmCompiler(env,context).compile(false);
        if (dumped) {
            var bytes=new ByteArrayOutputStream();var dumper=new ModuleDumper(context,env,true);
            dumper.save(module,bytes);module=dumper.load(new ByteArrayInputStream(bytes.toByteArray()));
        }
        env.scope.loadModule(module);env.scope.addUserModule(module);env.registerModule(module);return module;
    }
    private void check(JsonObject row,boolean negative) throws Throwable {
        for (boolean debug:new boolean[]{false,true}) for (boolean dump:new boolean[]{false,true}) for (boolean strict:new boolean[]{false,true}) {
            var scope=newScope();scope.setDebugMode(debug);scope.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
            scope.registerExtension(new org.develnext.jphp.zend.ext.standard.StandardExtension());
            var output=new ByteArrayOutputStream();var env=new Environment(scope,output);env.getDefaultBuffer().setChunkSize(0);
            String mode=row.get("id").getAsString()+" debug="+debug+" dump="+dump+" strict="+strict;
            try {
                if (negative) {
                    String code=(strict?"declare(strict_types=1);":"")+"echo 'effect';"+row.get("code").getAsString();
                    assertThrows(mode,Throwable.class,()->compile(env,code,PhpLanguageProfile.PHP_8_5,dump));
                    assertEquals(mode,0,output.size());
                } else {
                    String code=(strict?"declare(strict_types=1);":"")+row.get("code").getAsString();
                    compile(env,code,PhpLanguageProfile.PHP_8_5,dump).include(env);env.getDefaultBuffer().flush();
                    assertEquals(mode,row.get("stdout"+(strict?"_strict":"")).getAsString(),output.toString(StandardCharsets.UTF_8));
                    assertNull(env.getRuntimeOwnership());
                }
            } finally {env.doFinal();}
        }
    }
    @Test public void nativeIdentityInheritanceTraitsScopeSelfTypesReflectionCloneAndSerializationSourceAndDump() throws Throwable {
        for (var entry:controls()) {
            var row=entry.getAsJsonObject();String name=row.get("id").getAsString();
            if (!row.get("negative").getAsBoolean() && !name.contains("generator") && !name.equals("autoload_parent")) check(row,false);
        }
    }
    @Test public void nativeGeneratorMethodsAndConstructorPromotionEntrySourceAndDump() throws Throwable {
        for (var entry:controls()) {var row=entry.getAsJsonObject();if(row.get("id").getAsString().contains("generator"))check(row,false);}
    }
    @Test public void invalidAnonymousConstantsAndInheritanceRejectBeforeEffects() throws Throwable {
        for (var entry:controls()) {var row=entry.getAsJsonObject();if(row.get("negative").getAsBoolean())check(row,true);}
    }
    @Test public void nativeAutoloadParentBeforeConstructorArgumentsSourceAndDump() throws Throwable {
        for (var entry:controls()) {var row=entry.getAsJsonObject();if(row.get("id").getAsString().equals("autoload_parent"))check(row,false);}
    }
    @Test public void legacyProfileRejectsAnonymousSyntaxBeforeEffects() throws Throwable {
        var scope=newScope();var output=new ByteArrayOutputStream();var env=new Environment(scope,output);
        try {assertThrows(Throwable.class,()->compile(env,"echo 'effect';new class{};",PhpLanguageProfile.JPHP_LEGACY,false));assertEquals(0,output.size());}
        finally {env.doFinal();}
    }
    @Test public void unsupportedExplicitOwnershipRejectsBeforeModuleEffects() throws Throwable {
        var scope=newScope();scope.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
        var output=new ByteArrayOutputStream();var env=new Environment(scope,output);
        var module=compile(env,"echo 'effect';$a=new class{public $n=4;};",PhpLanguageProfile.PHP_8_5,false);
        var authority=new PhpRuntimeOwnership.Authority(env,new PhpOwnershipDomain());
        try (var execution=authority.enter()) {
            try {assertThrows(Throwable.class,()->authority.registerModule(module,module.getData(),(p,b,c)->{}));assertEquals(0,output.size());}
            finally {env.doFinal();authority.close();}
        }
    }
}
