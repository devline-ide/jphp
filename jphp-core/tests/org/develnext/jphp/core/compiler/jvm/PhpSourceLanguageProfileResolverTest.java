package org.develnext.jphp.core.compiler.jvm;

import org.junit.Test;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.*;
import php.runtime.loader.dump.ModuleDumper;
import php.runtime.reflection.ModuleEntity;
import static org.junit.Assert.*;

public class PhpSourceLanguageProfileResolverTest extends JvmCompilerCase {
    private Environment environment(ByteArrayOutputStream output) {
        var scope=newScope();scope.setPhpLanguageProfile(PhpLanguageProfile.JPHP_LEGACY);
        scope.registerExtension(new org.develnext.jphp.zend.ext.standard.StandardExtension());
        return new Environment(scope,output);
    }
    private static final class Fixture implements AutoCloseable {
        final Path root=Files.createTempDirectory("jphp-source-profile-owned-").toRealPath();
        final List<Path> paths=new ArrayList<>();
        Fixture() throws IOException {}
        Path php(String name,String code) throws IOException {
            Path file=root.resolve(name);paths.add(file);Files.writeString(file,"<?php "+code,StandardCharsets.UTF_8);return file;
        }
        Path dump(String name,Environment env,ModuleEntity module) throws IOException {
            Path file=root.resolve(name);paths.add(file);
            try(var output=Files.newOutputStream(file)){new ModuleDumper(module.getContext(),env,false).save(module,output);}return file;
        }
        @Override public void close() throws IOException {
            IOException failure=null;
            for(Path p:paths)try{Files.deleteIfExists(p);}catch(IOException e){if(failure==null)failure=e;else failure.addSuppressed(e);}
            try{Files.deleteIfExists(root);}catch(IOException e){if(failure==null)failure=e;else failure.addSuppressed(e);}
            if(failure!=null)throw failure;
        }
    }
    private static String normalized(Path path){return ModuleCacheKey.normalize(path.toString());}
    @Test public void nestedAppSourceUsesTypedFieldsAndRequiredLibraryKeepsLegacyFromModernCaller() throws Throwable {
        for(boolean dump:new boolean[]{false,true})try(var fixture=new Fixture()) {
            Path dependency=fixture.php("ApplicationDependency.php","class TypedDependency {private float $x=1.5; public function value():float{return $this->x;}}");
            Path library=fixture.php("LegacyLibrary.php","echo '|legacy:',(0=='abc'?'true':'false');");
            Path entry=fixture.php("ApplicationEntry.php","require __DIR__.'/ApplicationDependency.php'; echo (new TypedDependency)->value(),'|modern:',(0=='abc'?'true':'false'); require __DIR__.'/LegacyLibrary.php';");
            var output=new ByteArrayOutputStream();var env=environment(output);List<String> seen=new ArrayList<>();
            Set<String> app=Set.of(normalized(entry),normalized(dependency));
            Function<String,PhpLanguageProfile> policy=path->{seen.add(path);return app.contains(path)?PhpLanguageProfile.PHP_8_5:PhpLanguageProfile.JPHP_LEGACY;};
            env.getModuleManager().setSourceLanguageProfileResolver(policy);
            try {
                var module=env.getModuleManager().fetchModule(entry.toString(),false,PhpLanguageProfile.JPHP_LEGACY);
                assertSame(PhpLanguageProfile.PHP_8_5,module.getPhpLanguageProfile());
                if(dump){Path file=fixture.dump("ApplicationEntry.phb",env,module);module=env.getModuleManager().fetchModule(file.toString(),true,PhpLanguageProfile.JPHP_LEGACY);}
                module.include(env);env.flushAll();assertEquals("1.5|modern:false|legacy:true",output.toString(StandardCharsets.UTF_8));
                assertTrue(seen.contains(normalized(entry)));assertTrue(seen.contains(normalized(dependency)));assertTrue(seen.contains(normalized(library)));
                assertSame(PhpLanguageProfile.PHP_8_5,env.getModuleManager().findModule(dependency.toString()).getPhpLanguageProfile());
                assertSame(PhpLanguageProfile.JPHP_LEGACY,env.getModuleManager().findModule(library.toString()).getPhpLanguageProfile());
                assertSame(PhpLanguageProfile.JPHP_LEGACY,env.scope.getPhpLanguageProfile());
            }finally{env.doFinal();}
        }
    }
    @Test public void selectedProfilePrecedesCacheKeyAndNullRetainsExplicitProfileWithTemporaryFetch() throws Throwable {
        try(var fixture=new Fixture()) {
            Path app=fixture.php("App.php","return 0=='abc';"),outside=fixture.php("Outside.php","return 0=='abc';");
            var env=environment(new ByteArrayOutputStream());AtomicInteger calls=new AtomicInteger();List<String> seen=new ArrayList<>();
            env.getModuleManager().setSourceLanguageProfileResolver(path->{calls.incrementAndGet();seen.add(path);return path.equals(normalized(app))?PhpLanguageProfile.PHP_8_5:null;});
            try {
                var one=env.getModuleManager().fetchModule(app.toString(),false,PhpLanguageProfile.JPHP_LEGACY);
                var two=env.getModuleManager().fetchModule(app.toString(),false,PhpLanguageProfile.PHP_8_5);
                assertSame(one,two);assertSame(PhpLanguageProfile.PHP_8_5,one.getPhpLanguageProfile());assertFalse(one.include(env).toBoolean());
                var legacy=env.getModuleManager().fetchModule(outside.toString(),false,PhpLanguageProfile.JPHP_LEGACY);
                var modern=env.getModuleManager().fetchModule(outside.toString(),false,PhpLanguageProfile.PHP_8_5);
                assertNotSame(legacy,modern);assertTrue(legacy.include(env).toBoolean());assertFalse(modern.include(env).toBoolean());
                var temporary=env.getModuleManager().fetchTemporaryModule(app.toString(),false,PhpLanguageProfile.JPHP_LEGACY);
                assertSame("Environment source scope cache may reuse the physical module after temporary manager fetch",one,temporary);assertSame(PhpLanguageProfile.PHP_8_5,temporary.getPhpLanguageProfile());
                var retained=env.getModuleManager().fetchTemporaryModule(outside.toString(),false,PhpLanguageProfile.JPHP_LEGACY);
                assertSame(PhpLanguageProfile.JPHP_LEGACY,retained.getPhpLanguageProfile());
                assertEquals(6,calls.get());assertEquals(List.of(normalized(app),normalized(app),normalized(outside),normalized(outside),normalized(app),normalized(outside)),seen);
                assertSame(PhpLanguageProfile.JPHP_LEGACY,env.scope.getPhpLanguageProfile());
            }finally{env.doFinal();}
        }
    }
    @Test public void applyCopiesPolicyAndFrozenStateWithoutRequiringAnotherScope() throws Throwable {
        try(var fixture=new Fixture()) {
            Path source=fixture.php("Source.php","return 0=='abc';");
            var parent=environment(new ByteArrayOutputStream());var child=environment(new ByteArrayOutputStream());var frozen=environment(new ByteArrayOutputStream());
            AtomicInteger calls=new AtomicInteger();Function<String,PhpLanguageProfile> policy=path->{calls.incrementAndGet();return PhpLanguageProfile.PHP_8_5;};
            try {
                parent.getModuleManager().setSourceLanguageProfileResolver(policy);child.getModuleManager().apply(parent.getModuleManager());
                var module=child.getModuleManager().fetchTemporaryModule(source.toString(),false,PhpLanguageProfile.JPHP_LEGACY);
                assertSame(PhpLanguageProfile.PHP_8_5,module.getPhpLanguageProfile());
                child.getModuleManager().setSourceLanguageProfileResolver(policy);
                assertThrows(IllegalStateException.class,()->child.getModuleManager().setSourceLanguageProfileResolver(null));
                var original=parent.getModuleManager().fetchModule(source.toString(),false);
                frozen.getModuleManager().apply(parent.getModuleManager());frozen.getModuleManager().setSourceLanguageProfileResolver(policy);
                assertThrows(IllegalStateException.class,()->frozen.getModuleManager().setSourceLanguageProfileResolver(path->null));
                assertSame(original,frozen.getModuleManager().fetchModule(source.toString(),false));assertEquals(3,calls.get());
            }finally{parent.doFinal();child.doFinal();frozen.doFinal();}
        }
    }
    @Test public void sourceCallbackFailureAndMissingFetchFreezeBeforeAnyCompilationOrEffects() throws Throwable {
        try(var fixture=new Fixture()) {
            Path source=fixture.php("Never.php","echo 'body';");var output=new ByteArrayOutputStream();var env=environment(output);
            RuntimeException exact=new RuntimeException("profile-policy-failure");AtomicInteger calls=new AtomicInteger();
            Function<String,PhpLanguageProfile> policy=path->{calls.incrementAndGet();throw exact;};
            env.getModuleManager().setSourceLanguageProfileResolver(policy);
            try {
                assertSame(exact,assertThrows(RuntimeException.class,()->env.getModuleManager().fetchModule(source.toString(),false)));
                assertEquals(1,calls.get());assertFalse(env.getModuleManager().hasModule(source.toString()));assertEquals(0,output.size());
                env.getModuleManager().setSourceLanguageProfileResolver(policy);
                assertThrows(IllegalStateException.class,()->env.getModuleManager().setSourceLanguageProfileResolver(path->PhpLanguageProfile.JPHP_LEGACY));
            }finally{env.doFinal();}
            var missing=environment(new ByteArrayOutputStream());
            try {assertNull(missing.getModuleManager().fetchTemporaryModule(fixture.root.resolve("absent.php").toString(),false));assertThrows(IllegalStateException.class,()->missing.getModuleManager().setSourceLanguageProfileResolver(policy));}
            finally{missing.doFinal();}
        }
    }
    @Test public void compiledModulesBypassBothResolversAndRetainActualPhbProfileOnCachedAndTemporaryFetch() throws Throwable {
        for(var profile:PhpLanguageProfile.values())try(var fixture=new Fixture()) {
            var writer=environment(new ByteArrayOutputStream());var reader=environment(new ByteArrayOutputStream());
            try {
                var context=new Context("return 0=='abc';");context.setPhpLanguageProfile(profile);
                var module=new JvmCompiler(writer,context).compile(false);Path dump=fixture.dump("Stored.phb",writer,module);
                AtomicInteger calls=new AtomicInteger();Function<String,PhpLanguageProfile> policy=path->{calls.incrementAndGet();throw new AssertionError("PHB source profile callback");};
                reader.getModuleManager().setSourceLanguageProfileResolver(policy);
                reader.getModuleManager().setImplicitClassImportsResolver(path->{throw new AssertionError("PHB source imports callback");});
                PhpLanguageProfile contrary=profile==PhpLanguageProfile.PHP_8_5?PhpLanguageProfile.JPHP_LEGACY:PhpLanguageProfile.PHP_8_5;
                var cold=reader.getModuleManager().fetchModule(dump.toString(),true,contrary);
                assertSame(profile,cold.getPhpLanguageProfile());assertSame(cold,reader.getModuleManager().fetchModule(dump.toString(),true,contrary));
                var temp=reader.getModuleManager().fetchTemporaryModule(dump.toString(),true,contrary);
                assertSame(profile,temp.getPhpLanguageProfile());assertSame("Environment compiled scope cache may reuse the physical module after temporary manager fetch",cold,temp);
                assertEquals(profile==PhpLanguageProfile.JPHP_LEGACY,cold.include(reader).toBoolean());assertEquals(0,calls.get());
                reader.getModuleManager().setSourceLanguageProfileResolver(policy);
                assertThrows(IllegalStateException.class,()->reader.getModuleManager().setSourceLanguageProfileResolver(null));
            }finally{writer.doFinal();reader.doFinal();}
        }
    }
}
