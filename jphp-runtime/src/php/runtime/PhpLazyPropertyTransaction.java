package php.runtime;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import php.runtime.env.Environment;
import php.runtime.lang.BaseObject;
import php.runtime.memory.PhpObjectProperties;
import php.runtime.memory.ReferenceMemory;
import php.runtime.memory.support.ArrayMapEntryMemory;
import php.runtime.memory.support.ArrayMemoryMap;
import php.runtime.reflection.PropertyEntity;

/** Lazy-operation locals and exact physical TABLE replacement; the table/storage allocation never changes. */
public final class PhpLazyPropertyTransaction implements AutoCloseable {
    public record Row(String key,PhpOwnershipValues.Binding binding,PropertyEntity declaration,boolean explicitlyUnset) {}
    private final PhpLazyObjects.State expectedState;
    private final PhpOwnershipExecution.Session session;
    private final BaseObject object;
    private final PhpOwnershipObjects.ObjectCertificate objectCertificate;
    private final PhpOwnershipObjects.PropertiesCertificate certificate;
    private final PhpOwnershipDomain domain;
    private final PhpOwnershipFrame frame;
    private final List<Row> saved=new ArrayList<>();
    private boolean closed;

    PhpLazyPropertyTransaction(PhpOwnershipValues.Binding actualReceiver)throws Throwable {
        actualReceiver.requireCertifiedObject();
        if(actualReceiver.role()!=PhpOwnershipValues.Role.VALUE||!(actualReceiver.rawValue()instanceof php.runtime.memory.ObjectMemory raw)||!(raw.value instanceof BaseObject base))
            throw new IllegalArgumentException("Lazy transaction needs its actual native receiver VALUE");
        object=base;objectCertificate=base.getOwnershipObject();certificate=objectCertificate.properties();domain=actualReceiver.domain();
        session=PhpOwnershipExecution.current(object.getEnvironment());
        if(session==null||session.domain()!=domain||objectCertificate.domain()!=domain)
            throw new IllegalArgumentException("Lazy transaction lacks its same-domain active Session");
        expectedState=PhpLazyObjects.state(object);frame=new PhpOwnershipFrame(domain.graph());
        try {
            var receiver=domain.heap().framePublisher(frame.receiver("lazy-operation-receiver"));
            try(var publication=receiver.prepare(receiver.current(),actualReceiver)){publication.commit();}
            var table=certificate.table();
            if(table.hasBucketLayout()) {
                for(int i=0;i<table.bucketLimit();i++) {
                    var carrier=table.bucketAt(i);
                    if(carrier==null)saved.add(null);else capture((String)table.bucketKey(i),carrier);
                }
            } else for(var carrier:table) {
                if(!(carrier instanceof ArrayMapEntryMemory entry)||!(entry.getKey()instanceof String key))
                    throw new IllegalArgumentException("Lazy TABLE has a non-property physical entry");
                capture(key,carrier);
            }
            certificate.validateAll();
        } catch(Throwable failure){frame.unwind(failure);throw failure;}
    }
    private void capture(String key,ReferenceMemory carrier)throws Throwable {
        var binding=certificate.entry(carrier).ownerPublisher().current().binding();
        if(binding.role()==PhpOwnershipValues.Role.VALUE&&binding.rawValue() instanceof php.runtime.memory.ArrayMemory array) {
            if(array.getClass()!=php.runtime.memory.ArrayMemory.class)throw new IllegalArgumentException("Lazy snapshot needs an actual ordinary ARRAY value view");
            binding=binding.view(array.toImmutable());
        }
        var holder=domain.heap().framePublisher(frame.temporary("lazy-before:"+saved.size()));
        try(var publication=holder.prepare(holder.current(),binding)){publication.commit();}
        var table=certificate.table();saved.add(new Row(key,binding,table.declaration(key),table.isExplicitlyUnset(key)));
    }
    private void open(){
        if(closed||PhpOwnershipExecution.current(object.getEnvironment())!=session||PhpLazyObjects.state(object)!=expectedState)
            throw new IllegalStateException("Lazy property transaction is closed, foreign or changed metadata");
        objectCertificate.validateObject(object);certificate.validateAll();
    }
    List<Row> saved(){open();return new ArrayList<>(saved);}
    PhpOwnershipValues.Binding local(String label,PhpOwnershipValues.Binding actualSource)throws Throwable {
        open();var holder=domain.heap().framePublisher(frame.temporary(label));
        try(var publication=holder.prepare(holder.current(),actualSource)){publication.commit();}
        return holder.current().binding();
    }
    void replace(List<Row> desired)throws Throwable {open();new Prepared(this,desired).commit();}
    void restore()throws Throwable {replace(saved);}
    @Override public void close()throws Exception {
        if(closed)return;closed=true;
        try{frame.close();}catch(Throwable failure){PhpLazyPropertyTransaction.<Exception>raise(failure);}
    }
    /** Only an actual transaction can create this raw-store permit. It owns no independent object root. */
    public static final class Prepared {
        private final PhpLazyPropertyTransaction transaction;
        private final PhpObjectProperties table;
        private final long beforeRevision;
        private final ReferenceMemory[] beforeCarriers;
        private final PhpOwnershipHeap.Snapshot[] beforeValues;
        private final IdentityHashMap<ReferenceMemory,PhpOwnershipArrays.PhysicalSlot> beforeSlots=new IdentityHashMap<>(),afterSlots=new IdentityHashMap<>();
        private final IdentityHashMap<PhpOwnershipArrays.PhysicalSlot,Boolean> beforeSlotIdentities=new IdentityHashMap<>(),afterSlotIdentities=new IdentityHashMap<>();
        private final IdentityHashMap<ReferenceMemory,Row> freshRows=new IdentityHashMap<>();
        private final ArrayMemoryMap replacement=new ArrayMemoryMap();
        private final java.util.Set<String> unset=new java.util.HashSet<>();
        private final List<ReferenceMemory> freshCarriers=new ArrayList<>();
        private final ReferenceMemory.OwnershipLazySources sources;
        private PhpOwnership.Publication barrier;
        private int count;
        private boolean preparing=true,rawStored,finished;
        private Prepared(PhpLazyPropertyTransaction transaction,List<Row> desired)throws Throwable {
            this.transaction=transaction;transaction.open();table=transaction.certificate.table();
            beforeRevision=transaction.certificate.removalRevision();
            if(beforeRevision==Long.MAX_VALUE)throw new IllegalStateException("Lazy TABLE revision overflow");
            var current=new ArrayList<ReferenceMemory>();for(var carrier:table)current.add(carrier);
            beforeCarriers=current.toArray(new ReferenceMemory[0]);beforeValues=new PhpOwnershipHeap.Snapshot[beforeCarriers.length];
            for(int i=0;i<beforeCarriers.length;i++) {
                var slot=transaction.certificate.entry(beforeCarriers[i]);beforeSlots.put(beforeCarriers[i],slot);beforeSlotIdentities.put(slot,Boolean.TRUE);beforeValues[i]=slot.ownerPublisher().current();
            }
            replacement.enableBucketLayout();
            for(var row:desired) {
                if(row==null){replacement.appendBucketHole();continue;}
                Objects.requireNonNull(row.key());var binding=Objects.requireNonNull(row.binding());binding.requireCertifiedObject();
                if(binding.domain()!=transaction.domain)throw new IllegalArgumentException("Foreign lazy property snapshot");
                if(row.declaration()!=table.declaration(row.key())||row.explicitlyUnset()&&row.declaration()==null)
                    throw new IllegalArgumentException("Lazy replacement cannot invent property declaration metadata");
                if(row.declaration()!=null&&row.declaration().getDeclaredType()!=null&&!binding.rawValue().isUndefined()) {
                    // Validate without publishing or coercing a saved/reference zval.
                    Memory raw=binding.role()==PhpOwnershipValues.Role.CELL?((ReferenceMemory)binding.rawValue()).getRawValue():binding.rawValue();
                    Memory checked=row.declaration().getDeclaredType().strictValue(transaction.object.getEnvironment(),raw,row.declaration().getClazz());
                    if(checked==null||checked!=raw)throw new IllegalArgumentException("Lazy snapshot no longer satisfies its actual typed property");
                }
                var fresh=replacement.insertBucket(row.key(),binding.rawValue());freshRows.put(fresh,row);freshCarriers.add(fresh);count++;
                if(row.explicitlyUnset())unset.add(row.key());
            }
            sources=ReferenceMemory.prepareOwnershipLazySources(this);preparing=false;validate();
        }
        public BaseObject actualObject(){return transaction.object;}
        public Environment environment(){return transaction.object.getEnvironment();}
        public ReferenceMemory[] oldCarriers(){if(!preparing)throw new IllegalStateException("Lazy source preparation finished");return beforeCarriers.clone();}
        public ReferenceMemory[] newCarriers(){if(!preparing)throw new IllegalStateException("Lazy source preparation finished");return freshCarriers.toArray(new ReferenceMemory[0]);}
        public Row sourceRow(ReferenceMemory fresh){if(!preparing)throw new IllegalStateException("Lazy source preparation finished");var row=freshRows.get(fresh);if(row==null)throw new IllegalArgumentException("Foreign lazy fresh carrier");return row;}
        private void validate() {
            transaction.open();transaction.certificate.validateAll();
            if(finished||rawStored||transaction.certificate.table()!=table||transaction.certificate.removalRevision()!=beforeRevision)
                throw new IllegalStateException("Lazy physical TABLE replacement is stale");
            var actual=new ArrayList<ReferenceMemory>();for(var carrier:table)actual.add(carrier);
            if(actual.size()!=beforeCarriers.length)throw new IllegalArgumentException("Lazy TABLE changed during preparation");
            for(int i=0;i<beforeCarriers.length;i++)if(actual.get(i)!=beforeCarriers[i]||beforeSlots.get(beforeCarriers[i]).ownerPublisher().current()!=beforeValues[i])
                throw new IllegalArgumentException("Lazy physical entry changed during preparation");
            if(!preparing)sources.validate();
        }
        private boolean pending(){return !finished&&!rawStored&&barrier!=null&&!barrier.isClosed();}
        public ArrayMemoryMap consumeRaw(PhpObjectProperties actual) {
            transaction.domain.graph().checkExecutionAccess();
            if(actual!=table||!pending())throw new IllegalArgumentException("Wrong lazy raw TABLE permit");
            sources.store(this);rawStored=true;return replacement;
        }
        public int rawSize(PhpObjectProperties actual){if(actual!=table||!rawStored||finished)throw new IllegalArgumentException("Lazy raw size outside publication");return count;}
        public java.util.Set<String> rawUnset(PhpObjectProperties actual){if(actual!=table||!rawStored||finished)throw new IllegalArgumentException("Lazy raw metadata outside publication");return unset;}
        public boolean permitsSources(ReferenceMemory.OwnershipLazySources actual){return actual==sources&&pending();}
        public boolean permitsRetirement(PhpOwnershipArrays.PhysicalSlot actual){return rawStored&&!finished&&beforeSlotIdentities.containsKey(actual);}
        void commit()throws Throwable {
            validate();Throwable failure=null;
            try {
                transaction.domain.graph().deferDestruction(()->{
                    for(var fresh:freshCarriers) {
                        var slot=transaction.session.arrays().attachPhysicalSlot(transaction.certificate.node(),fresh,freshRows.get(fresh).binding());
                        afterSlots.put(fresh,slot);afterSlotIdentities.put(slot,Boolean.TRUE);
                    }
                    validate();transaction.certificate.publishLazyReplacement(this);
                    for(var carrier:beforeCarriers)beforeSlots.get(carrier).retireLazyReplacement(this);
                    finished=true;
                });
            } catch(Throwable error){failure=error;throw error;}
            finally {
                if(!rawStored) {
                    Throwable cleanup=null;
                    for(var slot:afterSlots.values())try{slot.abortLazyPreparation(this);}catch(Throwable error){cleanup=transaction.domain.graph().composeFailure(cleanup,error);}
                    if(cleanup!=null){var composed=transaction.domain.graph().composeFailure(failure,cleanup);if(composed!=failure)throw composed;}
                }
            }
        }
        void storeUsingTableEdge(PhpOwnership.Slot actualEdge,PhpOwnershipObjects.PropertiesCertificate actual)throws Throwable {
            validate();
            if(actual!=transaction.certificate||actualEdge.owner()!=transaction.objectCertificate.node()||actualEdge.target()!=actual.node())
                throw new IllegalArgumentException("Lazy replacement lacks the actual object-to-TABLE holder");
            try(var publication=actualEdge.preparePublication(actual.node())) {
                barrier=publication;table.publishOwnershipLazyReplacement(this);actual.replaceLazyEntries(this,afterSlots);publication.commit();
            } finally {barrier=null;}
        }
        boolean permitsCertificate(PhpOwnershipObjects.PropertiesCertificate actual){return actual==transaction.certificate&&rawStored&&!finished&&barrier!=null&&!barrier.isClosed();}
        boolean permitsAbort(PhpOwnershipArrays.PhysicalSlot actual){return !rawStored&&afterSlotIdentities.containsKey(actual);}
    }
    @SuppressWarnings("unchecked")private static <T extends Throwable>void raise(Throwable failure)throws T{throw(T)failure;}
}
