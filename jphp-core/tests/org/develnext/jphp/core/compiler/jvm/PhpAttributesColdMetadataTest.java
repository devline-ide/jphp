package org.develnext.jphp.core.compiler.jvm;
import org.junit.Test;
import static org.junit.Assert.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.util.Arrays;
import php.runtime.env.*;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.reflection.*;
import php.runtime.loader.dump.*;
import php.runtime.loader.dump.io.DumpException;
public class PhpAttributesColdMetadataTest extends JvmCompilerCase {
    @Test public void parameterHelpersCannotBeSwappedAcrossEquallyNamedMethodParameters() throws Throwable {
        var env=new LookupTrap(newScope());
        try {
            var context=new Context("#[Attribute] class A {function __construct($x){}} class C {function first(#[A(1)] $x){} function other(#[A(2)] $x){}}");
            context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);var module=new JvmCompiler(env,context).compile(false);
            var methods=module.findClass("C");
            String first=methods.findMethod("first").getParameters()[0].getPhpAttributes().getFirst().arguments().getFirst().initializer().method();
            String other=methods.findMethod("other").getParameters()[0].getPhpAttributes().getFirst().arguments().getFirst().initializer().method();
            assertNotEquals(first,other);assertEquals(first.length(),other.length());
            var dumper=new ModuleDumper(context,env,true);var output=new ByteArrayOutputStream();dumper.save(module,output);
            byte[] bytes=output.toByteArray(),needle=first.getBytes(java.nio.charset.StandardCharsets.UTF_8),replacement=other.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            int at=footer(bytes),found=-1;
            for(int i=at;i+needle.length<=bytes.length;i++) {
                boolean equal=true;for(int n=0;n<needle.length;n++)if(bytes[i+n]!=needle[n])equal=false;
                if(equal){assertEquals(-1,found);found=i;}
            }
            assertTrue(found>=at);System.arraycopy(replacement,0,bytes,found,replacement.length);
            reject(dumper,env,bytes);
        } finally {env.doFinal();}
    }
    private static final class LookupTrap extends Environment {
        int lookups;boolean reject;
        LookupTrap(CompileScope scope){super(scope,new ByteArrayOutputStream());}
        @Override public ClassEntity fetchClass(String name,boolean autoload) {if(reject){lookups++;throw new AssertionError("Early provider "+name);}return super.fetchClass(name,autoload);}
        @Override public ClassEntity fetchClass(String name,boolean autoload,PhpLanguageProfile profile) {if(reject){lookups++;throw new AssertionError("Early provider "+name);}return super.fetchClass(name,autoload,profile);}
    }
    private static int footer(byte[] bytes) {
        int result=-1;
        for(int at=4;at+8<=bytes.length;at++) if(ByteBuffer.wrap(bytes,at,4).getInt()==0x41544d44) {
            assertEquals("Unique actual attributes frame",-1,result);result=at;
            assertEquals("Complete last physical frame",bytes.length-at,ByteBuffer.wrap(bytes,at-4,4).getInt());
        }
        assertTrue("Actual executable attribute frame",result>0);return result;
    }
    private void reject(ModuleDumper dumper,LookupTrap env,byte[] bytes) throws Throwable {
        env.lookups=0;env.reject=true;
        try {dumper.load(new ByteArrayInputStream(bytes));fail("Accepted malformed compiled attribute metadata");}
        catch(IOException expected) {}
        finally {env.reject=false;}
        assertEquals("No provider/autoload before complete cold validation",0,env.lookups);
        assertNull("No attribute constructor",env.getGlobals().getByScalar("attributeConstructed"));
        assertNull("No argument constructor",env.getGlobals().getByScalar("payloadConstructed"));
        assertNull("No module body",env.getGlobals().getByScalar("moduleBody"));
    }
    @Test public void malformedLastFrameRejectsBeforeExternalParentProviderConstructorsOrBody() throws Throwable {
        for(boolean debug:new boolean[]{false,true}) {
            var env=new LookupTrap(newScope());env.getScope().setDebugMode(debug);
            try {
                var parentContext=new Context("class ColdAttributeParent {}");parentContext.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
                var parent=new JvmCompiler(env,parentContext).compile(false);env.getScope().loadModule(parent);env.getScope().addUserModule(parent);env.registerModule(parent);
                var context=new Context("#[Attribute] class A {function __construct($p){$GLOBALS['attributeConstructed']=1;}} class Payload {function __construct(){$GLOBALS['payloadConstructed']=1;}} class ColdAttributeChild extends ColdAttributeParent {#[A(new Payload)] public function m(){}} $GLOBALS['moduleBody']=1;");
                context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);var module=new JvmCompiler(env,context).compile(false);
                var dumper=new ModuleDumper(context,env,debug);var output=new ByteArrayOutputStream();dumper.save(module,output);
                byte[] valid=output.toByteArray();int at=footer(valid);
                byte[] unknownVersion=valid.clone();ByteBuffer.wrap(unknownVersion,at+4,4).putInt(999);reject(dumper,env,unknownVersion);
                byte[] unknownStamp=valid.clone();ByteBuffer.wrap(unknownStamp,at,4).putInt(0);reject(dumper,env,unknownStamp);
                reject(dumper,env,Arrays.copyOf(valid,valid.length-1));
                reject(dumper,env,Arrays.copyOf(valid,valid.length+1));
                byte[] oversized=valid.clone();ByteBuffer.wrap(oversized,at-4,4).putInt(PhpAttributeDumper.MAX_BYTES+1);reject(dumper,env,oversized);
                // Reuse the actual dumper after each failed decode: no stale relation callbacks survive.
                var decoded=dumper.load(new ByteArrayInputStream(valid));
                assertEquals("A",decoded.findClass("ColdAttributeChild").findMethod("m").getPhpAttributes().getFirst().name());
                assertNull(env.getGlobals().getByScalar("moduleBody"));
            } finally {env.doFinal();}
        }
    }
}
