package php.runtime.loader.dump;

import java.io.*;
import java.util.*;
import php.runtime.loader.dump.io.DumpException;
import php.runtime.loader.dump.io.DumpInputStream;
import php.runtime.loader.dump.io.DumpOutputStream;
import php.runtime.reflection.support.PhpTypeDeclaration;
import php.runtime.reflection.support.TypeChecker;

/** Optional versioned metadata, preserving the old declaration wire layout. */
final class TypeDeclarationDumper {
    private TypeDeclarationDumper() {}
    static byte[] save(TypeChecker checker) throws IOException {
        if (!(checker instanceof PhpTypeDeclaration type)) return null;
        var bytes = new ByteArrayOutputStream(); var data = new DumpOutputStream(bytes);
        data.writeInt(1); data.writeInt(type.getAlternatives().size());
        for (var arm : type.getAlternatives()) {
            data.writeInt(arm.size()); for (String name : arm) data.writeName(name);
        }
        return bytes.toByteArray();
    }
    static PhpTypeDeclaration load(byte[] bytes) throws IOException {
        if (bytes == null || bytes.length == 0) return null;
        var data = new DumpInputStream(new ByteArrayInputStream(bytes));
        if (data.readInt() != 1) throw new DumpException("Unsupported type declaration metadata version");
        int count = data.readInt();
        if (count < 1 || count > data.available() / 11) throw new DumpException("Invalid type alternatives");
        List<List<String>> arms = new ArrayList<>();
        for (int i=0; i<count; i++) {
            int members = data.readInt();
            if (members < 1 || members > data.available() / 7) throw new DumpException("Invalid type members");
            List<String> arm = new ArrayList<>();
            for (int j=0; j<members; j++) {
                int length = data.readInt();
                if (length < 1 || length > 32768) throw new DumpException("Invalid type name size");
                String name = data.readUTF();
                if (name.length() != length) throw new DumpException("Invalid type name size");
                arm.add(name);
            }
            arms.add(arm);
        }
        if (data.available() != 0) throw new DumpException("Invalid type declaration metadata size");
        return new PhpTypeDeclaration(arms);
    }
}
