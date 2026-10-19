package org.develnext.jphp.scripting;

import org.develnext.jphp.scripting.util.ReaderInputStream;
import php.runtime.Information;
import php.runtime.Memory;
import php.runtime.env.Context;
import php.runtime.env.Environment;
import php.runtime.env.TraceInfo;
import php.runtime.lang.exception.BaseBaseException;
import php.runtime.launcher.Launcher;
import php.runtime.memory.ArrayMemory;
import php.runtime.memory.support.MemoryUtils;
import php.runtime.reflection.ModuleEntity;

import javax.script.*;
import java.io.*;
import java.util.*;

public class JPHPScriptEngine extends AbstractScriptEngine implements Compilable, AutoCloseable {

    private static final String __ENGINE_VERSION__   = Information.CORE_VERSION;
    private static final String __NAME__             = Information.NAME;
    private static final String __SHORT_NAME__       = "jphp";
    private static final String __LANGUAGE__         = "php";
    private static final String __LANGUAGE_VERSION__ = Information.LIKE_PHP_VERSION;

    private ScriptEngineFactory factory = null;
    private Environment environment;
    private boolean closed;
    private final ScriptContextScope.HostRoots contextRoots = new ScriptContextScope.HostRoots();

    public JPHPScriptEngine() {
        super();

        Launcher launcher = new Launcher();
        try {
            launcher.run(false);
        } catch (Error e) {
            cleanupFailedInitialization(launcher, e);
            throw e;
        } catch (Throwable e) {
            cleanupFailedInitialization(launcher, e);
            throw new IllegalStateException("Unable to initialize the JPHP script engine", e);
        }
        environment = launcher.getEnvironment();
        environment.getDefaultBuffer().setImplicitFlush(true);

        JPHPContext ctx = new JPHPContext();
        ctx.setBindings(new JPHPBindings(environment.getGlobals()), ScriptContext.ENGINE_SCOPE);
        setContext(ctx);

        put(LANGUAGE_VERSION, __LANGUAGE_VERSION__);
        put(LANGUAGE, __LANGUAGE__);
        put(ENGINE, __NAME__);
        put(ENGINE_VERSION, __ENGINE_VERSION__);
        put(NAME, __SHORT_NAME__);
    }

    private static void cleanupFailedInitialization(Launcher launcher, Throwable primary) {
        Environment failed = launcher.getEnvironment();
        if (failed == null) return;
        try {
            failed.doFinalForEmbedding();
        } catch (Throwable cleanup) {
            if (cleanup != primary) primary.addSuppressed(cleanup);
        } finally {
            try {
                failed.releaseThreadSupport();
            } catch (Throwable cleanup) {
                if (cleanup != primary) primary.addSuppressed(cleanup);
            }
        }
    }

    @Override
    public Object eval(String script, ScriptContext context) throws ScriptException {
        return eval(new StringReader(script), context);
    }

    @Override
    public Object eval(Reader reader, ScriptContext _ctx) throws ScriptException {
        return compile(reader).eval(_ctx);
    }

    @Override
    public CompiledScript compile(String script) throws ScriptException {
        return compile(new StringReader(script));
    }

    @Override
    public synchronized CompiledScript compile(Reader reader) throws ScriptException {
        requireOpen();
        ReaderInputStream input = null;
        try (ReaderInputStream is = input = new ReaderInputStream(reader, Context.DEFAULT_CHARSET.name())) {
            Context context = new Context(is);
            ModuleEntity module = environment.importModule(context);
            return new JPHPCompiledScript(module, environment);
        } catch (IOException e) {
            throw new ScriptException(e);
        } catch (Throwable e) {
            Throwable readFailure = input == null ? null : input.getReadFailure();
            if (readFailure != null) {
                ScriptException failure = new ScriptException(readFailure.toString());
                failure.initCause(readFailure);
                throw failure;
            }
            throw new ScriptException(new Exception(e));
        }
    }

    private void requireOpen() throws ScriptException {
        if (closed) throw new ScriptException("JPHP script engine is closed");
    }

    private ScriptException scriptFailure(Throwable failure, Environment env) {
        if (failure instanceof ScriptException) return (ScriptException) failure;
        ScriptException result;
        if (failure instanceof BaseBaseException) {
            BaseBaseException php = (BaseBaseException) failure;
            TraceInfo trace = php.getTrace();
            result = new ScriptException(php.getMessage(env).toString(), trace.getFileName(),
                    trace.getStartLine() < 0 ? -1 : trace.getStartLine() + 1,
                    trace.getStartPosition() < 0 ? -1 : trace.getStartPosition() + 1);
        } else {
            result = new ScriptException(failure.toString());
        }
        result.initCause(failure);
        return result;
    }

    @Override
    public synchronized void close() throws ScriptException {
        if (closed) return;
        closed = true;
        Throwable primary = null;
        try {
            contextRoots.close(environment);
        } catch (Throwable failure) {
            primary = failure;
        }
        try {
            environment.doFinalForEmbedding();
        } catch (Throwable failure) {
            if (primary == null) primary = failure;
            else if (primary != failure) primary.addSuppressed(failure);
        } finally {
            try {
                environment.releaseThreadSupport();
            } catch (Throwable cleanup) {
                if (primary == null) primary = cleanup;
                else if (primary != cleanup) primary.addSuppressed(cleanup);
            }
        }
        if (primary != null) throw scriptFailure(primary, environment);
    }

    @Override
    public Bindings createBindings() {
        return new JPHPBindings(new ArrayMemory());
    }

    @Override
    public synchronized ScriptEngineFactory getFactory() {
        if (factory == null) {
            factory = new JPHPScriptEngineFactory();
        }
        return factory;
    }

    public void setFactory(JPHPScriptEngineFactory f) {
        factory = f;
    }

    public class JPHPBindings extends AbstractMap<String, Object> implements Bindings {

        private ArrayMemory globals;

        public JPHPBindings(ArrayMemory globals) {
            this.globals = globals;
        }

        @Override
        public Object put(String name, Object value) {
            String key = checkedKey(name);
            Object previous = get(key);
            globals.putAsKeyString(key, MemoryUtils.valueOf(value));
            return previous;
        }

        private String checkedKey(Object key) {
            Objects.requireNonNull(key, "Bindings key");
            String name = (String) key;
            if (name.isEmpty()) throw new IllegalArgumentException("Bindings key is empty");
            return name;
        }

        @Override
        public void putAll(Map<? extends String, ? extends Object> toMerge) {
            for (String key : toMerge.keySet()) {
                put(key, toMerge.get(key));
            }
        }

        @Override
        public void clear() {
            globals.clear();
        }

        @Override
        public Set<Entry<String, Object>> entrySet() {
            return new AbstractSet<Entry<String, Object>>() {
                @Override public int size() { return JPHPBindings.this.size(); }
                @Override public void clear() { JPHPBindings.this.clear(); }
                @Override public Iterator<Entry<String, Object>> iterator() {
                    Iterator<Object> keys = new ArrayList<Object>(globals.keySet()).iterator();
                    return new Iterator<Entry<String, Object>>() {
                        private String current;
                        private boolean removable;
                        @Override public boolean hasNext() { return keys.hasNext(); }
                        @Override public Entry<String, Object> next() {
                            final String key = keys.next().toString();
                            current = key;
                            removable = true;
                            return new Entry<String, Object>() {
                                @Override public String getKey() { return key; }
                                @Override public Object getValue() { return JPHPBindings.this.get(key); }
                                @Override public Object setValue(Object value) {
                                    return JPHPBindings.this.put(key, value);
                                }
                                @Override public boolean equals(Object other) {
                                    if (!(other instanceof Entry)) return false;
                                    Entry<?, ?> entry = (Entry<?, ?>) other;
                                    return Objects.equals(key, entry.getKey())
                                            && Objects.equals(getValue(), entry.getValue());
                                }
                                @Override public int hashCode() {
                                    return key.hashCode() ^ Objects.hashCode(getValue());
                                }
                            };
                        }
                        @Override public void remove() {
                            if (!removable) throw new IllegalStateException();
                            JPHPBindings.this.remove(current);
                            removable = false;
                        }
                    };
                }
            };
        }

        @Override
        public int size() {
            return globals.size();
        }

        @Override
        public boolean isEmpty() {
            return size() == 0;
        }

        @Override
        public boolean containsKey(Object key) {
            return globals.containsKey(checkedKey(key));
        }

        @Override
        public Object get(Object key) {
            Memory value = globals.getByScalar(checkedKey(key));
            return value == null ? null : value.toValue();
        }

        @Override
        public Object remove(Object key) {
            String name = checkedKey(key);
            Object previous = get(name);
            globals.removeByScalar(name);
            return previous;
        }
    }

    public class JPHPCompiledScript extends CompiledScript {

        private ModuleEntity module;
        private Environment environment;
        public JPHPCompiledScript(ModuleEntity m, Environment env) {
            module = m;
            environment = env;
        }

        @Override
        public Object eval(ScriptContext context) throws ScriptException {
            Objects.requireNonNull(context, "ScriptContext");
            synchronized (JPHPScriptEngine.this) {
                requireOpen();
                try (Environment.ThreadBinding binding = environment.bindCurrentThread()) {
                    if (php.runtime.PhpOwnershipExecution.current(environment) != null)
                        throw new IllegalArgumentException("ScriptContext needs its complete explicit-session native-globals host protocol");
                    Bindings supplied = context.getBindings(ScriptContext.ENGINE_SCOPE);
                    boolean canonical = supplied instanceof JPHPBindings
                            && ((JPHPBindings) supplied).globals == environment.getRuntimeGlobalsTable();
                    try (ScriptContextScope scope = ScriptContextScope.open(environment, context, canonical, contextRoots, module)) {
                        try {
                            return scope.include(module);
                        } catch (Throwable failure) {
                            throw scope.unwrapWriter(failure);
                        }
                    }
                } catch (Throwable failure) {
                    throw scriptFailure(failure, environment);
                }
            }
        }

        @Override
        public ScriptEngine getEngine() {
            return JPHPScriptEngine.this;
        }
    }
}
