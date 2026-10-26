package php.runtime.memory;

import org.develnext.jphp.core.compiler.jvm.JvmCompiler;
import org.develnext.jphp.core.compiler.jvm.JvmCompilerCase;
import org.junit.Test;
import php.runtime.Memory;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.Context;
import php.runtime.env.Environment;
import php.runtime.env.TraceInfo;
import php.runtime.loader.dump.ModuleDumper;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;

import static org.junit.Assert.*;

public class PackedArrayReadTest extends JvmCompilerCase {
    @Test public void packedNumericReadsPreserveCellIdentityAndMissingKeys() {
        ArrayMemory array=ArrayMemory.createListed(32768);
        for(int index=0;index<32768;index++)array.add(index+17L);
        assertNull(array.map);assertNotNull(array._list);
        for(int index:new int[]{0,1,28087,30001,32767}) {
            Memory cell=array.getByScalar(LongMemory.valueOf((long)index));
            assertSame(cell,array.valueOfIndex(null,(long)index));
            assertSame(cell,array.valueOfIndex(null,new LongMemory(index)));
            assertSame(cell,array.valueOfIndex(null,new ReferenceMemory(new LongMemory(index))));
            assertEquals(index+17L,cell.toLong());
        }
        for(long index:new long[]{-1,Long.MIN_VALUE,32768,((long)Integer.MAX_VALUE)+1,Long.MAX_VALUE}) {
            assertSame(Memory.UNDEFINED,array.valueOfIndex(null,index));
            assertSame(Memory.UNDEFINED,array.valueOfIndex(null,new LongMemory(index)));
        }
        assertEquals(32768,array.size());assertNull(array.map);
        array._list.set(1,null);
        assertNull(array.getByScalar(Memory.CONST_INT_1));
        assertSame(Memory.UNDEFINED,array.valueOfIndex(null,1L));
        assertSame(Memory.UNDEFINED,array.valueOfIndex(null,new LongMemory(1)));
    }

    @Test public void readsDoNotDetachCowOrChangeInternalCursorAndReferencesStillAlias() {
        ArrayMemory original=new ArrayMemory();original.add(10L);original.add(20L);
        ArrayMemory copy=(ArrayMemory)original.toImmutable();
        Object storage=original._list;
        original.resetCurrentIteratorForNative();
        for(int repeat=0;repeat<16;repeat++) {
            assertSame(original.getByScalar(Memory.CONST_INT_1),original.valueOfIndex(null,1L));
            assertSame(copy.getByScalar(Memory.CONST_INT_1),copy.valueOfIndex(null,new LongMemory(1)));
        }
        assertSame(storage,original._list);assertSame(storage,copy._list);
        assertEquals(0,original.getCurrentIterator().getMemoryKey().toLong());
        copy.refOfIndex(1L).assign(99L);
        assertEquals(20,original.valueOfIndex(null,1L).toLong());assertEquals(99,copy.valueOfIndex(null,1L).toLong());
        ReferenceMemory alias=new ReferenceMemory(original);
        assertSame(original.valueOfIndex(null,0L),alias.valueOfIndex(null,0L));
        original.refOfIndex(0L).assign(77L);
        assertEquals(77,alias.valueOfIndex(null,new LongMemory(0)).toLong());
    }

    @Test public void mapSparseAndSubclassLookupsRetainTheirContracts() {
        ArrayMemory map=new ArrayMemory(true);map.refOfIndex(30001L).assign(41L);map.refOfIndex(-1L).assign(42L);
        for(long key:new long[]{30001,-1}) {
            assertSame(map.getByScalar(LongMemory.valueOf(key)),map.valueOfIndex(null,key));
            assertSame(map.getByScalar(LongMemory.valueOf(key)),map.valueOfIndex(null,new LongMemory(key)));
        }
        ArrayMemory sparse=new ArrayMemory();sparse.add(1L);sparse.refOfIndex(30001L).assign(2L);
        assertNotNull(sparse.map);assertEquals(2,sparse.valueOfIndex(null,30001L).toLong());
        class CustomArray extends ArrayMemory {
            int calls;
            final ReferenceMemory sentinel=new ReferenceMemory(LongMemory.valueOf(123));
            @Override public ReferenceMemory getByScalar(Object key){calls++;return sentinel;}
        }
        CustomArray custom=new CustomArray();custom.add(1L);
        assertSame(custom.sentinel,custom.valueOfIndex(null,0L));
        assertSame(custom.sentinel,custom.valueOfIndex(null,new LongMemory(0)));
        assertEquals(2,custom.calls);
        class CustomKey extends LongMemory {
            int conversions;
            CustomKey(){super(0);}
            @Override public long toLong(){conversions++;return 1;}
        }
        ArrayMemory packed=new ArrayMemory();packed.add(10L);packed.add(20L);
        CustomKey key=new CustomKey();assertEquals(20,packed.valueOfIndex(null,key).toLong());assertEquals(1,key.conversions);
    }

    @Test public void modernPhpReadsMutationsAndCowAgreeForSourceAndPhb() throws Throwable {
        String code="function packedRead($rows,$start,$count){$sum=0;for($i=0;$i<$count;$i++){$sum+=$rows[$start+$i];}return $sum;} " +
                "$rows=[];for($i=0;$i<32768;$i++)$rows[]=$i+17; $copy=$rows; " +
                "$sum=packedRead($rows,30001,64); $copy[30001]=99; $alias=&$rows[0];$alias=77; " +
                "return [$sum,$rows[30001],$copy[30001],$rows[0],isset($rows[-1]),isset($rows[32768])];";
        long expected=64L*(30001+17)+(63L*64)/2;
        for(boolean dumped:new boolean[]{false,true}) {
            Environment env=new Environment(newScope(),OutputStream.nullOutputStream());
            try {
                Context context=new Context(code);context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
                var module=new JvmCompiler(env,context).compile(false);
                if(dumped){var bytes=new ByteArrayOutputStream();var dumper=new ModuleDumper(context,env,true);dumper.save(module,bytes);module=dumper.load(new ByteArrayInputStream(bytes.toByteArray()));}
                env.scope.loadModule(module);env.scope.addUserModule(module);env.registerModule(module);
                Memory result=module.include(env);
                assertEquals(expected,result.valueOfIndex(null,0).toLong());assertEquals(30018,result.valueOfIndex(null,1).toLong());
                assertEquals(99,result.valueOfIndex(null,2).toLong());assertEquals(77,result.valueOfIndex(null,3).toLong());
                assertFalse(result.valueOfIndex(null,4).toBoolean());assertFalse(result.valueOfIndex(null,5).toBoolean());
            }finally{env.doFinal();}
        }
    }
}
