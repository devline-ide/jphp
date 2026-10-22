package php.runtime.memory;

import php.runtime.Memory;
import php.runtime.reflection.PropertyEntity;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Object storage: declared anchors and physical Zend-style dynamic buckets. */
public final class PhpObjectProperties extends ArrayMemory {
    private Map<String, PropertyEntity> declarations;
    private Set<String> explicitlyUnset;

    /** Engine-only metadata. The same certified TABLE and backing identity are retained. */
    private static final class LazyDeclarations extends HashMap<String, PropertyEntity> {
        final php.runtime.PhpLazyObjects.State state;
        LazyDeclarations(Map<String, PropertyEntity> declared, php.runtime.PhpLazyObjects.State state) {
            if (declared != null) putAll(declared);
            this.state = state;
        }
    }
    public php.runtime.PhpLazyObjects.State lazyState() {
        return declarations instanceof LazyDeclarations ? ((LazyDeclarations) declarations).state : null;
    }
    public void installLazyState(php.runtime.PhpLazyObjects.State state) {
        java.util.Objects.requireNonNull(state).validateTableInstallation(this);
        if (lazyState() != null) throw new IllegalStateException("Lazy metadata must be detached before replacement");
        declarations = new LazyDeclarations(declarations, state);
    }
    public void detachLazyState(php.runtime.PhpLazyObjects.State state) {
        if (lazyState() != state) throw new IllegalArgumentException("Foreign lazy table state");
        state.validateTableDetachment(this);
        declarations = declarations.isEmpty() ? null : new HashMap<>(declarations);
    }

    public PhpObjectProperties() {
        super(false);
    }

    public void declare(PropertyEntity property) {
        rejectUnmanagedOwnershipMutation();
        if (declarations == null) declarations = new HashMap<>();
        declarations.putIfAbsent(property.getSpecificName(), property);
    }

    public PropertyEntity declaration(String storageKey) { return declarations == null ? null : declarations.get(storageKey); }
    public boolean isExplicitlyUnset(String storageKey) {
        ReferenceMemory value = super.getByScalar(storageKey);
        return explicitlyUnset != null && explicitlyUnset.contains(storageKey) && value != null && value.isUndefined();
    }
    public boolean hasUnsetAnchors() { return explicitlyUnset != null && !explicitlyUnset.isEmpty(); }
    public boolean omitUndefined(String storageKey) {
        PropertyEntity property = declaration(storageKey);
        return isExplicitlyUnset(storageKey) || property != null && property.getDeclaredType() != null
                && property.getClazz().getModule() != null
                && property.getClazz().getModule().getPhpLanguageProfile() == php.runtime.common.PhpLanguageProfile.PHP_8_5;
    }

    /** SPL's explicit raw own-property loader; ordinary assignment/type/readonly paths stay guarded. */
    public ReferenceMemory restoreSplArrayObjectProperty(php.runtime.env.Environment env,
            php.runtime.lang.spl.iterator.ArrayObject owner,String storageKey,Memory value) {
        rejectUnmanagedOwnershipMutation();
        if(owner==null || owner.peekProperties()!=this || owner.getEnvironment()!=env
                || php.runtime.PhpOwnershipExecution.current(env)!=null)
            throw new IllegalArgumentException("Raw SPL restoration requires its actual ordinary receiver table");
        PropertyEntity property=declaration(storageKey);
        if(property==null || property.isStatic() || !property.getSpecificName().equals(storageKey)
                || !owner.getReflection().isInstanceOf(property.getClazz())
                || property.getClazz().findProperty(property.getName())!=property)
            throw new IllegalArgumentException("Raw SPL restoration requires its actual declared own slot");
        ReferenceMemory address=super.getByScalar(storageKey);
        if(property.isPhpReadonly() && address!=null && !address.isUndefined())
            env.exception(php.runtime.lang.exception.BaseError.class,"Cannot modify readonly property %s::$%s",property.getClazz().getName(),property.getName());
        if(address==null)address=putAsKeyString(storageKey,Memory.UNDEFINED);
        address.publishSplArrayObjectOwnProperty(this,owner,property,value);
        return address;
    }

    public ReferenceMemory republishDeclared(String storageKey, Memory value) {
        rejectUnmanagedOwnershipMutation();
        ReferenceMemory anchor = super.getByScalar(storageKey);
        if (anchor == null) return putAsKeyString(storageKey, value);
        anchor.detachPropertySource();
        // Replace the slot zval rather than assigning through a handler-created PHP alias.
        anchor.setValue(value);
        return anchor;
    }

    @Override public ReferenceMemory getByScalar(Object key) {
        ReferenceMemory value = super.getByScalar(key);
        return value != null && explicitlyUnset != null && explicitlyUnset.contains(key) && value.isUndefined() ? null : value;
    }

    @Override public ReferenceMemory getByScalarOrCreate(Object key, Memory initial) {
        rejectUnmanagedOwnershipMutation();
        ReferenceMemory anchor = super.getByScalar(key);
        if (anchor != null) {
            if (explicitlyUnset != null && explicitlyUnset.contains(key) && anchor.isUndefined()) {
                PropertyEntity property = declarations == null ? null : declarations.get(key);
                if (property != null && property.getDeclaredType() == null) anchor.assign(Memory.NULL);
            }
            return anchor;
        }
        return super.getByScalarOrCreate(key, initial);
    }

    /** Return false for a repeated unset so magic dispatch still sees a missing slot. */
    public boolean unsetDeclared(String storageKey) {
        rejectUnmanagedOwnershipMutation();
        ReferenceMemory anchor = getByScalar(storageKey);
        if (declaration(storageKey) == null || anchor == null) return false;
        anchor.unset();
        if (explicitlyUnset == null) explicitlyUnset = new HashSet<>();
        explicitlyUnset.add(storageKey);
        return true;
    }

    /** Preallocate explicit-unset metadata outside the graph barrier. */
    public Set<String> prepareOwnershipUnsetKeys(String key) {
        Set<String> result=explicitlyUnset == null ? new HashSet<>() : new HashSet<>(explicitlyUnset);
        result.add(key);return result;
    }
    public void publishOwnershipDeclaredUnset(php.runtime.PhpOwnershipArrays.UnsetPublication publication,ReferenceMemory carrier) {
        Set<String> next=publication.unsetKeys(this);
        carrier.publishOwnershipUnset(publication);explicitlyUnset=next;
    }

    @Override public Memory refOfIndex(php.runtime.env.TraceInfo trace, String key) {
        return getByScalarOrCreate(key, Memory.UNDEFINED);
    }

    @Override public Memory refOfIndex(php.runtime.env.TraceInfo trace, Memory key) {
        return refOfIndex(trace, key.toString());
    }

    @Override public boolean containsKey(Object key) { return getByScalar(key) != null; }

    @Override public Memory removeByScalar(Object key) {
        rejectUnmanagedOwnershipMutation();
        // Property names are literal strings, including names such as "0".
        if (!(key instanceof String)) return super.removeByScalar(key);
        ReferenceMemory entry = super.getByScalar(key);
        if (entry == null) return null;
        Memory removed = map.remove(key);
        if (removed != null) size--;
        return removed;
    }

    /** Same TABLE/storage allocation; only its prevalidated physical layout and unset metadata change. */
    public void publishOwnershipLazyReplacement(php.runtime.PhpLazyPropertyTransaction.Prepared placement) {
        var replacement=placement.consumeRaw(this);
        map=replacement;_list=null;size=placement.rawSize(this);lastLongIndex=-1;
        explicitlyUnset=placement.rawUnset(this);foreachIterator=null;
    }

    @Override public void clear() {
        var lazy = lazyState();
        if (lazy != null) lazy.validateRawClear(this);
        super.clear();
        if (declarations != null) declarations.clear();
        if (explicitlyUnset != null) explicitlyUnset.clear();
    }

    public void copyMetadataFrom(PhpObjectProperties source) {
        rejectUnmanagedOwnershipMutation();
        if (source.declarations != null) {
            if (declarations == null) declarations = new HashMap<>();
            declarations.putAll(source.declarations);
        }
        if (source.explicitlyUnset != null) {
            if (explicitlyUnset == null) explicitlyUnset = new HashSet<>();
            explicitlyUnset.addAll(source.explicitlyUnset);
        }
    }
}
