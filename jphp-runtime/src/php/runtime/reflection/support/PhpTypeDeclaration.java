package php.runtime.reflection.support;

import php.runtime.common.PhpClassNames;

import php.runtime.Memory;
import php.runtime.common.HintType;
import php.runtime.env.Environment;
import php.runtime.memory.DoubleMemory;
import php.runtime.memory.ObjectMemory;
import php.runtime.reflection.ClassEntity;
import php.runtime.reflection.ModuleEntity;
import java.util.*;

/** Immutable disjunction of conjunctions. Names are resolved by the declaration parser. */
public final class PhpTypeDeclaration extends TypeChecker {
    public static final Set<String> BUILTINS = Set.of("int", "float", "string", "bool", "array", "object",
            "iterable", "mixed", "null", "true", "false", "callable", "void", "never");
    private final List<List<String>> alternatives;
    private final String signature;

    public PhpTypeDeclaration(List<? extends List<String>> alternatives) {
        if (alternatives.isEmpty()) throw new IllegalArgumentException("Invalid type alternatives");
        List<List<String>> copy = new ArrayList<>();
        for (List<String> branch : alternatives) {
            if (branch.isEmpty()) throw new IllegalArgumentException("Invalid type members");
            for (String name : branch) if (name == null || name.isEmpty() || name.length() > 32768)
                throw new IllegalArgumentException("Invalid type name");
            copy.add(List.copyOf(branch));
        }
        boolean nullableNamed = copy.size() == 2 && copy.stream().allMatch(a -> a.size() == 1)
                && copy.stream().anyMatch(a -> a.getFirst().equals("null"));
        if (copy.size() > 1 && !nullableNamed) {
            List<List<String>> expanded = new ArrayList<>();
            for (var arm : copy) if (arm.equals(List.of("iterable"))) {
                expanded.add(List.of("Traversable")); expanded.add(List.of("array"));
            } else expanded.add(arm);
            copy = expanded;
        }
        copy.sort(Comparator.comparingInt(a -> a.size() != 1 ? 0 : typeOrder(a.getFirst())));
        this.alternatives = List.copyOf(copy);
        signature = format(this.alternatives);
    }
    private static int typeOrder(String name) {
        int index = List.of("static", "callable", "object", "array", "string", "int", "float", "bool", "false", "true", "null").indexOf(name);
        return BUILTINS.contains(name) || name.equals("static") ? index + 1 : 0;
    }
    public PhpTypeDeclaration bind(ClassEntity owner) {
        return new PhpTypeDeclaration(alternatives.stream().map(a -> a.stream().map(n -> relative(n, owner)).toList()).toList());
    }
    public PhpTypeDeclaration forTraitComposition(ClassEntity owner) {
        return new PhpTypeDeclaration(alternatives.stream().map(a -> a.stream().map(n ->
                n.equals("parent") && owner.getParent() != null ? owner.getParent().getName() : n).toList()).toList());
    }
    public List<List<String>> getAlternatives() {return alternatives;}
    public boolean equivalentTo(PhpTypeDeclaration other) {
        return normalized(semanticAlternatives()).equals(normalized(other.semanticAlternatives()));
    }
    private List<List<String>> semanticAlternatives() {
        List<List<String>> result = new ArrayList<>();
        for (var arm : alternatives) if (arm.equals(List.of("iterable"))) {
            result.add(List.of("Traversable")); result.add(List.of("array"));
        } else result.add(arm);
        return result;
    }
    private static Set<Set<String>> normalized(List<List<String>> alternatives) {
        Set<Set<String>> result = new HashSet<>();
        for (var arm : alternatives) {
            Set<String> names = new HashSet<>();
            for (String name : arm) names.add(PhpClassNames.canonicalKey(name));
            result.add(names);
        }
        return result;
    }
    public static PhpTypeDeclaration fromChecker(TypeChecker type, boolean nullable) {
        if (type == null) return new PhpTypeDeclaration(List.of(List.of("mixed")));
        PhpTypeDeclaration declaration = type instanceof PhpTypeDeclaration ? (PhpTypeDeclaration) type
                : new PhpTypeDeclaration(List.of(List.of(type.getSignature())));
        if (!nullable || declaration.allowsNull()) return declaration;
        var arms = new ArrayList<>(declaration.getAlternatives()); arms.add(List.of("null"));
        return new PhpTypeDeclaration(arms);
    }
    public boolean allowsNull() {return has("null") || has("mixed");}
    public boolean has(String name) {return alternatives.stream().anyMatch(a -> a.size() == 1 && a.getFirst().equals(name));}
    public boolean isNullableNamed() {return alternatives.size() == 2 && has("null") && alternatives.stream().allMatch(a -> a.size() == 1);}
    public String getNamedType() {
        if (alternatives.size() == 1 && alternatives.getFirst().size() == 1) return alternatives.getFirst().getFirst();
        if (isNullableNamed()) return alternatives.stream().filter(a -> !a.getFirst().equals("null")).findFirst().get().getFirst();
        return null;
    }
    @Override public String getSignature() {return signature;}
    @Override public String getHumanString() {return "the type " + signature;}
    @Override public boolean isBuiltin() {return getNamedType() != null && BUILTINS.contains(getNamedType());}

    public static String format(List<List<String>> alternatives) {
        if (alternatives.size() == 2 && alternatives.stream().allMatch(a -> a.size() == 1)
                && alternatives.stream().anyMatch(a -> a.getFirst().equals("null"))) {
            return "?" + alternatives.stream().filter(a -> !a.getFirst().equals("null")).findFirst().get().getFirst();
        }
        return String.join("|", alternatives.stream().map(a -> a.size() == 1 ? a.getFirst()
                : (alternatives.size() == 1 ? String.join("&", a) : "(" + String.join("&", a) + ")")).toList());
    }
    public String getRuntimeErrorSignature(Environment env, String ownerName) {
        if (ownerName == null && (has("self") || has("parent"))) {
            var object = env.getLateObject();
            ownerName = object instanceof php.runtime.lang.Closure && ((php.runtime.lang.Closure) object).hasPhp8Binding()
                    ? ((php.runtime.lang.Closure) object).getScope() : env.__getMacroClass().toString();
        }
        ClassEntity owner = ownerName == null || ownerName.isEmpty() ? null : env.fetchClass(ownerName, false);
        ClassEntity late = env.getLateStaticClass();
        List<List<String>> arms = new ArrayList<>();
        for (var arm : alternatives) {
            List<String> names = new ArrayList<>();
            for (String name : arm) names.add(name.equals("static") && late != null ? late.getName()
                    : owner == null ? name : relative(name, owner));
            arms.add(names);
        }
        return new PhpTypeDeclaration(arms).getErrorSignature();
    }
    public String getErrorSignature() {
        if (has("iterable")) {
            List<List<String>> expanded = new ArrayList<>();
            for (var arm : alternatives) {
                if (arm.size() == 1 && arm.getFirst().equals("iterable")) {
                    expanded.add(List.of("Traversable")); expanded.add(List.of("array"));
                } else expanded.add(arm);
            }
            return format(expanded);
        }
        return signature;
    }
    @Override public boolean check(Environment env, Memory value, boolean nullable, String ownerName) {
        if (nullable && value.isNull()) return true;
        for (List<String> branch : alternatives) {
            boolean matches = true;
            for (String name : branch) if (!checkAtom(env, value, name, ownerName)) {matches = false; break;}
            if (matches) return true;
        }
        return false;
    }
    private static boolean checkAtom(Environment env, Memory value, String name, String ownerName) {
        return switch (name) {
            case "null" -> value.isNull();
            case "true", "false" -> value.getRealType() == Memory.Type.BOOL && value.toBoolean() == name.equals("true");
            case "mixed" -> true;
            case "static" -> ownerName == null ? TypeChecker.of(HintType.STATIC).check(env, value, false, null)
                    : value.isObject() && PhpClassNames.matches(value.toValue(ObjectMemory.class).getReflection(), ownerName, env);
            case "never" -> false;
            default -> {
                HintType builtin = HintType.of(name);
                yield builtin != null && (BUILTINS.contains(name) || name.equals("self") || name.equals("parent")) ? TypeChecker.of(builtin).check(env, value, false, ownerName)
                        : value.isObject() && PhpClassNames.matches(value.toValue(ObjectMemory.class).getReflection(), name, env);
            }
        };
    }
    @Override public Memory apply(Environment env, Memory value, boolean nullable, boolean strict) {
        if ((nullable || allowsNull()) && value.isNull()) return value;
        if (value.getRealType() == Memory.Type.INT && has("float")) return DoubleMemory.valueOf(value.toDouble());
        if (strict) return null;
        if (value.isString() && has("float") && has("int")) {
            Memory numeric = php.runtime.memory.StringMemory.toNumericStrict(value.toString());
            if (numeric != null && numeric.getRealType() == Memory.Type.DOUBLE) return DoubleMemory.valueOf(numeric.toDouble());
        }
        // PHP's scalar preference is independent of declaration order; exact members win above.
        for (String name : List.of("int", "float", "string", "bool")) if (has(name)) {
            Memory coerced = TypeChecker.of(HintType.of(name)).apply(env, value, false, false);
            if (coerced != null) return coerced;
        }
        return null;
    }
    public Memory strictValue(Environment env, Memory value, ClassEntity owner) {
        if (check(env, value, false, owner.getName())) return value;
        return value.getRealType() == Memory.Type.INT && has("float") ? DoubleMemory.valueOf(value.toDouble()) : null;
    }
    public static String valueType(Memory value) {
        if (value.isObject()) return value.toValue(ObjectMemory.class).getReflection().getName();
        if (value.isNull()) return "null";
        if (value.getRealType() == Memory.Type.BOOL) return "bool";
        if (value.getRealType() == Memory.Type.INT) return "int";
        return value.getRealType().toString();
    }
    public boolean isSubtypeOf(PhpTypeDeclaration parent, ClassEntity owner, ClassEntity parentOwner,
                               ModuleEntity module, Environment env) {
        if (parent.has("mixed")) return true;
        for (List<String> childArm : semanticAlternatives()) {
            boolean included = false;
            for (List<String> parentArm : parent.semanticAlternatives()) {
                boolean all = true;
                for (String required : parentArm) {
                    boolean entailed = false;
                    for (String actual : childArm) if (atomSubtype(actual, required, owner, parentOwner, module, env)) {entailed = true; break;}
                    if (!entailed) {all = false; break;}
                }
                if (all) {included = true; break;}
            }
            if (!included) return false;
        }
        return true;
    }
    private static String relative(String name, ClassEntity owner) {
        return name.equals("self") ? owner.getName()
                : name.equals("parent") && owner.getParent() != null ? owner.getParent().getName() : name;
    }
    private static boolean atomSubtype(String child, String parent, ClassEntity owner, ClassEntity parentOwner,
                                       ModuleEntity module, Environment env) {
        if (child.equals("never") || parent.equals("mixed")) return true;
        child = relative(child, owner); parent = relative(parent, parentOwner);
        if (PhpClassNames.canonicalKey(child).equals(PhpClassNames.canonicalKey(parent))) return true;
        if (parent.equals("static")) return false;
        if (child.equals("static")) child = owner.getName();
        if (parent.equals("bool") && (child.equals("true") || child.equals("false"))) return true;
        if (parent.equals("callable") && PhpClassNames.canonicalKey(child).equals("closure")) return true;
        boolean named = !BUILTINS.contains(child);
        if (parent.equals("object")) return named;
        if (parent.equals("iterable") && child.equals("array")) return true;
        if (!named) return false;
        ClassEntity clazz = PhpClassNames.canonicalKey(owner.getName()).equals(PhpClassNames.canonicalKey(child)) ? owner : module.findClass(child);
        if (clazz == null && owner.getModule() != null) clazz = owner.getModule().findClass(child);
        if (clazz == null) clazz = env.fetchClass(child, false, php.runtime.common.PhpLanguageProfile.PHP_8_5);
        return clazz != null && (parent.equals("iterable") ? clazz.isInstanceOfLower("traversable")
                : PhpClassNames.matches(clazz, parent, env));
    }
}
