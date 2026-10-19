package php.runtime.loader;

import java.lang.classfile.*;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.constant.ClassDesc;
import java.lang.constant.MethodTypeDesc;
import java.io.*;
import java.util.*;

/** Bind an unresolved PHP superclass on an already compiled class before JVM definition. */
final class PhpJvmInheritanceBytecode {
    private static final ClassFile FILE=ClassFile.of(ClassFile.AttributesProcessingOption.PASS_ALL_ATTRIBUTES);
    private static final String BASE="php/runtime/lang/BaseObject";
    private static final String CONSTRUCTOR="(Lphp/runtime/env/Environment;Lphp/runtime/reflection/ClassEntity;)V";
    static byte[] bind(byte[] source,String parent) {return bind(source,parent,null);}
    static byte[] bind(byte[] source,String parent,String definitionName) {
        byte[] linked=parent==null ? source : bindParent(source,parent);
        if (definitionName==null) return linked;
        String original=FILE.parse(source).thisClass().asInternalName();
        String target=definitionName.replace('.', '/');
        ClassDesc.ofInternalName(target);
        if (original.equals(target)) return linked;
        return new SelfNames(linked,original,target).rewrite();
    }
    /** Called only for an attested bound PHP INTERFACE carrier, never for ordinary CLASS inheritance. */
    static byte[] bindInterfaceCarrier(byte[] source,String parent,String definitionName) {
        ClassModel model=FILE.parse(source);
        if (!"java/lang/Object".equals(parent) || model.superclass().isEmpty()
                || !model.superclass().get().asInternalName().equals("java/lang/Object")
                || model.flags().flagsMask()!=(ClassFile.ACC_PUBLIC|ClassFile.ACC_SUPER)
                || model.methods().stream().anyMatch(method->method.methodName().equalsString("<init>")))
            throw new LinkageError("Deferred PHP interface requires its exact original Object carrier");
        // PHP interface inheritance is reflection metadata; its JVM carrier parent remains Object.
        return bind(source,null,definitionName);
    }
    private static byte[] bindParent(byte[] source,String parent) {
        ClassModel model=FILE.parse(source);
        if (model.superclass().isEmpty() || !model.superclass().get().asInternalName().equals(BASE))
            throw new LinkageError("Deferred PHP inheritance requires its original BaseObject template");
        ClassDesc target=ClassDesc.ofInternalName(parent);
        int[] replacements={0};
        ClassTransform transform=ClassTransform.transformingMethodBodies(method->method.methodName().equalsString("<init>"),
                (code,element)->{
                    if (element instanceof InvokeInstruction call && call.opcode()==Opcode.INVOKESPECIAL
                            && call.owner().asInternalName().equals(BASE) && call.name().equalsString("<init>")) {
                        if (!call.type().equalsString(CONSTRUCTOR)) throw new LinkageError("Unknown deferred superclass constructor");
                        replacements[0]++;
                        code.invokespecial(target,"<init>",MethodTypeDesc.ofDescriptor(CONSTRUCTOR));
                    } else code.with(element);
                }).andThen(ClassTransform.endHandler(builder->builder.withSuperclass(target)));
        byte[] result=FILE.transformClass(model,transform);
        if (replacements[0]!=1) throw new LinkageError("Deferred PHP inheritance needs exactly one superclass constructor call");
        return result;
    }
    /** Remap semantic name/type indices, never a shared UTF8 or PHP String constant in place. */
    private static final class SelfNames {
        private final byte[] input;
        private final String original,target;
        private final List<byte[]> pool=new ArrayList<>();
        private final Map<Integer,Integer> names=new HashMap<>(),types=new HashMap<>();
        private final Map<String,Integer> additions=new HashMap<>();
        private int bodyOffset;
        SelfNames(byte[] input,String original,String target) {
            this.input=input;this.original=original;this.target=target;
        }
        byte[] rewrite() {
            Cursor source=new Cursor(input,8,input.length);
            int count=source.u2();pool.add(null);
            for(int i=1;i<count;i++) {
                int start=source.at,tag=source.u1();
                int size=switch(tag) {
                    case 1 -> source.u2();
                    case 3,4,9,10,11,12,17,18 -> 4;
                    case 5,6 -> 8;
                    case 7,8,16,19,20 -> 2;
                    case 15 -> 3;
                    default -> throw bad("Unknown constant pool tag");
                };
                source.skip(size);pool.add(Arrays.copyOfRange(input,start,source.at));
                if(tag==5 || tag==6) {if(++i>=count)throw bad("Invalid double-slot constant");pool.add(null);}
            }
            bodyOffset=source.at;
            for(int i=1;i<count;i++) {
                byte[] entry=pool.get(i);if(entry==null)continue;
                switch(entry[0]&255) {
                    case 7 -> set(entry,1,rename(u2(entry,1),true));
                    case 12 -> set(entry,3,rename(u2(entry,3),false));
                    case 16 -> set(entry,1,rename(u2(entry,1),false));
                    default -> {}
                }
            }
            byte[] body=Arrays.copyOfRange(input,bodyOffset,input.length);
            Cursor data=new Cursor(body,0,body.length);
            data.skip(6);data.skip(2*data.u2());
            members(data);members(data);attributes(data);
            if(data.at!=data.end)throw bad("Trailing class data");
            try {
                var bytes=new ByteArrayOutputStream();var output=new DataOutputStream(bytes);
                output.write(input,0,8);output.writeShort(pool.size());
                for(byte[] entry:pool)if(entry!=null)output.write(entry);
                output.write(body);byte[] result=bytes.toByteArray();
                if(!FILE.parse(result).thisClass().asInternalName().equals(target))throw bad("Wrong renamed class identity");
                return result;
            }catch(IOException impossible){throw new UncheckedIOException(impossible);}
        }
        private int rename(int index,boolean className) {
            Map<Integer,Integer> cache=className ? names : types;
            Integer known=cache.get(index);if(known!=null)return known;
            String before=utf(index),after=className && before.equals(original) ? target : descriptor(before);
            int result=index;
            if(!before.equals(after)) {
                Integer added=additions.get(after);
                if(added!=null)result=added;
                else {
                    if(pool.size()>=65535)throw bad("Renamed constant pool is full");
                    try {
                        var bytes=new ByteArrayOutputStream();var out=new DataOutputStream(bytes);
                        out.writeByte(1);out.writeUTF(after);result=pool.size();pool.add(bytes.toByteArray());additions.put(after,result);
                    }catch(IOException failure){throw new UncheckedIOException(failure);}
                }
            }
            cache.put(index,result);return result;
        }
        private String descriptor(String text) {
            // Both descriptors and generic signatures use L + binary-name + ;/< boundary.
            // PHP source/name strings never enter this method.
            String token="L"+original;StringBuilder out=null;int from=0,at=0;
            while((at=text.indexOf(token,at))>=0) {
                int end=at+token.length();char next=end<text.length() ? text.charAt(end) : 0;
                if(next==';' || next=='<') {
                    if(out==null)out=new StringBuilder(text.length()+target.length());
                    out.append(text,from,at).append('L').append(target);from=end;
                }
                at=end;
            }
            return out==null ? text : out.append(text,from,text.length()).toString();
        }
        private String utf(int index) {
            if(index<=0 || index>=pool.size())throw bad("Invalid UTF8 index");
            byte[] entry=pool.get(index);
            if(entry==null || entry[0]!=1)throw bad("Expected UTF8 entry");
            try{return new DataInputStream(new ByteArrayInputStream(entry,1,entry.length-1)).readUTF();}
            catch(IOException failure){throw new UncheckedIOException(failure);}
        }
        private void type(Cursor data) {int position=data.at;int index=data.u2();set(data.bytes,position,rename(index,false));}
        private void members(Cursor data) {
            int count=data.u2();for(int i=0;i<count;i++){data.skip(4);type(data);attributes(data);}
        }
        private void attributes(Cursor data) {
            int count=data.u2();
            for(int i=0;i<count;i++) {
                String name=utf(data.u2());int length=data.length();int end=data.at+length;
                Cursor value=new Cursor(data.bytes,data.at,end);
                switch(name) {
                    case "Signature" -> type(value);
                    case "Code" -> {
                        value.skip(4);value.skip(value.length());value.skip(8*value.u2());attributes(value);
                    }
                    case "LocalVariableTable","LocalVariableTypeTable" -> {
                        int n=value.u2();for(int k=0;k<n;k++){value.skip(6);type(value);value.skip(2);}
                    }
                    case "RuntimeVisibleAnnotations","RuntimeInvisibleAnnotations" -> annotations(value);
                    case "RuntimeVisibleParameterAnnotations","RuntimeInvisibleParameterAnnotations" -> {
                        int n=value.u1();for(int k=0;k<n;k++)annotations(value);
                    }
                    case "RuntimeVisibleTypeAnnotations","RuntimeInvisibleTypeAnnotations" -> {
                        int n=value.u2();for(int k=0;k<n;k++){annotationTarget(value);annotation(value);}
                    }
                    case "AnnotationDefault" -> element(value);
                    case "Record" -> {
                        int n=value.u2();for(int k=0;k<n;k++){value.skip(2);type(value);attributes(value);}
                    }
                    default -> value.skip(length);
                }
                if(value.at!=end)throw bad("Invalid "+name+" attribute length");
                data.at=end;
            }
        }
        private void annotations(Cursor data) {int n=data.u2();for(int i=0;i<n;i++)annotation(data);}
        private void annotation(Cursor data) {
            type(data);int count=data.u2();for(int i=0;i<count;i++){data.skip(2);element(data);}
        }
        private void element(Cursor data) {
            switch(data.u1()) {
                case 'B','C','D','F','I','J','S','Z','s' -> data.skip(2);
                case 'e' -> {type(data);data.skip(2);}
                case 'c' -> type(data);
                case '@' -> annotation(data);
                case '[' -> {int n=data.u2();for(int i=0;i<n;i++)element(data);}
                default -> throw bad("Invalid annotation element tag");
            }
        }
        private void annotationTarget(Cursor data) {
            switch(data.u1()) {
                case 0x00,0x01,0x16 -> data.skip(1);
                case 0x10,0x11,0x12,0x17,0x42,0x43,0x44,0x45,0x46 -> data.skip(2);
                case 0x13,0x14,0x15 -> {}
                case 0x40,0x41 -> data.skip(6*data.u2());
                case 0x47,0x48,0x49,0x4a,0x4b -> data.skip(3);
                default -> throw bad("Invalid annotation target tag");
            }
            data.skip(2*data.u1());
        }
        private static int u2(byte[] bytes,int at){return (bytes[at]&255)<<8 | bytes[at+1]&255;}
        private static void set(byte[] bytes,int at,int value){bytes[at]=(byte)(value>>>8);bytes[at+1]=(byte)value;}
        private static LinkageError bad(String message){return new LinkageError(message);}
        private static final class Cursor {
            final byte[] bytes;final int end;int at;
            Cursor(byte[] bytes,int at,int end){this.bytes=bytes;this.at=at;this.end=end;if(at<0 || end<at || end>bytes.length)throw bad("Invalid class interval");}
            void skip(int count){if(count<0 || count>end-at)throw bad("Truncated class data");at+=count;}
            int u1(){if(at==end)throw bad("Truncated class data");return bytes[at++]&255;}
            int u2(){int a=u1();return a<<8 | u1();}
            int length(){long n=((long)u2()<<16)|u2();if(n>end-at)throw bad("Truncated attribute or code");return (int)n;}
        }
    }

}
