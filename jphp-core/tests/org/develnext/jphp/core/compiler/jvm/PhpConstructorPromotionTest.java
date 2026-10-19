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

/** Native PHP 8.5 constructor, default initializer and transitive array COW controls. */
public class PhpConstructorPromotionTest extends JvmCompilerCase {
    private JsonArray controls() throws IOException {
        try (var input=getClass().getClassLoader().getResourceAsStream("resources/type_hinting/php85_constructor_promotion_controls.json")) {
            assertNotNull(input);
            return new Gson().fromJson(new String(input.readAllBytes(),StandardCharsets.UTF_8),JsonObject.class).getAsJsonArray("cases");
        }
    }
    private php.runtime.reflection.ModuleEntity compile(Environment env,String code,PhpLanguageProfile profile,boolean dumped) throws Throwable {
        var context=new Context(code);context.setPhpLanguageProfile(profile);
        var module=new JvmCompiler(env,context).compile(false);
        if (dumped) {
            var data=new ByteArrayOutputStream();var dumper=new ModuleDumper(context,env,true);
            dumper.save(module,data);module=dumper.load(new ByteArrayInputStream(data.toByteArray()));
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
                String code=(strict?"declare(strict_types=1);":"")+row.get("code").getAsString();
                if (negative) {
                    String invalid=(strict?"declare(strict_types=1);":"")+"echo 'effect';"+row.get("code").getAsString();
                    assertThrows(mode,Throwable.class,()->compile(env,invalid,PhpLanguageProfile.PHP_8_5,dump));
                    assertEquals(mode,0,output.size());
                } else {
                    compile(env,code,PhpLanguageProfile.PHP_8_5,dump).include(env);env.getDefaultBuffer().flush();
                    assertEquals(mode,row.get("stdout"+(strict?"_strict":"")).getAsString(),output.toString(StandardCharsets.UTF_8));
                    assertNull(env.getRuntimeOwnership());
                }
            } finally {env.doFinal();}
        }
    }
    @Test public void nativeTypedReferenceDefaultBodyInheritanceTraitReflectionAndCowSourceAndDump() throws Throwable {
        for (var entry:controls()) {
            var row=entry.getAsJsonObject();
            if (!row.get("negative").getAsBoolean() && !row.get("id").getAsString().equals("anonymous")) check(row,false);
        }
    }
    @Test public void invalidPromotionAndInitializerDeclarationsRejectBeforeEffects() throws Throwable {
        for (var entry:controls()) {var row=entry.getAsJsonObject();if(row.get("negative").getAsBoolean())check(row,true);}
    }
    @Test public void nativeAnonymousConstructorPromotionSourceAndDump() throws Throwable {
        for (var entry:controls()) {var row=entry.getAsJsonObject();if(row.get("id").getAsString().equals("anonymous"))check(row,false);}
    }
    @Test public void nativeTransitiveArrayCopiesAcrossWriteOrdersAndDetachedOrigins() throws Throwable {
        JsonArray rows;
        try (var input=getClass().getClassLoader().getResourceAsStream("resources/type_hinting/php85_array_cow_chain_controls.json")) {
            assertNotNull(input);rows=new Gson().fromJson(new String(input.readAllBytes(),StandardCharsets.UTF_8),JsonObject.class).getAsJsonArray("cases");
        }
        assertEquals(57,rows.size());for(var row:rows)check(row.getAsJsonObject(),false);
    }
    @Test public void legacyProfileRemainsExplicitAndDoesNotAcceptPromotedSyntax() throws Throwable {
        var scope=newScope();var env=new Environment(scope);
        try {
            assertThrows(Throwable.class,()->compile(env,"class P{function __construct(public int $x){}}",PhpLanguageProfile.JPHP_LEGACY,false));
            assertEquals(PhpLanguageProfile.JPHP_LEGACY,scope.getPhpLanguageProfile());
        } finally {env.doFinal();}
    }
    @Test public void unsupportedExplicitOwnershipRejectsBeforeModuleEffects() throws Throwable {
        var scope=newScope();scope.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
        var output=new ByteArrayOutputStream();var env=new Environment(scope,output);
        var module=compile(env,"echo 'effect';class P{function __construct(public int $x){}}$p=new P(7);",PhpLanguageProfile.PHP_8_5,false);
        var authority=new PhpRuntimeOwnership.Authority(env,new PhpOwnershipDomain());
        try (var execution=authority.enter()) {
            try {assertThrows(Throwable.class,()->authority.registerModule(module,module.getData(),(p,b,c)->{}));assertEquals(0,output.size());}
            finally {env.doFinal();authority.close();}
        }
    }
    @Test public void detachedArrayOriginsDoNotClearOrMutateDescendantViews() {
        var root=new php.runtime.memory.ArrayMemory();root.add(1);
        var middle=root.toImmutable().toValue(php.runtime.memory.ArrayMemory.class);
        var leaf=middle.toImmutable().toValue(php.runtime.memory.ArrayMemory.class);
        middle.refOfPush().assign(9);root.refOfPush().assign(2);
        assertEquals(1,leaf.size());assertEquals(1,leaf.valueOfIndex(0).toInteger());
        var iterator=leaf.foreachIterator(false,false);assertTrue(iterator.next());assertEquals(1,iterator.getValue().toInteger());assertFalse(iterator.next());
        var copy=leaf.toImmutable().toValue(php.runtime.memory.ArrayMemory.class);
        middle.unset();leaf.refOfPush().assign(3);
        assertEquals(1,copy.size());assertEquals(1,copy.valueOfIndex(0).toInteger());
    }
}
