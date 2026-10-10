package php.runtime.reflection;

import php.runtime.common.PhpClassNames;

import php.runtime.Memory;
import php.runtime.common.LangMode;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.Context;
import php.runtime.env.DieException;
import php.runtime.env.Environment;
import php.runtime.exceptions.support.ErrorException;
import php.runtime.lang.BaseException;
import php.runtime.memory.ArrayMemory;
import php.runtime.reflection.helper.ClosureEntity;
import php.runtime.reflection.helper.GeneratorEntity;
import php.runtime.reflection.support.Entity;
import php.runtime.reflection.support.PhpMethodCompatibility;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.*;

public class ModuleEntity extends Entity {
    private int id;
    private Class<?> nativeClazz;
    protected Method nativeMethod;

    private boolean strictTypes = false;
    private PhpLanguageProfile phpLanguageProfile = PhpLanguageProfile.JPHP_LEGACY;
    private boolean compiledArtifact;
    private volatile CompilerProvenance compilerProvenance = CompilerProvenance.UNKNOWN;

    public enum CompilerOrigin { UNKNOWN, SOURCE, DUMP }

    /** Compiler lineage is distinct from the language profile and the latest transport header. */
    public static final class CompilerProvenance {
        public static final int UNKNOWN_ABI = -1;
        private static final CompilerProvenance UNKNOWN = new CompilerProvenance(CompilerOrigin.UNKNOWN, UNKNOWN_ABI, UNKNOWN_ABI);
        private final CompilerOrigin origin;
        private final int compilerAbiVersion;
        private final int acceptedDumpAbiVersion;

        private CompilerProvenance(CompilerOrigin origin, int compilerAbiVersion, int acceptedDumpAbiVersion) {
            this.origin = origin;
            this.compilerAbiVersion = compilerAbiVersion;
            this.acceptedDumpAbiVersion = acceptedDumpAbiVersion;
        }

        public CompilerOrigin getOrigin() { return origin; }
        public boolean hasKnownCompilerAbi() { return compilerAbiVersion != UNKNOWN_ABI; }
        public int getCompilerAbiVersion() { return compilerAbiVersion; }
        public int getAcceptedDumpAbiVersion() { return acceptedDumpAbiVersion; }
    }

    private final Map<String, ClassEntity> classes;
    private final List<FunctionEntity> functions;
    private final Map<String, ConstantEntity> constants;
    private final List<ClosureEntity> closures;
    private final List<GeneratorEntity> generators;

    protected boolean isLoaded;

    private final static Memory[] argsMock = new Memory[]{};

    public ModuleEntity(Context context) {
        super(context);
        this.classes = new LinkedHashMap<String, ClassEntity>();
        this.functions = new ArrayList<FunctionEntity>();
        this.constants = new LinkedHashMap<String, ConstantEntity>();
        this.closures = new ArrayList<ClosureEntity>();
        this.generators = new ArrayList<GeneratorEntity>();
        this.setName(context.getModuleNameNoThrow());
    }

    public int getId() {
        return id;
    }

    public void validateMethodSignatures(Environment env) {
        if (phpLanguageProfile == PhpLanguageProfile.PHP_8_5) {
            for (ClassEntity clazz : classes.values()) clazz.validateConstantInheritance(env);
            php.runtime.reflection.support.PhpConstantTypes.validate(this, env);
            php.runtime.reflection.support.PhpPropertyTypes.validate(this, env);
            PhpMethodCompatibility.validate(this, env);
        }
    }

    public void setId(int id) {
        this.id = id;
    }

    public Method getNativeMethod() {
        return nativeMethod;
    }

    public void setNativeMethod(Method nativeMethod) {
        this.nativeMethod = nativeMethod;
    }

    public Memory include(Environment env, ArrayMemory locals) throws Throwable {
        env.getModuleManager().markIncluded(context.getFileName());
        try {
            return (Memory) nativeMethod.invoke(null, env, argsMock, locals);
        } catch (InvocationTargetException e){
            return env.__throwException(e);
        }
    }

    public Memory include(Environment env) throws Throwable {
        env.getModuleManager().markIncluded(context.getFileName());
        try {
            return (Memory) nativeMethod.invoke(null, env, argsMock, env.getGlobals());
        } catch (InvocationTargetException e){
            return env.__throwException(e);
        }
    }

    public Memory includeNoThrow(Environment env, ArrayMemory locals){
        try {
            return include(env, locals);
        } catch (DieException | ErrorException e) {
            throw e;
        } catch (Exception e) {
            env.catchUncaught(e);
            return Memory.NULL;
        } catch (Throwable throwable) {
            throw new RuntimeException(throwable);
        }
    }

    public Memory includeNoThrow(Environment env){
        return includeNoThrow(env, env.getGlobals());
    }

    public boolean isLoaded() {
        return isLoaded;
    }

    public CompilerProvenance getCompilerProvenance() { return compilerProvenance; }

    /** Record only after source compilation and final validation have succeeded. */
    public synchronized void recordSourceCompilerAbi(int compilerAbiVersion) {
        if (compilerAbiVersion < 1) throw new IllegalArgumentException("Invalid source compiler ABI");
        recordCompilerProvenance(new CompilerProvenance(CompilerOrigin.SOURCE, compilerAbiVersion,
                CompilerProvenance.UNKNOWN_ABI));
    }

    /** Record only after the entire artifact and its lineage metadata have been validated. */
    public synchronized void recordDumpCompilerAbi(int compilerAbiVersion, int acceptedDumpAbiVersion) {
        if (acceptedDumpAbiVersion < 0 || compilerAbiVersion < CompilerProvenance.UNKNOWN_ABI
                || compilerAbiVersion > acceptedDumpAbiVersion)
            throw new IllegalArgumentException("Invalid dump compiler ABI provenance");
        recordCompilerProvenance(new CompilerProvenance(CompilerOrigin.DUMP, compilerAbiVersion, acceptedDumpAbiVersion));
    }

    private void recordCompilerProvenance(CompilerProvenance provenance) {
        if (compilerProvenance != CompilerProvenance.UNKNOWN)
            throw new IllegalStateException("Compiler provenance is immutable");
        compilerProvenance = provenance;
    }

    public boolean isCompiledArtifact() { return compiledArtifact; }
    public void setCompiledArtifact(boolean compiledArtifact) { this.compiledArtifact = compiledArtifact; }

    public PhpLanguageProfile getPhpLanguageProfile() {
        return phpLanguageProfile;
    }

    public void setPhpLanguageProfile(PhpLanguageProfile profile) {
        phpLanguageProfile = Objects.requireNonNull(profile, "profile");
    }

    public void setLoaded(boolean loaded) {
        isLoaded = loaded;
    }

    public Collection<ConstantEntity> getConstants(){
        return constants.values();
    }

    public Collection<ClassEntity> getClasses(){
        return classes.values();
    }

    public Collection<ClosureEntity> getClosures(){
        return closures;
    }

    public Collection<GeneratorEntity> getGenerators() {
        return generators;
    }

    public Collection<FunctionEntity> getFunctions(){
        return functions;
    }

    public ClassEntity findClass(String name){
        return classes.get(PhpClassNames.key(name, getPhpLanguageProfile()));
    }

    public FunctionEntity findFunction(String name) {
        name = name.toLowerCase();

        for (FunctionEntity function : functions) {
            if (function.getLowerName().equals(name)) {
                return function;
            }
        }

        return null;
    }

    public FunctionEntity findFunction(int index) {
        if (index >= 0 && index < functions.size())
            return functions.get(index);
        else
            return null;
    }

    public ConstantEntity findConstant(String name){
        return constants.get(name.toLowerCase());
    }

    public ClosureEntity findClosure(int index){
        if (index >= 0 && index < closures.size())
            return closures.get(index);
        else
            return null;
    }

    public GeneratorEntity findGenerator(int index){
        if (index >= 0 && index < generators.size())
            return generators.get(index);
        else
            return null;
    }

    public void addConstant(ConstantEntity constant){
        if (!constants.containsKey(constant.getName()))
            constants.put(constant.getName(), constant);
    }

    public void addClosure(ClosureEntity closure){
        closure.setModule(this);
        closures.add(closure);
        if (closure.getGeneratorEntity() != null) {
            addGenerator(closure.getGeneratorEntity());
        }
    }

    public void addGenerator(GeneratorEntity generator) {
        generator.setModule(this);
        generators.add(generator);
    }

    public void addClass(ClassEntity clazz){
        clazz.setModule(this);
        classes.put(clazz.getLowerName(), clazz);
        for (MethodEntity entity : clazz.getOwnedMethods()) {
            if (entity.getGeneratorEntity() != null) {
                addGenerator(entity.getGeneratorEntity());
            }
        }
    }

    public void addFunction(FunctionEntity function){
        function.setModule(this);
        functions.add(function);
        if (function.getGeneratorEntity() != null) {
            addGenerator(function.getGeneratorEntity());
        }
    }

    public Class<?> getNativeClazz() {
        return nativeClazz;
    }

    public void setNativeClazz(Class<?> nativeClazz) {
        this.nativeClazz = nativeClazz;
    }

    public boolean isStrictTypes() {
        return strictTypes;
    }

    public void setStrictTypes(boolean strictTypes) {
        this.strictTypes = strictTypes;
    }
}
