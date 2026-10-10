package php.runtime;

import php.runtime.memory.ReferenceMemory;
import java.util.*;

/** The actual certified Closure static table. Its physical carriers are the PHP values, not a parallel map. */
public final class PhpClosureStaticStorage extends AbstractMap<String,ReferenceMemory> {
    private final PhpOwnershipClosures.Certificate closure;
    private final PhpOwnershipArrays arrays;
    private final PhpOwnership.Node node;
    private Map<String,PhpOwnershipArrays.PhysicalSlot> slots=new LinkedHashMap<>();
    private Map<String,ReferenceMemory> carriers=new LinkedHashMap<>();
    PhpClosureStaticStorage(PhpOwnershipClosures.Certificate closure,PhpOwnershipArrays arrays) {
        this.closure=closure;this.arrays=arrays;node=closure.domain().storage(this);
    }
    PhpOwnership.Node node() {return node;}
    public PhpOwnershipArrays.PhysicalSlot slot(String name) {
        closure.validate();var slot=slots.get(name);if (slot == null) throw new IllegalArgumentException("Static slot is absent");slot.ownerPublisher().current();return slot;
    }
    public ReferenceMemory getOrCreate(String name,PhpOwnershipValues.Binding value) {
        closure.validate();Objects.requireNonNull(name);
        if (name.isEmpty() || name.indexOf('\0') >= 0) throw new IllegalArgumentException("Invalid static slot name");
        var present=carriers.get(name);if (present != null) return present;
        if (value.domain() != closure.domain() || value.role() != PhpOwnershipValues.Role.VALUE)
            throw new IllegalArgumentException("Static initialization requires its same-domain VALUE");
        closure.semanticValue(value.rawValue());
        ReferenceMemory carrier=new ReferenceMemory(value.rawValue());
        // Allocate index capacity before attaching the physical owner edge. No old holder retires here.
        Map<String,ReferenceMemory> nextCarriers=new LinkedHashMap<>(carriers);nextCarriers.put(name,carrier);
        Map<String,PhpOwnershipArrays.PhysicalSlot> nextSlots=new LinkedHashMap<>(slots);nextSlots.put(name,null);
        try {
            closure.domain().graph().deferDestruction(() -> {
                var physical=arrays.attachPhysicalSlot(node,carrier,value);
                nextSlots.put(name,physical);slots=nextSlots;carriers=nextCarriers;
            });return carrier;
        } catch (RuntimeException | Error error) {throw error;}
        catch (Throwable failure) {PhpClosureStaticStorage.<RuntimeException>raise(failure);return null;}
    }
    @SuppressWarnings("unchecked") private static <T extends Throwable> void raise(Throwable failure) throws T {throw (T)failure;}
    Map<String,PhpOwnershipValues.Binding> copyableValues() {
        closure.validate();Map<String,PhpOwnershipValues.Binding> values=new LinkedHashMap<>();
        for (var entry:slots.entrySet()) {
            var value=entry.getValue().ownerPublisher().current().binding();
            if (value.role() != PhpOwnershipValues.Role.VALUE)
                throw new IllegalStateException("Certified static reference clone needs complete semantic owner accounting");
            values.put(entry.getKey(),value.view(value.rawValue().toImmutable()));
        }
        return values;
    }
    @Override public ReferenceMemory get(Object name) {closure.validate();return carriers.get(name);}
    @Override public Set<Entry<String,ReferenceMemory>> entrySet() {
        closure.validate();Set<Entry<String,ReferenceMemory>> result=new LinkedHashSet<>();
        for (var entry:carriers.entrySet()) result.add(new SimpleImmutableEntry<>(entry));
        return Collections.unmodifiableSet(result);
    }
    @Override public ReferenceMemory put(String key,ReferenceMemory value) {throw new IllegalStateException("Certified statics require their physical publisher");}
    @Override public ReferenceMemory remove(Object key) {throw new IllegalStateException("Certified static removal requires its address protocol");}
    @Override public void clear() {throw new IllegalStateException("Certified statics require their physical publisher");}
}
