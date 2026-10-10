package php.runtime.loader.dump;

import org.junit.Test;
import php.runtime.common.HintType;
import php.runtime.loader.dump.io.DumpOutputStream;
import php.runtime.reflection.support.PhpTypeDeclaration;
import php.runtime.reflection.support.TypeChecker;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

public class TypeDeclarationDumperTest {
    @Test public void testOptionalAndCompoundRoundTrip() throws IOException {
        assertNull(TypeDeclarationDumper.load(null));
        assertNull(TypeDeclarationDumper.load(new byte[0]));
        assertNull(TypeDeclarationDumper.save(TypeChecker.of(HintType.INT)));
        var type = new PhpTypeDeclaration(List.of(List.of("Namespace\\A", "Namespace\\B"), List.of("false"), List.of("null")));
        var copy = TypeDeclarationDumper.load(TypeDeclarationDumper.save(type));
        assertEquals(type.getSignature(), copy.getSignature());
        assertEquals(type.getAlternatives(), copy.getAlternatives());
        assertTrue(copy.allowsNull());
    }

    @Test public void testLargeUnionAndNameBoundary() throws IOException {
        var arms = new ArrayList<List<String>>();
        for (int i=0; i<140; i++) arms.add(List.of("Type" + i));
        arms.add(List.of("A".repeat(32768)));
        var type = new PhpTypeDeclaration(arms);
        assertEquals(type.getAlternatives(), TypeDeclarationDumper.load(TypeDeclarationDumper.save(type)).getAlternatives());
    }

    @Test public void testRejectsMalformedMetadata() throws IOException {
        reject(data(2, 1, 1, "A"));
        reject(data(1, 0, 1, "A"));
        reject(data(1, -1, 1, "A"));
        reject(data(1, Integer.MAX_VALUE, 1, "A"));
        reject(data(1, 1, 0, "A"));
        reject(data(1, 1, -1, "A"));
        reject(data(1, 1, Integer.MAX_VALUE, "A"));
        reject(data(1, 1, 1, null));
        reject(data(1, 1, 1, ""));
        reject(data(1, 1, 1, "A".repeat(32769)));
        var valid = data(1, 1, 1, "ClassName");
        for (int i=1; i<valid.length; i++) reject(Arrays.copyOf(valid, i));
        reject(Arrays.copyOf(valid, valid.length + 1));
        // A forged UTF length must not evade the name limit or disagree with the saved name.
        var forged = valid.clone(); forged[15] = 1;
        reject(forged);
        forged = data(1, 1, 1, "A".repeat(32769));
        forged[12] = 0; forged[13] = 0; forged[14] = 0; forged[15] = 1;
        reject(forged);
    }

    private static byte[] data(int version, int arms, int members, String name) throws IOException {
        var bytes = new ByteArrayOutputStream();
        var stream = new DumpOutputStream(bytes);
        stream.writeInt(version); stream.writeInt(arms); stream.writeInt(members); stream.writeName(name);
        return bytes.toByteArray();
    }

    private static void reject(byte[] bytes) {
        try {
            TypeDeclarationDumper.load(bytes);
            fail("Malformed type metadata was accepted");
        } catch (IOException expected) {
            // Invalid dumps must fail as I/O errors, without unchecked allocation/index failures.
        }
    }
}
