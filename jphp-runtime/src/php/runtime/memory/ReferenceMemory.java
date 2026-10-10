package php.runtime.memory;

import php.runtime.Memory;
import php.runtime.env.Environment;
import php.runtime.env.TraceInfo;
import php.runtime.lang.ForeachIterator;
import php.runtime.lang.IObject;

import java.nio.charset.Charset;

public class ReferenceMemory extends Memory {
    private static final Object SHARED_REFERENCE = new Object();
    private Memory _value;
    private transient Object ownershipAuthority;
    private php.runtime.memory.helper.PhpPropertyGuard.Source propertySource;
    private java.util.List<php.runtime.memory.helper.PhpPropertyGuard.Source> propertySources;

    public void attachPropertySource(Environment env, php.runtime.reflection.PropertyEntity property) {
        if (property.getDeclaredType() == null || propertySource != null) return;
        propertySource = new php.runtime.memory.helper.PhpPropertyGuard.Source(env, property);
        ReferenceMemory cell=getReference();
        if (cell.propertySources == null) cell.propertySources=new java.util.ArrayList<>();
        cell.propertySources.add(propertySource); propertySource.cell=cell;
    }

    /** Validates a magic getter reference without attaching the missing property's source. */
    public Memory checkPropertyReference(Environment env, php.runtime.reflection.PropertyEntity property) {
        var cell=getReference();
        return php.runtime.memory.helper.PhpPropertyGuard.bind(
                new php.runtime.memory.helper.PhpPropertyGuard.Source(env,property), cell, cell.propertySources);
    }

    public boolean hasPropertySources() {
        var cell=getReference();
        return cell.propertySources != null && !cell.propertySources.isEmpty();
    }

    /** Modern operators capture a zval, without making it a PHP reference. */
    public Memory incrementTarget(boolean decrement, boolean post, Environment env, TraceInfo trace) {
        return php.runtime.PhpArithmetic.incrementReference(this, decrement, post, env, trace);
    }

    /** Capture an overloaded array receiver once before walking another offset. */
    public Memory incrementArrayCarrier(Environment env, TraceInfo trace) { return this; }

    public boolean isSharedIncrementCell() { return getReference().hasSharedReference(); }

    public void checkIncrementOverflow(Memory before, boolean decrement, Environment env, TraceInfo trace) {
        if (before.type != Type.INT || before.toLong() != (decrement ? Long.MIN_VALUE : Long.MAX_VALUE)) return;
        ReferenceMemory cell = getReference();
        if (cell.propertySources == null) return;
        Memory candidate = DoubleMemory.valueOf((double) before.toLong() + (decrement ? -1 : 1));
        for (var source : cell.propertySources) {
            if (!source.type().check(env, candidate, false, source.property.getClazz().getName())) {
                env.exception(trace, php.runtime.lang.exception.BaseTypeError.class,
                        "Cannot %s %sproperty %s of type %s past its %s value",
                        decrement ? "decrement" : "increment", cell.hasSharedReference() ? "a reference held by " : "",
                        source.label(), source.type().getErrorSignature(), decrement ? "minimal" : "maximal");
            }
        }
    }

    public IncrementCell captureIncrementCell() { return new IncrementCell(this); }

    public static final class IncrementCell {
        private final ReferenceMemory origin;
        private final ReferenceMemory cell;
        private final php.runtime.memory.helper.PhpPropertyGuard.Source direct;
        private final boolean shared;
        private final boolean initiallyGuarded;
        private IncrementCell(ReferenceMemory origin) {
            this.cell = origin.getReference(); this.shared = cell.hasSharedReference();
            this.initiallyGuarded = cell.propertySources != null && !cell.propertySources.isEmpty();
            // ArrayValueMemory and other storage wrappers are not semantic PHP aliases.
            this.origin = !shared && origin.propertySource == null ? cell : origin;
            this.direct = shared ? null : this.origin.propertySource;
        }
        public Memory commit(Memory value) {
            cell.rejectUnmanagedOwnershipMutation();origin.rejectUnmanagedOwnershipMutation();
            if (shared) {
                Memory checked = !initiallyGuarded || cell.propertySources == null ? value
                        : php.runtime.memory.helper.PhpPropertyGuard.write(cell.propertySources, value, null);
                cell._value = checked;
                return checked;
            }
            if (direct != null)
                value = php.runtime.memory.helper.PhpPropertyGuard.write(java.util.List.of(direct), value, direct);
            return restore(value);
        }
        /** No-effect operators and failed typed writes restore the original zval without a second type check. */
        public Memory restore(Memory value) {
            cell.rejectUnmanagedOwnershipMutation();origin.rejectUnmanagedOwnershipMutation();
            if (shared) { cell._value = value; return value; }
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
            origin._value = value;
            return value;
        }
    }

    public void detachPropertySource() {
        if (propertySource == null) return;
        var cell=propertySource.cell;
        if (cell.propertySources != null) {
            cell.propertySources.remove(propertySource);
            if (cell.propertySources.isEmpty()) cell.propertySources=null;
        }
        propertySource.cell=null; propertySource=null;
    }

    /** Removal detaches exactly the declaring property's Source, never other reference constraints. */
    public final OwnershipSourceRemoval prepareOwnershipSourceRemoval() {return new OwnershipSourceRemoval(this);}
    public static final class OwnershipSourceRemoval {
        private final ReferenceMemory origin,cell;
        private final php.runtime.memory.helper.PhpPropertyGuard.Source source;
        private final java.util.List<php.runtime.memory.helper.PhpPropertyGuard.Source> before,after;
        private OwnershipSourceRemoval(ReferenceMemory origin) {
            this.origin=origin;source=origin.propertySource;cell=source == null ? null : source.cell;
            before=cell == null ? null : cell.propertySources;
            after=before == null ? null : new java.util.ArrayList<>(before);
            if (after != null) after.remove(source);
        }
        public void validate() {
            if (origin.propertySource != source || source != null && (source.cell != cell || cell.propertySources != before))
                throw new IllegalArgumentException("Property Source changed during removal preparation");
        }
        public OwnershipDeclaration declaration() {return source == null ? null : new OwnershipDeclaration(origin,source.environment,source.property);}
        private void store() {
            if (source != null) {cell.propertySources=after == null || after.isEmpty() ? null : after;source.cell=null;origin.propertySource=null;}
        }
    }
    /** Declaration provenance only: never retain the removed CELL or its old value/Sources. */
    public static final class OwnershipDeclaration {
        private final ReferenceMemory origin;
        private final Environment environment;
        private final php.runtime.reflection.PropertyEntity property;
        private OwnershipDeclaration(ReferenceMemory origin,Environment environment,php.runtime.reflection.PropertyEntity property) {
            this.origin=origin;this.environment=environment;this.property=property;
        }
        private php.runtime.memory.helper.PhpPropertyGuard.Source freshSource() {
            if (origin.propertySource != null || !origin._value.isUndefined()) throw new IllegalArgumentException("Unset declaration changed");
            return new php.runtime.memory.helper.PhpPropertyGuard.Source(environment,property);
        }
        public void initializeReference() {php.runtime.memory.helper.PhpPropertyGuard.initializeReference(freshSource());}
        public OwnershipRevival prepareRevival(Memory value) {return new OwnershipRevival(origin,freshSource(),value);}
    }
    public static final class OwnershipRevival {
        private final ReferenceMemory origin;
        private final php.runtime.memory.helper.PhpPropertyGuard.Source source;
        private final java.util.List<php.runtime.memory.helper.PhpPropertyGuard.Source> before,after;
        private final Memory checked;
        private OwnershipRevival(ReferenceMemory origin,php.runtime.memory.helper.PhpPropertyGuard.Source source,Memory value) {
            this.origin=origin;this.source=source;before=origin.propertySources;
            checked=php.runtime.memory.helper.PhpPropertyGuard.write(java.util.List.of(source),value,source);
            after=before == null ? new java.util.ArrayList<>() : new java.util.ArrayList<>(before);after.add(source);validate();
        }
        public Memory checked() {return checked;}
        public void validate() {
            if (origin.propertySource != null || origin.propertySources != before || !origin._value.isUndefined())
                throw new IllegalArgumentException("Unset declaration changed during revival validation");
        }
        private void store() {origin.propertySource=source;origin.propertySources=after;source.cell=origin;}
    }
    public final void publishOwnershipUnset(php.runtime.PhpOwnershipArrays.UnsetPublication publication) {
        publication.consumeCarrier(this);publication.sourceRemoval().store();_value=Memory.UNDEFINED;
    }
    public final void publishOwnershipRemovalSource(php.runtime.PhpOwnershipArrays.UnsetPublication publication) {
        publication.consumeSource(this);publication.sourceRemoval().store();
    }
    public final void relocateOwnershipEntry(php.runtime.PhpOwnershipArrays.UnsetPublication publication,ReferenceMemory replacement) {
        var entry=publication.consumeRelocation(this,replacement);
        if (ownershipAuthority == entry) ownershipAuthority=null;
        replacement.ownershipAuthority=entry;
    }

    private boolean guarded() {return propertySource != null || propertySources != null;}
    private Memory assignProperty(Memory value, php.runtime.memory.helper.PhpPropertyGuard.Source direct) {
        ReferenceMemory cell=getReference();
        cell.rejectUnmanagedOwnershipMutation();
        Memory checked=cell.propertySources == null ? value
                : php.runtime.memory.helper.PhpPropertyGuard.write(cell.propertySources, value, direct);
        cell._value=checked; return checked;
    }

    protected ReferenceMemory(Type type, Memory value) {
        super(type);
        this._value = value == null ? NULL : value;
    }

    public ReferenceMemory(Memory value) {
        super(Type.REFERENCE);
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
        return _value;
    }

    /** Ownership adapters may inspect a carrier without evaluating its overridden PHP getter. */
    public final Memory getRawValue() { return _value; }

    public final php.runtime.PhpOwnershipArrays.Entry getOwnershipEntry() {
        return ownershipAuthority instanceof php.runtime.PhpOwnershipArrays.Entry ? (php.runtime.PhpOwnershipArrays.Entry)ownershipAuthority : null;
    }
    public final void attachOwnershipEntry(php.runtime.PhpOwnershipArrays.Entry entry) {
        entry.validateCarrierAttachment(this);
        if (ownershipAuthority != null && ownershipAuthority != SHARED_REFERENCE && ownershipAuthority != entry
                && (!(ownershipAuthority instanceof php.runtime.PhpOwnershipArrays.CellCertificate)
                    || entry.cellCertificate() != ownershipAuthority))
            throw new IllegalArgumentException("Physical field already belongs to another ownership publisher");
        ownershipAuthority = entry;
    }
    private boolean hasSharedReference() {
        return ownershipAuthority == SHARED_REFERENCE
                || ownershipAuthority instanceof php.runtime.PhpOwnershipArrays.CellCertificate
                || ownershipAuthority instanceof php.runtime.PhpOwnershipArrays.Entry
                    && ((php.runtime.PhpOwnershipArrays.Entry) ownershipAuthority).cellCertificate() != null;
    }
    private void markSharedReference() {
        if (hasSharedReference()) return;
        rejectUnmanagedOwnershipMutation();ownershipAuthority = SHARED_REFERENCE;
    }
    private void rejectUnmanagedOwnershipMutation() {
        if (ownershipAuthority != null && ownershipAuthority != SHARED_REFERENCE)
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
            nextTarget=targetSources == null ? new java.util.ArrayList<>() : new java.util.ArrayList<>(targetSources);
            if (promotion && originSources != null) {
                for (var source : originSources) if (!nextTarget.contains(source)) nextTarget.add(source);
            } else if (direct != null && !nextTarget.contains(direct)) nextTarget.add(direct);
            nextOld=oldSources == null ? null : new java.util.ArrayList<>(oldSources);
            if (nextOld != null && direct != null) nextOld.remove(direct);
        }
        public Memory checkedTarget() {return checked;}
        public void validate() {
            if (consumed || origin._value != oldRaw || origin.propertySource != originalDirect
                    || origin.propertySources != originSources || target.propertySources != targetSources
                    || direct != null && (direct.cell != oldSourceCell || oldSourceCell != null && oldSourceCell.propertySources != oldSources))
                throw new IllegalArgumentException("Reference source address changed during validation");
        }
        private void publish(ReferenceMemory actual,php.runtime.PhpOwnershipArrays.RawPublication publication) {
            validate();if (actual != origin || target._value != checked)
                throw new IllegalArgumentException("Reference source/target no longer matches its permit");
            Memory pointer=publication.consume(origin);
            if (pointer != target) throw new IllegalArgumentException("Reference permit has a different canonical target");
            consumed=true;
            if (promotion) origin.propertySources=null;
            else if (oldSourceCell != null && oldSourceCell != target)
                oldSourceCell.propertySources=nextOld == null || nextOld.isEmpty() ? null : nextOld;
            target.propertySources=nextTarget.isEmpty() ? null : nextTarget;
            if (promotion && originSources != null) for (var source : originSources) source.cell=target;
            if (direct != null) {direct.cell=target;origin.propertySource=direct;}
            origin._value=target;
        }
    }
    public final void publishOwnershipReference(OwnershipReferenceChange change,php.runtime.PhpOwnershipArrays.RawPublication publication) {
        change.publish(this,publication);
    }

    /** Only an already validated, single-use target permit may publish the private raw field. */
    public final void publishOwnershipRaw(php.runtime.PhpOwnershipArrays.RawPublication publication) {
        _value = publication.consume(this);
        if (publication.revival() != null) publication.revival().store();
    }
    public final php.runtime.PhpOwnershipArrays.CellCertificate getOwnershipCell() {
        return ownershipAuthority instanceof php.runtime.PhpOwnershipArrays.CellCertificate
                ? (php.runtime.PhpOwnershipArrays.CellCertificate) ownershipAuthority
                : ownershipAuthority instanceof php.runtime.PhpOwnershipArrays.Entry
                    ? ((php.runtime.PhpOwnershipArrays.Entry) ownershipAuthority).cellCertificate() : null;
    }
    public final void attachOwnershipCell(php.runtime.PhpOwnershipArrays.CellCertificate certificate) {
        certificate.validateAttachment(this);
        if (ownershipAuthority != null && ownershipAuthority != SHARED_REFERENCE) {
            if (getOwnershipCell() != certificate)
                throw new IllegalArgumentException("Reference allocation already belongs to another ownership certificate");
            return;
        }
        ownershipAuthority = certificate;
    }

    /** A terminal explicit-reference allocation, never an ArrayValue/ArrayAccess carrier. */
    public final boolean isCanonicalReferenceCell() {
        return getClass() == ReferenceMemory.class && hasSharedReference() && !(_value instanceof ReferenceMemory);
    }

    public Memory setValue(Memory value) {
        rejectUnmanagedOwnershipMutation();
        if (guarded()) return assignProperty(value, propertySource);
        return this._value = value == null ? NULL : value;
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
        rejectStringOffsetReference(value);
        return value instanceof ReferenceMemory ? ((ReferenceMemory) value).getReferenceForBinding() : value;
    }

    public ReferenceMemory getReferenceForBinding() {
        if (ownershipAuthority != null && ownershipAuthority != SHARED_REFERENCE && !hasSharedReference() && !(_value instanceof ReferenceMemory))
            rejectUnmanagedOwnershipMutation();
        rejectStringOffsetReference(this);
        if (getValue().isReference()) {
            ReferenceMemory shared = getReference();
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
        _value=shared;
        return shared;
    }

    public ReferenceMemory getReference(){
        if (getValue().type == Type.REFERENCE){
            return ((ReferenceMemory)getValue()).getReference();
        } else
            return this;
    }

    @Override
    public Memory assignRef(Memory reference){
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
                target._value=checked;
                if (target.propertySources == null) target.propertySources=new java.util.ArrayList<>();
                target.propertySources.add(propertySource); propertySource.cell=target;
            }
            _value=target; return target;
        }
        setValue(reference);
        return reference;
    }

    @Override
    public Memory assign(long memory) {
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
        rejectUnmanagedOwnershipMutation();
        detachPropertySource();
        this._value=UNDEFINED;
    }

    @Override
    public void manualUnset(Environment env){
        rejectUnmanagedOwnershipMutation();
        if (this.getValue().isObject())
            this.getValue().manualUnset(env);

        this.unset();
    }

    public void needArray(){
        Type type = getRealType();
        if (type != Type.STRING && type != Type.ARRAY && type != Type.OBJECT){
            initializeArray();
        }
    }

    public Memory initializeArray() {
        ArrayMemory initialized=new ArrayMemory();
        ReferenceMemory cell=getReference();
        if (cell.propertySources != null) for (var source : cell.propertySources)
            if (!source.type().check(source.writer(),initialized,false,source.property.getClazz().getName()))
                source.writer().exception(source.trace(),php.runtime.lang.exception.BaseTypeError.class,
                        "Cannot auto-initialize an array inside property %s of type %s",source.label(),source.type().getErrorSignature());
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
        needArray();
        return getValue().refOfPush(trace);
    }

    @Override
    public Memory refOfIndexAsShortcut(TraceInfo trace, Memory index){
        needArray();
        switch (getValue().type){
            case STRING:
                return refOfIndex(trace, index);
            default: return getValue().refOfIndexAsShortcut(trace, index);
        }
    }

    @Override
    public Memory refOfIndex(TraceInfo trace, Memory index) {
        needArray();
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
        needArray();
        switch (getValue().type){
            case STRING: return CharMemory.valueOf(this, (StringMemory)this.getValue(), (int)index);
            default: return getValue().refOfIndex(trace, index);
        }
    }

    @Override
    public Memory refOfIndex(TraceInfo trace, double index) {
        needArray();
        switch (getValue().type){
            case STRING: return CharMemory.valueOf(this, (StringMemory)this.getValue(), (int)index);
            default: return getValue().refOfIndex(trace, index);
        }
    }

    @Override
    public Memory refOfIndex(TraceInfo trace, String index) {
        needArray();
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
        needArray();
        switch (getValue().type){
            case STRING: return CharMemory.valueOf(this, (StringMemory)this.getValue(), index ? 1 : 0);
            default: return getValue().refOfIndex(trace, index);
        }
    }

    @Override
    public void unsetOfIndex(TraceInfo trace, Memory index) {
        getValue().unsetOfIndex(trace, index);
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
