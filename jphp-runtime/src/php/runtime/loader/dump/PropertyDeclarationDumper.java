package php.runtime.loader.dump;

import php.runtime.loader.dump.io.DumpException;
import php.runtime.loader.dump.io.DumpInputStream;
import php.runtime.loader.dump.io.DumpOutputStream;
import php.runtime.reflection.PropertyEntity;
import java.io.*;

/** ABI 6 property contracts in the pre-existing optional metadata slot. */
final class PropertyDeclarationDumper {
    private PropertyDeclarationDumper() {}
    static byte[] save(PropertyEntity property) throws IOException {
        if (property.getDeclaredType() == null && !property.isFinal()) return null;
        var bytes = new ByteArrayOutputStream(); var data = new DumpOutputStream(bytes);
        data.writeInt(1); data.writeBoolean(property.isFinal());
        data.writeRawData(TypeDeclarationDumper.save(property.getDeclaredType()));
        return bytes.toByteArray();
    }
    static void load(PropertyEntity property, byte[] bytes) throws IOException {
        if (bytes == null || bytes.length == 0) return;
        var data = new DumpInputStream(new ByteArrayInputStream(bytes));
        if (data.readInt() != 1) throw new DumpException("Unsupported property declaration metadata version");
        boolean isFinal = data.readBoolean();
        var type = TypeDeclarationDumper.load(data.readRawData());
        if (data.available() != 0) throw new DumpException("Invalid property declaration metadata size");
        property.setFinal(isFinal);
        property.setDeclaredType(type);
        if (property.getDeclaredType() != null && !property.isDefault()) property.setDefaultValue(php.runtime.Memory.UNDEFINED);
    }
}
