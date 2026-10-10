package org.develnext.jphp.core.compiler.jvm;

import org.junit.Test;
import php.runtime.Memory;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.*;
import php.runtime.invoke.cache.*;
import php.runtime.loader.dump.ModuleDumper;
import php.runtime.memory.ObjectMemory;
import php.runtime.reflection.*;
import java.io.*;
import java.lang.ref.WeakReference;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.Assert.*;

/** Cache instances can be shared by compiled JVM classes; all mutable rows belong to an Environment. */
public class CallCacheIsolationTest {
    @FunctionalInterface private interface Work { void run() throws Throwable; }
    private void isolated(Work work) throws Throwable {
        AtomicReference<Throwable> failed = new AtomicReference<>();
        Thread thread = new Thread(() -> { try { work.run(); } catch (Throwable e) { failed.set(e); } }, "jphp-cache-qa");
        thread.setDaemon(true); thread.start(); thread.join(15000);
        assertFalse("isolated cache test timed out", thread.isAlive());
        if (failed.get() != null) throw failed.get();
    }
    private Environment env(CompileScope scope) { return new Environment(scope, new ByteArrayOutputStream()); }
    private ClassEntity clazz(String name) { ClassEntity c = new ClassEntity(new Context("")); c.setName(name); return c; }
    private PropertyEntity property(String name) { PropertyEntity p = new PropertyEntity(new Context("")); p.setName(name); return p; }
    private MethodEntity method(String name) { MethodEntity m = new MethodEntity(new Context("")); m.setName(name); return m; }
    private ConstantEntity constant(String name) { return new ConstantEntity(name, Memory.NULL, true); }

    private static class HookedPropertyCache extends PropertyCallCache {
        int arrays, maxLength, puts, gets, matrixFactories;
        @Override public Item[] newArrayData(int length) { arrays++; maxLength = Math.max(maxLength, length); return super.newArrayData(length); }
        @Override public Item[][] newArrayArrayData(int length) { matrixFactories++; return super.newArrayArrayData(length); }
        @Override protected void putItem(Environment env, int index, Item item) { puts++; super.putItem(env, index, item); }
        @Override public PropertyEntity get(Environment env, int index) { gets++; return super.get(env, index); }
        boolean legacyMatrixAbsent() { return cache == null; }
    }

    @Test public void testHighIdsUseOnlyParticipatingRowsAndKeepVirtualHooks() throws Throwable {
        isolated(() -> {
            CompileScope scope = new CompileScope(); List<Environment> retained = new ArrayList<>();
            Set<Integer> ids = new HashSet<>(); Environment latest = null;
            try {
                for (int i = 0; i < 2048; i++) {
                    latest = env(scope); latest.releaseThreadSupport(); retained.add(latest);
                    assertTrue("simultaneously live Environment IDs must be unique", ids.add(latest.id));
                }
                assertTrue(latest.id >= 2047); HookedPropertyCache cache = new HookedPropertyCache();
                PropertyEntity p = property("value"); cache.put(latest, 7, p);
                assertSame(p, cache.get(latest, 7)); assertEquals(1, cache.puts); assertEquals(1, cache.gets);
                assertEquals(1, cache.arrays); assertTrue("one low callsite must not allocate by global Environment ID", cache.maxLength <= 32);
                assertEquals(0, cache.matrixFactories); assertTrue(cache.legacyMatrixAbsent());
                assertNull("non-participating environment gets no row", retained.get(0).getCallCacheRow(cache, false));
                latest.doFinal(); assertNull(cache.get(latest, 7));
                int arrays = cache.arrays; cache.put(latest, 200, p); assertEquals(arrays, cache.arrays);
                assertNull(latest.getCallCacheRow(cache, false));
                Environment after = env(scope); assertTrue(after.id > latest.id); after.doFinal();
            } finally { for (Environment e : retained) e.doFinal(); }
        });
    }

    @Test public void testClassFunctionMethodPropertyAndConstantEnvironmentIsolation() throws Throwable {
        isolated(() -> {
            CompileScope scope = new CompileScope(); Environment a = env(scope), b = env(scope);
            try {
                ClassEntity ra = clazz("ReceiverA"), rb = clazz("ReceiverB"), sa = clazz("ScopeA"), sb = clazz("ScopeB");
                PropertyEntity pa = property("pa"), pb = property("pb"); MethodEntity ma = method("ma"), mb = method("mb");
                ConstantEntity ca = constant("A"), cb = constant("B");
                ClassCallCache classes = new ClassCallCache(); classes.put(a,0,ra); classes.put(b,0,rb);
                assertSame(ra,classes.get(a,0)); assertSame(rb,classes.get(b,0));
                FunctionEntity fa = new FunctionEntity(new Context("")), fb = new FunctionEntity(new Context(""));
                FunctionCallCache functions = new FunctionCallCache(); functions.put(a,0,fa); functions.put(b,0,fb);
                assertSame(fa,functions.get(a,0)); assertSame(fb,functions.get(b,0));
                MethodCallCache methods = new MethodCallCache(); methods.put(a,0,ma,ra,"value",sa); methods.put(b,0,mb,rb,"value",sb);
                PropertyCallCache properties = new PropertyCallCache(); properties.put(a,0,pa,ra,sa); properties.put(b,0,pb,rb,sb);
                ConstantCallCache constants = new ConstantCallCache(); constants.put(a,0,ca,ra,"V"); constants.put(b,0,cb,rb,"V");
                for(int i=0;i<20;i++) {
                    assertSame(ma,methods.get(a,0,ra,"value",sa)); assertSame(mb,methods.get(b,0,rb,"value",sb));
                    assertSame(pa,properties.get(a,0,ra,sa)); assertSame(pb,properties.get(b,0,rb,sb));
                    assertSame(ca,constants.get(a,0,ra,"V")); assertSame(cb,constants.get(b,0,rb,"V"));
                    assertNull(methods.get(a,0,ra,"value",sb)); assertNull(methods.get(a,0,ra,"other",sa));
                    assertNull(properties.get(a,0,rb,sa)); assertNull(constants.get(a,0,ra,"OTHER"));
                }
                assertNotSame(a.getCallCacheRow(properties,false),b.getCallCacheRow(properties,false));
            } finally { b.doFinal(); a.doFinal(); }
        });
    }

    @Test public void testScopedMetadataUsesOneSnapshotDuringConcurrentReplacement() throws Throwable {
        isolated(() -> {
            Environment env = env(new CompileScope()); ClassEntity a=clazz("A"), b=clazz("B");
            PropertyEntity pa=property("a"), pb=property("b");
            CountDownLatch captured=new CountDownLatch(1), committed=new CountDownLatch(1);
            AtomicBoolean pause=new AtomicBoolean(true); AtomicReference<Throwable> failure=new AtomicReference<>();
            PropertyCallCache cache=new PropertyCallCache() {
                @Override protected Item getItem(Environment target,int index) {
                    Item snapshot=super.getItem(target,index);
                    if(pause.getAndSet(false)) {
                        captured.countDown();
                        try { if(!committed.await(5,TimeUnit.SECONDS)) throw new AssertionError("replacement timed out"); }
                        catch(InterruptedException e) { throw new AssertionError(e); }
                    }
                    return snapshot;
                }
            };
            cache.put(env,0,pa,a,a);
            Thread writer=new Thread(() -> { try { captured.await(); cache.put(env,0,pb,b,b); } catch(Throwable e) { failure.set(e); } finally { committed.countDown(); } });
            writer.setDaemon(true); writer.start();
            try {
                // A payload captured before replacement must not be accepted using B's later metadata.
                assertNull(cache.get(env,0,b,b)); writer.join(5000); assertFalse(writer.isAlive()); assertNull(failure.get());
                assertSame(pb,cache.get(env,0,b,b)); assertNull(cache.get(env,0,a,a));
                MethodCallCache methods=new MethodCallCache(); ConstantCallCache constants=new ConstantCallCache();
                MethodEntity ma=method("a"),mb=method("b"); ConstantEntity ca=constant("a"),cb=constant("b");
                CountDownLatch start=new CountDownLatch(1);
                Thread concurrent=new Thread(() -> {
                    try { start.await(); for(int i=0;i<20000;i++) {
                        boolean first=(i&1)==0; ClassEntity r=first?a:b;
                        cache.put(env,0,first?pa:pb,r,r); methods.put(env,0,first?ma:mb,r,"value",r); constants.put(env,0,first?ca:cb,r,"V");
                    } } catch(Throwable e) { failure.set(e); }
                });
                concurrent.setDaemon(true); concurrent.start(); start.countDown();
                for(int i=0;i<20000;i++) {
                    boolean first=(i&1)==0; ClassEntity r=first?a:b;
                    PropertyEntity p=cache.get(env,0,r,r); MethodEntity m=methods.get(env,0,r,"value",r); ConstantEntity c=constants.get(env,0,r,"V");
                    assertTrue(p==null||p==(first?pa:pb)); assertTrue(m==null||m==(first?ma:mb)); assertTrue(c==null||c==(first?ca:cb));
                }
                concurrent.join(5000); assertFalse(concurrent.isAlive()); assertNull(failure.get());
            } finally { env.doFinal(); }
        });
    }

    private ModuleEntity compile(Environment env,String code,boolean dump) throws Throwable {
        Context context=new Context(code); context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
        ModuleEntity module=new JvmCompiler(env,context).compile(false);
        if(dump) { ByteArrayOutputStream bytes=new ByteArrayOutputStream(); ModuleDumper dumper=new ModuleDumper(context,env,true);
            dumper.save(module,bytes); module=dumper.load(new ByteArrayInputStream(bytes.toByteArray())); }
        return module;
    }
    private Memory include(Environment env,ModuleEntity module) throws Throwable {
        env.getScope().loadModule(module); env.getScope().addUserModule(module); env.registerModule(module); return module.include(env);
    }
    @Test public void testCompiledSourceAndDumpShareCacheAbiAcrossEnvironments() throws Throwable {
        isolated(() -> {
            for(boolean dump:new boolean[]{false,true}) {
                CompileScope scope=new CompileScope(); Environment a=env(scope),b=env(scope);
                try {
                    include(a,compile(a,"class CacheProvider {public $slot='pa';const V='ca';function value(){return 'ma';}}",dump));
                    include(b,compile(b,"class CacheProvider {public $slot='pb';const V='cb';function value(){return 'mb';}}",dump));
                    a.getGlobals().refOfIndex("provider").assign(new ObjectMemory(a.fetchClass("CacheProvider").newObject(a,TraceInfo.UNKNOWN,false)));
                    b.getGlobals().refOfIndex("provider").assign(new ObjectMemory(b.fetchClass("CacheProvider").newObject(b,TraceInfo.UNKNOWN,false)));
                    ModuleEntity caller=compile(a,"$o=$GLOBALS['provider'];return $o->value().':'.$o->slot.':'.$o::V;",dump);
                    include(a,caller); include(b,caller);
                    for(int i=0;i<10;i++) { assertEquals("ma:pa:ca",caller.include(a).toString()); assertEquals("mb:pb:cb",caller.include(b).toString()); }
                    int used=0;
                    for(String field:new String[]{"$CALL_METH_CACHE","$CALL_PROP_CACHE","$CALL_CONST_CACHE"}) {
                        CallCache<?> cache=(CallCache<?>)caller.getNativeClazz().getField(field).get(null);
                        CallCache.Row ar=a.getCallCacheRow(cache,false),br=b.getCallCacheRow(cache,false);
                        if(ar!=null||br!=null) { assertNotNull(ar); assertNotNull(br); assertNotSame(ar,br); used++; }
                    }
                    assertTrue("shared compiled class must exercise real cache rows",used>0);
                } finally { b.doFinal(); a.doFinal(); }
            }
        });
    }

    @Test public void testFinalizationFailureReleasesRowsWithoutDisablingFallbackData() throws Throwable {
        isolated(() -> {
            RuntimeException failure=new IllegalStateException("finalization"); Environment env=new Environment(new CompileScope(),new ByteArrayOutputStream()) {
                @Override public void finalizeObjects() { throw failure; }
            };
            ClassCallCache cache=new ClassCallCache(); ClassEntity entity=clazz("Cached"); cache.put(env,0,entity);
            assertSame(entity,cache.get(env,0)); assertNotNull(env.getCallCacheRow(cache,false));
            try { env.doFinal(); fail("finalization failure"); } catch(Throwable e) { assertSame(failure,e); }
            assertNull(env.getCallCacheRow(cache,false)); assertNull(cache.get(env,0)); cache.put(env,0,entity); assertNull(env.getCallCacheRow(cache,false));
            // Caches cease participating; existing Environment data remains accessible to an already bound worker.
            env.getGlobals().refOfIndex("after").assign(5); assertEquals(5,env.getGlobals().valueOfIndex("after").toLong());
            env.doFinal(); assertNull(Environment.current());
        });
    }

    private WeakReference<Environment> cyclicOwner(ConstantCallCache cache) {
        Environment env=env(new CompileScope());
        ConstantEntity entity=new ConstantEntity("Owner",Memory.NULL,true) { private final Environment owner=env; };
        cache.put(env,0,entity); env.releaseThreadSupport(); return new WeakReference<>(env);
    }
    @Test public void testStaticCacheCannotRetainEnvironmentThroughEntityCycle() throws Throwable {
        isolated(() -> {
            ConstantCallCache cache=new ConstantCallCache(); WeakReference<Environment> abandoned=cyclicOwner(cache);
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
            while(abandoned.get()!=null&&System.nanoTime()<deadline) { System.gc(); Thread.sleep(10); }
            assertNull("static cache must not root row -> Entity -> Environment cycles",abandoned.get());
            Environment alive=env(new CompileScope()); try { ConstantEntity entity=constant("alive");cache.put(alive,0,entity);assertSame(entity,cache.get(alive,0)); }
            finally { alive.doFinal(); }
        });
    }
}
