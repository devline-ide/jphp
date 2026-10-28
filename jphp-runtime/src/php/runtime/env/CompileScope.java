package php.runtime.env;

import php.runtime.common.PhpClassNames;

import php.runtime.Memory;
import php.runtime.Startup;
import php.runtime.common.AbstractCompiler;
import php.runtime.common.CompilerFactory;
import php.runtime.common.LangMode;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.common.PhpOptimizationProfile;
import php.runtime.common.PhpRuntimeOwnershipMode;
import php.runtime.common.Messages;
import php.runtime.env.handler.EntityFetchHandler;
import php.runtime.env.handler.ProgramShutdownHandler;
import php.runtime.env.handler.TickHandler;
import php.runtime.exceptions.ConflictException;
import php.runtime.exceptions.CriticalException;
import php.runtime.ext.CoreExtension;
import php.runtime.ext.JavaExtension;
import php.runtime.ext.NetExtension;
import php.runtime.ext.java.JavaException;
import php.runtime.ext.support.Extension;
import php.runtime.ext.support.compile.CompileClass;
import php.runtime.ext.support.compile.CompileConstant;
import php.runtime.ext.support.compile.CompileFunction;
import php.runtime.ext.support.compile.CompileFunctionSpec;
import php.runtime.lang.*;
import php.runtime.lang.exception.*;
import php.runtime.lang.spl.ArrayAccess;
import php.runtime.lang.spl.ErrorException;
import php.runtime.lang.spl.Serializable;
import php.runtime.lang.spl.Traversable;
import php.runtime.lang.spl.iterator.IteratorAggregate;
import php.runtime.loader.RuntimeClassLoader;
import php.runtime.reflection.*;
import php.runtime.reflection.support.ReflectionUtils;
import php.runtime.util.JVMStackTracer;
import php.runtime.wrap.ClassWrapper;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class CompileScope {
    public final int id;

    protected RuntimeClassLoader classLoader;
    public final Set<String> superGlobals;

    protected final static AtomicInteger scopeCount = new AtomicInteger(0);
    protected final AtomicInteger moduleCount = new AtomicInteger(0);
    protected final AtomicLong classCount = new AtomicLong(0);
    protected final AtomicLong methodCount = new AtomicLong(0);

    public final ConcurrentHashMap<String, ModuleEntity> moduleMap;
    public final ConcurrentHashMap<String, ModuleEntity> moduleIndexMap;
    private final ConcurrentHashMap<ModuleCacheKey, ModuleEntity> cachedModules = new ConcurrentHashMap<>();
    private final Set<Object> loadingModules = new HashSet<>();
    private final Set<String> loadingPhp85Classes = new HashSet<>();
    private static final class ModuleInputIdentity {
        private final java.lang.ref.WeakReference<Context> context;
        private final int hash;
        ModuleInputIdentity(Context context){this.context=new java.lang.ref.WeakReference<>(context);hash=System.identityHashCode(context);}
        @Override public int hashCode(){return hash;}
        @Override public boolean equals(Object other){return this==other || other instanceof ModuleInputIdentity identity
                && context.get()!=null && context.get()==identity.context.get();}
    }
    public final class ModuleLoad implements AutoCloseable {
        private final Object key;private final Thread owner=Thread.currentThread();private boolean closed;
        private ModuleLoad(Object key){this.key=key;}
        @Override public void close(){if(closed)return;if(owner!=Thread.currentThread())throw new IllegalStateException("Foreign module load release");
            synchronized(loadingModules){loadingModules.remove(key);}closed=true;}
    }
    public ModuleLoad beginModuleLoad(Context context,boolean compiled)throws java.io.IOException {
        String name=context.getModuleName();
        Object key=name==null?new ModuleInputIdentity(context):new ModuleCacheKey(name,compiled,context.getPhpLanguageProfile(this),
                compiled?"":context.getImplicitClassImportsFingerprint(), context.getPhpOptimizationProfile(this), context.getPhpOwnershipMode(this), context.isPhpArrayOptimizationEnabled(this));
        synchronized(loadingModules){if(!loadingModules.add(key))throw new IllegalStateException("Recursive module restoration before publication");}
        return new ModuleLoad(key);
    }

    /** Canonical ASCII class keys, including aliases; legacy lookup state stays private. */
    protected final Map<String, ClassEntity> classMap;
    private final Map<String, ClassEntity> legacyClassMap = new HashMap<>();
    private final Map<String, String> legacyClassKeys = new HashMap<>();
    private final ThreadLocal<Boolean> classLookupModern = new ThreadLocal<>();
    // Explicit producer context only; restore/remove in finally, never infer an Env from a thread binding.
    private final ThreadLocal<Environment> classLookupEnvironment = new ThreadLocal<>();
    private final Map<String, CompileClass> canonicalCompileClassMap = new HashMap<>();
    private final Map<String, CompileClass> php85CompileClassMap = new HashMap<>();
    private final Map<String, ClassEntity> php85ClassMap = new HashMap<>();
    private final Map<String,java.util.function.Consumer<ClassEntity>> php85ClassInitializers = new HashMap<>();
    private final Map<String,FunctionEntity> php85FunctionMap=new HashMap<>();
    public void registerPhp85Function(Extension extension,CompileFunctionSpec function) {
        synchronized(functionMap) {
            FunctionEntity previous=php85FunctionMap.get(function.getLowerName());
            if(previous!=null) {
                if(previous instanceof CompileFunctionEntity
                        && ((CompileFunctionEntity)previous).hasSameNativeDeclaration(extension,function)) return;
                throw new IllegalArgumentException("Duplicate PHP_8_5 function "+function.getName());
            }
            php85FunctionMap.put(function.getLowerName(),new CompileFunctionEntity(extension,function));
        }
    }
    public FunctionEntity fetchPhp85Function(String name) {
        synchronized(functionMap) {return php85FunctionMap.get(name.toLowerCase(java.util.Locale.ROOT));}
    }
    protected final Map<String, FunctionEntity> functionMap;
    protected final Map<String, ConstantEntity> constantMap;
    protected final Map<Class<? extends Throwable>, Class<? extends JavaException>> exceptionMap;
    protected final Map<String, Class<? extends JavaException>> exceptionMapForContext;

    protected Map<String, Extension> extensions;

    protected Map<String, CompileConstant> compileConstantMap;
    protected Map<String, CompileFunction> compileFunctionMap;
    protected Map<String, CompileClass> compileClassMap;
    protected Map<String, CompileFunctionSpec> compileFunctionSpecMap;

    protected CompilerFactory compilerFactory;

    protected List<EntityFetchHandler> classEntityFetchHandler;
    protected List<EntityFetchHandler> functionEntityFetchHandler;
    protected List<EntityFetchHandler> constantEntityFetchHandler;

    protected TickHandler tickHandler;
    protected List<ProgramShutdownHandler> programShutdownHandlers;

    public Map<String, Memory> configuration = new HashMap<>();

    // flags
    public boolean debugMode = false;
    protected LangMode langMode = LangMode.MODERN;
    protected PhpLanguageProfile phpLanguageProfile = PhpLanguageProfile.JPHP_LEGACY;
    protected PhpOptimizationProfile phpOptimizationProfile = PhpOptimizationProfile.COMPATIBLE;
    protected PhpRuntimeOwnershipMode phpOwnershipMode = PhpRuntimeOwnershipMode.AUTO;
    protected boolean phpArrayOptimizationEnabled;

    public CompileScope(CompileScope parent) {
        id = scopeCount.getAndIncrement();
        classLoader = parent.classLoader;
        langMode = parent.langMode;
        phpLanguageProfile = parent.phpLanguageProfile;
        phpOptimizationProfile = parent.phpOptimizationProfile;
        phpOwnershipMode = parent.phpOwnershipMode;
        phpArrayOptimizationEnabled = parent.phpArrayOptimizationEnabled;

        moduleMap = new ConcurrentHashMap<>();
        moduleIndexMap = new ConcurrentHashMap<>();

        classMap = new HashMap<>();
        functionMap = new HashMap<>();
        constantMap = new HashMap<>();
        exceptionMap = new HashMap<>();
        exceptionMapForContext = new HashMap<>();

        extensions = new LinkedHashMap<>();

        compileConstantMap = new HashMap<>();
        compileFunctionMap = new HashMap<>();
        compileFunctionSpecMap = new HashMap<>();
        compileClassMap    = new HashMap<>();

        superGlobals = new HashSet<>();
        superGlobals.addAll(parent.superGlobals);

        synchronized (parent.classMap) {
            classMap.putAll(parent.classMap);
            legacyClassMap.putAll(parent.legacyClassMap);
            legacyClassKeys.putAll(parent.legacyClassKeys);
            canonicalCompileClassMap.putAll(parent.canonicalCompileClassMap);
            compileClassMap.putAll(parent.compileClassMap);
            php85CompileClassMap.putAll(parent.php85CompileClassMap);
            php85ClassMap.putAll(parent.php85ClassMap);
            php85ClassInitializers.putAll(parent.php85ClassInitializers);
        }

        synchronized (parent.functionMap) {
            functionMap.putAll(parent.functionMap);
            php85FunctionMap.putAll(parent.php85FunctionMap);
            compileFunctionMap.putAll(parent.compileFunctionMap);
            compileFunctionSpecMap.putAll(parent.compileFunctionSpecMap);
        }

        constantMap.putAll(parent.constantMap);
        compileConstantMap.putAll(parent.compileConstantMap);

        exceptionMap.putAll(parent.exceptionMap);

        moduleMap.putAll(parent.moduleMap);
        moduleIndexMap.putAll(parent.moduleIndexMap);
        cachedModules.putAll(parent.cachedModules);

        classCount.set(parent.classCount.longValue());
        moduleCount.set(parent.moduleCount.intValue());
        methodCount.set(parent.methodCount.longValue());

        compilerFactory = parent.compilerFactory;

        classEntityFetchHandler = new ArrayList<>(parent.classEntityFetchHandler);
        functionEntityFetchHandler = new ArrayList<>(parent.functionEntityFetchHandler);
        constantEntityFetchHandler = new ArrayList<>(parent.constantEntityFetchHandler);

        extensions.putAll(parent.extensions);

        tickHandler = parent.tickHandler;
        programShutdownHandlers = new ArrayList<>(parent.programShutdownHandlers);
    }

    public CompileScope() {
        this(new RuntimeClassLoader(Thread.currentThread().getContextClassLoader()));
    }

    public CompileScope(RuntimeClassLoader classLoader) {
        id = scopeCount.getAndIncrement();
        this.classLoader = classLoader;

        moduleMap = new ConcurrentHashMap<>();
        moduleIndexMap = new ConcurrentHashMap<>();

        classMap = new HashMap<>();
        functionMap = new HashMap<>();
        constantMap = new HashMap<>();

        extensions = new LinkedHashMap<>();
        compileConstantMap = new HashMap<>();
        compileFunctionMap = new HashMap<>();
        compileFunctionSpecMap = new HashMap<>();
        compileClassMap    = new HashMap<>();
        exceptionMap = new HashMap<>();
        exceptionMapForContext = new HashMap<>();

        classEntityFetchHandler = new ArrayList<>();
        constantEntityFetchHandler = new ArrayList<>();
        functionEntityFetchHandler = new ArrayList<>();

        programShutdownHandlers = new ArrayList<>();

        superGlobals = new HashSet<>();

        superGlobals.add("GLOBALS");
        superGlobals.add("_ENV");

        try {
            final Class<?> jvmCompilerClass = Class.forName("org.develnext.jphp.core.compiler.jvm.JvmCompiler");
            final Constructor<?> jvmConstructor = jvmCompilerClass.getConstructor(Environment.class, Context.class);

            compilerFactory = new CompilerFactory() {
                @Override
                public AbstractCompiler getCompiler(Environment env, Context context) throws Throwable {
                    return (AbstractCompiler) jvmConstructor.newInstance(env, context);
                }
            };
        } catch (ClassNotFoundException | NoSuchMethodException e) {
            // nop.
        }

        CoreExtension extension = new CoreExtension();

        registerLazyClass(extension, Closure.class);
        registerLazyClass(extension, Generator.class);
        registerLazyClass(extension, StdClass.class);
        registerLazyClass(extension, php.runtime.lang.PhpWeakReference.class);
        registerLazyClass(extension, php.runtime.lang.IncompleteClass.class);
        registerLazyClass(extension, BaseThrowable.class);
        registerLazyClass(extension, BaseException.class);
        registerLazyClass(extension, BaseError.class);
        registerLazyClass(extension, BaseParseError.class);
        registerLazyClass(extension, BaseTypeError.class);
        registerLazyClass(extension, BaseArgumentCountError.class);
        registerLazyClass(extension, BaseValueError.class);
        registerLazyClass(extension, BaseUnhandledMatchError.class);
        registerLazyClass(extension, BaseArithmeticError.class);
        registerLazyClass(extension, BaseDivisionByZeroError.class);
        registerLazyClass(extension, ErrorException.class);
        registerLazyClass(extension, ArrayAccess.class);

        // iterators
        registerLazyClass(extension, Traversable.class);
        registerLazyClass(extension, php.runtime.lang.spl.iterator.Iterator.class);
        registerLazyClass(extension, IteratorAggregate.class);
        registerLazyClass(extension, Serializable.class);

        registerExtension(new JavaExtension());
        registerExtension(new NetExtension());
        registerExtension(extension);
    }

    public AbstractCompiler createCompiler(Environment env, Context context) throws Throwable {
        if (compilerFactory == null) {
            throw new CriticalException("Cannot find a compiler to compile php code");
        }

        try {
            return compilerFactory.getCompiler(env, context);
        } catch (InvocationTargetException e) {
            env.__throwException(e);
            return null;
        }
    }

    public CompilerFactory getCompilerFactory() {
        return compilerFactory;
    }

    public void setCompilerFactory(CompilerFactory compilerFactory) {
        this.compilerFactory = Objects.requireNonNull(compilerFactory, "compilerFactory");
    }

    public PhpLanguageProfile getPhpLanguageProfile() {
        return phpLanguageProfile;
    }

    public void setPhpLanguageProfile(PhpLanguageProfile profile) {
        phpLanguageProfile = Objects.requireNonNull(profile, "profile");
    }

    public PhpOptimizationProfile getPhpOptimizationProfile() { return phpOptimizationProfile; }

    public void setPhpOptimizationProfile(PhpOptimizationProfile profile) {
        phpOptimizationProfile = Objects.requireNonNull(profile, "profile");
    }

    public boolean isPhpArrayOptimizationEnabled() { return phpArrayOptimizationEnabled; }

    public void setPhpArrayOptimizationEnabled(boolean enabled) { phpArrayOptimizationEnabled = enabled; }

    public PhpRuntimeOwnershipMode getPhpOwnershipMode() { return phpOwnershipMode; }

    public void setPhpOwnershipMode(PhpRuntimeOwnershipMode mode) {
        phpOwnershipMode = Objects.requireNonNull(mode, "mode");
    }

    public RuntimeClassLoader getClassLoader() {
        return classLoader;
    }

    public void setNativeClassLoader(ClassLoader classLoader) {
        this.classLoader = new RuntimeClassLoader(classLoader);
    }

    public LangMode getLangMode() {
        return langMode;
    }

    public void setLangMode(LangMode langMode) {
        this.langMode = langMode;
    }

    public boolean isDebugMode() {
        return debugMode;
    }

    public void setDebugMode(boolean debugMode) {
        this.debugMode = debugMode;
    }

    public Map<String, ClassEntity> getClassMap() {
        return classMap;
    }

    /** Snapshot of registered native/user names, including lazy declarations, without autoload. */
    public Set<String> getRegisteredClassNames() {
        Set<String> names = new TreeSet<>(compileClassMap.keySet());
        names.addAll(classMap.keySet());
        return Collections.unmodifiableSet(names);
    }

    public Set<String> getRegisteredConstantNames() {
        Set<String> names = new TreeSet<>(compileConstantMap.keySet());
        names.addAll(constantMap.keySet());
        return Collections.unmodifiableSet(names);
    }

    public Map<String, FunctionEntity> getFunctionMap() {
        return functionMap;
    }

    /** Snapshot of the registered modern builtins for API metadata consumers. */
    public Set<String> getPhp85FunctionNames() {
        synchronized (functionMap) {
            return Set.copyOf(php85FunctionMap.keySet());
        }
    }

    public Map<String, ConstantEntity> getConstantMap() {
        return constantMap;
    }

    public int nextModuleIndex(){
        return moduleCount.incrementAndGet();
    }

    public long nextClassIndex(){
        return classCount.incrementAndGet();
    }

    public long nextMethodIndex(){
        return methodCount.incrementAndGet();
    }

    public void setTickHandler(TickHandler tickHandler) {
        this.tickHandler = tickHandler;
    }

    public TickHandler getTickHandler() {
        return tickHandler;
    }

    /** Reserved modern builtins never replace a legacy user's same-named declaration. */
    public void registerPhp85LazyClass(Extension extension, Class<?> clazz) {
        registerPhp85LazyClass(extension,clazz,null);
    }
    public void registerPhp85LazyClass(Extension extension, Class<?> clazz,java.util.function.Consumer<ClassEntity> initializer) {
        CompileClass compiled=new CompileClass(extension,clazz);
        synchronized(classMap) {
            String key=PhpClassNames.canonicalKey(compiled.getName());
            CompileClass previous=php85CompileClassMap.putIfAbsent(key,compiled);
            if(previous!=null && previous.getNativeClass()!=clazz)
                throw new IllegalArgumentException("Duplicate PHP_8_5 builtin "+compiled.getName());
            if(initializer!=null) php85ClassInitializers.put(key,initializer);
        }
    }
    public ClassEntity peekPhp85Class(String name) {
        synchronized(classMap){return php85ClassMap.get(PhpClassNames.canonicalKey(name));}
    }
    public boolean isPhp85ClassReserved(String name) {
        synchronized(classMap){return php85CompileClassMap.containsKey(PhpClassNames.canonicalKey(name));}
    }
    public ClassEntity fetchPhp85Class(String name) {
        String key=PhpClassNames.canonicalKey(name);
        synchronized(classMap){ClassEntity found=php85ClassMap.get(key);if(found!=null)return found;if(!php85CompileClassMap.containsKey(key))return null;}
        try(var admission=enterClassLookupDomain()) {
            CompileClass compiled;java.util.function.Consumer<ClassEntity> initializer;
            synchronized(classMap){ClassEntity found=php85ClassMap.get(key);if(found!=null)return found;
                compiled=php85CompileClassMap.get(key);if(compiled==null)return null;
                if(!loadingPhp85Classes.add(key))throw new IllegalStateException("Recursive builtin class wrapping before publication");
                initializer=php85ClassInitializers.get(key);}
            Boolean previous=classLookupModern.get();classLookupModern.set(true);
            try {
                ClassEntity entity=new ClassEntity(new ClassWrapper(compiled.getExtension(),this,compiled.getNativeClass()));
                entity.setId(nextClassIndex());if(initializer!=null)initializer.accept(entity);
                synchronized(classMap){php85ClassMap.put(key,entity);}return entity;
            }finally{if(previous==null)classLookupModern.remove();else classLookupModern.set(previous);
                synchronized(classMap){loadingPhp85Classes.remove(key);}}
        }
    }
    public boolean isPhp85Builtin(ClassEntity entity) {
        if(entity==null) return false;
        synchronized(classMap) {return php85ClassMap.get(PhpClassNames.canonicalKey(entity.getName()))==entity;}
    }
    public void registerLazyClass(Extension extension, Class<?> clazz) {
        CompileClass el = new CompileClass(extension, clazz);
        synchronized (classMap) {
            compileClassMap.put(el.getLowerName(), el);
            canonicalCompileClassMap.put(PhpClassNames.canonicalKey(el.getName()), el);
        }
    }

    @SuppressWarnings("unchecked")
    public void registerExtension(String extClass) {
        try {
            registerExtension((Class<? extends Extension>) Class.forName(extClass));
        } catch (ClassNotFoundException e) {
            throw new RuntimeException(e);
        }
    }

    public void registerExtension(Class<? extends Extension> extClass) {
        try {
            registerExtension(extClass.newInstance());
        } catch (Exception e) {
            throw new CriticalException(e);
        }
    }

    public void registerExtension(Extension extension) {
        // Registration callbacks may wrap classes. Enter their domain before the scope monitor.
        try (var admission = enterClassLookupDomain()) {
            registerExtensionInClassDomain(extension);
        }
    }

    private synchronized void registerExtensionInClassDomain(Extension extension) {
        long t = System.currentTimeMillis();

        if (extensions.containsKey(extension.getName().toLowerCase()))
            return;

        // required
        for(String dep : extension.getRequiredExtensions()){
            try {
                Extension el = (Extension) Class.forName(dep).newInstance();
                registerExtension(el);
            } catch (Exception e) {
                throw new CriticalException(e);
            }
        }

        // optional
        for (String dep : extension.getOptionalExtensions()) {
            try {
                Extension el = (Extension) Class.forName(dep).newInstance();
                registerExtension(el);
            } catch (ClassNotFoundException e) {
                // do nothing ...
            } catch (Exception e) {
                throw new CriticalException(e);
            }
        }

        // conflicts
        for(String dep : extension.getConflictExtensions()) {
            if (extensions.containsKey(dep))
                throw new ConflictException(
                        "'" + dep + "' extension conflicts with '" + extension.getClass().getName() + "'"
                );
        }

        extension.onRegister(this);

        compileConstantMap.putAll(extension.getConstants());
        compileFunctionSpecMap.putAll(extension.getFunctions());


        for(Class<?> clazz : extension.getClasses()) {
            registerLazyClass(extension, clazz);
        }

        for(CompileFunctionSpec function : extension.getFunctions().values()) {
            functionMap.put(function.getLowerName(), new CompileFunctionEntity(extension, function));
        }

        extensions.put(extension.getName().toLowerCase(), extension);

        if (Startup.isTracing()) {
            Startup.traceWithTime("Register extension '" + extension.getName() + "'", t);
        }
    }

    public Extension getExtension(String name){
        return extensions.get(name.toLowerCase());
    }

    public Set<String> getExtensions(){
        return extensions.keySet();
    }

    public void addClassEntityFetchHandler(EntityFetchHandler classEntityFetchHandler) {
        this.classEntityFetchHandler.add(classEntityFetchHandler);
    }

    public void addFunctionEntityFetchHandler(EntityFetchHandler functionEntityFetchHandler) {
        this.functionEntityFetchHandler.add(functionEntityFetchHandler);
    }

    public void addConstantEntityFetchHandler(EntityFetchHandler constantEntityFetchHandler) {
        this.constantEntityFetchHandler.add(constantEntityFetchHandler);
    }

    public void registerJavaException(Class<? extends JavaException> clazz, Class<? extends Throwable> throwClazz) {
        exceptionMap.put(throwClazz, clazz);
    }

    public void registerJavaExceptionForContext(Class<? extends JavaException> clazz, Class<? extends IObject> context) {
        exceptionMapForContext.put(ReflectionUtils.getClassName(context).toLowerCase(), clazz);
    }

    public void registerClass(String name, ClassEntity entity) {
        synchronized (classMap) {
            String key = PhpClassNames.canonicalKey(name);
            String legacyKey = name.toLowerCase();
            ClassEntity existing = classMap.get(key);
            String previousLegacyKey = legacyClassKeys.remove(key);
            if (previousLegacyKey != null) legacyClassMap.remove(previousLegacyKey, existing);
            // This public Java registration API has always allowed replacement; PHP declarations use registerModule.
            classMap.put(key, entity);
            if (!PhpClassNames.isModern(entity)) {
                legacyClassMap.put(legacyKey, entity); legacyClassKeys.put(key, legacyKey);
            }
        }
    }

    public void registerClass(ClassEntity clazz) {
        registerClass(clazz.getName(), clazz);
    }

    public void registerModule(ModuleEntity module) {
        synchronized (classMap) {
            Map<String, ClassEntity> canonical = new HashMap<>(classMap);
            Map<String, ClassEntity> legacy = new HashMap<>(legacyClassMap);
            for (ClassEntity entity : module.getClasses()) if (entity.isStatic()) {
                String key = PhpClassNames.canonicalKey(entity.getName());
                String oldKey = entity.getName().toLowerCase();
                if (canonical.containsKey(key) || !PhpClassNames.isModern(entity) && legacy.containsKey(oldKey)) {
                    throw new CriticalException(Messages.ERR_CANNOT_REDECLARE_CLASS.fetch(entity.getName()));
                }
                canonical.put(key, entity);
                if (!PhpClassNames.isModern(entity)) legacy.put(oldKey, entity);
            }
            for (ClassEntity entity : module.getClasses()) if (entity.isStatic()) registerClass(entity);
            addUserModule(module);
        }

        for(FunctionEntity entity : module.getFunctions()) {
            if (entity.isStatic()) {
                if (functionMap.put(entity.getLowerName(), entity) != null) {
                    throw new CriticalException(Messages.ERR_CANNOT_REDECLARE_FUNCTION.fetch(entity.getName()));
                }
            }
        }

        for(ConstantEntity entity : module.getConstants()) {
            if (constantMap.put(entity.getLowerName(), entity) != null) {
                throw new CriticalException(Messages.ERR_CANNOT_REDECLARE_CONSTANT.fetch(entity.getName()));
            }
        }
    }

    public void registerProgramShutdownHandler(ProgramShutdownHandler shutdownHandler) {
        programShutdownHandlers.add(shutdownHandler);
    }

    public void addUserModule(ModuleEntity module){
        moduleMap.put(module.getName(), module);
        moduleIndexMap.put(module.getInternalName(), module);
        String inputName = module.getContext().getModuleNameNoThrow();
        if (inputName != null) cachedModules.put(new ModuleCacheKey(inputName, module.isCompiledArtifact(),
                module.getPhpLanguageProfile(), module.getContext().getImplicitClassImportsFingerprint(), module.getOptimizationProfile(), module.getOwnershipMode(), module.isArrayOptimizationEnabled()), module);
    }

    public void registerFunction(FunctionEntity function){
        functionMap.put(function.getLowerName(), function);
    }

    public void registerConstant(ConstantEntity constant){
        constantMap.put(constant.getLowerName(), constant);
    }

    public ModuleEntity findUserModule(String name){
        return moduleMap.get(name);
    }

    public ModuleEntity findCachedModule(String name, boolean compiled, PhpLanguageProfile profile) {
        return findCachedModule(name, compiled, profile, "");
    }

    public ModuleEntity findCachedModule(String name, boolean compiled, PhpLanguageProfile profile, String importsFingerprint) {
        return findCachedModule(name, compiled, profile, importsFingerprint, getPhpOptimizationProfile());
    }

    public ModuleEntity findCachedModule(String name, boolean compiled, PhpLanguageProfile profile, String importsFingerprint,
                                         PhpOptimizationProfile optimizationProfile) {
        return findCachedModule(name, compiled, profile, importsFingerprint, optimizationProfile, getPhpOwnershipMode());
    }

    public ModuleEntity findCachedModule(String name, boolean compiled, PhpLanguageProfile profile, String importsFingerprint,
                                         PhpOptimizationProfile optimizationProfile, PhpRuntimeOwnershipMode ownershipMode) {
        return findCachedModule(name, compiled, profile, importsFingerprint, optimizationProfile, ownershipMode, isPhpArrayOptimizationEnabled());
    }

    public ModuleEntity findCachedModule(String name, boolean compiled, PhpLanguageProfile profile, String importsFingerprint,
                                         PhpOptimizationProfile optimizationProfile, PhpRuntimeOwnershipMode ownershipMode, boolean arrayOptimization) {
        ModuleCacheKey key = new ModuleCacheKey(name, compiled, profile, importsFingerprint, optimizationProfile, ownershipMode, arrayOptimization);
        ModuleEntity module = cachedModules.get(key);
        if (module != null && module.getContext().getLastModified() != 0
                && module.getContext().getLastModified() != new java.io.File(key.getPath()).lastModified()) {
            cachedModules.remove(key, module);
            moduleMap.remove(module.getName(), module);
            return null;
        }
        return module;
    }

    public ModuleEntity removeUserModule(String name) {
        ModuleEntity userModule = findUserModule(name);

        if (userModule != null) {
            moduleMap.remove(name);
            moduleIndexMap.remove(userModule.getInternalName());
        }

        String path = ModuleCacheKey.normalize(name);
        cachedModules.entrySet().removeIf(entry -> entry.getKey().getPath().equals(path)
                || name.equals(entry.getValue().getName()));

        return userModule;
    }

    public ClassEntity fetchUserClass(Class<? extends IObject> clazz) {
        return fetchUserClass(ReflectionUtils.getClassName(clazz));
    }

    public ClassEntity fetchUserClass(String name) {
        return fetchUserClass(name, name.toLowerCase());
    }

    public ClassEntity fetchUserClass(String name, String nameLower) {
        return fetchUserClassInternal(name, nameLower, isModernClassLookup());
    }

    public boolean isModernClassLookup() {
        Boolean active = classLookupModern.get();
        return active == null ? getPhpLanguageProfile() == PhpLanguageProfile.PHP_8_5 : active;
    }

    ClassEntity fetchUserClass(Environment environment,String name,String nameLower,boolean modern) {
        if(environment==null||environment.scope!=this)throw new IllegalArgumentException("Class lookup participant belongs to another CompileScope");
        Environment previous=classLookupEnvironment.get();classLookupEnvironment.set(environment);
        try{return fetchUserClass(name,nameLower,modern);}
        finally{if(previous==null)classLookupEnvironment.remove();else classLookupEnvironment.set(previous);}
    }
    private PhpCallbackDomain.Lease enterClassLookupDomain() {
        Environment environment=classLookupEnvironment.get();
        if(environment!=null&&environment.scope!=this)throw new IllegalStateException("Stale class lookup Environment context");
        return environment==null?classLoader.getCallbackDomain().enter():environment.enterCallbackDomain();
    }
    public ClassEntity fetchUserClass(String name, String nameLower, boolean modern) {
        Boolean previous = classLookupModern.get(); classLookupModern.set(modern);
        try { return fetchUserClass(name, nameLower); }
        finally { if (previous == null) classLookupModern.remove(); else classLookupModern.set(previous); }
    }

    private ClassEntity fetchUserClassInternal(String name, String nameLower, boolean modern) {
        try (var admission=enterClassLookupDomain()) {
            if(modern) {ClassEntity builtin=fetchPhp85Class(name);if(builtin!=null) return builtin;}
            ClassEntity entity;
            String canonical = PhpClassNames.canonicalKey(name);
            nameLower = modern ? canonical : name.toLowerCase();

            if (classEntityFetchHandler != null) {
                Boolean previous = classLookupModern.get();
                classLookupModern.set(modern);
                try {
                    for (EntityFetchHandler handler : classEntityFetchHandler) handler.fetch(this, name, nameLower);
                } finally {
                    if (previous == null) classLookupModern.remove(); else classLookupModern.set(previous);
                }
            }

            entity = classMap.get(canonical);
            if (!modern && !PhpClassNames.isModern(entity)) entity = legacyClassMap.get(nameLower);
            if (entity != null) {
                return entity;
            }

            CompileClass compileClass = modern ? canonicalCompileClassMap.get(canonical) : compileClassMap.get(nameLower);
            if (compileClass == null)
                return null;

            {
                entity = classMap.get(canonical);
                if (!modern && !PhpClassNames.isModern(entity)) entity = legacyClassMap.get(nameLower);
                if (entity != null) return entity;
                Boolean previous = classLookupModern.get();
                classLookupModern.set(modern);
                try {
                    entity = new ClassEntity(new ClassWrapper(
                            compileClass.getExtension(), this, compileClass.getNativeClass()
                    ));
                    entity.setId(nextClassIndex());
                    registerClass(entity);
                    return entity;
                } finally {
                    if (previous == null) classLookupModern.remove(); else classLookupModern.set(previous);
                }
            }
        }
    }

    public FunctionEntity findUserFunction(String name) {
        if(isModernClassLookup()) {FunctionEntity builtin=fetchPhp85Function(name);if(builtin!=null) return builtin;}
        String originName = name;
        name = name.toLowerCase();

        FunctionEntity entity = functionMap.get(name);

        if (entity == null && functionEntityFetchHandler != null) {
            for (EntityFetchHandler handler : functionEntityFetchHandler) {
                handler.fetch(this, originName, name);
            }

            entity = functionMap.get(name);
        }

        return entity;
    }

    public ConstantEntity findUserConstant(String name){
        String originName = name;
        name = name.toLowerCase();

        ConstantEntity entity = constantMap.get(name.toLowerCase());

        if (entity == null && constantEntityFetchHandler != null) {
            for (EntityFetchHandler handler : constantEntityFetchHandler) {
                handler.fetch(this, originName, name);
            }

            entity = constantMap.get(name);
        }

        return entity;
    }

    public Collection<ConstantEntity> getConstants(){
        return constantMap.values();
    }

    private static final CompileConstant PHP85_E_ALL = new CompileConstant("E_ALL", 30719);

    public CompileConstant findCompileConstant(String name, PhpLanguageProfile profile) {
        CompileConstant constant = findCompileConstant(name);
        return constant != null && profile == PhpLanguageProfile.PHP_8_5 && "E_ALL".equals(name)
                ? PHP85_E_ALL : constant;
    }

    public CompileConstant findCompileConstant(String name){
        return compileConstantMap.get(name);
    }

    public CompileFunction findCompileFunction(String name,PhpLanguageProfile profile) {
        Boolean previous=classLookupModern.get();classLookupModern.set(profile==PhpLanguageProfile.PHP_8_5);
        try {return findCompileFunction(name);}
        finally {if(previous==null) classLookupModern.remove();else classLookupModern.set(previous);}
    }
    public CompileFunction findCompileFunction(String name) {
        if(isModernClassLookup()) {
            FunctionEntity builtin=fetchPhp85Function(name);
            if(builtin instanceof CompileFunctionEntity) return ((CompileFunctionEntity)builtin).getCompileFunction();
        }
        name = name.toLowerCase();

        CompileFunction function = compileFunctionMap.get(name);

        if (function != null) {
            return function;
        }

        synchronized (this) {
            CompileFunctionSpec functionSpec = compileFunctionSpecMap.get(name);

            if (functionSpec == null) {
                return null;
            }

            compileFunctionMap.put(name, function = functionSpec.toFunction());
        }

        return function;
    }

    public CompileClass findCompileClass(String name) {
        if(isModernClassLookup()) {
            CompileClass builtin=php85CompileClassMap.get(PhpClassNames.canonicalKey(name));
            if(builtin!=null) return builtin;
        }
        return isModernClassLookup()
                ? canonicalCompileClassMap.get(PhpClassNames.canonicalKey(name)) : compileClassMap.get(name.toLowerCase());
    }

    /** Declaration collision check without fetch handlers, autoload, wrapping or initialization. */
    public boolean hasClassDeclaration(String name) {
        String key = PhpClassNames.canonicalKey(name);
        synchronized (classMap) {
            return classMap.containsKey(key) || canonicalCompileClassMap.containsKey(key)
                    || isModernClassLookup() && php85CompileClassMap.containsKey(key);
        }
    }

    public Class<? extends JavaException> findJavaException(Class<? extends Throwable> clazz) {
        return exceptionMap.get(clazz);
    }

    public Class<? extends JavaException> findJavaExceptionForContext(String className) {
        return exceptionMapForContext.get(className);
    }

    /**
     * Loads a module by name (filename) for classLoader
     * @param name a filename
     * @return
     */
    public ModuleEntity loadModule(String name, boolean withBytecode) {
        ModuleEntity entity = findUserModule(name);
        if (entity == null)
            return null;

        classLoader.loadModule(entity, withBytecode);
        return entity;
    }

    public ModuleEntity loadModule(String name) {
        return loadModule(name, true);
    }

    /**
     * Loads a module for classLoader
     * @param module a module
     */
    public void loadModule(ModuleEntity module, boolean withBytecode) {
        classLoader.loadModule(module, withBytecode);
    }

    public void loadModule(ModuleEntity module) {
        loadModule(module, true);
    }

    public JVMStackTracer getStackTracer(StackTraceElement[] elements) {
        return new JVMStackTracer(classLoader, elements);
    }

    public JVMStackTracer getStackTracer(Throwable throwable){
        return getStackTracer(throwable.getStackTrace());
    }

    public JVMStackTracer getStackTracer(){
        return getStackTracer(Thread.currentThread().getStackTrace());
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof CompileScope)) return false;

        CompileScope that = (CompileScope) o;

        return id == that.id;

    }

    @Override
    public int hashCode() {
        return id;
    }

    public void triggerProgramShutdown(Environment env) {
        for (ProgramShutdownHandler handler : programShutdownHandlers) {
            handler.onShutdown(this, env);
        }
    }
}
