package org.develnext.jphp.core.compiler.jvm;

import org.junit.Test;
import php.runtime.Memory;
import php.runtime.annotation.Reflection.*;
import php.runtime.common.HintType;
import php.runtime.ext.CoreExtension;
import php.runtime.ext.support.compile.CompileFunctionSpec;
import php.runtime.reflection.CompileFunctionEntity;
import static org.junit.Assert.*;

public class PhpImplodeMetadataTest {
    public static class Declarations {
        @Signature({@Arg(value="separator",typeDeclaration="array|string")})
        public static Memory valid(Memory value) {return value;}
        @Signature({@Arg(value="separator",typeDeclaration="array|int")})
        public static Memory unchecked(Memory value) {return value;}
        @Signature({@Arg(value="separator",typeDeclaration="array|string",type=HintType.STRING)})
        public static Memory scalarConflict(Memory value) {return value;}
        @Signature({@Arg(value="separator",typeDeclaration="array|string",nullable=true)})
        public static Memory nullableConflict(Memory value) {return value;}
        @Signature({@Arg(value="separator",typeDeclaration="array|string",typeClass="stdClass")})
        public static Memory classConflict(Memory value) {return value;}
        @Signature({@Arg(value="separator",typeDeclaration="array|string",optional=@Optional(value="null"))})
        public static Memory defaultConflict(Memory value) {return value;}
    }
    private CompileFunctionEntity declaration(String name)throws Exception {
        var spec=new CompileFunctionSpec(name);
        spec.addMethod(Declarations.class.getDeclaredMethod(name,Memory.class));
        return new CompileFunctionEntity(new CoreExtension(),spec);
    }
    @Test public void exactUnionPublishesConcreteCheckerRatherThanAny()throws Exception {
        var p=declaration("valid").getParameters()[0];
        assertNotNull(p.getTypeChecker());assertEquals("array|string",p.getTypeChecker().getSignature());
        assertTrue(p.checkTypeHinting(null,new php.runtime.memory.ArrayMemory()));
        assertTrue(p.checkTypeHinting(null,new php.runtime.memory.StringMemory("text")));
        assertFalse(p.checkTypeHinting(null,Memory.NULL));assertFalse(p.checkTypeHinting(null,Memory.TRUE));
    }
    @Test public void unknownAndConflictingParameterDeclarationsFailClosed()throws Exception {
        for(String name:new String[]{"unchecked","scalarConflict","nullableConflict","classConflict","defaultConflict"}) {
            try {declaration(name);fail("Accepted invalid native parameter declaration: "+name);}
            catch(IllegalArgumentException expected) {}
        }
    }
    @Test public void historicalJavaDescriptorsRemainPublicAndIgnoredForPhpRegistration()throws Exception {
        var owner=org.develnext.jphp.zend.ext.standard.StringFunctions.class;
        for(String name:new String[]{"implode","join"})for(int arity:new int[]{1,2}) {
            Class<?>[] types=arity==1 ? new Class<?>[]{php.runtime.env.Environment.class,php.runtime.env.TraceInfo.class,Memory.class}
                    : new Class<?>[]{php.runtime.env.Environment.class,php.runtime.env.TraceInfo.class,Memory.class,Memory.class};
            var method=owner.getDeclaredMethod(name,types);
            assertEquals(Memory.class,method.getReturnType());assertTrue(java.lang.reflect.Modifier.isPublic(method.getModifiers()));
            assertNotNull(method.getAnnotation(Ignore.class));
        }
    }
}
