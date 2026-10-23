package org.develnext.jphp.core.compiler.jvm;

import org.junit.Test;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.*;
import php.runtime.loader.dump.ClassDumper;
import php.runtime.loader.dump.ModuleDumper;
import php.runtime.loader.dump.io.DumpException;
import php.runtime.reflection.*;
import java.io.*;
import java.util.*;
import static org.junit.Assert.*;

/** Real compiler output and public declaration APIs; cold decoding never acquires class/provider authority. */
public class PhpAnonymousTraitTemplateProofTest extends JvmCompilerCase {
    private static final String SOURCE="trait NestedPlainBase {public function value(){return 73;}}"
            +"trait NestedPlainUse {use NestedPlainBase;}"
            +"function make_nested_plain(){return new class {use NestedPlainUse;};}";
    private static final class LookupTrap extends Environment {
        final ByteArrayOutputStream output;boolean armed;int lookups;
        LookupTrap(CompileScope scope,ByteArrayOutputStream output){super(scope,output);this.output=output;}
        @Override public ClassEntity fetchClass(String name,boolean autoload){
            if(armed){lookups++;throw new AssertionError("Cold declaration consulted provider "+name);}
            return super.fetchClass(name,autoload);
        }
        @Override public ClassEntity fetchClass(String name,boolean autoload,PhpLanguageProfile profile){
            if(armed){lookups++;throw new AssertionError("Cold declaration consulted profile provider "+name);}
            return super.fetchClass(name,autoload,profile);
        }
        @Override public ClassEntity fetchClass(String name,String lowerName,boolean autoload){
            if(armed){lookups++;throw new AssertionError("Cold declaration consulted canonical provider "+name);}
            return super.fetchClass(name,lowerName,autoload);
        }
    }
    private final class Fixture implements AutoCloseable {
        final LookupTrap env;final Context context;final ModuleEntity module;final ClassEntity template;final boolean debug;
        Fixture(boolean debug,boolean phb)throws Throwable {
            this.debug=debug;var scope=newScope();scope.setDebugMode(debug);scope.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
            env=new LookupTrap(scope,new ByteArrayOutputStream());env.getDefaultBuffer().setChunkSize(0);
            context=new Context(SOURCE);context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
            ModuleEntity compiled=new JvmCompiler(env,context).compile(false);
            if(phb){var bytes=new ByteArrayOutputStream();var codec=new ModuleDumper(context,env,debug);
                codec.save(compiled,bytes);compiled=codec.load(new ByteArrayInputStream(bytes.toByteArray()));}
            module=compiled;var candidates=module.getClasses().stream().filter(ClassEntity::isAnonymous).toList();
            assertEquals(1,candidates.size());template=candidates.getFirst();
            assertSame(template,module.findClass(template.getName()));assertRecipe(template);
            assertNull("Compiler/PHB recipe is not already a resolved Java class",template.getNativeClass());
        }
        ClassDumper codec(){var codec=new ClassDumper(context,module,env,debug);codec.setDeferLinking(true);return codec;}
        byte[] save(ClassEntity entity)throws IOException {var bytes=new ByteArrayOutputStream();codec().save(entity,bytes);return bytes.toByteArray();}
        ClassEntity cold(byte[] bytes)throws IOException {return codec().load(new ByteArrayInputStream(bytes));}
        void quiet()throws Throwable {env.getDefaultBuffer().flush();assertEquals(0,env.output.size());assertEquals(0,env.lookups);}
        public void close()throws Exception {
            env.armed=false;
            try{env.doFinal();}catch(Throwable failure){throw new AssertionError(failure);}
            finally{env.getScope().getClassLoader().close();}
        }
    }
    private static void assertRecipe(ClassEntity entity) {
        assertTrue(entity.isAnonymous());assertFalse(entity.isStatic());assertEquals(ClassEntity.Type.CLASS,entity.getType());
        assertEquals(-1,entity.getDeclarationId());assertFalse(entity.isDeferredDeclaration());assertTrue(entity.isDeclarationTemplate());
        assertFalse(entity.getDeclarationRelations().traitGroups().isEmpty());assertNull(entity.getClassComposition());
        assertEquals("NestedPlainUse",entity.getDeclarationRelations().traitGroups().getFirst().traits().getFirst().name());
    }
    @Test public void realNestedAnonymousRecipeRoundTripsWithoutExecutingItsFactory()throws Throwable {
        for(boolean debug:new boolean[]{false,true})for(boolean phb:new boolean[]{false,true})try(var f=new Fixture(debug,phb)) {
            byte[] bytes=f.save(f.template);f.env.armed=true;ClassEntity copy=f.cold(bytes);assertRecipe(copy);
            assertNotSame(f.template,copy);assertSame(f.module,copy.getModule());assertNull(copy.getNativeClass());
            assertEquals(f.template.getName(),copy.getName());assertEquals(f.template.getCompiledInternalName(),copy.getCompiledInternalName());
            assertArrayEquals(f.template.getData(),copy.getData());f.quiet();
        }
    }
    @Test public void changedIdlessKindFlagsAndParentRejectBeforeProviderLookup()throws Throwable {
        for(boolean debug:new boolean[]{false,true})for(boolean phb:new boolean[]{false,true})try(var f=new Fixture(debug,phb)) {
            byte[] original=f.save(f.template);f.env.armed=true;
            for(int mutation=0;mutation<4;mutation++) {
                ClassEntity changed=f.cold(original);assertRecipe(changed);
                switch(mutation) {
                    case 0 -> changed.setAnonymous(false);
                    case 1 -> changed.setStatic(true);
                    case 2 -> changed.setType(ClassEntity.Type.TRAIT);
                    case 3 -> changed.setDeferredParentName("UnexpectedParent");
                    default -> throw new AssertionError();
                }
                // The transport writer accepts these public metadata changes. The cold reader must reject them.
                byte[] invalid=f.save(changed);
                DumpException failure=assertThrows(DumpException.class,()->f.cold(invalid));
                assertTrue(failure.getMessage(),failure.getMessage().contains(mutation==3?"parent recipe":"require a deferred"));
                assertSame(f.template,f.module.findClass(f.template.getName()));f.quiet();
            }
        }
    }
    @Test public void sameNameAndSamePhysicalBytesCannotSubstituteForActualModuleTemplate()throws Throwable {
        for(boolean debug:new boolean[]{false,true})for(boolean phb:new boolean[]{false,true})try(var f=new Fixture(debug,phb)) {
            byte[] bytes=f.save(f.template);f.env.armed=true;ClassEntity foreign=f.cold(bytes);assertRecipe(foreign);
            assertSame(f.module,foreign.getModule());assertEquals(f.template.getName(),foreign.getName());
            assertArrayEquals(f.template.getData(),foreign.getData());assertNotSame(f.template,foreign);
            assertSame(f.template,f.module.findClass(foreign.getName()));
            IllegalArgumentException failure=assertThrows(IllegalArgumentException.class,()->PhpClassDeclaration.bind(foreign,null,f.env));
            assertTrue(failure.getMessage(),failure.getMessage().contains("module catalog"));
            assertNull(foreign.getClassComposition());assertNull(foreign.getNativeClass());assertNull(f.template.getClassComposition());f.quiet();
        }
    }
}
