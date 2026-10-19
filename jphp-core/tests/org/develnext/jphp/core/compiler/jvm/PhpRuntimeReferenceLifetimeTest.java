package org.develnext.jphp.core.compiler.jvm;

import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.util.CheckClassAdapter;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.*;
import php.runtime.ext.*;
import php.runtime.loader.dump.ModuleDumper;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.Assert.*;

/** Ordinary PHP roots, including aliases retained by captures and released before a static clone. */
public class PhpRuntimeReferenceLifetimeTest {
    private void check(String family) throws Throwable {
        List<String> differences=new ArrayList<>();int comparisons=0;
        try (var resource=getClass().getClassLoader().getResourceAsStream("resources/type_hinting/php85_runtime_reference_owners.tsv")) {
            assertNotNull(resource);
            for (String line:new String(resource.readAllBytes(),StandardCharsets.UTF_8).split("\\R")) {
                if (line.isEmpty()) continue;
                String[] row=line.split("\t",-1);assertEquals(5,row.length);
                if (!row[0].equals(family)) continue;
                String source=new String(Base64.getDecoder().decode(row[3]),StandardCharsets.UTF_8);
                String expected=new String(Base64.getDecoder().decode(row[4]),StandardCharsets.UTF_8);
                for (boolean debug:new boolean[]{false,true}) for (boolean dumped:new boolean[]{false,true}) {
                    comparisons++;String label=row[1]+"/"+row[2]+"/debug="+debug+"/dump="+dumped;
                    var scope=new CompileScope();scope.registerExtension(new CoreExtension());scope.registerExtension(new SPLExtension());
                    scope.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);scope.setDebugMode(debug);
                    var output=new ByteArrayOutputStream();var env=new Environment(scope,output);
                    try {
                        var context=new Context(source.startsWith("<?php")?source.substring(5):source);
                        context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
                        var module=new JvmCompiler(env,context).compile(false);
                        if (dumped) {
                            var bytes=new ByteArrayOutputStream();var dumper=new ModuleDumper(context,env,true);
                            dumper.save(module,bytes);module=dumper.load(new ByteArrayInputStream(bytes.toByteArray()));
                        }
                        scope.loadModule(module);scope.addUserModule(module);env.registerModule(module);
                        List<byte[]> classes=new ArrayList<>();classes.add(module.getData());
                        for (var function:module.getFunctions()) classes.add(function.getData());
                        for (var closure:module.getClosures()) classes.add(closure.getData());
                        for (byte[] bytes:classes) {
                            var diagnostics=new StringWriter();
                            CheckClassAdapter.verify(new ClassReader(bytes),scope.getClassLoader(),false,new PrintWriter(diagnostics));
                            assertEquals(label+" ASM", "", diagnostics.toString());
                        }
                        module.include(env);env.getDefaultBuffer().flush();String actual=output.toString(StandardCharsets.UTF_8);
                        if (!expected.equals(actual)) differences.add(label+" expected="+expected+" actual="+actual);
                    } catch (Throwable failure) {differences.add(label+" failure="+failure);}
                    finally {env.doFinal();}
                }
            }
        }
        assertEquals("native rows must remain complete",48,comparisons);
        assertTrue("mismatches="+differences.size()+"\n"+String.join("\n",differences.subList(0,Math.min(20,differences.size()))),differences.isEmpty());
    }
    @Test public void nativeSingleAndRepeatedCycleOwnersSourceAndDump() throws Throwable {check("cycle");}
    @Test public void nativeStaticCellReleaseRebindAndCaptureOwnersSourceAndDump() throws Throwable {check("static");}
}
