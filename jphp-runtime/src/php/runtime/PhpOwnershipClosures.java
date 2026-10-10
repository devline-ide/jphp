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
        var plan=Objects.requireNonNull(entity.getOwnershipCapturePlan(),"Missing lexical capture capability");
        Class<?> type=entity.getNativeClass();
        if (!plan.emptyShellConstructorVerified() || type == null || type.getSuperclass() != Closure.class
                || !(type.getClassLoader() instanceof RuntimeClassLoader)
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
            if (arrays.certificate((ReferenceMemory)raw).node() != value.node()) throw new IllegalArgumentException("Capture CELL lacks its allocation origin");
        } else if (raw instanceof ArrayMemory && arrays.certificate((ArrayMemory)raw).node() != value.node())
            throw new IllegalArgumentException("Capture ARRAY lacks its allocation origin");
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
        if (captures.size() != plan.entries().size()) throw new IllegalArgumentException("Incomplete ordered capture plan");
        List<PhpOwnershipValues.Binding> prepared=new ArrayList<>();
        for (int i=0;i<captures.size();i++) {
            var capture=captures.get(i);candidate(capture);
            boolean cell=plan.entries().get(i).mode() == ClosureEntity.CaptureMode.CELL;
            if (cell != (capture.role() == PhpOwnershipValues.Role.CELL)) throw new IllegalArgumentException("Capture mode differs from its AST descriptor");
            prepared.add(cell ? capture : capture.view(capture.rawValue().toImmutable()));
        }
        // Hold every actual child before factory/default hooks or later publication can call PHP.
        List<PhpOwnershipDomain.Owned> staged=new ArrayList<>();
        PhpOwnershipObjects.Allocation shell=null;Throwable failure=null;
        try {
            staged.add(self.host());for (var capture:prepared) staged.add(capture.host());
            shell=objects.allocate(provider.shell,environment,Collections.emptyMap());
            Certificate certificate=new Certificate(provider,(Closure)shell.object(),shell.certificate(),self,prepared);
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
        private boolean initialized;
        private Certificate(Provider provider,Closure closure,PhpOwnershipObjects.ObjectCertificate object,
                            PhpOwnershipValues.Binding self,List<PhpOwnershipValues.Binding> captures) {
            this.provider=provider;this.closure=closure;this.object=object;this.self=self;this.captures=List.copyOf(captures);
            selfEdge=domain().graph().edge(object.node());staticsEdge=domain().graph().edge(object.node());
            for (int i=0;i<captures.size();i++) captureEdges.add(domain().graph().edge(object.node()));
            statics=new PhpClosureStaticStorage(this,arrays);
        }
        public PhpOwnershipObjects.ObjectCertificate objectCertificate() {return object;}
        public PhpOwnershipDomain domain() {return PhpOwnershipClosures.this.domain();}
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
            } else if (raw instanceof ArrayMemory) node=arrays.certificate((ArrayMemory)raw).node();
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
