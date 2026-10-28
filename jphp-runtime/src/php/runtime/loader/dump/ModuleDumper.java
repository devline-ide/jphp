package php.runtime.loader.dump;

import php.runtime.common.LangMode;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.common.PhpOptimizationProfile;
import php.runtime.common.PhpRuntimeOwnershipMode;
import php.runtime.env.Context;
import php.runtime.env.Environment;
import php.runtime.loader.dump.io.DumpException;
import php.runtime.loader.dump.io.DumpInputStream;
import php.runtime.loader.dump.io.DumpOutputStream;
import php.runtime.reflection.ClassEntity;
import php.runtime.reflection.ConstantEntity;
import php.runtime.reflection.FunctionEntity;
import php.runtime.reflection.ModuleEntity;
import php.runtime.reflection.helper.ClosureEntity;
import php.runtime.reflection.helper.GeneratorEntity;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public class ModuleDumper extends Dumper<ModuleEntity> {
    public final static int DUMP_STAMP = 479873682;
    public final static int LEGACY_DUMP_VERSION = 20141011;
    public final static int DUMP_VERSION = 20261007;
    // ABI24 bytecode may require PhpUnwindSignal in native-finally exception tables.
    // ABI26 emits compact explicit-capture reads and their actual owned producer operation.
    // ABI27 adds the explicit constant-only parameter initializer kind (PDIN3).
    // ABI28 emits the explicit legacy VALUE-copy helper; modern copies preserve independent cursors.
    // ABI29 preserves Deprecated invocation effects and lexical trait-use publication ordering.
    // ABI30 records the numeric optimization contract in the header and generated entry context.
    // ABI31 stores the generated ownership contract; older artifacts retain AUTO.
    // ABI33 records opt-in numeric-array lowering and admits its pure instance entries.
    public final static int COMPILER_ABI_VERSION = 33;
    public final static int COMPILER_PROVENANCE_STAMP = 0x43414249; // CABI, in the existing reserved module tail.
    public final static int COMPILER_PROVENANCE_VERSION = 1;

    private static final int GENERATOR_LINKAGE_FRAME_STAMP = 0x474c4e4b;
    private static final int GENERATOR_LINKAGE_FRAME_VERSION = 1;
    private static final int GENERATOR_LINKAGE_MAX_BYTES = 16 * 1024 * 1024;

    private static final int DECLARATION_CATALOG_STAMP = 0x44434C53; // DCLS
    private static final int DECLARATION_CATALOG_VERSION = 1;

    public static final class Header {
        private final PhpLanguageProfile profile;
        private final int compilerAbiVersion;
        private final PhpOptimizationProfile optimizationProfile;
        private final PhpRuntimeOwnershipMode ownershipMode;
        private final boolean arrayOptimization;

        private Header(PhpLanguageProfile profile, int compilerAbiVersion, PhpOptimizationProfile optimizationProfile, PhpRuntimeOwnershipMode ownershipMode, boolean arrayOptimization) {
            this.arrayOptimization = arrayOptimization;
            this.profile = profile;
            this.compilerAbiVersion = compilerAbiVersion;
            this.optimizationProfile = optimizationProfile;
            this.ownershipMode = ownershipMode;
        }

        public PhpLanguageProfile getPhpLanguageProfile() { return profile; }
        /** Zero denotes the original header without an explicit ABI, not unknown provenance. */
        public int getCompilerAbiVersion() { return compilerAbiVersion; }
        public PhpOptimizationProfile getOptimizationProfile() { return optimizationProfile; }
        public PhpRuntimeOwnershipMode getOwnershipMode() { return ownershipMode; }
        public boolean isArrayOptimizationEnabled() { return arrayOptimization; }
    }

    protected ConstantDumper constantDumper = new ConstantDumper(context, env, debugInformation);
    protected ClosureDumper closureDumper = new ClosureDumper(context, env, debugInformation);
    protected FunctionDumper functionDumper = new FunctionDumper(context, env, debugInformation);
    protected ClassDumper classDumper = new ClassDumper(context, null, env, debugInformation);
    protected GeneratorDumper generatorDumper = new GeneratorDumper(context, env, debugInformation);


    public ModuleDumper(Context context, Environment env, boolean debugInformation) {
        super(context, env, debugInformation);
    }

    @Override
    public void setIncludeData(boolean includeData) {
        super.setIncludeData(includeData);
        constantDumper.setIncludeData(includeData);
        closureDumper.setIncludeData(includeData);
        functionDumper.setIncludeData(includeData);
        classDumper.setIncludeData(includeData);
        generatorDumper.setIncludeData(includeData);
    }

    @Override
    public int getType() {
        return -1;
    }

    @Override
    public void save(ModuleEntity entity, OutputStream output) throws IOException {
        if (entity.getData() == null)
            throw new DumpException("Module '" + entity.getName() + "' not compiled");

        PhpPropertyHookDumper.validateModule(entity, includeData,env);
        byte[] attributes=PhpAttributeDumper.save(entity,includeData);
        int compilerAbi = entity.getCompilerProvenance().getCompilerAbiVersion();
        PhpAttributeDumper.requireDeprecatedCompilerAbi(entity,compilerAbi);
        if (compilerAbi > COMPILER_ABI_VERSION)
            throw new DumpException("Unsupported original compiler ABI: " + compilerAbi);
        if (entity.getOwnershipMode() != PhpRuntimeOwnershipMode.AUTO && compilerAbi < 31)
            throw new DumpException("Ownership mode requires original ABI31 provenance");
        if (entity.getOptimizationProfile() != PhpOptimizationProfile.COMPATIBLE
                && (compilerAbi < 30 || entity.getPhpLanguageProfile() != PhpLanguageProfile.PHP_8_5))
            throw new DumpException("Fast numeric module requires original ABI30 and PHP_8_5 semantics");

        if (entity.isArrayOptimizationEnabled() && (compilerAbi < 33 || entity.getPhpLanguageProfile() != PhpLanguageProfile.PHP_8_5))
            throw new DumpException("Numeric array optimization requires ABI33 and PHP_8_5");

        // Attest before emitting transport bytes. Unknown/historical metadata remains explicitly unavailable.
        byte[] generatorLinkage = includeData && entity.hasValidatedGeneratorLinkage()
                ? PhpGeneratorLinkageDumper.save(entity, this::resolveGeneratorDonor) : null;

        DumpOutputStream data = new DumpOutputStream(output, true);

        // version
        data.writeInt(DUMP_STAMP);
        data.writeInt(DUMP_VERSION);
        data.writeInt(COMPILER_ABI_VERSION);
        data.writeInt(entity.getPhpLanguageProfile().getId());
        data.writeInt(entity.getOptimizationProfile().getId());
        data.writeInt(entity.getOwnershipMode().getId());
        data.writeBoolean(entity.isArrayOptimizationEnabled());

        // legacy code.
        data.writeEnum(LangMode.DEFAULT);

        // module name
        data.writeName(entity.getContext().getModuleName());
        data.writeName(entity.getInternalName());

        // strict types.
        data.writeBoolean(entity.isStrictTypes());

        // trace
        data.writeTrace(entity.getTrace());

        // constants
        data.writeInt(entity.getConstants().size());
        for(ConstantEntity e : entity.getConstants()){
            constantDumper.save(e, data);
        }

        // ABI20 declaration-only records; never registered as constants before module execution.
        data.writeInt(entity.getDeferredGlobalConstantDeclarations().size());
        for(ConstantEntity declaration:entity.getDeferredGlobalConstantDeclarations()) {
            if(declaration.getModule()!=entity || declaration.getValue()!=null || declaration.getClazz()!=null)
                throw new DumpException("Invalid deferred global constant declaration");
            constantDumper.save(declaration,data);
        }
        // closures
        data.writeInt(entity.getClosures().size());
        for(ClosureEntity e : entity.getClosures()){
            closureDumper.save(e, data);
        }

        // generators
        data.writeInt(entity.getGenerators().size());
        for(GeneratorEntity e : entity.getGenerators()){
            generatorDumper.save(e, data);
        }

        // functions
        data.writeInt(entity.getFunctions().size());
        for(FunctionEntity e : entity.getFunctions()){
            functionDumper.save(e, data);
        }

        // classes
        data.writeInt(entity.getClasses().size());
        for(ClassEntity e : entity.getClasses()){
            classDumper.save(e, data);
        }

        data.writeInt(DECLARATION_CATALOG_STAMP);
        data.writeInt(DECLARATION_CATALOG_VERSION);
        data.writeInt(entity.getDeferredClasses().size());
        int declarationId = 0;
        for (ClassEntity declaration : entity.getDeferredClasses()) {
            if (declaration.getDeclarationId() != declarationId++ || declaration.getModule() != entity
                    || declaration.isStatic() || declaration.getNativeClass() != null)
                throw new DumpException("Invalid deferred class declaration catalog");
            classDumper.save(declaration, data);
        }

        if (includeData) {
            // byte code
            data.writeInt(entity.getData().length);
            data.write(entity.getData());
        } else {
            data.writeInt(0);
        }

        // Current source ABI is already represented exactly by the header; retain its original empty tail.
        if (compilerAbi == COMPILER_ABI_VERSION) {
            data.writeRawData(null, 1024 * 1024 * 5);
            writeGeneratorLinkage(data, generatorLinkage);
            data.writeRawData(attributes,PhpAttributeDumper.MAX_BYTES);
            return;
        }
        // Keep the transport ABI/layout unchanged, without certifying old or manually supplied bytecode.
        ByteArrayOutputStream metadata = new ByteArrayOutputStream();
        DumpOutputStream provenance = new DumpOutputStream(metadata);
        provenance.writeInt(COMPILER_PROVENANCE_STAMP);
        provenance.writeInt(COMPILER_PROVENANCE_VERSION);
        provenance.writeInt(1); // Original compiler ABI field count.
        provenance.writeInt(compilerAbi);
        data.writeRawData(metadata.toByteArray(), 1024 * 1024 * 5); // Existing reserved field, consumed by older readers.
        writeGeneratorLinkage(data, generatorLinkage);
        data.writeRawData(attributes,PhpAttributeDumper.MAX_BYTES);
    }

    private ModuleEntity resolveGeneratorDonor(String physicalName) {
        return env == null ? null : env.getScope().moduleIndexMap.get(physicalName);
    }

    private static void writeGeneratorLinkage(DumpOutputStream data, byte[] linkage) throws IOException {
        data.writeInt(GENERATOR_LINKAGE_FRAME_STAMP);
        data.writeInt(GENERATOR_LINKAGE_FRAME_VERSION);
        data.writeRawData(linkage, GENERATOR_LINKAGE_MAX_BYTES);
    }

    /** Reads and validates only the header; no class dependency resolution or provenance certification. */
    public static Header readHeader(InputStream input) throws IOException {
        DumpInputStream data = new DumpInputStream(input);
        int STAMP = data.readInt();
        if (STAMP != DUMP_STAMP)
            throw new DumpException("Invalid file format");

        int VERSION = data.readInt();
        if (VERSION != DUMP_VERSION && VERSION != LEGACY_DUMP_VERSION)
            throw new DumpException("Unsupported JPHP dump version: " + VERSION);

        PhpLanguageProfile profile = PhpLanguageProfile.JPHP_LEGACY;
        PhpOptimizationProfile optimizationProfile = PhpOptimizationProfile.COMPATIBLE;
        PhpRuntimeOwnershipMode ownershipMode = PhpRuntimeOwnershipMode.AUTO;
        boolean arrayOptimization = false;
        int abi = 0;
        if (VERSION == DUMP_VERSION) {
            abi = data.readInt();
            if (abi < 1 || abi > COMPILER_ABI_VERSION)
                throw new DumpException("Unsupported JPHP compiler ABI: " + abi);
            try {
                profile = PhpLanguageProfile.fromId(data.readInt());
                if (abi >= 30) optimizationProfile = PhpOptimizationProfile.fromId(data.readInt());
                if (abi >= 31) ownershipMode = PhpRuntimeOwnershipMode.fromId(data.readInt());
                if (abi >= 33) arrayOptimization = data.readBoolean();
            } catch (IllegalArgumentException e) {
                throw new DumpException(e.getMessage());
            }
        }

        if (optimizationProfile != PhpOptimizationProfile.COMPATIBLE && profile != PhpLanguageProfile.PHP_8_5)
            throw new DumpException("Fast numeric optimization requires PHP_8_5 language semantics");
        if (arrayOptimization && profile != PhpLanguageProfile.PHP_8_5)
            throw new DumpException("Numeric array optimization requires PHP_8_5");
        return new Header(profile, abi, optimizationProfile, ownershipMode, arrayOptimization);
    }

    public static PhpLanguageProfile readLanguageProfileHeader(InputStream input) throws IOException {
        return readHeader(input).getPhpLanguageProfile();
    }

    private static int readCompilerProvenance(byte[] metadata, int acceptedAbi) throws IOException {
        if (metadata.length == 0) return acceptedAbi; // Original ABI0..7 dumps have no lineage extension.
        if (metadata.length < 4) return ModuleEntity.CompilerProvenance.UNKNOWN_ABI;
        DumpInputStream data = new DumpInputStream(new ByteArrayInputStream(metadata));
        if (data.readInt() != COMPILER_PROVENANCE_STAMP)
            return ModuleEntity.CompilerProvenance.UNKNOWN_ABI; // Unrelated historical reserved data is opaque.
        if (data.readInt() != COMPILER_PROVENANCE_VERSION)
            throw new DumpException("Unsupported compiler provenance version");
        if (data.readInt() != 1) throw new DumpException("Invalid compiler provenance field count");
        int abi = data.readInt();
        if (abi < ModuleEntity.CompilerProvenance.UNKNOWN_ABI || abi > acceptedAbi)
            throw new DumpException("Invalid original compiler ABI: " + abi);
        if (data.available() != 0) throw new DumpException("Invalid compiler provenance size");
        return abi;
    }

    @Override
    public ModuleEntity load(InputStream input) throws IOException {
        DumpInputStream data = new DumpInputStream(input);

        Header header = readHeader(data);
        data = new DumpInputStream(data, header.getCompilerAbiVersion() >= 17);
        PhpLanguageProfile profile = header.getPhpLanguageProfile();

        data.readLangMode(); // legacy

        ModuleEntity entity = new ModuleEntity(context);
        entity.setCompiledArtifact(true);
        entity.setPhpLanguageProfile(profile);
        entity.setOptimizationProfile(header.getOptimizationProfile());
        entity.setOwnershipMode(header.getOwnershipMode());
        entity.setArrayOptimizationEnabled(header.isArrayOptimizationEnabled());
        entity.setName(data.readName());
        entity.setInternalName(data.readName());
        entity.setStrictTypes(data.readBoolean());
        entity.setTrace(data.readTrace(context));

        boolean cold=header.getCompilerAbiVersion()>=20;
        GeneratorDumper generatorDumper=new GeneratorDumper(context,env,debugInformation);
        generatorDumper.setDeferLinking(cold);
        // constants
        int count = data.readInt();
        for(int i = 0; i < count; i++){
            ConstantEntity el = constantDumper.load(data);
            el.setModule(entity);
            entity.addConstant(el);
        }

        if(cold) {
            count=data.readInt();
            if(count<0 || count>65535) throw new DumpException("Invalid deferred global constant count");
            java.util.Set<String> names=new java.util.HashSet<>();
            for(ConstantEntity constant:entity.getConstants()) names.add(constant.getName());
            for(int i=0;i<count;i++) {
                ConstantEntity declaration=constantDumper.load(data);declaration.setModule(entity);
                if(declaration.getValue()!=null || declaration.getClazz()!=null || declaration.getInitializerMethod()!=null
                        || declaration.getName()==null || !names.add(declaration.getName()))
                    throw new DumpException("Invalid deferred global constant declaration");
                entity.addDeferredGlobalConstantDeclaration(declaration);
            }
        }
        // closures
        count = data.readInt();
        for(int i = 0; i < count; i++){
            ClosureEntity el = closureDumper.load(data, header.getCompilerAbiVersion());
            el.setModule(entity);
            entity.addClosure(el);
        }

        count = data.readInt();
        for(int i = 0; i < count; i++){
            GeneratorEntity el = generatorDumper.load(data);
            el.setModule(entity);
            entity.addGenerator(el);
        }

        // functions
        count = data.readInt();
        for(int i = 0; i < count; i++){
            FunctionEntity el = functionDumper.load(data);
            el.setModule(entity);
            entity.addFunction(el);
        }

        // classes
        count = data.readInt();
        ClassDumper classDumper = new ClassDumper(context, entity, env, debugInformation);
        classDumper.setDeferLinking(cold);
        classDumper.setAllowPhpHooks(header.getCompilerAbiVersion()>=22);
        classDumper.setAllowPhpSet(header.getCompilerAbiVersion()>=23);
        for(int i = 0; i < count; i++){
            ClassEntity el = classDumper.load(data);
            el.setModule(entity);
            entity.addClass(el);
        }

        if (header.getCompilerAbiVersion() >= 16) {
            if (data.readInt() != DECLARATION_CATALOG_STAMP || data.readInt() != DECLARATION_CATALOG_VERSION)
                throw new DumpException("Unsupported deferred class declaration catalog");
            count = data.readInt();
            if (count < 0 || count > 1000000) throw new DumpException("Invalid deferred class declaration count");
            java.util.Set<String> physicalNames = new java.util.HashSet<>();
            for (ClassEntity ordinary : entity.getClasses()) {
                if (ordinary.isDeferredDeclaration()) throw new DumpException("Deferred declaration in static class map");
                physicalNames.add(ordinary.getCompiledInternalName());
            }
            for (int i = 0; i < count; i++) {
                ClassEntity declaration = classDumper.load(data);
                if (declaration.getDeclarationId() != i || declaration.isStatic()
                        || !physicalNames.add(declaration.getCompiledInternalName())
                        || declaration.getCompiledInternalName() == null
                        || !declaration.getCompiledInternalName().equals(entity.getInternalName() + "_declaration" + i))
                    throw new DumpException("Invalid deferred class declaration identity");
                declaration.setModule(entity);
                entity.addDeferredClass(declaration);
            }
        }

        // byte code
        entity.setData(data.readRawData(Integer.MAX_VALUE));

        int compilerAbi = readCompilerProvenance(data.readRawData(1024 * 1024 * 5), header.getCompilerAbiVersion());
        if (entity.isArrayOptimizationEnabled() && compilerAbi < 33)
            throw new DumpException("Array optimization lacks original ABI33 provenance");
        if (entity.getOwnershipMode() != PhpRuntimeOwnershipMode.AUTO && compilerAbi < 31)
            throw new DumpException("Ownership mode lacks original ABI31 provenance");
        if (entity.getOptimizationProfile() != PhpOptimizationProfile.COMPATIBLE && compilerAbi < 30)
            throw new DumpException("Fast numeric module lacks original ABI30 provenance");
        if (!cold) entity.recordDumpCompilerAbi(compilerAbi, header.getCompilerAbiVersion());
        byte[] deferredLinkage=null;
        if (header.getCompilerAbiVersion() >= 17) {
            if (data.readInt() != GENERATOR_LINKAGE_FRAME_STAMP || data.readInt() != GENERATOR_LINKAGE_FRAME_VERSION)
                throw new DumpException("Unsupported generator linkage frame");
            byte[] linkage = data.readRawData(GENERATOR_LINKAGE_MAX_BYTES);
            // Empty frames do not certify historical, unknown or metadata-only declarations.
            if(cold) deferredLinkage=linkage;
            else if (linkage.length != 0) PhpGeneratorLinkageDumper.restore(entity, linkage, this::resolveGeneratorDonor);
        }
        if(cold) {
            PhpAttributeDumper.restore(entity,data.readRawData(PhpAttributeDumper.MAX_BYTES),context);
            if(data.read()!=-1) throw new DumpException("Trailing compiled module data");
            PhpAttributeDumper.requireDeprecatedCompilerAbi(entity,compilerAbi);
            // All declaration/helper bytes and complete metadata were validated before any relation provider.
            if(deferredLinkage!=null && deferredLinkage.length!=0)
                PhpGeneratorLinkageDumper.restoreDecoded(entity,deferredLinkage,this::resolveGeneratorDonor,compilerAbi,header.getCompilerAbiVersion());
            for(ClosureEntity closure:entity.getClosures()) {
                closure.setParent(env.scope.fetchUserClass(php.runtime.lang.Closure.class));closure.doneDeclare();
            }
            generatorDumper.linkDecodedDeclarations();classDumper.linkDecodedDeclarations();
        }
        entity.validateMethodSignatures(env);
        if (cold) entity.recordDumpCompilerAbi(compilerAbi, header.getCompilerAbiVersion());
        return entity;
    }
}
