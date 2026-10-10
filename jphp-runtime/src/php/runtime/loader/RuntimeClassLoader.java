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

public class RuntimeClassLoader extends URLClassLoader {
    protected Map<String, ClassEntity> internalClasses = new HashMap<String, ClassEntity>();
    protected Map<String, FunctionEntity> internalFunctions = new HashMap<String, FunctionEntity>();
    protected Map<String, ModuleEntity> internalModules = new HashMap<String, ModuleEntity>();
    private final Map<String, byte[]> definedBytecodeHashes = new HashMap<>();

    private synchronized Class<?> defineOrReuse(String name, byte[] data) {
        byte[] hash;
        try { hash = MessageDigest.getInstance("SHA-256").digest(data); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
        Class<?> existing = findLoadedClass(name);
        if (existing != null) {
            byte[] previous = definedBytecodeHashes.get(name);
            if (previous == null || !MessageDigest.isEqual(hash, previous))
                throw new LinkageError("Different bytecode already defined for " + name);
            return existing;
        }
        Class<?> defined = defineClass(name, data, 0, data.length);
        definedBytecodeHashes.put(name, hash);
        return defined;
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
            Class<?> result = defineOrReuse(clazz.getCompiledInternalName(), data);

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

        internalClasses.put(clazz.getCompiledInternalName(), clazz);
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
                    if (clazz.getType() != ClassEntity.Type.INTERFACE || clazz.getData() != null && clazz.getData().length > 0)
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
