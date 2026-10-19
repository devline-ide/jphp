package org.develnext.jphp.core.compiler.jvm;

import org.develnext.jphp.zend.ext.standard.StandardExtension;
import org.junit.Test;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.Context;
import php.runtime.env.Environment;
import php.runtime.loader.dump.ModuleDumper;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.Assert.*;

/** Native controls for the actual captured storage, surviving VALUE copies, and released SEND/local roots. */
public class PhpArrayWarningStorageOwnershipTest extends JvmCompilerCase {
    @Test public void warningContinuationUsesActualBackingOwnersSourceDumpAndDebug() throws Throwable {
        List<String[]> rows=new ArrayList<>();
        try (var input=getClass().getClassLoader().getResourceAsStream("resources/type_hinting/php85_array_warning_storage_owners.tsv")) {
            assertNotNull(input);
            for (String line:new String(input.readAllBytes(),StandardCharsets.UTF_8).split("\\R"))
                if (!line.isEmpty() && !line.startsWith("#")) rows.add(line.split("\\t",-1));
        }
        assertEquals(12,rows.size());var differences=new ArrayList<String>();int comparisons=0;
        for (boolean debug:new boolean[]{false,true}) for (boolean dump:new boolean[]{false,true}) for (String[] row:rows) {
            assertEquals(3,row.length);String mode=row[0]+" debug="+debug+" dump="+dump;
            var scope=newScope();scope.setDebugMode(debug);scope.registerExtension(new StandardExtension());
            var output=new ByteArrayOutputStream();var env=new Environment(scope,output);
            try {
                var decoder=Base64.getDecoder();var source=new String(decoder.decode(row[1]),StandardCharsets.UTF_8);
                var context=new Context(source.substring(5));context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
                var module=new JvmCompiler(env,context).compile(false);
                if (dump) {var dumper=new ModuleDumper(context,env,true);var bytes=new ByteArrayOutputStream();dumper.save(module,bytes);module=dumper.load(new ByteArrayInputStream(bytes.toByteArray()));}
                scope.loadModule(module);scope.addUserModule(module);env.registerModule(module);module.include(env);env.getDefaultBuffer().flush();
                var expected=new String(decoder.decode(row[2]),StandardCharsets.UTF_8);var actual=output.toString(StandardCharsets.UTF_8);
                if (!expected.equals(actual)) differences.add(mode+" expected="+expected+" actual="+actual);
            } catch (Throwable failure) {differences.add(mode+" threw="+failure);}
            finally {env.doFinal();}
            comparisons++;
        }
        assertEquals(48,comparisons);assertEquals(String.join("\n",differences),0,differences.size());
    }
}
