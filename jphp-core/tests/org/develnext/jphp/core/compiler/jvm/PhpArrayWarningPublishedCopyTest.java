package org.develnext.jphp.core.compiler.jvm;

import org.develnext.jphp.zend.ext.standard.StandardExtension;
import org.junit.Test;
import php.runtime.Memory;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.Context;
import php.runtime.env.Environment;
import php.runtime.lang.BaseObject;
import php.runtime.loader.dump.ModuleDumper;
import php.runtime.memory.ArrayMemory;
import php.runtime.memory.ObjectMemory;
import php.runtime.memory.ReferenceMemory;
import php.runtime.reflection.ClassEntity;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.Assert.*;

/** Ordinary execution: witnessed retained copies, discarded temporaries and callback-free inspection. */
public class PhpArrayWarningPublishedCopyTest extends JvmCompilerCase {
    @Test public void nativePublishedCopiesSourceDumpAndDebug() throws Throwable {
        String fixture;
        try (var input=getClass().getClassLoader().getResourceAsStream("resources/type_hinting/php85_array_warning_published_copies.tsv")) {
            assertNotNull(input);fixture=new String(input.readAllBytes(),StandardCharsets.UTF_8);
        }
        int comparisons=0;
        for(boolean debug:new boolean[]{false,true})for(boolean dump:new boolean[]{false,true})for(String line:fixture.split("\\R")) {
            if(line.isEmpty() || line.startsWith("#"))continue;
            var row=line.split("\\t",-1);assertEquals(3,row.length);
            var scope=newScope();scope.setDebugMode(debug);scope.registerExtension(new StandardExtension());
            var output=new ByteArrayOutputStream();var env=new Environment(scope,output);
            try {
                var decoder=Base64.getDecoder();var source=new String(decoder.decode(row[1]),StandardCharsets.UTF_8);
                var context=new Context(source.substring(5));context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
                var module=new JvmCompiler(env,context).compile(false);
                if(dump){var dumper=new ModuleDumper(context,env,true);var bytes=new ByteArrayOutputStream();dumper.save(module,bytes);module=dumper.load(new ByteArrayInputStream(bytes.toByteArray()));}
                scope.loadModule(module);scope.addUserModule(module);env.registerModule(module);module.include(env);env.getDefaultBuffer().flush();
                assertEquals(row[0]+" debug="+debug+" dump="+dump,new String(decoder.decode(row[2]),StandardCharsets.UTF_8),output.toString(StandardCharsets.UTF_8));
                comparisons++;
            }finally{env.doFinal();}
        }
        assertEquals(20,comparisons);
    }

    private static final class PassiveObject extends BaseObject {
        PassiveObject(ArrayMemory properties){super((ClassEntity)null);__dynamicProperties__=properties;}
        @Override public ArrayMemory getProperties(){throw new AssertionError("Native property getter was called");}
    }
    private static final class PassiveReference extends ReferenceMemory {
        PassiveReference(Memory value){super(value);}
        @Override public Memory getValue(){throw new AssertionError("Virtual reference getter was called");}
    }

    @Test public void nestedLeasesInspectRawCyclicStateWithoutCallbacks() {
        var env=new Environment(newScope());var writer=new ArrayMemory();
        var outer=writer.pinWriteFetch();var inner=writer.pinWriteFetch();
        try {
            var properties=new ArrayMemory();var object=new PassiveObject(properties);
            properties.refOfIndex("self").assign(new ObjectMemory(object));
            properties.refOfIndex("copy").assign(writer.toImmutable());
            env.getRuntimeGlobalsTable().refOfIndex("holder").assign(new PassiveReference(new ObjectMemory(object)));
            assertNull(inner.continuation(writer,env));assertNull(outer.continuation(writer,env));
            outer.close(); // A still-active inner lease keeps its proof after out-of-order close.
            assertNull(inner.continuation(writer,env));
            properties.refOfIndex("copy").assign(Memory.NULL);
            assertSame(writer,inner.continuation(writer,env));
        }finally{inner.close();outer.close();}
    }

    @Test public void uninitializedPropertiesAndUnpublishedCopiesDoNotBecomeOwners() {
        var env=new Environment(newScope());var writer=new ArrayMemory();var object=new PassiveObject(null);
        env.getRuntimeGlobalsTable().refOfIndex("holder").assign(new ObjectMemory(object));
        try(var lease=writer.pinWriteFetch()) {
            Memory unpublished=writer.toImmutable();
            assertSame(writer,lease.continuation(writer,env));assertNull(object.peekProperties());
            assertNotSame(writer,unpublished);
        }
    }
}
