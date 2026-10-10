package php.runtime.loader.dump;

import php.runtime.common.LangMode;
import php.runtime.common.PhpLanguageProfile;
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
    public final static int COMPILER_ABI_VERSION = 9;
    public final static int COMPILER_PROVENANCE_STAMP = 0x43414249; // CABI, in the existing reserved module tail.
    public final static int COMPILER_PROVENANCE_VERSION = 1;

    public static final class Header {
        private final PhpLanguageProfile profile;
        private final int compilerAbiVersion;

        private Header(PhpLanguageProfile profile, int compilerAbiVersion) {
            this.profile = profile;
            this.compilerAbiVersion = compilerAbiVersion;
        }

        public PhpLanguageProfile getPhpLanguageProfile() { return profile; }
        /** Zero denotes the original header without an explicit ABI, not unknown provenance. */
        public int getCompilerAbiVersion() { return compilerAbiVersion; }
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

        int compilerAbi = entity.getCompilerProvenance().getCompilerAbiVersion();
        if (compilerAbi > COMPILER_ABI_VERSION)
            throw new DumpException("Unsupported original compiler ABI: " + compilerAbi);

        DumpOutputStream data = new DumpOutputStream(output);

        // version
        data.writeInt(DUMP_STAMP);
        data.writeInt(DUMP_VERSION);
        data.writeInt(COMPILER_ABI_VERSION);
        data.writeInt(entity.getPhpLanguageProfile().getId());

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
            constantDumper.save(e, output);
        }

        // closures
        data.writeInt(entity.getClosures().size());
        for(ClosureEntity e : entity.getClosures()){
            closureDumper.save(e, output);
        }

        // generators
        data.writeInt(entity.getGenerators().size());
        for(GeneratorEntity e : entity.getGenerators()){
            generatorDumper.save(e, output);
        }

        // functions
        data.writeInt(entity.getFunctions().size());
        for(FunctionEntity e : entity.getFunctions()){
            functionDumper.save(e, output);
        }

        // classes
        data.writeInt(entity.getClasses().size());
        for(ClassEntity e : entity.getClasses()){
            classDumper.save(e, output);
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
        int abi = 0;
        if (VERSION == DUMP_VERSION) {
            abi = data.readInt();
            if (abi < 1 || abi > COMPILER_ABI_VERSION)
                throw new DumpException("Unsupported JPHP compiler ABI: " + abi);
            try {
                profile = PhpLanguageProfile.fromId(data.readInt());
            } catch (IllegalArgumentException e) {
                throw new DumpException(e.getMessage());
            }
        }

        return new Header(profile, abi);
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

        Header header = readHeader(input);
        PhpLanguageProfile profile = header.getPhpLanguageProfile();

        data.readLangMode(); // legacy

        ModuleEntity entity = new ModuleEntity(context);
        entity.setCompiledArtifact(true);
        entity.setPhpLanguageProfile(profile);
        entity.setName(data.readName());
        entity.setInternalName(data.readName());
        entity.setStrictTypes(data.readBoolean());
        entity.setTrace(data.readTrace(context));

        // constants
        int count = data.readInt();
        for(int i = 0; i < count; i++){
            ConstantEntity el = constantDumper.load(input);
            el.setModule(entity);
            entity.addConstant(el);
        }

        // closures
        count = data.readInt();
        for(int i = 0; i < count; i++){
            ClosureEntity el = closureDumper.load(input, header.getCompilerAbiVersion());
            el.setModule(entity);
            entity.addClosure(el);
        }

        count = data.readInt();
        for(int i = 0; i < count; i++){
            GeneratorEntity el = generatorDumper.load(input);
            el.setModule(entity);
            entity.addGenerator(el);
        }

        // functions
        count = data.readInt();
        for(int i = 0; i < count; i++){
            FunctionEntity el = functionDumper.load(input);
            el.setModule(entity);
            entity.addFunction(el);
        }

        // classes
        count = data.readInt();
        ClassDumper classDumper = new ClassDumper(context, entity, env, debugInformation);
        for(int i = 0; i < count; i++){
            ClassEntity el = classDumper.load(input);
            el.setModule(entity);
            entity.addClass(el);
        }

        // byte code
        entity.setData(data.readRawData(Integer.MAX_VALUE));

        int compilerAbi = readCompilerProvenance(data.readRawData(1024 * 1024 * 5), header.getCompilerAbiVersion());
        entity.validateMethodSignatures(env);
        entity.recordDumpCompilerAbi(compilerAbi, header.getCompilerAbiVersion());
        return entity;
    }
}
