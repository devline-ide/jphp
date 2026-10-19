package org.develnext.jphp.core.compiler.jvm;

import com.google.gson.*;
import org.junit.Test;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.*;
import php.runtime.exceptions.support.ErrorException;
import php.runtime.exceptions.support.ErrorType;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import static org.junit.Assert.*;

/** Exact native fatal messages and pre-body compilation rejection; CLI framing is tested separately. */
public class PhpPropertyHookFatalTest extends JvmCompilerCase {
    private byte[] resource(String name)throws IOException {
        try(var stream=getClass().getClassLoader().getResourceAsStream(name)) {
            assertNotNull(name,stream);return stream.readAllBytes();
        }
    }
    @Test public void nativeInvalidDeclarationsRejectBeforeBodyWithExactMessages()throws Throwable {
        var rows=new Gson().fromJson(new String(resource("resources/property_hooks/fatal/controls.json"),StandardCharsets.UTF_8),JsonArray.class);
        assertEquals(14,rows.size());var failures=new ArrayList<Throwable>();int compared=0;
        for(var element:rows)for(boolean debug:new boolean[]{false,true}) {
            var row=element.getAsJsonObject();String path=row.get("resource").getAsString(),label=row.get("id").getAsString()+" debug="+debug;
            var scope=newScope();scope.setDebugMode(debug);var output=new ByteArrayOutputStream();var env=new Environment(scope,output);
            Path directory=Files.createTempDirectory("jphp-hook-fatal-"),file=directory.resolve("fixture.php");boolean compiled=false;compared++;
            try {
                byte[] code=resource(path+".php");Files.write(file,code);var context=new Context(new String(code,StandardCharsets.UTF_8),file.toFile());
                context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);new JvmCompiler(env,context).compile(false);compiled=true;
                fail("Invalid native hook declaration admitted: "+label);
            }catch(Throwable failure) {
                try {
                    assertTrue(label,failure instanceof ErrorException);
                    assertEquals(label,ErrorType.E_COMPILE_ERROR,((ErrorException)failure).getType());
                    assertEquals(label,row.get("message").getAsString(),failure.getMessage());
                    assertFalse(label+" must reject during compilation",compiled);
                    assertArrayEquals(label+" no body effects",resource(path+".stdout"),output.toByteArray());
                }catch(Throwable mismatch){failures.add(new AssertionError(label,mismatch));}
            }finally{try{env.doFinal();}finally{Files.deleteIfExists(file);Files.delete(directory);}}
        }
        assertEquals(28,compared);
        if(!failures.isEmpty()){var error=new AssertionError(failures.size()+" native fatal variants failed");failures.forEach(error::addSuppressed);throw error;}
    }
}
