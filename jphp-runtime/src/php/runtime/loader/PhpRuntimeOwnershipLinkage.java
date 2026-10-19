package php.runtime.loader;

import php.runtime.PhpRuntimeOwnership;
import java.io.*;
import java.lang.reflect.*;

/** Generated helper linkage is checked before definition, independently of ownership admission. */
final class PhpRuntimeOwnershipLinkage {
    private record Pair(int first,int second) {}
    private record Ref(int owner,int type,boolean iface) {}
    private record Handle(int kind,int member) {}
    private static final String ROOT="php/runtime/PhpRuntimeOwnership";
    static void requireCompatible(byte[] bytes)throws IOException {
        if(bytes==null||bytes.length>64*1024*1024)throw new IOException("Invalid generated class bytes");
        var in=new DataInputStream(new ByteArrayInputStream(bytes));if(in.readInt()!=0xcafebabe)throw new IOException("Invalid classfile");in.readUnsignedShort();in.readUnsignedShort();
        Object[] pool=new Object[in.readUnsignedShort()];
        for(int i=1;i<pool.length;i++)switch(in.readUnsignedByte()){
            case 1:pool[i]=in.readUTF();break;case 3:case 4:in.skipNBytes(4);break;case 5:case 6:in.skipNBytes(8);if(++i>=pool.length)throw new IOException("Invalid wide constant");break;
            case 7:pool[i]=in.readUnsignedShort();break;case 8:case 16:case 19:case 20:in.skipNBytes(2);break;
            case 9:in.skipNBytes(4);break;
            case 10:pool[i]=new Ref(in.readUnsignedShort(),in.readUnsignedShort(),false);break;
            case 11:pool[i]=new Ref(in.readUnsignedShort(),in.readUnsignedShort(),true);break;
            case 12:pool[i]=new Pair(in.readUnsignedShort(),in.readUnsignedShort());break;case 17:case 18:in.skipNBytes(4);break;case 15:pool[i]=new Handle(in.readUnsignedByte(),in.readUnsignedShort());break;default:throw new IOException("Unknown class constant");
        }
        for(Object value:pool)if(value instanceof Handle handle&&handle.kind()>=5&&handle.kind()<=9){
            int opcode=switch(handle.kind()){case 5->182;case 6->184;case 7,8->183;default->185;};
            method(pool,handle.member(),opcode,handle.kind());
        }
        in.skipNBytes(6);in.skipNBytes(2L*in.readUnsignedShort());
        for(int i=0,n=in.readUnsignedShort();i<n;i++){in.skipNBytes(6);attributes(in,pool,false);}
        for(int i=0,n=in.readUnsignedShort();i<n;i++){in.skipNBytes(6);attributes(in,pool,true);}
        attributes(in,pool,false);if(in.read()!=-1)throw new IOException("Trailing class data");
    }
    private static void attributes(DataInputStream in,Object[] pool,boolean methods)throws IOException {
        for(int i=0,n=in.readUnsignedShort();i<n;i++){
            String name=utf(pool,in.readUnsignedShort());long size=Integer.toUnsignedLong(in.readInt());if(size>in.available())throw new IOException("Truncated class attribute");
            if(methods&&name.equals("Code"))code(in.readNBytes((int)size),pool);else in.skipNBytes(size);
        }
    }
    private static void code(byte[] bytes,Object[] pool)throws IOException {
        var in=new DataInputStream(new ByteArrayInputStream(bytes));in.readUnsignedShort();in.readUnsignedShort();int length=in.readInt();
        if(length<0||length>65535||length>in.available())throw new IOException("Invalid Code length");byte[] code=in.readNBytes(length);
        for(int pc=0;pc<code.length;){
            int opcode=code[pc]&255,size;
            if(opcode<=15||opcode>=26&&opcode<=53||opcode>=59&&opcode<=131||opcode>=133&&opcode<=152||opcode>=172&&opcode<=177||opcode==190||opcode==191||opcode==194||opcode==195)size=1;
            else if(opcode==16||opcode==18||opcode>=21&&opcode<=25||opcode>=54&&opcode<=58||opcode==169||opcode==188)size=2;
            else if(opcode==17||opcode==19||opcode==20||opcode==132||opcode>=153&&opcode<=168||opcode>=178&&opcode<=184||opcode==187||opcode==189||opcode==192||opcode==193||opcode==198||opcode==199)size=3;
            else if(opcode==185||opcode==186||opcode==200||opcode==201)size=5;else if(opcode==197)size=4;
            else if(opcode==196){if(pc+1>=code.length)throw new IOException("Truncated wide instruction");int wide=code[pc+1]&255;if(wide!=132&&!(wide>=21&&wide<=25)&&!(wide>=54&&wide<=58)&&wide!=169)throw new IOException("Invalid wide opcode");size=wide==132?6:4;}
            else if(opcode==170||opcode==171){
                int aligned=(pc+4)&~3;if(aligned+8>code.length)throw new IOException("Truncated switch");
                if(opcode==170){if(aligned+12>code.length)throw new IOException("Truncated tableswitch");long count=(long)integer(code,aligned+8)-integer(code,aligned+4)+1;if(count<0||count>65535)throw new IOException("Invalid switch range");size=aligned-pc+12+4*(int)count;}
                else{int count=integer(code,aligned+4);if(count<0||count>65535)throw new IOException("Invalid switch count");size=aligned-pc+8+8*count;}
            }else throw new IOException("Unknown JVM instruction");
            if(pc+size>code.length)throw new IOException("Truncated JVM instruction");
            if(opcode>=182&&opcode<=185)method(pool,(code[pc+1]&255)<<8|(code[pc+2]&255),opcode);pc+=size;
        }
        in.skipNBytes(8L*in.readUnsignedShort());attributes(in,pool,false);if(in.read()!=-1)throw new IOException("Trailing Code data");
    }
    private static void method(Object[] pool,int index,int opcode)throws IOException {method(pool,index,opcode,0);}
    private static void method(Object[] pool,int index,int opcode,int handleKind)throws IOException {
        if(index<=0||index>=pool.length||!(pool[index] instanceof Ref))throw new IOException("Invalid method constant");Ref ref=(Ref)pool[index];String owner=className(pool,ref.owner());
        if(!owner.equals(ROOT)&&!owner.startsWith(ROOT+"$"))return;
        if(ref.type()<=0||ref.type()>=pool.length||!(pool[ref.type()] instanceof Pair))throw new IOException("Invalid helper type");Pair type=(Pair)pool[ref.type()];String name=utf(pool,type.first()),descriptor=utf(pool,type.second());
        if(handleKind==8&&!name.equals("<init>")||handleKind!=0&&handleKind!=8&&name.equals("<init>"))throw new IOException("Ownership helper handle constructor kind differs from its target");
        try{
            Class<?> actual=Class.forName(owner.replace('/','.'),false,PhpRuntimeOwnership.class.getClassLoader());
            if(!actualOwner(actual)||!Modifier.isPublic(actual.getModifiers())||actual.isInterface()!=ref.iface())throw new NoSuchMethodException("Unknown or non-public helper owner");
            if(name.equals("<init>")){
                if(opcode!=183)throw new NoSuchMethodException("Constructor opcode");
                for(Constructor<?> constructor:actual.getConstructors())if(descriptor(constructor.getParameterTypes(),void.class).equals(descriptor))return;
            }else for(Method method:actual.getMethods())if(method.getName().equals(name)&&descriptor(method.getParameterTypes(),method.getReturnType()).equals(descriptor)&&(Modifier.isStatic(method.getModifiers())?opcode==184:opcode==(actual.isInterface()?185:182)||handleKind==7&&opcode==183))return;
            throw new NoSuchMethodException(owner+"."+name+descriptor);
        }catch(ReflectiveOperationException failure){throw new IOException("Generated ownership helper is incompatible: "+owner+"."+name+descriptor,failure);}
    }
    private static boolean actualOwner(Class<?> actual) {
        while(actual!=PhpRuntimeOwnership.class){
            Class<?> outer=actual.getEnclosingClass();if(outer==null)return false;boolean listed=false;
            for(Class<?> child:outer.getDeclaredClasses())if(child==actual){listed=true;break;}
            if(!listed)return false;actual=outer;
        }
        return true;
    }
    private static String descriptor(Class<?>[] arguments,Class<?> result){StringBuilder value=new StringBuilder("(");for(Class<?> argument:arguments)value.append(type(argument));return value.append(')').append(type(result)).toString();}
    private static String type(Class<?> value){if(value.isArray())return value.getName().replace('.','/');if(!value.isPrimitive())return "L"+value.getName().replace('.','/')+";";if(value==void.class)return "V";if(value==boolean.class)return "Z";if(value==byte.class)return "B";if(value==char.class)return "C";if(value==short.class)return "S";if(value==int.class)return "I";if(value==long.class)return "J";if(value==float.class)return "F";return "D";}
    private static String className(Object[] pool,int index)throws IOException {if(index<=0||index>=pool.length||!(pool[index] instanceof Integer))throw new IOException("Invalid class constant");return utf(pool,(Integer)pool[index]);}
    private static String utf(Object[] pool,int index)throws IOException {if(index<=0||index>=pool.length||!(pool[index] instanceof String))throw new IOException("Invalid UTF constant");return (String)pool[index];}
    private static int integer(byte[] bytes,int at){return (bytes[at]&255)<<24|(bytes[at+1]&255)<<16|(bytes[at+2]&255)<<8|(bytes[at+3]&255);}
}
