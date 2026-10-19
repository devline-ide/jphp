package php.runtime;

import php.runtime.env.*;
import php.runtime.exceptions.FinallyException;
import php.runtime.lang.exception.*;
import php.runtime.memory.*;
import php.runtime.reflection.*;
import java.util.*;

/** Exact engine TypeError allocation and failure custody; native ancestry is not a capability. */
public final class PhpOwnershipThrowables {
    private PhpOwnershipThrowables() {}
    public record TraceFrame(CallStackItem metadata,List<PhpOwnershipValues.Binding> arguments) {
        public TraceFrame {Objects.requireNonNull(metadata);arguments=List.copyOf(arguments);}
    }
    public static final class Certificate {
        private final PhpOwnershipDomain domain;
        private final PhpOwnershipArrays arrays;
        private final Environment originEnvironment;
        private final BaseTypeError object;
        private final ClassEntity entity;
        private final PhpOwnership.Node node;
        private final ArrayMemory properties;
        private final PhpOwnershipArrays.StorageCertificate storage;
        private PhpOwnership.Slot propertiesEdge;
        private PhpOwnershipFramePublisher custody;
        private PhpOwnershipArrays.Entry previous;
        private boolean initialized;
        private Certificate(PhpOwnershipExecution.Session session,BaseTypeError object,ArrayMemory properties,
                            PhpOwnershipArrays.StorageCertificate storage) {
            this.domain=session.domain();this.arrays=session.arrays();this.originEnvironment=session.environment();this.object=object;this.entity=object.getReflection();this.properties=properties;this.storage=storage;
            node=session.domain().object(object,new PhpOwnershipDomain.Lifecycle() {
                @Override public void destroy(PhpOwnership.Node ignored,Object releasing) {
                    custody=null;object.clearOwnershipThrowable(Certificate.this);
                }
            });
        }
        public PhpOwnership.Node node(){return node;}
        public void validateBinding(PhpOwnershipDomain domain,PhpOwnership.Node actual,php.runtime.lang.IObject identity) {
            if(domain!=this.domain || actual!=node || identity!=object || object.getClass()!=BaseTypeError.class
                    || object.getOwnershipThrowable()!=this || object.getReflection()!=entity || object.getEnvironment()!=originEnvironment || !initialized || node.state()==PhpOwnership.State.DISPOSED)
                throw new IllegalArgumentException("TypeError allocation lacks its exact complete native origin");
            if(arrays.certificate(properties)!=storage)throw new IllegalArgumentException("Native throwable backing certificate changed");
        }
        public void validateAttachment(BaseBaseException actual,ArrayMemory table) {
            if(actual!=object || actual.getClass()!=BaseTypeError.class || table!=properties || initialized)
                throw new IllegalArgumentException("Native TypeError attachment is not its private allocation");
            if(arrays.certificate(table)!=storage)throw new IllegalArgumentException("Native throwable backing certificate changed");
        }
        public void validateProperties(BaseBaseException actual,ArrayMemory table) {
            if(actual!=object || table!=properties || object.getOwnershipThrowable()!=this || object.getReflection()!=entity || !initialized || node.state()==PhpOwnership.State.DISPOSED)
                throw new IllegalArgumentException("Native throwable property holder changed");
        }
        void moveTo(PhpOwnershipFramePublisher destination)throws Throwable {
            domain.currentContext();
            if(custody==null)throw new IllegalStateException("Native PHP failure has no live custody");
            if(custody==destination)return;
            var source=custody;var raw=source.current().binding().rawValue();
            try(var publication=source.prepareTransferTo(source.current(),destination,destination.current(),raw)) {
                custody=destination;try{publication.commit();}finally{if(!publication.isCommitted())custody=source;}
            }
        }
        PhpOwnershipFramePublisher custody() {return custody;}
        void claim(PhpOwnershipFramePublisher publisher) {
            var binding=publisher.current().binding();
            if(binding==null || binding.node()!=node || publisher.domain()!=domain)throw new IllegalArgumentException("Throwable custody is not its actual live owner");
            custody=publisher;
        }
        void forget(PhpOwnershipFramePublisher publisher) {if(custody==publisher)custody=null;}
        PhpOwnershipValues.Binding binding(PhpOwnershipExecution.Session actual) {
            if(actual.domain()!=domain)throw new IllegalArgumentException("Throwable belongs to another ownership domain");
            return actual.value(new ObjectMemory(object));
        }
        void staged(PhpOwnershipFramePublisher publisher) {
            if(custody!=null || publisher.current().binding()==null || publisher.current().binding().node()!=node)
                throw new IllegalArgumentException("Native failure stage is not its actual OBJECT owner");
            custody=publisher;
        }
    }
    public static Certificate certificate(Throwable error) {
        if(error==null || error.getClass()!=BaseTypeError.class)return null;
        return ((BaseTypeError)error).getOwnershipThrowable();
    }
    static Certificate require(PhpOwnershipExecution.Session session,BaseBaseException error) {
        var certificate=certificate(error);if(certificate==null)throw new IllegalArgumentException("PHP throwable lacks its exact complete native origin");
        certificate.binding(session);return certificate;
    }
    /** Publish a real previous-property edge before releasing the replaced pending exception. */
    static BaseBaseException chain(PhpOwnershipExecution.Session session,BaseBaseException replacement,BaseBaseException pending)throws Throwable {
        if(replacement==pending)return replacement;
        if(replacement.getClass()==FinallyException.class || pending.getClass()==FinallyException.class)return replacement;
        require(session,replacement);require(session,pending);
        Set<BaseBaseException> pendingChain=Collections.newSetFromMap(new IdentityHashMap<>());
        for(BaseBaseException current=pending;current!=null;current=previous(session,current))if(!pendingChain.add(current))return replacement;
        Set<BaseBaseException> visited=Collections.newSetFromMap(new IdentityHashMap<>());
        BaseBaseException current=replacement;
        while(visited.add(current)) {
            if(pendingChain.contains(current))return replacement;
            var certificate=require(session,current);var next=certificate.previous.current().binding();
            if(next.rawValue().isNull()){certificate.previous.assign(session.value(new ObjectMemory(pending)));return replacement;}
            current=previous(session,current);if(current==null)return replacement;
        }
        return replacement;
    }
    private static BaseBaseException previous(PhpOwnershipExecution.Session session,BaseBaseException error) {
        var raw=require(session,error).previous.current().binding().rawValue();if(raw.isNull())return null;
        if(!(raw instanceof ObjectMemory) || !(((ObjectMemory)raw).value instanceof BaseBaseException))throw new IllegalArgumentException("Throwable previous holder is not a certified PHP exception");
        var value=(BaseBaseException)((ObjectMemory)raw).value;require(session,value);return value;
    }
    public static BaseTypeError typeError(PhpOwnershipExecution.Session session,ClassEntity entity,
                                           TraceInfo trace,String message)throws Throwable {
        Objects.requireNonNull(session);Objects.requireNonNull(entity);Objects.requireNonNull(message);
        if(entity.getNativeClass()!=BaseTypeError.class || session.environment().fetchClass(BaseTypeError.class)!=entity
                || !"TypeError".equals(entity.getName()) || entity.methodDestruct!=null)
            throw new IllegalArgumentException("Engine TypeError class differs from its exact trusted native contract");
        var entry=PhpOwnershipExecution.currentEntry(session.environment());
        if(entry==null || entry.session()!=session)throw new IllegalArgumentException("Engine TypeError needs its actual releasing entry");
        List<TraceFrame> frames=session.captureThrowableTrace();
        var arrays=session.arrays();List<PhpOwnershipArrays.Allocation> leases=new ArrayList<>();
        PhpOwnershipDomain.OwnedValue allocation=null;Certificate certificate=null;
        try {
            var backtrace=arrays.allocate(false);leases.add(backtrace);
            CallStackItem[] metadata=new CallStackItem[frames.size()];
            for(int i=0;i<frames.size();i++) {
                var frame=frames.get(i);CallStackItem item=frame.metadata();
                // Java stack metadata borrows values already represented by the trace STORAGE; no second PHP owner.
                metadata[i]=new CallStackItem(item);metadata[i].object=null;
                metadata[i].args=new Memory[frame.arguments().size()];
                var row=arrays.allocate(true);leases.add(row);
                if(item.trace!=null && item.trace.getFile()!=null)row.insertBucket("file",session.value(new StringMemory(item.trace.getFileName())));
                if(item.trace!=null)row.insertBucket("line",session.value(LongMemory.valueOf(item.trace.getStartLine()+1)));
                row.insertBucket("function",session.value(item.function==null?Memory.NULL:new StringMemory(item.function)));
                if(item.clazz!=null) {
                    row.insertBucket("class",session.value(new StringMemory(item.clazz)));
                    row.insertBucket("type",session.value(new StringMemory(item.object==null?"::":"->")));
                }
                var arguments=arrays.allocate(false);leases.add(arguments);
                for(int n=0;n<frame.arguments().size();n++) {var value=frame.arguments().get(n);arguments.append(value);metadata[i].args[n]=value.rawValue();}
                row.insertBucket("args",arguments.binding());backtrace.append(row.binding());
            }
            var props=arrays.allocate(true);leases.add(props);
            String[] names={"message","code","previous","trace","file","line","position"};
            Memory[] values={new StringMemory(message),Memory.CONST_INT_0,Memory.NULL,backtrace.array(),
                    trace==null || trace.getFile()==null?Memory.NULL:new StringMemory(trace.getFileName()),
                    trace==null?Memory.NULL:LongMemory.valueOf(trace.getStartLine()+1),
                    trace==null?Memory.NULL:LongMemory.valueOf(trace.getStartPosition()+1)};
            PhpOwnershipArrays.Entry previous=null;
            for(int i=0;i<names.length;i++) {
                PropertyEntity property=entity.findProperty(names[i]);
                if(property==null || property.getGetter()!=null || property.isStatic())throw new IllegalArgumentException("Native TypeError property descriptor changed");
                var entryProperty=props.insertBucket(property.getSpecificName(),session.value(values[i]));if(names[i].equals("previous"))previous=entryProperty;
            }
            BaseTypeError error=new BaseTypeError(session.environment(),entity);
            certificate=new Certificate(session,error,props.array(),props.certificate());certificate.previous=previous;
            allocation=session.domain().hostValue(certificate.node,new ObjectMemory(error));
            var exact=certificate;var owned=allocation;
            session.domain().graph().deferDestruction(()->{
                error.attachOwnershipThrowable(exact,props.array(),metadata,trace,message);
                exact.propertiesEdge=session.domain().graph().edge(exact.node);exact.propertiesEdge.replace(props.certificate().node());
                exact.initialized=true;
                var binding=PhpOwnershipValues.bindValue(session.domain(),new ObjectMemory(error),exact.node).requireCertifiedObject();
                exact.staged(entry.stageThrowable(binding,owned));
                for(int i=leases.size()-1;i>=0;i--)leases.get(i).close();leases.clear();
            });
            allocation=null;return error;
        } catch(Throwable primary) {
            if(allocation!=null)try{allocation.close();}catch(Throwable cleanup){primary=session.domain().graph().composeFailure(primary,cleanup);}
            for(int i=leases.size()-1;i>=0;i--)try{leases.get(i).close();}catch(Throwable cleanup){primary=session.domain().graph().composeFailure(primary,cleanup);}
            throw primary;
        }
    }
}
