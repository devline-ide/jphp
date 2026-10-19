package org.develnext.jphp.core.compiler.jvm;
import org.junit.Test;
import static org.junit.Assert.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import php.runtime.env.*;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.loader.dump.ModuleDumper;
/** Original byte-exact native PHP 8.5 results; source and PHB execute through the normal runtime. */
public class PhpAttributesPipelineTest extends JvmCompilerCase {
    private byte[] resource(String path) throws IOException {
        try(var input=getClass().getClassLoader().getResourceAsStream("resources/attributes/"+path)) {
            assertNotNull(path,input);return input.readAllBytes();
        }
    }
    private void nativeCase(String path) throws Throwable {
        for(boolean debug:new boolean[]{false,true}) {
            var output=new ByteArrayOutputStream();var env=new Environment(newScope(),output);
            env.getScope().setDebugMode(debug);
            env.getScope().registerExtension(new org.develnext.jphp.zend.ext.standard.StandardExtension());
            String group=path.substring(0,path.indexOf('/')),name=path.substring(path.indexOf('/')+1)+".php";
            String[] siblings=group.equals("original") ? new String[]{name,"helper.php"}
                    : new String[]{name,"helper.php","occurrences-weak.php","occurrences-strict.php","ctor-weak.php","ctor-strict.php"};
            try(var fixture=PhpNativeTestResources.fixture("attributes/"+group,name,siblings)) {
                var file=fixture.file();
                byte[] original=PhpNativeTestResources.read("attributes/"+path+".php");
                assertArrayEquals("native fixture provenance "+path,resource(path+".php"),original);
                String code=new String(original,StandardCharsets.UTF_8);
                var context=new Context(code,file);context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
                var module=new JvmCompiler(env,context).compile(false);
                if(isCompiled()) {
                    var dumper=new ModuleDumper(context,env,debug);var bytes=new ByteArrayOutputStream();
                    dumper.save(module,bytes);module=dumper.load(new ByteArrayInputStream(bytes.toByteArray()));
                }
                env.getScope().setPhpLanguageProfile(PhpLanguageProfile.JPHP_LEGACY);
                env.getScope().loadModule(module);env.getScope().addUserModule(module);env.registerModule(module);
                module.include(env);env.flushAll();
                byte[] actual=PhpNativeTestResources.remapCallerDiagnostics(output.toByteArray(),fixture,group);
                assertArrayEquals(path+" debug="+debug+" PHB="+isCompiled()+" actual="+output.toString(StandardCharsets.UTF_8),resource(path+".expected"),actual);
            } finally {env.doFinal();}
        }
    }
    @Test public void native_original_lazy_new_arguments() throws Throwable {nativeCase("original/lazy-new-arguments");}
    @Test public void native_original_group_names_filters() throws Throwable {nativeCase("original/group-names-filters");}
    @Test public void native_original_targets_and_declarations() throws Throwable {nativeCase("original/targets-and-declarations");}
    @Test public void native_original_promotion_dual_target() throws Throwable {nativeCase("original/promotion-dual-target");}
    @Test public void native_original_trait_alias_scope() throws Throwable {nativeCase("original/trait-alias-scope");}
    @Test public void native_original_inheritance_origin() throws Throwable {nativeCase("original/inheritance-origin");}
    @Test public void native_original_lazy_validation() throws Throwable {nativeCase("original/lazy-validation");}
    @Test public void native_original_constructor_binding_weak() throws Throwable {nativeCase("original/constructor-binding-weak");}
    @Test public void native_original_constructor_binding_strict() throws Throwable {nativeCase("original/constructor-binding-strict");}
    @Test public void native_original_reflection_object_contract() throws Throwable {nativeCase("original/reflection-object-contract");}
    @Test public void native_original_marker_flags_inheritance() throws Throwable {nativeCase("original/marker-flags-inheritance");}
    @Test public void native_original_global_constant_reflection() throws Throwable {nativeCase("original/global-constant-reflection");}
    @Test public void native_original_raw_byte_arguments() throws Throwable {nativeCase("original/raw-byte-arguments");}
    @Test public void native_original_missing_attribute_autoload() throws Throwable {nativeCase("original/missing-attribute-autoload");}
    @Test public void native_review_legal_static_closure_and_firstclass() throws Throwable {nativeCase("review/legal-static-closure-and-firstclass");}
    @Test public void native_review_override_trait_alias_with_parent() throws Throwable {nativeCase("review/override-trait-alias-with-parent");}
    @Test public void native_review_override_promotion_isolated() throws Throwable {nativeCase("review/override-promotion-isolated");}
    @Test public void native_review_caller_weak_crossfile_strictness() throws Throwable {nativeCase("review/caller-weak-crossfile-strictness");}
    @Test public void native_review_caller_strict_crossfile_strictness() throws Throwable {nativeCase("review/caller-strict-crossfile-strictness");}
    @Test public void native_review_marker_zero_flags() throws Throwable {nativeCase("review/marker-zero-flags");}
    @Test public void native_review_marker_invalid_bits() throws Throwable {nativeCase("review/marker-invalid-bits");}
    @Test public void native_review_sensitive_value_object_contract() throws Throwable {nativeCase("review/sensitive-value-object-contract");}
}
