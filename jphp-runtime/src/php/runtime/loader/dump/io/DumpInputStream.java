package php.runtime.loader.dump.io;

import php.runtime.Memory;
import php.runtime.common.HintType;
import php.runtime.common.LangMode;
import php.runtime.common.Modifier;
import php.runtime.env.Context;
import php.runtime.env.TraceInfo;
import php.runtime.memory.ArrayMemory;
import php.runtime.memory.DoubleMemory;
import php.runtime.memory.LongMemory;
import php.runtime.memory.StringMemory;
import php.runtime.memory.PhpByteStringMemory;
import php.runtime.memory.helper.ClassConstantMemory;
import php.runtime.memory.helper.ConstantMemory;
import php.runtime.reflection.ClassEntity;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;

public class DumpInputStream extends DataInputStream {
    private final boolean rawStrings;

    public DumpInputStream(InputStream in) {
        this(in, in instanceof DumpInputStream && ((DumpInputStream) in).rawStrings);
    }

    public DumpInputStream(InputStream in, boolean rawStrings) {
        super(in);
        this.rawStrings = rawStrings;
    }

    public boolean isRawStringEnabled() { return rawStrings; }

    public String readName(int max) throws IOException {
        int nameLength = readInt();
        if (nameLength == -1)
            return null;
        if (nameLength == 0)
            return "";

        if (nameLength > max || nameLength < 1){
            throw new DumpException("Invalid name size, max " + max);
        } else {
            return readUTF();
        }
    }

    public String readName() throws IOException {
        return readName(2048);
    }

    public TraceInfo readTrace(Context context) throws IOException {
        boolean exits = readBoolean();
        if (exits){
            int line = readInt();
            int position = readInt();
            String file = readUTF();
            return new TraceInfo(file, line, position);
        } else {
            return new TraceInfo(context);
        }
    }

    public Modifier readModifier() throws IOException {
        int tmp = readInt();
        if (tmp == -1) return null;
        if (tmp >= 0 && tmp < Modifier.values().length) {
            return Modifier.values()[tmp];
        } else
            throw new DumpException("Invalid ~Modifier value");
    }

    public HintType readHintType() throws IOException {
        int tmp = readInt();
        if (tmp == -1) return null;
        if (tmp >= 0 && tmp < HintType.values().length)
            return HintType.values()[tmp];
        else
            throw new DumpException("Invalid ~HintType value");
    }

    public ClassEntity.Type readClassType() throws IOException {
        int tmp = readInt();
        if (tmp == -1) return null;
        if (tmp >= 0 && tmp <  ClassEntity.Type.values().length)
            return ClassEntity.Type.values()[tmp];
        else
            throw new DumpException("Invalid ~ClassEntity.Type value");
    }

    public LangMode readLangMode() throws IOException {
        int tmp = readInt();
        if (tmp == -1) return null;
        if (tmp >= 0 && tmp <  LangMode.values().length)
            return LangMode.values()[tmp];
        else
            throw new DumpException("Invalid ~LangMode value");
    }

    public Memory readMemory() throws IOException {
        int tmp = readInt();
        if (tmp == -1)
            return null;
        if (tmp == -2)
            return new ConstantMemory(readUTF());
        if (tmp == -3)
            return new ClassConstantMemory(readUTF(), readUTF());

        if (tmp == -4) {
            if (!rawStrings) throw new DumpException("Raw PHP strings require compiler ABI 17");
            if (readInt() != DumpOutputStream.RAW_STRING_STAMP)
                throw new DumpException("Invalid raw PHP string stamp");
            if (readInt() != DumpOutputStream.RAW_STRING_VERSION)
                throw new DumpException("Unsupported raw PHP string version");
            return new PhpByteStringMemory(readRawData(DumpOutputStream.MAX_RAW_STRING_BYTES));
        }

        if (tmp >= 0 && tmp < Memory.Type.values().length){
            Memory.Type type = Memory.Type.values()[tmp];
            switch (type){
                case BOOL:
                    return readBoolean() ? Memory.TRUE : Memory.FALSE;
                case INT:
                    return LongMemory.valueOf(readLong());
                case DOUBLE:
                    return DoubleMemory.valueOf(readDouble());
                case NULL:
                    return Memory.NULL;
                case STRING:
                    return StringMemory.valueOf(readUTF());
                case ARRAY:
                    ArrayMemory array = new ArrayMemory();
                    int size = readInt();
                    if (size < 0 || size > Short.MAX_VALUE)
                        throw new DumpException("Invalid array memory size");

                    for(int i = 0; i < size; i++){
                        Memory key = readMemory();
                        Memory value = readMemory();
                        array.refOfIndex(key).assign(value);
                    }
                    return array;
                case OBJECT:
                default:
                    throw new DumpException("Cannot read " + type.toString() + " memory");
            }
        } else
            throw new DumpException("Invalid ~Memory.Type value");
    }

    public byte[] readRawData(int max) throws IOException {
        int length = readInt();

        if (length == 0)
            return new byte[0];
        else if (length < 0)
            throw new DumpException("Invalid raw-data size");
        else {
            if (length > max){
                throw new DumpException(
                        "Invalid raw-data size, max " + max + " bytes");
            } else {
                // Read actual payload bytes before committing to the declared allocation.
                byte[] raw = in.readNBytes(length);
                if (raw.length != length)
                    throw new java.io.EOFException("Truncated raw-data payload");
                return raw;
            }
        }
    }

    public byte[] readRawData() throws IOException {
        return readRawData(DumpOutputStream.MAX_LENGTH_OF_RAW_DATA);
    }
}
