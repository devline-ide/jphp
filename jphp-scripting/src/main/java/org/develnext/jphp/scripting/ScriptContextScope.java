package org.develnext.jphp.scripting;

import org.develnext.jphp.scripting.util.WriterOutputStream;
import php.runtime.Memory;
import php.runtime.PhpOwnershipDomain;
import php.runtime.PhpOwnershipExecution;
import php.runtime.PhpOwnershipValues;
import php.runtime.PhpRuntimeOwnership;
import php.runtime.env.Environment;
import php.runtime.memory.ArrayMemory;
import php.runtime.memory.ReferenceMemory;
import php.runtime.memory.StringMemory;
import php.runtime.memory.support.MemoryUtils;
import php.runtime.output.OutputBuffer;
import php.runtime.reflection.ModuleEntity;

import javax.script.Bindings;
import javax.script.ScriptContext;
import java.util.*;

/** One embedding invocation borrows the actual Environment tables and restores its caller. */
final class ScriptContextScope implements AutoCloseable {
    private static final Set<String> RESERVED=Set.of("GLOBALS");
    private final Environment environment;
    private final ArrayMemory globals;
    private final Bindings bindings;
    private final boolean canonicalBindings;
    private final PhpRuntimeOwnership.Authority authority;
    private final PhpOwnershipDomain.Execution execution;
    private final HostRoots hostRoots;
    private final List<PhpOwnershipDomain.Owned> incomingCustody=new ArrayList<>();
    private final Map<Object,Saved> original=new LinkedHashMap<>();
    private final Map<Object,Observed> observed=new LinkedHashMap<>();
    private final Map<Object,String> engineNames=new LinkedHashMap<>();
    private final Set<Object> fallbackOnly=new LinkedHashSet<>();
    private final List<OutputBuffer> previousBuffers;
    private final List<OutputBuffer.ScopedOutput> outputScopes=new ArrayList<>();
    private final WriterOutputStream writer;
    private boolean mutated,published,closed;

    private static Object key(String name){return ArrayMemory.toKey(StringMemory.valueOf(name));}
    private static boolean reserved(Object key){return key instanceof String && RESERVED.contains(key);}
    private static final class Saved {
        final ReferenceMemory carrier;
        final Memory raw;
        final PhpRuntimeOwnership.Role role;
        final PhpOwnershipDomain.Owned custody;
        Saved(ReferenceMemory carrier,Memory raw,PhpRuntimeOwnership.Role role,PhpOwnershipDomain.Owned custody) {
            this.carrier=carrier;this.raw=raw;this.role=role;this.custody=custody;
        }
    }
    private static final class Observed {
        final ReferenceMemory carrier;
        final Memory raw;
        final Object arrayStorage;
        final List<ReferenceMemory.GlobalsWriteObservation> writes=new ArrayList<>();
        Observed(Environment environment,Object key,ReferenceMemory carrier) {
            this.carrier=carrier;raw=carrier.getRawValue();
            Memory value=carrier.toValue();arrayStorage=value instanceof ArrayMemory ? ((ArrayMemory)value).getStorageIdentity() : null;
            writes.add(carrier.observeGlobalsWrites(environment,key,carrier));
            ReferenceMemory cell=carrier.getReference();
            if(cell!=carrier)writes.add(cell.observeGlobalsWrites(environment,key,carrier));
        }
        boolean changed(ReferenceMemory current) {
            if(current!=carrier || current.getRawValue()!=raw)return true;
            for(var write:writes)if(write.wasWritten())return true;
            Memory value=current.toValue();
            return arrayStorage!=null && (!(value instanceof ArrayMemory) || ((ArrayMemory)value).getStorageIdentity()!=arrayStorage);
        }
        void close(){for(int i=writes.size()-1;i>=0;i--)writes.get(i).close();}
    }

    static ScriptContextScope open(Environment environment,ScriptContext context,boolean canonicalBindings,HostRoots roots,ModuleEntity module) throws Throwable {
        Objects.requireNonNull(context,"ScriptContext");
        if(PhpOwnershipExecution.current(environment)!=null)
            throw new IllegalArgumentException("ScriptContext needs its complete explicit-session native-globals host protocol");
        ScriptContextScope scope=new ScriptContextScope(environment,context,canonicalBindings,roots);
        try{scope.install(context,module);return scope;}catch(Throwable failure){try{scope.close();}catch(Throwable cleanup){if(cleanup!=failure)failure.addSuppressed(cleanup);}throw failure;}
    }
    private ScriptContextScope(Environment environment,ScriptContext context,boolean canonicalBindings,HostRoots roots) {
        this.environment=environment;globals=environment.getRuntimeGlobalsTable();
        bindings=Objects.requireNonNull(context.getBindings(ScriptContext.ENGINE_SCOPE),"ENGINE_SCOPE bindings");
        this.canonicalBindings=canonicalBindings;
        hostRoots=roots;
        previousBuffers=new ArrayList<>(environment.getOutputBuffers());
        writer=new WriterOutputStream(Objects.requireNonNull(context.getWriter(),"ScriptContext writer"),environment.getDefaultCharset());
        authority=PhpRuntimeOwnership.current(environment);execution=authority==null?null:authority.enter();
    }
    private void preflightBindings(Bindings source) {
        if(source==null)return;
        for(var entry:source.entrySet()) {
            String name=Objects.requireNonNull(entry.getKey(),"Bindings key");
            if(name.isEmpty())throw new IllegalArgumentException("Bindings key is empty");
            if(reserved(key(name))) {
                Object value=entry.getValue();
                if(value!=globals && (!(value instanceof Memory) || ((Memory)value).toValue()!=globals))
                    throw new IllegalArgumentException("ScriptContext cannot replace its actual GLOBALS self-carrier");
            }
        }
    }
    private static void input(Map<Object,Memory> values,Map<Object,String> names,Bindings source) {
        if(source==null)return;
        for(var entry:source.entrySet()) {
            String name=Objects.requireNonNull(entry.getKey(),"Bindings key");
            if(name.isEmpty())throw new IllegalArgumentException("Bindings key is empty");
            Object key=key(name);if(reserved(key))continue;
            values.put(key,MemoryUtils.valueOf(entry.getValue()).toValue().toImmutable());names.put(key,name);
        }
    }
    private void install(ScriptContext context,ModuleEntity module)throws Throwable {
        boolean pure=authority!=null;
        if(pure && (previousBuffers.size()!=1 || previousBuffers.get(0)!=environment.getDefaultBuffer()))
            throw new IllegalArgumentException("Owned ScriptContext output needs the actual pure root buffer");
        // Only reversible private buffer state changes here. No Writer method runs before admission.
        for(OutputBuffer buffer:previousBuffers)outputScopes.add(buffer.scopeOutput(writer,pure));
        if(authority!=null)authority.requireModuleEntry(module);
        preflightBindings(context.getBindings(ScriptContext.GLOBAL_SCOPE));preflightBindings(bindings);
        Map<Object,Memory> values=new LinkedHashMap<>();Map<Object,String> names=new LinkedHashMap<>();
        input(values,names,context.getBindings(ScriptContext.GLOBAL_SCOPE));
        fallbackOnly.addAll(values.keySet());
        if(canonicalBindings) {
            for(String name:bindings.keySet())if(!reserved(key(name)))engineNames.put(key(name),name);
            fallbackOnly.removeAll(engineNames.keySet());
            for(Object key:engineNames.keySet())values.remove(key);
        } else {
            Map<Object,Memory> engineValues=new LinkedHashMap<>();input(engineValues,engineNames,bindings);
            fallbackOnly.removeAll(engineValues.keySet());values.putAll(engineValues);names.putAll(engineNames);
        }
        if(authority!=null) {
            // Prove every incoming and saved semantic value before taking roots or sealing the native table.
            for(Memory value:values.values())authority.value(value,PhpRuntimeOwnership.Role.VALUE);
            for(Object key:new ArrayList<>(globals.keySet()))if(!reserved(key)) {
                ReferenceMemory carrier=globals.getByScalar(key);Memory raw=carrier.getRawValue();
                var holder=carrier.getRuntimeHolder();
                PhpRuntimeOwnership.Role role=holder==null ? PhpRuntimeOwnership.Role.VALUE : holder.role();
                authority.value(raw,role);
            }
            for(Memory value:values.values()) {
                var actual=authority.value(value,PhpRuntimeOwnership.Role.VALUE);
                incomingCustody.add(PhpOwnershipValues.bindValue(authority.domain(),actual.raw(),actual.node()).host());
            }
        }

        for(Object key:new ArrayList<>(globals.keySet()))if(!reserved(key)) {
            ReferenceMemory carrier=globals.getByScalar(key);Memory raw=carrier.getRawValue();
            PhpRuntimeOwnership.Role role=PhpRuntimeOwnership.Role.VALUE;PhpOwnershipDomain.Owned custody=null;
            if(authority!=null) {
                var holder=authority.globalSlot(key).holder();raw=holder.raw();role=holder.role();
                custody=(role==PhpRuntimeOwnership.Role.CELL
                        ? PhpOwnershipValues.bindCell(authority.domain(),(ReferenceMemory)raw,holder.node())
                        : PhpOwnershipValues.bindValue(authority.domain(),raw,holder.node())).host();
            }
            original.put(key,new Saved(carrier,raw,role,custody));
        }
        mutated=true;
        if(!canonicalBindings)for(Object key:original.keySet())unset(key);
        for(var entry:values.entrySet())if(!canonicalBindings || globals.getByScalar(entry.getKey())==null)
            assign(entry.getKey(),entry.getValue(),PhpRuntimeOwnership.Role.VALUE);
        for(Object key:new ArrayList<>(globals.keySet()))if(!reserved(key))
            observed.put(key,new Observed(environment,key,globals.getByScalar(key)));
        hostRoots.reconcile(bindings);
    }
    private void unset(Object key) {
        if(authority==null)globals.removeByScalar(key);else try{authority.globalSlot(key).unset();}catch(Throwable failure){ScriptContextScope.<RuntimeException>raise(failure);}
    }
    private void assign(Object key,Memory raw,PhpRuntimeOwnership.Role role)throws Throwable {
        if(authority==null)globals.put(key,raw);
        else {
            var slot=authority.globalSlot(key);
            if(role==PhpRuntimeOwnership.Role.CELL)slot.carrier().assignRef(raw);else slot.assign(raw);
        }
    }
    void publish() throws Throwable {
        if(published)return;published=true;
        if(canonicalBindings)return;
        for(Object key:engineNames.keySet()) {
            ReferenceMemory current=globals.getByScalar(key);
            if(current==null || current.toValue().isUndefined())hostRoots.remove(bindings,engineNames.get(key));
        }
        for(Object key:new ArrayList<>(globals.keySet()))if(!reserved(key)) {
            ReferenceMemory current=globals.getByScalar(key);Observed initial=observed.get(key);
            if(current.toValue().isUndefined())continue;
            if(initial==null || initial.changed(current))hostRoots.publish(bindings,engineNames.getOrDefault(key,key.toString()),current.toValue().toImmutable(),authority);
        }
    }
    Memory include(ModuleEntity module)throws Throwable {
        if(authority==null)return module.include(environment);
        try(var result=authority.include(module)){return hostRoots.result(result,authority);}
    }

    @Override public void close()throws Exception {
        if(closed)return;closed=true;Throwable failure=null;
        // A failed Writer retains its first IOException; do not retry or leak its bytes into caller output.
        if(mutated && !outputScopes.isEmpty() && writer.failure()==null) {
            try {
                while(environment.getOutputBuffers().size()>previousBuffers.size())environment.popOutputBuffer();
                List<OutputBuffer> active=new ArrayList<>(environment.getOutputBuffers());
                for(int i=active.size()-1;i>=0;i--)active.get(i).flush();
            }catch(Throwable cleanup){failure=compose(failure,unwrapWriter(cleanup));}
        }
        try{if(mutated)publish();}catch(Throwable error){failure=compose(failure,error);}
        Set<Object> unusedFallback=new LinkedHashSet<>();
        if(canonicalBindings)for(Object key:fallbackOnly) {
            ReferenceMemory current=globals.getByScalar(key);Observed initial=observed.get(key);
            if(!original.containsKey(key) && current!=null && initial!=null && !initial.changed(current))unusedFallback.add(key);
        }
        List<Observed> observations=new ArrayList<>(observed.values());
        for(int i=observations.size()-1;i>=0;i--)try{observations.get(i).close();}catch(Throwable cleanup){failure=compose(failure,cleanup);}
        if(mutated) {
            if(canonicalBindings) {
                for(Object key:unusedFallback)try{unset(key);}catch(Throwable cleanup){failure=compose(failure,cleanup);}
            } else {
                for(Object key:new ArrayList<>(globals.keySet()))if(!reserved(key))try{unset(key);}catch(Throwable cleanup){failure=compose(failure,cleanup);}
                for(var entry:original.entrySet())try{assign(entry.getKey(),entry.getValue().raw,entry.getValue().role);}catch(Throwable cleanup){failure=compose(failure,cleanup);}
            }
        }
        for(Saved saved:original.values())if(saved.custody!=null)try{saved.custody.close();}catch(Throwable cleanup){failure=compose(failure,cleanup);}
        for(var custody:incomingCustody)try{custody.close();}catch(Throwable cleanup){failure=compose(failure,cleanup);}
        // Root releases can run certified PHP finalizers. Their output belongs to this invocation too.
        if(mutated && !outputScopes.isEmpty() && writer.failure()==null)try {
            while(environment.getOutputBuffers().size()>previousBuffers.size())environment.popOutputBuffer();
            List<OutputBuffer> active=new ArrayList<>(environment.getOutputBuffers());
            for(int i=active.size()-1;i>=0;i--)if(active.get(i).getBufferSize()!=0)active.get(i).flush();
        }catch(Throwable cleanup){failure=compose(failure,unwrapWriter(cleanup));}
        if(mutated && !outputScopes.isEmpty())try{writer.finish();}catch(Throwable cleanup){failure=compose(failure,cleanup);}
        for(int i=outputScopes.size()-1;i>=0;i--)try{outputScopes.get(i).close();}catch(Throwable cleanup){failure=compose(failure,cleanup);}
        if(!outputScopes.isEmpty()){environment.getOutputBuffers().clear();environment.getOutputBuffers().addAll(previousBuffers);}
        if(execution!=null)try{execution.close();}catch(Throwable cleanup){failure=compose(failure,cleanup);}
        if(failure!=null)ScriptContextScope.<Exception>raise(failure);
    }
    Throwable unwrapWriter(Throwable failure) {
        java.io.IOException exact=writer.failure();
        if(exact==null)return failure;
        // PHP's JavaException carries the actual Java failure outside Throwable.getCause().
        Set<Throwable> seen=Collections.newSetFromMap(new IdentityHashMap<>());
        List<Throwable> transports=new ArrayList<>();
        for(Throwable current=failure;current!=null && seen.add(current);) {
            if(current==exact) {
                for(Throwable transport:transports)for(Throwable cleanup:transport.getSuppressed()) {
                    if(cleanup==exact || seen.contains(cleanup))continue;
                    boolean duplicate=false;
                    for(Throwable previous:exact.getSuppressed())if(previous==cleanup){duplicate=true;break;}
                    if(!duplicate)exact.addSuppressed(cleanup);
                }
                return exact;
            }
            transports.add(current);
            Throwable carried=current instanceof php.runtime.ext.java.JavaException
                    ? ((php.runtime.ext.java.JavaException)current).getThrowable():null;
            current=carried==null?current.getCause():carried;
        }
        return failure;
    }
    private static Throwable compose(Throwable primary,Throwable cleanup){if(primary==null)return cleanup;if(primary!=cleanup)primary.addSuppressed(cleanup);return primary;}
    @SuppressWarnings("unchecked") private static <E extends Throwable> void raise(Throwable failure)throws E{throw (E)failure;}

    /** Host publications retain actual certified values; Java reachability is never an admission certificate. */
    static final class HostRoots {
        private final Map<Bindings,Map<String,Retained>> bindingsRoots=new IdentityHashMap<>();
        private final List<PhpOwnershipDomain.Owned> results=new ArrayList<>();
        private static final class Retained {
            final Object actual;
            final PhpOwnershipDomain.Owned root;
            Retained(Object actual,PhpOwnershipDomain.Owned root){this.actual=actual;this.root=root;}
        }
        void reconcile(Bindings bindings)throws Throwable {
            Map<String,Retained> roots=bindingsRoots.get(bindings);if(roots==null)return;
            Throwable failure=null;
            for(var iterator=roots.entrySet().iterator();iterator.hasNext();) {
                var entry=iterator.next();
                if(bindings.get(entry.getKey())!=entry.getValue().actual) {
                    iterator.remove();try{entry.getValue().root.close();}catch(Throwable cleanup){failure=compose(failure,cleanup);}
                }
            }
            if(roots.isEmpty())bindingsRoots.remove(bindings);
            if(failure!=null)raise(failure);
        }
        void publish(Bindings bindings,String name,Memory value,PhpRuntimeOwnership.Authority authority)throws Throwable {
            PhpOwnershipDomain.Owned root=null;
            if(authority!=null) {
                var actual=authority.value(value,PhpRuntimeOwnership.Role.VALUE);
                if(actual.node()!=null)root=PhpOwnershipValues.bindValue(authority.domain(),value,actual.node()).host();
            }
            try{bindings.put(name,value);}catch(Throwable failure){if(root!=null)try{root.close();}catch(Throwable cleanup){failure=compose(failure,cleanup);}throw failure;}
            Map<String,Retained> roots=bindingsRoots.get(bindings);Retained previous=roots==null?null:roots.remove(name);
            if(root!=null)bindingsRoots.computeIfAbsent(bindings,ignored->new LinkedHashMap<>()).put(name,new Retained(value,root));
            if(previous!=null)previous.root.close();
        }
        void remove(Bindings bindings,String name)throws Throwable {
            bindings.remove(name);Map<String,Retained> roots=bindingsRoots.get(bindings);
            Retained removed=roots==null?null:roots.remove(name);if(removed!=null)removed.root.close();
        }
        Memory result(PhpRuntimeOwnership.Returned returned,PhpRuntimeOwnership.Authority authority)throws Throwable {
            var holder=returned.holder();Memory value=returned.raw();
            if(holder.role()!=PhpRuntimeOwnership.Role.VALUE)throw new IllegalArgumentException("ScriptContext result needs its actual semantic VALUE export");
            if(holder.node()!=null)results.add(PhpOwnershipValues.bindValue(authority.domain(),value,holder.node()).host());
            return value;
        }
        void close(Environment environment)throws Throwable {
            if(bindingsRoots.isEmpty() && results.isEmpty())return;
            var authority=PhpRuntimeOwnership.current(environment);
            if(authority==null)throw new IllegalStateException("Certified ScriptContext custody lost its actual Environment authority");
            Throwable failure=null;
            try(var thread=environment.bindCurrentThread();var execution=authority.enter()) {
                for(var roots:bindingsRoots.values())for(var retained:roots.values())try{retained.root.close();}catch(Throwable cleanup){failure=compose(failure,cleanup);}
                bindingsRoots.clear();
                for(var result:results)try{result.close();}catch(Throwable cleanup){failure=compose(failure,cleanup);}
                results.clear();
            }
            if(failure!=null)raise(failure);
        }
    }
}
