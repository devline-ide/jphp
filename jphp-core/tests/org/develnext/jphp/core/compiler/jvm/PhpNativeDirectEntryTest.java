package org.develnext.jphp.core.compiler.jvm;

import org.junit.Test;
import php.runtime.Memory;
import php.runtime.annotation.Reflection.*;
import php.runtime.common.HintType;
import php.runtime.ext.support.compile.CompileFunction;
import static org.junit.Assert.*;

public class PhpNativeDirectEntryTest {
    public static class Declarations {
        @Signature({@Arg(value="value",type=HintType.STRING)})
        @php.runtime.annotation.Runtime.DirectCall("exact")
        public static Memory ordinary(Memory value) {return value;}
        @Ignore @Signature({@Arg(value="value",type=HintType.STRING)})
        public static Memory exact(@php.runtime.annotation.Runtime.Reference Memory value) {return value;}
        @Signature({@Arg(value="value",type=HintType.STRING)})
        @php.runtime.annotation.Runtime.DirectCall("absent")
        public static Memory missing(Memory value) {return value;}
        @Signature({@Arg(value="value",type=HintType.STRING)})
        @php.runtime.annotation.Runtime.DirectCall("wrongReturn")
        public static Memory returnConflict(Memory value) {return value;}
        @Ignore @Signature({@Arg(value="value",type=HintType.STRING)})
        public static String wrongReturn(Memory value) {return value.toString();}
        @Signature({@Arg(value="value",type=HintType.STRING)})
        @php.runtime.annotation.Runtime.DirectCall("wrongParameter")
        public static Memory declarationConflict(Memory value) {return value;}
        @Ignore @Signature({@Arg(value="other",type=HintType.INT)})
        public static Memory wrongParameter(Memory value) {return value;}
        @Signature({@Arg(value="value",type=HintType.STRING)})
        @php.runtime.annotation.Runtime.DirectCall("visible")
        public static Memory publicationConflict(Memory value) {return value;}
        @Signature({@Arg(value="value",type=HintType.STRING)})
        public static Memory visible(Memory value) {return value;}
        @Signature({@Arg(value="value",type=HintType.STRING)})
        @php.runtime.annotation.Runtime.DirectCall("self")
        public static Memory self(Memory value) {return value;}
        @Signature({@Arg(value="value",type=HintType.STRING)})
        @php.runtime.annotation.Runtime.DirectCall("folded")
        public static Memory foldingConflict(Memory value) {return value;}
        @Ignore @Signature({@Arg(value="value",type=HintType.STRING)})
        @php.runtime.annotation.Runtime.Immutable
        public static Memory folded(Memory value) {return value;}
    }
    private CompileFunction function(String name)throws Exception {
        var f=new CompileFunction(name);f.addMethod(Declarations.class.getDeclaredMethod(name,Memory.class));return f;
    }
    @Test public void directTargetIsExactWhileDynamicSelectionRetainsPublishedEntry()throws Exception {
        var f=function("ordinary");var ordinary=f.find(1);var direct=f.findDirect(1);
        assertEquals("ordinary",ordinary.method.getName());assertEquals("exact",direct.method.getName());
        assertSame(ordinary,f.find(1));assertTrue(direct.references[0]);assertFalse(direct.isImmutable);
    }
    @Test public void missingConflictingVisibleSelfAndFoldedTargetsFailClosed()throws Exception {
        for(String name:new String[]{"missing","returnConflict","declarationConflict","publicationConflict","self","foldingConflict"}) {
            try {function(name).findDirect(1);fail("Accepted invalid native direct target: "+name);}
            catch(IllegalArgumentException expected) {}
        }
    }
    @Test public void actualJoinAliasRetainsGenericEntryWhileImplodeHasDeclaredDirectSibling()throws Exception {
        for(String name:new String[]{"nativeImplode","nativeJoin"})for(int arity:new int[]{1,2}) {
            Class<?>[] types=arity==1?new Class<?>[]{php.runtime.env.Environment.class,php.runtime.env.TraceInfo.class,Memory.class}
                    :new Class<?>[]{php.runtime.env.Environment.class,php.runtime.env.TraceInfo.class,Memory.class,Memory.class};
            var method=org.develnext.jphp.zend.ext.standard.StringFunctions.class.getDeclaredMethod(name,types);
            var function=new CompileFunction(name);function.addMethod(method);
            if(name.equals("nativeJoin")){assertSame(function.find(arity),function.findDirect(arity));assertNull(method.getAnnotation(php.runtime.annotation.Runtime.DirectCall.class));}
            else assertEquals("directImplode",function.findDirect(arity).method.getName());
            assertNotNull(method.getAnnotation(php.runtime.annotation.Runtime.StringJoin.class));
        }
    }
}
