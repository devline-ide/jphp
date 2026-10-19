package php.runtime;

import java.lang.ref.WeakReference;
import java.util.*;
import php.runtime.env.*;
import php.runtime.lang.Closure;
import php.runtime.memory.*;
import php.runtime.memory.support.ArrayMapEntryMemory;

/** Sparse physical CELL custody. No ordinary Memory or context-frame fields are added. */
public final class PhpPrimitiveCellRuntime {
    private PhpPrimitiveCellRuntime() {}
    private static final ThreadLocal<Session> CURRENT = new ThreadLocal<>();
    private static final ThreadLocal<Permit> PUBLICATION = new ThreadLocal<>();
    private static final Object UNKNOWN_STATIC = new Object();
    private static final StackWalker WALKER = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);
    public static final class ReadAccess {
        private ReadAccess() {}
        public void validate() { if (this != READ) throw new IllegalArgumentException("Foreign CELL raw reader"); }
    }
    private static final ReadAccess READ = new ReadAccess();
    private static Memory raw(ReferenceMemory carrier) { return carrier.readPrimitiveRaw(READ); }
    /** Invalidation only: inspect actual existing attachments, never mutate a foreign Session census. */
    private static Memory invalidateAttachedCell(Memory value) {
        for(int depth=0;depth<4&&value instanceof ReferenceMemory carrier;depth++) {
            var attachment=carrier.getPrimitiveCellAttachment();
            if(attachment instanceof Cell known){known.escape();return null;}
            if(attachment instanceof Slot slot){slot.cell.escape();return null;}
            if(attachment instanceof ObjectSlot slot){slot.resource.escape();return null;}
            value=raw(carrier);
        }
        return value;
    }
    private static void invalidateForeignIngress(Memory value) {
        Memory terminal=invalidateAttachedCell(value);
        if(terminal instanceof ObjectMemory object&&object.value instanceof Closure closure)closure.invalidatePrimitiveCaptures();
    }
    public static void beforeStructuralRead(ReferenceMemory carrier) {
        if(CURRENT.get()==null){invalidateForeignIngress(carrier);return;}
        if (PUBLICATION.get() != null) return;
        if(carrier.getPrimitiveCellAttachment() instanceof ObjectSlot slot){
            Class<?> caller=WALKER.walk(stream->stream.skip(2).findFirst().map(StackWalker.StackFrame::getDeclaringClass).orElse(null));
            if(caller!=ReferenceMemory.class)slot.resource.escape();return;
        }
        Cell cell = cell(carrier); if (cell == null) return;
        Class<?> caller = WALKER.walk(stream -> stream.skip(2).findFirst().map(StackWalker.StackFrame::getDeclaringClass).orElse(null));
        if (caller != ReferenceMemory.class) cell.escape();
    }
    public static void beforeRawRead(ReferenceMemory carrier) {
        if(CURRENT.get()==null){invalidateForeignIngress(carrier);return;}
        if (PUBLICATION.get() != null) return;
        var resource=resource(carrier);if(resource!=null)resource.escape();
        Cell cell = cell(carrier); if (cell != null) cell.escape();
    }

    public abstract static class Attachment {
        private final ReferenceMemory carrier;
        private Attachment(ReferenceMemory carrier) { this.carrier = carrier; }
        public final void validateCarrier(ReferenceMemory actual) {
            Permit permit = PUBLICATION.get();
            if (actual != carrier || permit == null || !permit.active)
                throw new IllegalArgumentException("CELL attachment needs its physical publication permit");
        }
    }
    private static final class Cell extends Attachment {
        final Session session;
        final WeakReference<Closure> closure;
        final String key;
        final ReferenceMemory staticSlot;
        final long epoch;
        long owners = 1;
        volatile boolean complete = true;
        Cell(ReferenceMemory carrier, Session session, Closure closure, String key, ReferenceMemory staticSlot) {
            super(carrier); this.session = session; this.closure = new WeakReference<>(closure);
            this.key = key; this.staticSlot = staticSlot; this.epoch = session.epoch;
        }
        void escape() { complete = false; }
        boolean usable() { return complete && !session.closed && !session.incomplete && session.epoch == epoch; }
    }
    private static final class Slot extends Attachment {
        final Cell cell;
        final Scope scope;
        final boolean borrowed, table;
        ReferenceMemory borrowedStatic;
        boolean live = true;
        Slot(ReferenceMemory carrier, Cell cell, Scope scope, boolean borrowed, boolean table) {
            super(carrier); this.cell = cell; this.scope = scope; this.borrowed = borrowed; this.table = table;
        }
        void retire() {
            if (!live) { cell.escape(); return; }
            live = false;
            if (!borrowed) { if (cell.owners < 1) cell.escape(); else cell.owners--; }
        }
    }
    private static final class ReturnLease {
        final Cell cell;
        final PhpPropertyWriteContext.Frame receiver;
        final PhpPrimitiveCellPlan.Member producer;
        boolean live = true, evaluation;
        ReturnLease(Cell cell, PhpPropertyWriteContext.Frame receiver, PhpPrimitiveCellPlan.Member producer) {
            this.cell = cell; this.receiver = receiver; this.producer = producer; cell.owners++;
        }
        void retire() { if (!live) { cell.escape(); return; } live = false; cell.owners--; }
    }
    private static final class SendLease {
        final Cell cell;final PhpPrimitiveCellPlan.Member target;final PhpPropertyWriteContext.Frame receiver;final int index;final ReferenceMemory canonical;
        boolean live=true;
        SendLease(Cell cell,PhpPrimitiveCellPlan.Member target,PhpPropertyWriteContext.Frame receiver,int index){this.cell=cell;this.target=target;this.receiver=receiver;this.index=index;this.canonical=((Attachment)cell).carrier;cell.owners++;}
        void retire(){if(!live){cell.escape();return;}live=false;cell.owners--;}
    }
    private record LocalAddress(ArrayMemory table,String name){}
    /** One captured object's physical use slots stay live while any certified CV or EVAL owns that object. */
    private static final class CaptureResource {
        final Session session;final PhpPrimitiveCellPlan.Member producer;final Class<?> type;final Memory[] uses;final Cell[] cells;
        Closure object;long owners=1;boolean evaluation=true,released;
        CaptureResource(Session session,PhpPrimitiveCellPlan.Member producer,Class<?> type,Memory[] uses){this.session=session;this.producer=producer;this.type=type;this.uses=uses;this.cells=new Cell[uses.length];}
        void escape(){for(var cell:cells)if(cell!=null)cell.escape();}
        void retire(){
            if(owners<1||released){escape();return;}
            if(--owners==0){
                released=true;for(var cell:cells)if(cell!=null)cell.owners--;
                // Final physical retirement must not keep the Closure/use array rooted until module exit.
                if(session.capturedObjects!=null&&object!=null&&session.capturedObjects.get(object)==this)session.capturedObjects.remove(object);
                if(session.pendingCaptures!=null&&session.pendingCaptures.get(uses)==this)session.pendingCaptures.remove(uses);
            }
        }
    }
    private static final class ObjectSlot extends Attachment {
        final Scope scope;final CaptureResource resource;boolean live=true;
        ObjectSlot(ReferenceMemory carrier,Scope scope,CaptureResource resource){super(carrier);this.scope=scope;this.resource=resource;resource.owners++;}
        void retire(){if(!live){resource.escape();return;}live=false;resource.retire();}
    }
    private static final class Scope {
        final PhpPropertyWriteContext.Frame frame;
        final IdentityHashMap<ReferenceMemory, Slot> slots = new IdentityHashMap<>();
        PhpPrimitiveCellPlan.Member member;
        IdentityHashMap<ReferenceMemory,LocalAddress> addresses;
        IdentityHashMap<ReferenceMemory,ObjectSlot> objects;
        Scope(PhpPropertyWriteContext.Frame frame) { this.frame = frame; }
    }
    private static final class Session {
        Environment environment;
        PhpPropertyWriteContext.Frame root;
        final IdentityHashMap<PhpPropertyWriteContext.Frame, Scope> scopes = new IdentityHashMap<>();
        final ArrayDeque<ReturnLease> returns = new ArrayDeque<>();
        IdentityHashMap<php.runtime.invoke.PhpCallFrame,List<SendLease>> sends;
        IdentityHashMap<Memory[],CaptureResource> pendingCaptures;
        IdentityHashMap<Closure,CaptureResource> capturedObjects;
        long epoch;
        boolean closed,incomplete;
        Session(Environment environment, PhpPropertyWriteContext.Frame root) { this.environment = environment; this.root = root; }
        Scope scope(PhpPropertyWriteContext.Frame frame, PhpPrimitiveCellPlan.Member member) {
            Scope scope = scopes.computeIfAbsent(frame, Scope::new);
            if (scope.member == null) scope.member = member;
            else if (scope.member != member) { epoch++;incomplete=true; }
            if (!member.closed()) incomplete=true;
            return scope;
        }
    }
    private static final class Permit implements AutoCloseable {
        final Permit previous;
        boolean active = true;
        Permit() { previous = PUBLICATION.get(); PUBLICATION.set(this); }
        @Override public void close() { active = false; if (previous == null) PUBLICATION.remove(); else PUBLICATION.set(previous); }
    }
    private static ReferenceMemory reference(Memory value) {
        return value instanceof ReferenceMemory ? (ReferenceMemory) value : null;
    }
    private static Cell cell(Memory value) {
        for (int depth = 0; depth < 4 && value instanceof ReferenceMemory; depth++) {
            ReferenceMemory ref = (ReferenceMemory) value;
            Attachment attachment = ref.getPrimitiveCellAttachment();
            if (attachment instanceof Cell) return (Cell) attachment;
            if (attachment instanceof Slot) return ((Slot) attachment).cell;
            value = raw(ref);
        }
        return null;
    }
    private static CaptureResource resource(Memory value){
        Session session=CURRENT.get();if(session==null||session.capturedObjects==null)return null;
        for(int depth=0;depth<4&&value instanceof ReferenceMemory;depth++){
            var carrier=(ReferenceMemory)value;var attachment=carrier.getPrimitiveCellAttachment();
            if(attachment instanceof ObjectSlot slot)return slot.resource;
            value=raw(carrier);
        }
        return value instanceof ObjectMemory object&&object.value instanceof Closure closure?session.capturedObjects.get(closure):null;
    }
    public static void beforeObjectConstruction(php.runtime.lang.IObject object){
        if(object instanceof Closure closure)closure.invalidatePrimitiveCaptures();
    }
    public static void closureOpaque(Closure closure){
        if(closure!=null)closure.invalidatePrimitiveCaptures();
    }
    public static void closureOpaque(Closure closure,Memory[] actualUses){
        if(actualUses==null||actualUses.length==0)return;
        if(PUBLICATION.get()!=null)return;
        // Uses are the Closure's real physical array. No current-thread map or new ownership claim.
        for(Memory value:actualUses)invalidateAttachedCell(value);
        Session session=CURRENT.get();if(session==null||session.capturedObjects==null)return;
        var resource=session.capturedObjects.get(closure);if(resource!=null)resource.escape();
    }
    private static void detachObjectSlot(ReferenceMemory carrier,ObjectSlot slot){
        slot.resource.escape();try(Permit ignored=new Permit()){if(carrier.getPrimitiveCellAttachment()==slot)carrier.clearPrimitiveCell(slot);}
        if(slot.live)slot.retire();if(slot.scope.objects!=null)slot.scope.objects.remove(carrier);
    }
    private static boolean primitive(Memory value) {
        return value != null && value.getClass() == LongMemory.class;
    }
    private static PhpPropertyWriteContext.Frame context() { return PhpPropertyWriteContext.current(null); }
    private static PhpPrimitiveCellPlan.Member member(Class<?> owner, String name, String descriptor, int site) {
        return PhpPrimitiveCellPlan.requireSite(owner, name, descriptor, site);
    }
    private static Scope currentScope(PhpPrimitiveCellPlan.Member member) {
        Session session = CURRENT.get(); PhpPropertyWriteContext.Frame frame = context();
        if (session == null || frame == null || session.environment != frame.environment) return null;
        return session.scope(frame, member);
    }

    /** All opaque holders are invalidated before their constructor/store publishes a CELL. */
    public static void beforeUntrackedConstruction(Memory value) {
        if(CURRENT.get()==null){invalidateForeignIngress(value);return;}
        if (PUBLICATION.get() != null) return;
        var resource=resource(value);if(resource!=null)resource.escape();
        Cell cell = cell(value); if (cell != null) cell.escape();
    }
    public static void beforeRawStore(ReferenceMemory carrier, Memory next) {
        if(CURRENT.get()==null){invalidateForeignIngress(carrier);invalidateForeignIngress(next);return;}
        Attachment attachment = carrier.getPrimitiveCellAttachment();
        if (PUBLICATION.get() != null) return;
        if (attachment instanceof ObjectSlot slot)slot.resource.escape();
        var resource=resource(next);if(resource!=null)resource.escape();
        if (attachment instanceof Slot) ((Slot) attachment).cell.escape();
        if (attachment instanceof Cell && !primitive(next)) ((Cell) attachment).escape();
        Cell incoming = cell(next); if (incoming != null) incoming.escape();
    }
    public static void beforeExport(ReferenceMemory carrier) {
        if(CURRENT.get()!=null&&PUBLICATION.get()==null&&carrier.getPrimitiveCellAttachment() instanceof ObjectSlot slot)detachObjectSlot(carrier,slot);
        if(CURRENT.get()==null){invalidateForeignIngress(carrier);return;}
        if (PUBLICATION.get() != null) return;
        var resource=resource(carrier);if(resource!=null)resource.escape();
        Cell cell = cell(carrier); if (cell != null) cell.escape();
    }
    public static boolean isCellAttachment(Attachment attachment) { return attachment instanceof Cell; }
    public static boolean isUnknownStatic(Object tag) { return tag == UNKNOWN_STATIC; }
    public static Object unknownStaticTag() { return UNKNOWN_STATIC; }
    public static void staticExposed(ReferenceMemory slot) {
        if (slot == null || PUBLICATION.get() != null) return;
        Cell cell = cell(slot);
        if (cell != null) cell.escape();
        else slot.markPrimitiveStaticUnknown(UNKNOWN_STATIC);
    }
    /** Modern echo may invoke Stringable; only an exact ordinary integer read keeps the active primitive certificate. */
    public static void beforeEcho(Memory value,Environment environment){
        Session session=CURRENT.get();if(session==null)return;
        if(session.environment!=environment){session.epoch++;session.incomplete=true;return;}
        Memory actual=value;
        for(int depth=0;depth<4&&actual instanceof ReferenceMemory;depth++){
            if(actual.getClass()!=ReferenceMemory.class&&actual.getClass()!=ArrayMapEntryMemory.class){opaque(environment);return;}
            actual=raw((ReferenceMemory)actual);
        }
        if(actual==null||actual.getClass()!=LongMemory.class)opaque(environment);
    }
    public static void opaque(Environment environment) {
        Session session = CURRENT.get(); if (session != null && session.environment == environment) { session.epoch++;session.incomplete=true; }
    }
    public static void leaving(PhpPropertyWriteContext.Frame frame) {
        Session session = CURRENT.get(); if (session == null || session.environment != frame.environment) return;
        for (Iterator<ReturnLease> iterator=session.returns.iterator();iterator.hasNext();) {
            ReturnLease lease=iterator.next();if (lease.live && lease.receiver==frame) {lease.retire();iterator.remove();}
        }
        Scope scope = session.scopes.remove(frame);
        if (scope != null) {
            try (Permit ignored = new Permit()) { for (Slot slot : scope.slots.values()) {
                if (slot.live) slot.retire();
                if (((Attachment)slot).carrier.getPrimitiveCellAttachment()==slot) ((Attachment)slot).carrier.clearPrimitiveCell(slot);
            } }
            scope.slots.clear();
            if(scope.objects!=null){try(Permit ignored=new Permit()){for(var slot:scope.objects.values()){
                if(slot.live)slot.retire();if(((Attachment)slot).carrier.getPrimitiveCellAttachment()==slot)((Attachment)slot).carrier.clearPrimitiveCell(slot);
            }}scope.objects.clear();}
        }
        if (frame == session.root) {
            session.closed = true; session.epoch++;
            session.scopes.clear(); session.returns.clear();if(session.sends!=null){for(var leases:session.sends.values())for(var lease:leases)if(lease.live)lease.retire();session.sends.clear();}retireCaptureEvaluations(session.pendingCaptures);
            retireCaptureEvaluations(session.capturedObjects);
            session.environment=null;session.root=null; CURRENT.remove();
        }
    }

    private static void retireCaptureEvaluations(Map<?,CaptureResource> resources){
        if(resources==null)return;
        for(var iterator=resources.values().iterator();iterator.hasNext();){
            var resource=iterator.next();iterator.remove();
            if(resource.evaluation){resource.evaluation=false;resource.retire();}
        }
    }

    public static boolean active(Environment env) { Session session=CURRENT.get();return session!=null&&session.environment==env&&!session.closed; }

    /** Actual debug local-table buckets are issued by the compiled address producer, never by matching a key alone. */
    public static Memory localAddress(Memory table,String key,PhpRuntimeOwnership.Frame heavy,Class<?> owner,String name,String descriptor,int site){
        if(heavy!=null||CURRENT.get()==null)return PhpRuntimeOwnership.localAddress(table,key,heavy);
        var plan=member(owner,name,descriptor,site);Scope scope=currentScope(plan);
        Memory result=PhpRuntimeOwnership.localAddress(table,key,null);
        if(scope==null||!plan.closed()||table.getClass()!=ArrayMemory.class||!(result instanceof ReferenceMemory carrier)
                ||((ArrayMemory)table).getByScalar(key)!=result){opaque(scope==null?null:scope.frame.environment);return result;}
        if(scope.addresses==null)scope.addresses=new IdentityHashMap<>();
        LocalAddress previous=scope.addresses.putIfAbsent(carrier,new LocalAddress((ArrayMemory)table,key));
        if(previous!=null&&(previous.table()!=table||!previous.name().equals(key)))opaque(scope.frame.environment);
        return result;
    }
    public static boolean actualLocal(PhpPrimitiveCellPlan.Member plan,ReferenceMemory actual,String key,Environment environment){
        Session session=CURRENT.get();PhpPropertyWriteContext.Frame frame=context();
        if(session==null||frame==null||session.environment!=environment||frame.environment!=environment)return false;
        Scope scope=session.scopes.get(frame);if(scope==null||scope.member!=plan||scope.addresses==null)return false;
        LocalAddress issued=scope.addresses.get(actual);
        return issued!=null&&issued.name().equals(key)&&issued.table().getByScalar(key)==actual;
    }
    public static Memory getStatic(Closure closure, String key, Class<?> owner, String name, String descriptor, int site) {
        var plan=member(owner,name,descriptor,site);
        if(!plan.closed())return closure.getStatic(key);
        return closure.readPrimitiveStatic(READ,key,null,false);
    }
    public static Memory getOrCreateStatic(Closure closure, String key, Memory initial, Class<?> owner, String name, String descriptor, int site) {
        var plan=member(owner,name,descriptor,site);
        if(!plan.closed())return closure.getOrCreateStatic(key,initial);
        return closure.readPrimitiveStatic(READ,key,initial,true);
    }
    public static Memory borrowStatic(Memory value, Class<?> owner, String name, String descriptor, int site) {
        Cell cell = cell(value);
        if (cell == null) return ReferenceMemory.valueOf(value);
        var plan = member(owner,name,descriptor,site);
        Scope scope = currentScope(plan);
        if (scope == null) { cell.escape(); return ReferenceMemory.valueOf(value); }
        try (Permit ignored = new Permit()) {
            ReferenceMemory borrowed = new ReferenceMemory(value);
            Slot token=new Slot(borrowed,cell,scope,true,false);token.borrowedStatic=reference(value);
            if(token.borrowedStatic==null || !(token.borrowedStatic.getPrimitiveCellAttachment() instanceof Slot)
                    || !((Slot)token.borrowedStatic.getPrimitiveCellAttachment()).table)cell.escape();
            borrowed.attachPrimitiveCell(token); return borrowed;
        }
    }

    public static Memory export(Memory value,Class<?> owner,String name,String descriptor,int site) {
        Cell cell=cell(value);if(cell==null)return ReferenceMemory.exportReference(value);
        var plan=member(owner,name,descriptor,site);Scope scope=currentScope(plan);ReferenceMemory source=reference(value);
        if(scope==null||!plan.closed()){cell.escape();return ReferenceMemory.exportReference(value);}
        ReturnLease lease=null;for(ReturnLease pending:cell.session.returns)if(pending.live&&pending.cell==cell&&pending.receiver==scope.frame){if(lease!=null){cell.escape();break;}lease=pending;}
        if(lease==null){
            if(source==null||!(source.getPrimitiveCellAttachment() instanceof Slot)
                    ||!((Slot)source.getPrimitiveCellAttachment()).live||!plan.isActualLocal(site,source,scope.frame.environment))cell.escape();
            else{lease=new ReturnLease(cell,scope.frame,plan);lease.evaluation=true;cell.session.returns.addLast(lease);}
        }
        try(Permit ignored=new Permit()){return ReferenceMemory.exportReference(value);}
    }
    public static void discard(Object value,Class<?> owner,String name,String descriptor,int site) {
        if(!(value instanceof Memory))return;
        var resource=resource((Memory)value);if(resource!=null){var plan=member(owner,name,descriptor,site);var scope=currentScope(plan);
            if(scope==null||!plan.closed()){resource.escape();return;}if(resource.evaluation){resource.evaluation=false;resource.retire();}return;}
        Cell cell=cell((Memory)value);if(cell==null)return;
        var plan=member(owner,name,descriptor,site);Scope scope=currentScope(plan);if(scope==null||!plan.closed()){cell.escape();return;}
        ReturnLease lease=null;for(ReturnLease pending:cell.session.returns)if(pending.live&&pending.cell==cell&&pending.receiver==scope.frame){if(lease!=null){cell.escape();return;}lease=pending;}
        if(lease!=null){lease.retire();cell.session.returns.remove(lease);}
    }

    public static Memory bindStatic(PhpRuntimeOwnership.Frame heavy,int local,String key,Memory cv,Memory slot,
            Class<?> owner,String name,String descriptor,int site) {
        if(heavy!=null)return PhpRuntimeOwnership.bindStaticLocal(heavy,local,key,cv,slot);
        Cell cell=cell(slot);if(cell==null)return Memory.assignRefRight(slot,cv);
        var plan=member(owner,name,descriptor,site);Scope scope=currentScope(plan);ReferenceMemory address=reference(cv),table=reference(slot);
        if(scope==null||address==null||table==null||!(table.getPrimitiveCellAttachment() instanceof Slot)
                ||!((Slot)table.getPrimitiveCellAttachment()).table){cell.escape();return Memory.assignRefRight(slot,cv);}
        Attachment old=address.getPrimitiveCellAttachment();Slot borrowed=new Slot(address,cell,scope,true,false);borrowed.borrowedStatic=table;
        Memory result;try(Permit ignored=new Permit()){result=Memory.assignRefRight(slot,cv);address.attachPrimitiveCell(borrowed);}
        if(old instanceof Slot&&((Slot)old).live)((Slot)old).retire();return result;
    }

    /** RETURN owns the canonical CELL before callee cleanup; caller publication consumes this lease once. */
    public static Memory prepareReturn(PhpRuntimeOwnership.Frame heavy, Memory raw, Environment env, TraceInfo trace,
            Closure closure, Class<?> owner, String name, String descriptor, int site) {
        if (heavy != null) return PhpRuntimeOwnership.prepareReturnReference(heavy,raw,env,trace);
        var plan = member(owner,name,descriptor,site);
        PhpPropertyWriteContext.Frame frame = context();
        if (frame == null || frame.environment != env || closure == null || !plan.closed() || !plan.returnReference()
                || !PhpPrimitiveCellPlan.closedInvocationPath(closure)
                || closure.getClass() != owner || !closure.hasPhp8Binding() || closure.getReflectedMethod() != null
                || closure.getReflection().findMethod("__invoke")==null
                || !closure.getReflection().findMethod("__invoke").isReturnReference()
                || closure.getReflection().findMethod("__invoke").getReturnTypeChecker() != null
                || closure.getReflection().findMethod("__invoke").getNativeMethod()==null
                || !closure.getReflection().findMethod("__invoke").getNativeMethod().getDeclaringClass().equals(owner)
                || !closure.getReflection().findMethod("__invoke").getNativeMethod().getName().equals(name)
                || !PhpOwnershipExecution.descriptor(closure.getReflection().findMethod("__invoke").getNativeMethod()).equals(descriptor)
                || closure.getUses().length!=0 || !closure.getSelf().isNull()
                || env.getErrorHandler()!=null || env.scope.getTickHandler()!=null
                || env.getExceptionHandler()!=php.runtime.env.handler.ExceptionHandler.DEFAULT
                || env.getOutputBuffers().stream().anyMatch(buffer->buffer.getCallback()!=null&&!buffer.getCallback().isNull())) {
            opaque(env); return php.runtime.invoke.InvokeHelper.prepareReturnReference(raw,env,trace);
        }
        ReferenceMemory cv = reference(raw), actualSlot = null; String actualKey = null;
        if (cv != null) for (String key : plan.staticNames()) {
            Memory slot;
            slot = closure.readPrimitiveStatic(READ,key,null,false);
            if (slot instanceof ReferenceMemory && (cv == slot || raw(cv) == slot
                    || cv.getPrimitiveCellAttachment() instanceof Slot && ((Slot)cv.getPrimitiveCellAttachment()).borrowed
                        && ((Slot)cv.getPrimitiveCellAttachment()).borrowedStatic==slot && raw(cv)==raw((ReferenceMemory)slot))) {
                if (actualSlot != null) { opaque(env); return php.runtime.invoke.InvokeHelper.prepareReturnReference(raw,env,trace); }
                actualSlot = (ReferenceMemory) slot; actualKey = key;
            }
        }
        Cell cell = cell(raw);
        if (cell == null && (actualSlot == null || actualSlot.hasPrimitiveStaticUnknown()
                || actualSlot.isCanonicalReferenceCell() || !primitive(raw(actualSlot)))) {
            opaque(env); return php.runtime.invoke.InvokeHelper.prepareReturnReference(raw,env,trace);
        }
        Session session = CURRENT.get();
        if (session == null) {
            PhpPropertyWriteContext.Frame root = frame;
            while (root.previousFrame() != null && root.previousFrame().environment == env) root = root.previousFrame();
            session = new Session(env,root); CURRENT.set(session);
        }
        if (session.environment != env || session.closed) {
            if (cell != null) cell.escape(); return php.runtime.invoke.InvokeHelper.prepareReturnReference(raw,env,trace);
        }
        Scope scope = session.scope(frame,plan);
        ReferenceMemory canonical;
        try (Permit ignored = new Permit()) {
            if (cell == null) {
                canonical = actualSlot.getReferenceForBinding();
                cell = new Cell(canonical,session,closure,actualKey,actualSlot);
                canonical.attachPrimitiveCell(cell);
                actualSlot.attachPrimitiveCell(new Slot(actualSlot,cell,null,false,true));
            } else {
                canonical = (ReferenceMemory) ((Attachment)cell).carrier;
                if (cell.session != session || !cell.usable()) cell.escape();
            }
            // A structural CV wrapper of this exact table address is a borrow, never another owner.
            if (cv != null && cv != actualSlot && actualSlot != null && raw(cv) == actualSlot)
                { Slot borrowed=new Slot(cv,cell,scope,true,false);borrowed.borrowedStatic=actualSlot;cv.attachPrimitiveCell(borrowed); }
        }
        ReturnLease transferred=null;
        for(ReturnLease pending:session.returns)if(pending.live&&pending.cell==cell&&pending.receiver==frame) {
            if(transferred!=null){cell.escape();break;}transferred=pending;
        }
        session.returns.addLast(new ReturnLease(cell,frame.previousFrame(),plan));
        if(transferred!=null){transferred.retire();session.returns.remove(transferred);}
        return canonical;
    }

    public static Memory assignRef(Memory value, Memory destination, Class<?> owner, String name, String descriptor, int site) throws Throwable {
        var resource=resource(value);if(resource!=null)resource.escape();
        if(destination instanceof ReferenceMemory object&&object.getPrimitiveCellAttachment() instanceof ObjectSlot slot)detachObjectSlot(object,slot);
        Cell incoming=cell(value);
        Attachment present=destination instanceof ReferenceMemory ? ((ReferenceMemory)destination).getPrimitiveCellAttachment():null;
        if(incoming==null && present==null)return Memory.assignRefRight(value,destination);
        var plan = member(owner,name,descriptor,site);
        if (incoming == null) {
            Attachment old = destination instanceof ReferenceMemory ? ((ReferenceMemory)destination).getPrimitiveCellAttachment() : null;
            Scope scope = currentScope(plan);
            if (old instanceof Slot && scope != null && plan.closed()
                    && plan.isActualLocal(site,(ReferenceMemory)destination,scope.frame.environment) && !((Slot)old).table) {
                Memory result;
                try (Permit ignored = new Permit()) {
                    result = Memory.assignRefRight(value,destination);
                    ((ReferenceMemory)destination).clearPrimitiveCell(old);
                }
                if (((Slot)old).live) ((Slot)old).retire(); scope.slots.remove(destination); return result;
            }
            return Memory.assignRefRight(value,destination);
        }
        Scope scope = currentScope(plan); ReferenceMemory carrier = reference(destination);
        if (scope == null || !plan.closed() || carrier == null || !plan.isActualLocal(site,carrier,scope.frame.environment)) {
            incoming.escape(); return Memory.assignRefRight(value,destination);
        }
        Attachment old = carrier.getPrimitiveCellAttachment();
        if (old instanceof Slot && ((Slot) old).table) {
            incoming.escape(); return Memory.assignRefRight(value,destination);
        }
        ReturnLease lease = null;
        for (ReturnLease candidate : incoming.session.returns) if (candidate.live && candidate.cell == incoming
                && candidate.receiver == scope.frame) { if (lease != null) { incoming.escape(); break; } lease = candidate; }
        Slot retained = old instanceof Slot ? (Slot) old : null;
        boolean same = retained != null && retained.live && !retained.borrowed && retained.cell == incoming;
        Slot next = same ? retained : new Slot(carrier,incoming,scope,false,false);
        if (!same) incoming.owners++;
        Memory result;
        try (Permit ignored = new Permit()) {
            result = Memory.assignRefRight(value,destination);
            carrier.attachPrimitiveCell(next);
        } catch (Throwable failure) { if (!same) incoming.owners--; throw failure; }
        if (!same && retained != null && retained.live) retained.retire();
        scope.slots.put(carrier,next);
        if (lease != null) { lease.retire(); incoming.session.returns.remove(lease); }
        else if (!same && !(reference(value) != null && reference(value).getPrimitiveCellAttachment() instanceof Slot)) incoming.escape();
        return result;
    }
    /** Exact generated SEND admission precedes canonical export and both packet array publications. */
    public static void sendCompiler(php.runtime.invoke.PhpCallFrame call,Memory value,int kind,PhpOwnershipExecution.Entry entry,int eval,
            Class<?> owner,String name,String descriptor,int site)throws Throwable {
        Cell incoming=cell(value);
        if(incoming==null){var resource=resource(value);if(resource!=null)resource.escape();call.sendCompiler(value,kind,entry,eval);return;}
        var plan=member(owner,name,descriptor,site);Scope scope=currentScope(plan);ReferenceMemory carrier=reference(value);
        var target=call.primitiveFunctionMember();
        Slot source=carrier!=null&&carrier.getPrimitiveCellAttachment() instanceof Slot?(Slot)carrier.getPrimitiveCellAttachment():null;
        if(entry!=null||!plan.closed()||scope==null||target==null||kind!=php.runtime.invoke.PhpCallFrame.VARIABLE
                ||source==null||!source.live||source.borrowed||source.scope!=scope||scope.slots.get(carrier)!=source
                ||!plan.isActualLocal(site,carrier,scope.frame.environment)||!call.primitivePositionalSend()) {
            incoming.escape();opaque(scope==null?incoming.session.environment:scope.frame.environment);call.sendCompiler(value,kind,entry,eval);return;
        }
        var lease=new SendLease(incoming,target,scope.frame,call.primitiveSendIndex());
        Session session=incoming.session;if(session.sends==null)session.sends=new IdentityHashMap<>();
        session.sends.computeIfAbsent(call,ignored->new ArrayList<>()).add(lease);
        call.sendCompiler(value,kind,entry,eval);
    }
    /** Original SEND export only receives a permit for that already published exact packet row. */
    public static Memory exportSend(php.runtime.invoke.PhpCallFrame call,Memory value){
        Cell incoming=cell(value);Session session=CURRENT.get();
        if(incoming!=null&&session!=null&&session.sends!=null){var leases=session.sends.get(call);
            if(leases!=null)for(var lease:leases)if(lease.live&&lease.cell==incoming&&lease.index==call.primitiveSendIndex()
                    &&lease.target==call.primitiveFunctionMember()&&lease.receiver==context())
                try(Permit ignored=new Permit()){return ReferenceMemory.exportReference(value);}
        }
        return ReferenceMemory.exportReference(value);
    }
    public static Memory exportArgument(Memory value,php.runtime.reflection.ParameterEntity parameter,int index){
        Cell incoming=cell(value);Session session=CURRENT.get();var call=php.runtime.invoke.PhpCallFrame.primitiveInvocation();
        if(incoming!=null&&session!=null&&session.sends!=null&&call!=null&&call.primitiveParameter(index)==parameter){
            var leases=session.sends.get(call);if(leases!=null)for(var lease:leases)
                if(lease.live&&lease.cell==incoming&&lease.index==index&&lease.canonical==value&&lease.target==call.primitiveFunctionMember())
                    try(Permit ignored=new Permit()){return ReferenceMemory.exportReference(value);}
        }
        return ReferenceMemory.exportReference(value);
    }
    public static void callClosed(php.runtime.invoke.PhpCallFrame call){
        Session session=CURRENT.get();if(session==null||session.sends==null)return;
        var leases=session.sends.remove(call);if(leases!=null)for(var lease:leases)if(lease.live)lease.retire();
    }
    /** The actual incoming SEND row retains the CV before the callee can release its packet. */
    public static Memory importArgument(Memory value,Memory destination,int index,int role,Class<?> owner,String name,String descriptor,int site){
        Cell incoming=cell(value);if(incoming==null)return Memory.assignRight(value,destination);
        var plan=member(owner,name,descriptor,site);Scope scope=currentScope(plan);ReferenceMemory carrier=reference(destination);
        Session session=CURRENT.get();var call=php.runtime.invoke.PhpCallFrame.primitiveInvocation();SendLease lease=null;
        if(session!=null&&session.sends!=null&&call!=null){var leases=session.sends.get(call);if(leases!=null)for(var candidate:leases)
            if(candidate.live&&candidate.index==index&&candidate.cell==incoming&&candidate.canonical==value&&candidate.target==plan)lease=candidate;}
        if(scope==null||!plan.closed()||carrier==null||role!=1||!plan.argument(site,index,role)||lease==null
                ||lease.receiver!=scope.frame.previousFrame()||!plan.isActualLocal(site,carrier,scope.frame.environment)){
            incoming.escape();return Memory.assignRight(value,destination);
        }
        Attachment old=carrier.getPrimitiveCellAttachment();if(old!=null){incoming.escape();return Memory.assignRight(value,destination);}
        Slot next=new Slot(carrier,incoming,scope,false,false);incoming.owners++;
        Memory result;try(Permit ignored=new Permit()){result=Memory.assignRight(value,destination);carrier.attachPrimitiveCell(next);}
        scope.slots.put(carrier,next);return result;
    }
    private static Memory assignObject(Memory value,Memory destination,CaptureResource resource,ObjectSlot previous,
            Class<?> owner,String name,String descriptor,int site){
        var plan=member(owner,name,descriptor,site);Scope scope=currentScope(plan);ReferenceMemory carrier=reference(destination);
        if(scope==null||!plan.closed()||carrier==null||!plan.isActualLocal(site,carrier,scope.frame.environment)
                ||previous!=null&&(!previous.live||previous.scope!=scope)||resource!=null&&(resource.released||resource.object==null)){
            if(resource!=null)resource.escape();if(previous!=null)detachObjectSlot(carrier,previous);return Memory.assignRight(value,destination);
        }
        if(carrier.getPrimitiveCellAttachment()!=null&&previous==null){if(resource!=null)resource.escape();return Memory.assignRight(value,destination);}
        ObjectSlot next=resource==null?null:new ObjectSlot(carrier,scope,resource);
        Memory result;try(Permit ignored=new Permit()){
            if(previous!=null)carrier.clearPrimitiveCell(previous);
            result=Memory.assignRight(value,destination);if(next!=null)carrier.attachPrimitiveCell(next);
        }
        if(previous!=null&&previous.live)previous.retire();
        if(next!=null){if(scope.objects==null)scope.objects=new IdentityHashMap<>();scope.objects.put(carrier,next);
            if(resource.evaluation){resource.evaluation=false;resource.retire();}}
        else if(scope.objects!=null)scope.objects.remove(carrier);
        return result;
    }
    /** Each exact physical capture array slot retains its CELL before publication. */
    public static void capture(Memory[] uses,int index,Memory value,Class<?> type,int role,Class<?> owner,String name,String descriptor,int site){
        Session session=CURRENT.get();if(session==null){uses[index]=value;return;}
        var nested=resource(value);if(nested!=null)nested.escape();Cell cell=cell(value);
        if(cell==null){uses[index]=value;return;}
        var plan=member(owner,name,descriptor,site);Scope scope=currentScope(plan);
        ReturnLease lease=null;for(var pending:session.returns)if(pending.live&&pending.cell==cell&&pending.receiver==context()&&pending.producer==plan&&pending.evaluation){if(lease!=null){cell.escape();break;}lease=pending;}
        if(scope==null||!plan.closed()||role!=1||!plan.capture(site,type,index,role,uses.length)||lease==null||uses[index]!=null){cell.escape();uses[index]=value;return;}
        if(session.pendingCaptures==null)session.pendingCaptures=new IdentityHashMap<>();
        var resource=session.pendingCaptures.get(uses);if(resource==null){resource=new CaptureResource(session,plan,type,uses);session.pendingCaptures.put(uses,resource);}
        if(resource.type!=type||resource.producer!=plan||resource.cells[index]!=null){resource.escape();cell.escape();uses[index]=value;return;}
        resource.cells[index]=cell;cell.owners++;uses[index]=value;lease.retire();session.returns.remove(lease);
    }
    public static void closureConstructed(Closure object,Memory[] uses){
        if(uses==null||uses.length==0)return; // A capture publisher requires an actual use index.
        Session session=CURRENT.get();if(session==null||session.pendingCaptures==null)return;
        var resource=session.pendingCaptures.remove(uses);if(resource==null)return;
        if(resource.type!=object.getClass()||object.getReflection().getClass()!=php.runtime.reflection.helper.ClosureEntity.class){resource.escape();return;}
        var metadata=((php.runtime.reflection.helper.ClosureEntity)object.getReflection()).uses;
        if(metadata==null||metadata.length!=uses.length){resource.escape();return;}
        for(int index=0;index<uses.length;index++)if(resource.cells[index]!=null&&(!metadata[index].isReference()||uses[index]!=((Attachment)resource.cells[index]).carrier))resource.escape();
        resource.object=object;if(session.capturedObjects==null)session.capturedObjects=new IdentityHashMap<>();session.capturedObjects.put(object,resource);
    }
    public static Memory wrapClosure(php.runtime.lang.IObject object,Class<?> owner,String name,String descriptor,int site){
        Session session=CURRENT.get();if(session==null)return ObjectMemory.valueOf(object);
        var plan=member(owner,name,descriptor,site);
        var resource=session.capturedObjects==null||!(object instanceof Closure closure)?null:session.capturedObjects.get(closure);
        if(resource==null)return ObjectMemory.valueOf(object);
        if(!plan.closed()||resource.producer!=plan||!plan.factory(site,object.getClass())||resource.released||!resource.evaluation){resource.escape();return ObjectMemory.valueOf(object);}
        try(Permit ignored=new Permit()){return ObjectMemory.valueOf(object);}
    }
    public static Memory assignValue(Memory value, Memory destination, Class<?> owner, String name, String descriptor, int site) {
        var resource=resource(value);
        ObjectSlot previous=destination instanceof ReferenceMemory carrier&&carrier.getPrimitiveCellAttachment() instanceof ObjectSlot slot?slot:null;
        if(resource!=null||previous!=null)return assignObject(value,destination,resource,previous,owner,name,descriptor,site);
        Cell incoming = cell(value);
        Attachment attachment = destination instanceof ReferenceMemory ? ((ReferenceMemory)destination).getPrimitiveCellAttachment() : null;
        if(incoming==null && attachment==null)return Memory.assignRight(value,destination);
        var plan=member(owner,name,descriptor,site);
        Scope scope=currentScope(plan);
        if(!plan.closed()||scope==null||!(destination instanceof ReferenceMemory)
                ||!plan.isActualLocal(site,(ReferenceMemory)destination,scope.frame.environment)) {
            if(incoming!=null)incoming.escape();if(attachment instanceof Slot)((Slot)attachment).cell.escape();
            return Memory.assignRight(value,destination);
        }
        // Assigning through a CELL preserves the destination's alias; VALUE reads do not add CELL owners.
        if (attachment instanceof Slot && !((Slot)attachment).borrowed && ((Slot)attachment).live) {
            if(!primitive(value.toValue()))((Slot)attachment).cell.escape();
            try (Permit ignored = new Permit()) { return Memory.assignRight(value,destination); }
        }
        if (incoming != null && !plan.closed()) incoming.escape();
        return Memory.assignRight(value,destination);
    }
    public static void unset(PhpRuntimeOwnership.Frame heavy, int local, Memory actual, Environment env,
            Class<?> owner, String name, String descriptor, int site) {
        if (heavy != null) { PhpRuntimeOwnership.unsetLocal(heavy,local,actual,env); return; }
        if(actual instanceof ReferenceMemory carrier&&carrier.getPrimitiveCellAttachment() instanceof ObjectSlot slot){
            var plan=member(owner,name,descriptor,site);Scope scope=currentScope(plan);
            if(scope!=slot.scope||!plan.closed()||!plan.isActualLocal(site,carrier,env)){slot.resource.escape();actual.manualUnset(env);return;}
            try(Permit ignored=new Permit()){actual.manualUnset(env);carrier.clearPrimitiveCell(slot);}if(slot.live)slot.retire();scope.objects.remove(carrier);return;
        }
        Slot old = actual instanceof ReferenceMemory && ((ReferenceMemory)actual).getPrimitiveCellAttachment() instanceof Slot
                ? (Slot)((ReferenceMemory)actual).getPrimitiveCellAttachment() : null;
        if(old==null){
            // An escaped object reference still has PHP aliases. Unset this actual CV address,
            // never recursively unset the terminal canonical object CELL through manualUnset.
            if(actual instanceof ReferenceMemory carrier
                    && (carrier.getClass()==ReferenceMemory.class||carrier.getClass()==ArrayMapEntryMemory.class)
                    && raw(carrier) instanceof ReferenceMemory canonical&&canonical.isCanonicalReferenceCell()) {
                var plan=member(owner,name,descriptor,site);Scope scope=currentScope(plan);
                if(scope!=null&&plan.closed()&&plan.isActualLocal(site,carrier,env)) {carrier.unset();return;}
            }
            PhpRuntimeOwnership.unsetLocal(null,local,actual,env);return;
        }
        var plan=member(owner,name,descriptor,site);Scope scope=currentScope(plan);
        if (scope == null || !plan.closed() || !plan.isActualLocal(site,(ReferenceMemory)actual,env)) {
            if (old != null) old.cell.escape(); actual.manualUnset(env); return;
        }
        try (Permit ignored = new Permit()) { actual.manualUnset(env); ((ReferenceMemory)actual).clearPrimitiveCell(old); }
        if (old.live) old.retire(); scope.slots.remove(actual);
    }

    public static Memory cloneLocal(PhpRuntimeOwnership.Frame heavy,int result,int local,Memory value,Environment env,TraceInfo trace,
            Class<?> owner,String name,String descriptor,int site) throws Throwable {
        if (heavy != null) return PhpOwnershipClosures.cloneLocalRuntime(heavy,result,local,value,env,trace);
        if(!active(env))return value.clone(env,trace);
        return cloneValue(value,env,trace,member(owner,name,descriptor,site));
    }
    public static Memory cloneEval(PhpRuntimeOwnership.Frame heavy,int result,Memory value,Environment env,TraceInfo trace,
            Class<?> owner,String name,String descriptor,int site) throws Throwable {
        if (heavy != null) return PhpOwnershipClosures.cloneRuntime(heavy,result,value,env,trace);
        if(!active(env))return value.clone(env,trace);
        return cloneValue(value,env,trace,member(owner,name,descriptor,site));
    }
    private static Memory cloneValue(Memory value,Environment env,TraceInfo trace,PhpPrimitiveCellPlan.Member plan) throws Throwable {
        var resource=resource(value);if(resource!=null)resource.escape();
        Session session=CURRENT.get();
        if (session==null || session.environment!=env || !plan.closed()) { opaque(env);return value.clone(env,trace); }
        currentScope(plan);
        try(Permit ignored=new Permit()){return value.clone(env,trace);}
    }
    /** Clone consults the exact original table slot. No global canonical-bit demotion occurs. */
    public static Memory cloneStaticValue(Closure source,String key,ReferenceMemory slot,Memory original) {
        Cell cell=cell(slot);Permit permit=PUBLICATION.get();Session session=CURRENT.get();
        if(cell==null || permit==null || session==null || cell.session!=session || !cell.usable()
                || cell.closure.get()!=source || cell.staticSlot!=slot || !cell.key.equals(key))return original;
        Scope root=session.scopes.get(session.root);
        if(root==null || root.member==null || !root.member.closed())return original;
        if(!(slot.getPrimitiveCellAttachment() instanceof Slot) || !((Slot)slot.getPrimitiveCellAttachment()).table
                || ((Slot)slot.getPrimitiveCellAttachment()).cell!=cell)return original;
        if(cell.owners==1 && raw(slot)==((Attachment)cell).carrier && primitive(raw((ReferenceMemory)((Attachment)cell).carrier)))
            return raw((ReferenceMemory)((Attachment)cell).carrier).toImmutable();
        return original;
    }
    public static void clonedStatic(Closure copy,String key,ReferenceMemory carrier,Memory value) {
        Cell cell=cell(value);if(cell==null)return;
        if(PUBLICATION.get()==null){cell.escape();return;}
        cell.owners++;carrier.attachPrimitiveCell(new Slot(carrier,cell,null,false,true));
        // The clone owns another actual table slot; its later reference-return needs its own table proof.
    }
}
