package php.runtime.loader;

import java.io.*;
import java.util.ArrayList;
import java.util.List;

/** Old LEGACY copy calls only; preserve original pool entries, code lengths and every instruction BCI. */
final class PhpJvmLegacyCopyBytecode {
    private static final String OWNER="php/runtime/Memory";
    private static final String DESCRIPTOR="()Lphp/runtime/Memory;";
    private record Pair(int first,int second) {}
    private record Ref(int owner,int type,boolean iface) {}

    static byte[] rewrite(byte[] data) {
        try {return normalize(data);}
        catch(IOException failure) {
            LinkageError error=new LinkageError("Invalid historical LEGACY copy linkage");error.initCause(failure);throw error;
        }
    }
    private static byte[] normalize(byte[] data)throws IOException {
        if(data==null || data.length>64*1024*1024)throw new IOException("Invalid generated class bytes");
        DataInputStream in=new DataInputStream(new ByteArrayInputStream(data));
        if(in.readInt()!=0xcafebabe)throw new IOException("Invalid classfile");
        in.readUnsignedShort();in.readUnsignedShort();int count=in.readUnsignedShort();
        if(count==0)throw new IOException("Invalid constant pool");
        Object[] pool=new Object[count];
        for(int i=1;i<count;i++)switch(in.readUnsignedByte()) {
            case 1:pool[i]=in.readUTF();break;
            case 3:case 4:in.skipNBytes(4);break;
            case 5:case 6:in.skipNBytes(8);if(++i>=count)throw new IOException("Invalid wide constant");break;
            case 7:pool[i]=in.readUnsignedShort();break;
            case 8:case 16:case 19:case 20:in.skipNBytes(2);break;
            case 9:case 17:case 18:in.skipNBytes(4);break;
            case 10:pool[i]=new Ref(in.readUnsignedShort(),in.readUnsignedShort(),false);break;
            case 11:pool[i]=new Ref(in.readUnsignedShort(),in.readUnsignedShort(),true);break;
            case 12:pool[i]=new Pair(in.readUnsignedShort(),in.readUnsignedShort());break;
            case 15:in.skipNBytes(3);break;
            default:throw new IOException("Unknown constant pool tag");
        }
        int poolEnd=data.length-in.available();
        List<Integer> operands=new ArrayList<>();
        in.skipNBytes(6);in.skipNBytes(2L*in.readUnsignedShort());
        for(int i=0,n=in.readUnsignedShort();i<n;i++){in.skipNBytes(6);attributes(in,pool,data.length,false,operands);}
        for(int i=0,n=in.readUnsignedShort();i<n;i++){in.skipNBytes(6);attributes(in,pool,data.length,true,operands);}
        attributes(in,pool,data.length,false,operands);
        if(in.read()!=-1)throw new IOException("Trailing classfile bytes");
        if(operands.isEmpty())return data;
        if(count>65532)throw new IOException("Historical copy linkage exhausts constant pool");
        int first=operands.get(0);int old=(data[first]&255)<<8|(data[first+1]&255);
        Ref original=(Ref)pool[old];Pair type=(Pair)pool[original.type()];
        int method=count+2;
        byte[] patched=data.clone();
        for(int operand:operands){patched[operand]=(byte)(method>>>8);patched[operand+1]=(byte)method;}
        ByteArrayOutputStream bytes=new ByteArrayOutputStream(data.length+64);DataOutputStream out=new DataOutputStream(bytes);
        out.write(data,0,8);out.writeShort(count+3);out.write(data,10,poolEnd-10);
        out.writeByte(1);out.writeUTF("toImmutableForLegacy");
        out.writeByte(12);out.writeShort(count);out.writeShort(type.second());
        out.writeByte(10);out.writeShort(original.owner());out.writeShort(count+1);
        out.write(patched,poolEnd,patched.length-poolEnd);
        return bytes.toByteArray();
    }
    private static void attributes(DataInputStream in,Object[] pool,int total,boolean methods,List<Integer> operands)throws IOException {
        for(int i=0,n=in.readUnsignedShort();i<n;i++) {
            String name=utf(pool,in.readUnsignedShort());long length=Integer.toUnsignedLong(in.readInt());
            if(length>in.available())throw new IOException("Truncated class attribute");
            if(!methods || !name.equals("Code")){in.skipNBytes(length);continue;}
            int before=in.available();in.readUnsignedShort();in.readUnsignedShort();int size=in.readInt();
            if(size<0 || size>65535 || size>in.available())throw new IOException("Invalid Code size");
            int start=total-in.available();byte[] code=in.readNBytes(size);
            instructions(code,start,pool,operands);
            in.skipNBytes(8L*in.readUnsignedShort());attributes(in,pool,total,false,operands);
            if(before-in.available()!=length)throw new IOException("Invalid Code attribute size");
        }
    }
    private static void instructions(byte[] code,int start,Object[] pool,List<Integer> operands)throws IOException {
        for(int pc=0;pc<code.length;) {
            int opcode=code[pc]&255,size;
            if(opcode<=15 || opcode>=26&&opcode<=53 || opcode>=59&&opcode<=131 || opcode>=133&&opcode<=152
                    || opcode>=172&&opcode<=177 || opcode==190 || opcode==191 || opcode==194 || opcode==195)size=1;
            else if(opcode==16 || opcode==18 || opcode>=21&&opcode<=25 || opcode>=54&&opcode<=58 || opcode==169 || opcode==188)size=2;
            else if(opcode==17 || opcode==19 || opcode==20 || opcode==132 || opcode>=153&&opcode<=168 || opcode>=178&&opcode<=184
                    || opcode==187 || opcode==189 || opcode==192 || opcode==193 || opcode==198 || opcode==199)size=3;
            else if(opcode==185 || opcode==186 || opcode==200 || opcode==201)size=5;
            else if(opcode==197)size=4;
            else if(opcode==196) {
                if(pc+1>=code.length)throw new IOException("Truncated wide opcode");int wide=code[pc+1]&255;
                if(wide!=132 && !(wide>=21&&wide<=25) && !(wide>=54&&wide<=58) && wide!=169)throw new IOException("Invalid wide opcode");
                size=wide==132?6:4;
            } else if(opcode==170 || opcode==171) {
                int aligned=(pc+4)&~3;
                if(aligned+8>code.length)throw new IOException("Truncated switch");
                if(opcode==170) {
                    if(aligned+12>code.length)throw new IOException("Truncated tableswitch");
                    long count=(long)integer(code,aligned+8)-integer(code,aligned+4)+1;
                    if(count<0 || count>65535)throw new IOException("Invalid tableswitch range");size=aligned-pc+12+4*(int)count;
                } else {
                    int count=integer(code,aligned+4);if(count<0 || count>65535)throw new IOException("Invalid lookupswitch count");
                    size=aligned-pc+8+8*count;
                }
            } else throw new IOException("Unknown JVM opcode");
            if(pc+size>code.length)throw new IOException("Truncated instruction");
            if(opcode==182 && copyCall(pool,(code[pc+1]&255)<<8|(code[pc+2]&255)))operands.add(start+pc+1);
            pc+=size;
        }
    }
    private static boolean copyCall(Object[] pool,int index)throws IOException {
        if(index<=0 || index>=pool.length || !(pool[index] instanceof Ref ref))throw new IOException("Invalid invocation member");
        if(ref.iface() || !className(pool,ref.owner()).equals(OWNER))return false;
        if(ref.type()<=0 || ref.type()>=pool.length || !(pool[ref.type()] instanceof Pair type))throw new IOException("Invalid invocation type");
        return utf(pool,type.first()).equals("toImmutable") && utf(pool,type.second()).equals(DESCRIPTOR);
    }
    private static String className(Object[] pool,int index)throws IOException {
        if(index<=0 || index>=pool.length || !(pool[index] instanceof Integer name))throw new IOException("Invalid class constant");
        return utf(pool,name);
    }
    private static String utf(Object[] pool,int index)throws IOException {
        if(index<=0 || index>=pool.length || !(pool[index] instanceof String text))throw new IOException("Invalid UTF8 constant");return text;
    }
    private static int integer(byte[] code,int at) {
        return (code[at]&255)<<24|(code[at+1]&255)<<16|(code[at+2]&255)<<8|(code[at+3]&255);
    }
}
