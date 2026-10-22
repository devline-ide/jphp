package org.develnext.jphp.core.compiler.jvm;

import org.junit.BeforeClass;
import org.junit.Test;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.CompileScope;
import php.runtime.env.Context;
import php.runtime.env.Environment;
import php.runtime.loader.dump.ModuleDumper;
import php.runtime.memory.ArrayMemory;
import php.runtime.memory.LongMemory;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import static org.junit.Assert.*;

/** Native controls for the actual symbol table versus ordinary ARRAY VALUE snapshots. */
public class PhpGlobalsStructuralSnapshotTest {
    private static byte[] resource(String name)throws Exception {
        try(var input=PhpGlobalsStructuralSnapshotTest.class.getClassLoader().getResourceAsStream("resources/native-oracle/globals-structural-snapshot/"+name)) {
            assertNotNull("Missing native GLOBALS resource "+name,input);return input.readAllBytes();
        }
    }
    @BeforeClass public static void origins()throws Exception {
        String runtime=System.getProperty("qa.runtime"),core=System.getProperty("qa.core");
        if(runtime!=null)assertEquals(Path.of(runtime).toRealPath(),Path.of(Environment.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath());
        if(core!=null)assertEquals(Path.of(core).toRealPath(),Path.of(JvmCompiler.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath());
    }
    private static CompileScope scope(boolean debug,PhpLanguageProfile profile) {
        var scope=new CompileScope();scope.setDebugMode(debug);scope.setPhpLanguageProfile(profile);
        scope.registerExtension(new php.runtime.ext.CoreExtension());scope.registerExtension(new php.runtime.ext.SPLExtension());
        scope.registerExtension(new org.develnext.jphp.zend.ext.standard.StandardExtension());
        scope.registerExtension(new org.develnext.jphp.zend.ext.json.JsonExtension());return scope;
    }
    private static void nativeCase(String name)throws Throwable {
        for(boolean debug:new boolean[]{false,true})for(boolean phb:new boolean[]{false,true}) {
            var output=new ByteArrayOutputStream();var env=new Environment(scope(debug,PhpLanguageProfile.PHP_8_5),output);
            var stack=env.getCallStack();var buffers=env.getOutputBuffers();env.getDefaultBuffer().setImplicitFlush(true);
            try {
                String source=new String(resource(name+".php"),StandardCharsets.UTF_8);
                var context=new Context(source,new File(name+".php"));context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
                var module=new JvmCompiler(env,context).compile(false);
                if(phb){var bytes=new ByteArrayOutputStream();var codec=new ModuleDumper(context,env,debug);codec.save(module,bytes);module=codec.load(new ByteArrayInputStream(bytes.toByteArray()));assertTrue(module.isCompiledArtifact());}
                env.scope.loadModule(module);env.scope.addUserModule(module);env.registerModule(module);
                module.include(env);env.flushAll();
                byte[] actual=output.toByteArray();
                System.out.println("CELL "+name+" debug="+debug+" phb="+phb+" ACTUAL "+new String(actual,StandardCharsets.UTF_8));
                assertArrayEquals(name+"/"+debug+"/"+phb,resource(name+".stdout"),actual);
                assertSame(stack,env.getCallStack());assertSame(buffers,env.getOutputBuffers());
            } finally {env.doFinal();}
        }
    }
    @Test(timeout=60000) public void handler_after_readonly()throws Throwable {nativeCase("handler_after_readonly");}
    @Test(timeout=60000) public void repeated_callback_scopes()throws Throwable {nativeCase("repeated_callback_scopes");}
    @Test(timeout=60000) public void snapshot_value_isolation()throws Throwable {nativeCase("snapshot_value_isolation");}
    @Test(timeout=60000) public void snapshot_key_isolation()throws Throwable {nativeCase("snapshot_key_isolation");}
    @Test(timeout=60000) public void nested_array_isolation()throws Throwable {nativeCase("nested_array_isolation");}
    @Test(timeout=60000) public void explicit_reference_preserved()throws Throwable {nativeCase("explicit_reference_preserved");}
    @Test(timeout=60000) public void existing_callback_key()throws Throwable {nativeCase("existing_callback_key");}
    @Test(timeout=60000) public void user_value_argument()throws Throwable {nativeCase("user_value_argument");}
    @Test(timeout=60000) public void ordinary_recursive_array()throws Throwable {nativeCase("ordinary_recursive_array");}
    @Test(timeout=60000) public void ordinary_globals_key()throws Throwable {nativeCase("ordinary_globals_key");}
    @Test(timeout=60000) public void callback_snapshot_isolation()throws Throwable {nativeCase("callback_snapshot_isolation");}
    @Test(timeout=60000) public void globals_key_replaced()throws Throwable {nativeCase("globals_key_replaced");}
    @Test(timeout=60000) public void globals_key_unset()throws Throwable {nativeCase("globals_key_unset");}
    @Test(timeout=60000) public void globals_numeric_key_preserved()throws Throwable {nativeCase("globals_numeric_key_preserved");}
    @Test(timeout=60000) public void actual_table_and_carrier_identity()throws Throwable {
        var env=new Environment(scope(false,PhpLanguageProfile.PHP_8_5),new ByteArrayOutputStream());
        try {
            var table=env.getRuntimeGlobalsTable();var origin=env.getSyntheticGlobalsOrigin();var carrier=origin.carrier();
            Object backing=table.getStorageIdentity();origin.validateCarrier(carrier);
            ArrayMemory first=(ArrayMemory)table.toImmutable();ArrayMemory second=table.toImmutableForNativeRead();
            assertNotSame(table,first);assertNotSame(backing,first.getStorageIdentity());
            assertNull(first.getByScalar("GLOBALS"));assertNull(second.getByScalar("GLOBALS"));
            table.refOfIndex("freshIdentity").assign(17);
            assertSame(backing,table.getStorageIdentity());assertSame(carrier,table.getByScalar("GLOBALS"));assertSame(table,carrier.getRawValue());
            assertNull(first.getByScalar("freshIdentity"));origin.validateCarrier(carrier);
        } finally {env.doFinal();}
    }
    @Test(timeout=60000) public void legacy_snapshot_retains_original_behavior()throws Throwable {
        var env=new Environment(scope(false,PhpLanguageProfile.JPHP_LEGACY),new ByteArrayOutputStream());
        try {
            var table=env.getRuntimeGlobalsTable();Object backing=table.getStorageIdentity();
            ArrayMemory copy=(ArrayMemory)table.toImmutable();
            assertNotSame(table,copy);assertSame(backing,copy.getStorageIdentity());assertNotNull(copy.getByScalar("GLOBALS"));
            env.getSyntheticGlobalsOrigin().validateCarrier(env.getSyntheticGlobalsOrigin().carrier());
        } finally {env.doFinal();}
    }

    @Test(timeout=60000) public void live_origin_and_ordinary_ownership_guards()throws Throwable {
        var env=new Environment(scope(false,PhpLanguageProfile.PHP_8_5),new ByteArrayOutputStream());
        try {
            var table=env.getRuntimeGlobalsTable();Object backing=table.getStorageIdentity();
            assertNull(table.getOwnershipStorage());assertNull(table.getOwnershipTable());assertNull(table.getRuntimeOrigin());
            // Use the actual PHP global-store producer; Java assign(long) destroys an old ARRAY payload.
            var context=new Context("<?php $GLOBALS['GLOBALS']=123;",new File("live-origin-key.php"));
            context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
            var module=new JvmCompiler(env,context).compile(false);
            env.scope.loadModule(module);env.scope.addUserModule(module);env.registerModule(module);module.include(env);
            System.gc();System.runFinalization();
            ArrayMemory copy=(ArrayMemory)table.toImmutable();
            assertEquals(123,copy.getByScalar("GLOBALS").toLong());assertNotSame(backing,copy.getStorageIdentity());
            table.refOfIndex("afterSnapshot").assign(17);
            assertSame(backing,table.getStorageIdentity());assertNull(copy.getByScalar("afterSnapshot"));
            assertNull(table.getOwnershipStorage());assertNull(table.getOwnershipTable());assertNull(table.getRuntimeOrigin());
            java.lang.ref.Reference.reachabilityFence(env);
        } finally {env.doFinal();}
    }
    @Test(timeout=60000) public void legacy_cow_and_clear_preserve_table_identity()throws Throwable {
        var hostScope=scope(false,PhpLanguageProfile.JPHP_LEGACY);
        var env=new Environment(hostScope,new ByteArrayOutputStream());
        try {
            var table=env.getRuntimeGlobalsTable();table.clear();
            ArrayMemory before=(ArrayMemory)table.toImmutable();Object oldBacking=table.getStorageIdentity();
            table.refOfIndex("legacyKey").assign(17);
            assertNotSame(oldBacking,table.getStorageIdentity());assertNull(before.getByScalar("legacyKey"));
            hostScope.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
            Object current=table.getStorageIdentity();ArrayMemory modern=(ArrayMemory)table.toImmutable();
            assertNotSame(current,modern.getStorageIdentity());assertEquals(17,modern.getByScalar("legacyKey").toLong());
            table.refOfIndex("newKey").assign(18);assertSame(current,table.getStorageIdentity());assertNull(modern.getByScalar("newKey"));
            table.clear();Object cleared=table.getStorageIdentity();ArrayMemory empty=(ArrayMemory)table.toImmutable();
            table.refOfIndex("afterClear").assign(19);assertSame(cleared,table.getStorageIdentity());assertEquals(0,empty.size());
            java.lang.ref.Reference.reachabilityFence(env);
        } finally {env.doFinal();}
    }
    @Test(timeout=60000) public void legacy_shared_value_does_not_gain_table_identity()throws Throwable {
        var hostScope=scope(false,PhpLanguageProfile.JPHP_LEGACY);
        var env=new Environment(hostScope,new ByteArrayOutputStream());
        try {
            var table=env.getRuntimeGlobalsTable();table.clear();
            ArrayMemory value=(ArrayMemory)table.toImmutable();Object shared=value.getStorageIdentity();
            hostScope.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
            ArrayMemory copiedValue=(ArrayMemory)value.toImmutable();assertSame(shared,copiedValue.getStorageIdentity());
            ArrayMemory copiedTable=(ArrayMemory)table.toImmutable();assertNotSame(shared,copiedTable.getStorageIdentity());
            java.lang.ref.Reference.reachabilityFence(env);
        } finally {env.doFinal();}
    }
}
