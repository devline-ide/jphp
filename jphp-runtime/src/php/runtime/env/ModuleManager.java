package php.runtime.env;

import php.runtime.ext.core.classes.stream.FileStream;
import php.runtime.ext.core.classes.stream.ResourceStream;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.common.PhpOptimizationProfile;
import php.runtime.common.PhpRuntimeOwnershipMode;
import php.runtime.ext.core.classes.stream.Stream;
import php.runtime.ext.core.classes.stream.WrapIOException;
import php.runtime.reflection.ModuleEntity;

import java.io.BufferedInputStream;
import java.io.File;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.ArrayList;
import java.util.Collections;
import java.util.function.Function;

public class ModuleManager {
    protected final Environment env;
    protected final Map<String, ModuleEntity> modules = new LinkedHashMap<String, ModuleEntity>();
    private final Map<ModuleCacheKey, ModuleEntity> cachedModules = new LinkedHashMap<>();
    private final Set<String> includedPaths = new LinkedHashSet<>();
    private final Set<ModuleCacheKey> pendingFetches = new LinkedHashSet<>();
    private Function<String, Collection<String>> implicitClassImportsResolver;
    private boolean implicitClassImportsResolverFrozen;
    private Function<String, PhpLanguageProfile> sourceLanguageProfileResolver;
    private boolean sourceLanguageProfileResolverFrozen;
    private Function<String, PhpOptimizationProfile> sourceOptimizationProfileResolver;
    private boolean sourceOptimizationProfileResolverFrozen;
    private Function<String, PhpRuntimeOwnershipMode> sourceOwnershipModeResolver;
    private boolean sourceOwnershipModeResolverFrozen;
    private Function<String, Boolean> sourceArrayOptimizationResolver;
    private boolean sourceArrayOptimizationResolverFrozen;
    private Function<String, Boolean> sourceStringOptimizationResolver;
    private boolean sourceStringOptimizationResolverFrozen;

    public ModuleManager(Environment env) {
        this.env = env;
    }

    public void apply(ModuleManager parent) {
        synchronized(parent) {
            this.modules.putAll(parent.modules);
            this.cachedModules.putAll(parent.cachedModules);
            this.includedPaths.addAll(parent.includedPaths);
            this.implicitClassImportsResolver = parent.implicitClassImportsResolver;
            this.implicitClassImportsResolverFrozen = parent.implicitClassImportsResolverFrozen;
            this.sourceLanguageProfileResolver = parent.sourceLanguageProfileResolver;
            this.sourceLanguageProfileResolverFrozen = parent.sourceLanguageProfileResolverFrozen;
            this.sourceOptimizationProfileResolver = parent.sourceOptimizationProfileResolver;
            this.sourceOptimizationProfileResolverFrozen = parent.sourceOptimizationProfileResolverFrozen;
            this.sourceOwnershipModeResolver = parent.sourceOwnershipModeResolver;
            this.sourceOwnershipModeResolverFrozen = parent.sourceOwnershipModeResolverFrozen;
            this.sourceArrayOptimizationResolver = parent.sourceArrayOptimizationResolver;
            this.sourceArrayOptimizationResolverFrozen = parent.sourceArrayOptimizationResolverFrozen;
            this.sourceStringOptimizationResolver = parent.sourceStringOptimizationResolver;
            this.sourceStringOptimizationResolverFrozen = parent.sourceStringOptimizationResolverFrozen;
        }
    }

    /**
     * Environment-local source policy, configured before the first fetch. The callback
     * receives the normalized resolved path once per fetch, including cache hits.
     * Return null/empty for sources outside the application. Dumps never call it.
     */
    public void setImplicitClassImportsResolver(Function<String, Collection<String>> resolver) {
        synchronized(this) {
            if (implicitClassImportsResolverFrozen && implicitClassImportsResolver != resolver) {
                throw new IllegalStateException("Implicit class import resolver is frozen after the first fetch");
            }
            implicitClassImportsResolver = resolver;
        }
    }

    /**
     * Environment-local source language policy, configured before the first fetch.
     * Called once per source fetch with its normalized resolved path, including cache hits.
     * A non-null selection overrides the supplied/caller profile; null retains it.
     * Compiled modules never call the resolver and retain their stored language profile.
     */
    public void setSourceLanguageProfileResolver(Function<String, PhpLanguageProfile> resolver) {
        synchronized(this) {
            if (sourceLanguageProfileResolverFrozen && sourceLanguageProfileResolver != resolver) {
                throw new IllegalStateException("Source language profile resolver is frozen after the first fetch");
            }
            sourceLanguageProfileResolver = resolver;
        }
    }

    private PhpLanguageProfile resolveSourceLanguageProfile(String path, boolean compiled, PhpLanguageProfile profile) {
        Function<String,PhpLanguageProfile> resolver;
        synchronized(this){sourceLanguageProfileResolverFrozen=true;resolver=sourceLanguageProfileResolver;}
        if(!compiled && resolver!=null){PhpLanguageProfile selected=resolver.apply(ModuleCacheKey.normalize(path));if(selected!=null)return selected;}
        return profile;
    }

    /** Authored-source optimization policy. Compiled artifacts retain their own stored contract. */
    public void setSourceOptimizationProfileResolver(Function<String, PhpOptimizationProfile> resolver) {
        synchronized(this) {
            if (sourceOptimizationProfileResolverFrozen && sourceOptimizationProfileResolver != resolver)
                throw new IllegalStateException("Source optimization profile resolver is frozen after the first fetch");
            sourceOptimizationProfileResolver = resolver;
        }
    }

    private PhpOptimizationProfile resolveSourceOptimizationProfile(String path, boolean compiled) {
        Function<String, PhpOptimizationProfile> resolver;
        synchronized(this) { sourceOptimizationProfileResolverFrozen = true; resolver = sourceOptimizationProfileResolver; }
        if (!compiled && resolver != null) {
            PhpOptimizationProfile selected = resolver.apply(ModuleCacheKey.normalize(path));
            if (selected != null) return selected;
        }
        return env.getScope().getPhpOptimizationProfile();
    }

    /** Source-only ownership contract; stored PHB contracts never consult the resolver. */
    public void setSourceOwnershipModeResolver(Function<String, PhpRuntimeOwnershipMode> resolver) {
        synchronized(this) {
            if (sourceOwnershipModeResolverFrozen && sourceOwnershipModeResolver != resolver)
                throw new IllegalStateException("Source ownership mode resolver is frozen after the first fetch");
            sourceOwnershipModeResolver = resolver;
        }
    }

    private PhpRuntimeOwnershipMode resolveSourceOwnershipMode(String path, boolean compiled) {
        Function<String, PhpRuntimeOwnershipMode> resolver;
        synchronized(this) { sourceOwnershipModeResolverFrozen = true; resolver = sourceOwnershipModeResolver; }
        if (!compiled && resolver != null) {
            PhpRuntimeOwnershipMode selected = resolver.apply(ModuleCacheKey.normalize(path));
            if (selected != null) return selected;
        }
        return env.getScope().getPhpOwnershipMode();
    }

    private Context resolveImplicitClassImports(String path, boolean compiled) {
        Function<String,Collection<String>> resolver;
        synchronized(this){implicitClassImportsResolverFrozen=true;resolver=implicitClassImportsResolver;}
        Context options=new Context("");
        if(!compiled && resolver!=null){Collection<String> imports=resolver.apply(ModuleCacheKey.normalize(path));options.setImplicitClassImports(imports==null?Collections.emptyList():imports);}
        options.freezeImplicitClassImports();return options;
    }

    public ModuleEntity fetchModule(String path) throws Throwable {
        return fetchModule(path, path.endsWith(".phb"));
    }

    public void setSourceArrayOptimizationResolver(Function<String, Boolean> resolver) {
        synchronized (this) {
            if (sourceArrayOptimizationResolverFrozen && sourceArrayOptimizationResolver != resolver)
                throw new IllegalStateException("Source array optimization resolver is frozen after the first fetch");
            sourceArrayOptimizationResolver = resolver;
        }
    }

    private boolean resolveSourceArrayOptimization(String path, boolean compiled) {
        Function<String, Boolean> resolver;
        synchronized (this) { sourceArrayOptimizationResolverFrozen = true; resolver = sourceArrayOptimizationResolver; }
        if (!compiled && resolver != null) {
            Boolean selected = resolver.apply(ModuleCacheKey.normalize(path));
            if (selected != null) return selected;
        }
        return !compiled && env.getScope().isPhpArrayOptimizationEnabled();
    }

    public void setSourceStringOptimizationResolver(Function<String, Boolean> resolver) {
        synchronized (this) {
            if (sourceStringOptimizationResolverFrozen && sourceStringOptimizationResolver != resolver)
                throw new IllegalStateException("Source string optimization resolver is frozen after the first fetch");
            sourceStringOptimizationResolver = resolver;
        }
    }

    private boolean resolveSourceStringOptimization(String path, boolean compiled) {
        Function<String, Boolean> resolver;
        synchronized (this) { sourceStringOptimizationResolverFrozen = true; resolver = sourceStringOptimizationResolver; }
        if (!compiled && resolver != null) {
            Boolean selected = resolver.apply(ModuleCacheKey.normalize(path));
            if (selected != null) return selected;
        }
        return !compiled && env.getScope().isPhpStringOptimizationEnabled();
    }

    public ModuleEntity fetchModule(String path, boolean compiled) throws Throwable {
        return fetchModule(path, compiled, env.getScope().getPhpLanguageProfile());
    }

    public ModuleEntity fetchModule(String path, boolean compiled, PhpLanguageProfile profile) throws Throwable {
        try(var admission=env.enterCallbackDomain()) {
            String resourcePath=resolveResourceBytecode(path);
            if(!resourcePath.equals(path))return fetchModule(resourcePath,compiled || path.endsWith(".php") && resourcePath.endsWith(".phb"),profile);
            ModuleCacheKey pending=new ModuleCacheKey(path,compiled,profile,"");
            synchronized(this){if(!pendingFetches.add(pending))throw new IllegalStateException("Recursive source resolution before module publication");}
            try {
                profile=resolveSourceLanguageProfile(path,compiled,profile);Context imports=resolveImplicitClassImports(path,compiled);
                PhpOptimizationProfile optimizationProfile = resolveSourceOptimizationProfile(path,compiled);
                PhpRuntimeOwnershipMode ownershipMode = resolveSourceOwnershipMode(path,compiled);
                boolean arrayOptimization = resolveSourceArrayOptimization(path,compiled);
                boolean stringOptimization = resolveSourceStringOptimization(path,compiled);
                ModuleCacheKey key=new ModuleCacheKey(path,compiled,profile,imports.getImplicitClassImportsFingerprint(),optimizationProfile,ownershipMode,arrayOptimization,stringOptimization);ModuleEntity module;
                synchronized(this){module=cachedModules.get(key);}
                if(module!=null && (module.getContext().getLastModified()==0 || module.getContext().getLastModified()==new File(key.getPath()).lastModified()))return module;
                synchronized(this){cachedModules.remove(key);if(module!=null)modules.remove(key.getPath(),module);}
                module=fetchTemporaryModule(path,compiled,profile,imports.getImplicitClassImports(),optimizationProfile,ownershipMode,arrayOptimization,stringOptimization);
                if(module!=null)synchronized(this){cachedModules.put(key,module);modules.put(key.getPath(),module);}
                return module;
            }finally{synchronized(this){pendingFetches.remove(pending);}}
        }
    }

    public ModuleEntity fetchTemporaryModule(String path, boolean compiled) throws Throwable {
        return fetchTemporaryModule(path, compiled, env.getScope().getPhpLanguageProfile());
    }

    public ModuleEntity fetchTemporaryModule(String path, boolean compiled, PhpLanguageProfile profile) throws Throwable {
        try(var admission=env.enterCallbackDomain()) {
            String resourcePath=resolveResourceBytecode(path);
            if(!resourcePath.equals(path)){compiled=compiled || path.endsWith(".php") && resourcePath.endsWith(".phb");path=resourcePath;}
            ModuleCacheKey pending=new ModuleCacheKey(path,compiled,profile,"");
            synchronized(this){if(!pendingFetches.add(pending))throw new IllegalStateException("Recursive source resolution before module publication");}
            try {
                profile=resolveSourceLanguageProfile(path,compiled,profile);Context imports=resolveImplicitClassImports(path,compiled);
                return fetchTemporaryModule(path,compiled,profile,imports.getImplicitClassImports(),resolveSourceOptimizationProfile(path,compiled),resolveSourceOwnershipMode(path,compiled),resolveSourceArrayOptimization(path,compiled),resolveSourceStringOptimization(path,compiled));
            }finally{synchronized(this){pendingFetches.remove(pending);}}
        }
    }

    private ModuleEntity fetchTemporaryModule(String path, boolean compiled, PhpLanguageProfile profile,
                                              Map<String, String> imports, PhpOptimizationProfile optimizationProfile, PhpRuntimeOwnershipMode ownershipMode, boolean arrayOptimization, boolean stringOptimization) throws Throwable {
        Stream stream = fetchStream(path);

        if (stream == null) {
            return null;
        }

        try {
            if (stream._isExternalResourceStream()) {
                env.exception("Cannot import module form external stream: " + stream.getPath());
                return null;
            } else {
                ModuleEntity module;
                Context context = fetchContext(stream,profile);
                if (!compiled) {
                    context.setPhpLanguageProfile(profile);
                    context.setPhpOptimizationProfile(optimizationProfile);
                    context.setPhpOwnershipMode(ownershipMode);
                    context.setPhpArrayOptimizationEnabled(arrayOptimization);
                    context.setPhpStringOptimizationEnabled(stringOptimization);
                    context.setImplicitClassImports(imports.values());
                }

                if (compiled) {
                    module = env.importCompiledModule(context, true);
                } else {
                    module = env.importModule(context);
                }

                context.setPhpLanguageProfile(module.getPhpLanguageProfile());
                context.setPhpOptimizationProfile(module.getOptimizationProfile());
                context.setPhpOwnershipMode(module.getOwnershipMode());
                context.setPhpArrayOptimizationEnabled(module.isArrayOptimizationEnabled());
                context.setPhpStringOptimizationEnabled(module.isStringOptimizationEnabled());
                module.setTrace(new TraceInfo(context));
                return module;
            }
        } finally {
            env.invokeMethod(stream, "close");
        }
    }

    private Context fetchContext(Stream stream,PhpLanguageProfile profile) throws Throwable {
        if(stream instanceof FileStream && profile==PhpLanguageProfile.PHP_8_5)
            return new Context(new BufferedInputStream(Stream.getInputStream(env,stream)),
                    new File(stream.getPath()).getCanonicalPath(),env.getDefaultCharset());
        return fetchContext(stream);
    }

    public Context fetchContext(Stream stream) throws Throwable {
        if (stream instanceof FileStream) {
            return new Context(
                    new BufferedInputStream(Stream.getInputStream(env, stream)), stream.getPath(), env.getDefaultCharset()
            );
        }

        return new Context(
                new BufferedInputStream(Stream.getInputStream(env, stream)), stream.getPath(), env.getDefaultCharset()
        );
    }

    protected Stream fetchStream(String path) throws Throwable {
        try {
            return Stream.create(env, path, "r");
        } catch (WrapIOException e) {
            return null;
        }
    }

    public ModuleEntity findModule(String path) {
        String key=ModuleCacheKey.normalize(resolveResourceBytecode(path));synchronized(this){return modules.get(key);}
    }

    public void addModule(String path, ModuleEntity module) {
        ModuleCacheKey key=new ModuleCacheKey(path,module.isCompiledArtifact(),module.getPhpLanguageProfile(),module.getContext().getImplicitClassImportsFingerprint(),module.getOptimizationProfile(),module.getOwnershipMode(),module.isArrayOptimizationEnabled(),module.isStringOptimizationEnabled());
        synchronized(this){modules.put(key.getPath(),module);cachedModules.put(key,module);}
    }

    public ModuleEntity findModule(TraceInfo traceInfo) {
        if (traceInfo == null || traceInfo == TraceInfo.UNKNOWN) {
            return null;
        }

        if (traceInfo.getModuleInternalName() != null) {
            ModuleEntity module = env.getScope().getClassLoader().getModule(traceInfo.getModuleInternalName());
            if (module != null) {
                return module;
            }
        }

        String path = ModuleCacheKey.normalize(traceInfo.getFileName());
        PhpLanguageProfile profile = traceInfo.getPhpLanguageProfile(env.getScope());
        Map<ModuleCacheKey,ModuleEntity> snapshot;
        synchronized(this){snapshot=new LinkedHashMap<>(cachedModules);}
        for (Map.Entry<ModuleCacheKey, ModuleEntity> entry : snapshot.entrySet()) {
            if (entry.getKey().getPath().equals(path) && entry.getValue().getPhpLanguageProfile() == profile) return entry.getValue();
        }
        return null;
    }

    public boolean hasModule(String path) {
        String key=ModuleCacheKey.normalize(resolveResourceBytecode(path));synchronized(this){return modules.containsKey(key);}
    }

    // Packaged bytecode replaces a missing PHP resource; an existing source always wins.
    private String resolveResourceBytecode(String path) {
        if (!path.startsWith("res://")) return path;
        String resource;
        try { resource = ResourceStream.normalizeResourcePath(path.substring(6)); }
        catch (java.io.IOException invalid) { return path; }
        String normalized = "res://" + resource;
        if (!resource.endsWith(".php")) return normalized;
        ClassLoader loader = env.getScope().getClassLoader();
        if (loader.getResource(resource) != null) return normalized;
        String bytecode = resource.substring(0, resource.length() - 4) + ".phb";
        return loader.getResource(bytecode) == null ? normalized : "res://" + bytecode;
    }

    public boolean isIncluded(String path) {
        String key=ModuleCacheKey.normalize(resolveResourceBytecode(path));synchronized(this){return includedPaths.contains(key);}
    }

    public void markIncluded(String path) {
        if(path!=null){String key=ModuleCacheKey.normalize(resolveResourceBytecode(path));synchronized(this){includedPaths.add(key);}}
    }

    public Collection<String> getIncludedPaths() {
        synchronized(this){return new ArrayList<>(includedPaths);}
    }

    public PhpLanguageProfile getPhpLanguageProfile(TraceInfo trace) {
        ModuleEntity module = findModule(trace);
        if (module != null) return module.getPhpLanguageProfile();
        return trace == null ? env.getScope().getPhpLanguageProfile() : trace.getPhpLanguageProfile(env.getScope());
    }

    public PhpRuntimeOwnershipMode getOwnershipMode(TraceInfo trace) {
        ModuleEntity module = findModule(trace);
        return module == null ? env.getScope().getPhpOwnershipMode() : module.getOwnershipMode();
    }

    public PhpOptimizationProfile getOptimizationProfile(TraceInfo trace) {
        ModuleEntity module = findModule(trace);
        return module == null ? env.getScope().getPhpOptimizationProfile() : module.getOptimizationProfile();
    }

    public Collection<String> getCachedPaths() {
        synchronized(this){return new ArrayList<>(modules.keySet());}
    }
}
