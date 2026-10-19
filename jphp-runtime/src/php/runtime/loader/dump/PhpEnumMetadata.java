package php.runtime.loader.dump;

import java.io.IOException;
import java.lang.classfile.*;
import java.lang.classfile.instruction.*;
import java.util.*;
import php.runtime.Memory;
import php.runtime.reflection.*;
import php.runtime.loader.dump.io.DumpException;

/** Cold declaration validation, before a parent/interface/autoload provider or case factory can run. */
public final class PhpEnumMetadata {
    private PhpEnumMetadata(){}
    private static final String PHP_METHOD="(Lphp/runtime/env/Environment;[Lphp/runtime/Memory;)Lphp/runtime/Memory;";
    private static final Set<String> PROHIBITED=Set.of("__construct","__destruct","__clone","__get","__set","__unset","__isset","__tostring","__sleep","__wakeup","__serialize","__unserialize","__debuginfo","__set_state");
    public static void validate(ClassEntity clazz,List<ConstantEntity> constants,List<PropertyEntity> properties,List<MethodEntity> methods) throws IOException {
        if(!clazz.isEnum()) {
            for(ConstantEntity constant:constants)if(constant.isEnumCase())throw new DumpException("Ordinary declaration contains enum case metadata");
            return;
        }
        if(clazz.getModule()==null || clazz.getModule().getPhpLanguageProfile()!=php.runtime.common.PhpLanguageProfile.PHP_8_5
                || !clazz.isFinal() || clazz.isAbstract() || clazz.isPhpReadonly() || clazz.isAnonymous()
                || clazz.getParent()!=null || clazz.getDeferredParentName()!=null || clazz.getDeclarationRelations().parent()!=null)
            throw new DumpException("Invalid enum declaration shape");
        if(clazz.getData()==null || clazz.getData().length==0)throw new DumpException("Enum declaration lacks its physical carrier");
        Set<String> names=new HashSet<>();
        for(ConstantEntity constant:constants) {
            if(constant.getName()==null || constant.getName().isEmpty() || !names.add(constant.getName()))throw new DumpException("Duplicate enum declaration constant/case");
            if(constant.isEnumCase() && (!constant.isPublic() || !constant.caseSensitise || constant.isFinal() || constant.getDeclaredType()!=null || constant.getTraitName()!=null))
                throw new DumpException("Invalid enum case flags");
        }
        Set<String> propertyNames=new HashSet<>();
        for(PropertyEntity property:properties) {
            property.setClazz(clazz);
            if(!php.runtime.PhpEnums.syntheticProperty(clazz,property) || !propertyNames.add(property.getName()))throw new DumpException("Invalid enum synthetic property shape");
        }
        if(propertyNames.size()!=(clazz.getEnumBackingType()==null?1:2) || !propertyNames.contains("name") || clazz.getEnumBackingType()!=null && !propertyNames.contains("value"))
            throw new DumpException("Enum lacks its exact synthetic readonly property metadata");
        ClassModel physical;
        try{physical=ClassFile.of().parse(clazz.getData());}catch(IllegalArgumentException invalid){throw new DumpException("Invalid enum physical bytecode");}
        if(!physical.thisClass().asInternalName().equals(clazz.getCompiledInternalName().replace('.','/'))
                || physical.superclass().isEmpty() || !physical.superclass().get().asInternalName().equals("php/runtime/lang/BaseEnum"))
            throw new DumpException("Enum has a foreign physical case carrier");
        MethodModel witness=find(physical,"__$enum$declaration","()Ljava/lang/String;",true);
        List<Instruction> witnessCode=witness.code().orElseThrow().elementList().stream().filter(Instruction.class::isInstance).map(Instruction.class::cast).toList();
        if(witnessCode.size()!=2 || !(witnessCode.getFirst() instanceof ConstantInstruction signature)
                || !declarationWitness(clazz,constants).equals(signature.constantValue()) || witnessCode.getLast().opcode()!=Opcode.ARETURN)
            throw new DumpException("Enum declaration roles/initializers differ from its physical carrier");
        Set<String> generated=new HashSet<>();
        for(MethodEntity method:methods) {
            method.setClazz(clazz);
            String name=method.getLowerName();
            if(PROHIBITED.contains(name))throw new DumpException("Enum contains prohibited magic member");
            if(name.equals("cases") || clazz.getEnumBackingType()!=null && (name.equals("from")||name.equals("tryfrom"))) {
                if(!php.runtime.PhpEnums.generatedMethod(method) || !method.isStatic() || !method.isPublic() || method.isAbstract()
                        || !generated.add(name))throw new DumpException("Invalid enum generated method declaration");
                int count=name.equals("cases")?0:1;
                if(method.getParameters()==null || method.getParameters().length!=count || count==1 && (!method.getParameters()[0].getName().equals("value") || method.getParameters()[0].isReference() || method.getParameters()[0].isVariadic()))
                    throw new DumpException("Invalid enum generated argument declaration");
                String returnType=name.equals("cases")?"array":name.equals("tryfrom")?"?static":"static";
                if(method.getReturnTypeChecker()==null || !returnType.equals(method.getReturnTypeChecker().getSignature())
                        || count==1 && (method.getParameters()[0].getTypeChecker()==null || !"string|int".equals(method.getParameters()[0].getTypeChecker().getSignature())
                            || method.getParameters()[0].getDefaultValue()!=null))
                    throw new DumpException("Invalid enum generated type/default declaration");
                MethodModel implementation=find(physical,method.getInternalName(),PHP_METHOD,true);
                if(implementation.code().isEmpty())throw new DumpException("Enum generated method lacks code");
                List<Instruction> instructions=implementation.code().get().elementList().stream().filter(Instruction.class::isInstance).map(Instruction.class::cast).toList();
                int expected=name.equals("cases")?4:6;
                if(instructions.size()!=expected || !(instructions.get(0) instanceof LoadInstruction load) || load.slot()!=0
                        || !(instructions.get(1) instanceof ConstantInstruction owner) || !owner.constantValue().equals(clazz.getName())
                        || instructions.get(expected-1).opcode()!=Opcode.ARETURN)
                    throw new DumpException("Enum generated body differs from its declaration");
                if(count==1 && (!(instructions.get(2) instanceof LoadInstruction argument) || argument.slot()!=1
                        || !(instructions.get(3) instanceof ConstantInstruction nullable) || !nullable.constantValue().equals(name.equals("tryfrom")?Integer.valueOf(1):Integer.valueOf(0))))
                    throw new DumpException("Enum generated lookup route differs from its declaration");
                if(!(instructions.get(expected-2) instanceof InvokeInstruction call) || call.opcode()!=Opcode.INVOKESTATIC
                        || !call.owner().asInternalName().equals("php/runtime/PhpEnums") || !call.name().equalsString(name.equals("cases")?"cases":"from")
                        || !call.type().equalsString(name.equals("cases")?"(Lphp/runtime/env/Environment;Ljava/lang/String;)Lphp/runtime/Memory;":"(Lphp/runtime/env/Environment;Ljava/lang/String;[Lphp/runtime/Memory;Z)Lphp/runtime/Memory;"))
                    throw new DumpException("Enum generated lookup helper is foreign");
            }
        }
        if(!generated.contains("cases") || clazz.getEnumBackingType()!=null && (!generated.contains("from") || !generated.contains("tryfrom")))throw new DumpException("Enum lacks generated method metadata");
        for(ConstantEntity constant:constants)if(constant.isEnumCase() && constant.getInitializerMethod()!=null) {
            if(!clazz.getName().equals(constant.getInitializerClass()))throw new DumpException("Enum backing initializer has a foreign declaration owner");
            find(physical,constant.getInitializerMethod(),"(Lphp/runtime/env/Environment;)Lphp/runtime/Memory;",true);
        }
    }
    public static String declarationWitness(ClassEntity clazz,List<ConstantEntity> constants) {
        try {
            java.io.ByteArrayOutputStream bytes=new java.io.ByteArrayOutputStream();java.io.DataOutputStream data=new java.io.DataOutputStream(bytes);
            data.writeUTF(clazz.getName());data.writeUTF(Objects.toString(clazz.getEnumBackingType(),""));
            for(ConstantEntity constant:constants)if(constant.isEnumCase()) {
                data.writeUTF(constant.getName());
                data.writeUTF(Objects.toString(constant.getInitializerClass(),""));data.writeUTF(Objects.toString(constant.getInitializerMethod(),""));
            }
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
        }catch(java.io.IOException|java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
    private static MethodModel find(ClassModel physical,String name,String descriptor,boolean isStatic) throws IOException {
        MethodModel found=null;
        for(MethodModel method:physical.methods())if(method.methodName().equalsString(name) && method.methodType().equalsString(descriptor)) {
            if(found!=null || (method.flags().flagsMask() & ClassFile.ACC_STATIC)!= (isStatic?ClassFile.ACC_STATIC:0) || method.code().isEmpty())throw new DumpException("Invalid enum physical member shape");
            found=method;
        }
        if(found==null)throw new DumpException("Enum metadata lacks its physical initializer/member");
        return found;
    }
}
