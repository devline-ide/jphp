package php.runtime.env;

import php.runtime.ext.core.classes.stream.FileStream;
import php.runtime.ext.core.classes.stream.ResourceStream;
import php.runtime.common.PhpLanguageProfile;
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
    private Function<String, Collection<String>> implicitClassImportsResolver;
    private boolean implicitClassImportsResolverFrozen;

    public ModuleManager(Environment env) {
        this.env = env;
    }

    public void apply(ModuleManager parent) {
        this.modules.putAll(parent.modules);
        this.cachedModules.putAll(parent.cachedModules);
        this.includedPaths.addAll(parent.includedPaths);
        this.implicitClassImportsResolver = parent.implicitClassImportsResolver;
        this.implicitClassImportsResolverFrozen = parent.implicitClassImportsResolverFrozen;
    }

    /**
     * Environment-local source policy, configured before the first fetch. The callback
     * receives the normalized resolved path once per fetch, including cache hits.
     * Return null/empty for sources outside the application. Dumps never call it.
     */
    public void setImplicitClassImportsResolver(Function<String, Collection<String>> resolver) {
        if (implicitClassImportsResolverFrozen && implicitClassImportsResolver != resolver) {
            throw new IllegalStateException("Implicit class import resolver is frozen after the first fetch");
        }
        implicitClassImportsResolver = resolver;
    }

    private Context resolveImplicitClassImports(String path, boolean compiled) {
        implicitClassImportsResolverFrozen = true;
        Context options = new Context("");
        if (!compiled && implicitClassImportsResolver != null) {
            Collection<String> imports = implicitClassImportsResolver.apply(ModuleCacheKey.normalize(path));
            options.setImplicitClassImports(imports == null ? Collections.emptyList() : imports);
        }
        options.freezeImplicitClassImports();
        return options;
    }

    public ModuleEntity fetchModule(String path) throws Throwable {
        return fetchModule(path, path.endsWith(".phb"));
    }

    public ModuleEntity fetchModule(String path, boolean compiled) throws Throwable {
        return fetchModule(path, compiled, env.getScope().getPhpLanguageProfile());
    }

    public ModuleEntity fetchModule(String path, boolean compiled, PhpLanguageProfile profile) throws Throwable {
        String resourcePath = resolveResourceBytecode(path);
        if (!resourcePath.equals(path)) return fetchModule(resourcePath, compiled || path.endsWith(".php") && resourcePath.endsWith(".phb"), profile);
        Context imports = resolveImplicitClassImports(path, compiled);
        ModuleCacheKey key = new ModuleCacheKey(path, compiled, profile, imports.getImplicitClassImportsFingerprint());
        ModuleEntity moduleEntity = cachedModules.get(key);

        if (moduleEntity != null &&
                (moduleEntity.getContext().getLastModified() == 0
                        || moduleEntity.getContext().getLastModified() == new File(key.getPath()).lastModified())) {

            return moduleEntity;
        } else {
            cachedModules.remove(key);
            if (moduleEntity != null) modules.remove(key.getPath(), moduleEntity);
            moduleEntity = fetchTemporaryModule(path, compiled, profile, imports.getImplicitClassImports());

            if (moduleEntity == null) {
                return null;
            }

            cachedModules.put(key, moduleEntity);
            modules.put(key.getPath(), moduleEntity);

            return moduleEntity;
        }
    }

    public ModuleEntity fetchTemporaryModule(String path, boolean compiled) throws Throwable {
        return fetchTemporaryModule(path, compiled, env.getScope().getPhpLanguageProfile());
    }

    public ModuleEntity fetchTemporaryModule(String path, boolean compiled, PhpLanguageProfile profile) throws Throwable {
        String resourcePath = resolveResourceBytecode(path);
        if (!resourcePath.equals(path)) {
            compiled = compiled || path.endsWith(".php") && resourcePath.endsWith(".phb");
            path = resourcePath;
        }
        Context imports = resolveImplicitClassImports(path, compiled);
        return fetchTemporaryModule(path, compiled, profile, imports.getImplicitClassImports());
    }

    private ModuleEntity fetchTemporaryModule(String path, boolean compiled, PhpLanguageProfile profile,
                                              Map<String, String> imports) throws Throwable {
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
                Context context = fetchContext(stream);
                if (!compiled) {
                    context.setPhpLanguageProfile(profile);
                    context.setImplicitClassImports(imports.values());
                }

                if (compiled) {
                    module = env.importCompiledModule(context, true);
                } else {
                    module = env.importModule(context);
                }

                context.setPhpLanguageProfile(module.getPhpLanguageProfile());
                module.setTrace(new TraceInfo(context));
                return module;
            }
        } finally {
            env.invokeMethod(stream, "close");
        }
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
        return modules.get(ModuleCacheKey.normalize(resolveResourceBytecode(path)));
    }

    public void addModule(String path, ModuleEntity module) {
        ModuleCacheKey key = new ModuleCacheKey(path, module.isCompiledArtifact(), module.getPhpLanguageProfile(),
                module.getContext().getImplicitClassImportsFingerprint());
        modules.put(key.getPath(), module);
        cachedModules.put(key, module);
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
        for (Map.Entry<ModuleCacheKey, ModuleEntity> entry : cachedModules.entrySet()) {
            if (entry.getKey().getPath().equals(path) && entry.getValue().getPhpLanguageProfile() == profile) return entry.getValue();
        }
        return null;
    }

    public boolean hasModule(String path) {
        return modules.containsKey(ModuleCacheKey.normalize(resolveResourceBytecode(path)));
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

    public boolean isIncluded(String path) { return includedPaths.contains(ModuleCacheKey.normalize(resolveResourceBytecode(path))); }

    public void markIncluded(String path) {
        if (path != null) includedPaths.add(ModuleCacheKey.normalize(resolveResourceBytecode(path)));
    }

    public Collection<String> getIncludedPaths() { return new ArrayList<>(includedPaths); }

    public PhpLanguageProfile getPhpLanguageProfile(TraceInfo trace) {
        ModuleEntity module = findModule(trace);
        if (module != null) return module.getPhpLanguageProfile();
        return trace == null ? env.getScope().getPhpLanguageProfile() : trace.getPhpLanguageProfile(env.getScope());
    }

    public Collection<String> getCachedPaths() {
        return modules.keySet();
    }
}
