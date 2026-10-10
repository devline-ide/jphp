package php.runtime.loader.dump;

import org.junit.Test;
import php.runtime.Memory;
import php.runtime.loader.dump.io.DumpOutputStream;
import php.runtime.reflection.PropertyEntity;
import php.runtime.reflection.support.PhpTypeDeclaration;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import static org.junit.Assert.*;

public class PropertyDeclarationDumperTest {
    @Test public void testTypedUndefinedAndExplicitNullRoundTrip() throws IOException {
        var source = new PropertyEntity(null);
        assertNull(PropertyDeclarationDumper.save(source));
        source.setDeclaredType(new PhpTypeDeclaration(List.of(List.of("int"), List.of("null"))));
        source.setFinal(true);
        var bytes = PropertyDeclarationDumper.save(source);
        var copy = new PropertyEntity(null);
        copy.setDefaultValue(Memory.NULL);
        PropertyDeclarationDumper.load(copy, bytes);
        assertTrue(copy.isFinal());
        assertEquals(source.getDeclaredType().getAlternatives(), copy.getDeclaredType().getAlternatives());
        assertTrue(copy.getDefaultValue().isUndefined());
        assertFalse(copy.hasDefaultValue());
        copy.setDefault(true); copy.setDefaultValue(Memory.NULL);
        PropertyDeclarationDumper.load(copy, bytes);
        assertTrue(copy.hasDefaultValue());
        assertTrue(copy.getDefaultValue().isNull());
        assertFalse(copy.getDefaultValue().isUndefined());
        source.setDeclaredType(null);
        copy = new PropertyEntity(null);
        PropertyDeclarationDumper.load(copy, PropertyDeclarationDumper.save(source));
        assertTrue(copy.isFinal()); assertNull(copy.getDeclaredType());
    }

    @Test public void testRejectsMalformedContractsWithoutPartialMutation() throws IOException {
        var source = new PropertyEntity(null); source.setFinal(true);
        source.setDeclaredType(new PhpTypeDeclaration(List.of(List.of("int"))));
        byte[] valid = PropertyDeclarationDumper.save(source);
        for (int i=1; i<valid.length; i++) reject(Arrays.copyOf(valid, i));
        reject(Arrays.copyOf(valid, valid.length+1));
        var wrongVersion = valid.clone(); wrongVersion[3]=2; reject(wrongVersion);
        for (int size : new int[]{-1,Integer.MAX_VALUE}) {
            var bytes = new ByteArrayOutputStream(); var data = new DumpOutputStream(bytes);
            data.writeInt(1); data.writeBoolean(true); data.writeInt(size); reject(bytes.toByteArray());
        }
    }

    private static void reject(byte[] bytes) {
        var property = new PropertyEntity(null); property.setDefaultValue(Memory.NULL);
        try {PropertyDeclarationDumper.load(property, bytes); fail("Malformed property contract accepted");}
        catch (IOException expected) {
            assertFalse(property.isFinal()); assertNull(property.getDeclaredType());
            assertFalse(property.getDefaultValue().isUndefined());
        }
    }
}
