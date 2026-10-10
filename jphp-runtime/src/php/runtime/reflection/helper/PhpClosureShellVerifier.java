package php.runtime.reflection.helper;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;

/** Verifies only the known side-effect-free generated Closure shell, never its invocation body. */
public final class PhpClosureShellVerifier {
    private static final String PARENT = "php/runtime/lang/Closure";
    private static final String CONSTRUCTOR = "(Lphp/runtime/env/Environment;Lphp/runtime/reflection/ClassEntity;Lphp/runtime/Memory;Ljava/lang/String;[Lphp/runtime/Memory;)V";
    private PhpClosureShellVerifier() {}

    public static boolean verify(byte[] bytecode, String generatedClassName) {
        if (bytecode == null || generatedClassName == null) throw new IllegalArgumentException("Missing closure shell bytecode identity");
        try { return new Reader(bytecode).verify(generatedClassName.replace('.', '/')); }
        catch (IOException malformed) { throw new IllegalArgumentException("Malformed closure shell bytecode", malformed); }
    }

    private static final class Reader {
        private final DataInputStream input;
        private int[] tags, first, second;
        private String[] utf8;
        Reader(byte[] bytes) { input = stream(bytes); }
        private static DataInputStream stream(byte[] bytes) { return new DataInputStream(new ByteArrayInputStream(bytes)); }
        private void require(boolean valid) throws IOException { if (!valid) throw new IOException("Invalid closure class structure"); }
        private void index(int index, int... expected) throws IOException {
            require(index > 0 && index < tags.length);
            for (int tag : expected) if (tags[index] == tag) return;
            throw new IOException("Invalid closure constant-pool reference");
        }
        private String text(int index) throws IOException { index(index, 1); return utf8[index]; }
        private String className(int index) throws IOException { index(index, 7); return text(first[index]); }
        private byte[] bytes(DataInputStream in) throws IOException {
            int size = in.readInt(); require(size >= 0 && size <= in.available());
            return in.readNBytes(size);
        }
        private void attributes(DataInputStream in) throws IOException {
            int count = in.readUnsignedShort();
            for (int i = 0; i < count; i++) { text(in.readUnsignedShort()); bytes(in); }
        }
        private void constants() throws IOException {
            int count = input.readUnsignedShort(); require(count > 0);
            tags = new int[count]; first = new int[count]; second = new int[count]; utf8 = new String[count];
            for (int i = 1; i < count; i++) {
                int tag = tags[i] = input.readUnsignedByte();
                switch (tag) {
                    case 1: utf8[i] = input.readUTF(); break;
                    case 3: case 4: input.readInt(); break;
                    case 5: case 6: input.readLong(); require(i + 1 < count); i++; break;
                    case 7: case 8: case 16: case 19: case 20: first[i] = input.readUnsignedShort(); break;
                    case 9: case 10: case 11: case 12: case 17: case 18:
                        first[i] = input.readUnsignedShort(); second[i] = input.readUnsignedShort(); break;
                    case 15: first[i] = input.readUnsignedByte(); second[i] = input.readUnsignedShort(); break;
                    default: throw new IOException("Invalid closure constant-pool tag");
                }
            }
            for (int i = 1; i < count; i++) switch (tags[i]) {
                case 7: case 8: case 16: case 19: case 20: index(first[i], 1); break;
                case 9: case 10: case 11: index(first[i], 7); index(second[i], 12); break;
                case 12: index(first[i], 1); index(second[i], 1); break;
                case 17: case 18: index(second[i], 12); break;
                case 15:
                    require(first[i] >= 1 && first[i] <= 9);
                    if (first[i] <= 4) index(second[i], 9);
                    else if (first[i] == 9) index(second[i], 11);
                    else if (first[i] == 5 || first[i] == 8) index(second[i], 10);
                    else index(second[i], 10, 11);
                    break;
                default: break;
            }
        }
        private boolean constructor(byte[] attribute) throws IOException {
            var in = stream(attribute);
            int stack = in.readUnsignedShort(), locals = in.readUnsignedShort();
            byte[] code = bytes(in);
            int handlers = in.readUnsignedShort();
            for (int i = 0; i < handlers; i++) {
                in.readUnsignedShort(); in.readUnsignedShort(); in.readUnsignedShort();
                int catchType = in.readUnsignedShort(); if (catchType != 0) index(catchType, 7);
            }
            attributes(in); require(in.available() == 0);
            if (stack != 6 || locals != 6 || handlers != 0) return false;
            var instructions = stream(code);
            for (int slot = 0; slot <= 5; slot++) {
                int opcode = instructions.readUnsignedByte();
                if (slot <= 3 && opcode == 42 + slot) continue;
                if (opcode != 25 || instructions.readUnsignedByte() != slot) return false;
            }
            if (instructions.readUnsignedByte() != 183) return false;
            int call = instructions.readUnsignedShort(); index(call, 10);
            if (!PARENT.equals(className(first[call]))) return false;
            int nameType = second[call];
            if (!"<init>".equals(text(first[nameType])) || !CONSTRUCTOR.equals(text(second[nameType]))) return false;
            return instructions.readUnsignedByte() == 177 && instructions.available() == 0;
        }
        boolean verify(String expectedName) throws IOException {
            require(input.readInt() == 0xCAFEBABE);
            input.readUnsignedShort(); int version = input.readUnsignedShort(); require(version >= 45);
            constants(); int access = input.readUnsignedShort();
            String name = className(input.readUnsignedShort()), parent = className(input.readUnsignedShort());
            boolean known = name.equals(expectedName) && PARENT.equals(parent) && (access & (0x0200 | 0x0400 | 0x2000 | 0x4000)) == 0;
            int interfaces = input.readUnsignedShort(); known &= interfaces == 0;
            for (int i = 0; i < interfaces; i++) index(input.readUnsignedShort(), 7);
            int fields = input.readUnsignedShort();
            for (int i = 0; i < fields; i++) {
                int flags = input.readUnsignedShort(); text(input.readUnsignedShort()); text(input.readUnsignedShort());
                known &= (flags & 8) != 0; attributes(input);
            }
            int methods = input.readUnsignedShort(), constructors = 0;
            for (int i = 0; i < methods; i++) {
                int flags = input.readUnsignedShort(); String method = text(input.readUnsignedShort()), descriptor = text(input.readUnsignedShort());
                int attributes = input.readUnsignedShort(); boolean valid = false; int codes = 0;
                for (int a = 0; a < attributes; a++) {
                    String attributeName = text(input.readUnsignedShort()); byte[] attribute = bytes(input);
                    if (method.equals("<init>") && attributeName.equals("Code")) { codes++; valid = constructor(attribute); }
                }
                if (method.equals("<init>")) {
                    constructors++; known &= descriptor.equals(CONSTRUCTOR) && flags == 1 && codes == 1 && valid;
                }
            }
            attributes(input); require(input.available() == 0);
            return known && constructors == 1;
        }
    }
}
