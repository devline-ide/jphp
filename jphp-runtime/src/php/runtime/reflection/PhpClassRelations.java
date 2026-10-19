package php.runtime.reflection;

import java.util.List;
import java.util.Objects;
import php.runtime.common.Modifier;
import php.runtime.env.TraceInfo;

/** Declaration-only recipe. It neither resolves names nor composes or publishes a class. */
public final class PhpClassRelations {
    public static final PhpClassRelations EMPTY = new PhpClassRelations(null, List.of(), List.of());

    public record Name(String name, TraceInfo trace) {
        public Name {
            requireName(name);
            Objects.requireNonNull(trace, "trace");
        }
    }

    public enum InterfaceKind { IMPLEMENTS, EXTENDS }

    public record InterfaceUse(Name name, InterfaceKind kind) {
        public InterfaceUse {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(kind, "kind");
        }
    }

    public sealed interface Adaptation permits Alias, Precedence { TraceInfo trace(); }

    /** A null trait is an actual unqualified source directive, not the first trait in its use group. */
    public record Alias(String trait, String method, String alias, Modifier visibility,
                        boolean makeFinal, TraceInfo trace) implements Adaptation {
        public Alias {
            if (trait != null) requireName(trait);
            requireName(method);
            if (alias != null) requireName(alias);
            Objects.requireNonNull(trace, "trace");
        }
    }

    public record Precedence(String preferredTrait, String method, List<Name> excluded,
                             TraceInfo trace) implements Adaptation {
        public Precedence {
            requireName(preferredTrait);
            requireName(method);
            excluded = List.copyOf(excluded);
            if (excluded.isEmpty()) throw new IllegalArgumentException("Empty trait precedence directive");
            Objects.requireNonNull(trace, "trace");
        }
    }

    public record TraitGroup(List<Name> traits, List<Adaptation> adaptations, TraceInfo trace) {
        public TraitGroup {
            traits = List.copyOf(traits);
            adaptations = List.copyOf(adaptations);
            if (traits.isEmpty()) throw new IllegalArgumentException("Empty trait use group");
            Objects.requireNonNull(trace, "trace");
        }
    }

    private final Name parent;
    private final List<InterfaceUse> interfaces;
    private final List<TraitGroup> traitGroups;

    public PhpClassRelations(Name parent, List<InterfaceUse> interfaces, List<TraitGroup> traitGroups) {
        this.parent = parent;
        this.interfaces = List.copyOf(interfaces);
        this.traitGroups = List.copyOf(traitGroups);
    }

    public Name parent() { return parent; }
    public List<InterfaceUse> interfaces() { return interfaces; }
    public List<TraitGroup> traitGroups() { return traitGroups; }
    public boolean isEmpty() { return parent == null && interfaces.isEmpty() && traitGroups.isEmpty(); }

    private static void requireName(String name) {
        if (name == null || name.isEmpty() || name.indexOf('\0') >= 0)
            throw new IllegalArgumentException("Invalid declared relation name");
    }
}
