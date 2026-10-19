package php.runtime.loader.dump.io;

import php.runtime.Memory;
import php.runtime.env.TraceInfo;
import php.runtime.lang.ForeachIterator;
import php.runtime.memory.ArrayMemory;
import php.runtime.memory.PhpByteStringMemory;
import java.nio.charset.StandardCharsets;
import php.runtime.memory.helper.ClassConstantMemory;
import php.runtime.memory.helper.ConstantMemory;

import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;

public class DumpOutputStream extends DataOutputStream {

    public static final int RAW_STRING_STAMP = 0x42535452; // BSTR
    public static final int RAW_STRING_VERSION = 1;
    public static final int MAX_RAW_STRING_BYTES = 16 * 1024 * 1024;
    private final boolean rawStrings;

    public DumpOutputStream(OutputStream out) {
        this(out, out instanceof DumpOutputStream && ((DumpOutputStream) out).rawStrings);
    }

    public DumpOutputStream(OutputStream out, boolean rawStrings) {
        super(out);
        this.rawStrings = rawStrings;
    }

    public boolean isRawStringEnabled() { return rawStrings; }

    public void writeName(String value) throws IOException {
        if (value == null)
            writeInt(-1);
        else {
            writeInt(value.length());
            writeUTF(value);
        }
    }

    public void writeTrace(TraceInfo trace) throws IOException {
        writeBoolean(trace != null);
        if (trace != null){
            writeInt(trace.getStartLine());
            writeInt(trace.getStartPosition());
            writeUTF(trace.getFileName());
        }
    }

    public void writeMemory(Memory memory) throws IOException {
        if (memory == null){
            writeInt(-1);
            return;
        }

        memory = memory.toValue();
        if (memory instanceof ConstantMemory){
            writeInt(-2);
            writeUTF(((ConstantMemory) memory).getName());
            return;
        } else if (memory instanceof ClassConstantMemory){
            writeInt(-3);
            writeUTF(((ClassConstantMemory) memory).getClassName());
            writeUTF(((ClassConstantMemory) memory).getName());
            return;
        }

        if (memory instanceof PhpByteStringMemory) {
            if (!rawStrings) throw new DumpException("Raw PHP strings require compiler ABI 17");
            PhpByteStringMemory value = (PhpByteStringMemory) memory;
            if (value.byteLength() > MAX_RAW_STRING_BYTES)
                throw new DumpException("Raw PHP string exceeds maximum size");
            writeInt(-4);
            writeInt(RAW_STRING_STAMP);
            writeInt(RAW_STRING_VERSION);
            writeRawData(value.getBinaryBytes(StandardCharsets.UTF_8), MAX_RAW_STRING_BYTES);
            return;
        }

        Memory.Type type = memory.getRealType();
        writeInt(type.ordinal());
        switch (type){
            case NULL:
                break;
            case INT:
                writeLong(memory.toLong());
                break;
            case STRING:
                writeUTF(memory.toString());
                break;
            case DOUBLE:
                writeDouble(memory.toDouble());
                break;
            case BOOL:
                writeBoolean(memory.toBoolean());
                break;
            case ARRAY:
                ArrayMemory array = memory.toValue(ArrayMemory.class);
                if (array.size() > Short.MAX_VALUE)
                    throw new DumpException("Array is too big");

                writeInt(array.size());
                ForeachIterator foreachIterator = array.foreachIterator(false, false);
                while (foreachIterator.next()){
                    Memory key = foreachIterator.getMemoryKey();
                    Memory value = foreachIterator.getValue();
                    if (value.isShortcut())
                        throw new DumpException("Cannot dump references");

                    if (value.toValue() != Memory.UNDEFINED){
                        writeMemory(key);
                        writeMemory(value.toValue());
                    }
                }
                break;
            case OBJECT:
            default:
                throw new DumpException("Cannot dump "+ type.toString() +" memory");
        }
    }

    public final static int MAX_LENGTH_OF_RAW_DATA = 1024 * 200; // 200 kb

    public void writeRawData(byte[] bytes, int max) throws IOException {
        if (bytes == null || bytes.length == 0)
            writeInt(0);
        else {
            if (bytes.length > max)
                throw new DumpException("Raw-size is too big, max " + MAX_LENGTH_OF_RAW_DATA);

            writeInt(bytes.length);
            write(bytes);
        }
    }

    public void writeRawData(byte[] bytes) throws IOException {
        writeRawData(bytes, MAX_LENGTH_OF_RAW_DATA);
    }

    public void writeEnum(Enum _enum) throws IOException {
        writeInt(_enum == null ? -1 : _enum.ordinal());
    }
}
