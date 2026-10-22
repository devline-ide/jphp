package php.runtime.memory;

import org.junit.Assert;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.junit.runners.MethodSorters;
import php.runtime.Memory;

@RunWith(JUnit4.class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class ReferenceMemoryTest {

    @Test
    public void testValueOf(){
        Memory memory = ReferenceMemory.valueOf(Memory.TRUE);
        Assert.assertTrue(memory instanceof ReferenceMemory);
        Assert.assertFalse(memory.isImmutable());
        Assert.assertNotNull(memory.toImmutable());
        Assert.assertTrue(memory.toImmutable().isImmutable());
        Assert.assertTrue(memory.toImmutable() instanceof TrueMemory);
    }

    @Test
    public void testNew(){
        Memory memory = new ReferenceMemory(Memory.FALSE);
        Assert.assertEquals(Memory.FALSE, memory.toImmutable());
    }

    @Test
    public void testAssign(){
        Memory memory = new ReferenceMemory();

        memory.assign(true);
        Assert.assertEquals(Memory.TRUE, memory.toImmutable());
        Assert.assertTrue(memory.toBoolean());

        memory.assign(false);
        Assert.assertEquals(Memory.FALSE, memory.toImmutable());
        Assert.assertFalse(memory.toBoolean());

        memory.assign(12);
        Assert.assertTrue(memory.toImmutable() instanceof LongMemory);
        Assert.assertEquals(12, memory.toLong());

        memory.assign(12.3);
        Assert.assertTrue(memory.toImmutable() instanceof DoubleMemory);
        Assert.assertEquals(12.3, memory.toDouble(), 0.000001);

        memory.assign("foobar");
        Assert.assertTrue(memory.toImmutable() instanceof StringMemory);
        Assert.assertEquals("foobar", memory.toString());

        memory.assign(new ReferenceMemory(new StringMemory("foobar")));
        Assert.assertTrue(memory.toImmutable() instanceof StringMemory);
        Assert.assertEquals("foobar", memory.toString());
    }

    @Test
    public void testAssignRef(){
        Memory memory = new ReferenceMemory();
        ReferenceMemory ref = new ReferenceMemory(Memory.TRUE);

        memory.assign(ref);
        Assert.assertEquals(Memory.TRUE, memory.toImmutable());

        memory.assign(false);
        Assert.assertEquals(Memory.FALSE, ref.toImmutable());

        Memory memory2 = new ReferenceMemory();
        memory2.assign(memory);
        Assert.assertEquals(Memory.FALSE, memory.toImmutable());

        memory2.assign(12);
        Assert.assertEquals(12, memory.toLong());
        Assert.assertEquals(12, ref.toLong());
    }

    @Test public void testLegacyCopyPreservesActualVirtualConversionAndInheritedArrayReset() {
        var sentinel=new StringMemory("converted");var calls=new int[1];
        ReferenceMemory custom=new ReferenceMemory(Memory.NULL) {
            @Override public Memory toImmutable(){calls[0]++;return sentinel;}
            @Override public Memory getValue(){throw new AssertionError("Custom immutable conversion must run before payload reads");}
        };
        Assert.assertSame(sentinel,custom.toImmutableForLegacy());Assert.assertEquals(1,calls[0]);
        Assert.assertSame(sentinel,custom.toImmutableForLegacy());Assert.assertEquals(2,calls[0]);
        Assert.assertSame(sentinel,custom.toImmutable());Assert.assertEquals(3,calls[0]);

        var pair=new KeyValueMemory(new StringMemory("named"),LongMemory.valueOf(7));
        Assert.assertSame(pair,pair.toImmutableForLegacy());Assert.assertEquals("named",pair.getArrayKey());
        var nativeValue=new NativeMemory<Object>(new Object());Assert.assertSame(nativeValue,nativeValue.toImmutableForLegacy());
        var observable=new php.runtime.memory.helper.ObservableMemory(Memory.CONST_INT_1);
        Assert.assertSame(observable,observable.toImmutableForLegacy());
        var cell=new ReferenceMemory(Memory.CONST_INT_1);var shortcut=new php.runtime.memory.helper.ShortcutMemory(cell);
        Assert.assertSame(cell,shortcut.toImmutableForLegacy());

        var arrayCalls=new int[1];ArrayMemory customArray=new ArrayMemory() {
            @Override public Memory toImmutable(){arrayCalls[0]++;return sentinel;}
        };
        Assert.assertSame(sentinel,customArray.toImmutableForLegacy());Assert.assertEquals(1,arrayCalls[0]);
        Assert.assertSame(sentinel,customArray.toImmutableForLegacy());Assert.assertEquals(2,arrayCalls[0]);
        Assert.assertSame(sentinel,new ReferenceMemory(customArray).toImmutableForLegacy());Assert.assertEquals(3,arrayCalls[0]);

        for(boolean inheritedReference:new boolean[]{false,true}) {
            var source=ArrayMemory.ofStrings("first","second");source.getCurrentIteratorForNative().next();
            Assert.assertEquals("second",source.getCurrentIterator().getValue().toString());
            ReferenceMemory wrapper=inheritedReference?new ReferenceMemory(source){}:new ReferenceMemory(source);
            var copy=(ArrayMemory)wrapper.toImmutableForLegacy();Assert.assertNotSame(source,copy);
            Assert.assertNull("legacy source reset",source.getCurrentIterator().getKey());
            Assert.assertNull("legacy copy reset",copy.getCurrentIterator().getKey());
        }
        ArrayMemory inheritedArray=new ArrayMemory(){};inheritedArray.add("first");inheritedArray.add("second");
        inheritedArray.getCurrentIteratorForNative().next();var copy=(ArrayMemory)inheritedArray.toImmutableForLegacy();
        Assert.assertNull(inheritedArray.getCurrentIterator().getKey());Assert.assertNull(copy.getCurrentIterator().getKey());
        var modern=ArrayMemory.ofStrings("first","second");modern.getCurrentIteratorForNative().next();
        var modernCopy=(ArrayMemory)new ReferenceMemory(modern).toImmutable();
        Assert.assertEquals("second",modern.getCurrentIterator().getValue().toString());
        Assert.assertEquals("second",modernCopy.getCurrentIterator().getValue().toString());
    }
}
