package php.runtime.memory;

import php.runtime.Memory;
import php.runtime.env.Environment;
import php.runtime.env.TraceInfo;
import php.runtime.lang.ForeachIterator;
import php.runtime.lang.IObject;

import java.nio.charset.Charset;

public class ReferenceMemory extends Memory {
    private static final Object SHARED_REFERENCE = new Object();
    // Preserve existing subclass conversion contracts; values never retain a Class, Memory or Environment.
    private static final ClassValue<Boolean> INHERITS_IMMUTABLE_CONVERSION = new ClassValue<Boolean>() {
        @Override protected Boolean computeValue(Class<?> type) {
            try { return type.getMethod("toImmutable").getDeclaringClass() == ReferenceMemory.class; }
            catch (NoSuchMethodException impossible) { throw new IllegalStateException("Missing public immutable conversion", impossible); }
        }
    };
    private Memory _value;
    private transient Object ownershipAuthority;
    /** Sealed native-map structure persists independently of the actual payload publisher. */
    private static final class NativeGlobalAuthority {
        final php.runtime.memory.support.ArrayMemoryMap.NativeGlobalSlot slot;
        Object payload;
        NativeGlobalAuthority(php.runtime.memory.support.ArrayMemoryMap.NativeGlobalSlot slot,Object payload) {this.slot=slot;this.payload=payload;}
    }
    private Object authority() {
        Object result=ownershipAuthority instanceof NativeGlobalAuthority ? ((NativeGlobalAuthority)ownershipAuthority).payload : ownershipAuthority;
        while(result instanceof GlobalsWriteObservation)result=((GlobalsWriteObservation)result).previous;
        return result;
    }
    private void authority(Object next) {
        Object current=ownershipAuthority instanceof NativeGlobalAuthority ? ((NativeGlobalAuthority)ownershipAuthority).payload : ownershipAuthority;
        if(current instanceof GlobalsWriteObservation) {
            GlobalsWriteObservation oldest=(GlobalsWriteObservation)current;
            while(oldest.previous instanceof GlobalsWriteObservation)oldest=(GlobalsWriteObservation)oldest.previous;
            oldest.previous=next;
        } else if (ownershipAuthority instanceof NativeGlobalAuthority) ((NativeGlobalAuthority)ownershipAuthority).payload=next;
        else ownershipAuthority=next;
    }
    /** Temporary embedding observation adds no field or owner to ordinary reference cells. */
    public final GlobalsWriteObservation observeGlobalsWrites(Environment environment,Object key,ReferenceMemory source) {
        if(environment.getRuntimeGlobalsTable().getByScalar(key)!=source
                || this!=source && this!=source.getReference())
            throw new IllegalArgumentException("Globals observation needs its actual Environment slot or canonical payload");
        Object previous=ownershipAuthority instanceof NativeGlobalAuthority ? ((NativeGlobalAuthority)ownershipAuthority).payload : ownershipAuthority;
        GlobalsWriteObservation observation=new GlobalsWriteObservation(this,previous);
        if(ownershipAuthority instanceof NativeGlobalAuthority)((NativeGlobalAuthority)ownershipAuthority).payload=observation;
        else ownershipAuthority=observation;
        return observation;
    }
    public static final class GlobalsWriteObservation implements AutoCloseable {
        private final ReferenceMemory carrier;
        private Object previous;
        private long writes;
        private boolean closed;
        private GlobalsWriteObservation(ReferenceMemory carrier,Object previous){this.carrier=carrier;this.previous=previous;}
        public boolean wasWritten(){if(closed)throw new IllegalStateException("Closed globals observation");return writes!=0;}
        @Override public void close() {
            if(closed)return;
            Object active=carrier.ownershipAuthority instanceof NativeGlobalAuthority
                    ? ((NativeGlobalAuthority)carrier.ownershipAuthority).payload : carrier.ownershipAuthority;
            if(active!=this)throw new IllegalStateException("Globals observations must close in reverse scope order");
            if(carrier.ownershipAuthority instanceof NativeGlobalAuthority)((NativeGlobalAuthority)carrier.ownershipAuthority).payload=previous;
            else carrier.ownershipAuthority=previous;
            closed=true;
        }
    }
    /** All attachments are allocated and validated before structural sealing publishes any tag. */
    public final NativeGlobalAttachment prepareNativeGlobalSlot(php.runtime.memory.support.ArrayMemoryMap.NativeGlobalSlot slot) {
        if (slot == null || ownershipAuthority instanceof NativeGlobalAuthority)
            throw new IllegalArgumentException("Native global carrier already has a structural slot or lacks its exact slot");
        slot.validateCarrierAttachment(this);
        return new NativeGlobalAttachment(this,slot,ownershipAuthority);
    }
    public static final class NativeGlobalAttachment {
        private final ReferenceMemory carrier;
        private final Object expected;
        private final NativeGlobalAuthority prepared;
        private boolean stored;
        private NativeGlobalAttachment(ReferenceMemory carrier,php.runtime.memory.support.ArrayMemoryMap.NativeGlobalSlot slot,Object expected) {
            this.carrier=carrier;this.expected=expected;this.prepared=new NativeGlobalAuthority(slot,expected);
        }
        public void validate() {
            if (stored || carrier.ownershipAuthority != expected)
                throw new IllegalArgumentException("Prepared native global carrier attachment changed");
            prepared.slot.validateCarrierAttachment(carrier);
        }
        public void store() {
            validate();carrier.ownershipAuthority=prepared;stored=true;
        }
    }

    private php.runtime.memory.helper.PhpPropertyGuard.Source propertySource;
    private java.util.List<php.runtime.memory.helper.PhpPropertyGuard.Source> propertySources;

    public void attachPropertySource(Environment env, php.runtime.reflection.PropertyEntity property) {
        if (property.getDeclaredType() == null || propertySource != null) return;
        attachPropertySource(new php.runtime.memory.helper.PhpPropertyGuard.Source(env,property));
    }
    public void attachPropertySource(Environment env, php.runtime.reflection.PropertyEntity property,IObject owner) {
        if (property.getDeclaredType() == null || propertySource != null) return;
        attachPropertySource(new php.runtime.memory.helper.PhpPropertyGuard.Source(env,property,
                property.isStatic()?null:php.runtime.memory.helper.PhpPropertyGuard.instanceOwner(owner)));
    }
    /** Reuse one token across the typed properties of an actual initialization/clone, never untyped slots. */
    public php.runtime.memory.helper.PhpPropertyGuard.OwnerToken attachInstancePropertySource(Environment env,
            php.runtime.reflection.PropertyEntity property,IObject owner,php.runtime.memory.helper.PhpPropertyGuard.OwnerToken lifetime) {
        if (property.getDeclaredType() == null) return lifetime;
        if (lifetime == null) lifetime=php.runtime.memory.helper.PhpPropertyGuard.instanceOwner(owner);
        else lifetime.validateOwner(owner);
        if (propertySource == null) attachPropertySource(new php.runtime.memory.helper.PhpPropertyGuard.Source(env,property,lifetime));
        return lifetime;
    }
    private void attachPropertySource(php.runtime.memory.helper.PhpPropertyGuard.Source source) {
        propertySource=source;
        ReferenceMemory cell=getReference();
        if (cell.propertySources == null) cell.propertySources=new java.util.ArrayList<>();
        cell.propertySources.add(source);source.cell=cell;
    }
    /** Ordinary guard readers prune by copy; prevalidated publications keep their captured list identities. */
    private void purgeDeadPropertySources() {
        if (propertySources != null) propertySources=php.runtime.memory.helper.PhpPropertyGuard.liveSources(propertySources);
        if (propertySource != null && propertySource.isExpired()) propertySource=null;
    }

    /** Validates a magic getter reference without attaching the missing property's source. */
    public Memory checkPropertyReference(Environment env, php.runtime.reflection.PropertyEntity property) {
        var cell=getReference();
        return php.runtime.memory.helper.PhpPropertyGuard.bind(
                new php.runtime.memory.helper.PhpPropertyGuard.Source(env,property), cell, cell.propertySources);
    }

    public boolean hasPropertySources() {
        var cell=getReference();cell.purgeDeadPropertySources();
        return cell.propertySources != null && !cell.propertySources.isEmpty();
    }

    /** Modern operators capture a zval, without making it a PHP reference. */
    public Memory incrementTarget(boolean decrement, boolean post, Environment env, TraceInfo trace) {
        if(propertySource!=null && propertySource.property.isPhpReadonly()) php.runtime.PhpReadonlyProperty.checkWrite(propertySource,getReference().getRawValue());
        return php.runtime.PhpArithmetic.incrementReference(this, decrement, post, env, trace);
    }

    /** Capture an overloaded array receiver once before walking another offset. */
    public Memory incrementArrayCarrier(Environment env, TraceInfo trace) { prepareRuntimeArrayWrite();return this; }

    public boolean isSharedIncrementCell() {
        ReferenceMemory cell=getReference();
        return cell.getRuntimeCell() == null ? cell.hasSharedReference() : cell.getRuntimeCell().node().incoming() > 1;
    }

    public void checkIncrementOverflow(Memory before, boolean decrement, Environment env, TraceInfo trace) {
        if (before.type != Type.INT || before.toLong() != (decrement ? Long.MIN_VALUE : Long.MAX_VALUE)) return;
        ReferenceMemory cell = getReference();cell.purgeDeadPropertySources();
        if (cell.propertySources == null) return;
        Memory candidate = DoubleMemory.valueOf((double) before.toLong() + (decrement ? -1 : 1));
        for (var source : cell.propertySources) {
            if (!source.type().check(env, candidate, false, source.property.getClazz().getName())) {
                env.exception(trace, php.runtime.lang.exception.BaseTypeError.class,
                        "Cannot %s %sproperty %s of type %s past its %s value",
                        decrement ? "decrement" : "increment", cell.isSharedIncrementCell() ? "a reference held by " : "",
                        source.label(), source.type().getErrorSignature(), decrement ? "minimal" : "maximal");
            }
        }
    }

    public IncrementCell captureIncrementCell() { return new IncrementCell(this); }
    public final IncrementCell captureIncrementCell(php.runtime.PhpArithmetic.RuntimeIncrementTarget proof) {
        proof.validateTarget(this);return new IncrementCell(this,proof);
    }

    public static final class IncrementCell {
        private final ReferenceMemory origin;
        private final ReferenceMemory cell;
        private final php.runtime.memory.helper.PhpPropertyGuard.Source direct;
        private final boolean shared;
        private final boolean initiallyGuarded;
        private php.runtime.PhpArithmetic.RuntimeIncrementTarget runtimeProof;
        private php.runtime.PhpRuntimeOwnership.Holder runtimeHolder;
        private php.runtime.PhpRuntimeOwnership.IncrementLease runtimeLease;
        private Memory runtimeApproved;
        private boolean runtimeRestore,runtimeStored;
        private IncrementCell(ReferenceMemory origin,php.runtime.PhpArithmetic.RuntimeIncrementTarget proof) {
            this.cell=origin.getReference();cell.purgeDeadPropertySources();this.shared=origin.isSharedIncrementCell();
            this.initiallyGuarded=cell.propertySources!=null&&!cell.propertySources.isEmpty();
            this.origin=shared?cell:origin;this.direct=shared?null:origin.propertySource;
            if(direct!=null)throw new IllegalArgumentException("ARRAY increment proof cannot restore a property declaration");
            runtimeProof=proof;runtimeHolder=this.origin.getRuntimeHolder();
            if(runtimeHolder==null)throw new IllegalArgumentException("Captured scalar lacks its actual runtime publisher");
            runtimeLease=runtimeHolder.captureIncrement(this);
        }
        public ReferenceMemory runtimeCarrier() {
            if(runtimeProof==null)throw new IllegalArgumentException("Passive increment has no runtime operator proof");return origin;
        }
        public php.runtime.PhpRuntimeOwnership.Frame runtimeFrame() {
            if(runtimeProof==null)throw new IllegalArgumentException("Passive increment has no runtime operator frame");return runtimeProof.frame();
        }
        public Memory runtimeScalar() {
            if(runtimeProof==null)throw new IllegalArgumentException("Passive increment has no captured runtime scalar");return runtimeProof.scalar();
        }
        public void validateRuntimeCarrier(php.runtime.PhpRuntimeOwnership.Holder actual,ReferenceMemory carrier) {
            if(runtimeProof==null||actual!=runtimeHolder||carrier!=origin)
                throw new IllegalArgumentException("Increment lease belongs to another captured publisher");
        }
        public void validateRuntimeValue(Memory candidate,boolean restore) {
            if(runtimeProof==null||candidate!=runtimeApproved||restore!=runtimeRestore)
                throw new IllegalArgumentException("Increment candidate was not approved by the captured operator");
        }
        private Memory publishRuntime(Memory requested,boolean restore) {
            runtimeProof.validateValue(requested,restore);
            Memory checked=requested;
            if(!restore&&shared&&initiallyGuarded&&cell.propertySources!=null)
                checked=php.runtime.memory.helper.PhpPropertyGuard.write(cell.propertySources,requested,null);
            runtimeApproved=checked;runtimeRestore=restore;
            try(var publication=runtimeLease.prepare(this,checked,restore)) {
                if(publication!=null){origin.publishRuntimeRaw(publication);runtimeStored=true;publication.commit();}
                return checked;
            }catch(Throwable failure){return ReferenceMemory.<Memory,RuntimeException>incrementFailure(failure);}
            finally{runtimeApproved=null;}
        }
        public boolean hasRuntimeStore(){return runtimeStored;}
        public void closeRuntime()throws Exception {if(runtimeLease!=null)runtimeLease.close();}
        private IncrementCell(ReferenceMemory origin) {
            this.cell = origin.getReference();cell.purgeDeadPropertySources(); this.shared = origin.isSharedIncrementCell();
            this.initiallyGuarded = cell.propertySources != null && !cell.propertySources.isEmpty();
            // ArrayValueMemory and other storage wrappers are not semantic PHP aliases.
            this.origin = !shared && origin.propertySource == null ? cell : origin;
            this.direct = shared ? null : this.origin.propertySource;
        }
        public Memory commit(Memory value) {
            if(runtimeProof!=null)return publishRuntime(value,false);
            cell.rejectUnmanagedOwnershipMutation();origin.rejectUnmanagedOwnershipMutation();
            if (shared) {
                Memory checked = !initiallyGuarded || cell.propertySources == null ? value
                        : php.runtime.memory.helper.PhpPropertyGuard.write(cell.propertySources, value, null);
                cell.storeRaw(checked);
                return checked;
            }
            if (direct != null)
                value = php.runtime.memory.helper.PhpPropertyGuard.write(java.util.List.of(direct), value, direct);
            return restore(value);
        }
        /** No-effect operators and failed typed writes restore the original zval without a second type check. */
        public Memory restore(Memory value) {
            if(runtimeProof!=null)return publishRuntime(value,true);
            cell.rejectUnmanagedOwnershipMutation();origin.rejectUnmanagedOwnershipMutation();
            if (shared) { cell.storeRaw(value); return value; }
            if (direct != null) {
                // A direct property retains its declaration when a warning handler rebinds/unsets it.
                if (direct.cell != origin) {
                    if (direct.cell != null && direct.cell.propertySources != null) {
                        direct.cell.propertySources.remove(direct);
                        if (direct.cell.propertySources.isEmpty()) direct.cell.propertySources = null;
                    }
                    if (origin.propertySources == null) origin.propertySources = new java.util.ArrayList<>();
                    if (!origin.propertySources.contains(direct)) origin.propertySources.add(direct);
                    direct.cell = origin; origin.propertySource = direct;
                }
            }
            origin.storeRaw(value);
            return value;
        }
    }

    @SuppressWarnings("unchecked") private static <T,E extends Throwable>T incrementFailure(Throwable failure)throws E{throw (E)failure;}

    public void detachPropertySource() {
        if (propertySource == null) return;
        var cell=propertySource.cell;
        if (cell.propertySources != null) {
            cell.propertySources.remove(propertySource);
            if (cell.propertySources.isEmpty()) cell.propertySources=null;
        }
        propertySource.cell=null; propertySource=null;
    }

    /** Unserialization replaces the property's own zval; old aliases retain their separate CELL. */
    /** Only the actual SPL own-table producer may replace this address without assigning through its old CELL. */
    final void publishSplArrayObjectOwnProperty(PhpObjectProperties table,
            php.runtime.lang.spl.iterator.ArrayObject owner,php.runtime.reflection.PropertyEntity property,Memory value) {
        rejectUnmanagedOwnershipMutation();
        if(owner==null || owner.peekProperties()!=table || table.declaration(property.getSpecificName())!=property
                || table.getByScalar(property.getSpecificName())!=this || isCanonicalReferenceCell() || getRuntimeHolder()!=null)
            throw new IllegalArgumentException("Raw SPL publication needs its unissued physical property address");
        var direct=propertySource;ReferenceMemory oldCell=getReference();
        if(direct!=null && (direct.property!=property || direct.cell!=oldCell
                || direct.ownerToken()!=null && !direct.ownerToken().matches(owner)))
            throw new IllegalArgumentException("Raw SPL publication has a different actual property Source");
        if(oldCell!=this && !oldCell.isCanonicalReferenceCell())
            throw new IllegalArgumentException("Raw SPL publication has a noncanonical old reference");
        if(propertySources!=null)for(var source:propertySources)
            if(source.cell==this && source!=direct)
                throw new IllegalArgumentException("Raw SPL publication cannot retire another property's address constraint");
        // An escaped old CELL keeps its native typed constraint. The new raw zval acquires no Source.
        if(direct!=null && oldCell==this)direct.cell=null;
        var fresh=property.getDeclaredType()==null?null:direct==null
                ?new php.runtime.memory.helper.PhpPropertyGuard.Source(owner.getEnvironment(),property,
                        php.runtime.memory.helper.PhpPropertyGuard.instanceOwner(owner)):direct.freshDeclaration();
        propertySource=null;propertySources=null;
        storeRaw(value==null?NULL:value);
        if(fresh!=null) {
            // Keep the property's declaration on its address, without constraining a raw incoming alias.
            propertySource=fresh;fresh.cell=getReference();
            if(fresh.cell==this)propertySources=new java.util.ArrayList<>(java.util.List.of(fresh));
        }
    }

    public Memory restorePropertyValue(Memory value) {
        rejectUnmanagedOwnershipMutation();
        if (isCanonicalReferenceCell()) throw new IllegalArgumentException("Restoration needs the property's address carrier");
        var source=propertySource;
        Memory checked=source == null ? value : php.runtime.memory.helper.PhpPropertyGuard.write(java.util.List.of(source),value,source);
        detachPropertySource();
        storeRaw(checked == null ? NULL : checked);
        if (source != null) attachPropertySource(source.freshDeclaration());
        return _value;
    }
    /** Ordinary parser path: clear the real property before resolving/constructing its child. */
    public final PropertyRestoration beginPropertyRestoration(Memory placeholder) {
        rejectUnmanagedOwnershipMutation();
        if (isCanonicalReferenceCell()) throw new IllegalArgumentException("Restoration needs the property's address carrier");
        return new PropertyRestoration(this,placeholder);
    }
    public static final class PropertyRestoration implements AutoCloseable {
        private final ReferenceMemory origin;
        private final OwnershipDeclaration declaration;
        private boolean finished;
        private PropertyRestoration(ReferenceMemory origin,Memory placeholder) {
            this.origin=origin;var source=origin.propertySource;
            declaration=source == null ? null : new OwnershipDeclaration(origin,source.environment,source.property,source.ownerToken());
            origin.detachPropertySource();origin.storeRaw(placeholder == null ? NULL : placeholder);
        }
        private void open() {
            origin.rejectUnmanagedOwnershipMutation();
            if (finished || origin.propertySource != null) throw new IllegalStateException("Property restoration was consumed or replaced");
        }
        public void store(Memory child) {open();origin.storeRaw(child == null ? NULL : child);}
        public void complete() throws Throwable {
            open();
            try {
                if (declaration != null) {
                    var change=new OwnershipRestorationCompletion(origin,declaration,false);
                    change.validate();change.store();
                }
                finished=true;
            } catch (Throwable failure) {origin.storeRaw(UNDEFINED);finished=true;throw failure;}
        }
        public void abort() {
            open();
            if (declaration != null && origin._value instanceof ReferenceMemory && origin.getReference().isCanonicalReferenceCell()) {
                var change=new OwnershipRestorationCompletion(origin,declaration,true);change.validate();change.store();
            }
            finished=true;
        }
        @Override public void close() {if (!finished) {open();finished=true;}}
    }

    /** Batched instance Source publication for one actual prepared lazy TABLE layout. */
    public static OwnershipLazySources prepareOwnershipLazySources(php.runtime.PhpLazyPropertyTransaction.Prepared placement) {
        return new OwnershipLazySources(placement);
    }
    public static final class OwnershipLazySources {
        private record Old(ReferenceMemory carrier,Memory raw,php.runtime.memory.helper.PhpPropertyGuard.Source source,ReferenceMemory cell) {}
        private record Fresh(ReferenceMemory carrier,ReferenceMemory cell,php.runtime.memory.helper.PhpPropertyGuard.Source source) {}
        private static final class CellSources {
            final ReferenceMemory cell;
            final java.util.List<php.runtime.memory.helper.PhpPropertyGuard.Source> before,after;
            final php.runtime.memory.helper.PhpPropertyGuard.Source[] contents;
            final java.util.IdentityHashMap<php.runtime.memory.helper.PhpPropertyGuard.Source,Boolean> removals=new java.util.IdentityHashMap<>();
            CellSources(ReferenceMemory cell) {
                this.cell=cell;before=cell.propertySources;
                contents=before==null?null:before.toArray(new php.runtime.memory.helper.PhpPropertyGuard.Source[0]);
                after=before==null?new java.util.ArrayList<>():new java.util.ArrayList<>(before);
            }
            void finishRemovals() {
                after.clear();int removed=0;
                if(contents!=null)for(var source:contents) {
                    if(removals.containsKey(source))removed++;else after.add(source);
                }
                if(removed!=removals.size())throw new IllegalArgumentException("Physical Source is absent or duplicated in its actual CELL");
            }
            void validate() {
                if(cell.propertySources!=before)throw new IllegalArgumentException("Lazy source CELL membership changed");
                if(before!=null) {
                    if(before.size()!=contents.length)throw new IllegalArgumentException("Lazy source CELL membership changed");
                    for(int i=0;i<contents.length;i++)if(before.get(i)!=contents[i])throw new IllegalArgumentException("Lazy source CELL membership changed");
                }
            }
        }
        private final php.runtime.PhpLazyPropertyTransaction.Prepared placement;
        private final java.util.List<Old> old=new java.util.ArrayList<>();
        private final java.util.List<Fresh> fresh=new java.util.ArrayList<>();
        private final java.util.IdentityHashMap<ReferenceMemory,CellSources> cells=new java.util.IdentityHashMap<>();
        private boolean stored;
        private OwnershipLazySources(php.runtime.PhpLazyPropertyTransaction.Prepared placement) {
            this.placement=java.util.Objects.requireNonNull(placement);
            var owner=placement.actualObject();
            for(var carrier:placement.oldCarriers()) {
                var source=carrier.propertySource;var cell=source==null?null:source.cell;
                if(source!=null&&(source.ownerToken()==null||!source.ownerToken().matches(owner)||cell==null))
                    throw new IllegalArgumentException("Lazy replacement cannot detach another instance's Source");
                old.add(new Old(carrier,carrier._value,source,cell));
                if(source!=null) {
                    var group=cells.computeIfAbsent(cell,CellSources::new);
                    if(group.removals.put(source,Boolean.TRUE)!=null)throw new IllegalArgumentException("Physical Source attached to two property carriers");
                }
            }
            for(var group:cells.values())group.finishRemovals();
            var lifetime=php.runtime.memory.helper.PhpPropertyGuard.instanceOwner(owner);
            for(var carrier:placement.newCarriers()) {
                var row=placement.sourceRow(carrier);var property=row.declaration();
                if(property==null||property.getDeclaredType()==null||row.explicitlyUnset())continue;
                var cell=row.binding().role()==php.runtime.PhpOwnershipValues.Role.CELL?(ReferenceMemory)row.binding().rawValue():carrier;
                if(carrier.propertySource!=null)throw new IllegalArgumentException("Lazy fresh carrier already has a Source");
                var source=new php.runtime.memory.helper.PhpPropertyGuard.Source(placement.environment(),property,lifetime);
                fresh.add(new Fresh(carrier,cell,source));cells.computeIfAbsent(cell,CellSources::new).after.add(source);
            }
            validate();
        }
        public void validate() {
            if(stored)throw new IllegalStateException("Lazy typed Sources already published");
            for(var record:old)if(record.carrier._value!=record.raw||record.carrier.propertySource!=record.source||record.source!=null&&record.source.cell!=record.cell)
                throw new IllegalArgumentException("Lazy property Source changed during preparation");
            for(var group:cells.values())group.validate();
            for(var record:fresh)if(record.carrier.propertySource!=null||record.source.cell!=null)
                throw new IllegalArgumentException("Fresh lazy Source was published early");
        }
        public void store(php.runtime.PhpLazyPropertyTransaction.Prepared actual) {
            if(stored||actual!=placement||!placement.permitsSources(this))throw new IllegalArgumentException("Lazy Source store lacks its actual pending layout");
            // Everything is preallocated. Shared CELL changes are applied once, never one stale list per property.
            for(var group:cells.values())group.cell.propertySources=group.after.isEmpty()?null:group.after;
            for(var record:old)if(record.source!=null){record.carrier.propertySource=null;record.source.cell=null;}
            for(var record:fresh){record.carrier.propertySource=record.source;record.source.cell=record.cell;}
            stored=true;
        }
    }

    /** Removal detaches exactly the declaring property's Source, never other reference constraints. */
    public final OwnershipSourceRemoval prepareOwnershipSourceRemoval() {return new OwnershipSourceRemoval(this);}
    public static final class OwnershipSourceRemoval {
        private final ReferenceMemory origin,cell;
        private final php.runtime.memory.helper.PhpPropertyGuard.Source source;
        private final java.util.List<php.runtime.memory.helper.PhpPropertyGuard.Source> before,after;
        private final php.runtime.memory.helper.PhpPropertyGuard.Source[] contents;
        private OwnershipSourceRemoval(ReferenceMemory origin) {
            this.origin=origin;source=origin.propertySource;cell=source == null ? null : source.cell;
            before=cell == null ? null : cell.propertySources;
            contents=before == null ? null : before.toArray(new php.runtime.memory.helper.PhpPropertyGuard.Source[0]);
            after=before == null ? null : new java.util.ArrayList<>(before);
            if (after != null) after.remove(source);
        }
        public void validate() {
            if (origin.propertySource != source || source != null && (source.cell != cell || cell.propertySources != before))
                throw new IllegalArgumentException("Property Source changed during removal preparation");
            if (before != null) {
                if (before.size() != contents.length) throw new IllegalArgumentException("Property Source membership changed during removal preparation");
                for (int i=0;i<contents.length;i++) if (before.get(i) != contents[i]) throw new IllegalArgumentException("Property Source membership changed during removal preparation");
            }
        }
        public OwnershipDeclaration declaration() {return source == null ? null : new OwnershipDeclaration(origin,source.environment,source.property,source.ownerToken());}
        private void store() {
            if (source != null) {cell.propertySources=after == null || after.isEmpty() ? null : after;source.cell=null;origin.propertySource=null;}
        }
    }
    public final OwnershipRestorationClear prepareOwnershipRestorationClear(OwnershipDeclaration prior) {
        return new OwnershipRestorationClear(this,prior);
    }
    public static final class OwnershipRestorationClear {
        private final ReferenceMemory origin;
        private final Memory before;
        private final OwnershipSourceRemoval removal;
        private final OwnershipDeclaration declaration;
        private OwnershipRestorationClear(ReferenceMemory origin,OwnershipDeclaration prior) {
            this.origin=origin;before=origin._value;removal=new OwnershipSourceRemoval(origin);
            if (prior != null) prior.freshSource();
            var direct=origin.propertySource;
            declaration=direct != null ? new OwnershipDeclaration(origin,direct.environment,direct.property,direct.ownerToken(),Memory.NULL)
                    : prior == null ? null : new OwnershipDeclaration(origin,prior.environment,prior.property,prior.owner,Memory.NULL);
            validate();
        }
        public OwnershipDeclaration declaration() {return declaration;}
        public void validate() {removal.validate();if (origin._value != before) throw new IllegalArgumentException("Restoration cleanup address changed");}
        private void store() {removal.store();origin.storeRaw(Memory.NULL);}
    }
    public final void publishOwnershipRestorationClear(php.runtime.PhpOwnershipArrays.RestorationClearPublication publication) {
        publication.consumeCarrier(this);publication.sourceChange().store();
    }
    /** Reader owner replacement validates only this declaration, preserving old shared aliases. */
    public final OwnershipRestoration prepareOwnershipRestoration(Memory value,OwnershipDeclaration unset) {
        return new OwnershipRestoration(this,value,unset,false);
    }
    public final OwnershipRestoration prepareOwnershipPendingRestoration(php.runtime.PhpOwnershipArrays.RestorationPublication publication,Memory value,OwnershipDeclaration unset) {
        publication.validatePendingPreparation(this,value);return new OwnershipRestoration(this,value,unset,true);
    }
    public static final class OwnershipRestoration {
        private final ReferenceMemory origin;
        private final Memory before,checked;
        private final OwnershipSourceRemoval removal;
        private final java.util.List<php.runtime.memory.helper.PhpPropertyGuard.Source> originalSources,nextSources;
        private final php.runtime.memory.helper.PhpPropertyGuard.Source[] contents;
        private final php.runtime.memory.helper.PhpPropertyGuard.Source fresh;
        private OwnershipRestoration(ReferenceMemory origin,Memory value,OwnershipDeclaration unset,boolean pending) {
            this.origin=origin;before=origin._value;removal=new OwnershipSourceRemoval(origin);originalSources=origin.propertySources;
            contents=originalSources == null ? null : originalSources.toArray(new php.runtime.memory.helper.PhpPropertyGuard.Source[0]);
            var direct=origin.propertySource;
            if (unset != null && unset.origin != origin) throw new IllegalArgumentException("Foreign declaration address");
            fresh=direct != null ? direct.freshDeclaration()
                    : unset == null ? null : unset.freshSource();
            checked=fresh == null || pending ? value : php.runtime.memory.helper.PhpPropertyGuard.write(java.util.List.of(fresh),value,fresh);
            nextSources=fresh == null || pending ? null : new java.util.ArrayList<>(java.util.List.of(fresh));validate();
        }
        public Memory checked() {return checked;}
        public OwnershipDeclaration pendingDeclaration() {return fresh == null ? null : new OwnershipDeclaration(origin,fresh.environment,fresh.property,fresh.ownerToken());}
        public void validate() {
            removal.validate();
            if (origin._value != before || origin.propertySources != originalSources)
                throw new IllegalArgumentException("Restoration address changed during validation");
            if (originalSources != null) {
                if (originalSources.size() != contents.length) throw new IllegalArgumentException("Restoration Source membership changed");
                for (int i=0;i<contents.length;i++) if (originalSources.get(i) != contents[i]) throw new IllegalArgumentException("Restoration Source membership changed");
            }
        }
        private void store() {
            removal.store();origin.propertySources=nextSources;origin.propertySource=nextSources == null ? null : fresh;
            if (origin.propertySource != null) fresh.cell=origin;
        }
    }
    public final void publishOwnershipRestoration(php.runtime.PhpOwnershipArrays.RestorationPublication publication) {
        Memory next=publication.consumeCarrier(this);
        if (publication.sourceChange() != null) publication.sourceChange().store();
        storeRaw(next);
    }
    public final void relocateOwnershipRestoration(php.runtime.PhpOwnershipArrays.RestorationPublication publication,ReferenceMemory replacement) {
        var entry=publication.consumeRelocation(this,replacement);
        if (authority() == entry) authority(null);
        replacement.authority(entry);
    }
    public final OwnershipRestorationCompletion prepareOwnershipRestorationCompletion(php.runtime.PhpOwnershipArrays.RestorationCompletionPublication publication) {
        publication.validatePreparation(this);return new OwnershipRestorationCompletion(this,publication.declaration(),publication.unchecked());
    }
    public static final class OwnershipRestorationCompletion {
        private final ReferenceMemory origin,target;
        private final Memory before,payload,checked;
        private final php.runtime.memory.helper.PhpPropertyGuard.Source fresh;
        private final java.util.List<php.runtime.memory.helper.PhpPropertyGuard.Source> sources,after;
        private final php.runtime.memory.helper.PhpPropertyGuard.Source[] contents;
        private OwnershipRestorationCompletion(ReferenceMemory origin,OwnershipDeclaration declaration,boolean unchecked) {
            this.origin=origin;target=origin.getReference();before=origin._value;payload=target._value;sources=target.propertySources;
            if (declaration == null || declaration.origin != origin || origin.propertySource != null)
                throw new IllegalArgumentException("Pending declaration no longer identifies this property");
            contents=sources == null ? null : sources.toArray(new php.runtime.memory.helper.PhpPropertyGuard.Source[0]);
            fresh=new php.runtime.memory.helper.PhpPropertyGuard.Source(declaration.environment,declaration.property,declaration.owner);
            checked=unchecked ? payload : php.runtime.memory.helper.PhpPropertyGuard.bind(fresh,target,sources);
            if (checked != payload && (payload instanceof ArrayMemory || payload instanceof ObjectMemory))
                throw new IllegalArgumentException("Completed container validation changed its allocation");
            after=sources == null ? new java.util.ArrayList<>() : new java.util.ArrayList<>(sources);after.add(fresh);validate();
        }
        public void validate() {
            if (origin._value != before || origin.propertySource != null || origin.getReference() != target
                    || target._value != payload || target.propertySources != sources)
                throw new IllegalArgumentException("Pending property/CELL changed during Source completion");
            if (sources != null) {
                if (sources.size() != contents.length) throw new IllegalArgumentException("Pending CELL Source membership changed");
                for (int i=0;i<contents.length;i++) if (sources.get(i) != contents[i]) throw new IllegalArgumentException("Pending CELL Source membership changed");
            }
        }
        private void store() {target.storeRaw(checked);target.propertySources=after;origin.propertySource=fresh;fresh.cell=target;}
    }
    public final void publishOwnershipRestorationCompletion(php.runtime.PhpOwnershipArrays.RestorationCompletionPublication publication,OwnershipRestorationCompletion change) {
        publication.consume(this,change);change.store();
    }
    /** Declaration provenance only: never retain the removed CELL or its old value/Sources. */
    public static final class OwnershipDeclaration {
        private final ReferenceMemory origin;
        private final Environment environment;
        private final php.runtime.reflection.PropertyEntity property;
        private final php.runtime.memory.helper.PhpPropertyGuard.OwnerToken owner;
        private final Memory placeholder;
        private OwnershipDeclaration(ReferenceMemory origin,Environment environment,php.runtime.reflection.PropertyEntity property,php.runtime.memory.helper.PhpPropertyGuard.OwnerToken owner) {
            this(origin,environment,property,owner,Memory.UNDEFINED);
        }
        private OwnershipDeclaration(ReferenceMemory origin,Environment environment,php.runtime.reflection.PropertyEntity property,php.runtime.memory.helper.PhpPropertyGuard.OwnerToken owner,Memory placeholder) {
            this.origin=origin;this.environment=environment;this.property=property;this.owner=owner;this.placeholder=placeholder;
        }
        private php.runtime.memory.helper.PhpPropertyGuard.Source freshSource() {
            if (origin.propertySource != null || origin._value != placeholder) throw new IllegalArgumentException("Unset declaration changed");
            return new php.runtime.memory.helper.PhpPropertyGuard.Source(environment,property,owner);
        }
        public void initializeReference() {php.runtime.memory.helper.PhpPropertyGuard.initializeReference(freshSource());}
        public OwnershipRevival prepareRevival(Memory value) {return new OwnershipRevival(origin,freshSource(),value,placeholder);}
    }
    public static final class OwnershipRevival {
        private final ReferenceMemory origin;
        private final php.runtime.memory.helper.PhpPropertyGuard.Source source;
        private final java.util.List<php.runtime.memory.helper.PhpPropertyGuard.Source> before,after;
        private final Memory checked,placeholder;
        private OwnershipRevival(ReferenceMemory origin,php.runtime.memory.helper.PhpPropertyGuard.Source source,Memory value,Memory placeholder) {
            this.origin=origin;this.source=source;this.placeholder=placeholder;before=origin.propertySources;
            checked=php.runtime.memory.helper.PhpPropertyGuard.write(java.util.List.of(source),value,source);
            after=before == null ? new java.util.ArrayList<>() : new java.util.ArrayList<>(before);after.add(source);validate();
        }
        public Memory checked() {return checked;}
        public void validate() {
            if (origin.propertySource != null || origin.propertySources != before || origin._value != placeholder)
                throw new IllegalArgumentException("Unset declaration changed during revival validation");
        }
        private void store() {origin.propertySource=source;origin.propertySources=after;source.cell=origin;}
    }
    public final void publishOwnershipUnset(php.runtime.PhpOwnershipArrays.UnsetPublication publication) {
        publication.consumeCarrier(this);publication.sourceRemoval().store();storeRaw(Memory.UNDEFINED);
    }
    public final void publishOwnershipRemovalSource(php.runtime.PhpOwnershipArrays.UnsetPublication publication) {
        publication.consumeSource(this);publication.sourceRemoval().store();
    }
    public final void relocateOwnershipEntry(php.runtime.PhpOwnershipArrays.UnsetPublication publication,ReferenceMemory replacement) {
        var entry=publication.consumeRelocation(this,replacement);
        if (authority() == entry) authority(null);
        replacement.authority(entry);
    }

    private boolean guarded() {
        if (propertySource == null && propertySources == null) return false;
        purgeDeadPropertySources();return propertySource != null || propertySources != null;
    }
    private Memory assignProperty(Memory value, php.runtime.memory.helper.PhpPropertyGuard.Source direct) {
        ReferenceMemory cell=getReference();
        cell.rejectUnmanagedOwnershipMutation();cell.purgeDeadPropertySources();
        if(direct!=null && direct.property.isPhpReadonly()) php.runtime.PhpReadonlyProperty.checkWrite(direct,cell.getRawValue());
        var sources=cell.propertySources;
        if(direct!=null && (sources==null || !sources.contains(direct))) {
            // A raw SPL property can own a declaration without imposing it on its incoming CELL.
            var declared=new java.util.ArrayList<php.runtime.memory.helper.PhpPropertyGuard.Source>();
            if(sources!=null)declared.addAll(sources);declared.add(direct);sources=declared;
        }
        Memory checked=sources == null ? value
                : php.runtime.memory.helper.PhpPropertyGuard.write(sources, value, direct);
        if(direct!=null && direct.property.isPhpReadonly()) php.runtime.PhpReadonlyProperty.checkWrite(direct,cell.getRawValue());
        cell.storeRaw(checked);
        if(direct!=null && direct.property.isPhpReadonly()) php.runtime.PhpReadonlyProperty.wrote(direct);
        return checked;
    }

    protected ReferenceMemory(Type type, Memory value) {
        super(type);
        php.runtime.PhpPrimitiveCellRuntime.beforeUntrackedConstruction(value);
        this._value = value == null ? NULL : value;
    }

    public ReferenceMemory(Memory value) {
        super(Type.REFERENCE);
        php.runtime.PhpPrimitiveCellRuntime.beforeUntrackedConstruction(value);
        this._value = value == null ? Memory.NULL : value;
    }

    public static Memory valueOf(Memory value){
        return new ReferenceMemory(value);
    }

    public ReferenceMemory() {
        super(Type.REFERENCE);
        this._value = Memory.NULL;
    }

    public ReferenceMemory duplicate(){
        return new ReferenceMemory(getValue().toImmutable());
    }

    public Memory getValue() {
        php.runtime.PhpPrimitiveCellRuntime.beforeStructuralRead(this);
        return _value;
    }

    /** Origin tags occupy the existing authority union; neither tag adds a PHP owner. */
    public final Environment.SyntheticGlobalsOrigin getSyntheticGlobalsOrigin() {
        return authority() instanceof Environment.SyntheticGlobalsOrigin ? (Environment.SyntheticGlobalsOrigin) authority() : null;
    }
    public final void attachSyntheticGlobalsOrigin(Environment.SyntheticGlobalsOrigin origin) {
        if (origin == null || authority() != null) throw new IllegalArgumentException("Constructor GLOBALS origin conflicts with an existing publisher");
        origin.validateCarrier(this);authority(origin);
    }
    public final php.runtime.PhpRuntimeOwnership.GlobalTableBorrow getGlobalTableBorrow() {
        return authority() instanceof php.runtime.PhpRuntimeOwnership.GlobalTableBorrow ? (php.runtime.PhpRuntimeOwnership.GlobalTableBorrow) authority() : null;
    }
    public final void attachGlobalTableBorrow(php.runtime.PhpRuntimeOwnership.GlobalTableBorrow borrow) {
        if (borrow == null || authority() != null) throw new IllegalArgumentException("Literal GLOBALS borrow conflicts with an existing publisher");
        borrow.validateCarrier(this);authority(borrow);
    }
    private void rejectGlobalTableBorrow() {
        var borrow=getGlobalTableBorrow();if (borrow != null) borrow.rejectMutation();
    }
    /** Nonthrowing metadata retirement follows successful validation, immediately before the raw store. */
    public final void retireSyntheticGlobalsOrigin() {
        var origin=getSyntheticGlobalsOrigin();if (origin != null) {origin.retire();authority(null);}
    }
    private Memory storeRaw(Memory value) {
        php.runtime.PhpPrimitiveCellRuntime.beforeRawStore(this,value);
        retireSyntheticGlobalsOrigin();_value=value;
        Object active=ownershipAuthority instanceof NativeGlobalAuthority ? ((NativeGlobalAuthority)ownershipAuthority).payload : ownershipAuthority;
        while(active instanceof GlobalsWriteObservation) {
            GlobalsWriteObservation observation=(GlobalsWriteObservation)active;
            observation.writes++;active=observation.previous;
        }
        return value;
    }
    protected final void prepareUnownedKeyStore() {
        if (ownershipAuthority instanceof NativeGlobalAuthority) throw new IllegalStateException("Sealed native global carrier cannot be rekeyed");
        rejectUnmanagedOwnershipMutation();retireSyntheticGlobalsOrigin();
    }

    /** Actual ordinary PHP holders are registered by their producer/address, never by wrapper construction. */
    public final php.runtime.PhpRuntimeOwnership.Holder getRuntimeHolder() {
        return authority() instanceof php.runtime.PhpRuntimeOwnership.Holder ? (php.runtime.PhpRuntimeOwnership.Holder)authority()
                : authority() instanceof php.runtime.PhpRuntimeOwnership.CellOrigin ? ((php.runtime.PhpRuntimeOwnership.CellOrigin)authority()).payload() : null;
    }
    public final php.runtime.PhpRuntimeOwnership.CellOrigin getRuntimeCell() {
        return authority() instanceof php.runtime.PhpRuntimeOwnership.CellOrigin ? (php.runtime.PhpRuntimeOwnership.CellOrigin)authority() : null;
    }
    public final void attachRuntimeHolder(php.runtime.PhpRuntimeOwnership.Holder holder) {
        if (holder == null || !holder.matches(this)) throw new IllegalArgumentException("Runtime holder does not identify this physical carrier");
        if (getSyntheticGlobalsOrigin() != null || getGlobalTableBorrow() != null) throw new IllegalArgumentException("Special GLOBALS carrier cannot become a runtime holder");
        var present=getRuntimeHolder();
        if (present != null && present != holder) throw new IllegalArgumentException("Physical carrier already has a runtime holder");
        if (getOwnershipEntry() != null || getOwnershipCell() != null) throw new IllegalArgumentException("Certified physical carrier already has its ownership publisher");
        if (getRuntimeCell() == null) authority(holder);
    }
    public final void attachRuntimeCell(php.runtime.PhpRuntimeOwnership.CellOrigin origin) {
        if (origin == null || origin.cell() != this || getClass() != ReferenceMemory.class || _value instanceof ReferenceMemory)
            throw new IllegalArgumentException("Runtime CELL origin does not identify this canonical payload");
        if (getSyntheticGlobalsOrigin() != null || getGlobalTableBorrow() != null) throw new IllegalArgumentException("Special GLOBALS carrier cannot become a runtime CELL");
        var present=getRuntimeCell();
        if (present != null && present != origin) throw new IllegalArgumentException("Canonical CELL already has a runtime origin");
        if (getRuntimeHolder() != null && getRuntimeHolder() != origin.payload()) throw new IllegalArgumentException("Canonical CELL already has another runtime payload holder");
        if (getOwnershipCell() != null || getOwnershipEntry() != null) throw new IllegalArgumentException("Certified canonical CELL already has its payload publisher");
        authority(origin);
    }
    /** A single-use publisher permit is the only ordinary route to the private raw field. */
    public final void publishRuntimeRaw(php.runtime.PhpRuntimeOwnership.Publication publication) {
        boolean borrowed=publication.claimsRuntimeBorrowCarrier();
        var attachment=publication.claimsRuntimeCarrierAttachment() ? publication.holder() : null;
        if (attachment != null && (borrowed || authority() != null))
            throw new IllegalArgumentException("Borrowed CV cannot install conflicting ownership publishers");
        if (borrowed && authority() != publication.holder())
            throw new IllegalArgumentException("Borrowed CV does not identify its retiring publisher");
        storeRaw(publication.consumeRaw(this));
        if (borrowed) authority(null);
        else if (attachment != null) authority(attachment);
    }
    public final void publishRuntimeUnset(php.runtime.PhpRuntimeOwnership.Publication publication,OwnershipSourceRemoval removal) {
        if (removal.origin != this) throw new IllegalArgumentException("Source removal belongs to another carrier");
        removal.validate();
        if (publication.next() != UNDEFINED) throw new IllegalArgumentException("Unset publication requires UNDEFINED");
        Memory next=publication.consumeRaw(this);removal.store();storeRaw(next);
    }
    public final OwnershipReferenceChange prepareRuntimeReference(ReferenceMemory target,boolean promotion) {
        if (target == this || !target.isCanonicalReferenceCell() || target.getRuntimeCell() == null)
            throw new IllegalArgumentException("Reference binding needs a distinct runtime canonical CELL");
        if (promotion && _value.isUndefined() && propertySources != null)
            for (var source:propertySources) php.runtime.memory.helper.PhpPropertyGuard.initializeReference(source);
        Memory checked=propertySource == null || propertySource.cell == target ? target._value
                : php.runtime.memory.helper.PhpPropertyGuard.bind(propertySource,target,target.propertySources);
        return new OwnershipReferenceChange(this,target,promotion,checked);
    }
    public final void publishRuntimeReference(OwnershipReferenceChange change,php.runtime.PhpRuntimeOwnership.Publication publication) {
        change.validate();
        if (change.origin != this || change.target._value != change.checked || publication.next() != change.target)
            throw new IllegalArgumentException("Runtime reference source/target no longer matches its permit");
        var attachment=publication.claimsRuntimeCarrierAttachment() ? publication.holder() : null;
        if (attachment != null && authority() != null)
            throw new IllegalArgumentException("Borrowed CV already has an ownership publisher");
        change.publishPointer(publication.consumeReference(this,change));
        if (attachment != null) authority(attachment);
    }

    /** Ownership adapters may inspect a carrier without evaluating its overridden PHP getter. */
    public final Memory getRawValue() { php.runtime.PhpPrimitiveCellRuntime.beforeRawRead(this);return _value; }
    public final Memory readPrimitiveRaw(php.runtime.PhpPrimitiveCellRuntime.ReadAccess access) { access.validate();return _value; }
    public final php.runtime.PhpPrimitiveCellRuntime.Attachment getPrimitiveCellAttachment() {
        return authority() instanceof php.runtime.PhpPrimitiveCellRuntime.Attachment ? (php.runtime.PhpPrimitiveCellRuntime.Attachment)authority() : null;
    }
    public final boolean hasPrimitiveStaticUnknown() { return php.runtime.PhpPrimitiveCellRuntime.isUnknownStatic(authority()); }
    public final void markPrimitiveStaticUnknown(Object tag) {
        if (!php.runtime.PhpPrimitiveCellRuntime.isUnknownStatic(tag)) throw new IllegalArgumentException("Foreign static origin tag");
        if (authority()==null) authority(tag);
    }
    public final void attachPrimitiveCell(php.runtime.PhpPrimitiveCellRuntime.Attachment attachment) {
        attachment.validateCarrier(this);
        if (getRuntimeHolder()!=null || getOwnershipEntry()!=null || getOwnershipCell()!=null || getSyntheticGlobalsOrigin()!=null || getGlobalTableBorrow()!=null)
            throw new IllegalArgumentException("Physical CELL conflicts with another publisher");
        authority(attachment);
    }
    public final void clearPrimitiveCell(php.runtime.PhpPrimitiveCellRuntime.Attachment expected) {
        expected.validateCarrier(this);if (authority()!=expected) throw new IllegalArgumentException("CELL slot generation changed");authority(null);
    }

    public final php.runtime.PhpOwnershipArrays.Entry getOwnershipEntry() {
        return authority() instanceof php.runtime.PhpOwnershipArrays.Entry ? (php.runtime.PhpOwnershipArrays.Entry)authority() : null;
    }
    public final void attachOwnershipEntry(php.runtime.PhpOwnershipArrays.Entry entry) {
        entry.validateCarrierAttachment(this);
        if (authority() != null && authority() != SHARED_REFERENCE && authority() != entry
                && (!(authority() instanceof php.runtime.PhpOwnershipArrays.CellCertificate)
                    || entry.cellCertificate() != authority()))
            throw new IllegalArgumentException("Physical field already belongs to another ownership publisher");
        authority(entry);
    }
    /** Actual unpromoted CV storage; SHARED_REFERENCE is a binding certificate, not a reference count. */
    public final boolean isExclusiveTypedObjectCv(IObject actual) {
        return (getClass() == ReferenceMemory.class || getClass() == php.runtime.memory.support.ArrayMapEntryMemory.class)
                && getReference() == this && !hasSharedReference() && getRuntimeHolder() == null
                && getOwnershipCell() == null && getOwnershipEntry() == null && getPrimitiveCellAttachment() == null
                && getValue() instanceof php.runtime.memory.ObjectMemory
                && ((php.runtime.memory.ObjectMemory)getValue()).value == actual;
    }
    private boolean hasSharedReference() {
        return getRuntimeCell() != null || php.runtime.PhpPrimitiveCellRuntime.isCellAttachment(getPrimitiveCellAttachment()) || authority() == SHARED_REFERENCE
                || authority() instanceof php.runtime.PhpOwnershipArrays.CellCertificate
                || authority() instanceof php.runtime.PhpOwnershipArrays.Entry
                    && ((php.runtime.PhpOwnershipArrays.Entry) authority()).cellCertificate() != null;
    }
    private void markSharedReference() {
        if (hasSharedReference()) return;
        rejectUnmanagedOwnershipMutation();retireSyntheticGlobalsOrigin();authority(SHARED_REFERENCE);
    }
    private void rejectUnmanagedOwnershipMutation() {
        rejectGlobalTableBorrow();
        var origin=getSyntheticGlobalsOrigin();
        if (origin != null) {origin.validateCarrier(this);return;}
        if (getRuntimeHolder() != null || getRuntimeCell() != null)
            throw new IllegalStateException("Runtime Memory mutation requires its actual ownership publisher");
        if (authority() != null && authority() != SHARED_REFERENCE && getPrimitiveCellAttachment()==null && !hasPrimitiveStaticUnknown())
            throw new IllegalStateException("Certified Memory mutation requires its actual ownership publisher");
    }

    /** Validation remains outside the graph publication barrier and cannot manufacture a PHP alias. */
    public final Memory validateOwnershipAssignment(Memory candidate) {
        if (_value instanceof ReferenceMemory || candidate instanceof ReferenceMemory)
            throw new IllegalArgumentException("Ownership VALUE assignment requires a terminal payload");
        return propertySources == null ? candidate
                : php.runtime.memory.helper.PhpPropertyGuard.write(propertySources, candidate, propertySource);
    }
    /** Prepare type checks and source-list allocation before the reference-owner publication barrier. */
    public final OwnershipReferenceChange prepareOwnershipReference(ReferenceMemory target, boolean promotion) {
        if (target == this || !target.isCanonicalReferenceCell() || target.getOwnershipCell() == null)
            throw new IllegalArgumentException("Reference rebinding needs a distinct certified canonical CELL");
        if (promotion && _value.isUndefined() && propertySources != null)
            for (var source : propertySources) php.runtime.memory.helper.PhpPropertyGuard.initializeReference(source);
        Memory checked = propertySource == null || propertySource.cell == target ? target._value
                : php.runtime.memory.helper.PhpPropertyGuard.bind(propertySource,target,target.propertySources);
        return new OwnershipReferenceChange(this,target,promotion,checked);
    }
    public final OwnershipReferenceChange prepareOwnershipRevivedReference(ReferenceMemory target,boolean promotion,OwnershipDeclaration unset) {
        if (target == this || !target.isCanonicalReferenceCell() || target.getOwnershipCell() == null)
            throw new IllegalArgumentException("Reference revival needs a distinct certified canonical CELL");
        var source=unset.freshSource();
        if (promotion) php.runtime.memory.helper.PhpPropertyGuard.initializeReference(source);
        Memory checked=php.runtime.memory.helper.PhpPropertyGuard.bind(source,target,target.propertySources);
        return new OwnershipReferenceChange(this,target,promotion,checked,source);
    }
    public static final class OwnershipReferenceChange {
        private final ReferenceMemory origin,target,oldSourceCell;
        private final Memory oldRaw,checked;
        private final php.runtime.memory.helper.PhpPropertyGuard.Source direct,originalDirect;
        private final java.util.List<php.runtime.memory.helper.PhpPropertyGuard.Source> originSources,targetSources,oldSources;
        private final java.util.List<php.runtime.memory.helper.PhpPropertyGuard.Source> nextTarget,nextOld;
        private final php.runtime.memory.helper.PhpPropertyGuard.Source[] originContents,targetContents,oldContents;
        private final boolean promotion;
        private boolean consumed;
        private OwnershipReferenceChange(ReferenceMemory origin,ReferenceMemory target,boolean promotion,Memory checked) {
            this(origin,target,promotion,checked,origin.propertySource);
        }
        private OwnershipReferenceChange(ReferenceMemory origin,ReferenceMemory target,boolean promotion,Memory checked,php.runtime.memory.helper.PhpPropertyGuard.Source direct) {
            this.origin=origin;this.target=target;this.promotion=promotion;this.checked=checked;oldRaw=origin._value;
            this.direct=direct;originalDirect=origin.propertySource;oldSourceCell=direct == null ? null : direct.cell;
            originSources=origin.propertySources;targetSources=target.propertySources;
            oldSources=oldSourceCell == null ? null : oldSourceCell.propertySources;
            originContents=contents(originSources);targetContents=contents(targetSources);oldContents=contents(oldSources);
            nextTarget=targetSources == null ? new java.util.ArrayList<>() : new java.util.ArrayList<>(targetSources);
            if (promotion && originSources != null) {
                for (var source : originSources) if (!nextTarget.contains(source)) nextTarget.add(source);
            } else if (direct != null && !nextTarget.contains(direct)) nextTarget.add(direct);
            nextOld=oldSources == null ? null : new java.util.ArrayList<>(oldSources);
            if (nextOld != null && direct != null) nextOld.remove(direct);
        }
        private static php.runtime.memory.helper.PhpPropertyGuard.Source[] contents(java.util.List<php.runtime.memory.helper.PhpPropertyGuard.Source> sources) {
            return sources == null ? null : sources.toArray(new php.runtime.memory.helper.PhpPropertyGuard.Source[0]);
        }
        private static boolean matches(java.util.List<php.runtime.memory.helper.PhpPropertyGuard.Source> sources,php.runtime.memory.helper.PhpPropertyGuard.Source[] snapshot) {
            if (sources == null) return snapshot == null;
            if (snapshot == null || sources.size() != snapshot.length) return false;
            for (int i=0;i<snapshot.length;i++) if (sources.get(i) != snapshot[i]) return false;
            return true;
        }
        public Memory checkedTarget() {return checked;}
        public void validate() {
            if (consumed || origin._value != oldRaw || origin.propertySource != originalDirect
                    || origin.propertySources != originSources || target.propertySources != targetSources
                    || direct != null && (direct.cell != oldSourceCell || oldSourceCell != null && oldSourceCell.propertySources != oldSources))
                throw new IllegalArgumentException("Reference source address changed during validation");
            if (!matches(originSources,originContents) || !matches(targetSources,targetContents) || !matches(oldSources,oldContents))
                throw new IllegalArgumentException("Reference Source membership changed during validation");
        }
        private void publish(ReferenceMemory actual,php.runtime.PhpOwnershipArrays.RawPublication publication) {
            validate();if (actual != origin || target._value != checked) throw new IllegalArgumentException("Reference source/target no longer matches its permit");
            publishPointer(publication.consume(origin));
        }
        private void publish(ReferenceMemory actual,php.runtime.PhpOwnershipArrays.RestorationPublication publication) {
            validate();if (actual != origin || target._value != checked) throw new IllegalArgumentException("Restoration reference source/target no longer matches its permit");
            publishPointer(publication.consumeCarrier(origin));
        }
        private void publishPointer(Memory pointer) {
            if (pointer != target) throw new IllegalArgumentException("Reference permit has a different canonical target");
            consumed=true;
            if (promotion) origin.propertySources=null;
            else if (oldSourceCell != null && oldSourceCell != target)
                oldSourceCell.propertySources=nextOld == null || nextOld.isEmpty() ? null : nextOld;
            target.propertySources=nextTarget.isEmpty() ? null : nextTarget;
            if (promotion && originSources != null) for (var source : originSources) source.cell=target;
            if (direct != null) {direct.cell=target;origin.propertySource=direct;}
            origin.storeRaw(target);
        }
    }
    public final void publishOwnershipReference(OwnershipReferenceChange change,php.runtime.PhpOwnershipArrays.RawPublication publication) {
        change.publish(this,publication);
    }
    public final void publishOwnershipRestorationReference(OwnershipReferenceChange change,php.runtime.PhpOwnershipArrays.RestorationPublication publication) {
        change.publish(this,publication);
    }

    /** Only an already validated, single-use target permit may publish the private raw field. */
    public final void publishOwnershipRaw(php.runtime.PhpOwnershipArrays.RawPublication publication) {
        storeRaw(publication.consume(this));
        if (publication.revival() != null) publication.revival().store();
    }
    public final php.runtime.PhpOwnershipArrays.CellCertificate getOwnershipCell() {
        return authority() instanceof php.runtime.PhpOwnershipArrays.CellCertificate
                ? (php.runtime.PhpOwnershipArrays.CellCertificate) authority()
                : authority() instanceof php.runtime.PhpOwnershipArrays.Entry
                    ? ((php.runtime.PhpOwnershipArrays.Entry) authority()).cellCertificate() : null;
    }
    public final void attachOwnershipCell(php.runtime.PhpOwnershipArrays.CellCertificate certificate) {
        certificate.validateAttachment(this);
        if (authority() != null && authority() != SHARED_REFERENCE) {
            if (getOwnershipCell() != certificate)
                throw new IllegalArgumentException("Reference allocation already belongs to another ownership certificate");
            return;
        }
        authority(certificate);
    }

    /** A terminal explicit-reference allocation, never an ArrayValue/ArrayAccess carrier. */
    public final boolean isCanonicalReferenceCell() {
        return getClass() == ReferenceMemory.class && hasSharedReference() && !(_value instanceof ReferenceMemory);
    }

    public Memory setValue(Memory value) {
        if (getRuntimeHolder() != null) return getRuntimeHolder().assign(value == null ? NULL : value);
        rejectUnmanagedOwnershipMutation();
        if (guarded()) return assignProperty(value, propertySource);
        return storeRaw(value == null ? NULL : value);
    }

    @Override
    public int getPointer() {
        return getValue().getPointer();
    }

    @Override
    public int getPointer(boolean absolute) {
        return absolute ? getValue().getPointer() : getPointer();
    }

    @Override
    public long toLong() {
        return getValue().toLong();
    }

    @Override
    public double toDouble() {
        return getValue().toDouble();
    }

    @Override
    public boolean toBoolean() {
        return getValue().toBoolean();
    }

    @Override
    public Memory toNumeric() {
        return getValue().toNumeric();
    }

    @Override
    public String toString() {
        return getValue().toString();
    }

    @Override
    public char toChar() {
        return getValue().toChar();
    }

    @Override
    public boolean isNull() {
        return getValue().isNull();
    }

    @Override
    public boolean isObject() {
        return getValue().isObject();
    }

    @Override
    public boolean isResource() {
        return getValue().isResource();
    }

    @Override
    public boolean isArray() {
        return getValue().isArray();
    }

    @Override
    public boolean isString() {
        return getValue().isString();
    }

    @Override
    public boolean isNumber() {
        return getValue().isNumber();
    }

    @Override
    public boolean isReference() {
        return true;
    }

    @Override
    public Memory inc() {
        return getValue().inc();
    }

    @Override
    public Memory dec() {
        return getValue().dec();
    }

    @Override
    public Memory negative() {
        return getValue().negative();
    }

    @Override
    public Memory plus(Memory memory) {
        return getValue().plus(memory);
    }

    @Override
    public Memory plus(long value) {
        return this.getValue().plus(value);
    }

    @Override
    public Memory plus(double value) {
        return this.getValue().plus(value);
    }

    @Override
    public Memory plus(boolean value) {
        return this.getValue().plus(value);
    }

    @Override
    public Memory plus(String value) {
        return this.getValue().plus(value);
    }

    @Override
    public Memory minus(Memory memory) {
        return getValue().minus(memory);
    }

    @Override
    public Memory minus(double value) {
        return this.getValue().minus(value);
    }

    @Override
    public Memory minus(boolean value) {
        return this.getValue().minus(value);
    }

    @Override
    public Memory minus(String value) {
        return this.getValue().minus(value);
    }

    @Override
    public Memory minus(long value) {
        return this.getValue().minus(value);
    }

    @Override
    public Memory mul(Memory memory) {
        return getValue().mul(memory);
    }

    @Override
    public Memory mul(long value) {
        return this.getValue().mul(value);
    }

    @Override
    public Memory mul(double value) {
        return this.getValue().mul(value);
    }

    @Override
    public Memory mul(boolean value) {
        return this.getValue().mul(value);
    }

    @Override
    public Memory mul(String value) {
        return this.getValue().mul(value);
    }

    @Override
    public Memory pow(Memory memory) {
        return getValue().pow(memory);
    }

    @Override
    public Memory pow(long value) {
        return this.getValue().pow(value);
    }

    @Override
    public Memory pow(double value) {
        return this.getValue().pow(value);
    }

    @Override
    public Memory pow(boolean value) {
        return this.getValue().pow(value);
    }

    @Override
    public Memory pow(String value) {
        return this.getValue().pow(value);
    }

    @Override
    public Memory div(Memory memory) {
        return getValue().div(memory);
    }

    @Override
    public Memory div(long value) {
        return this.getValue().div(value);
    }

    @Override
    public Memory div(double value) {
        return this.getValue().div(value);
    }

    @Override
    public Memory div(boolean value) {
        return this.getValue().div(value);
    }

    @Override
    public Memory div(String value) {
        return this.getValue().div(value);
    }

    @Override
    public Memory mod(long value) {
        return this.getValue().mod(value);
    }

    @Override
    public Memory mod(double value) {
       return this.getValue().mod(value);
    }

    @Override
    public Memory mod(boolean value) {
        return this.getValue().mod(value);
    }

    @Override
    public Memory mod(String value) {
        return this.getValue().mod(value);
    }

    @Override
    public boolean equal(Memory memory) {
        return getValue().equal(memory);
    }

    @Override
    public boolean equal(long value) {
        return this.getValue().equal(value);
    }

    @Override
    public boolean equal(double value) {
        return this.getValue().equal(value);
    }

    @Override
    public boolean equal(boolean value) {
        return this.getValue().equal(value);
    }

    @Override
    public boolean equal(String value) {
        return this.getValue().equal(value);
    }

    @Override
    public boolean notEqual(Memory memory) {
        return getValue().notEqual(memory);
    }

    @Override
    public boolean notEqual(long value) {
        return this.getValue().notEqual(value);
    }

    @Override
    public boolean notEqual(double value) {
        return this.getValue().notEqual(value);
    }

    @Override
    public boolean notEqual(boolean value) {
        return this.getValue().notEqual(value);
    }

    @Override
    public boolean notEqual(String value) {
        return this.getValue().notEqual(value);
    }

    @Override
    public String concat(Memory memory) {
        return getValue().concat(memory);
    }

    @Override
    public String concat(long value) {
        return this.getValue().concat(value);
    }

    @Override
    public String concat(double value) {
        return this.getValue().concat(value);
    }

    @Override
    public String concat(boolean value) {
        return this.getValue().concat(value);
    }

    @Override
    public String concat(String value) {
        return this.getValue().concat(value);
    }

    @Override
    public boolean smaller(Memory memory) {
        return getValue().smaller(memory);
    }

    @Override
    public boolean smaller(long value) {
        return this.getValue().smaller(value);
    }

    @Override
    public boolean smaller(double value) {
        return this.getValue().smaller(value);
    }

    @Override
    public boolean smaller(boolean value) {
        return this.getValue().smaller(value);
    }

    @Override
    public boolean smaller(String value) {
        return this.getValue().smaller(value);
    }

    @Override
    public boolean smallerEq(Memory memory) {
        return getValue().smallerEq(memory);
    }

    @Override
    public boolean smallerEq(long value) {
        return this.getValue().smallerEq(value);
    }

    @Override
    public boolean smallerEq(double value) {
        return this.getValue().smallerEq(value);
    }

    @Override
    public boolean smallerEq(boolean value) {
        return this.getValue().smallerEq(value);
    }

    @Override
    public boolean smallerEq(String value) {
        return this.getValue().smallerEq(value);
    }

    @Override
    public boolean greater(Memory memory) {
        return getValue().greater(memory);
    }

    @Override
    public boolean greater(long value) {
        return this.getValue().greater(value);
    }

    @Override
    public boolean greater(double value) {
        return this.getValue().greater(value);
    }

    @Override
    public boolean greater(boolean value) {
        return this.getValue().greater(value);
    }

    @Override
    public boolean greater(String value) {
        return this.getValue().greater(value);
    }

    @Override
    public boolean greaterEq(Memory memory) {
        return getValue().greaterEq(memory);
    }

    @Override
    public boolean greaterEq(long value) {
        return this.getValue().greaterEq(value);
    }

    @Override
    public boolean greaterEq(double value) {
        return this.getValue().greaterEq(value);
    }

    @Override
    public boolean greaterEq(boolean value) {
        return this.getValue().greaterEq(value);
    }

    @Override
    public boolean greaterEq(String value) {
        return this.getValue().greaterEq(value);
    }

    @Override
    public Memory bitAnd(Memory memory) {
        return this.getValue().bitAnd(memory);
    }

    @Override
    public Memory bitAnd(long memory) {
        return this.getValue().bitAnd(memory);
    }

    @Override
    public Memory bitAnd(double memory) {
        return this.getValue().bitAnd(memory);
    }

    @Override
    public Memory bitAnd(boolean memory) {
        return this.getValue().bitAnd(memory);
    }

    @Override
    public Memory bitAnd(String memory) {
        return this.getValue().bitAnd(memory);
    }

    @Override
    public Memory bitOr(Memory memory) {
        return this.getValue().bitOr(memory);
    }

    @Override
    public Memory bitOr(long memory) {
        return this.getValue().bitOr(memory);
    }

    @Override
    public Memory bitOr(double memory) {
        return this.getValue().bitOr(memory);
    }

    @Override
    public Memory bitOr(boolean memory) {
        return this.getValue().bitOr(memory);
    }

    @Override
    public Memory bitOr(String memory) {
        return this.getValue().bitOr(memory);
    }

    @Override
    public Memory bitXor(Memory memory) {
        return this.getValue().bitXor(memory);
    }

    @Override
    public Memory bitXor(long memory) {
        return this.getValue().bitXor(memory);
    }

    @Override
    public Memory bitXor(double memory) {
        return this.getValue().bitXor(memory);
    }

    @Override
    public Memory bitXor(boolean memory) {
        return this.getValue().bitXor(memory);
    }

    @Override
    public Memory bitXor(String memory) {
        return this.getValue().bitXor(memory);
    }

    @Override
    public Memory bitNot() {
        return this.getValue().bitNot();
    }

    @Override
    public Memory bitShr(Memory memory) {
        return this.getValue().bitShr(memory);
    }

    @Override
    public Memory bitShr(long memory) {
        return this.getValue().bitShr(memory);
    }

    @Override
    public Memory bitShr(double memory) {
        return this.getValue().bitShr(memory);
    }

    @Override
    public Memory bitShr(boolean memory) {
        return this.getValue().bitShr(memory);
    }

    @Override
    public Memory bitShr(String memory) {
        return this.getValue().bitShr(memory);
    }

    @Override
    public Memory bitShl(Memory memory) {
        return this.getValue().bitShl(memory);
    }

    @Override
    public Memory bitShl(long memory) {
        return this.getValue().bitShl(memory);
    }

    @Override
    public Memory bitShl(double memory) {
        return this.getValue().bitShl(memory);
    }

    @Override
    public Memory bitShl(boolean memory) {
        return this.getValue().bitShl(memory);
    }

    @Override
    public Memory bitShl(String memory) {
        return this.getValue().bitShl(memory);
    }

    @Override
    public Memory newKeyValue(Memory memory) {
        return this.getValue().newKeyValue(memory);
    }

    @Override
    public Memory newKeyValue(long memory) {
        return this.getValue().newKeyValue(memory);
    }

    @Override
    public Memory newKeyValue(double memory) {
        return this.getValue().newKeyValue(memory);
    }

    @Override
    public Memory newKeyValue(boolean memory) {
        return this.getValue().newKeyValue(memory);
    }

    @Override
    public Memory newKeyValue(String memory) {
        return this.getValue().newKeyValue(memory);
    }

    @Override
    public Memory toImmutable() {
        switch (getValue().type){
            case NULL:
            case REFERENCE:
            case ARRAY: return getValue().toImmutable();
            default: return getValue();
        }
    }

    @Override
    public Memory toImmutableForLegacy() {
        if (getClass() != ReferenceMemory.class && !INHERITS_IMMUTABLE_CONVERSION.get(getClass()))
            return toImmutable();
        switch (getValue().type){
            case NULL:
            case REFERENCE:
            case ARRAY: return getValue().toImmutableForLegacy();
            default: return getValue();
        }
    }

    @Override
    public <T extends Memory> T toValue(Class<T> clazz){
        switch (getValue().type){
            case REFERENCE: return getValue().toValue(clazz);
            default:
                return (T) getValue();
        }
    }

    @Override
    public Memory toValue(){
        switch (getValue().type){
            case REFERENCE: return getValue().toValue();
            default:
                return getValue();
        }
    }

    @Override
    public boolean isImmutable() {
        return false;
    }

    @Override
    public Memory assign(Memory memory) {
        if (getRuntimeHolder() != null) return getRuntimeHolder().assign(memory);
        rejectUnmanagedOwnershipMutation();
        if (guarded()) return assignProperty(memory, propertySource);
        switch (getValue().type){
            case REFERENCE:
                return getValue().assign(memory);
            case ARRAY: //value.unset(); // do not need break!!
            default:
                return setValue(memory);
        }
    }

    public Memory readProperty(php.runtime.env.Environment env, php.runtime.env.TraceInfo trace) {
        if (propertySource != null) propertySource.property.readInitialized(env, trace, this);
        return this;
    }

    public static void rejectStringOffsetReference(Memory value, Environment env, TraceInfo trace) {
        if (value.toValue() instanceof CharMemory
                && env.getModuleManager().getPhpLanguageProfile(trace) == php.runtime.common.PhpLanguageProfile.PHP_8_5)
            env.exception(trace,php.runtime.lang.exception.BaseError.class,"Cannot create references to/from string offsets");
    }

    public static void rejectStringOffsetReference(Memory value) {
        if (!(value.toValue() instanceof CharMemory)) return;
        var context=php.runtime.PhpPropertyWriteContext.current(null);
        if (context != null) rejectStringOffsetReference(value,context.environment,context.trace);
    }

    public static Memory exportReference(Memory value) {
        if (value instanceof ReferenceMemory) ((ReferenceMemory) value).rejectGlobalTableBorrow();
        rejectStringOffsetReference(value);
        return value instanceof ReferenceMemory ? ((ReferenceMemory) value).getReferenceForBinding() : value;
    }

    public ReferenceMemory getReferenceForBinding() {
        if(propertySource!=null && propertySource.property.isPhpReadonly()) php.runtime.PhpReadonlyProperty.indirect(propertySource,this);
        php.runtime.PhpPrimitiveCellRuntime.beforeExport(this);
        rejectGlobalTableBorrow();
        rejectStringOffsetReference(this);purgeDeadPropertySources();
        if (getRuntimeCell() != null) return this;
        if (getRuntimeHolder() != null) return getRuntimeHolder().reference();
        if (authority() != null && authority() != SHARED_REFERENCE && !hasSharedReference() && !(_value instanceof ReferenceMemory))
            rejectUnmanagedOwnershipMutation();
        rejectStringOffsetReference(this);
        if (getValue().isReference()) {
            ReferenceMemory shared = getReference();shared.purgeDeadPropertySources();
            if (shared.getRuntimeHolder() != null || shared.getRuntimeCell() != null) return shared.getReferenceForBinding();
            shared.markSharedReference();
            if (shared.getValue().isUndefined()) {
                if (shared.propertySources != null) for (var source : shared.propertySources)
                    php.runtime.memory.helper.PhpPropertyGuard.initializeReference(source);
                shared.assign(NULL);
            }
            return shared;
        }
        if (hasSharedReference()) return this;
        if (getValue().isUndefined() && propertySources != null) for (var source : propertySources)
            php.runtime.memory.helper.PhpPropertyGuard.initializeReference(source);
        ReferenceMemory shared = new ReferenceMemory(getValue().isUndefined() ? NULL : getValue());
        shared.markSharedReference();
        shared.propertySources=propertySources; propertySources=null;
        if (shared.propertySources != null) for (var source : shared.propertySources) source.cell=shared;
        storeRaw(shared);
        return shared;
    }

    public ReferenceMemory getReference(){
        php.runtime.PhpPrimitiveCellRuntime.beforeStructuralRead(this);
        if (getValue().type == Type.REFERENCE){
            return ((ReferenceMemory)getValue()).getReference();
        } else
            return this;
    }

    @Override
    public Memory assignRef(Memory reference){
        if(propertySource!=null && propertySource.property.isPhpReadonly()) php.runtime.PhpReadonlyProperty.indirect(propertySource,this);
        if (getRuntimeHolder() != null) {
            rejectStringOffsetReference(reference);
            if (!(reference instanceof ReferenceMemory)) return getRuntimeHolder().assign(reference);
            ReferenceMemory target=((ReferenceMemory)reference).getReferenceForBinding();
            return target == this ? target : getRuntimeHolder().rebind(target);
        }
        rejectUnmanagedOwnershipMutation();
        rejectStringOffsetReference(reference);
        if (reference.isReference()){
            reference = ((ReferenceMemory)reference).getReference();
        }

        if (reference == this) {
            return reference;
        }

        if (propertySource != null) {
            if (!(reference instanceof ReferenceMemory)) return assign(reference);
            ReferenceMemory target=((ReferenceMemory) reference).getReferenceForBinding();
            if (propertySource.cell != target) {
                Memory checked=php.runtime.memory.helper.PhpPropertyGuard.bind(propertySource, target, target.propertySources);
                if (checked != target._value) target.rejectUnmanagedOwnershipMutation();
                var old=propertySource.cell;
                if (old.propertySources != null) {
                    old.propertySources.remove(propertySource);
                    if (old.propertySources.isEmpty()) old.propertySources=null;
                }
                target.storeRaw(checked);
                if (target.propertySources == null) target.propertySources=new java.util.ArrayList<>();
                target.propertySources.add(propertySource); propertySource.cell=target;
            }
            storeRaw(target); return target;
        }
        setValue(reference);
        return reference;
    }

    @Override
    public Memory assign(long memory) {
        if (getRuntimeHolder() != null) return assign(LongMemory.valueOf(memory));
        rejectUnmanagedOwnershipMutation();
        if (guarded()) return assign(LongMemory.valueOf(memory));
        switch (getValue().type){
            case REFERENCE: return getValue().assign(memory);
            case ARRAY: getValue().unset(); // do not need break!!
            default:
                return setValue(LongMemory.valueOf(memory));
        }
    }

    @Override
    public Memory assign(String memory) {
        if (getRuntimeHolder() != null) return assign(StringMemory.valueOf(memory));
        rejectUnmanagedOwnershipMutation();
        if (guarded()) return assign(StringMemory.valueOf(memory));
        switch (getValue().type){
            case REFERENCE: return getValue().assign(memory);
            case ARRAY: getValue().unset(); // do not need break!!
            default:
                return setValue(StringMemory.valueOf(memory));
        }
    }

    @Override
    public Memory assign(boolean memory) {
        if (getRuntimeHolder() != null) return assign(memory ? TRUE : FALSE);
        rejectUnmanagedOwnershipMutation();
        if (guarded()) return assign(memory ? TRUE : FALSE);
        switch (getValue().type){
            case REFERENCE: return getValue().assign(memory);
            case ARRAY: getValue().unset(); // do not need break!!
            default:
                return setValue(memory ? TRUE : FALSE);
        }
    }

    @Override
    public Memory assign(double memory) {
        if (getRuntimeHolder() != null) return assign(DoubleMemory.valueOf(memory));
        rejectUnmanagedOwnershipMutation();
        if (guarded()) return assign(DoubleMemory.valueOf(memory));
        switch (getValue().type){
            case REFERENCE: return getValue().assign(memory);
            case ARRAY: getValue().unset(); // do not need break!!
            default:
                return setValue(new DoubleMemory(memory));
        }
    }

    private StringMemory typeString(){
        if (toValue().type != Type.STRING){
            assign(new StringMemory(getValue().toString()));
        }

        return (StringMemory)toImmutable();
    }

    @Override
    public byte[] getBinaryBytes(Charset charset) {
        return getValue().getBinaryBytes(charset);
    }

    @Override
    public int hashCode(){
        return getValue().hashCode();
    }

    @Override
    public void unset() {
        if (getRuntimeHolder() != null) {getRuntimeHolder().unset();return;}
        rejectUnmanagedOwnershipMutation();
        detachPropertySource();
        storeRaw(UNDEFINED);
    }

    @Override
    public void manualUnset(Environment env){
        if (getRuntimeHolder() != null) {unset();return;}
        rejectUnmanagedOwnershipMutation();
        if (this.getValue().isObject())
            this.getValue().manualUnset(env);

        this.unset();
    }

    /** COW is selected by the actual writer holder, never by a borrowed Java array view. */
    public final void checkPhpReadonlyArrayWrite() {
        if(propertySource!=null && propertySource.property.isPhpReadonly() && !toValue().isObject()) php.runtime.PhpReadonlyProperty.indirect(propertySource,this);
    }
    private void prepareRuntimeArrayWrite() {
        checkPhpReadonlyArrayWrite();
        rejectGlobalTableBorrow();
        var holder = getRuntimeHolder();
        if (holder == null) return;
        Memory payload = holder.raw();
        if (holder.role() == php.runtime.PhpRuntimeOwnership.Role.CELL) {
            if (payload.getClass() != ReferenceMemory.class || ((ReferenceMemory) payload).getRuntimeCell() == null)
                throw new IllegalArgumentException("Ordinary ARRAY write needs its canonical payload publisher");
            payload = ((ReferenceMemory) payload).getRuntimeCell().payload().raw();
        }
        if (payload instanceof ArrayMemory)
            php.runtime.PhpOwnershipArrays.runtimeWritable(holder);
    }

    public void needArray(){
        Type type = getRealType();
        if (type != Type.STRING && type != Type.ARRAY && type != Type.OBJECT){
            initializeArray();
        }
    }

    public Memory initializeArray() {
        if(propertySource!=null && propertySource.property.isPhpReadonly()) php.runtime.PhpReadonlyProperty.indirect(propertySource,this);
        ArrayMemory initialized=new ArrayMemory();
        ReferenceMemory cell=getReference();
        if (cell.propertySources != null) for (var source : cell.propertySources)
            if (!source.type().check(source.writer(),initialized,false,source.property.getClazz().getName()))
                source.writer().exception(source.trace(),php.runtime.lang.exception.BaseTypeError.class,
                        "Cannot auto-initialize an array inside property %s of type %s",source.label(),source.type().getErrorSignature());
        if (getRuntimeHolder() != null) return php.runtime.PhpOwnershipArrays.runtimeEmpty(getRuntimeHolder());
        return assign(initialized);
    }

    public StringBuilderMemory needStringBuilder(){
        rejectUnmanagedOwnershipMutation();
        Memory value = toValue();

        if (value instanceof StringBuilderMemory)
            return (StringBuilderMemory)value;

        StringBuilderMemory builderMemory = new StringBuilderMemory(getValue().toString());
        assign(builderMemory);
        return builderMemory;
    }

    @Override
    public Memory valueOfIndex(TraceInfo trace, Memory index) {
        return getValue().valueOfIndex(trace, index);
    }

    @Override
    public Memory valueOfIndex(TraceInfo trace, long index) {
        return getValue().valueOfIndex(trace, index);
    }

    @Override
    public Memory valueOfIndex(TraceInfo trace, double index) {
        return getValue().valueOfIndex(trace, index);
    }

    @Override
    public Memory valueOfIndex(TraceInfo trace, String index) {
        return getValue().valueOfIndex(trace, index);
    }

    @Override
    public Memory valueOfIndex(TraceInfo trace, boolean index) {
        return getValue().valueOfIndex(trace, index);
    }

    @Override
    public Memory refOfPush(TraceInfo trace) {
        needArray();prepareRuntimeArrayWrite();
        return getValue().refOfPush(trace);
    }

    @Override
    public Memory refOfIndexAsShortcut(TraceInfo trace, Memory index){
        needArray();prepareRuntimeArrayWrite();
        switch (getValue().type){
            case STRING:
                return refOfIndex(trace, index);
            default: return getValue().refOfIndexAsShortcut(trace, index);
        }
    }

    @Override
    public Memory refOfIndex(TraceInfo trace, Memory index) {
        needArray();prepareRuntimeArrayWrite();
        switch (getValue().type){
            case STRING:
                if (index.isString()){
                    int _index = -1;
                    Memory tmp = StringMemory.toLong(index.toString());
                    if (tmp != null)
                        _index = tmp.toInteger();

                    return CharMemory.valueOf(this, (StringMemory)this.getValue(), _index);
                } else
                    return CharMemory.valueOf(this, (StringMemory)this.getValue(), (int)index.toNumeric().toLong());
            default: return getValue().refOfIndex(trace, index);
        }
    }

    @Override
    public Memory refOfIndex(TraceInfo trace, long index) {
        needArray();prepareRuntimeArrayWrite();
        switch (getValue().type){
            case STRING: return CharMemory.valueOf(this, (StringMemory)this.getValue(), (int)index);
            default: return getValue().refOfIndex(trace, index);
        }
    }

    @Override
    public Memory refOfIndex(TraceInfo trace, double index) {
        needArray();prepareRuntimeArrayWrite();
        switch (getValue().type){
            case STRING: return CharMemory.valueOf(this, (StringMemory)this.getValue(), (int)index);
            default: return getValue().refOfIndex(trace, index);
        }
    }

    @Override
    public Memory refOfIndex(TraceInfo trace, String index) {
        needArray();prepareRuntimeArrayWrite();
        int _index = -1;
        Memory tmp = StringMemory.toLong(index);
        if (tmp != null)
            _index = tmp.toInteger();

        switch (getValue().type){
            case STRING: return CharMemory.valueOf(this, (StringMemory)this.getValue(), _index);
            default: return getValue().refOfIndex(trace, index);
        }
    }

    @Override
    public Memory refOfIndex(TraceInfo trace, boolean index) {
        needArray();prepareRuntimeArrayWrite();
        switch (getValue().type){
            case STRING: return CharMemory.valueOf(this, (StringMemory)this.getValue(), index ? 1 : 0);
            default: return getValue().refOfIndex(trace, index);
        }
    }

    @Override
    public void unsetOfIndex(TraceInfo trace, Memory index) {
        prepareRuntimeArrayWrite();getValue().unsetOfIndex(trace, index);
    }

    @Override
    public Memory issetOfIndex(TraceInfo trace, Memory index) {
        return getValue().issetOfIndex(trace, index);
    }

    @Override
    public Memory emptyOfIndex(TraceInfo trace, Memory index) {
        return getValue().emptyOfIndex(trace, index);
    }

    @Override
    public boolean isShortcut(){
        return getValue().isReference();
    }

    @Override
    public boolean identical(Memory memory) {
        return getValue().identical(memory);
    }

    @Override
    public boolean identical(long value) {
        return this.getValue().identical(value);
    }

    @Override
    public boolean identical(double value) {
        return this.getValue().identical(value);
    }

    @Override
    public boolean identical(boolean value) {
        return this.getValue().identical(value);
    }

    @Override
    public boolean identical(String value) {
        return this.getValue().identical(value);
    }

    @Override
    public ForeachIterator getNewIterator(Environment env, boolean getReferences, boolean getKeyReferences) {
        if (getReferences || getKeyReferences) prepareRuntimeArrayWrite();
        return getValue().getNewIterator(env, getReferences, getKeyReferences);
    }

    @Override
    public Type getRealType() {
        if (getValue().type == Type.REFERENCE)
            return getValue().getRealType();

        return getValue().type;
    }

    @Override
    public Memory assignConcat(Memory memory) {
        if (guarded() || getValue().isReference()) return assign(concat(memory));
        needStringBuilder().append(memory);
        return this;
    }

    @Override
    public Memory assignConcat(long memory) {
        if (guarded() || getValue().isReference()) return assign(concat(memory));
        needStringBuilder().append(memory);
        return this;
    }

    @Override
    public Memory assignConcat(double memory) {
        if (guarded() || getValue().isReference()) return assign(concat(memory));
        needStringBuilder().append(memory);
        return this;
    }

    @Override
    public Memory assignConcat(boolean memory) {
        if (guarded() || getValue().isReference()) return assign(concat(memory));
        needStringBuilder().append(memory);
        return this;
    }

    @Override
    public Memory assignConcat(String memory) {
        if (guarded() || getValue().isReference()) return assign(concat(memory));
        needStringBuilder().append(memory);
        return this;
    }

    @Override
    public boolean instanceOf(String className, String lowerClassName) {
        return getValue().instanceOf(className, lowerClassName);
    }

    @Override
    public boolean instanceOf(String name) {
        return getValue().instanceOf(name);
    }

    @Override
    public Memory toArray() {
        return getValue().toArray();
    }

    @Override
    public Memory toObject(Environment env) {
        return getValue().toObject(env);
    }

    @Override
    public Memory clone(Environment env, TraceInfo trace) throws Throwable {
        return getValue().clone(env, trace);
    }

    @Override
    public boolean isClosure() {
        return getValue().isClosure();
    }

    @Override
    public String toBinaryString() {
        return getValue().toBinaryString();
    }

    @Override
    public boolean instanceOf(Class<? extends IObject> clazz) {
        return getValue().instanceOf(clazz);
    }

    @Override
    public <T extends IObject> T toObject(Class<T> clazz) {
        return getValue().toObject(clazz);
    }

    @Override
    public int compareTo(Memory o) {
        return getValue().compareTo(o);
    }
}
