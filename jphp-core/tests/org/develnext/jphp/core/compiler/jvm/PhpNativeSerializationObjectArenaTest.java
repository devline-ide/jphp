package org.develnext.jphp.core.compiler.jvm;

import org.junit.Test;
import php.runtime.PhpRuntimeOwnership;
import php.runtime.env.Environment;
import php.runtime.lang.StdClass;
import php.runtime.reflection.ClassEntity;
import static org.junit.Assert.*;

/** Explicit known factory and actual native OBJECT/TABLE roots; autoload and hooks remain separate. */
public class PhpNativeSerializationObjectArenaTest {
    private void roundTrip(String wire,String expected) throws Throwable {
        new PhpNativeSerializationArenaTest().roundTrip(wire,expected,authority -> {
            var env=authority.environment();var entity=env.fetchClass("stdClass",false);
            assertNotNull(entity);assertEquals(StdClass.class,entity.getNativeClass());
            try {
                var factory=StdClass.class.getConstructor(Environment.class,ClassEntity.class);
                authority.objects().registerPropertiesProvider(entity,factory,(actual,host) -> {
                    assertSame(entity,actual);assertSame(env,host);
                    assertTrue(actual.getProperties().isEmpty());assertNull(actual.methodMagicWakeup);
                    assertNull(actual.findMethod("__unserialize"));assertNull(actual.methodDestruct);
                });
            }catch(ReflectiveOperationException failure){throw new AssertionError(failure);}
        });
    }
    @Test public void emptyObjectHasExactTableOrigin() throws Throwable {
        roundTrip("O:8:\"stdClass\":0:{}","O:8:\"stdClass\":0:{}");
    }
    @Test public void nestedArrayMovesIntoPhysicalObjectTable() throws Throwable {
        roundTrip("O:8:\"stdClass\":2:{s:1:\"a\";a:2:{i:0;i:3;i:1;i:4;}s:1:\"b\";i:5;}","O:8:\"stdClass\":2:{s:1:\"a\";a:2:{i:0;i:3;i:1;i:4;}s:1:\"b\";i:5;}");
    }
    @Test public void objectSelfValueAliasDoesNotCreateCellOwner() throws Throwable {
        roundTrip("O:8:\"stdClass\":1:{s:4:\"self\";r:1;}","O:8:\"stdClass\":1:{s:4:\"self\";r:1;}");
    }
    @Test public void repeatedObjectAliasesUseValueIdentity() throws Throwable {
        roundTrip("a:2:{i:0;O:8:\"stdClass\":0:{}i:1;r:2;}","a:2:{i:0;O:8:\"stdClass\":0:{}i:1;r:2;}");
    }
    @Test public void duplicatePropertyReferenceUsesActualReplacement() throws Throwable {
        roundTrip("O:8:\"stdClass\":3:{s:1:\"x\";i:1;s:1:\"x\";i:2;s:1:\"y\";R:2;}","O:8:\"stdClass\":2:{s:1:\"x\";i:2;s:1:\"y\";R:2;}");
    }
    @Test public void sharedRootObjectCellKeepsReferenceRole() throws Throwable {
        roundTrip("O:8:\"stdClass\":2:{s:1:\"a\";R:1;s:1:\"b\";R:1;}","O:8:\"stdClass\":2:{s:1:\"a\";R:1;s:1:\"b\";R:1;}");
    }
    @Test public void singletonObjectCellUsesObjectIdentity() throws Throwable {
        roundTrip("O:8:\"stdClass\":3:{s:1:\"x\";R:1;s:1:\"x\";i:2;s:1:\"y\";R:1;}","O:8:\"stdClass\":2:{s:1:\"x\";i:2;s:1:\"y\";r:1;}");
    }
}
