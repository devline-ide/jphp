package php.runtime;

import java.lang.classfile.*;
import java.lang.classfile.instruction.*;
import java.lang.reflect.AccessFlag;
import java.util.*;
import php.runtime.env.Environment;
import php.runtime.env.TraceInfo;
import php.runtime.loader.RuntimeClassLoader;

/** An effectful constant read is admitted by its actual defined instruction, never by a name whitelist. */
public final class PhpConstantReads {
    public static final String OWNER = "php/runtime/PhpConstantReads";
    public static final String DESCRIPTOR = "(Lphp/runtime/env/Environment;Ljava/lang/String;Ljava/lang/String;Lphp/runtime/env/TraceInfo;ZLjava/lang/String;)Lphp/runtime/Memory;";
    private PhpConstantReads() {}
    public static final class Site {
        private final Class<?> owner;
        private final String member, descriptor, name, lowerName, key;
        private final int bci, traceIndex;
        private final boolean fallback;
        private Site(Class<?> owner, String member, String descriptor, int bci, String name,
                     String lowerName, int traceIndex, boolean fallback, String key) {
            this.owner=owner; this.member=member; this.descriptor=descriptor; this.bci=bci; this.name=name;
            this.lowerName=lowerName; this.traceIndex=traceIndex; this.fallback=fallback; this.key=key;
        }
        public Class<?> owner() { return owner; }
        public String member() { return member; }
        public String descriptor() { return descriptor; }
        public int bci() { return bci; }
        public String name() { return name; }
        public String lowerName() { return lowerName; }
        public int traceIndex() { return traceIndex; }
        public boolean fallback() { return fallback; }
        public String key() { return key; }
        public boolean matches(java.lang.reflect.Method method) {
            return owner == method.getDeclaringClass() && member.equals(method.getName())
                    && descriptor.equals(PhpOwnershipExecution.descriptor(method));
        }
    }
    private record Draft(int bci, String name, String lowerName, int traceIndex, boolean fallback, String key) {}
    private static final StackWalker WALKER = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);
    private static final ClassValue<Map<String, Map<Integer, Site>>> SITES = new ClassValue<>() {
        @Override protected Map<String, Map<Integer, Site>> computeValue(Class<?> owner) {
            if (!(owner.getClassLoader() instanceof RuntimeClassLoader loader)) return Map.of();
            byte[] bytes = loader.definedInputBytes(owner);
            if (bytes == null || !loader.matchesDefinedBytecode(owner, bytes)) return Map.of();
            try {
                Map<String, Map<Integer, Site>> result = new HashMap<>();
                for (var method : ClassFile.of().parse(bytes).methods()) {
                    String name = method.methodName().stringValue(), descriptor = method.methodType().stringValue();
                    Map<Integer, Site> sites = new HashMap<>();
                    for (var draft : inspect(method)) sites.put(draft.bci(), new Site(owner, name, descriptor,
                            draft.bci(), draft.name(), draft.lowerName(), draft.traceIndex(), draft.fallback(), draft.key()));
                    result.put(name + descriptor, Map.copyOf(sites));
                }
                return Map.copyOf(result);
            } catch (RuntimeException unsupported) { return Map.of(); }
        }
    };
    private static List<Draft> inspect(MethodModel method) {
        var optional = method.code();
        if (optional.isEmpty() || !method.methodType().stringValue().startsWith("(Lphp/runtime/env/Environment;")) return List.of();
        CodeModel code = optional.get();
        List<Instruction> ops = code.elementList().stream().filter(Instruction.class::isInstance).map(Instruction.class::cast).toList();
        int[] offsets = new int[ops.size()]; int bci = 0;
        for (int at = 0; at < ops.size(); at++) { offsets[at] = bci; bci += ops.get(at).sizeInBytes(); }
        for (var element : code.elementList()) {
            if (element instanceof BranchInstruction branch) branch.target();
            else if (element instanceof TableSwitchInstruction table) { table.defaultTarget(); for (var item : table.cases()) item.target(); }
            else if (element instanceof LookupSwitchInstruction lookup) { lookup.defaultTarget(); for (var item : lookup.cases()) item.target(); }
        }
        Map<Label, Integer> labels = new IdentityHashMap<>(); int ordinal = 0;
        for (var element : code.elementList()) { if (element instanceof LabelTarget target) labels.put(target.label(), ordinal); else if (element instanceof Instruction) ordinal++; }
        Set<Integer> targets = new HashSet<>();
        for (var op : ops) {
            if (op instanceof BranchInstruction branch) targets.add(labels.get(branch.target()));
            else if (op instanceof TableSwitchInstruction table) { targets.add(labels.get(table.defaultTarget())); for (var item : table.cases()) targets.add(labels.get(item.target())); }
            else if (op instanceof LookupSwitchInstruction lookup) { targets.add(labels.get(lookup.defaultTarget())); for (var item : lookup.cases()) targets.add(labels.get(item.target())); }
        }
        for (var handler : code.exceptionHandlers()) targets.add(labels.get(handler.handler()));
        int envSlot = method.flags().has(AccessFlag.STATIC) ? 0 : 1;
        String owner = method.parent().orElseThrow().thisClass().asInternalName();
        List<Draft> result = new ArrayList<>();
        for (int at = 8; at < ops.size(); at++) if (ops.get(at) instanceof InvokeInstruction call
                && call.opcode() == Opcode.INVOKESTATIC && call.owner().asInternalName().equals(OWNER)
                && call.name().equalsString("fetch") && call.type().equalsString(DESCRIPTOR)) {
            if (!(ops.get(at - 8) instanceof LoadInstruction load) || load.typeKind() != TypeKind.REFERENCE || load.slot() != envSlot
                    || !(constant(ops.get(at - 7)) instanceof String name) || !(constant(ops.get(at - 6)) instanceof String lower)
                    || !lower.equals(name.toLowerCase(Locale.ROOT)) || !(ops.get(at - 5) instanceof FieldInstruction trace)
                    || trace.opcode() != Opcode.GETSTATIC || !trace.owner().asInternalName().equals(owner)
                    || !trace.name().equalsString("$TRC") || !trace.type().equalsString("[Lphp/runtime/env/TraceInfo;")
                    || !(constant(ops.get(at - 4)) instanceof Integer index) || index < 0 || ops.get(at - 3).opcode() != Opcode.AALOAD
                    || !(constant(ops.get(at - 2)) instanceof Integer fallback) || fallback < 0 || fallback > 1
                    || !(constant(ops.get(at - 1)) instanceof String key) || !key.startsWith("\0jphp.constant.fetch\0")) continue;
            boolean branched = false;
            for (int inside = at - 7; inside <= at; inside++) if (targets.contains(inside)) { branched = true; break; }
            if (!branched) result.add(new Draft(offsets[at], name, lower, index, fallback == 1, key));
        }
        return result;
    }
    private static Object constant(Instruction instruction) { return instruction instanceof ConstantInstruction value ? value.constantValue() : null; }
    private static Site require(String name, String lower, TraceInfo trace, boolean fallback, String key) {
        var caller = WALKER.walk(stream -> stream.filter(frame -> frame.getDeclaringClass() != PhpConstantReads.class).findFirst().orElseThrow());
        Site site = SITES.get(caller.getDeclaringClass()).getOrDefault(caller.getMethodName() + caller.getDescriptor(), Map.of()).get(caller.getByteCodeIndex());
        if (site == null || !site.name().equals(name) || !site.lowerName().equals(lower) || site.fallback() != fallback || !site.key().equals(key))
            throw new IllegalArgumentException("Constant read has no exact defined caller and operands");
        try {
            var field = site.owner().getDeclaredField("$TRC"); field.setAccessible(true);
            if (!(field.get(null) instanceof TraceInfo[] traces) || site.traceIndex() >= traces.length || trace != traces[site.traceIndex()])
                throw new IllegalArgumentException("Constant read has another physical trace");
        } catch (ReflectiveOperationException failure) { throw new IllegalArgumentException("Constant read lost its generated trace table", failure); }
        return site;
    }
    public static Memory fetch(Environment env, String name, String lower, TraceInfo trace, boolean fallback, String key) throws Throwable {
        var entry = PhpOwnershipExecution.currentEntry(env); var authority = PhpRuntimeOwnership.current(env);
        if (entry == null && authority == null) return env.__getConstant(name, lower, trace, fallback, key);
        if (env.getClass() != Environment.class) throw new IllegalArgumentException("Managed constant read needs the actual Environment contract");
        Site site = require(name, lower, trace, fallback, key);
        var frame = authority == null ? null : authority.currentFrame();
        if (entry != null) entry.requireConstantRead(site); else {
            if (frame == null) throw new IllegalArgumentException("Constant read has no actual runtime frame");
            frame.requireConstantRead(site);
        }
        Memory result = env.__getConstant(name, lower, trace, fallback, key);
        return entry != null ? entry.retainConstantRead(site, result) : frame.retainConstantRead(site, result);
    }
}
