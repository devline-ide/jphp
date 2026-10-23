package org.develnext.jphp.core.compiler.jvm;

import java.io.ByteArrayOutputStream;
import org.junit.Test;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.Context;
import php.runtime.env.Environment;
import php.runtime.env.TraceInfo;
import php.runtime.exceptions.CriticalException;
import php.runtime.reflection.ClassEntity;
import php.runtime.reflection.ModuleEntity;
import static org.junit.Assert.*;

/** Cold negative controls: exact-type checks run before any overridable metadata getter. */
public class PhpStaticDeclarationBridgeMetadataTest extends JvmCompilerCase {
    private static final class ForeignModule extends ModuleEntity {
        int effects;
        ForeignModule(){super(new Context(""));}
        @Override public ClassEntity findClass(String name){effects++;throw new AssertionError("foreign findClass entered");}
        @Override public String getInternalName(){effects++;throw new AssertionError("foreign module identity entered");}
        @Override public PhpLanguageProfile getPhpLanguageProfile(){effects++;throw new AssertionError("foreign profile entered");}
    }
    private static final class ForeignClass extends ClassEntity {
        int effects;
        ForeignClass(){super(new Context(""));}
        @Override public ModuleEntity getModule(){effects++;throw new AssertionError("foreign declaring module entered");}
        @Override public boolean isStatic(){effects++;throw new AssertionError("foreign class flags entered");}
    }
    private static void rejects(Environment env) {
        try {env.__checkClassDeclaration(TraceInfo.UNKNOWN,"metadata-probe","MetaProbe");fail("Foreign metadata accepted");}
        catch(CriticalException expected){assertEquals("Invalid static class declaration identity",expected.getMessage());}
    }
    @Test public void foreignModuleRejectsBeforeAnyMetadataDispatch() throws Throwable {
        var env=new Environment(newScope(),new ByteArrayOutputStream());
        try {
            var module=new ForeignModule();module.effects=0;
            env.getScope().moduleIndexMap.put("metadata-probe",module);
            rejects(env);assertEquals(0,module.effects);
        }finally{env.doFinal();}
    }
    @Test public void foreignClassRejectsBeforeAnyMetadataDispatch() throws Throwable {
        var env=new Environment(newScope(),new ByteArrayOutputStream());
        try {
            var module=new ModuleEntity(new Context(""));module.setInternalName("metadata-probe");module.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
            var entity=new ForeignClass();entity.setName("MetaProbe");module.addClass(entity);entity.effects=0;
            env.getScope().moduleIndexMap.put("metadata-probe",module);
            rejects(env);assertEquals(0,entity.effects);
        }finally{env.doFinal();}
    }
}
