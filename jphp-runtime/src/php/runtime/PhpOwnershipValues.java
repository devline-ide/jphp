package php.runtime;

import php.runtime.memory.ArrayMemory;
import php.runtime.memory.ObjectMemory;
import php.runtime.memory.ReferenceMemory;

import java.util.Objects;

/**
 * Explicit value/canonical-cell certificates for the connected ownership adapter.
 * These certificates do not acquire owners. Callers must already have performed
 * the semantic read and registered the allocation in its actual heap domain.
 * Borrowed addresses and arbitrary Memory subclasses are never dereferenced here.
 */
public final class PhpOwnershipValues {
    public enum Role { VALUE, CELL }

    private PhpOwnershipValues() {}

    public static Binding bindValue(PhpOwnershipDomain domain,Memory actualSemanticValue,PhpOwnership.Node node) {
        return new Binding(domain,Role.VALUE,actualSemanticValue,node);
    }

    public static Binding bindCell(PhpOwnershipDomain domain,ReferenceMemory actualCanonicalCell,PhpOwnership.Node node) {
        return new Binding(domain,Role.CELL,actualCanonicalCell,node);
    }

    public static final class Binding {
        private final PhpOwnershipDomain domain;
        private final Role role;
        private final Memory raw;
        private final PhpOwnership.Node node;

        private Binding(PhpOwnershipDomain domain,Role role,Memory raw,PhpOwnership.Node node) {
            this.domain=Objects.requireNonNull(domain);this.role=role;
            this.raw=Objects.requireNonNull(raw);this.node=node;validate();
        }

        /** Admission to a certified holder requires an actual origin, not an opaque graph identity. */
        public Binding requireCertifiedObject() {
            validate();
            if (raw instanceof ObjectMemory) {
                var object=((ObjectMemory)raw).value;
                if(PhpOwnershipThrowables.isEngineThrowableType(object.getClass())) {
                    var nativeCertificate=((php.runtime.lang.exception.BaseTypeError)object).getOwnershipThrowable();
                    if(nativeCertificate==null)throw new IllegalArgumentException("Native TypeError lacks its complete allocation origin");
                    nativeCertificate.validateBinding(domain,node,object);return this;
                }
                if (!(object instanceof php.runtime.lang.support.IPhpOwnershipObject))
                    throw new IllegalArgumentException("Object has no complete ownership allocation protocol");
                var certificate=((php.runtime.lang.support.IPhpOwnershipObject)object).getOwnershipObject();
                if (certificate == null) throw new IllegalArgumentException("Object allocation has no origin certificate");
                certificate.validateBinding(domain,node,object);
            }
            return this;
        }

        public PhpOwnershipDomain domain() {return domain;}
        public Role role() {return role;}
        public Memory rawValue() {validate();return raw;}
        public PhpOwnership.Node node() {validate();return node;}

        /** A different Java view of the same PHP storage is not another PHP owner. */
        public Binding view(Memory actualPublishedRaw) {
            validate();return new Binding(domain,role,actualPublishedRaw,node);
        }

        public PhpOwnershipDomain.Owned own(PhpOwnershipDomain.Participant participant) throws Throwable {
            validate();Objects.requireNonNull(participant);
            if (participant.domain() != domain)
                throw new IllegalArgumentException("Ownership participant belongs to another domain");
            return role == Role.CELL ? participant.ownCell(node,raw) : participant.ownValue(node,raw);
        }

        public PhpOwnershipDomain.Owned host() throws Throwable {
            validate();return role == Role.CELL ? domain.hostCell(node,raw) : domain.hostValue(node,raw);
        }

        public PhpOwnershipFrame.Publication prepareCommit(PhpOwnershipFrame.Slot destination) {
            validate();Objects.requireNonNull(destination);
            if (destination.frame().graph() != domain.graph())
                throw new IllegalArgumentException("Ownership destination belongs to another domain");
            return destination.prepareCommit(node,raw);
        }

        private void validate() {
            domain.currentContext();
            if (node != null) {
                if (node.graph() != domain.graph())
                    throw new IllegalArgumentException("Ownership value belongs to another domain");
                if (node.state() == PhpOwnership.State.DISPOSED)
                    throw new IllegalStateException("Ownership value was disposed");
            }
            if (role == Role.CELL) {
                if (raw.getClass() != ReferenceMemory.class || !((ReferenceMemory)raw).isCanonicalReferenceCell())
                    throw new IllegalArgumentException("Ownership CELL requires an exported canonical reference cell");
                identity(PhpOwnership.Kind.CELL,raw);return;
            }
            // Reference/address carriers must have been semantically read by the caller.
            // Classifying them via isReference/getReference/toValue could invoke PHP code.
            if (raw instanceof ReferenceMemory)
                throw new IllegalArgumentException("Ownership VALUE requires an already-read semantic value");
            if (raw instanceof ArrayMemory) {
                if (raw instanceof php.runtime.memory.PhpObjectProperties)
                    throw new IllegalArgumentException("An object property table is not a PHP array VALUE");
                identity(PhpOwnership.Kind.STORAGE,((ArrayMemory)raw).getStorageIdentity());return;
            }
            if (raw instanceof ObjectMemory) {
                var object=Objects.requireNonNull(((ObjectMemory)raw).value);
                identity(PhpOwnership.Kind.OBJECT,object);
                if (object instanceof php.runtime.lang.support.IPhpOwnershipObject) {
                    var certificate=((php.runtime.lang.support.IPhpOwnershipObject)object).getOwnershipObject();
                    if (certificate != null) certificate.validateBinding(domain,node,object);
                }
                return;
            }
            if (node != null) throw new IllegalArgumentException("Scalar VALUE cannot own a heap node");
            Class<?> representation=raw.getClass();
            if (representation != php.runtime.memory.NullMemory.class
                    && representation != php.runtime.memory.helper.UndefinedMemory.class
                    && representation != php.runtime.memory.TrueMemory.class
                    && representation != php.runtime.memory.FalseMemory.class
                    && representation != php.runtime.memory.LongMemory.class
                    && representation != php.runtime.memory.DoubleMemory.class
                    && representation != php.runtime.memory.StringMemory.class
                    && representation != php.runtime.memory.BinaryMemory.class
                    && representation != php.runtime.memory.PhpByteStringMemory.class
                    && representation != php.runtime.memory.StringBuilderMemory.class)
                throw new IllegalArgumentException("Scalar VALUE has an uncertified representation");
            switch (raw.type) {
                case NULL: case BOOL: case INT: case DOUBLE: case STRING: return;
                default: throw new IllegalArgumentException("Ownership VALUE has an uncertified representation");
            }
        }

        private void identity(PhpOwnership.Kind kind,Object identity) {
            if (node == null || node.kind() != kind || node.identity() != identity)
                throw new IllegalArgumentException("Ownership node does not identify the actual PHP value");
        }
    }
}
