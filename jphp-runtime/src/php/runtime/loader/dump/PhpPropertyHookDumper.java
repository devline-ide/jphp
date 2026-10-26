package php.runtime.loader.dump;

import java.io.IOException;
import java.util.*;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;
import php.runtime.loader.dump.io.DumpException;
import php.runtime.reflection.*;
import php.runtime.reflection.support.PhpPropertyHooks;
import php.runtime.common.PhpRuntimeOwnershipMode;

/** Cold declaration/physical hook binding checks. No autoload, class definition or ownership certification. */
final class PhpPropertyHookDumper {
    private static final String METHOD="(Lphp/runtime/env/Environment;[Lphp/runtime/Memory;)Lphp/runtime/Memory;";
    private static final String BIND="(Lphp/runtime/lang/IObject;Lphp/runtime/env/Environment;Ljava/lang/String;ILjava/lang/String;)Lphp/runtime/reflection/support/PhpPropertyHooks$Hook;";
    private PhpPropertyHookDumper() {}
    static void validateModule(ModuleEntity module,boolean includeData) throws IOException {validateModule(module,includeData,null);}
    static void validateModule(ModuleEntity module,boolean includeData,php.runtime.env.Environment env) throws IOException {
        for(ClassEntity klass:module.getClasses()) validateSourceClass(klass,includeData,env);
        for(ClassEntity klass:module.getDeferredClasses()) validateSourceClass(klass,includeData,env);
        for(ClassEntity klass:module.getGenerators()) validateSourceClass(klass,includeData,env);
    }
    private static void validateSourceClass(ClassEntity klass,boolean includeData,php.runtime.env.Environment env) throws IOException {
        List<PropertyEntity> owned=new ArrayList<>();
        for(PropertyEntity p:klass.getProperties()) if(p.isOwned(klass)) owned.add(p);
        for(PropertyEntity p:klass.getStaticProperties()) if(p.isOwned(klass)) owned.add(p);
        for(PropertyEntity property:owned) if(property.getPhpHooks()!=null)
            try {property.getPhpHooks().validateFor(property);}
            catch(IllegalArgumentException malformed){throw new DumpException("Invalid source hook composition recipe");}
        if(!includeData && owned.stream().anyMatch(p->p.getPhpHooks()!=null && !p.getPhpHooks().declaredOrder().isEmpty()))
            throw new DumpException("Cannot save hooked declarations without physical class bytes");
        validateClass(klass,owned,klass.getOwnedMethods(),true,env);
    }
    static void validateDecodedClass(ClassEntity klass,List<PropertyEntity> properties,List<MethodEntity> ordinary) throws IOException {
        validateDecodedClass(klass,properties,ordinary,false);
    }
    static void validateDecodedClass(ClassEntity klass,List<PropertyEntity> properties,List<MethodEntity> ordinary,
                                     boolean encodedParent) throws IOException {
        validateDecodedClass(klass,properties,ordinary,encodedParent,null);
    }
    static void validateDecodedClass(ClassEntity klass,List<PropertyEntity> properties,List<MethodEntity> ordinary,
                                     boolean encodedParent,php.runtime.env.Environment env) throws IOException {
        validateClass(klass,properties,ordinary,false,encodedParent || klass.isUnresolvedDeclaration(),env);
    }
    private static void validateClass(ClassEntity klass,List<PropertyEntity> properties,List<MethodEntity> ordinary,boolean source,
                                     php.runtime.env.Environment env) throws IOException {
        validateClass(klass,properties,ordinary,source,klass.getParent()!=null || klass.isUnresolvedDeclaration(),env);
    }
    private static void validateClass(ClassEntity klass,List<PropertyEntity> properties,List<MethodEntity> ordinary,boolean source,
                                      boolean encodedParent,php.runtime.env.Environment env) throws IOException {
        boolean hasHooks=properties.stream().anyMatch(p->p.getPhpHooks()!=null && !p.getPhpHooks().declaredOrder().isEmpty());
        if(!hasHooks) return;
        if(klass.getModule()==null || klass.getModule().getPhpLanguageProfile()!=php.runtime.common.PhpLanguageProfile.PHP_8_5 || klass.isEnum())
            throw new DumpException("Hook declarations require a modern non-enum class");
        byte[] bytes=klass.getData();
        if(bytes==null || bytes.length==0) throw new DumpException("Missing physical hook declaration class");
        ClassNode physical;
        try {physical=PhpTraitPropertyHookOrigin.readClass(bytes);}
        catch(RuntimeException malformed){throw new DumpException("Invalid physical hook class bytes");}
        if(!physical.name.equals(klass.getCompiledInternalName().replace('.','/')))
            throw new DumpException("Physical hook class identity mismatch");
        Set<String> ordinaryMembers=new HashSet<>(),hookMembers=new HashSet<>();
        for(MethodEntity method:ordinary) ordinaryMembers.add(method.getInternalName());
        Set<String> propertyNames=new HashSet<>();
        for(PropertyEntity property:properties) {
            if(property.getName()==null || property.getName().isEmpty() || property.getName().indexOf('\0')>=0 || !propertyNames.add(property.getName()))
                throw new DumpException("Invalid or duplicate owned hook property catalog");
            var hooks=property.getPhpHooks();if(hooks==null) continue;
            // A local virtual declaration may reuse an inherited backed slot, but only an explicit
            // encoded pending parent permits postponing this storage check until cold linkage.
            if(hooks.isVirtual() && property.isDefault() && !hooks.isResolved() && !encodedParent)
                throw new DumpException("Virtual default lacks an exact pending parent declaration");
            try {hooks.validateFor(property);} catch(IllegalArgumentException malformed){throw new DumpException("Invalid hook declaration contract");}
            if(!source && (hooks.isResolved() || hooks.declaredOrder().size()!=hooks.declarationOrder().size()))
                throw new DumpException("Decoded owned hook catalog contains a foreign composition recipe");
            for(var hook:hooks.declaredOrder()) {
                MethodEntity method=hook.method();
                if(hook.declaration()!=property || method.getClazz()!=property.getClazz() || method.isImmutable() || method.getImmutableResult()!=null)
                    throw new DumpException("Hook declaration is not its exact owned callable");
                String member=method.getInternalName();
                if(member==null || member.isEmpty() || member.indexOf('\0')>=0 || ordinaryMembers.contains(member) || !hookMembers.add(member))
                    throw new DumpException("Duplicate or invalid physical hook member");
                List<MethodNode> matches=physical.methods.stream().filter(m->m.name.equals(member)).toList();
                if(matches.isEmpty() && method.isAbstractable()) continue;
                if(matches.size()!=1) throw new DumpException("Missing or ambiguous physical hook member");
                MethodNode target=matches.get(0);int flags=Opcodes.ACC_PRIVATE|(method.isFinal()?Opcodes.ACC_FINAL:0);
                if(!target.desc.equals(METHOD) || target.access!=flags)
                    throw new DumpException("Physical hook callable descriptor or flags mismatch");
                List<AbstractInsnNode> code=new ArrayList<>();for(AbstractInsnNode n:target.instructions)if(n.getOpcode()>=0)code.add(n);
                PhpTraitPropertyHookOrigin.validateHeader(klass,physical,target,property,hook,env);
            }
        }
    }
    /** Called only after the creator's exact physical factory has selected this runner. */
    static void validateGenerator(MethodEntity method,php.runtime.reflection.helper.GeneratorEntity generator)throws IOException {
        PhpPropertyHooks.Hook selected=null;
        for(PropertyEntity property:method.getClazz().getProperties()) {
            var hooks=property.getPhpHooks();if(hooks==null)continue;
            for(var hook:hooks.declaredOrder())if(hook.method()==method) {
                if(selected!=null || hook.declaration()!=property || method.getClazz()!=property.getClazz())
                    throw new DumpException("Ambiguous generator hook declaration");
                selected=hook;
            }
        }
        if(selected==null)return; // Ordinary generators retain their existing exact linkage contract.
        if(selected.kind()!=PhpPropertyHooks.Kind.GET || generator==null || generator.getData()==null)
            throw new DumpException("Generator hook lacks its getter runner");
        ClassNode physical=new ClassNode();
        try{new ClassReader(generator.getData()).accept(physical,ClassReader.SKIP_DEBUG|ClassReader.SKIP_FRAMES);}
        catch(RuntimeException malformed){throw new DumpException("Invalid generator hook runner bytes");}
        if(!physical.name.equals(generator.getCompiledInternalName().replace('.','/'))
                || !"php/runtime/lang/Generator".equals(physical.superName))
            throw new DumpException("Generator hook runner identity mismatch");
        List<MethodNode> matches=physical.methods.stream().filter(m->m.name.equals("_run")).toList();
        if(matches.size()!=1 || !matches.get(0).desc.equals(METHOD) || matches.get(0).access!=Opcodes.ACC_PROTECTED)
            throw new DumpException("Generator hook lacks its exact protected resumed member");
        MethodNode target=matches.get(0);List<AbstractInsnNode> code=new ArrayList<>();
        for(AbstractInsnNode n:target.instructions)if(n.getOpcode()>=0)code.add(n);
        code=propertyContextHeaderView(code);
        boolean strict=generator.getModule().isStrictTypes();
        boolean owned=generator.getModule().getOwnershipMode().supportsAdmission();
        int body=owned?19:7;
        if(code.size()<body+10 || !load(code.get(0),1)
                || !(code.get(1) instanceof FieldInsnNode trace) || trace.getOpcode()!=Opcodes.GETSTATIC
                || !trace.owner.equals(physical.name) || !trace.name.equals("$TRC") || !trace.desc.equals("[Lphp/runtime/env/TraceInfo;")
                || !(code.get(2) instanceof LdcInsnNode site) || !(site.cst instanceof Integer index)
                || index<0 || index>=traceCapacity(physical) || code.get(3).getOpcode()!=Opcodes.AALOAD
                || code.get(4).getOpcode()!=(strict?Opcodes.ICONST_1:Opcodes.ICONST_0)
                || !invoke(code.get(5),"php/runtime/PhpPropertyWriteContext","enter",WRITE_ENTER))
            throw new DumpException("Generator hook has an invalid mandatory write prologue");
        int write=store(code.get(6),target.maxLocals);
        int entry=owned?ownershipEntry(code,7,physical,target,"php/runtime/PhpOwnershipExecution","php/runtime/PhpOwnershipExecution$Entry"):-1;
        int runtime=owned?ownershipEntry(code,13,physical,target,"php/runtime/PhpRuntimeOwnership","php/runtime/PhpRuntimeOwnership$Frame"):-2;
        if(!load(code.get(body+0),0) || !load(code.get(body+1),1) || !constant(code.get(body+2),selected.declaration().getName())
                || !integer(code.get(body+3),1) || !constant(code.get(body+4),"_run")
                || !invoke(code.get(body+5),"php/runtime/PhpPropertyHookBodyAccess","bindGeneratedGenerator",
                    "(Lphp/runtime/lang/Generator;Lphp/runtime/env/Environment;Ljava/lang/String;ILjava/lang/String;)Lphp/runtime/reflection/support/PhpPropertyHooks$Hook;"))
            throw new DumpException("Generator hook lacks its actual resumed binding header");
        int binding=store(code.get(body+6),target.maxLocals);
        if(!load(code.get(body+7),0) || !(code.get(body+8) instanceof MethodInsnNode receiver)
                || receiver.getOpcode()!=Opcodes.INVOKEVIRTUAL || receiver.itf || !receiver.owner.equals("php/runtime/lang/Generator")
                || !receiver.name.equals("__getHookReceiver") || !receiver.desc.equals("()Lphp/runtime/lang/IObject;"))
            throw new DumpException("Generator hook lost its retained receiver operand");
        int original=store(code.get(body+9),target.maxLocals);
        int[] locals=owned?new int[]{write,entry,runtime,binding,original}:new int[]{write,binding,original};
        for(int i=0;i<locals.length;i++)for(int j=i+1;j<locals.length;j++)if(locals[i]==locals[j])
            throw new DumpException("Generator hook aliases mandatory protocol locals");
    }
    private static final String WRITE_ENTER="(Lphp/runtime/env/Environment;Lphp/runtime/env/TraceInfo;Z)Lphp/runtime/PhpPropertyWriteContext$Frame;";
    private static final String PROFILE_WRITE_ENTER="(Lphp/runtime/env/Environment;Lphp/runtime/env/TraceInfo;ZI)Lphp/runtime/PhpPropertyWriteContext$Frame;";

    /** ABI30 adds one validated profile literal. This view never edits physical instructions or their ordinals. */
    static List<AbstractInsnNode> propertyContextHeaderView(List<AbstractInsnNode> code) {
        if(code.size()>6 && code.get(5) instanceof LdcInsnNode literal && literal.cst instanceof Integer profile
                && (profile==0 || profile==1) && invoke(code.get(6),"php/runtime/PhpPropertyWriteContext","enter",PROFILE_WRITE_ENTER)) {
            List<AbstractInsnNode> view=new ArrayList<>(code);
            view.remove(5);
            view.set(5,new MethodInsnNode(Opcodes.INVOKESTATIC,"php/runtime/PhpPropertyWriteContext","enter",WRITE_ENTER,false));
            return view;
        }
        return code;
    }
    private static final String ENTRY_ARGS="(Lphp/runtime/env/Environment;Ljava/lang/Class;Ljava/lang/String;Ljava/lang/String;)";
    /** Exactly 7 write-context + 6 scoped-ownership + 6 runtime-ownership operations, then 7 binding operations. */
    static void validateBodyHeader(ClassNode physical,MethodNode target,List<AbstractInsnNode> code,
            PropertyEntity property,PhpPropertyHooks.Hook hook,boolean strict)throws IOException {
        validateBodyHeader(physical,target,code,property,hook,strict,property.getClazz().getModule().getOwnershipMode());
    }
    /** Cold decoded members are not bound to their ClassEntity yet; the owning module supplies the contract. */
    static void validateBodyHeader(ClassNode physical,MethodNode target,List<AbstractInsnNode> code,
            PropertyEntity property,PhpPropertyHooks.Hook hook,boolean strict,PhpRuntimeOwnershipMode ownershipMode)throws IOException {
        validateBodyHeader(physical,target,code,property,hook,strict,physical.name,code.size()>2?integerValue(code.get(2)):-1,traceCapacity(physical),ownershipMode);
    }
    /** Called only after exact source-pair admission; no marker alone can supply a foreign trace witness. */
    static void validateBodyHeader(ClassNode physical,MethodNode target,List<AbstractInsnNode> code,
            PropertyEntity property,PhpPropertyHooks.Hook hook,boolean strict,String traceOwner,int traceIndex,int traceCapacity)throws IOException {
        validateBodyHeader(physical,target,code,property,hook,strict,traceOwner,traceIndex,traceCapacity,property.getClazz().getModule().getOwnershipMode());
    }
    static void validateBodyHeader(ClassNode physical,MethodNode target,List<AbstractInsnNode> code,
            PropertyEntity property,PhpPropertyHooks.Hook hook,boolean strict,String traceOwner,int traceIndex,int traceCapacity,
            PhpRuntimeOwnershipMode ownershipMode)throws IOException {
        code=propertyContextHeaderView(code);
        boolean owned=ownershipMode.supportsAdmission();
        int body=owned?19:7;
        if(code.size()<body+7 || !load(code.get(0),1)
                || !(code.get(1) instanceof FieldInsnNode trace) || trace.getOpcode()!=Opcodes.GETSTATIC
                || !trace.owner.equals(traceOwner) || !trace.name.equals("$TRC") || !trace.desc.equals("[Lphp/runtime/env/TraceInfo;")
                || !(code.get(2) instanceof LdcInsnNode site) || !(site.cst instanceof Integer index)
                || index<0 || index!=traceIndex || index>=traceCapacity
                || code.get(3).getOpcode()!=Opcodes.AALOAD || code.get(4).getOpcode()!=(strict?Opcodes.ICONST_1:Opcodes.ICONST_0)
                || !invoke(code.get(5),"php/runtime/PhpPropertyWriteContext","enter",WRITE_ENTER))
            throw new DumpException("Physical hook has an invalid mandatory property-write prologue");
        int write=store(code.get(6),target.maxLocals);
        int entry=owned?ownershipEntry(code,7,physical,target,"php/runtime/PhpOwnershipExecution","php/runtime/PhpOwnershipExecution$Entry"):-1;
        int runtime=owned?ownershipEntry(code,13,physical,target,"php/runtime/PhpRuntimeOwnership","php/runtime/PhpRuntimeOwnership$Frame"):-2;
        int kind=hook.kind()==PhpPropertyHooks.Kind.GET?1:2;
        if(!load(code.get(body+0),0) || !load(code.get(body+1),1) || !constant(code.get(body+2),property.getName())
                || !integer(code.get(body+3),kind) || !constant(code.get(body+4),target.name)
                || !invoke(code.get(body+5),"php/runtime/PhpPropertyHookBodyAccess","bindGeneratedMethod",BIND))
            throw new DumpException("Physical hook lacks its exact immutable body binding header");
        int binding=store(code.get(body+6),target.maxLocals);
        if(write==binding || owned && (write==entry || write==runtime || entry==runtime || entry==binding || runtime==binding))
            throw new DumpException("Physical hook aliases mandatory protocol locals");
    }
    private static int ownershipEntry(List<AbstractInsnNode> code,int offset,ClassNode physical,MethodNode target,String owner,String result)throws IOException {
        if(!load(code.get(offset),1) || !(code.get(offset+1) instanceof LdcInsnNode literal)
                || !(literal.cst instanceof org.objectweb.asm.Type type) || type.getSort()!=org.objectweb.asm.Type.OBJECT
                || !type.getInternalName().equals(physical.name) || !constant(code.get(offset+2),target.name)
                || !constant(code.get(offset+3),target.desc) || !invoke(code.get(offset+4),owner,"enterGenerated",ENTRY_ARGS+"L"+result+";"))
            throw new DumpException("Physical hook has an invalid mandatory ownership prologue");
        return store(code.get(offset+5),target.maxLocals);
    }
    private static int store(AbstractInsnNode node,int maximum)throws IOException {
        if(!(node instanceof VarInsnNode variable) || variable.getOpcode()!=Opcodes.ASTORE || variable.var<3 || variable.var>=maximum)
            throw new DumpException("Physical hook has an invalid mandatory protocol local");
        return variable.var;
    }
    private static boolean invoke(AbstractInsnNode node,String owner,String name,String descriptor) {
        return node instanceof MethodInsnNode call && call.getOpcode()==Opcodes.INVOKESTATIC && !call.itf
                && call.owner.equals(owner) && call.name.equals(name) && call.desc.equals(descriptor);
    }
    /** The compiler starts <clinit> with the real TraceInfo array allocation. Never resolve or execute it. */
    private static int traceCapacity(ClassNode physical)throws IOException {
        List<FieldNode> fields=physical.fields.stream().filter(f->f.name.equals("$TRC")).toList();
        if(fields.size()!=1 || fields.get(0).access!=(Opcodes.ACC_PUBLIC|Opcodes.ACC_STATIC)
                || !fields.get(0).desc.equals("[Lphp/runtime/env/TraceInfo;") || fields.get(0).value!=null)
            throw new DumpException("Physical hook has an invalid declaring trace table");
        List<MethodNode> initializers=physical.methods.stream().filter(m->m.name.equals("<clinit>")).toList();
        if(initializers.size()!=1 || !initializers.get(0).desc.equals("()V") || initializers.get(0).access!=Opcodes.ACC_STATIC)
            throw new DumpException("Physical hook lacks its exact trace initializer");
        List<AbstractInsnNode> code=new ArrayList<>();for(AbstractInsnNode n:initializers.get(0).instructions)if(n.getOpcode()>=0)code.add(n);
        if(code.size()<3 || !(code.get(1) instanceof TypeInsnNode create) || create.getOpcode()!=Opcodes.ANEWARRAY
                || !create.desc.equals("php/runtime/env/TraceInfo"))
            throw new DumpException("Physical hook trace table allocation mismatch");
        int capacity=integerValue(code.get(0));
        if(capacity<0)throw new DumpException("Physical hook trace table capacity mismatch");
        int writes=0;
        for(AbstractInsnNode n:code)if(n instanceof FieldInsnNode field && field.getOpcode()==Opcodes.PUTSTATIC
                && field.owner.equals(physical.name) && field.name.equals("$TRC")) {
            if(!field.desc.equals("[Lphp/runtime/env/TraceInfo;"))throw new DumpException("Physical hook trace table store descriptor mismatch");
            writes++;
        }
        if(writes!=1)throw new DumpException("Physical hook trace table store identity mismatch");
        return capacity;
    }
    private static int integerValue(AbstractInsnNode node) {
        int opcode=node.getOpcode();
        if(opcode>=Opcodes.ICONST_M1 && opcode<=Opcodes.ICONST_5)return opcode-Opcodes.ICONST_0;
        if(node instanceof IntInsnNode n && (opcode==Opcodes.BIPUSH||opcode==Opcodes.SIPUSH))return n.operand;
        return node instanceof LdcInsnNode n && n.cst instanceof Integer value?value:-1;
    }
    private static boolean load(AbstractInsnNode node,int slot){return node instanceof VarInsnNode v&&v.getOpcode()==Opcodes.ALOAD&&v.var==slot;}
    private static boolean constant(AbstractInsnNode node,String value){return node instanceof LdcInsnNode v&&value.equals(v.cst);}
    private static boolean integer(AbstractInsnNode node,int value){
        return node.getOpcode()==Opcodes.ICONST_0+value || node instanceof IntInsnNode n&&n.operand==value&&(n.getOpcode()==Opcodes.BIPUSH||n.getOpcode()==Opcodes.SIPUSH)
                || node instanceof LdcInsnNode n&&Integer.valueOf(value).equals(n.cst);
    }
}
