package scripting;

import org.develnext.jphp.scripting.JPHPScriptEngine;
import org.junit.Test;
import php.runtime.Memory;
import php.runtime.env.Environment;
import php.runtime.env.handler.ShutdownHandler;

import javax.script.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

/** Staged contract regressions. Not executed; no production change accompanies them. */
public class JSR223ContractRegressionTest {
    @FunctionalInterface private interface Action {
        void run(JPHPScriptEngine engine, Environment environment) throws Throwable;
    }

    private void fixture(Action action) throws Throwable {
        String previous = System.getProperty("jphp.config");
        Path bootstrap = Files.createTempFile("jphp-jsr223-bootstrap-", ".php");
        Path config = Files.createTempFile("jphp-jsr223-config-", ".properties");
        Environment environment = null;
        Throwable primary = null;
        try {
            Files.writeString(bootstrap, "<?php return 0;", StandardCharsets.UTF_8);
            Properties properties = new Properties();
            properties.setProperty("bootstrap.file", bootstrap.toString());
            properties.setProperty("env.doFinal", "0");
            properties.setProperty("env.extensions", "spl");
            try (OutputStream out = Files.newOutputStream(config)) {
                properties.store(out, "owned JSR223 fixture");
            }
            System.setProperty("jphp.config", config.toString());
            JPHPScriptEngine engine = new JPHPScriptEngine();
            var field = JPHPScriptEngine.class.getDeclaredField("environment");
            field.setAccessible(true);
            environment = (Environment) field.get(engine);
            action.run(engine, environment);
        } catch (Throwable failure) {
            primary = failure;
            throw failure;
        } finally {
            try {
                if (environment != null) environment.doFinal();
            } catch (Throwable cleanup) {
                if (primary == null) throw cleanup;
                if (primary != cleanup) primary.addSuppressed(cleanup);
            } finally {
                if (previous == null) System.clearProperty("jphp.config");
                else System.setProperty("jphp.config", previous);
                Files.deleteIfExists(config);
                Files.deleteIfExists(bootstrap);
            }
        }
    }

    @Test public void suppliedContextBindingsAndWriterAreUsed() throws Throwable {
        fixture((engine, environment) -> {
            engine.put("value", "default");
            SimpleScriptContext context = new SimpleScriptContext();
            context.setBindings(new SimpleBindings(), ScriptContext.ENGINE_SCOPE);
            context.setAttribute("value", "supplied", ScriptContext.ENGINE_SCOPE);
            StringWriter output = new StringWriter();
            context.setWriter(output);
            assertEquals("supplied", engine.compile("<?php echo $value; return $value;").eval(context).toString());
            assertEquals("supplied", output.toString());
            assertEquals("default", engine.get("value").toString());
        });
    }

    @Test public void suppliedBindingsReceiveScriptWritesWithoutChangingDefaults() throws Throwable {
        fixture((engine, environment) -> {
            engine.put("value", "default");
            Bindings supplied = new SimpleBindings();
            supplied.put("value", "supplied");
            assertEquals("changed", engine.eval("<?php $value = 'changed'; return $value;", supplied).toString());
            assertEquals("changed", supplied.get("value").toString());
            assertEquals("default", engine.get("value").toString());
        });
    }

    @Test public void globalScopeFallbackAndEngineScopePrecedenceAreUsed() throws Throwable {
        fixture((engine, environment) -> {
            SimpleScriptContext context = new SimpleScriptContext();
            Bindings global = new SimpleBindings();
            global.put("same", "global");
            global.put("globalOnly", "fallback");
            context.setBindings(global, ScriptContext.GLOBAL_SCOPE);
            context.setAttribute("same", "engine", ScriptContext.ENGINE_SCOPE);
            assertEquals("engine:fallback", engine.eval("<?php return $same . ':' . $globalOnly;", context).toString());
            assertEquals("global", global.get("same"));
        });
    }

    @Test public void createBindingsReturnsEmptyIndependentStorage() throws Throwable {
        fixture((engine, environment) -> {
            Bindings first = engine.createBindings();
            Bindings second = engine.createBindings();
            assertTrue(first.isEmpty());
            assertTrue(second.isEmpty());
            first.put("onlyFirst", "one");
            assertFalse(second.containsKey("onlyFirst"));
            assertNull(engine.get("onlyFirst"));
        });
    }

    @Test public void containsValueFindsAnActuallyStoredValue() throws Throwable {
        fixture((engine, environment) -> {
            Bindings bindings = engine.getBindings(ScriptContext.ENGINE_SCOPE);
            bindings.clear();
            bindings.put("value", "stored");
            assertTrue(bindings.containsValue(bindings.values().iterator().next()));
            assertFalse(bindings.containsValue("definitely missing"));
        });
    }

    @Test public void putReturnsNullForAnAbsentMapping() throws Throwable {
        fixture((engine, environment) -> {
            Bindings bindings = engine.getBindings(ScriptContext.ENGINE_SCOPE);
            assertNull(bindings.put("newMapping", "first"));
        });
    }

    @Test public void putReturnsThePreviousValueRatherThanTheUpdatedCell() throws Throwable {
        fixture((engine, environment) -> {
            Bindings bindings = engine.getBindings(ScriptContext.ENGINE_SCOPE);
            bindings.put("mapping", "first");
            Object previous = bindings.put("mapping", "second");
            assertEquals("first", previous.toString());
            assertEquals("second", bindings.get("mapping").toString());
        });
    }

    @Test public void invalidKeysFailAtTheBindingsBoundary() throws Throwable {
        fixture((engine, environment) -> {
            Bindings bindings = engine.getBindings(ScriptContext.ENGINE_SCOPE);
            assertThrows(NullPointerException.class, () -> bindings.put(null, 1));
            assertThrows(IllegalArgumentException.class, () -> bindings.put("", 1));
            for (Object key : new Object[] {null, "", 1}) {
                Class<? extends Throwable> expected = key == null ? NullPointerException.class
                        : key instanceof String ? IllegalArgumentException.class : ClassCastException.class;
                assertThrows(expected, () -> bindings.get(key));
                assertThrows(expected, () -> bindings.containsKey(key));
                assertThrows(expected, () -> bindings.remove(key));
            }
        });
    }

    @Test public void keySetIsABackedMapView() throws Throwable {
        fixture((engine, environment) -> {
            Bindings bindings = engine.getBindings(ScriptContext.ENGINE_SCOPE);
            bindings.put("entry", "value");
            Set<String> keys = bindings.keySet();
            assertTrue(keys.remove("entry"));
            assertFalse(bindings.containsKey("entry"));
            bindings.put("later", "value");
            assertTrue(keys.contains("later"));
        });
    }

    @Test public void valuesIsABackedMapView() throws Throwable {
        fixture((engine, environment) -> {
            Bindings bindings = engine.getBindings(ScriptContext.ENGINE_SCOPE);
            Collection<Object> values = bindings.values();
            values.clear();
            assertTrue(bindings.isEmpty());
            bindings.put("later", "value");
            assertEquals(1, values.size());
        });
    }

    @Test public void entrySetSetValueUpdatesTheMapping() throws Throwable {
        fixture((engine, environment) -> {
            Bindings bindings = engine.getBindings(ScriptContext.ENGINE_SCOPE);
            bindings.clear();
            bindings.put("entry", "before");
            bindings.entrySet().iterator().next().setValue("after");
            assertEquals("after", bindings.get("entry").toString());
        });
    }

    @Test public void bindingsImplementMapEqualityAndHashCode() throws Throwable {
        fixture((engine, environment) -> {
            Bindings bindings = engine.getBindings(ScriptContext.ENGINE_SCOPE);
            bindings.clear();
            bindings.put("number", 41);
            bindings.put("text", "mapped");
            Map<String, Object> copy = new HashMap<>();
            for (String key : bindings.keySet()) copy.put(key, bindings.get(key));
            assertEquals(copy, bindings);
            assertEquals(bindings, copy);
            assertEquals(copy.hashCode(), bindings.hashCode());
        });
    }

    @Test public void evalDoesNotFinalizeTheRetainedEnvironment() throws Throwable {
        AtomicInteger shutdowns = new AtomicInteger();
        fixture((engine, environment) -> {
            environment.registerShutdownFunction(new ShutdownHandler(null, new Memory[0]) {
                @Override public void call() { shutdowns.incrementAndGet(); }
            });
            CompiledScript script = engine.compile("<?php return 41;");
            assertEquals("41", script.eval().toString());
            assertEquals(0, shutdowns.get());
            try (Environment.ThreadBinding binding = environment.bindCurrentThread()) {
                assertSame(environment, Environment.current());
            }
            assertEquals("41", script.eval().toString());
            assertEquals(0, shutdowns.get());
        });
        assertEquals(1, shutdowns.get());
    }

    @Test public void phpRuntimeFailureIsReturnedToTheHostAsScriptException() throws Throwable {
        fixture((engine, environment) -> {
            ScriptException failure = assertThrows(ScriptException.class,
                    () -> engine.eval("<?php throw new \\RuntimeException('jsr223 primary');"));
            assertNotNull(failure.getCause());
            assertEquals("7", engine.eval("<?php return 7;").toString());
        });
    }

    @Test public void nullContextFailsBeforeScriptEffects() throws Throwable {
        fixture((engine, environment) -> {
            engine.put("effect", "untouched");
            CompiledScript script = engine.compile("<?php $effect = 'changed'; return 1;");
            assertThrows(NullPointerException.class, () -> script.eval((ScriptContext) null));
            assertEquals("untouched", engine.get("effect").toString());
        });
    }
}
