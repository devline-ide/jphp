package php.runtime.loader;

import php.runtime.Memory;
import php.runtime.env.Environment;
import php.runtime.reflection.ClassEntity;
import php.runtime.reflection.MethodEntity;
import php.runtime.reflection.PhpTraitCompositionPlan;
import php.runtime.loader.dump.PhpTraitPropertyHookOrigin;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.lang.classfile.*;
import java.lang.classfile.constantpool.Utf8Entry;
import java.lang.classfile.instruction.LocalVariable;
import java.lang.classfile.instruction.ConstantInstruction;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.classfile.instruction.LoadInstruction;
import java.lang.classfile.instruction.StoreInstruction;
import java.lang.classfile.instruction.FieldInstruction;
import java.lang.constant.ConstantDesc;
import java.util.HashMap;
import java.lang.constant.ClassDesc;
import java.lang.constant.MethodTypeDesc;
import java.lang.reflect.Method;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/** Physical method composition only; PHP relation checks precede this in the declaration resolver. */
final class PhpJvmTraitMethodCopy {
    private static final String DESC="(Lphp/runtime/env/Environment;[Lphp/runtime/Memory;)Lphp/runtime/Memory;";
    private static final MethodTypeDesc TYPE=MethodTypeDesc.ofDescriptor(DESC);
    private static final String FRAME="JPHP_Ownership_Frame_v1", RUNTIME="JPHP_Runtime_Frame_v1", STATIC_SITES="JPHP_Trait_Static_Sites_v1", CELL="JPHP_Primitive_Cell_Plan_v1";
    private static final String HOOK_ORIGIN=PhpTraitPropertyHookOrigin.ATTRIBUTE;
    private static final ClassFile API=ClassFile.of(ClassFile.AttributeMapperOption.of(name ->
            name.stringValue().equals(FRAME) ? new ProtocolMapper(FRAME)
                    : name.stringValue().equals(RUNTIME) ? new ProtocolMapper(RUNTIME)
                    : name.stringValue().equals(STATIC_SITES) ? new ProtocolMapper(STATIC_SITES) : name.stringValue().equals(CELL) ? new ProtocolMapper(CELL)
                    : name.stringValue().equals(HOOK_ORIGIN) ? new HookOriginMapper() : null));

    private record Copy(Class<?> actual, String donorTemplate, byte[] bytes, String source,
                        String target, String targetOwner, List<Class<?>> traceOrigins, int flags, String hash,
                        php.runtime.reflection.support.PhpPropertyHooks.Hook hook,
                        PhpTraitPropertyHookOrigin.RuntimeSource hookSource, String hookFingerprint) { }
    private record Bridge(Class<?> actual, Method contract, String name, String target) { }
    private record Selection(Class<?> actual, String source, String target, String targetOwner, int flags, String hash, String hookFingerprint) { }
    private record BridgeKey(Class<?> actual, Method contract, String name, String target) { }
    record Key(List<Class<?>> donors,List<Class<?>> interfaces,List<Selection> methods,List<BridgeKey> bridges) { }

    static final class Plan {
        private final List<Copy> copies;
        private final List<Class<?>> interfaces;
        private final List<Bridge> bridges;
        private final Key key;
        private Plan(List<Class<?>> donors,List<Copy> copies,List<Class<?>> interfaces,List<Bridge> bridges) {
            this.copies=List.copyOf(copies);this.interfaces=List.copyOf(interfaces);this.bridges=List.copyOf(bridges);
            key=new Key(List.copyOf(donors),this.interfaces,
                    this.copies.stream().map(copy -> new Selection(copy.actual(),copy.source(),copy.target(),copy.targetOwner(),copy.flags(),copy.hash(),copy.hookFingerprint())).toList(),
                    this.bridges.stream().map(bridge -> new BridgeKey(bridge.actual(),bridge.contract(),bridge.name(),bridge.target())).toList());
        }
        Key key(){return key;}
        boolean hasRuntimeHooks(){return copies.stream().anyMatch(copy->copy.hookSource()!=null);}
        void validateDefined(Class<?> actual) {
            for(Class<?> contract:interfaces)if(!contract.isAssignableFrom(actual))
                throw new LinkageError("Defined composition lost an actual native interface");
            try {
                for(Copy copy:copies) {
                    Method member=actual.getDeclaredMethod(copy.target(),Environment.class,Memory[].class);
                    if(member.getDeclaringClass()!=actual || member.getReturnType()!=Memory.class || member.getModifiers()!=copy.flags())
                        throw new LinkageError("Defined trait member differs from its exact selected body");
                }
                for(Bridge bridge:bridges) {
                    Method member=actual.getDeclaredMethod(bridge.name(),Environment.class,Memory[].class);
                    if(member.getDeclaringClass()!=actual || member.getReturnType()!=Memory.class || member.getModifiers()!=ClassFile.ACC_PUBLIC)
                        throw new LinkageError("Defined native interface bridge differs from its dispatch plan");
                }
            }catch(NoSuchMethodException missing){throw new LinkageError("Defined composition lacks its planned method",missing);}
        }
        String fingerprint(){
            StringBuilder text=new StringBuilder();
            key.donors().forEach(type -> text.append("D:").append(type.getName()).append('\0'));
            key.interfaces().forEach(type -> text.append("I:").append(type.getName()).append('\0'));
            key.methods().forEach(copy -> {
                text.append("M:").append(copy.actual().getName()).append('\0').append(copy.source())
                    .append('\0').append(copy.target()).append('\0').append(copy.targetOwner()).append('\0').append(copy.flags()).append('\0').append(copy.hash()).append('\0');
                if(copy.hookFingerprint()!=null)text.append("H:").append(copy.hookFingerprint()).append('\0');
            });
            key.bridges().forEach(bridge -> text.append("B:").append(bridge.actual().getName()).append('\0').append(bridge.contract().getDeclaringClass().getName()).append('.').append(bridge.contract().getName()).append(DESC)
                    .append('\0').append(bridge.name()).append('\0').append(bridge.target()).append('\0'));
            // Preserve every JVM UTF-16 name code unit, including unmatched surrogates.
            byte[] codeUnits=new byte[text.length()*2];
            for(int index=0;index<text.length();index++){char unit=text.charAt(index);codeUnits[index*2]=(byte)(unit>>>8);codeUnits[index*2+1]=(byte)unit;}
            return hash(codeUnits);
        }
    }

    static Plan capture(RuntimeClassLoader loader,ClassEntity owner,PhpTraitCompositionPlan recipe) {
        Objects.requireNonNull(recipe);recipe.validate();
        if(recipe.owner()!=owner || owner.isDeclarationTemplate()
                || !owner.isDeferredDeclaration() && !(owner.getClassComposition()==recipe
                    && php.runtime.reflection.PhpClassDeclaration.isBoundAnonymousDeclaration(owner)))
            throw new LinkageError("Composition requires its exact bound deferred PHP class");
        var donors=new ArrayList<Class<?>>();
        for(ClassEntity trait:recipe.traits()) {
            if(trait.getType()!=ClassEntity.Type.TRAIT)throw new LinkageError("Composition donor is not a trait");
            Class<?> actual=visible(loader,trait.getNativeClass());
            loader.traitDefinition(trait); // prove full original/defined-byte witness, even a methodless trait
            donors.add(actual);
        }
        var interfaces=new ArrayList<Class<?>>();
        for(ClassEntity iface:recipe.interfaces()) {
            if(iface.getType()!=ClassEntity.Type.INTERFACE)throw new LinkageError("Composition interface has the wrong PHP kind");
            if(!iface.isInternal())continue; // user interfaces remain PHP metadata-only
            Class<?> actual=visible(loader,iface.getNativeClass());
            if(!actual.isInterface())throw new LinkageError("Native PHP interface lacks a physical Java interface");
            if(!interfaces.contains(actual))interfaces.add(actual);
        }
        var copies=new ArrayList<Copy>();
        for(var choice:recipe.methods()) {
            MethodEntity source=choice.donor(),target=choice.target();
            if(source.getClazz()==null || target.getClazz()!=owner)throw new LinkageError("Trait method owner mismatch");
            ClassEntity donor=source.getClazz();Class<?> actual=visible(loader,donor.getNativeClass());
            if(!donors.contains(actual))throw new LinkageError("Selected method is outside the actual trait graph");
            byte[] data=loader.traitDefinition(donor);
            MethodModel body=method(API.parse(data),source.getInternalName(),DESC);
            if(!source.isAbstractable()) {
                Method nativeMethod=source.getNativeMethod();
                if(nativeMethod==null || nativeMethod.getDeclaringClass()!=actual
                        || !nativeMethod.getName().equals(source.getInternalName())
                        || nativeMethod.getReturnType()!=Memory.class
                        || !java.util.Arrays.equals(nativeMethod.getParameterTypes(),new Class<?>[]{Environment.class,Memory[].class}))
                    throw new LinkageError("Donor PHP method differs from its exact physical body");
            }
            int flags=choice.targetJvmFlags();
            int fixed=ClassFile.ACC_STATIC|ClassFile.ACC_ABSTRACT|ClassFile.ACC_NATIVE;
            if((body.flags().flagsMask()&fixed)!=(flags&fixed))throw new LinkageError("Trait adaptation changes the physical body kind");
            var hook=php.runtime.reflection.support.PhpPropertyHooks.ownedMethod(donor,source);
            PhpTraitPropertyHookOrigin.RuntimeSource hookSource=hook==null || source.isAbstractable()?null
                    :PhpTraitPropertyHookOrigin.runtimeSource(hook,loader,recipe.traits());
            String targetOwner=owner.getCompiledInternalName().replace('.','/');
            String hookFingerprint=hookSource==null?null:hookSource.fingerprint()+"\0"
                    +hash(PhpTraitPropertyHookOrigin.runtimeOrigin(hookSource,targetOwner,target.getInternalName()));
            supported(body,hookSource!=null);body.code().ifPresent(PhpJvmTraitMethodCopy::supported);
            copies.add(new Copy(actual,donor.getCompiledInternalName().replace('.','/'),data.clone(),
                    source.getInternalName(),target.getInternalName(),targetOwner,List.copyOf(donors),flags,hash(data),
                    hook,hookSource,hookFingerprint));
        }
        var bridges=new ArrayList<Bridge>();
        for(var choice:recipe.bridges()) {
            Class<?> actual=visible(loader,choice.nativeInterface().getNativeClass());
            if(!interfaces.contains(actual)||!actual.isInterface()
                    || owner.findMethod(choice.target().getLowerName())!=choice.target()
                    || choice.target().isStatic()||!DESC.equals(choice.descriptor()))throw new LinkageError("Invalid native interface bridge target");
            Method member=choice.contract().getNativeMethod();
            if(member==null||!member.getDeclaringClass().isAssignableFrom(actual)
                    ||!member.getName().equals(choice.javaName())||java.lang.reflect.Modifier.isStatic(member.getModifiers())
                    ||member.getReturnType()!=Memory.class
                    ||!java.util.Arrays.equals(member.getParameterTypes(),new Class<?>[]{Environment.class,Memory[].class}))
                throw new LinkageError("Unsupported actual native interface contract");
            Bridge bridge=new Bridge(actual,member,choice.javaName(),choice.target().getInternalName());
            Bridge existing=bridges.stream().filter(item -> item.name().equals(bridge.name())).findFirst().orElse(null);
            if(existing!=null&&!existing.target().equals(bridge.target()))throw new LinkageError("Conflicting native bridge selections");
            if(existing==null)bridges.add(bridge);
        }
        recipe.validate();return new Plan(donors,copies,interfaces,bridges);
    }

    private static Class<?> visible(RuntimeClassLoader loader,Class<?> actual) {
        if(actual==null)throw new LinkageError("Composition dependency lacks its physical class");
        try {if(Class.forName(actual.getName(),false,loader)!=actual)throw new LinkageError("Foreign physical composition dependency");}
        catch(ClassNotFoundException failure){throw new LinkageError("Missing physical composition dependency",failure);}
        return actual;
    }

    static byte[] compose(byte[] template,Plan plan,Class<?> parent,RuntimeClassLoader loader) {
        ClassModel target=API.parse(template);String owner=target.thisClass().asInternalName();
        ClassDesc receiver=target.thisClass().asSymbol();
        ClassHierarchyResolver known=ClassHierarchyResolver.ofClassLoading(loader);
        ClassHierarchyResolver hierarchy=name -> name.equals(receiver)
                ? ClassHierarchyResolver.ClassHierarchyInfo.ofClass(ClassDesc.of(parent.getName())) : known.getClassInfo(name);
        ClassFile writer=ClassFile.of(ClassFile.AttributeMapperOption.of(name ->
                name.stringValue().equals(FRAME)?new ProtocolMapper(FRAME):name.stringValue().equals(RUNTIME)?new ProtocolMapper(RUNTIME):name.stringValue().equals(STATIC_SITES)?new ProtocolMapper(STATIC_SITES):name.stringValue().equals(CELL)?new ProtocolMapper(CELL):name.stringValue().equals(HOOK_ORIGIN)?new HookOriginMapper():null),
                ClassFile.StackMapsOption.GENERATE_STACK_MAPS,ClassFile.ClassHierarchyResolverOption.of(hierarchy));
        var names=new HashSet<String>();target.methods().forEach(method -> names.add(method.methodName().stringValue()+method.methodType().stringValue()));
        for(Copy copy:plan.copies)if(!names.add(copy.target()+DESC))throw new LinkageError("Trait method collides with a compiled consumer method");
        var interfaceNames=new ArrayList<ClassDesc>();target.interfaces().forEach(entry -> interfaceNames.add(entry.asSymbol()));
        plan.interfaces.forEach(actual -> {ClassDesc type=ClassDesc.of(actual.getName());if(!interfaceNames.contains(type))interfaceNames.add(type);});
        byte[] output=writer.transformClass(target,ClassTransform.endHandler(builder -> {
            builder.withInterfaces(interfaceNames.stream().map(builder.constantPool()::classEntry).toList());
            for(Copy copy:plan.copies) {
                MethodModel source=method(API.parse(copy.bytes()),copy.source(),DESC);
                var replacements=entryConstants(source,copy,receiver);
                builder.withMethod(copy.target(),TYPE,copy.flags(),destination -> {
                    destination.transform(source,(MethodTransform)(method,element) -> {
                    if(element instanceof AccessFlags)return;
                    if(element instanceof HookOriginAttribute) {
                        if(copy.hookSource()==null)throw new LinkageError("Ordinary method cannot transport a hook origin");
                        return; // Replace only this fully attested hook's previous immediate-donor recipe.
                    }
                    if(element instanceof ProtocolAttribute protocol) {
                        method.with(protocol.rebind(copy.donorTemplate(),copy.source(),owner,copy.target(),DESC));
                    }else if(element instanceof CodeModel code) {
                        int[] ordinal={0};
                        method.transformCode(code,(body,instruction) -> {
                            ConstantDesc replacement=instruction instanceof Instruction ? replacements.get(ordinal[0]++) : null;
                            if(replacement!=null)body.ldc(replacement);
                            else if(instruction instanceof LocalVariable local && local.slot()==0 && local.name().stringValue().equals("~this")) {
                                body.with(LocalVariable.of(0,local.name().stringValue(),receiver,local.startScope(),local.endScope()));
                            }else body.with(instruction); // exact actual donor table/cache/type/callee origins stay intact
                        });
                    }else method.with(element);
                    });
                    if(copy.hookSource()!=null)destination.with(new HookOriginAttribute(new HookOriginMapper(),null,
                            PhpTraitPropertyHookOrigin.runtimeOrigin(copy.hookSource(),copy.targetOwner(),copy.target())));
                });
            }
            for(Bridge bridge:plan.bridges) {
                if(!names.add(bridge.name()+DESC)) {
                    // A precompiled bridge may satisfy it only when its complete body is the same dispatch.
                    throw new LinkageError("Native bridge collides with an existing JVM method");
                }
                builder.withMethodBody(bridge.name(),TYPE,ClassFile.ACC_PUBLIC,
                        code -> code.aload(0).aload(1).aload(2).invokevirtual(receiver,bridge.target(),TYPE).areturn());
            }
        }));
        ClassModel finished=API.parse(output);
        for(Copy copy:plan.copies) {
            MethodModel before=method(API.parse(copy.bytes()),copy.source(),DESC),after=method(finished,copy.target(),DESC);
            if(!opcodes(before).equals(opcodes(after)))throw new LinkageError("Copied method changed protocol instruction ordinals");
            if(copy.hookSource()!=null)PhpTraitPropertyHookOrigin.validateRuntimeTarget(
                    copy.hookSource(),output,copy.targetOwner(),copy.target(),loader);
        }
        return output;
    }

    /** Rebind only the exact generated entry class/name operands, never arbitrary PHP strings. */
    private static java.util.Map<Integer,ConstantDesc> entryConstants(MethodModel source,Copy copy,ClassDesc receiver) {
        var instructions=new ArrayList<Instruction>();
        source.code().ifPresent(code -> code.forEach(element -> {if(element instanceof Instruction item)instructions.add(item);}));
        var replacements=new HashMap<Integer,ConstantDesc>();
        for(int at=0;at<instructions.size();at++)if(instructions.get(at) instanceof InvokeInstruction invoke
                && invoke.name().stringValue().equals("enterGenerated")
                && (invoke.owner().asInternalName().equals("php/runtime/PhpOwnershipExecution")
                || invoke.owner().asInternalName().equals("php/runtime/PhpRuntimeOwnership"))) {
            String result=invoke.owner().asInternalName().equals("php/runtime/PhpOwnershipExecution")
                    ?"Lphp/runtime/PhpOwnershipExecution$Entry;":"Lphp/runtime/PhpRuntimeOwnership$Frame;";
            if(at<4||invoke.opcode()!=Opcode.INVOKESTATIC
                    || !invoke.type().stringValue().equals("(Lphp/runtime/env/Environment;Ljava/lang/Class;Ljava/lang/String;Ljava/lang/String;)"+result)
                    || !(instructions.get(at-4) instanceof LoadInstruction env)
                    || env.typeKind()!=TypeKind.REFERENCE || env.slot()!=((copy.flags()&ClassFile.ACC_STATIC)==0?1:0)
                    || !(instructions.get(at-3) instanceof ConstantInstruction clazz)
                    || !clazz.constantValue().equals(ClassDesc.of(copy.actual().getName()))
                    || !(instructions.get(at-2) instanceof ConstantInstruction name)||!name.constantValue().equals(copy.source())
                    || !(instructions.get(at-1) instanceof ConstantInstruction type)||!type.constantValue().equals(DESC))
                throw new LinkageError("Trait entry differs from its exact generated class/method tuple");
            replacements.put(at-3,receiver);replacements.put(at-2,copy.target());
        }
        for(int at=0;at<instructions.size();at++)if(instructions.get(at) instanceof InvokeInstruction invoke
                &&invoke.owner().asInternalName().equals("php/runtime/PhpPrimitiveCellRuntime")) {
            if(at<4||invoke.opcode()!=Opcode.INVOKESTATIC
                    ||!(instructions.get(at-4) instanceof ConstantInstruction clazz)||!clazz.constantValue().equals(ClassDesc.of(copy.actual().getName()))
                    ||!(instructions.get(at-3) instanceof ConstantInstruction name)||!name.constantValue().equals(copy.source())
                    ||!(instructions.get(at-2) instanceof ConstantInstruction type)||!type.constantValue().equals(DESC)
                    ||!(instructions.get(at-1) instanceof ConstantInstruction site)||!(site.constantValue() instanceof Integer))
                throw new LinkageError("Trait CELL helper differs from its exact generated member tuple");
            replacements.put(at-4,receiver);replacements.put(at-3,copy.target());
        }
        int hookEntries=0;
        for(int at=0;at<instructions.size();at++)if(instructions.get(at) instanceof InvokeInstruction invoke
                &&invoke.owner().asInternalName().equals("php/runtime/PhpPropertyHookBodyAccess")
                &&invoke.name().equalsString("bindGeneratedMethod")) {
            var hook=copy.hook();
            if(hook==null || ++hookEntries!=1 || at<5 || invoke.opcode()!=Opcode.INVOKESTATIC
                    || !invoke.type().equalsString("(Lphp/runtime/lang/IObject;Lphp/runtime/env/Environment;Ljava/lang/String;ILjava/lang/String;)Lphp/runtime/reflection/support/PhpPropertyHooks$Hook;")
                    || !(instructions.get(at-5) instanceof LoadInstruction self) || self.typeKind()!=TypeKind.REFERENCE || self.slot()!=0
                    || !(instructions.get(at-4) instanceof LoadInstruction env) || env.typeKind()!=TypeKind.REFERENCE || env.slot()!=1
                    || !(instructions.get(at-3) instanceof ConstantInstruction property) || !property.constantValue().equals(hook.declaration().getName())
                    || !(instructions.get(at-2) instanceof ConstantInstruction kind) || !kind.constantValue().equals(hook.kind()==php.runtime.reflection.support.PhpPropertyHooks.Kind.GET?1:2)
                    || !(instructions.get(at-1) instanceof ConstantInstruction member) || !member.constantValue().equals(copy.source()))
                throw new LinkageError("Trait hook body differs from its exact property/kind/private-member entry");
            hook.validate();replacements.put(at-1,copy.target());
        }
        if(copy.hook()!=null && (copy.flags()&ClassFile.ACC_ABSTRACT)==0 && hookEntries!=1)
            throw new LinkageError("Trait hook body lacks its exact private-member binder");
        staticKeys(source,copy,instructions,replacements);
        return replacements;
    }

    private record StaticSite(String local,String key,int[] at) { }
    private static List<StaticSite> staticSites(MethodModel source,Copy copy) {
        var attributes=source.attributes().stream().filter(attribute->attribute.attributeName().equalsString(STATIC_SITES)).toList();
        if(attributes.isEmpty())return List.of();if(attributes.size()!=1||!(attributes.getFirst() instanceof ProtocolAttribute attribute))
            throw new LinkageError("Duplicate or unreadable trait static sites");
        try {
            var in=new DataInputStream(new ByteArrayInputStream(attribute.data));
            if(in.readInt()!=1||!in.readUTF().equals(copy.donorTemplate())||!in.readUTF().equals(copy.source())||!in.readUTF().equals(DESC))
                throw new LinkageError("Trait static site header differs from its source");
            int count=in.readInt();if(count<1||count>65535)throw new LinkageError("Invalid trait static site count");
            var sites=new ArrayList<StaticSite>();
            for(int site=0;site<count;site++) {
                String local=in.readUTF(),key=in.readUTF();int[] at=new int[15];for(int node=0;node<15;node++)at[node]=in.readInt();
                sites.add(new StaticSite(local,key,at));
            }
            if(in.available()!=0)throw new LinkageError("Trailing trait static site data");return sites;
        }catch(IOException failure){throw new LinkageError("Malformed trait static sites",failure);}
    }
    private static void staticKeys(MethodModel source,Copy copy,List<Instruction> code,java.util.Map<Integer,ConstantDesc> replacements) {
        var claimed=new HashSet<Integer>();
        for(StaticSite site:staticSites(source,copy)) {
            int[] at=site.at();for(int ordinal:at)if(ordinal<0||ordinal>=code.size())throw new LinkageError("Trait static site is outside its body");
            String prefix=copy.donorTemplate()+"\0"+site.local()+"\0";
            if(site.local().isEmpty()||site.local().indexOf('\0')>=0||!site.key().startsWith(prefix)
                    ||site.key().length()==prefix.length()||site.key().substring(prefix.length()).indexOf('\0')>=0)
                throw new LinkageError("Trait static site key has a foreign owner/local");
            if(at[2]!=at[1]+1||at[3]!=at[2]+1||at[4]!=at[3]+1||at[0]!=at[1]-5
                    ||at[7]!=at[6]+1||at[8]!=at[7]+1||at[9]!=at[8]+1||at[5]!=at[6]-5
                    ||at[11]!=at[10]+1||at[12]!=at[11]+1||at[13]!=at[12]+1||at[14]!=at[13]+1
                    ||at[4]>=at[5]||at[9]>=at[10])throw new LinkageError("Trait static key producer/consumer order differs");
            int env=((copy.flags()&ClassFile.ACC_STATIC)==0?1:0);
            load(code.get(at[0]),env);self(code,at[1],env,copy);self(code,at[6],env,copy);
            literal(code.get(at[2]),site.key());literal(code.get(at[7]),site.key());
            invoke(code.get(at[3]),"java/lang/String","concat","(Ljava/lang/String;)Ljava/lang/String;");
            invoke(code.get(at[8]),"java/lang/String","concat","(Ljava/lang/String;)Ljava/lang/String;");
            invoke(code.get(at[4]),"php/runtime/env/Environment","getStatic","(Ljava/lang/String;)Lphp/runtime/Memory;");
            invoke(code.get(at[14]),"php/runtime/env/Environment","getOrCreateStatic","(Ljava/lang/String;Lphp/runtime/Memory;)Lphp/runtime/Memory;");
            int envSlot=store(code.get(at[5])),keySlot=store(code.get(at[9])),valueSlot=store(code.get(at[10]));
            if(envSlot==keySlot||envSlot==valueSlot||keySlot==valueSlot||envSlot<=env||keySlot<=env||valueSlot<=env)
                throw new LinkageError("Trait static scratch aliases an input or another scratch");
            load(code.get(at[5]-1),env);load(code.get(at[11]),envSlot);load(code.get(at[12]),keySlot);load(code.get(at[13]),valueSlot);
            for(int scratch:new int[]{envSlot,keySlot,valueSlot}) {
                long stores=code.stream().filter(item->item instanceof StoreInstruction value&&value.slot()==scratch).count();
                long reads=code.stream().filter(item->item instanceof LoadInstruction value&&value.slot()==scratch).count();
                if(stores!=1||reads!=1)throw new LinkageError("Trait static scratch was reused outside its exact producer/consumer");
            }
            if(!claimed.add(at[4])||!claimed.add(at[14]))throw new LinkageError("Duplicate trait static helper site");
            String replacement=copy.targetOwner()+"\0"+site.local()+"\0"+copy.target();
            if(replacements.put(at[2],replacement)!=null||replacements.put(at[7],replacement)!=null)
                throw new LinkageError("Trait static key overlaps another rewritten operand");
        }
        for(int at=0;at<code.size();at++)if(code.get(at) instanceof InvokeInstruction call
                &&call.owner().asInternalName().equals("php/runtime/env/Environment")
                &&(call.name().equalsString("getStatic")||call.name().equalsString("getOrCreateStatic"))&&!claimed.contains(at))
            throw new LinkageError("Copied trait static helper lacks its compiler-minted key sites");
    }
    private static void self(List<Instruction> code,int at,int env,Copy copy) {
        if(at<4)throw new LinkageError("Missing trait static self trace");load(code.get(at-4),env);
        if(!(code.get(at-3) instanceof FieldInstruction field)||field.opcode()!=Opcode.GETSTATIC
                ||!traceOrigin(copy,field.owner().asInternalName())
                ||!field.name().equalsString("$TRC")||!field.type().equalsString("[Lphp/runtime/env/TraceInfo;")
                ||!(code.get(at-2) instanceof ConstantInstruction index)||!(index.constantValue() instanceof Integer number)||number<0
                ||code.get(at-1).opcode()!=Opcode.AALOAD)throw new LinkageError("Trait static self trace differs from its actual donor");
        invoke(code.get(at),"php/runtime/env/Environment","__getSelf","(Lphp/runtime/env/TraceInfo;)Ljava/lang/String;");
    }
    private static boolean traceOrigin(Copy copy,String name) {
        for(Class<?> donor:copy.traceOrigins())if(donor.getName().replace('.','/').equals(name)) {
            try {
                var field=donor.getDeclaredField("$TRC");
                return java.lang.reflect.Modifier.isStatic(field.getModifiers()) && field.getType()==php.runtime.env.TraceInfo[].class;
            }catch(NoSuchFieldException missing){return false;}
        }
        return false;
    }
    private static void literal(Instruction instruction,String value) {
        if(!(instruction instanceof ConstantInstruction constant)||!value.equals(constant.constantValue()))throw new LinkageError("Trait static key literal differs");
    }
    private static void invoke(Instruction instruction,String owner,String name,String type) {
        if(!(instruction instanceof InvokeInstruction call)||call.opcode()!=Opcode.INVOKEVIRTUAL
                ||!call.owner().asInternalName().equals(owner)||!call.name().equalsString(name)||!call.type().equalsString(type))
            throw new LinkageError("Trait static helper descriptor differs");
    }
    private static int store(Instruction instruction) {
        if(!(instruction instanceof StoreInstruction store)||store.typeKind()!=TypeKind.REFERENCE)throw new LinkageError("Trait static scratch has no actual reference store");return store.slot();
    }
    private static void load(Instruction instruction,int slot) {
        if(!(instruction instanceof LoadInstruction load)||load.typeKind()!=TypeKind.REFERENCE||load.slot()!=slot)throw new LinkageError("Trait static scratch has no exact reference load");
    }

    private static List<String> opcodes(MethodModel method) {
        var result=new ArrayList<String>();method.code().ifPresent(code -> code.forEach(element -> {
            if(element instanceof Instruction instruction) {
                String op=instruction.opcode().name();
                result.add(op.equals("LDC_W")?"LDC":op.equals("GOTO_W")?"GOTO":op.equals("JSR_W")?"JSR":op);
            }
        }));return result;
    }
    private static MethodModel method(ClassModel model,String name,String descriptor) {
        var methods=model.methods().stream().filter(method -> method.methodName().stringValue().equals(name)
                && method.methodType().stringValue().equals(descriptor)).toList();
        if(methods.size()!=1)throw new LinkageError("Missing or ambiguous physical method "+name);return methods.getFirst();
    }
    private static void supported(AttributedElement element) {supported(element,false);}
    private static void supported(AttributedElement element,boolean attestedHook) {
        for(Attribute<?> attribute:element.attributes()) {
            if(attribute instanceof HookOriginAttribute) {
                if(!attestedHook || !(element instanceof MethodModel))
                    throw new LinkageError("Hook origin is outside its exact attested hidden method");
            }else if(!(attribute instanceof ProtocolAttribute)
                    && (attribute.attributeMapper().stability()==AttributeMapper.AttributeStability.UNKNOWN
                    || attribute.attributeMapper().stability()==AttributeMapper.AttributeStability.UNSTABLE))
                throw new LinkageError("Unsupported copied attribute "+attribute.attributeName().stringValue());
        }
    }
    private static String hash(byte[] data) {
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));}
        catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }

    /** Payload has no CP indices; opcode sites remain valid only after explicit output sequence comparison. */
    private static final class ProtocolAttribute extends CustomAttribute<ProtocolAttribute> {
        private final byte[] data;private final Utf8Entry name;
        ProtocolAttribute(ProtocolMapper mapper,Utf8Entry name,byte[] data){super(mapper);this.name=name;this.data=data.clone();}
        @Override public Utf8Entry attributeName(){return name==null?super.attributeName():name;}
        ProtocolAttribute rebind(String sourceOwner,String sourceMethod,String owner,String method,String descriptor) {
            try {
                var input=new DataInputStream(new ByteArrayInputStream(data));
                int version=input.readInt();String oldOwner=input.readUTF(),oldMethod=input.readUTF(),oldDescriptor=input.readUTF();
                if(version<1||!oldOwner.equals(sourceOwner)||!oldMethod.equals(sourceMethod)||!oldDescriptor.equals(descriptor))
                    throw new LinkageError("Copied protocol header differs from the selected source method");
                var bytes=new ByteArrayOutputStream();var output=new DataOutputStream(bytes);
                output.writeInt(version);output.writeUTF(owner);output.writeUTF(method);output.writeUTF(descriptor);
                if(attributeName().equalsString(STATIC_SITES)) {
                    int count=input.readInt();if(count<1||count>65535)throw new LinkageError("Invalid trait static site count");output.writeInt(count);
                    for(int site=0;site<count;site++){String local=input.readUTF();input.readUTF();output.writeUTF(local);
                        output.writeUTF(owner+"\0"+local+"\0"+method);for(int node=0;node<15;node++)output.writeInt(input.readInt());}
                    if(input.available()!=0)throw new LinkageError("Trailing trait static site data");
                }else output.write(input.readAllBytes());
                output.flush();return new ProtocolAttribute((ProtocolMapper)attributeMapper(),null,bytes.toByteArray());
            }catch(IOException malformed){throw new LinkageError("Malformed copied method protocol",malformed);}
        }
    }
    /** CP-independent recipe bytes; only runtimeSource/full-pair validation grants transport. */
    private static final class HookOriginAttribute extends CustomAttribute<HookOriginAttribute> {
        private final byte[] data;private final Utf8Entry name;
        HookOriginAttribute(HookOriginMapper mapper,Utf8Entry name,byte[] data) {
            super(mapper);this.name=name;this.data=data.clone();
        }
        @Override public Utf8Entry attributeName(){return name==null?super.attributeName():name;}
    }
    private static final class HookOriginMapper implements AttributeMapper<HookOriginAttribute> {
        @Override public String name(){return HOOK_ORIGIN;}
        @Override public AttributeStability stability(){return AttributeStability.UNSTABLE;}
        @Override public HookOriginAttribute readAttribute(AttributedElement enclosing,ClassReader reader,int offset) {
            if(!(enclosing instanceof MethodModel))throw new LinkageError("Hook origin must be a method attribute");
            int size=reader.readInt(offset-4);
            if(size<1 || size>1024*1024)throw new LinkageError("Invalid hook origin attribute size");
            return new HookOriginAttribute(this,reader.readEntry(offset-6,Utf8Entry.class),reader.readBytes(offset,size));
        }
        @Override public void writeAttribute(BufWriter output,HookOriginAttribute attribute) {
            output.writeIndex(attribute.attributeName());output.writeInt(attribute.data.length);output.writeBytes(attribute.data);
        }
    }
    private static final class ProtocolMapper implements AttributeMapper<ProtocolAttribute> {
        private final String name;ProtocolMapper(String name){this.name=name;}
        @Override public String name(){return name;}
        @Override public AttributeStability stability(){return AttributeStability.UNSTABLE;}
        @Override public ProtocolAttribute readAttribute(AttributedElement enclosing,ClassReader reader,int offset){
            return new ProtocolAttribute(this,reader.readEntry(offset-6,Utf8Entry.class),reader.readBytes(offset,reader.readInt(offset-4)));
        }
        @Override public void writeAttribute(BufWriter output,ProtocolAttribute attribute){
            output.writeIndex(attribute.attributeName());output.writeInt(attribute.data.length);output.writeBytes(attribute.data);
        }
    }
}
