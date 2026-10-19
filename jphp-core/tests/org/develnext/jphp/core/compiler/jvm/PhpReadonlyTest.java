package org.develnext.jphp.core.compiler.jvm;
import org.junit.Test;
import static org.junit.Assert.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import php.runtime.env.*;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.loader.dump.ModuleDumper;

/** Exact native PHP 8.5 values; both compiler modes and caller-profile provenance. */
public class PhpReadonlyTest extends JvmCompilerCase {
    private byte[] resource(String name) throws IOException {
        try(var input=getClass().getClassLoader().getResourceAsStream("resources/readonly/"+name)) {
            assertNotNull(name,input);return input.readAllBytes();
        }
    }
    private void nativeCase(String name) throws Throwable {
        for(boolean debug:new boolean[]{false,true}) {
            var output=new ByteArrayOutputStream();var env=new Environment(newScope(),output);
            env.getScope().setDebugMode(debug);
            env.getScope().registerExtension(new org.develnext.jphp.zend.ext.standard.StandardExtension());
            try {
                String code=new String(resource(name+".php"),StandardCharsets.UTF_8);
                var context=new Context(code.substring(5));context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
                var module=new JvmCompiler(env,context).compile(false);
                if(isCompiled()) {
                    var dumper=new ModuleDumper(context,env,debug);var bytes=new ByteArrayOutputStream();
                    dumper.save(module,bytes);module=dumper.load(new ByteArrayInputStream(bytes.toByteArray()));
                }
                // The actual compiled declaration selects modern readonly semantics even from a legacy caller.
                env.getScope().setPhpLanguageProfile(PhpLanguageProfile.JPHP_LEGACY);
                env.getScope().loadModule(module);env.getScope().addUserModule(module);env.registerModule(module);
                module.include(env);env.flushAll();
                assertArrayEquals(name+" debug="+debug+" PHB="+isCompiled()+"\nexpected="+new String(resource(name+".expected"),StandardCharsets.UTF_8)+"\nactual="+output.toString(StandardCharsets.UTF_8),resource(name+".expected"),output.toByteArray());
            } finally {env.doFinal();}
        }
    }
    @Test public void native_reflection_class_literal_constants() throws Throwable {nativeCase("reflection-class-literal-constants");}
    @Test public void legacyReflectionClassConstantsRetainHistoricalValues() throws Throwable {
        for(boolean debug:new boolean[]{false,true}) {
            var output=new ByteArrayOutputStream();var env=new Environment(newScope(),output);env.getScope().setDebugMode(debug);
            env.getScope().registerExtension(new org.develnext.jphp.zend.ext.standard.StandardExtension());
            try {
                var context=new Context(new String(resource("reflection-class-literal-constants.php"),StandardCharsets.UTF_8).substring(5));
                context.setPhpLanguageProfile(PhpLanguageProfile.JPHP_LEGACY);var module=new JvmCompiler(env,context).compile(false);
                if(isCompiled()) {var dumper=new ModuleDumper(context,env,debug);var bytes=new ByteArrayOutputStream();dumper.save(module,bytes);module=dumper.load(new ByteArrayInputStream(bytes.toByteArray()));}
                env.getScope().loadModule(module);env.getScope().addUserModule(module);env.registerModule(module);module.include(env);env.flushAll();
                assertEquals("a:7:{i:0;i:64;i:1;i:32;i:2;i:16;i:3;i:64;i:4;i:32;i:5;i:64;i:6;i:32;}",output.toString(StandardCharsets.UTF_8));
            } finally {env.doFinal();}
        }
    }
    private void rejectsDeclaration(String name,String fragment) throws Throwable {
        for(boolean debug:new boolean[]{false,true}) {
            var env=new Environment(newScope(),new ByteArrayOutputStream());env.getScope().setDebugMode(debug);
            try {
                String code=new String(resource(name+".php"),StandardCharsets.UTF_8);
                var context=new Context(code.substring(5));context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
                try {new JvmCompiler(env,context).compile(false);fail("Accepted readonly declaration "+name);}
                catch(php.runtime.exceptions.support.ErrorException error) {
                    assertTrue(name+": "+error.getMessage(),error.getMessage().toLowerCase().contains(fragment.toLowerCase()));
                }
            } finally {env.doFinal();}
        }
    }
    @Test public void native_property_basic() throws Throwable {nativeCase("property-basic");}
    @Test public void native_property_global_uninitialized() throws Throwable {nativeCase("property-global-uninitialized");}
    @Test public void native_property_initialization_descendant() throws Throwable {nativeCase("property-initialization-descendant");}
    @Test public void native_property_initialization_ancestor() throws Throwable {nativeCase("property-initialization-ancestor");}
    @Test public void native_property_sibling_scope() throws Throwable {nativeCase("property-sibling-scope");}
    @Test public void native_property_uninitialized_read() throws Throwable {nativeCase("property-uninitialized-read");}
    @Test public void native_property_unset_initialized() throws Throwable {nativeCase("property-unset-initialized");}
    @Test public void native_property_unset_uninitialized() throws Throwable {nativeCase("property-unset-uninitialized");}
    @Test public void native_property_reference_initialized() throws Throwable {nativeCase("property-reference-initialized");}
    @Test public void native_property_reference_uninitialized() throws Throwable {nativeCase("property-reference-uninitialized");}
    @Test public void native_property_reference_assignment() throws Throwable {nativeCase("property-reference-assignment");}
    @Test public void native_property_reference_parameter() throws Throwable {nativeCase("property-reference-parameter");}
    @Test public void native_property_array_offsets() throws Throwable {nativeCase("property-array-offsets");}
    @Test public void native_property_array_uninitialized() throws Throwable {nativeCase("property-array-uninitialized");}
    @Test public void native_property_object_interior() throws Throwable {nativeCase("property-object-interior");}
    @Test public void native_property_increments_compound() throws Throwable {nativeCase("property-increments-compound");}
    @Test public void native_property_coalesce() throws Throwable {nativeCase("property-coalesce");}
    @Test public void native_property_type_failure_then_success() throws Throwable {nativeCase("property-type-failure-then-success");}
    @Test public void native_property_reflection() throws Throwable {nativeCase("property-reflection");}
    @Test public void native_property_promotion() throws Throwable {nativeCase("property-promotion");}
    @Test public void native_property_clone_reinitialize() throws Throwable {nativeCase("property-clone-reinitialize");}
    @Test public void native_property_clone_twice() throws Throwable {nativeCase("property-clone-twice");}
    @Test public void native_property_clone_helper() throws Throwable {nativeCase("property-clone-helper");}
    @Test public void native_property_clone_unset() throws Throwable {nativeCase("property-clone-unset");}
    @Test public void native_property_clone_unset_then_init() throws Throwable {nativeCase("property-clone-unset-then-init");}
    @Test public void native_property_clone_reference() throws Throwable {nativeCase("property-clone-reference");}
    @Test public void native_property_clone_array_offset() throws Throwable {nativeCase("property-clone-array-offset");}
    @Test public void native_property_clone_foreign_target() throws Throwable {nativeCase("property-clone-foreign-target");}
    @Test public void native_property_default_unserialize() throws Throwable {nativeCase("property-default-unserialize");}
    @Test public void native_property_unserialize_duplicate() throws Throwable {nativeCase("property-unserialize-duplicate");}
    @Test public void native_property_unserialize_reference() throws Throwable {nativeCase("property-unserialize-reference");}
    @Test public void native_property_magic_unserialize() throws Throwable {nativeCase("property-magic-unserialize");}
    @Test public void native_class_basic() throws Throwable {nativeCase("class-basic");}
    @Test public void native_class_inheritance() throws Throwable {nativeCase("class-inheritance");}
    @Test public void native_class_trait() throws Throwable {nativeCase("class-trait");}
    @Test public void native_class_magic_set() throws Throwable {nativeCase("class-magic-set");}
    @Test public void native_property_foreach_reference() throws Throwable {nativeCase("property-foreach-reference");}
    @Test public void native_property_bound_closure() throws Throwable {nativeCase("property-bound-closure");}
    @Test public void native_property_unserialize_alias_write() throws Throwable {nativeCase("property-unserialize-alias-write");}
    @Test public void native_property_clone_unserialize_shared_alias() throws Throwable {nativeCase("property-clone-unserialize-shared-alias");}
    @Test public void native_property_clone_throw_cleanup() throws Throwable {nativeCase("property-clone-throw-cleanup");}
    @Test public void native_property_clone_nested_target() throws Throwable {nativeCase("property-clone-nested-target");}
    @Test public void native_property_private_initialization() throws Throwable {nativeCase("property-private-initialization");}
    @Test public void native_property_reflection_constants() throws Throwable {nativeCase("property-reflection-constants");}
    @Test public void native_property_promotion_reference() throws Throwable {nativeCase("property-promotion-reference");}
    @Test public void native_property_coalesce_initialized_null() throws Throwable {nativeCase("property-coalesce-initialized-null");}
    @Test public void native_property_strict_type_failure() throws Throwable {nativeCase("property-strict-type-failure");}
    @Test public void native_class_anonymous() throws Throwable {nativeCase("class-anonymous");}
    @Test public void native_class_final_abstract_orders() throws Throwable {nativeCase("class-final-abstract-orders");}
    @Test public void native_class_deferred() throws Throwable {nativeCase("class-deferred");}
    @Test public void reject_untyped() throws Throwable {rejectsDeclaration("untyped","must have type");}
    @Test public void reject_static() throws Throwable {rejectsDeclaration("static","cannot be readonly");}
    @Test public void reject_default() throws Throwable {rejectsDeclaration("default","cannot have default");}
    @Test public void reject_class_untyped() throws Throwable {rejectsDeclaration("class-untyped","must have type");}
    @Test public void reject_class_static() throws Throwable {rejectsDeclaration("class-static","cannot be readonly");}
    @Test public void reject_class_default() throws Throwable {rejectsDeclaration("class-default","cannot have default");}
    @Test public void reject_class_nonreadonly_parent() throws Throwable {rejectsDeclaration("class-nonreadonly-parent","cannot extend non-readonly");}
    @Test public void reject_class_nonreadonly_child() throws Throwable {rejectsDeclaration("class-nonreadonly-child","cannot extend readonly");}
    @Test public void reject_class_trait_mutable() throws Throwable {rejectsDeclaration("class-trait-mutable","non-readonly property");}
    @Test public void reject_property_redeclare_mutable() throws Throwable {rejectsDeclaration("property-redeclare-mutable","Cannot redeclare readonly");}
    @Test public void reject_property_redeclare_readonly() throws Throwable {rejectsDeclaration("property-redeclare-readonly","Cannot redeclare non-readonly");}
    @Test public void reject_property_trait_conflict() throws Throwable {rejectsDeclaration("property-trait-conflict","incompatible");}
    @Test public void reject_method_modifier() throws Throwable {rejectsDeclaration("method-modifier","readonly modifier");}
    @Test public void reject_readonly_enum() throws Throwable {rejectsDeclaration("readonly-enum","enum");}
    @Test public void reject_readonly_interface() throws Throwable {rejectsDeclaration("readonly-interface","interface");}
    @Test public void reject_readonly_trait() throws Throwable {rejectsDeclaration("readonly-trait","trait");}
    @Test public void legacyReadonlyNameAndMutablePropertiesRemainCompatible() throws Throwable {
        for(boolean debug:new boolean[]{false,true}) {
            var output=new ByteArrayOutputStream();var env=new Environment(newScope(),output);env.getScope().setDebugMode(debug);
            try {
                var context=new Context("class readonly {public $x=1;} $r=new readonly;$r->x=2;echo $r->x;");
                context.setPhpLanguageProfile(PhpLanguageProfile.JPHP_LEGACY);
                var module=new JvmCompiler(env,context).compile(false);
                if(isCompiled()) {var d=new ModuleDumper(context,env,debug);var b=new ByteArrayOutputStream();d.save(module,b);module=d.load(new ByteArrayInputStream(b.toByteArray()));}
                env.getScope().loadModule(module);env.getScope().addUserModule(module);env.registerModule(module);module.include(env);env.flushAll();
                assertEquals("2",output.toString(StandardCharsets.UTF_8));
            } finally {env.doFinal();}
        }
    }
    private void rejectsRuntimeDeclaration(String name,String fragment) throws Throwable {
        for(boolean debug:new boolean[]{false,true}) {
            var env=new Environment(newScope(),new ByteArrayOutputStream());env.getScope().setDebugMode(debug);
            try {
                String code=new String(resource(name+".php"),StandardCharsets.UTF_8);var context=new Context(code.substring(5));context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
                try {
                    var module=new JvmCompiler(env,context).compile(false);
                    if(isCompiled()){var d=new ModuleDumper(context,env,debug);var b=new ByteArrayOutputStream();d.save(module,b);module=d.load(new ByteArrayInputStream(b.toByteArray()));}
                    env.getScope().loadModule(module);env.getScope().addUserModule(module);env.registerModule(module);module.include(env);
                    fail("Accepted readonly runtime declaration "+name);
                }catch(php.runtime.exceptions.support.ErrorException error){assertTrue(name+": "+error.getMessage(),error.getMessage().toLowerCase().contains(fragment.toLowerCase()));}
            }finally{env.doFinal();}
        }
    }
    @Test public void native_class_deferred_trait() throws Throwable {nativeCase("class-deferred-trait");}
    @Test public void native_class_deferred_parent() throws Throwable {nativeCase("class-deferred-parent");}
    @Test public void native_property_promotion_readonly_order() throws Throwable {nativeCase("property-promotion-readonly-order");}
    @Test public void reject_deferred_trait_mutable() throws Throwable {rejectsRuntimeDeclaration("deferred-trait-mutable","non-readonly property");}
    @Test public void reject_deferred_trait_conflict() throws Throwable {rejectsRuntimeDeclaration("deferred-trait-conflict","incompatible");}
    @Test public void reject_deferred_parent_mutable() throws Throwable {rejectsRuntimeDeclaration("deferred-parent-mutable","cannot extend non-readonly");}
    @Test public void native_reflection_class_constants() throws Throwable {nativeCase("reflection-class-constants");}
}
