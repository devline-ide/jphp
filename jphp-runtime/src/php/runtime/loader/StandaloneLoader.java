package php.runtime.loader;

import php.runtime.common.PhpClassNames;

import php.runtime.Memory;
import php.runtime.Startup;
import php.runtime.common.Callback;
import php.runtime.common.LangMode;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.*;
import php.runtime.env.handler.EntityFetchHandler;
import php.runtime.exceptions.CriticalException;
import php.runtime.ext.core.classes.WrapClassLoader;
import php.runtime.ext.support.Extension;
import php.runtime.launcher.LaunchException;
import php.runtime.loader.dump.ModuleDumper;
import php.runtime.loader.dump.StandaloneLibrary;
import php.runtime.loader.dump.StandaloneLibraryDumper;
import php.runtime.memory.StringMemory;
import php.runtime.reflection.ClassEntity;
import php.runtime.reflection.FunctionEntity;
import php.runtime.reflection.ModuleEntity;
import php.runtime.reflection.helper.ClosureEntity;
import php.runtime.reflection.helper.GeneratorEntity;
import php.runtime.reflection.support.ReflectionUtils;

import java.io.File;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.*;

public class StandaloneLoader {
    protected final CompileScope scope;
    protected final Environment env;
    private ClassLoader classLoader;
    protected final Properties config;

    protected boolean isDebug;

    private StandaloneLibrary library;

    public StandaloneLoader() {
        config = new Properties();
        scope = new CompileScope();
        env = new ConcurrentEnvironment(scope, System.out);
        env.getDefaultBuffer().setImplicitFlush(true);

        scope.addClassEntityFetchHandler(new EntityFetchHandler() {
            @Override
            public void fetch(CompileScope scope, String originName, String name) {
                ModuleEntity module = fetchClass(originName);

                if (module != null) {
                        loadModule(module);
                        scope.loadModule(module, false);
                        scope.registerModule(module);
                }
            }
        });

        scope.addFunctionEntityFetchHandler(new EntityFetchHandler() {
            @Override
            public void fetch(CompileScope scope, String originName, String name) {
                ModuleEntity module = fetchFunction(name);

                if (module != null) {
                        loadModule(module);
                        scope.loadModule(module, false);
                        scope.registerModule(module);
                }
            }
        });

        scope.addConstantEntityFetchHandler(new EntityFetchHandler() {
            @Override
            public void fetch(CompileScope scope, String originName, String name) {
                ModuleEntity module = fetchConstant(originName);

                if (module != null) {
                        loadModule(module);
                        scope.loadModule(module, false);
                        scope.registerModule(module);
                }
            }
        });
    }

    public StandaloneLoader(ClassLoader classLoader) {
        this();
        this.setClassLoader(classLoader);
    }

    public ClassLoader getClassLoader() {
        return classLoader;
    }

    public void setClassLoader(ClassLoader classLoader) {
        this.classLoader = classLoader;
        this.scope.setNativeClassLoader(classLoader);
    }

    public Memory getConfigValue(String key) {
        return getConfigValue(key, Memory.NULL);
    }

    public Memory getConfigValue(String key, Memory def) {
        String result = config.getProperty(key);
        if (result == null)
            return def;

        return new StringMemory(result);
    }

    public Memory getConfigValue(String key, String def) {
        return getConfigValue(key, new StringMemory(def));
    }

    public Collection<InputStream> getResources(String name) {
        List<InputStream> result = new ArrayList<InputStream>();
        try {
            Enumeration<URL> urls = classLoader.getResources(name);
            while (urls.hasMoreElements()) {
                URL url = urls.nextElement();
                result.add(url.openStream());
            }
            return result;
        } catch (IOException e) {
            return Collections.emptyList();
            //throw new LaunchException(e.getMessage());
        }
    }

    public void loadExtensions() {
        if (classLoader == null) {
            throw new NullPointerException("classLoader is null");
        }

        ServiceLoader<Extension> loader = ServiceLoader.load(Extension.class, classLoader);

        for (Extension extension : loader) {
            scope.registerExtension(extension);
        }
    }

    public void initExtensions() {
        scope.getClassLoader().onAddLibrary(new Callback<Void, URL>() {
            @Override
            public Void call(URL param) {
                loadExtensions();
                return null;
            }
        });
    }

    public void loadLibrary() throws IOException {
        if (classLoader == null) {
            throw new NullPointerException("classLoader is null");
        }

        try (InputStream input = classLoader.getResourceAsStream("JPHP-INF/library.dump")) {
            if (input == null) throw new IOException("Cannot find standalone library: JPHP-INF/library.dump");
            this.loadLibraryDump(input);
        }
    }

    public void readConfig() {
        InputStream resource = classLoader.getResourceAsStream("JPHP-INF/launcher.conf");

        if (resource != null) {
            try {
                config.load(resource);

                for (String name : config.stringPropertyNames()){
                    scope.configuration.put(name, new StringMemory(config.getProperty(name)));
                }

                isDebug = Startup.isDebug();
                scope.setDebugMode(isDebug);

                scope.setLangMode(
                        LangMode.valueOf(getConfigValue("env.langMode", LangMode.MODERN.name()).toString().toUpperCase())
                );
                scope.setPhpLanguageProfile(PhpLanguageProfile.valueOf(
                        getConfigValue("env.phpProfile", PhpLanguageProfile.JPHP_LEGACY.name()).toString().toUpperCase(java.util.Locale.ROOT)
                ));
            } catch (IOException e) {
                throw new LaunchException(e.getMessage());
            }
        }
    }

    public void run() {
        readConfig();

        String classLoader = config.getProperty(
                "env.classLoader",
                ReflectionUtils.getClassName(WrapClassLoader.WrapLauncherClassLoader.class)
        );

        if (classLoader != null && !(classLoader.isEmpty())) {
            ClassEntity classLoaderEntity = env.fetchClass(classLoader);

            if (classLoaderEntity == null) {
                throw new LaunchException("Class loader class is not found: " + classLoader);
            }

            try {
                WrapClassLoader loader = classLoaderEntity.newObject(env, TraceInfo.UNKNOWN, true);
                env.invokeMethod(loader, "register", Memory.TRUE);
            } catch (Throwable e) {
                throw new CriticalException(e);
            }
        }

        run("JPHP-INF/.bootstrap.php");
    }

    public void run(String bootstrapScriptName) {
        loadExtensions();

        try {
            loadLibrary();

            ModuleEntity bootstrap = fetchModule(bootstrapScriptName);

            if (bootstrap != null) {
                bootstrap.includeNoThrow(env);
            } else {
                System.out.println("(!) Cannot find bootstrap script.");
            }
        } catch (IOException e) {
            throw new CriticalException(e);
        }
    }

    protected ModuleEntity _fetch(String name, Map<String, StandaloneLibrary.Module> source) {
        return _fetch(name, source, false);
    }

    private ModuleEntity _fetch(String name, Map<String, StandaloneLibrary.Module> source, boolean rejectModern) {
        StandaloneLibrary.Module module = source.get(name);

        if (module == null) {
            return null;
            //throw new CriticalException("Cannot find module of entity: " + name);
        }

        if (scope.findUserModule(module.getName()) != null) {
            return null;
        }

        InputStream input = classLoader.getResourceAsStream(module.getInternalName() + ".dump");

        if (input == null) {
            throw new CriticalException("Cannot find resource: " + module.getInternalName() + ".dump");
        }

        ModuleDumper moduleDumper = new ModuleDumper(
                new Context(new File(module.getInternalName())), env, true
        );

        try (BufferedInputStream buffered = new BufferedInputStream(input)) {
            if (rejectModern) {
                buffered.mark(16);
                if (ModuleDumper.readLanguageProfileHeader(buffered) == PhpLanguageProfile.PHP_8_5) return null;
                buffered.reset();
            }
            return moduleDumper.load(buffered);
        } catch (IOException e) {
            throw new CriticalException(e);
        }
    }

    public ModuleEntity fetchModule(String name) {
        if (library == null) {
            throw new CriticalException("Standalone library is not loaded");
        }

        ModuleEntity entity = _fetch(name, library.getModules());

        if (entity != null) {
            loadModule(entity);
            scope.loadModule(entity, false);
            scope.addUserModule(entity);
        }

        return entity;
    }

    public ModuleEntity fetchClass(String name) {
        if (library == null) {
            throw new CriticalException("Standalone library is not loaded");
        }

        String canonical = PhpClassNames.canonicalKey(name);
        var frame = php.runtime.PhpPropertyWriteContext.current(null);
        boolean modern = frame == null ? scope.isModernClassLookup() : PhpClassNames.isModern(frame.environment);
        ClassEntity loaded = scope.getClassMap().get(canonical);
        if (loaded != null && (modern || PhpClassNames.isModern(loaded))) return null;
        ModuleEntity exact = _fetch(canonical, library.getClassModules());
        if (modern || exact != null && exact.getPhpLanguageProfile() == php.runtime.common.PhpLanguageProfile.PHP_8_5) return exact;
        StandaloneLibrary.Module legacy = library.findLegacyClassModule(name);
        StandaloneLibrary.Module original = library.getClassModules().get(canonical);
        if (legacy == original) return exact;
        // The header gate runs before ClassDumper can autoload any dependencies.
        return legacy == null ? null : _fetch(legacy.getName(), library.getModules(), true);
    }

    public ModuleEntity fetchFunction(String name) {
        if (library == null) {
            throw new CriticalException("Standalone library is not loaded");
        }

        return _fetch(name.toLowerCase(), library.getFunctionModules());
    }

    public ModuleEntity fetchConstant(String name) {
        if (library == null) {
            throw new CriticalException("Standalone library is not loaded");
        }

        return _fetch(name, library.getConstantModules());
    }

    public CompileScope getScope() {
        return scope;
    }

    protected void loadLibraryDump(InputStream input) throws IOException {
        StandaloneLibraryDumper dumper = new StandaloneLibraryDumper();
        library = dumper.load(input);
    }

    protected void loadModule(ModuleEntity moduleEntity) {
        // Read every generated member before definition; native Java extensions stay in the resource parent.
        List<php.runtime.reflection.support.Entity> members = new ArrayList<>();
        members.add(moduleEntity);
        members.addAll(moduleEntity.getClasses());
        members.addAll(moduleEntity.getFunctions());
        members.addAll(moduleEntity.getClosures());
        members.addAll(moduleEntity.getGenerators());
        Map<php.runtime.reflection.support.Entity, byte[]> bytecode = new IdentityHashMap<>();
        try {
            for (php.runtime.reflection.support.Entity member : members) {
                byte[] data = member.getData();
                if (data == null || data.length == 0) {
                    String resource = member.getInternalName().replace('.', '/') + ".class";
                    try (InputStream input = classLoader.getResourceAsStream(resource)) {
                        if (input == null) throw new IOException("Cannot find standalone bytecode: " + resource);
                        data = input.readAllBytes();
                    }
                }
                bytecode.put(member, data);
            }
        } catch (IOException failure) {
            throw new RuntimeException(failure);
        }
        for (var member : members) member.setData(bytecode.get(member));
        scope.getClassLoader().loadModule(moduleEntity, true);
    }

    public Environment getScopeEnvironment() {
        return env;
    }
}
