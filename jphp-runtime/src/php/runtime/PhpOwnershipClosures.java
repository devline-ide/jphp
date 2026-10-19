package php.runtime;

import php.runtime.env.Environment;
import php.runtime.lang.Closure;
import php.runtime.loader.RuntimeClassLoader;
import php.runtime.memory.*;
import php.runtime.reflection.ClassEntity;
import php.runtime.reflection.helper.ClosureEntity;
import java.lang.reflect.Constructor;
import java.util.*;

/** Actual lexical Closure holders. This does not certify invocation bodies or enable compiler owner accounting. */
public final class PhpOwnershipClosures {
    private final PhpOwnershipObjects objects;
    private final PhpOwnershipArrays arrays;
    private final IdentityHashMap<ClosureEntity,Provider> providers=new IdentityHashMap<>();
    public PhpOwnershipClosures(PhpOwnershipObjects objects,PhpOwnershipArrays arrays) {
        this.objects=Objects.requireNonNull(objects);this.arrays=Objects.requireNonNull(arrays);
        if (objects.domain() != arrays.domain()) throw new IllegalArgumentException("Closure adapters belong to different domains");
    }
    public PhpOwnershipDomain domain() {return objects.domain();}
    static void validateLexicalShell(ClosureEntity entity,Constructor<?> constructor) {
        var plan=entity.getOwnershipCapturePlan();
        if (plan == null) throw new IllegalArgumentException("Missing lexical capture capability");
        Class<?> type=entity.getNativeClass();
        if (!plan.emptyShellConstructorVerified() || type == null || type.getSuperclass() != Closure.class
                || !(type.getClassLoader() instanceof RuntimeClassLoader)
                || !((RuntimeClassLoader)type.getClassLoader()).matchesDefinedBytecode(type,entity.getData())
                || !type.getName().equals(plan.generatedClassName().replace('/','.'))
                || constructor.getDeclaringClass() != type
                || !Arrays.equals(constructor.getParameterTypes(),new Class<?>[]{Environment.class,ClassEntity.class,Memory.class,String.class,Memory[].class})
                || entity.getGeneratorEntity() != null)
            throw new IllegalArgumentException("Closure needs its exact verified generated empty-shell protocol");
    }
    public Provider registerLexical(ClosureEntity entity,Constructor<?> constructor,
                                    PhpOwnershipObjects.CompletePropertiesProtocol shellProtocol) {
        domain().currentContext();validateLexicalShell(entity,constructor);
        if (providers.containsKey(entity)) throw new IllegalStateException("Lexical Closure provider already registered");
        Provider provider=new Provider(entity,constructor,objects.registerLexicalClosureProvider(entity,constructor,shellProtocol));
        providers.put(entity,provider);return provider;
    }
    public final class Provider {
        private final ClosureEntity entity;
        private final Constructor<?> constructor;
        private final PhpOwnershipObjects.Provider shell;
        private Provider(ClosureEntity entity,Constructor<?> constructor,PhpOwnershipObjects.Provider shell) {
            this.entity=entity;this.constructor=constructor;this.shell=shell;
        }
        public ClosureEntity entity() {return entity;}
    }
    private void candidate(PhpOwnershipValues.Binding value) {
        if (value == null || value.domain() != domain()) throw new IllegalArgumentException("Closure child belongs to another domain");
        value.requireCertifiedObject();Memory raw=value.rawValue();
        if (value.role() == PhpOwnershipValues.Role.CELL) {
            var cell=(ReferenceMemory)raw;var runtime=cell.getRuntimeCell();
            if (runtime != null) {
                if (runtime.authority().domain() != domain() || runtime.node() != value.node())
                    throw new IllegalArgumentException("Capture CELL lacks its exact runtime origin");
            } else if (arrays.certificate(cell).node() != value.node()) throw new IllegalArgumentException("Capture CELL lacks its allocation origin");
        } else if (raw instanceof ArrayMemory) {
            var array=(ArrayMemory)raw;var runtime=array.getRuntimeOrigin();
            if (runtime != null) {
                runtime.validate(array);
                if (runtime.authority().domain() != domain() || runtime.node() != value.node())
                    throw new IllegalArgumentException("Capture ARRAY lacks its exact runtime origin");
            } else if (arrays.certificate(array).node() != value.node()) throw new IllegalArgumentException("Capture ARRAY lacks its allocation origin");
        }
    }
    private static Memory holderView(Memory value) {
        return value instanceof ObjectMemory ? new ObjectMemory(((ObjectMemory)value).value) : value.toImmutable();
    }
    public Allocation allocate(Provider provider,Environment environment,PhpOwnershipValues.Binding self,
                               String lexicalScope,List<PhpOwnershipValues.Binding> captures) throws Throwable {
        domain().currentContext();Objects.requireNonNull(captures);
        if (provider == null || providers.get(provider.entity) != provider) throw new IllegalArgumentException("Foreign lexical provider");
        validateLexicalShell(provider.entity,provider.constructor);var plan=provider.entity.getOwnershipCapturePlan();
        candidate(self);
        if (self.role() != PhpOwnershipValues.Role.VALUE || !self.rawValue().isNull() && !(self.rawValue() instanceof ObjectMemory)
                || plan.staticClosure() && !self.rawValue().isNull())
            throw new IllegalArgumentException("Lexical Closure self must be its explicit object/null VALUE");
        self=self.view(holderView(self.rawValue()));
        if (captures.size() != plan.entries().size()) throw new IllegalArgumentException("Incomplete ordered capture plan");
        List<PhpOwnershipValues.Binding> prepared=new ArrayList<>();
        for (int i=0;i<captures.size();i++) {
            var capture=captures.get(i);candidate(capture);
            boolean cell=plan.entries().get(i).mode() == ClosureEntity.CaptureMode.CELL;
            if (cell != (capture.role() == PhpOwnershipValues.Role.CELL)) throw new IllegalArgumentException("Capture mode differs from its AST descriptor");
            prepared.add(cell ? capture : capture.view(holderView(capture.rawValue())));
        }
        // Hold every actual child before factory/default hooks or later publication can call PHP.
        List<PhpOwnershipDomain.Owned> staged=new ArrayList<>();
        PhpOwnershipObjects.Allocation shell=null;Throwable failure=null;
        try {
            staged.add(self.host());for (var capture:prepared) staged.add(capture.host());
            shell=objects.allocate(provider.shell,environment,Collections.emptyMap());
            Certificate certificate=new Certificate(provider,(Closure)shell.object(),shell.certificate(),self,prepared,environment);
            shell.certificate().attachClosure(certificate);certificate.initialize(lexicalScope,plan.staticClosure(),plan.usesThis());
            Allocation allocation=new Allocation(shell,certificate);shell=null;return allocation;
        } catch (Throwable error) {failure=error;throw error;}
        finally {
            Throwable cleanup=null;
            if (shell != null) try {shell.close();} catch (Throwable error) {cleanup=error;}
            for (int i=staged.size()-1;i>=0;i--) try {staged.get(i).close();} catch (Throwable error) {cleanup=domain().graph().composeFailure(cleanup,error);}
            if (cleanup != null) {
                if (failure == null) throw cleanup;
                // Preserve the original construction failure through the configured PHP/host composition.
                Throwable composed=domain().graph().composeFailure(failure,cleanup);if (composed != failure) throw composed;
            }
        }
    }
    /** The verified empty shell is an actual EVAL before evaluating any capture. */
    public static RuntimeAllocation prepareRuntime(PhpRuntimeOwnership.Frame frame,int evalId,ClosureEntity entity) throws Throwable {
        Objects.requireNonNull(frame);var authority=frame.authority();
        if (authority.currentFrame() != frame)throw new IllegalArgumentException("Closure producer needs its current actual frame");
        var adapter=authority.closures();var provider=authority.lexicalProvider(entity);
        validateLexicalShell(entity,provider.constructor);
        var shell=adapter.objects.allocate(provider.shell,authority.environment(),Collections.emptyMap());
        Closure closure=(Closure)shell.object();var certificate=shell.certificate();
        try {frame.stageAllocation(evalId,shell);}
        catch(Throwable failure) {try {shell.close();}catch(Throwable cleanup){failure=adapter.domain().graph().composeFailure(failure,cleanup);}throw failure;}
        return adapter.new RuntimeAllocation(frame,evalId,provider,closure,certificate);
    }
    public final class RuntimeAllocation {
        private final PhpRuntimeOwnership.Frame frame;
        private final int evalId;
        private final Provider provider;
        private final Closure closure;
        private final PhpOwnershipObjects.ObjectCertificate object;
        private final List<PhpOwnershipValues.Binding> captures=new ArrayList<>();
        private final List<PhpRuntimeOwnership.Diagnostic> pending=new ArrayList<>();
        private PhpOwnershipValues.Binding self;
        private String scope;
        private boolean completed;
        private RuntimeAllocation(PhpRuntimeOwnership.Frame frame,int evalId,Provider provider,Closure closure,
                                  PhpOwnershipObjects.ObjectCertificate object) {
            this.frame=frame;this.evalId=evalId;this.provider=provider;this.closure=closure;this.object=object;
        }
        private void open() {
            domain().currentContext();
            if (completed || frame.authority().currentFrame() != frame || frame.evalHolder(evalId) == null)
                throw new IllegalStateException("Closure producer is no longer its actual evaluation");
            object.validateObject(closure);
        }
        private PhpOwnershipValues.Binding stage(Memory raw,PhpRuntimeOwnership.Role role) throws Throwable {
            var value=frame.authority().value(raw,role);
            var binding=role == PhpRuntimeOwnership.Role.CELL ? PhpOwnershipValues.bindCell(domain(),(ReferenceMemory)raw,value.node())
                    : PhpOwnershipValues.bindValue(domain(),raw,value.node());
            candidate(binding);pending.add(frame.pinTemporary(raw,role));return binding;
        }
        public void self(Memory raw,String lexicalScope) throws Throwable {
            open();if (self != null || !captures.isEmpty())throw new IllegalStateException("Closure self must precede captures exactly once");
            if (!raw.isNull() && !(raw instanceof ObjectMemory) || provider.entity.getOwnershipCapturePlan().staticClosure() && !raw.isNull())
                throw new IllegalArgumentException("Closure self must be its exact object/null VALUE");
            self=stage(raw,PhpRuntimeOwnership.Role.VALUE);scope=lexicalScope;
        }
        public void capture(int index,Memory raw) throws Throwable {
            open();var plan=provider.entity.getOwnershipCapturePlan();
            if (self == null || index != captures.size() || index >= plan.entries().size())
                throw new IllegalArgumentException("Capture producer does not follow its exact AST order");
            boolean cell=plan.entries().get(index).mode() == ClosureEntity.CaptureMode.CELL;
            captures.add(stage(raw,cell ? PhpRuntimeOwnership.Role.CELL : PhpRuntimeOwnership.Role.VALUE));
        }
        public Memory complete() throws Throwable {
            open();var plan=provider.entity.getOwnershipCapturePlan();
            if (self == null || captures.size() != plan.entries().size())throw new IllegalArgumentException("Incomplete Closure capture producers");
            Certificate certificate=new Certificate(provider,closure,object,self,captures,frame.authority().environment());
            domain().graph().deferDestruction(() -> {
                object.attachClosure(certificate);certificate.initialize(scope,plan.staticClosure(),plan.usesThis());
                completed=true;
                for (int i=pending.size()-1;i>=0;i--)pending.get(i).close();pending.clear();
            });return new ObjectMemory(closure);
        }
    }
    /** A generated CV clone uses its recorded publication and current revision, not a carrier guess. */
    public static Memory cloneLocalRuntime(PhpRuntimeOwnership.Frame frame,int resultEval,int localId,Memory operand,
                                           Environment environment,php.runtime.env.TraceInfo trace) throws Throwable {
        if (frame == null) return operand.clone(environment,trace);
        if (PhpRuntimeOwnership.current(environment) != frame.authority() || frame.authority().currentFrame() != frame)
            throw new IllegalArgumentException("Closure clone needs its exact current invocation frame");
        Memory source=frame.borrowLocalCloneSource(resultEval,localId,operand);
        // The source CV remains an owner while the fresh allocation is handed to its actual EVAL.
        return cloneRuntime(frame,resultEval,source,environment,trace);
    }
    /** Clone keeps the source alive until the fresh OBJECT is published into its actual EVAL. */
    public static Memory cloneRuntime(PhpRuntimeOwnership.Frame frame,int evalId,Memory source,
                                      Environment environment,php.runtime.env.TraceInfo trace) throws Throwable {
        if (frame == null) return source.clone(environment,trace);
        if (PhpRuntimeOwnership.current(environment) != frame.authority() || frame.authority().currentFrame() != frame)
            throw new IllegalArgumentException("Closure clone needs its exact current invocation frame");
        if (source instanceof ReferenceMemory) {
            boolean address=false;Set<Memory> seen=Collections.newSetFromMap(new IdentityHashMap<>());
            while (source instanceof ReferenceMemory) {
                if (!seen.add(source))throw new IllegalArgumentException("Cyclic clone source address");
                ReferenceMemory carrier=(ReferenceMemory)source;
                var holder=carrier.getRuntimeHolder();
                if (source.getClass() == php.runtime.memory.support.ArrayMapEntryMemory.class) {
                    if (holder == null || holder.domain() != frame.authority().domain() || !holder.matches(carrier))
                        throw new IllegalArgumentException("Clone bucket lacks its exact same-domain publisher");
                } else if (source.getClass() != ReferenceMemory.class)
                    throw new IllegalArgumentException("Clone requires a pure actual reference address");
                address|=holder != null || carrier.getRuntimeCell() != null;
                source=carrier.getRawValue();
            }
            if (!address)throw new IllegalArgumentException("Clone address lacks its actual runtime publisher");
        }
        if (!(source instanceof ObjectMemory) || !(((ObjectMemory)source).value instanceof Closure))
            throw new IllegalArgumentException("Ordinary clone requires its exact supported allocation protocol");
        Closure closure=(Closure)((ObjectMemory)source).value;var certificate=closure.ownershipClosure();
        var allocation=certificate.cloneRuntime(frame,environment,certificate.selfBinding(),closure.getScope());
        return frame.stageAllocation(evalId,allocation);
    }
    public final class Allocation implements AutoCloseable {
        private final PhpOwnershipObjects.Allocation shell;
        private final Certificate certificate;
        private Allocation(PhpOwnershipObjects.Allocation shell,Certificate certificate) {this.shell=shell;this.certificate=certificate;}
        public Closure closure() {shell.object();certificate.validate();return certificate.closure;}
        public Certificate certificate() {closure();return certificate;}
        public PhpOwnershipValues.Binding binding() {closure();return shell.binding();}
        @Override public void close() throws Exception {shell.close();}
    }
    public final class Certificate {
        private final Provider provider;
        private final Closure closure;
        private final PhpOwnershipObjects.ObjectCertificate object;
        private final PhpOwnershipValues.Binding self;
        private final List<PhpOwnershipValues.Binding> captures;
        private final PhpOwnership.Slot selfEdge,staticsEdge;
        private final List<PhpOwnership.Slot> captureEdges=new ArrayList<>();
        private final PhpClosureStaticStorage statics;
        private final PhpRuntimeOwnership.Authority runtime;
        private boolean initialized;
        private Certificate(Provider provider,Closure closure,PhpOwnershipObjects.ObjectCertificate object,
                            PhpOwnershipValues.Binding self,List<PhpOwnershipValues.Binding> captures,Environment environment) {
            this.provider=provider;this.closure=closure;this.object=object;this.self=self;this.captures=List.copyOf(captures);
            runtime=PhpOwnershipExecution.current(environment) == null ? PhpRuntimeOwnership.current(environment) : null;
            if (runtime != null && (runtime.domain() != domain() || runtime.currentFrame() == null))
                throw new IllegalArgumentException("Closure allocation needs its same-domain actual frame");
            selfEdge=domain().graph().edge(object.node());staticsEdge=domain().graph().edge(object.node());
            for (int i=0;i<captures.size();i++) captureEdges.add(domain().graph().edge(object.node()));
            statics=new PhpClosureStaticStorage(this,arrays);
        }
        public PhpOwnershipObjects.ObjectCertificate objectCertificate() {return object;}
        public PhpOwnershipDomain domain() {return PhpOwnershipClosures.this.domain();}
        PhpRuntimeOwnership.Authority runtimeAuthority() {return runtime;}
        void validateChild(PhpOwnershipValues.Binding value) {validate();candidate(value);}
        public void validate() {object.validateObject(closure);if (object.rawClosureDescriptor() != this) throw new IllegalArgumentException("Closure descriptor origin changed");}
        public PhpClosureStaticStorage staticStorage() {validate();return statics;}
        public PhpOwnershipValues.Binding selfBinding() {validate();return self;}
        public List<PhpOwnershipValues.Binding> captureBindings() {validate();return captures;}
        /** Explicit VALUE read: no structural references, virtual getters or implicit allocation. */
        public PhpOwnershipValues.Binding semanticValue(Memory raw) {
            validate();PhpOwnership.Node node=null;
            if (raw instanceof ObjectMemory) {
                var child=((ObjectMemory)raw).value;
                if (!(child instanceof php.runtime.lang.support.IPhpOwnershipObject)
                        || ((php.runtime.lang.support.IPhpOwnershipObject)child).getOwnershipObject() == null)
                    throw new IllegalArgumentException("Static VALUE lacks an actual OBJECT origin");
                node=((php.runtime.lang.support.IPhpOwnershipObject)child).getOwnershipObject().node();
            } else if (raw instanceof ArrayMemory) {
                var array=(ArrayMemory)raw;var runtime=array.getRuntimeOrigin();
                if (runtime != null) {runtime.validate(array);if (runtime.authority().domain() != domain()) throw new IllegalArgumentException("Foreign static ARRAY origin");node=runtime.node();}
                else node=arrays.certificate(array).node();
            }
            var value=PhpOwnershipValues.bindValue(domain(),raw,node);candidate(value);return value;
        }
        private void initialize(String scope,boolean isStatic,boolean readsThis) throws Throwable {
            Memory[] empty=new Memory[captures.size()];Arrays.fill(empty,Memory.NULL);
            closure.initializeOwnershipHolders(this,empty,statics,scope,isStatic,readsThis);
            domain().graph().deferDestruction(() -> {
                publish(selfEdge,self,-1);
                for (int i=0;i<captures.size();i++) publish(captureEdges.get(i),captures.get(i),i);
                staticsEdge.replace(statics.node());initialized=true;
            });
        }
        private void publish(PhpOwnership.Slot edge,PhpOwnershipValues.Binding value,int index) throws Throwable {
            RawStore store=new RawStore(this,index,value.rawValue());
            try (var publication=edge.preparePublication(value.node())) {
                store.delegate=publication;
                if (index < 0) closure.publishOwnershipSelf(store);else closure.publishOwnershipCapture(store);
                publication.commit();
            }
        }
        public boolean consumeInitialization(Closure actual) {
            if (actual != closure || initialized || initializationConsumed) return false;
            initializationConsumed=true;return true;
        }
        private boolean initializationConsumed;
        /** A real new OBJECT identity; CELL sharing is deliberately unavailable until semantic owners are connected. */
        public Allocation cloneValues(Environment environment,PhpOwnershipValues.Binding newSelf,String scope) throws Throwable {
            validate();candidate(newSelf);
            for (var capture:captures) if (capture.role() == PhpOwnershipValues.Role.CELL)
                throw new IllegalStateException("Certified reference capture clone needs complete semantic owner accounting");
            var values=statics.copyableValues(); // Reject every CELL before new shell/provider effects.
            return cloneWithStatics(environment,newSelf,scope,values);
        }
        /** Connected clone consults actual physical CELL incoming edges while the source is alive. */
        public Allocation cloneRuntime(PhpRuntimeOwnership.Frame frame,Environment environment,
                                       PhpOwnershipValues.Binding newSelf,String scope) throws Throwable {
            validate();candidate(newSelf);
            if (frame.authority().domain() != domain() || runtimeAuthority() == null)
                throw new IllegalArgumentException("Closure clone lacks its connected frame");
            return cloneWithStatics(environment,newSelf,scope,statics.runtimeCloneValues(frame));
        }
        private Allocation cloneWithStatics(Environment environment,PhpOwnershipValues.Binding newSelf,
                                            String scope,Map<String,PhpOwnershipValues.Binding> values) throws Throwable {
            List<PhpOwnershipDomain.Owned> pending=new ArrayList<>();Throwable primary=null;
            try {
                for (var value:values.values()) pending.add(value.host());
                Allocation copy=allocate(provider,environment,newSelf,scope,captures);
                try {for (var entry:values.entrySet()) copy.certificate.statics.getOrCreate(entry.getKey(),entry.getValue());return copy;}
                catch (Throwable failure) {try {copy.close();} catch (Throwable cleanup) {failure=domain().graph().composeFailure(failure,cleanup);}throw failure;}
            } catch (Throwable error) {primary=error;throw error;}
            finally {
                Throwable cleanup=null;
                for (int i=pending.size()-1;i>=0;i--) try {pending.get(i).close();} catch (Throwable error) {cleanup=domain().graph().composeFailure(cleanup,error);}
                if (cleanup != null) {Throwable result=domain().graph().composeFailure(primary,cleanup);if (result != primary) throw result;}
            }
        }
    }
    /** Single-use exact field permit; obtaining the descriptor is not permission to mutate a holder. */
    public static final class RawStore {
        private final Certificate certificate;
        private final int index;
        private final Memory value;
        private PhpOwnership.Publication delegate;
        private boolean consumed;
        private RawStore(Certificate certificate,int index,Memory value) {this.certificate=certificate;this.index=index;this.value=value;}
        public Memory consume(Closure actual,int expectedIndex) {
            certificate.domain().graph().checkExecutionAccess();
            if (consumed || actual != certificate.closure || index != expectedIndex || delegate == null || delegate.isClosed())
                throw new IllegalArgumentException("Closure raw field permit is stale or foreign");
            consumed=true;return value;
        }
        public int index(Closure actual) {
            if (actual != certificate.closure || index < 0) throw new IllegalArgumentException("Not a capture field permit");return index;
        }
    }
}
