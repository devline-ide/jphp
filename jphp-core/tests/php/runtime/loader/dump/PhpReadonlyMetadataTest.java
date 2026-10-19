package php.runtime.loader.dump;

import org.junit.Test;
import org.develnext.jphp.core.compiler.jvm.JvmCompilerCase;
import php.runtime.Memory;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.*;
import php.runtime.loader.dump.io.*;
import php.runtime.reflection.*;
import php.runtime.reflection.support.PhpTypeDeclaration;
import java.io.*;
import java.nio.ByteBuffer;
import java.util.*;
import static org.junit.Assert.*;

/** Cold declaration wire guards, without defining byte classes or executing PHP. */
public class PhpReadonlyMetadataTest extends JvmCompilerCase {
    private static final Context CONTEXT=new Context("readonly codec controls");
    private static final class LookupTrap extends Environment {
        int lookups;boolean armed;
        LookupTrap(CompileScope scope){super(scope,new ByteArrayOutputStream());armed=true;}
        @Override public ClassEntity fetchClass(String name,boolean autoload){if(armed){lookups++;throw new AssertionError("Lookup "+name);}return super.fetchClass(name,autoload);}
        @Override public ClassEntity fetchClass(String name,boolean autoload,PhpLanguageProfile profile){if(armed){lookups++;throw new AssertionError("Lookup "+name);}return super.fetchClass(name,autoload,profile);}
    }
    private PropertyEntity property(boolean readonly,boolean promoted) {
        var p=new PropertyEntity(CONTEXT);p.setName("x");p.setPhpReadonly(readonly);p.setPromoted(promoted);
        p.setDeclaredType(new PhpTypeDeclaration(List.of(List.of("int"))));p.setDefaultValue(Memory.UNDEFINED);return p;
    }
    private static byte[] declaration(int version,int flag,boolean type) throws IOException {
        var b=new ByteArrayOutputStream();var d=new DumpOutputStream(b);d.writeInt(version);d.writeBoolean(false);
        if(version>=2)d.writeBoolean(true);if(version>=3)d.writeByte(flag);
        d.writeRawData(type?TypeDeclarationDumper.save(new PhpTypeDeclaration(List.of(List.of("int")))):null);return b.toByteArray();
    }
    private void rejectProperty(PropertyEntity p,byte[] bytes,String message) throws IOException {
        try{PropertyDeclarationDumper.load(p,bytes);fail("Accepted "+message);}catch(DumpException e){assertTrue(e.getMessage(),e.getMessage().contains(message));}
        assertFalse("Malformed record must not publish readonly",p.isPhpReadonly());
    }
    @Test public void historicalPropertyRecordsOneTwoDefaultReadonlyFalse() throws Throwable {
        for(int version:new int[]{1,2}) {
            var p=new PropertyEntity(CONTEXT);p.setName("x");PropertyDeclarationDumper.load(p,declaration(version,0,true));
            assertFalse(p.isPhpReadonly());assertEquals(version==2,p.isPromoted());assertEquals("int",p.getDeclaredType().getErrorSignature());
            assertTrue(p.getDefaultValue().isUndefined());
        }
    }
    @Test public void readonlyPropertyThreeRoundTripsPromotionAndType() throws Throwable {
        for(boolean promoted:new boolean[]{false,true}) {
            var bytes=PropertyDeclarationDumper.save(property(true,promoted));assertEquals(3,ByteBuffer.wrap(bytes).getInt());
            var p=new PropertyEntity(CONTEXT);p.setName("x");PropertyDeclarationDumper.load(p,bytes);
            assertTrue(p.isPhpReadonly());assertEquals(promoted,p.isPromoted());assertEquals("int",p.getDeclaredType().getErrorSignature());
        }
    }
    @Test public void malformedPropertyFlagsSizeAndUnknownVersionRejectCold() throws Throwable {
        rejectProperty(new PropertyEntity(CONTEXT),declaration(3,2,true),"flag");
        rejectProperty(new PropertyEntity(CONTEXT),declaration(3,0,true),"Noncanonical");
        var valid=declaration(3,1,true);var trailing=Arrays.copyOf(valid,valid.length+1);
        rejectProperty(new PropertyEntity(CONTEXT),trailing,"size");
        ByteBuffer.wrap(valid).putInt(4);rejectProperty(new PropertyEntity(CONTEXT),valid,"version");
    }
    @Test public void malformedReadonlyPropertyUntypedStaticDefaultRejectCold() throws Throwable {
        rejectProperty(new PropertyEntity(CONTEXT),declaration(3,1,false),"Invalid readonly");
        var statik=new PropertyEntity(CONTEXT);statik.setStatic(true);rejectProperty(statik,declaration(3,1,true),"Invalid readonly");
        var def=new PropertyEntity(CONTEXT);def.setDefault(true);rejectProperty(def,declaration(3,1,true),"Invalid readonly");
    }
    private ClassEntity fixture(boolean readonly,ClassEntity.Type kind,boolean mutable,boolean statik) {
        var c=new ClassEntity(CONTEXT);c.setId(1);c.setName("ColdReadonly");c.setInternalName("ColdReadonly$1");
        c.setType(kind);c.setData(new byte[]{1,2,3,4});c.setPhpReadonly(readonly);
        var p=property(!mutable,false);p.setStatic(statik);c.addProperty(p);return c;
    }
    private static byte[] save(ClassDumper d,ClassEntity c) throws IOException {var b=new ByteArrayOutputStream();d.save(c,b);return b.toByteArray();}
    private static int footer(byte[] bytes,int version) {
        int at=-1;for(int i=0;i+8<=bytes.length;i++)if(ByteBuffer.wrap(bytes,i,4).getInt()==bytes.length-i-4&&ByteBuffer.wrap(bytes,i+4,4).getInt()==version){assertEquals(-1,at);at=i;}
        assertTrue("Actual class metadata footer",at>=0);return at;
    }
    private static int readonlyFlag(byte[] bytes,int footer) throws IOException {
        var raw=new ByteArrayInputStream(bytes,footer+4,bytes.length-footer-4);var in=new DataInputStream(raw);
        assertEquals(8,in.readInt());in.readBoolean();in.readBoolean();in.readBoolean();in.readInt();
        assertFalse(in.readBoolean());assertEquals(0,in.readInt());assertEquals(0,in.readInt());
        return bytes.length-raw.available();
    }
    private void rejectClass(ClassDumper d,LookupTrap env,byte[] bytes,String message) throws IOException {
        try{d.load(new ByteArrayInputStream(bytes));fail("Accepted "+message);}catch(DumpException e){assertTrue(e.getMessage(),e.getMessage().contains(message));}
        assertEquals("No autoload before malformed record rejection",0,env.lookups);
    }
    @Test public void classEightRoundTripsAndHistoricalSevenIsMutable() throws Throwable {
        for(boolean debug:new boolean[]{false,true}) {
            var env=new LookupTrap(newScope());try {
                var d=new ClassDumper(CONTEXT,null,env,debug);
                var old=save(d,fixture(false,ClassEntity.Type.CLASS,true,false));footer(old,7);
                var mutable=d.load(new ByteArrayInputStream(old));assertFalse(mutable.isPhpReadonly());assertFalse(mutable.findProperty("x").isPhpReadonly());
                var bytes=save(d,fixture(true,ClassEntity.Type.CLASS,false,false));footer(bytes,8);
                var restored=d.load(new ByteArrayInputStream(bytes));assertTrue(restored.isPhpReadonly());assertTrue(restored.findProperty("x").isPhpReadonly());
                assertEquals(0,env.lookups);
            }finally{env.doFinal();}
        }
    }
    @Test public void classMalformedFlagsAndKindRejectBeforeLookup() throws Throwable {
        for(boolean debug:new boolean[]{false,true}) {
            var env=new LookupTrap(newScope());try {
                var d=new ClassDumper(CONTEXT,null,env,debug);var bytes=save(d,fixture(true,ClassEntity.Type.CLASS,false,false));
                int at=readonlyFlag(bytes,footer(bytes,8));
                for(int flag:new int[]{0,2}){var mutated=bytes.clone();mutated[at]=(byte)flag;rejectClass(d,env,mutated,"flag");}
                rejectClass(d,env,save(d,fixture(true,ClassEntity.Type.TRAIT,false,false)),"kind");
            }finally{env.doFinal();}
        }
    }
    @Test public void classMalformedMutableOrStaticPropertyRejectBeforeLookup() throws Throwable {
        for(boolean debug:new boolean[]{false,true}) {
            var env=new LookupTrap(newScope());try {
                var d=new ClassDumper(CONTEXT,null,env,debug);
                rejectClass(d,env,save(d,fixture(true,ClassEntity.Type.CLASS,true,false)),"class property metadata");
                // A forged class readonly flag must reject a conventional mutable static declaration as well.
                rejectClass(d,env,save(d,fixture(true,ClassEntity.Type.CLASS,true,true)),"static property metadata");
            }finally{env.doFinal();}
        }
    }
    @Test public void classUnknownVersionAndTrailingBytesRejectBeforeLookup() throws Throwable {
        for(boolean debug:new boolean[]{false,true}) {
            var env=new LookupTrap(newScope());try {
                var d=new ClassDumper(CONTEXT,null,env,debug);var bytes=save(d,fixture(true,ClassEntity.Type.CLASS,false,false));
                int at=footer(bytes,8);var unknown=bytes.clone();ByteBuffer.wrap(unknown).putInt(at+4,10);
                rejectClass(d,env,unknown,"version");
                var trailing=Arrays.copyOf(bytes,bytes.length+1);ByteBuffer.wrap(trailing).putInt(at,bytes.length-at-4+1);
                rejectClass(d,env,trailing,"size");
            }finally{env.doFinal();}
        }
    }
}
