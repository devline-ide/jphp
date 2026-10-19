package php.runtime.reflection.helper;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;

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

    /** The caller must separately prove the exact loaded parent constructor and initializer chain. */
    public static boolean verifyPropertiesShell(byte[] bytecode, String generatedClassName, String verifiedParentClassName) {
        if (bytecode == null || generatedClassName == null || verifiedParentClassName == null)
            throw new IllegalArgumentException("Missing generated properties shell identity");
        String descriptor="(Lphp/runtime/env/Environment;Lphp/runtime/reflection/ClassEntity;)V";
        try {return new Reader(bytecode,verifiedParentClassName.replace('.', '/'),descriptor,3)
                .verify(generatedClassName.replace('.', '/'));}
        catch (IOException malformed) {throw new IllegalArgumentException("Malformed generated properties shell bytecode",malformed);}
    }

    private static final class Reader {
        private final DataInputStream input;
        private final String parentName, constructorDescriptor;
        private final int constructorSlots;
        private int[] tags, first, second;
        private String[] utf8;
        private static final String MEMORY = "Lphp/runtime/Memory;";
        private static final String CACHE = "php/runtime/invoke/cache/";
        private static final Map<String, String> FIELDS = Map.of(
                "$FN", "Ljava/lang/String;", "$TRC", "[Lphp/runtime/env/TraceInfo;",
                "$MEM", "[Lphp/runtime/Memory;", "$AMEM", "[[Lphp/runtime/Memory;",
                "$CALL_FUNC_CACHE", "L"+CACHE+"FunctionCallCache;", "$CALL_METH_CACHE", "L"+CACHE+"MethodCallCache;",
                "$CALL_CONST_CACHE", "L"+CACHE+"ConstantCallCache;", "$CALL_PROP_CACHE", "L"+CACHE+"PropertyCallCache;",
                "$CALL_CLASS_CACHE", "L"+CACHE+"ClassCallCache;");
        private static final Set<String> CACHES = Set.of(CACHE+"FunctionCallCache", CACHE+"MethodCallCache",
                CACHE+"ConstantCallCache", CACHE+"PropertyCallCache", CACHE+"ClassCallCache");
        Reader(byte[] bytes) {this(bytes,PARENT,CONSTRUCTOR,6);}
        Reader(byte[] bytes,String parentName,String constructorDescriptor,int constructorSlots) {
            input=stream(bytes);this.parentName=parentName;this.constructorDescriptor=constructorDescriptor;
            this.constructorSlots=constructorSlots;
        }
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
            if (stack != constructorSlots || locals != constructorSlots || handlers != 0) return false;
            var instructions = stream(code);
            for (int slot = 0; slot < constructorSlots; slot++) {
                int opcode = instructions.readUnsignedByte();
                if (slot <= 3 && opcode == 42 + slot) continue;
                if (opcode != 25 || instructions.readUnsignedByte() != slot) return false;
            }
            if (instructions.readUnsignedByte() != 183) return false;
            int call = instructions.readUnsignedShort(); index(call, 10);
            if (!parentName.equals(className(first[call]))) return false;
            int nameType = second[call];
            if (!"<init>".equals(text(first[nameType])) || !constructorDescriptor.equals(text(second[nameType]))) return false;
            return instructions.readUnsignedByte() == 177 && instructions.available() == 0;
        }
        /** Closed literal/cache initializer language: no PHP calls, branches, locals, or escaping stores. */
        private boolean initializer(byte[] attribute, String owner) throws IOException {
            var in = stream(attribute); in.readUnsignedShort(); int locals = in.readUnsignedShort();
            byte[] code = bytes(in); int handlers = in.readUnsignedShort();
            for (int i=0;i<handlers;i++) { in.readUnsignedShort(); in.readUnsignedShort(); in.readUnsignedShort();
                int type=in.readUnsignedShort(); if(type!=0) index(type,7); }
            attributes(in); require(in.available()==0);
            if (locals!=0 || handlers!=0) return false;
            var instructions=stream(code); Set<String> written=new HashSet<>();
            while (instructions.available()>0) {
                int opcode=instructions.readUnsignedByte();
                if (opcode>=1 && opcode<=15 || opcode==83 || opcode==87 || opcode==89) continue;
                if (opcode==16) { instructions.readByte(); continue; }
                if (opcode==17) { instructions.readShort(); continue; }
                if (opcode==18 || opcode==19 || opcode==20) {
                    int literal=opcode==18 ? instructions.readUnsignedByte() : instructions.readUnsignedShort();
                    index(literal,3,4,5,6,8); continue;
                }
                if (opcode==187 || opcode==189) {
                    String type=className(instructions.readUnsignedShort());
                    if (opcode==187 ? !CACHES.contains(type) && !type.equals("php/runtime/memory/ReferenceMemory")
                            : !Set.of("php/runtime/env/TraceInfo","php/runtime/Memory","[Lphp/runtime/Memory;").contains(type)) return false;
                    continue;
                }
                if (opcode==178 || opcode==179) {
                    int field=instructions.readUnsignedShort(); index(field,9);
                    String target=className(first[field]), name=text(first[second[field]]), type=text(second[second[field]]);
                    if (opcode==179) {
                        if (!target.equals(owner) || name.equals("$FN") || !type.equals(FIELDS.get(name)) || !written.add(name)) return false;
                    } else if (!(target.equals(owner) && name.equals("$FN") && type.equals(FIELDS.get(name)))
                            && !(target.equals("php/runtime/Memory") && Set.of("NULL","UNDEFINED","TRUE","FALSE").contains(name) && type.equals(MEMORY))) return false;
                    continue;
                }
                if (opcode==182 || opcode==183 || opcode==184) {
                    int call=instructions.readUnsignedShort(); index(call,10);
                    String target=className(first[call]), name=text(first[second[call]]), descriptor=text(second[second[call]]);
                    if (!pureCall(opcode,target,name,descriptor)) return false;
                    continue;
                }
                if (opcode==177) return instructions.available()==0 && written.size()==FIELDS.size()-1;
                return false;
            }
            return false;
        }
        private boolean pureCall(int opcode, String owner, String name, String descriptor) {
            if (opcode==183) return name.equals("<init>") && descriptor.equals("()V")
                    && (CACHES.contains(owner) || owner.equals("php/runtime/memory/ReferenceMemory"));
            if (opcode==182) return owner.equals("php/runtime/Memory") && name.equals("toImmutable") && descriptor.equals("()"+MEMORY)
                    || owner.equals("php/runtime/memory/ArrayMemory") && (name.equals("add") && descriptor.equals("("+MEMORY+")Lphp/runtime/memory/ReferenceMemory;")
                        || name.equals("put") && descriptor.equals("(Ljava/lang/Object;"+MEMORY+")Lphp/runtime/memory/ReferenceMemory;"));
            if (opcode!=184) return false;
            if (owner.equals("php/runtime/env/TraceInfo")) return name.equals("valueOf")
                    && descriptor.equals("(Ljava/lang/String;JJLjava/lang/String;)Lphp/runtime/env/TraceInfo;");
            if (owner.equals("php/runtime/memory/ArrayMemory")) return (name.equals("createListed") || name.equals("createHashed"))
                    && descriptor.equals("(I)Lphp/runtime/memory/ArrayMemory;");
            if (!name.equals("valueOf")) return false;
            if (owner.equals("php/runtime/memory/LongMemory")) return descriptor.equals("(J)"+MEMORY) || descriptor.equals("(I)"+MEMORY);
            if (owner.equals("php/runtime/memory/DoubleMemory")) return descriptor.equals("(D)"+MEMORY) || descriptor.equals("(F)"+MEMORY);
            if (owner.equals("php/runtime/memory/StringMemory")) return descriptor.equals("(Ljava/lang/String;)"+MEMORY);
            if (owner.equals("php/runtime/memory/TrueMemory")) return descriptor.equals("(Z)"+MEMORY);
            return owner.equals("php/runtime/memory/KeyValueMemory") && (descriptor.equals("("+MEMORY+MEMORY+")"+MEMORY)
                    || descriptor.equals("("+MEMORY+MEMORY+"Ljava/lang/String;)"+MEMORY) || descriptor.equals("("+MEMORY+MEMORY+MEMORY+")"+MEMORY));
        }
        boolean verify(String expectedName) throws IOException {
            require(input.readInt() == 0xCAFEBABE);
            input.readUnsignedShort(); int version = input.readUnsignedShort(); require(version >= 45);
            constants(); int access = input.readUnsignedShort();
            String name = className(input.readUnsignedShort()), parent = className(input.readUnsignedShort());
            boolean known = name.equals(expectedName) && parentName.equals(parent) && (access & (0x0200 | 0x0400 | 0x2000 | 0x4000)) == 0;
            int interfaces = input.readUnsignedShort(); known &= interfaces == 0;
            for (int i = 0; i < interfaces; i++) index(input.readUnsignedShort(), 7);
            int fields = input.readUnsignedShort(); Set<String> fieldNames=new HashSet<>();
            boolean propertiesShell=constructorSlots==3;
            for (int i = 0; i < fields; i++) {
                int flags=input.readUnsignedShort(); String field=text(input.readUnsignedShort()), type=text(input.readUnsignedShort());
                boolean className=propertiesShell && field.equals("$CL");
                // A lexical attribute scope is a ConstantValue literal, never a runtime publisher.
                boolean lexicalScope=!propertiesShell && (field.equals("__$attributeLexicalScope") || field.equals("__$noDiscardClosureName"));
                boolean literal=field.equals("$FN") || className || lexicalScope;
                known &= type.equals(className || lexicalScope ? "Ljava/lang/String;" : FIELDS.get(field))
                        && fieldNames.add(field) && flags==(literal ? 25 : 9);
                int count=input.readUnsignedShort(); known &= className || lexicalScope ? count==1 : literal ? count<=1 : count==0;
                for(int a=0;a<count;a++) {
                    String attr=text(input.readUnsignedShort()); byte[] data=bytes(input);
                    if(literal && attr.equals("ConstantValue") && data.length==2) index(stream(data).readUnsignedShort(),8);
                    else known=false;
                }
            }
            known &= fields==FIELDS.size()+(propertiesShell ? 1 : 0)
                    +(!propertiesShell && fieldNames.contains("__$attributeLexicalScope") ? 1 : 0)
                    +(!propertiesShell && fieldNames.contains("__$noDiscardClosureName") ? 1 : 0);
            int methods = input.readUnsignedShort(), constructors = 0, initializers = 0;
            for (int i = 0; i < methods; i++) {
                int flags = input.readUnsignedShort(); String method = text(input.readUnsignedShort()), descriptor = text(input.readUnsignedShort());
                int attributes = input.readUnsignedShort(); boolean valid = false; int codes = 0;
                for (int a = 0; a < attributes; a++) {
                    String attributeName = text(input.readUnsignedShort()); byte[] attribute = bytes(input);
                    if (method.equals("<init>") && attributeName.equals("Code")) { codes++; valid = constructor(attribute); }
                    if (method.equals("<clinit>") && attributeName.equals("Code")) { codes++; valid = initializer(attribute,name); }
                }
                if (method.equals("<init>")) {
                    constructors++; known &= descriptor.equals(constructorDescriptor) && flags == 1 && codes == 1 && valid;
                }
                if (method.equals("<clinit>")) {
                    initializers++; known &= descriptor.equals("()V") && flags==8 && codes==1 && valid;
                }
            }
            attributes(input); require(input.available() == 0);
            return known && constructors == 1 && initializers == 1;
        }
    }
}
