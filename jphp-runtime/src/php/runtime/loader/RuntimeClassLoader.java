package php.runtime.loader;

import php.runtime.Memory;
import php.runtime.common.Callback;
import php.runtime.env.Environment;
import php.runtime.memory.ArrayMemory;
import php.runtime.reflection.*;
import php.runtime.reflection.helper.ClosureEntity;
import php.runtime.reflection.helper.GeneratorEntity;

import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

public class RuntimeClassLoader extends URLClassLoader {
    protected Map<String, ClassEntity> internalClasses = new HashMap<String, ClassEntity>();
    protected Map<String, FunctionEntity> internalFunctions = new HashMap<String, FunctionEntity>();
    protected Map<String, ModuleEntity> internalModules = new HashMap<String, ModuleEntity>();
    private record LinkedIdentity(String templateInternal,String module,long declarationId,Class<?> parent,
                                  PhpJvmTraitMethodCopy.Plan composition,boolean interfaceCarrier) {
        @Override public boolean equals(Object other) {
            return other instanceof LinkedIdentity identity && templateInternal.equals(identity.templateInternal)
                    && module.equals(identity.module) && declarationId==identity.declarationId && parent==identity.parent
                    && interfaceCarrier==identity.interfaceCarrier
                    && java.util.Objects.equals(composition==null?null:composition.key(),
                    identity.composition==null?null:identity.composition.key());
        }
        @Override public int hashCode(){return java.util.Objects.hash(templateInternal,module,declarationId,parent,
                composition==null?null:composition.key(),interfaceCarrier);}
    }
    private record DefinedBytecode(byte[] inputHash,byte[] definitionHash,String definitionInternal,LinkedIdentity linked) {}
    private final Map<String, DefinedBytecode> definedBytecodeHashes = new HashMap<>();

    /** Purpose-neutral typed Source accessor uses the existing exact defined-input witness. */
    public final synchronized byte[] definedInputBytes(Class<?> actual) { return primitiveDefinedBytes(actual); }
    /** Original source/PHB bytes are returned only after the existing exact definition witness matches. */
    public final synchronized byte[] primitiveDefinedBytes(Class<?> actual) {
        if (actual==null || actual.getClassLoader()!=this || findLoadedClass(actual.getName())!=actual) return null;
        for (ClassEntity entity:internalClasses.values()) if (entity.getNativeClass()==actual && matchesDefinedBytecode(actual,entity.getData())) return entity.getData().clone();
        for (FunctionEntity entity:internalFunctions.values()) if (entity.getNativeMethod()!=null && entity.getNativeMethod().getDeclaringClass()==actual && matchesDefinedBytecode(actual,entity.getData())) return entity.getData().clone();
        for (ModuleEntity entity:internalModules.values()) if (entity.getNativeMethod()!=null && entity.getNativeMethod().getDeclaringClass()==actual && matchesDefinedBytecode(actual,entity.getData())) return entity.getData().clone();
        return null;
    }
    /** Bind original bytes to the exact self-renamed, parent-linked, finalizer-adapted JVM definition. */
    public final synchronized boolean matchesDefinedBytecode(Class<?> actual, byte[] data) {
        if (actual == null || data == null || actual.getClassLoader() != this
                || findLoadedClass(actual.getName()) != actual) return false;
        DefinedBytecode expected=definedBytecodeHashes.get(actual.getName());
        if (expected == null) return false;
        return MessageDigest.isEqual(expected.inputHash(),digest(data))
                && (expected.linked()==null || actual.getSuperclass()==expected.linked().parent())
                && MessageDigest.isEqual(expected.definitionHash(),digest(definition(data,expected.definitionInternal(),expected.linked())));
    }

    private static byte[] digest(byte[] bytes) {
        try {return MessageDigest.getInstance("SHA-256").digest(bytes);}
        catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
    private byte[] definition(byte[] data,String name,LinkedIdentity linked) {
        String parent=linked==null || linked.parent()==null ? null : linked.parent().getName().replace('.', '/');
        byte[] composed=linked==null || linked.composition()==null ? data
                : PhpJvmTraitMethodCopy.compose(data,linked.composition(),linked.parent(),this);
        byte[] bound=linked!=null && linked.interfaceCarrier()
                ? PhpJvmInheritanceBytecode.bindInterfaceCarrier(composed,parent,name)
                : PhpJvmInheritanceBytecode.bind(composed,parent,linked==null ? null : name);
        return PhpJvmFinalizerBytecode.rewrite(bound);
    }

    /** Reconstruct exact defined donor bytes; original PHP template metadata remains unchanged. */
    /** Read-only exact defined-byte bridge for cold source imports of already-linked trait hooks. */
    public final synchronized byte[] traitHookDefinition(ClassEntity donor) {
        if(donor==null || !donor.isTrait())throw new LinkageError("Hook body donor is not a trait");
        return traitDefinition(donor);
    }
    final synchronized byte[] traitDefinition(ClassEntity donor) {
        Class<?> actual=donor.getNativeClass();byte[] source=translateData(donor.getData());
        if(!matchesDefinedBytecode(actual,source))throw new LinkageError("Trait donor lacks its exact defined-byte witness");
        DefinedBytecode witness=definedBytecodeHashes.get(actual.getName());
        return definition(source,witness.definitionInternal(),witness.linked());
    }
    private static String linkedName(LinkedIdentity identity) {
        String key=identity.templateInternal()+"\0"+identity.module()+"\0"+identity.declarationId()+"\0"+identity.parent().getName()
                +(identity.composition()==null?"":"\0"+identity.composition().fingerprint())
                +(identity.interfaceCarrier()?"\0PHP_INTERFACE_CARRIER":"");
        return identity.templateInternal()+"$php$link$"+HexFormat.of().formatHex(digest(key.getBytes(StandardCharsets.UTF_8)));
    }
    private synchronized Class<?> defineOrReuse(String name,byte[] data) {return defineOrReuse(name,data,null);}
    private synchronized Class<?> defineOrReuse(String name,byte[] data,LinkedIdentity linked) {
        String binary=name.replace('/', '.');
        validateOwnershipLinkage(binary,data);
        byte[] hash=digest(data);
        if(linked!=null) {
            String actualTemplate=java.lang.classfile.ClassFile.of().parse(data).thisClass().asInternalName();
            if(!actualTemplate.equals(linked.templateInternal()))throw new LinkageError("Linked definition differs from its declaration template");
            if(linked.parent()==null)throw new LinkageError("Linked definition requires an actual physical parent");
            try {
                if(Class.forName(linked.parent().getName(),false,this)!=linked.parent())
                    throw new LinkageError("Deferred parent differs from the loader's resolved physical class");
            }catch(ClassNotFoundException failure){LinkageError error=new LinkageError("Deferred parent is not visible to this loader");error.initCause(failure);throw error;}
        }
        Class<?> existing=findLoadedClass(binary);
        if(existing!=null) {
            DefinedBytecode previous=definedBytecodeHashes.get(binary);
            if(previous==null || !MessageDigest.isEqual(hash,previous.inputHash())
                    || !java.util.Objects.equals(linked,previous.linked()) || !name.equals(previous.definitionInternal()))
                throw new LinkageError("Different declaration or bytecode already defined for "+binary);
            if(linked!=null && linked.composition()!=null && linked.composition().hasRuntimeHooks()) {
                byte[] verified=definition(data,name,linked);
                if(!MessageDigest.isEqual(previous.definitionHash(),digest(verified)))
                    throw new LinkageError("Cached trait hook definition differs from its fresh source witness");
                linked.composition().validateDefined(existing);
            }
            return existing;
        }
        byte[] transformed=definition(data,name,linked);
        if(linked!=null && linked.composition()!=null)validateOwnershipLinkage(binary,transformed);
        Class<?> defined=defineClass(binary,transformed,0,transformed.length);
        if(linked!=null && defined.getSuperclass()!=linked.parent())throw new LinkageError("Linked JVM class has the wrong physical parent");
        if(linked!=null && linked.composition()!=null)linked.composition().validateDefined(defined);
        definedBytecodeHashes.put(binary,new DefinedBytecode(hash,digest(transformed),name,linked));
        return defined;
    }

    private void validateOwnershipLinkage(String name, byte[] data) {
        if(findLoadedClass(name)!=null) {
            DefinedBytecode previous=definedBytecodeHashes.get(name);
            try {
                if(previous==null||data==null||!MessageDigest.isEqual(previous.inputHash(),MessageDigest.getInstance("SHA-256").digest(data)))
                    throw new LinkageError("Different bytecode already defined for "+name);
            } catch(NoSuchAlgorithmException impossible) {throw new IllegalStateException(impossible);}
        }
        try {PhpRuntimeOwnershipLinkage.requireCompatible(data);}
        catch (java.io.IOException failure) {LinkageError error=new LinkageError("Incompatible generated ownership helpers in "+name);error.initCause(failure);throw error;}
    }

    private void validateModuleOwnershipLinkage(ModuleEntity module) {
        for (ClosureEntity closure:module.getClosures())validateOwnershipLinkage(closure.getCompiledInternalName(),translateData(closure.getData()));
        for (GeneratorEntity generator:module.getGenerators())validateOwnershipLinkage(generator.getCompiledInternalName(),translateData(generator.getData()));
        for (ClassEntity clazz:module.getClasses())if(clazz.getData()!=null&&clazz.getData().length>0)validateOwnershipLinkage(clazz.getCompiledInternalName(),translateData(clazz.getData()));
        for(ClassEntity declaration:module.getDeferredClasses()) {
            if(!declaration.isDeclarationTemplate())throw new LinkageError("Deferred module catalog contains an already bound definition");
            if(declaration.getData()!=null && declaration.getData().length>0)
                validateOwnershipLinkage(declaration.getCompiledInternalName(),translateData(declaration.getData()));
        }
        for (FunctionEntity function:module.getFunctions())validateOwnershipLinkage(function.getInternalName(),translateData(function.getData()));
        validateOwnershipLinkage(module.getInternalName(),translateData(module.getData()));
    }

    protected List<Callback<Void, URL>> addLibraryListeners = new ArrayList<>();

    public RuntimeClassLoader() {
        this(Thread.currentThread().getContextClassLoader());
    }

    public RuntimeClassLoader(ClassLoader parent) {
        super(new URL[0], parent);
    }

    public ClassEntity getClass(String internalName) {
        return internalClasses.get(internalName);
    }

    public FunctionEntity getFunction(String internalName) {
        return internalFunctions.get(internalName);
    }

    public ModuleEntity getModule(String internalName) {
        return internalModules.get(internalName);
    }

    public Class<?> loadClass(ClassEntity clazz, boolean withBytecode) throws NoSuchMethodException, NoSuchFieldException {
        if (withBytecode) {
            byte[] data = translateData(clazz.getData());
            if(clazz.isDeclarationTemplate())throw new LinkageError("A deferred catalog template cannot be defined before declaration binding");
            if (clazz.isUnresolvedDeclaration()) throw new LinkageError("Unresolved PHP class cannot be defined in the JVM");
            LinkedIdentity linked=null;
            String definitionName=clazz.getCompiledInternalName();
            PhpTraitCompositionPlan composition=clazz.getClassComposition();
            if(clazz.getDeferredParentName()!=null || composition!=null) {
                boolean interfaceCarrier=clazz.getType()==ClassEntity.Type.INTERFACE;
                Class<?> parent=interfaceCarrier ? Object.class : clazz.getParent()==null ? null : clazz.getParent().getNativeClass();
                if(parent==null && clazz.getDeferredParentName()!=null)
                    throw new LinkageError("Deferred declaration parent lacks its physical JVM definition");
                if(parent==null) {
                    String parentName=java.lang.classfile.ClassFile.of().parse(data).superclass().orElseThrow().asInternalName().replace('/', '.');
                    try {parent=Class.forName(parentName,false,this);}
                    catch(ClassNotFoundException failure){throw new LinkageError("Composition template parent is not visible",failure);}
                }
                PhpJvmTraitMethodCopy.Plan physical=composition==null?null:PhpJvmTraitMethodCopy.capture(this,clazz,composition);
                linked=new LinkedIdentity(definitionName.replace('.', '/'),clazz.getModule()==null ? "" : clazz.getModule().getInternalName(),clazz.getDeclarationId(),parent,physical,interfaceCarrier);
                definitionName=linkedName(linked);
            }
            Class<?> result = defineOrReuse(definitionName,data,linked);

            clazz.setNativeClazz(result);
        }

        for (MethodEntity method : clazz.getMethods().values()) {
            if (!(method instanceof CompileMethodEntity) && method.getNativeMethod() == null && !method.isAbstractable()) {
                method.setNativeMethod(
                        clazz.getNativeClass().getDeclaredMethod(method.getInternalName(), Environment.class, Memory[].class)
                );
                method.getNativeMethod().setAccessible(true);
            }
        }

        for(var property:clazz.getProperties()) {
            var hooks=property.getPhpHooks();
            if(hooks==null) continue;
            for(var hook:hooks.declaredOrder()) {
                var method=hook.method();
                if(hook.declaration().getClazz()!=clazz || method.getNativeMethod()!=null || method.isAbstractable()) continue;
                method.setNativeMethod(clazz.getNativeClass().getDeclaredMethod(method.getInternalName(),Environment.class,Memory[].class));
                method.getNativeMethod().setAccessible(true);
            }
        }

        // The template name is not a mutable alias to another Environment's linked hierarchy.
        internalClasses.put(clazz.getInternalName(), clazz);
        return clazz.getNativeClass();
    }

    protected Class<?> loadClosure(ClosureEntity closure, boolean withBytecode) throws NoSuchMethodException, NoSuchFieldException {
        return loadClass(closure, withBytecode);
    }

    protected Class<?> loadFunction(FunctionEntity function, boolean withBytecode) throws NoSuchMethodException {
        String className = function.getInternalName();
        if (withBytecode) {
            byte[] data = translateData(function.getData());

            Class<?> result = defineOrReuse(className, data);

            function.setNativeClazz(result);
        }

        Method method = function.getNativeClazz().getDeclaredMethod(
                "__invoke", Environment.class, Memory[].class
        );
        function.setNativeMethod(method);
        internalFunctions.put(className, function);

        return function.getNativeClazz();
    }

    protected Class<?> loadGenerator(GeneratorEntity generator, boolean withBytecode) throws NoSuchMethodException, NoSuchFieldException {
        return loadClass(generator, withBytecode);
    }

    public synchronized boolean loadModule(ModuleEntity module, boolean withBytecode) {
        if (withBytecode)validateModuleOwnershipLinkage(module);
        if (module.isLoaded() && module.getNativeMethod() != null) return false;
        String internal = module.getInternalName();

        boolean ret = false;
        if (!module.isLoaded()) {
            try {
                for (ClosureEntity closure : module.getClosures())
                    loadClosure(closure, withBytecode);

                for (GeneratorEntity generator : module.getGenerators())
                    loadGenerator(generator, withBytecode);

                for (ClassEntity clazz : module.getClasses()) {
                    if (!clazz.isDeclarationTemplate() && !clazz.isUnresolvedDeclaration() && (clazz.getType() != ClassEntity.Type.INTERFACE || clazz.getData() != null && clazz.getData().length > 0))
                        loadClass(clazz, withBytecode);
                }

                for (FunctionEntity function : module.getFunctions()) {
                    loadFunction(function, withBytecode);
                }

            } catch (NoSuchMethodException e) {
                throw new RuntimeException(e);
            } catch (NoSuchFieldException e) {
                throw new RuntimeException(e);
            }

            ret = true;
        }

        if (withBytecode) {
            byte[] data = translateData(module.getData());
            Class<?> result = defineOrReuse(internal, data);
            module.setNativeClazz(result);
        }

        try {
            Method method = module.getNativeClazz().getDeclaredMethod(
                    "__include", Environment.class, Memory[].class, ArrayMemory.class
            );
            module.setNativeMethod(method);
        } catch (NoSuchMethodException e) {
            throw new RuntimeException(e);
        }

        module.setLoaded(true);
        internalModules.put(internal, module);
        return ret;
    }

    protected byte[] translateData(byte[] data) {
        return data;
    }

    synchronized public void addLibrary(URL url) {
        addURL(url);

        for (Callback<Void, URL> listener : addLibraryListeners) {
            listener.call(url);
        }
    }

    public URL[] getLibraries() {
        return getURLs();
    }

    public void onAddLibrary(Callback<Void, URL> listener) {
        addLibraryListeners.add(listener);
    }
}
