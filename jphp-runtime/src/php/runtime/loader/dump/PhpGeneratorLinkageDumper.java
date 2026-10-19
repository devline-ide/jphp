package php.runtime.loader.dump;

import php.runtime.common.PhpLanguageProfile;
import php.runtime.loader.dump.io.DumpException;
import php.runtime.reflection.ClassEntity;
import php.runtime.reflection.FunctionEntity;
import php.runtime.reflection.MethodEntity;
import php.runtime.reflection.ModuleEntity;
import php.runtime.reflection.helper.ClosureEntity;
import php.runtime.reflection.helper.GeneratorEntity;
import php.runtime.reflection.support.AbstractFunctionEntity;

import java.io.*;
import java.lang.classfile.*;
import java.lang.classfile.instruction.ConstantInstruction;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.classfile.instruction.NewObjectInstruction;
import java.util.*;

/**
 * Generator declaration linkage component for the shared, provisional ABI17.
 * This class does not select a PHB header or section layout. The owning reader must
 * restore after all declaration tables and compiler provenance load, before publication.
 * Exact classfile factories attest links, including factories copied from foreign traits.
 */
public final class PhpGeneratorLinkageDumper {
    private static final int STAMP = 0x474c4e4b; // GLNK, not a PHB header.
    private static final int VERSION = 1;
    private static final int MAX_BYTES = 16 * 1024 * 1024, MAX_ENTRIES = 100_000;
    private static final String METHOD = "(Lphp/runtime/env/Environment;[Lphp/runtime/Memory;)Lphp/runtime/Memory;";
    private static final String LOOKUP = "(Ljava/lang/String;I)Lphp/runtime/reflection/ClassEntity;";
    private static final String CONSTRUCTOR = "(Lphp/runtime/env/Environment;Lphp/runtime/reflection/ClassEntity;Lphp/runtime/Memory;[Lphp/runtime/Memory;)V";
    private PhpGeneratorLinkageDumper() { }

    /** Resolve an already validated donor by its physical module name; never by a PHP class alias. */
    @FunctionalInterface public interface ModuleResolver { ModuleEntity resolve(String internalName); }
    private record Identity(int kind, String physical, String phpName, String body) { }
    private record Target(Identity identity, byte[] data, AbstractFunctionEntity entity, ClosureEntity closure) { }
    private record Factory(String module, int ordinal, String physical, GeneratorEntity generator) { }
    private record Link(Target target, GeneratorEntity generator) { }
    private static final Factory NONE = new Factory("", -1, "", null);
    private static final Comparator<Identity> ORDER = Comparator.comparingInt(Identity::kind)
            .thenComparing(Identity::physical).thenComparing(Identity::phpName).thenComparing(Identity::body);

    private static DumpException bad(String reason) { return new DumpException("Invalid generator linkage: " + reason); }
    private static String physical(String name) throws DumpException {
        if (name == null || name.isEmpty()) throw bad("missing physical identity");
        return name.replace('.', '/');
    }
    private static void moduleBytecode(ModuleEntity module) throws IOException {
        if (module.getData() == null || module.getData().length == 0) throw bad("missing module bytecode");
        try {
            if (!ClassFile.of().parse(module.getData()).thisClass().asInternalName().equals(physical(module.getInternalName())))
                throw bad("module classfile identity mismatch");
        } catch (IllegalArgumentException failure) { throw bad("invalid module classfile"); }
    }
    private static boolean interfaceDeclaration(Target target) {
        return target.entity instanceof MethodEntity method && method.getClazz().isInterface() && method.isAbstractable();
    }
    private static int compilerAbi(ModuleEntity module) throws DumpException {
        int abi = module.getCompilerProvenance().getCompilerAbiVersion();
        if (abi < 1) throw bad("unknown original compiler ABI; source recompilation required");
        return abi;
    }
    private static void add(Map<Identity,Target> targets, int kind, String physical, String phpName,
                            String body, byte[] data, AbstractFunctionEntity entity, ClosureEntity closure) throws IOException {
        if (phpName == null || body == null) throw bad("missing declaration identity");
        Identity identity = new Identity(kind, physical(physical), phpName, body);
        if (targets.putIfAbsent(identity,new Target(identity,data,entity,closure)) != null)
            throw bad("duplicate declaration identity");
        if (targets.size() > MAX_ENTRIES) throw bad("too many declarations");
    }
    private static Map<Identity,Target> targets(ModuleEntity module) throws IOException {
        Map<Identity,Target> targets = new TreeMap<>(ORDER);
        for (FunctionEntity function : module.getFunctions()) {
            if (function.getModule() != module) throw bad("foreign function");
            add(targets,0,function.getInternalName(),function.getName(),"__invoke",function.getData(),function,null);
        }
        for (ClosureEntity closure : module.getClosures()) {
            MethodEntity invoke = closure.methodMagicInvoke;
            if (closure.getModule() != module || invoke == null || invoke.getClazz() == null)
                throw bad("foreign closure or missing __invoke");
            // Legacy compiler retains its unnamed system wrapper as __invoke's owner.
            // Both carriers must share the exact source bytecode object; factory() attests thisClass.
            if (invoke.getClazz() != closure && (module.getPhpLanguageProfile() != PhpLanguageProfile.JPHP_LEGACY
                    || invoke.getClazz().getModule() != module
                    || invoke.getClazz().getData() != closure.getData()))
                throw bad("closure ownership is not exact");
            add(targets,1,closure.getCompiledInternalName(),invoke.getName(),invoke.getInternalName(),closure.getData(),invoke,closure);
        }
        for (ClassEntity clazz : module.getClasses()) addClass(targets,module,clazz,2);
        for (ClassEntity clazz : module.getDeferredClasses()) addClass(targets,module,clazz,3);
        return targets;
    }
    private static void addClass(Map<Identity,Target> targets, ModuleEntity module, ClassEntity clazz, int kind) throws IOException {
        if (clazz.getModule() != module) throw bad("foreign class");
        // Legacy named constructors expose one declaration pointer under two map aliases.
        Set<MethodEntity> declarations = Collections.newSetFromMap(new IdentityHashMap<>());
        List<MethodEntity> ownedDeclarations=new ArrayList<>(clazz.getOwnedMethods());
        for(var property:clazz.getProperties()) if(property.getClazz()==clazz && property.getPhpHooks()!=null)
            for(var hook:property.getPhpHooks().declaredOrder()) {
                if(hook.declaration()!=property || hook.method().getClazz()!=clazz) throw bad("foreign owned hook factory");
                ownedDeclarations.add(hook.method());
            }
        for (MethodEntity method : ownedDeclarations) {
            if (!declarations.add(method)) continue;
            if (method.getClazz() != clazz) throw bad("method ownership is not exact");
            add(targets,kind,clazz.getCompiledInternalName(),method.getName(),method.getInternalName(),clazz.getData(),method,null);
        }
    }
    private static List<GeneratorEntity> generators(ModuleEntity module) throws IOException {
        List<GeneratorEntity> generators = new ArrayList<>(module.getGenerators());
        if (generators.size() > MAX_ENTRIES) throw bad("too many generators");
        Set<GeneratorEntity> pointers = Collections.newSetFromMap(new IdentityHashMap<>());
        Set<String> names = new HashSet<>();
        for (int at = 0; at < generators.size(); at++) {
            GeneratorEntity generator = generators.get(at);
            if (generator == null || generator.getModule() != module || generator.getData() == null
                    || !pointers.add(generator)) throw bad("foreign/duplicate generator or missing bytecode");
            String name = physical(generator.getCompiledInternalName());
            if (!names.add(name) || !name.equals(physical(module.getInternalName() + "_generator" + at)))
                throw bad("generator lexical physical identity mismatch");
            try {
                if (!ClassFile.of().parse(generator.getData()).thisClass().asInternalName().equals(name))
                    throw bad("generator classfile identity mismatch");
            } catch (IllegalArgumentException failure) { throw bad("invalid generator classfile"); }
        }
        return generators;
    }
    private static ModuleEntity resolve(ModuleEntity module, String name, ModuleResolver resolver) throws IOException {
        ModuleEntity donor = name.equals(module.getInternalName()) ? module : resolver.resolve(name);
        if (donor == null || !name.equals(donor.getInternalName())) throw bad("unresolved/wrong physical donor module");
        if (donor != module && !donor.hasValidatedGeneratorLinkage()) throw bad("donor linkage unavailable; recompile donor source");
        // The local cold artifact's ABI was checked at the restoration boundary; it remains unpublished.
        if (donor != module) compilerAbi(donor);
        return donor;
    }

    /** Attest real NEW, constant module/ordinal lookup and constructor owner in the exact declaration body. */
    private static Factory factory(Target target, ModuleEntity module, ModuleResolver resolver,
                                   Map<ModuleEntity,List<GeneratorEntity>> catalogs, Map<byte[],ClassModel> classes) throws IOException {
        // PHP interfaces have declaration metadata but no executable PHP method body.
        // Legacy interfaces have no JVM carrier at all; modern carriers omit their declared methods.
        // A missing carrier is allowed only for this exact non-executable interface representation.
        if (target.data == null || target.data.length == 0) {
            if (module.getPhpLanguageProfile() == PhpLanguageProfile.JPHP_LEGACY && interfaceDeclaration(target)) return NONE;
            throw bad("missing declaration bytecode");
        }
        try {
            ClassModel model = classes.get(target.data);
            if (model == null) { model = ClassFile.of().parse(target.data); classes.put(target.data,model); }
            if (!model.thisClass().asInternalName().equals(target.identity.physical)) throw bad("declaration classfile identity mismatch");
            List<MethodModel> methods = model.methods().stream().filter(method -> method.methodName().equalsString(target.identity.body)
                    && method.methodType().equalsString(METHOD)).toList();
            if (methods.isEmpty() && interfaceDeclaration(target)) return NONE;
            if (methods.size() != 1) throw bad("missing or ambiguous implementation method");
            MethodModel method = methods.getFirst();
            if (method.code().isEmpty()) {
                if (!(target.entity instanceof MethodEntity entity) || !entity.isAbstractable()) throw bad("missing method body");
                return NONE;
            }
            List<Instruction> code = new ArrayList<>();
            method.code().get().forEach(element -> { if (element instanceof Instruction instruction) code.add(instruction); });
            Factory found = NONE; int lookups = 0, constructors = 0;
            for (int at = 0; at < code.size(); at++) {
                if (!(code.get(at) instanceof InvokeInstruction invoke)) continue;
                if (invoke.name().equalsString("__getGenerator") && invoke.owner().asInternalName().equals("php/runtime/env/Environment")) {
                    if (++lookups != 1 || invoke.opcode() != Opcode.INVOKEVIRTUAL || !invoke.type().equalsString(LOOKUP) || at < 2
                            || !(code.get(at-2) instanceof ConstantInstruction owner) || !(owner.constantValue() instanceof String name)
                            || !(code.get(at-1) instanceof ConstantInstruction ordinal) || !(ordinal.constantValue() instanceof Integer index))
                        throw bad("invalid or multiple generator factories");
                    ModuleEntity donor = resolve(module,name,resolver);
                    List<GeneratorEntity> catalog = catalogs.get(donor);
                    if (catalog == null) { catalog = generators(donor); catalogs.put(donor,catalog); }
                    if (index < 0 || index >= catalog.size()) throw bad("generator factory ordinal out of bounds");
                    GeneratorEntity generator = catalog.get(index);
                    found = new Factory(name,index,physical(generator.getCompiledInternalName()),generator);
                }
                if (invoke.name().equalsString("<init>") && invoke.type().equalsString(CONSTRUCTOR)) {
                    if (++constructors != 1 || found.generator == null || invoke.opcode() != Opcode.INVOKESPECIAL
                            || !invoke.owner().asInternalName().equals(found.physical)) throw bad("generator factory constructor mismatch");
                }
            }
            if (found.generator == null) {
                if (lookups != 0 || constructors != 0) throw bad("incomplete generator factory");
                for (Instruction instruction : code) if (instruction instanceof NewObjectInstruction create)
                    for (GeneratorEntity local : catalogs.get(module))
                        if (create.className().asInternalName().equals(physical(local.getCompiledInternalName())))
                            throw bad("unlinked local generator allocation");
                return NONE;
            }
            int allocations = 0;
            for (Instruction instruction : code)
                if (instruction instanceof NewObjectInstruction create && create.className().asInternalName().equals(found.physical)) allocations++;
            if (allocations != 1 || constructors != 1) throw bad("incomplete generator factory allocation");
            if (target.entity.isReturnReference() != found.generator.isReturnReference()
                    || target.closure != null && target.closure.isReturnReference() != found.generator.isReturnReference())
                throw bad("generator reference metadata disagrees with declaration");
            if(target.entity instanceof MethodEntity hookMethod) PhpPropertyHookDumper.validateGenerator(hookMethod,found.generator);
            return found;
        } catch (IllegalArgumentException failure) { throw bad("invalid declaration classfile"); }
    }
    private static void identity(DataOutputStream output, Identity identity) throws IOException {
        output.writeByte(identity.kind); output.writeUTF(identity.physical); output.writeUTF(identity.phpName); output.writeUTF(identity.body);
    }
    private static Identity identity(DataInputStream input) throws IOException {
        return new Identity(input.readUnsignedByte(),input.readUTF(),input.readUTF(),input.readUTF());
    }
    public static byte[] save(ModuleEntity module) throws IOException { return save(module, name -> null); }
    public static byte[] save(ModuleEntity module, ModuleResolver resolver) throws IOException {
        Objects.requireNonNull(resolver,"resolver");
        if (!module.hasValidatedGeneratorLinkage()) throw bad("declaration linkage unavailable; source recompilation required");
        moduleBytecode(module);
        Map<Identity,Target> targets = targets(module); List<GeneratorEntity> generators = generators(module);
        Map<ModuleEntity,List<GeneratorEntity>> catalogs = new IdentityHashMap<>(); catalogs.put(module,generators);
        Map<byte[],ClassModel> classes = new IdentityHashMap<>();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); DataOutputStream output = new DataOutputStream(bytes);
        output.writeInt(STAMP); output.writeInt(VERSION); output.writeUTF(module.getInternalName());
        output.writeInt(compilerAbi(module)); output.writeInt(module.getPhpLanguageProfile().getId());
        output.writeInt(generators.size());
        for (GeneratorEntity generator : generators) output.writeUTF(physical(generator.getCompiledInternalName()));
        output.writeInt(targets.size());
        for (Target target : targets.values()) {
            Factory factory = factory(target,module,resolver,catalogs,classes);
            if (target.entity.getGeneratorEntity() != factory.generator || target.closure != null && target.closure.getGeneratorEntity() != factory.generator)
                throw bad("source generator pointer disagrees with actual factory");
            identity(output,target.identity); output.writeUTF(factory.module); output.writeInt(factory.ordinal); output.writeUTF(factory.physical);
            if (bytes.size() > MAX_BYTES) throw bad("component size limit exceeded");
        }
        output.flush(); if (bytes.size() > MAX_BYTES) throw bad("component size limit exceeded"); return bytes.toByteArray();
    }
    public static void restore(ModuleEntity module, byte[] component) throws IOException { restore(module,component,name -> null); }

    /** All-or-nothing metadata restoration; publish the validation flag last, after every factory is attested. */
    public static void restore(ModuleEntity module, byte[] component, ModuleResolver resolver) throws IOException {
        restoreWithCompilerAbi(module,component,resolver,compilerAbi(module));
    }

    /** Only the cold module reader may supply its already validated, still unpublished lineage. */
    static void restoreDecoded(ModuleEntity module, byte[] component, ModuleResolver resolver,
                               int originalCompilerAbi, int acceptedDumpAbi) throws IOException {
        if (acceptedDumpAbi < 20 || acceptedDumpAbi > ModuleDumper.COMPILER_ABI_VERSION || originalCompilerAbi < 1
                || originalCompilerAbi > acceptedDumpAbi
                || module.getCompilerProvenance().getOrigin() != ModuleEntity.CompilerOrigin.UNKNOWN)
            throw bad("invalid unpublished compiler lineage");
        restoreWithCompilerAbi(module,component,resolver,originalCompilerAbi);
    }

    private static void restoreWithCompilerAbi(ModuleEntity module, byte[] component, ModuleResolver resolver,
                                               int originalCompilerAbi) throws IOException {
        Objects.requireNonNull(resolver,"resolver");
        if (!module.isCompiledArtifact() || module.hasValidatedGeneratorLinkage() || module.isLoaded()) throw bad("not an unpublished unrestored compiled artifact");
        if (component == null || component.length > MAX_BYTES) throw bad("component size limit exceeded");
        moduleBytecode(module);
        Map<Identity,Target> targets = targets(module); List<GeneratorEntity> generators = generators(module);
        Map<ModuleEntity,List<GeneratorEntity>> catalogs = new IdentityHashMap<>(); catalogs.put(module,generators);
        Map<byte[],ClassModel> classes = new IdentityHashMap<>();
        DataInputStream input = new DataInputStream(new ByteArrayInputStream(component));
        if (input.readInt() != STAMP || input.readInt() != VERSION) throw bad("component stamp/version mismatch");
        if (!input.readUTF().equals(module.getInternalName())) throw bad("module identity mismatch");
        if (input.readInt() != originalCompilerAbi || input.readInt() != module.getPhpLanguageProfile().getId())
            throw bad("compiler ABI/language profile mismatch");
        if (input.readInt() != generators.size()) throw bad("generator catalog count mismatch");
        for (GeneratorEntity generator : generators)
            if (!input.readUTF().equals(physical(generator.getCompiledInternalName()))) throw bad("generator catalog identity mismatch");
        if (input.readInt() != targets.size()) throw bad("declaration catalog count mismatch");
        List<Link> links = new ArrayList<>(); Set<Identity> seen = new HashSet<>();
        for (int at = 0; at < targets.size(); at++) {
            Identity identity = identity(input); Target target = targets.get(identity);
            if (target == null || !seen.add(identity)) throw bad("unknown/duplicate declaration identity");
            String donor = input.readUTF(); int ordinal = input.readInt(); String generatorName = input.readUTF();
            Factory actual = factory(target,module,resolver,catalogs,classes);
            if (!donor.equals(actual.module) || ordinal != actual.ordinal || !generatorName.equals(actual.physical))
                throw bad("generator link disagrees with actual physical factory");
            if (target.entity.getGeneratorEntity() != null || target.closure != null && target.closure.getGeneratorEntity() != null)
                throw bad("partially populated compiled metadata");
            links.add(new Link(target,actual.generator));
        }
        if (seen.size() != targets.size() || input.read() != -1) throw bad("incomplete catalog or trailing bytes");
        synchronized (module) {
            if (module.hasValidatedGeneratorLinkage() || module.isLoaded()) throw bad("generator linkage already restored/published");
            for (Link link : links)
                if (link.target.entity.getGeneratorEntity() != null || link.target.closure != null && link.target.closure.getGeneratorEntity() != null)
                    throw bad("concurrent metadata population");
            for (Link link : links) {
                link.target.entity.setGeneratorEntity(link.generator);
                if (link.target.closure != null) link.target.closure.setGeneratorEntity(link.generator);
            }
            module.recordGeneratorLinkageValidated();
        }
    }
}