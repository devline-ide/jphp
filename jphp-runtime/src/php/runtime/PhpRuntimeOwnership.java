package php.runtime;

import php.runtime.env.Environment;
import php.runtime.memory.ArrayMemory;
import php.runtime.memory.ObjectMemory;
import php.runtime.memory.ReferenceMemory;
import php.runtime.lang.support.IPhpOwnershipObject;
import java.util.*;
import java.io.*;

/** Actual ordinary holder publication in the existing heap. Admission remains explicit;
 * no Java wrapper, reference flag or reachability scan manufactures an owner. */
public final class PhpRuntimeOwnership {
    public enum Role { VALUE, CELL, BORROW_ADDRESS }
    public record Producer(int id,String kind,Set<String> consumers) {
        public Producer {
            if(id<4_000_000||!Set.of("ARRAY_LITERAL","CLOSURE_LITERAL","CLONE","NATIVE_RESULT","CALL_RESULT","GLOBAL_RESULT","SCALAR_LITERAL","ARRAY_REF_RESULT","FUNCTION_RESULT","ARRAY_INCREMENT_RESULT","OBJECT_NEW","PROPERTY_REF_RESULT","JOIN_RESULT","CV_READ_RESULT","NULL_LITERAL","ENTRY_SCALAR_LITERAL","ENTRY_STRING_LITERAL","OBJECT_CAST_RESULT","LOCAL_INCREMENT_RESULT").contains(kind))
                throw new IllegalArgumentException("Unknown runtime producer identity");
            consumers=Set.copyOf(consumers);
            if(consumers.isEmpty()||!Set.of("CV","GLOBAL","ENTRY","POP","RETURN","SEND","JOIN","CAST").containsAll(consumers))
                throw new IllegalArgumentException("Incomplete runtime producer consumer protocol: "+id+":"+kind+":"+consumers);
        }
    }
    public record NativeMember(int id,String owner,String name,String descriptor,int argc,boolean hasBorrow,boolean framelessBorrow,Map<Integer,Boolean> wrappedArguments,Map<Integer,Integer> directCvArguments,String physicalOwner,String physicalName,String physicalDescriptor,boolean discardedResult) {
        public NativeMember(int id,String owner,String name,String descriptor,int argc,boolean hasBorrow,boolean framelessBorrow,Map<Integer,Boolean> wrappedArguments,Map<Integer,Integer> directCvArguments) {
            this(id,owner,name,descriptor,argc,hasBorrow,framelessBorrow,wrappedArguments,directCvArguments,owner,name,descriptor,false);
        }
        public NativeMember(int id,String owner,String name,String descriptor,int argc){this(id,owner,name,descriptor,argc,false,false,Map.of(),Map.of());}
        public NativeMember(int id,String owner,String name,String descriptor,int argc,boolean hasBorrow,boolean framelessBorrow,Map<Integer,Boolean> wrappedArguments){this(id,owner,name,descriptor,argc,hasBorrow,framelessBorrow,wrappedArguments,Map.of());}
        public NativeMember {
            if(physicalOwner==null||physicalName==null||physicalDescriptor==null||!physicalOwner.equals(owner)||!physicalDescriptor.equals(descriptor)
                    ||!discardedResult&&!physicalName.equals(name)||discardedResult&&physicalName.equals(name))
                throw new IllegalArgumentException("Invalid native semantic/physical result bridge");
            wrappedArguments=Map.copyOf(wrappedArguments);directCvArguments=Map.copyOf(directCvArguments);if(id<4_000_000||owner==null||owner.isEmpty()||name==null||name.isEmpty()||descriptor==null||!descriptor.startsWith("(")||argc<0||argc>65535)throw new IllegalArgumentException("Invalid exact native dependency");}
    }
    public record ClosureLiteral(int producer,String closure,String module,int index,int lookup,int prepare) {}
    public record ClosureCv(int producer,int local,String closure,int publication) {}
    public record CallSite(int id,int local,int evaluation,int producer,String closure,int arity,int factory,int invoke,int start,int fallback,int end,boolean requiresCellResult,boolean discardedResult) {
        public CallSite(int id,int local,int evaluation,int producer,String closure,int arity,int factory,int invoke,int start,int fallback,int end){this(id,local,evaluation,producer,closure,arity,factory,invoke,start,fallback,end,false,false);}
        public CallSite(int id,int local,int evaluation,int producer,String closure,int arity,int factory,int invoke,int start,int fallback,int end,boolean requiresCellResult){this(id,local,evaluation,producer,closure,arity,factory,invoke,start,fallback,end,requiresCellResult,false);}
    }
    public record CloneSite(int id,int local,int producer,String closure,int helper,int start,int fallback,int end) {}
    public record ArrayEntrySite(int id,int producer,int local,boolean append,int factory,int step,int assignment) {}
    public record HandlerSite(int call,int producer,String closure,int send) {}
    public record NamedGlobal(int local,String name,int helper,int start,int fallback,int end) {}
    public record EntryString(int id,String value,boolean raw,int helper) {}
    public record ReturnBoundary(int helper,int candidate) {}
    public record GlobalImport(int local,int helper,int start,int fallback,int end) {}
    public record GlobalRead(int id,int root,String key,boolean cell,int helper,int read) {}
    public record GlobalUnset(int id,int root,String key,int helper,int read) {}
    public record GlobalScalar(int id,long value,int helper) {}
    public record GlobalNull(int id,int helper) {}
    public record EntryScalar(int id,long value,int helper) {}
    public record ObjectCast(int id,int source,int helper) {}
    public record LiteralEntry(int parent,int wrapper,int helper) {}
    public record LocalIncrement(int id,int root,boolean decrement,boolean post,int helper,int read) {}
    public record ArrayReference(int id,int root,String key,int helper,int read) {}
    public record ObjectCv(int producer,int local,String owner,int publication) {}
    public record PropertyReference(int id,int root,int producer,String key,int helper,int read,int resultLocal,int resultPublication) {}
    public record ObjectNew(int id,String name,String owner,String fingerprint,int helper,int start,int fallback,int end) {}
    public record ArrayIncrement(int id,int root,String key,boolean decrement,boolean post,int helper,int read) {}
    public record FunctionCall(int id,String name,String owner,String member,String descriptor,int local,int factory,int send,int read,int invoke,int start,int fallback,int end,boolean discardedResult) {
        public FunctionCall(int id,String name,String owner,String member,String descriptor,int local,int factory,int send,int read,int invoke,int start,int fallback,int end){this(id,name,owner,member,descriptor,local,factory,send,read,invoke,start,fallback,end,false);}
    }
    public record GlobalAccess(int id,int root,String key,int sourceEval,int sourceLocal,int mode,int read,int factory,int index,int assignment) {}
    public record ConditionalSite(int id,int source,int kind,boolean retainTrue,int trueSource,int falseSource,int test,int branch,int trueResult,int jump,int falseResult,int alternative,int end,int scratch,String trueLiteral,String falseLiteral) {}
    public record ConditionalLocal(int id,int local,int helper,int read) {}
    public record LifecycleReceiver(int local,int read,int boxing,int helper,int store,int start,int fallback,int end) {}
    public record LifecycleDiscard(int local,int read,int branch,int exception,int pop) {}
    public record RuntimePlan(String owner,String method,String descriptor,List<Producer> producers,List<String> unsupported,List<NativeMember> nativeMembers,List<ClosureCv> closureCvs,List<CallSite> calls,List<ClosureLiteral> literals,List<CloneSite> clones,List<ArrayEntrySite> arrayEntries,List<HandlerSite> handlers,List<GlobalImport> globalImports,List<GlobalAccess> globalAccesses,List<GlobalRead> globalReads,List<GlobalUnset> globalUnsets,List<GlobalScalar> globalScalars,List<ArrayReference> arrayReferences,List<FunctionCall> functionCalls,List<ArrayIncrement> arrayIncrements,List<ObjectNew> objectNews,List<ObjectCv> objectCvs,List<PropertyReference> propertyReferences,List<ConditionalSite> conditionals,Set<Integer> quietGlobalReads,List<ConditionalLocal> conditionalLocals,boolean lifecycle,List<LifecycleReceiver> lifecycleReceivers,List<LifecycleDiscard> lifecycleDiscards,List<GlobalNull> globalNulls,List<EntryScalar> entryScalars,List<ObjectCast> objectCasts,List<LiteralEntry> literalEntries,List<LocalIncrement> localIncrements,List<NamedGlobal> namedGlobals,List<EntryString> entryStrings,List<ReturnBoundary> returnBoundaries) {
        public RuntimePlan(String owner,String method,String descriptor,List<Producer> producers,List<String> unsupported,List<NativeMember> nativeMembers,List<ClosureCv> closureCvs,List<CallSite> calls,List<ClosureLiteral> literals,List<CloneSite> clones,List<ArrayEntrySite> arrayEntries,List<HandlerSite> handlers,List<GlobalImport> globalImports,List<GlobalAccess> globalAccesses,List<GlobalRead> globalReads,List<GlobalUnset> globalUnsets,List<GlobalScalar> globalScalars,List<ArrayReference> arrayReferences,List<FunctionCall> functionCalls,List<ArrayIncrement> arrayIncrements,List<ObjectNew> objectNews,List<ObjectCv> objectCvs,List<PropertyReference> propertyReferences,List<ConditionalSite> conditionals,Set<Integer> quietGlobalReads,List<ConditionalLocal> conditionalLocals,boolean lifecycle,List<LifecycleReceiver> lifecycleReceivers,List<LifecycleDiscard> lifecycleDiscards,List<GlobalNull> globalNulls,List<EntryScalar> entryScalars,List<ObjectCast> objectCasts,List<LiteralEntry> literalEntries,List<LocalIncrement> localIncrements){this(owner,method,descriptor,producers,unsupported,nativeMembers,closureCvs,calls,literals,clones,arrayEntries,handlers,globalImports,globalAccesses,globalReads,globalUnsets,globalScalars,arrayReferences,functionCalls,arrayIncrements,objectNews,objectCvs,propertyReferences,conditionals,quietGlobalReads,conditionalLocals,lifecycle,lifecycleReceivers,lifecycleDiscards,globalNulls,entryScalars,objectCasts,literalEntries,localIncrements,List.of(),List.of(),List.of());}
        public RuntimePlan(String owner,String method,String descriptor,List<Producer> producers,List<String> unsupported,List<NativeMember> nativeMembers,List<ClosureCv> closureCvs,List<CallSite> calls,List<ClosureLiteral> literals,List<CloneSite> clones,List<ArrayEntrySite> arrayEntries,List<HandlerSite> handlers,List<GlobalImport> globalImports,List<GlobalAccess> globalAccesses,List<GlobalRead> globalReads,List<GlobalUnset> globalUnsets,List<GlobalScalar> globalScalars,List<ArrayReference> arrayReferences,List<FunctionCall> functionCalls,List<ArrayIncrement> arrayIncrements,List<ObjectNew> objectNews,List<ObjectCv> objectCvs,List<PropertyReference> propertyReferences,List<ConditionalSite> conditionals,Set<Integer> quietGlobalReads,List<ConditionalLocal> conditionalLocals,boolean lifecycle,List<LifecycleReceiver> lifecycleReceivers,List<LifecycleDiscard> lifecycleDiscards,List<GlobalNull> globalNulls,List<EntryScalar> entryScalars,List<ObjectCast> objectCasts,List<LiteralEntry> literalEntries){this(owner,method,descriptor,producers,unsupported,nativeMembers,closureCvs,calls,literals,clones,arrayEntries,handlers,globalImports,globalAccesses,globalReads,globalUnsets,globalScalars,arrayReferences,functionCalls,arrayIncrements,objectNews,objectCvs,propertyReferences,conditionals,quietGlobalReads,conditionalLocals,lifecycle,lifecycleReceivers,lifecycleDiscards,globalNulls,entryScalars,objectCasts,literalEntries,List.of());}
        public RuntimePlan(String owner,String method,String descriptor,List<Producer> producers,List<String> unsupported,List<NativeMember> nativeMembers,List<ClosureCv> closureCvs,List<CallSite> calls,List<ClosureLiteral> literals,List<CloneSite> clones,List<ArrayEntrySite> arrayEntries,List<HandlerSite> handlers,List<GlobalImport> globalImports,List<GlobalAccess> globalAccesses,List<GlobalRead> globalReads,List<GlobalUnset> globalUnsets,List<GlobalScalar> globalScalars,List<ArrayReference> arrayReferences,List<FunctionCall> functionCalls,List<ArrayIncrement> arrayIncrements,List<ObjectNew> objectNews,List<ObjectCv> objectCvs,List<PropertyReference> propertyReferences,List<ConditionalSite> conditionals,Set<Integer> quietGlobalReads,List<ConditionalLocal> conditionalLocals,boolean lifecycle,List<LifecycleReceiver> lifecycleReceivers,List<LifecycleDiscard> lifecycleDiscards,List<GlobalNull> globalNulls,List<EntryScalar> entryScalars,List<ObjectCast> objectCasts){this(owner,method,descriptor,producers,unsupported,nativeMembers,closureCvs,calls,literals,clones,arrayEntries,handlers,globalImports,globalAccesses,globalReads,globalUnsets,globalScalars,arrayReferences,functionCalls,arrayIncrements,objectNews,objectCvs,propertyReferences,conditionals,quietGlobalReads,conditionalLocals,lifecycle,lifecycleReceivers,lifecycleDiscards,globalNulls,entryScalars,objectCasts,List.of());}
        public RuntimePlan(String owner,String method,String descriptor,List<Producer> producers,List<String> unsupported,List<NativeMember> nativeMembers,List<ClosureCv> closureCvs,List<CallSite> calls,List<ClosureLiteral> literals,List<CloneSite> clones,List<ArrayEntrySite> arrayEntries,List<HandlerSite> handlers,List<GlobalImport> globalImports,List<GlobalAccess> globalAccesses,List<GlobalRead> globalReads,List<GlobalUnset> globalUnsets,List<GlobalScalar> globalScalars,List<ArrayReference> arrayReferences,List<FunctionCall> functionCalls,List<ArrayIncrement> arrayIncrements,List<ObjectNew> objectNews,List<ObjectCv> objectCvs,List<PropertyReference> propertyReferences,List<ConditionalSite> conditionals,Set<Integer> quietGlobalReads,List<ConditionalLocal> conditionalLocals,boolean lifecycle,List<LifecycleReceiver> lifecycleReceivers,List<LifecycleDiscard> lifecycleDiscards,List<GlobalNull> globalNulls,List<EntryScalar> entryScalars){this(owner,method,descriptor,producers,unsupported,nativeMembers,closureCvs,calls,literals,clones,arrayEntries,handlers,globalImports,globalAccesses,globalReads,globalUnsets,globalScalars,arrayReferences,functionCalls,arrayIncrements,objectNews,objectCvs,propertyReferences,conditionals,quietGlobalReads,conditionalLocals,lifecycle,lifecycleReceivers,lifecycleDiscards,globalNulls,entryScalars,List.of());}
        public RuntimePlan(String owner,String method,String descriptor,List<Producer> producers,List<String> unsupported,List<NativeMember> nativeMembers,List<ClosureCv> closureCvs,List<CallSite> calls,List<ClosureLiteral> literals,List<CloneSite> clones,List<ArrayEntrySite> arrayEntries,List<HandlerSite> handlers,List<GlobalImport> globalImports,List<GlobalAccess> globalAccesses,List<GlobalRead> globalReads,List<GlobalUnset> globalUnsets,List<GlobalScalar> globalScalars,List<ArrayReference> arrayReferences,List<FunctionCall> functionCalls,List<ArrayIncrement> arrayIncrements,List<ObjectNew> objectNews,List<ObjectCv> objectCvs,List<PropertyReference> propertyReferences,List<ConditionalSite> conditionals,Set<Integer> quietGlobalReads,List<ConditionalLocal> conditionalLocals,boolean lifecycle,List<LifecycleReceiver> lifecycleReceivers,List<LifecycleDiscard> lifecycleDiscards,List<GlobalNull> globalNulls){this(owner,method,descriptor,producers,unsupported,nativeMembers,closureCvs,calls,literals,clones,arrayEntries,handlers,globalImports,globalAccesses,globalReads,globalUnsets,globalScalars,arrayReferences,functionCalls,arrayIncrements,objectNews,objectCvs,propertyReferences,conditionals,quietGlobalReads,conditionalLocals,lifecycle,lifecycleReceivers,lifecycleDiscards,globalNulls,List.of());}
        public RuntimePlan(String owner,String method,String descriptor,List<Producer> producers,List<String> unsupported,List<NativeMember> nativeMembers,List<ClosureCv> closureCvs,List<CallSite> calls,List<ClosureLiteral> literals,List<CloneSite> clones,List<ArrayEntrySite> arrayEntries,List<HandlerSite> handlers,List<GlobalImport> globalImports,List<GlobalAccess> globalAccesses,List<GlobalRead> globalReads,List<GlobalUnset> globalUnsets,List<GlobalScalar> globalScalars,List<ArrayReference> arrayReferences,List<FunctionCall> functionCalls,List<ArrayIncrement> arrayIncrements,List<ObjectNew> objectNews,List<ObjectCv> objectCvs,List<PropertyReference> propertyReferences,List<ConditionalSite> conditionals,Set<Integer> quietGlobalReads,List<ConditionalLocal> conditionalLocals,boolean lifecycle,List<LifecycleReceiver> lifecycleReceivers,List<LifecycleDiscard> lifecycleDiscards){this(owner,method,descriptor,producers,unsupported,nativeMembers,closureCvs,calls,literals,clones,arrayEntries,handlers,globalImports,globalAccesses,globalReads,globalUnsets,globalScalars,arrayReferences,functionCalls,arrayIncrements,objectNews,objectCvs,propertyReferences,conditionals,quietGlobalReads,conditionalLocals,lifecycle,lifecycleReceivers,lifecycleDiscards,List.of());}
        public RuntimePlan {namedGlobals=List.copyOf(namedGlobals);entryStrings=List.copyOf(entryStrings);returnBoundaries=List.copyOf(returnBoundaries);producers=List.copyOf(producers);unsupported=List.copyOf(unsupported);nativeMembers=List.copyOf(nativeMembers);closureCvs=List.copyOf(closureCvs);calls=List.copyOf(calls);literals=List.copyOf(literals);clones=List.copyOf(clones);arrayEntries=List.copyOf(arrayEntries);handlers=List.copyOf(handlers);globalImports=List.copyOf(globalImports);globalAccesses=List.copyOf(globalAccesses);globalReads=List.copyOf(globalReads);globalUnsets=List.copyOf(globalUnsets);globalScalars=List.copyOf(globalScalars);arrayReferences=List.copyOf(arrayReferences);functionCalls=List.copyOf(functionCalls);arrayIncrements=List.copyOf(arrayIncrements);objectNews=List.copyOf(objectNews);objectCvs=List.copyOf(objectCvs);propertyReferences=List.copyOf(propertyReferences);conditionals=List.copyOf(conditionals);quietGlobalReads=Set.copyOf(quietGlobalReads);conditionalLocals=List.copyOf(conditionalLocals);lifecycleReceivers=List.copyOf(lifecycleReceivers);lifecycleDiscards=List.copyOf(lifecycleDiscards);globalNulls=List.copyOf(globalNulls);entryScalars=List.copyOf(entryScalars);objectCasts=List.copyOf(objectCasts);literalEntries=List.copyOf(literalEntries);localIncrements=List.copyOf(localIncrements);}
        public RuntimePlan(String owner,String method,String descriptor,List<Producer> producers,List<String> unsupported,List<NativeMember> nativeMembers,List<ClosureCv> closureCvs,List<CallSite> calls,List<ClosureLiteral> literals,List<CloneSite> clones,List<ArrayEntrySite> arrayEntries,List<HandlerSite> handlers,List<GlobalImport> globalImports,List<GlobalAccess> globalAccesses,List<GlobalRead> globalReads,List<GlobalUnset> globalUnsets,List<GlobalScalar> globalScalars,List<ArrayReference> arrayReferences,List<FunctionCall> functionCalls,List<ArrayIncrement> arrayIncrements,List<ObjectNew> objectNews,List<ObjectCv> objectCvs,List<PropertyReference> propertyReferences,List<ConditionalSite> conditionals,Set<Integer> quietGlobalReads,List<ConditionalLocal> conditionalLocals,boolean lifecycle,List<LifecycleReceiver> lifecycleReceivers){this(owner,method,descriptor,producers,unsupported,nativeMembers,closureCvs,calls,literals,clones,arrayEntries,handlers,globalImports,globalAccesses,globalReads,globalUnsets,globalScalars,arrayReferences,functionCalls,arrayIncrements,objectNews,objectCvs,propertyReferences,conditionals,quietGlobalReads,conditionalLocals,lifecycle,lifecycleReceivers,List.of());}
        public RuntimePlan(String owner,String method,String descriptor,List<Producer> producers,List<String> unsupported,List<NativeMember> nativeMembers,List<ClosureCv> closureCvs,List<CallSite> calls,List<ClosureLiteral> literals,List<CloneSite> clones,List<ArrayEntrySite> arrayEntries,List<HandlerSite> handlers,List<GlobalImport> globalImports,List<GlobalAccess> globalAccesses,List<GlobalRead> globalReads,List<GlobalUnset> globalUnsets,List<GlobalScalar> globalScalars,List<ArrayReference> arrayReferences,List<FunctionCall> functionCalls,List<ArrayIncrement> arrayIncrements,List<ObjectNew> objectNews,List<ObjectCv> objectCvs,List<PropertyReference> propertyReferences,List<ConditionalSite> conditionals,Set<Integer> quietGlobalReads,List<ConditionalLocal> conditionalLocals){this(owner,method,descriptor,producers,unsupported,nativeMembers,closureCvs,calls,literals,clones,arrayEntries,handlers,globalImports,globalAccesses,globalReads,globalUnsets,globalScalars,arrayReferences,functionCalls,arrayIncrements,objectNews,objectCvs,propertyReferences,conditionals,quietGlobalReads,conditionalLocals,false,List.of());}
        public RuntimePlan(String owner,String method,String descriptor,List<Producer> producers,List<String> unsupported,List<NativeMember> nativeMembers,List<ClosureCv> closureCvs,List<CallSite> calls,List<ClosureLiteral> literals,List<CloneSite> clones,List<ArrayEntrySite> arrayEntries,List<HandlerSite> handlers,List<GlobalImport> globalImports,List<GlobalAccess> globalAccesses,List<GlobalRead> globalReads,List<GlobalUnset> globalUnsets,List<GlobalScalar> globalScalars,List<ArrayReference> arrayReferences,List<FunctionCall> functionCalls,List<ArrayIncrement> arrayIncrements,List<ObjectNew> objectNews,List<ObjectCv> objectCvs,List<PropertyReference> propertyReferences,List<ConditionalSite> conditionals,Set<Integer> quietGlobalReads){this(owner,method,descriptor,producers,unsupported,nativeMembers,closureCvs,calls,literals,clones,arrayEntries,handlers,globalImports,globalAccesses,globalReads,globalUnsets,globalScalars,arrayReferences,functionCalls,arrayIncrements,objectNews,objectCvs,propertyReferences,conditionals,quietGlobalReads,List.of());}
        public RuntimePlan(String owner,String method,String descriptor,List<Producer> producers,List<String> unsupported,List<NativeMember> natives,List<ClosureCv> cvs,List<CallSite> calls,List<ClosureLiteral> literals,List<CloneSite> clones,List<ArrayEntrySite> entries,List<HandlerSite> handlers,List<GlobalImport> imports,List<GlobalAccess> accesses,List<GlobalRead> reads,List<GlobalUnset> unsets,List<GlobalScalar> scalars,List<ArrayReference> refs,List<FunctionCall> functions,List<ArrayIncrement> increments,List<ObjectNew> news,List<ObjectCv> objectCvs,List<PropertyReference> properties){this(owner,method,descriptor,producers,unsupported,natives,cvs,calls,literals,clones,entries,handlers,imports,accesses,reads,unsets,scalars,refs,functions,increments,news,objectCvs,properties,List.of(),Set.of());}
        public RuntimePlan(String owner,String method,String descriptor,List<Producer> producers,List<String> unsupported,List<NativeMember> natives,List<ClosureCv> cvs,List<CallSite> calls,List<ClosureLiteral> literals,List<CloneSite> clones,List<ArrayEntrySite> entries,List<HandlerSite> handlers,List<GlobalImport> imports,List<GlobalAccess> accesses,List<GlobalRead> reads,List<GlobalUnset> unsets,List<GlobalScalar> scalars,List<ArrayReference> refs,List<FunctionCall> functions,List<ArrayIncrement> increments,List<ObjectNew> news){this(owner,method,descriptor,producers,unsupported,natives,cvs,calls,literals,clones,entries,handlers,imports,accesses,reads,unsets,scalars,refs,functions,increments,news,List.of(),List.of());}
        public RuntimePlan(String owner,String method,String descriptor,List<Producer> producers,List<String> unsupported,List<NativeMember> natives,List<ClosureCv> cvs,List<CallSite> calls,List<ClosureLiteral> literals,List<CloneSite> clones,List<ArrayEntrySite> entries,List<HandlerSite> handlers,List<GlobalImport> imports,List<GlobalAccess> accesses,List<GlobalRead> reads,List<GlobalUnset> unsets,List<GlobalScalar> scalars,List<ArrayReference> refs,List<FunctionCall> functions,List<ArrayIncrement> increments){this(owner,method,descriptor,producers,unsupported,natives,cvs,calls,literals,clones,entries,handlers,imports,accesses,reads,unsets,scalars,refs,functions,increments,List.of());}
        public RuntimePlan(String owner,String method,String descriptor,List<Producer> producers,List<String> unsupported,List<NativeMember> natives,List<ClosureCv> cvs,List<CallSite> calls,List<ClosureLiteral> literals,List<CloneSite> clones,List<ArrayEntrySite> entries,List<HandlerSite> handlers,List<GlobalImport> imports,List<GlobalAccess> accesses,List<GlobalRead> reads,List<GlobalUnset> unsets,List<GlobalScalar> scalars,List<ArrayReference> refs,List<FunctionCall> functions){this(owner,method,descriptor,producers,unsupported,natives,cvs,calls,literals,clones,entries,handlers,imports,accesses,reads,unsets,scalars,refs,functions,List.of());}
        public RuntimePlan(String owner,String method,String descriptor,List<Producer> producers,List<String> unsupported,List<NativeMember> natives,List<ClosureCv> cvs,List<CallSite> calls,List<ClosureLiteral> literals,List<CloneSite> clones,List<ArrayEntrySite> entries,List<HandlerSite> handlers,List<GlobalImport> imports,List<GlobalAccess> accesses,List<GlobalRead> reads,List<GlobalUnset> unsets,List<GlobalScalar> scalars,List<ArrayReference> refs){this(owner,method,descriptor,producers,unsupported,natives,cvs,calls,literals,clones,entries,handlers,imports,accesses,reads,unsets,scalars,refs,List.of());}
        public RuntimePlan(String owner,String method,String descriptor,List<Producer> producers,List<String> unsupported,List<NativeMember> natives,List<ClosureCv> cvs,List<CallSite> calls,List<ClosureLiteral> literals,List<CloneSite> clones,List<ArrayEntrySite> entries,List<HandlerSite> handlers,List<GlobalImport> imports,List<GlobalAccess> accesses,List<GlobalRead> reads,List<GlobalUnset> unsets,List<GlobalScalar> scalars){this(owner,method,descriptor,producers,unsupported,natives,cvs,calls,literals,clones,entries,handlers,imports,accesses,reads,unsets,scalars,List.of());}
        public RuntimePlan(String owner,String method,String descriptor,List<Producer> producers,List<String> unsupported,List<NativeMember> natives,List<ClosureCv> cvs,List<CallSite> calls,List<ClosureLiteral> literals,List<CloneSite> clones,List<ArrayEntrySite> entries,List<HandlerSite> handlers,List<GlobalImport> imports,List<GlobalAccess> accesses,List<GlobalRead> reads,List<GlobalUnset> unsets){this(owner,method,descriptor,producers,unsupported,natives,cvs,calls,literals,clones,entries,handlers,imports,accesses,reads,unsets,List.of());}
        public RuntimePlan(String owner,String method,String descriptor,List<Producer> producers,List<String> unsupported,List<NativeMember> natives,List<ClosureCv> cvs,List<CallSite> calls,List<ClosureLiteral> literals,List<CloneSite> clones,List<ArrayEntrySite> entries,List<HandlerSite> handlers,List<GlobalImport> imports,List<GlobalAccess> accesses){this(owner,method,descriptor,producers,unsupported,natives,cvs,calls,literals,clones,entries,handlers,imports,accesses,List.of(),List.of());}
        public RuntimePlan(String owner,String method,String descriptor,List<Producer> producers,List<String> unsupported,List<NativeMember> natives,List<ClosureCv> cvs,List<CallSite> calls,List<ClosureLiteral> literals,List<CloneSite> clones,List<ArrayEntrySite> entries,List<HandlerSite> handlers){this(owner,method,descriptor,producers,unsupported,natives,cvs,calls,literals,clones,entries,handlers,List.of(),List.of());}
        public RuntimePlan(String owner,String method,String descriptor,List<Producer> producers,List<String> unsupported,List<NativeMember> natives,List<ClosureCv> cvs,List<CallSite> calls,List<ClosureLiteral> literals,List<CloneSite> clones){this(owner,method,descriptor,producers,unsupported,natives,cvs,calls,literals,clones,List.of(),List.of());}
        public RuntimePlan(String owner,String method,String descriptor,List<Producer> producers,List<String> unsupported,List<NativeMember> natives,List<ClosureCv> cvs,List<CallSite> calls,List<ClosureLiteral> literals){this(owner,method,descriptor,producers,unsupported,natives,cvs,calls,literals,List.of());}
        public RuntimePlan(String owner,String method,String descriptor,List<Producer> producers,List<String> unsupported,List<NativeMember> natives){this(owner,method,descriptor,producers,unsupported,natives,List.of(),List.of(),List.of(),List.of());}
        public RuntimePlan(String owner,String method,String descriptor,List<Producer> producers,List<String> unsupported){this(owner,method,descriptor,producers,unsupported,List.of());}
    }
    /** Data-only transport; byte identity and complete body protocols are validated separately. */
    public static RuntimePlan readRuntimePlan(byte[] bytes,String method,String descriptor)throws IOException {
        if(bytes==null || bytes.length>64*1024*1024)throw new IOException("Invalid generated class size");
        DataInputStream in=new DataInputStream(new ByteArrayInputStream(bytes));if(in.readInt()!=0xcafebabe)throw new IOException("Invalid classfile");in.readUnsignedShort();in.readUnsignedShort();
        int count=in.readUnsignedShort();Object[] pool=new Object[count];
        for(int i=1;i<count;i++)switch(in.readUnsignedByte()){
            case 1:pool[i]=in.readUTF();break;case 3:pool[i]=in.readInt();break;case 4:in.skipNBytes(4);break;case 5:pool[i]=in.readLong();if(++i>=count)throw new IOException("Invalid wide constant");break;case 6:in.skipNBytes(8);if(++i>=count)throw new IOException("Invalid wide constant");break;
            case 7:pool[i]=in.readUnsignedShort();break;case 8:pool[i]=new RuntimeString(in.readUnsignedShort());break;case 16:case 19:case 20:in.skipNBytes(2);break;case 9:case 10:case 11:case 12:case 17:case 18:pool[i]=new RuntimePair(in.readUnsignedShort(),in.readUnsignedShort());break;case 15:in.skipNBytes(3);break;default:throw new IOException("Unknown class constant");
        }
        in.readUnsignedShort();int thisClass=in.readUnsignedShort();in.readUnsignedShort();if(thisClass<=0 || thisClass>=count || !(pool[thisClass] instanceof Integer))throw new IOException("Invalid class identity");String owner=runtimeUtf(pool,(Integer)pool[thisClass]);
        int interfaces=in.readUnsignedShort();in.skipNBytes(2L*interfaces);
        int fields=in.readUnsignedShort();for(int i=0;i<fields;i++){in.skipNBytes(6);skipRuntimeAttributes(in,pool);}
        int methods=in.readUnsignedShort();byte[] payload=null,cellPayload=null;RuntimeCode methodCode=null;int methodAccess=-1;
        for(int i=0;i<methods;i++){
            int access=in.readUnsignedShort();String name=runtimeUtf(pool,in.readUnsignedShort()),desc=runtimeUtf(pool,in.readUnsignedShort());int attributes=in.readUnsignedShort();boolean matching=name.equals(method)&&desc.equals(descriptor);if(matching)methodAccess=access;
            for(int j=0;j<attributes;j++){String attribute=runtimeUtf(pool,in.readUnsignedShort());long length=Integer.toUnsignedLong(in.readInt());if(length>in.available())throw new IOException("Truncated class attribute");
                if(matching && attribute.equals("JPHP_Runtime_Frame_v1")){if(payload!=null || length>1024*1024)throw new IOException("Duplicate/oversized execution plan");payload=in.readNBytes((int)length);}else if(matching&&attribute.equals(PhpPrimitiveCellPlan.ATTRIBUTE)){if(cellPayload!=null||length>1024*1024)throw new IOException("Duplicate/oversized CELL transport plan");cellPayload=in.readNBytes((int)length);}else if(matching&&attribute.equals("Code")){if(methodCode!=null)throw new IOException("Duplicate Code attribute");methodCode=runtimeCode(in.readNBytes((int)length),pool);}else in.skipNBytes(length);
            }
        }
        skipRuntimeAttributes(in,pool);if(in.read()!=-1)throw new IOException("Trailing class data");if(payload==null)return null;
        DataInputStream data=new DataInputStream(new ByteArrayInputStream(payload));
        int schema=data.readInt();if(schema!=1&&schema!=2&&schema!=3&&schema!=4&&schema!=5&&schema!=6&&schema!=7&&schema!=8&&schema!=9&&schema!=10&&schema!=11&&schema!=12&&schema!=13&&schema!=14&&schema!=15&&schema!=16&&schema!=17&&schema!=18&&schema!=19&&schema!=20&&schema!=21&&schema!=22&&schema!=23&&schema!=24)throw new IOException("Unknown runtime frame schema");
        String planOwner=data.readUTF(),planMethod=data.readUTF(),planDescriptor=data.readUTF();
        if(!owner.equals(planOwner)||!method.equals(planMethod)||!descriptor.equals(planDescriptor))throw new IOException("Foreign runtime frame identity");
        List<Producer> producers=new ArrayList<>();Set<Integer> ids=new HashSet<>();
        try {
            int size=runtimeCount(data);
            for(int i=0;i<size;i++) {
                int id=data.readInt();String kind=data.readUTF();Set<String> consumers=runtimeStrings(data);
                if(!ids.add(id))throw new IOException("Duplicate runtime producer");producers.add(new Producer(id,kind,consumers));
            }
            List<String> unsupported=new ArrayList<>(runtimeStrings(data));
            List<NativeMember> nativeMembers=new ArrayList<>();Set<Integer> nativeIds=new HashSet<>();
            if(schema>=2)for(int i=0,n=runtimeCount(data);i<n;i++) {
                NativeMember nativeMember=new NativeMember(data.readInt(),data.readUTF(),data.readUTF(),data.readUTF(),data.readInt());
                if(schema>=24)nativeMember=new NativeMember(nativeMember.id(),nativeMember.owner(),nativeMember.name(),nativeMember.descriptor(),nativeMember.argc(),false,false,Map.of(),Map.of(),data.readUTF(),data.readUTF(),data.readUTF(),data.readBoolean());
                if(!nativeIds.add(nativeMember.id()))throw new IOException("Duplicate native dependency");
                int nativeId=nativeMember.id();Producer producer=producers.stream().filter(p->p.id()==nativeId).findFirst().orElseThrow(()->new IllegalArgumentException("Native RESULT is not a recorded producer"));
                if(!producer.kind().equals("NATIVE_RESULT"))throw new IOException("Native dependency uses another producer kind");nativeMembers.add(nativeMember);
            }
            for(Producer producer:producers)if(producer.kind().equals("NATIVE_RESULT")&&!nativeIds.contains(producer.id()))throw new IOException("Native RESULT lacks its exact member");
            Set<Integer> bodyIds=new HashSet<>();Map<Integer,Set<Integer>> nativeBorrowSites=new HashMap<>();Map<Integer,Integer> nativeSuccessJoins=new HashMap<>(),nativeBodyEnters=new HashMap<>(),nativeBodyOpcodes=new HashMap<>();
            if(schema>=3)for(int i=0,n=runtimeCount(data);i<n;i++) {
                int id=data.readInt(),enter=data.readInt(),body=data.readInt(),leave=data.readInt(),failure=data.readInt(),guard=data.readInt();
                NativeMember member=nativeMembers.stream().filter(m->m.id()==id).findFirst().orElseThrow(()->new IllegalArgumentException("Native BODY has no exact member"));
                if(!bodyIds.add(id))throw new IOException("Duplicate native BODY");
                validateRuntimeBody(methodCode,pool,member,enter,body,leave,failure,guard);nativeSuccessJoins.put(id,methodCode.instructions().get(leave+1).operand());nativeBodyEnters.put(id,enter);nativeBodyOpcodes.put(id,body);
                Set<Integer> conversions=new HashSet<>();for(int j=0,c=runtimeCount(data);j<c;j++){
                    int ordinal=data.readInt();if(ordinal<0||ordinal>=enter||!conversions.add(ordinal))throw new IOException("Native transport conversion escapes its body preparation");
                    runtimeMethod(methodCode.instructions().get(ordinal),pool,182,"php/runtime/Memory","toValue","()Lphp/runtime/Memory;");
                }
                Set<Integer> borrows=new HashSet<>();Map<Integer,Boolean> wrapping=new LinkedHashMap<>();Map<Integer,Integer> directCvs=new LinkedHashMap<>();String borrowMode=null;for(int j=0,c=runtimeCount(data);j<c;j++){
                    int ordinal=data.readInt();String name=data.readUTF();
                    boolean directCv=name.startsWith("nativeDirectCvSend-fast-wrapped:");
                    if(ordinal<0||ordinal>=enter||!borrows.add(ordinal)||!directCv&&!Set.of("borrowNativeArgument","borrowNativeFastArgument","nativeBorrowSend-value-wrapped","nativeBorrowSend-fast-wrapped","nativeBorrowSend-value-plain","nativeBorrowSend-fast-plain").contains(name)||borrowMode!=null&&borrowMode.contains("-fast-")!=name.contains("-fast-"))throw new IOException("Native operand borrow escapes its exact preparation");
                    if(directCv) {
                        String[] cvProofFields=name.split(":",-1);if(cvProofFields.length!=4)throw new IOException("Malformed direct CV transport proof");
                        int source,store,local;try{source=Integer.parseInt(cvProofFields[1]);store=Integer.parseInt(cvProofFields[2]);local=Integer.parseInt(cvProofFields[3]);}catch(NumberFormatException malformed){throw new IOException("Malformed direct CV source",malformed);}
                        var ops=methodCode.instructions();
                        runtimeMethod(ops.get(ordinal),pool,184,"php/runtime/PhpRuntimeOwnership","nativeDirectCvSend","(Lphp/runtime/PhpRuntimeOwnership$NativePacket;ILphp/runtime/Memory;I)Lphp/runtime/Memory;");
                        if(local<0||ordinal<6||source!=ordinal-6||store!=ordinal-5||ops.get(source).opcode()!=25||ops.get(source).operand()!=local
                                ||ops.get(store).opcode()!=58||ops.get(ordinal-4).opcode()!=25||ops.get(ordinal-4).operand()!=ops.get(enter-1).operand()
                                ||ops.get(ordinal-2).opcode()!=25||ops.get(ordinal-2).operand()!=ops.get(store).operand()||runtimeSmallInt(ops.get(ordinal-1),pool)!=local)
                            throw new IOException("Native direct CV lost its exact physical read/slot/packet");
                        int argument=runtimeSmallInt(ops.get(ordinal-3),pool);
                        if(argument<0||argument>=member.argc()||wrapping.putIfAbsent(argument,true)!=null||directCvs.putIfAbsent(argument,local)!=null)
                            throw new IOException("Native direct CV changed its argument identity");
                    } else if(name.startsWith("nativeBorrowSend-")) {
                        runtimeMethod(methodCode.instructions().get(ordinal),pool,184,"php/runtime/PhpRuntimeOwnership","nativeBorrowSend","(Lphp/runtime/PhpRuntimeOwnership$NativePacket;ILphp/runtime/Memory;IZZ)Lphp/runtime/Memory;");
                        int expected=name.contains("-fast-")?4:3;boolean wrapped=name.endsWith("-wrapped");
                        if(ordinal<5||methodCode.instructions().get(ordinal-2).opcode()!=expected||methodCode.instructions().get(ordinal-1).opcode()!=(wrapped?4:3))throw new IOException("Native borrow opcode mode changed");
                        int argument=runtimeSmallInt(methodCode.instructions().get(ordinal-5),pool);
                        if(argument<0||argument>=member.argc()||wrapping.putIfAbsent(argument,wrapped)!=null)throw new IOException("Native borrowed argument identity changed");
                    }else runtimeMethod(methodCode.instructions().get(ordinal),pool,184,"php/runtime/invoke/PhpCallFrame",name,"(Lphp/runtime/Memory;)Lphp/runtime/Memory;");borrowMode=name;
                }
                nativeBorrowSites.put(id,Set.copyOf(borrows));
                if(borrowMode!=null)nativeMembers.set(nativeMembers.indexOf(member),new NativeMember(member.id(),member.owner(),member.name(),member.descriptor(),member.argc(),true,borrowMode.equals("borrowNativeFastArgument")||borrowMode.contains("-fast-"),wrapping,directCvs,member.physicalOwner(),member.physicalName(),member.physicalDescriptor(),member.discardedResult()));
            }
            for(int id:nativeIds)if(!bodyIds.contains(id))unsupported.add("native-body-protocol-missing:"+id);
            Map<Integer,Integer> nativeFactories=schema>=3?validateRuntimeNativeFactories(methodCode,pool,nativeMembers):Map.of();
            List<ClosureLiteral> literals=new ArrayList<>();Set<Integer> literalIds=new HashSet<>();List<ClosureCv> closureCvs=new ArrayList<>();List<CallSite> calls=new ArrayList<>();Set<String> cvIds=new HashSet<>();Set<Integer> callIds=new HashSet<>();
            if(schema>=4) {
                if(methodCode==null)throw new IOException("CALL transport has no actual Code body");
                int frameLocal=runtimeGeneratedFrame(methodCode,pool);
                for(int i=0,n=runtimeCount(data);i<n;i++) {
                    ClosureLiteral literal=new ClosureLiteral(data.readInt(),data.readUTF(),data.readUTF(),data.readInt(),data.readInt(),data.readInt());
                    if(!literalIds.add(literal.producer())||literal.closure().isEmpty()||literal.module().isEmpty()||literal.index()<0||producers.stream().noneMatch(item->item.id()==literal.producer()&&item.kind().equals("CLOSURE_LITERAL")))throw new IOException("Invalid literal Closure source");
                    var instructions=methodCode.instructions();int lookup=literal.lookup(),prepare=literal.prepare();if(lookup<4||prepare!=lookup+2||prepare>=instructions.size())throw new IOException("Closure literal escapes its exact allocation protocol");
                    runtimeMethod(instructions.get(lookup),pool,182,"php/runtime/env/Environment","__getClosure","(Ljava/lang/String;I)Lphp/runtime/reflection/ClassEntity;");
                    runtimeMethod(instructions.get(prepare),pool,184,"php/runtime/PhpOwnershipClosures","prepareRuntime","(Lphp/runtime/PhpRuntimeOwnership$Frame;ILphp/runtime/reflection/helper/ClosureEntity;)Lphp/runtime/PhpOwnershipClosures$RuntimeAllocation;");
                    if(lookup<5||instructions.get(lookup-5).opcode()!=25||instructions.get(lookup-5).operand()!=frameLocal||runtimeSmallInt(instructions.get(lookup-1),pool)!=literal.index()||!runtimeString(instructions.get(lookup-2),pool).equals(literal.module())||instructions.get(lookup-3).opcode()!=25||runtimeSmallInt(instructions.get(lookup-4),pool)!=literal.producer()||instructions.get(lookup+1).opcode()!=192||!runtimeClass(pool,instructions.get(lookup+1).operand()).equals("php/runtime/reflection/helper/ClosureEntity"))throw new IOException("Closure literal module/index/EVAL operands changed");literals.add(literal);
                }
                for(int i=0,n=runtimeCount(data);i<n;i++) {
                    ClosureCv cv=new ClosureCv(data.readInt(),data.readInt(),data.readUTF(),data.readInt());
                    Producer source=producers.stream().filter(item->item.id()==cv.producer()).findFirst().orElseThrow(()->new IllegalArgumentException("Closure CV has no actual producer"));
                    if(cv.local()<0||cv.closure().isEmpty()||!Set.of("CLOSURE_LITERAL","CLONE").contains(source.kind())||!source.consumers().contains("CV")||!cvIds.add(cv.producer()+":"+cv.local()))throw new IOException("Invalid Closure CV source");
                    var instructions=methodCode.instructions();int at=cv.publication();if(at<5||at>=instructions.size())throw new IOException("Closure CV publication escapes its body");
                    runtimeMethod(instructions.get(at),pool,182,"php/runtime/PhpRuntimeOwnership$Frame","assignLocalFromEval","(IILphp/runtime/Memory;I)Lphp/runtime/Memory;");
                    if(runtimeSmallInt(instructions.get(at-4),pool)!=cv.producer()||runtimeSmallInt(instructions.get(at-3),pool)!=cv.local()||instructions.get(at-2).opcode()!=25||instructions.get(at-1).opcode()!=3)throw new IOException("Closure CV publication differs from its actual source/local VALUE operands");closureCvs.add(cv);
                }
                for(int i=0,n=runtimeCount(data);i<n;i++) {
                    CallSite call=new CallSite(data.readInt(),data.readInt(),data.readInt(),data.readInt(),data.readUTF(),data.readInt(),data.readInt(),data.readInt(),data.readInt(),data.readInt(),data.readInt());
                    if(!callIds.add(call.id())||call.arity()!=0||(call.local()<0)==(call.evaluation()<0)||call.evaluation()>=0&&call.evaluation()!=call.producer()||call.closure().isEmpty())throw new IOException("Invalid zero-argument CALL source");
                    Producer result=producers.stream().filter(item->item.id()==call.id()).findFirst().orElseThrow(()->new IllegalArgumentException("CALL lacks its actual RESULT producer"));
                    Producer source=producers.stream().filter(item->item.id()==call.producer()).findFirst().orElseThrow(()->new IllegalArgumentException("CALL lacks its actual callable producer"));
                    if(!result.kind().equals("CALL_RESULT")||!Set.of("CLOSURE_LITERAL","CLONE").contains(source.kind()))throw new IOException("CALL producer role changed");
                    if(schema==4&&literals.stream().noneMatch(literal->literal.producer()==call.producer()&&literal.closure().equals(call.closure())))throw new IOException("CALL has no exact literal module/entity source");
                    if(call.local()>=0&&closureCvs.stream().noneMatch(cv->cv.producer()==call.producer()&&cv.local()==call.local()&&cv.closure().equals(call.closure())&&cv.publication()<call.factory()))throw new IOException("CALL has no earlier exact CV publication");
                    validateRuntimeCall(methodCode,pool,call,frameLocal);
                    boolean cellResult=runtimeRequiresCellResult(methodCode,pool,call.id(),frameLocal);
                    calls.add(new CallSite(call.id(),call.local(),call.evaluation(),call.producer(),call.closure(),call.arity(),call.factory(),call.invoke(),call.start(),call.fallback(),call.end(),cellResult,runtimeInvocationDiscarded(methodCode.instructions().get(call.invoke()),pool,"php/runtime/PhpRuntimeOwnership$CallPacket")));
                }
            }
            List<CloneSite> clones=new ArrayList<>();Set<Integer> cloneIds=new HashSet<>();
            if(schema>=5) {
                int frameLocal=runtimeGeneratedFrame(methodCode,pool);
                for(int i=0,n=runtimeCount(data);i<n;i++) {
                    CloneSite clone=new CloneSite(data.readInt(),data.readInt(),data.readInt(),data.readUTF(),data.readInt(),data.readInt(),data.readInt(),data.readInt());
                    if(!cloneIds.add(clone.id())||clone.id()==clone.producer()||clone.local()<0||clone.closure().isEmpty()||producers.stream().noneMatch(item->item.id()==clone.id()&&item.kind().equals("CLONE")))throw new IOException("Invalid CLONE producer");
                    if(closureCvs.stream().noneMatch(cv->cv.producer()==clone.producer()&&cv.local()==clone.local()&&cv.closure().equals(clone.closure())&&cv.publication()<clone.helper()))throw new IOException("CLONE has no prior exact source CV publication");
                    validateRuntimeClone(methodCode,pool,clone,frameLocal);clones.add(clone);
                }
                for(CloneSite clone:clones)if(!runtimeClosureClass(clone.id(),literals,clones,new HashSet<>()).equals(clone.closure()))throw new IOException("CLONE changed its source Closure class");
                for(ClosureCv cv:closureCvs) {
                    String closure=runtimeClosureClass(cv.producer(),literals,clones,new HashSet<>());
                    int allocation=literals.stream().filter(literal->literal.producer()==cv.producer()).mapToInt(ClosureLiteral::prepare).findFirst().orElseGet(()->clones.stream().filter(clone->clone.id()==cv.producer()).mapToInt(CloneSite::helper).findFirst().orElse(-1));
                    if(!closure.equals(cv.closure())||allocation<0||allocation>=cv.publication())throw new IOException("Closure CV is not derived from its earlier exact allocation");
                }
                for(CallSite call:calls)if(!runtimeClosureClass(call.producer(),literals,clones,new HashSet<>()).equals(call.closure()))throw new IOException("CALL changed its literal-rooted Closure source");
            }
            List<ArrayEntrySite> arrayEntries=new ArrayList<>();List<HandlerSite> handlers=new ArrayList<>();
            if(schema>=6) {
                int frameLocal=runtimeGeneratedFrame(methodCode,pool);var declaredFrame=PhpOwnershipExecution.readFramePlan(bytes,method,descriptor);
                if(declaredFrame==null)throw new IOException("Schema6 requires its actual CV frame declaration");Set<Integer> entryIds=new HashSet<>(),handlerIds=new HashSet<>();
                for(int i=0,n=runtimeCount(data);i<n;i++) {
                    ArrayEntrySite site=new ArrayEntrySite(data.readInt(),data.readInt(),data.readInt(),data.readBoolean(),data.readInt(),data.readInt(),data.readInt());
                    if(site.id()<4_000_000||site.local()<0||!entryIds.add(site.id())||producers.stream().noneMatch(p->p.id()==site.producer()&&p.consumers().contains("ENTRY")))throw new IOException("ARRAY ENTRY has no exact producer/local identity");
                    validateRuntimeArrayEntry(methodCode,pool,site,frameLocal,owner,declaredFrame);arrayEntries.add(site);
                }
                for(int i=0,n=runtimeCount(data);i<n;i++) {
                    HandlerSite site=new HandlerSite(data.readInt(),data.readInt(),data.readUTF(),data.readInt());
                    NativeMember nativeMember=nativeMembers.stream().filter(m->m.id()==site.call()).findFirst().orElseThrow(()->new IOException("Handler FIELD lacks its exact native BODY"));
                    if(!handlerIds.add(site.call())||!nativeMember.owner().equals("php/runtime/ext/core/LangFunctions")||!nativeMember.name().equals("set_error_handler")
                            ||producers.stream().noneMatch(p->p.id()==site.producer()&&p.kind().equals("CLOSURE_LITERAL")&&p.consumers().contains("SEND"))
                            ||literals.stream().noneMatch(l->l.producer()==site.producer()&&l.closure().equals(site.closure())&&l.prepare()<site.send()))throw new IOException("Handler FIELD has no actual literal callable source");
                    var instructions=methodCode.instructions();int send=site.send();
                    if(send<6||send>=instructions.size())throw new IOException("Handler SEND escapes its actual body");
                    runtimeMethod(instructions.get(send),pool,184,"php/runtime/PhpRuntimeOwnership","nativeBorrowSend","(Lphp/runtime/PhpRuntimeOwnership$NativePacket;ILphp/runtime/Memory;IZZ)Lphp/runtime/Memory;");
                    if(runtimeSmallInt(instructions.get(send-5),pool)!=0||runtimeSmallInt(instructions.get(send-3),pool)!=site.producer())throw new IOException("Handler FIELD does not consume argument zero's exact producer");
                    // This ordinal must already belong to this exact BODY's checked argument-zero transport.
                    if(!nativeMember.wrappedArguments().containsKey(0)||!nativeBorrowSites.getOrDefault(site.call(),Set.of()).contains(send))throw new IOException("Handler FIELD lacks its exact BODY checked borrow transport");
                    handlers.add(site);
                }
            }
            List<GlobalImport> globalImports=new ArrayList<>();List<GlobalAccess> globalAccesses=new ArrayList<>();
            if(schema>=7) {
                int frameLocal=runtimeGeneratedFrame(methodCode,pool);var declared=PhpOwnershipExecution.readFramePlan(bytes,method,descriptor);if(declared==null)throw new IOException("Globals require the actual CV frame");
                Set<Integer> imported=new HashSet<>(),accessIds=new HashSet<>();
                for(int i=0,n=runtimeCount(data);i<n;i++) {
                    GlobalImport item=new GlobalImport(data.readInt(),data.readInt(),data.readInt(),data.readInt(),data.readInt());
                    if(!imported.add(item.local()))throw new IOException("Duplicate literal GLOBALS import");validateRuntimeGlobalImport(methodCode,pool,item,frameLocal,declared);globalImports.add(item);
                }
                for(int i=0,n=runtimeCount(data);i<n;i++) {
                    GlobalAccess item=new GlobalAccess(data.readInt(),data.readInt(),data.readUTF(),data.readInt(),data.readInt(),data.readInt(),data.readInt(),data.readInt(),data.readInt(),data.readInt());
                    if(item.id()<4_000_000||!accessIds.add(item.id())||!imported.contains(item.root())||item.mode()<0||item.mode()>1||(item.sourceEval()<0)==(item.sourceLocal()<0))throw new IOException("Named global source or root is not exclusive and declared");
                    if(item.sourceEval()>=0&&producers.stream().noneMatch(producer->producer.id()==item.sourceEval()&&producer.consumers().contains("GLOBAL")))throw new IOException("Named global write lacks its actual producer consumer");
                    if(item.sourceLocal()>=0&&declared.locals().stream().noneMatch(local->local.id()==item.sourceLocal()&&!local.name().equals("GLOBALS")))throw new IOException("Named global source is not an actual local declaration");
                    validateRuntimeGlobalAccess(methodCode,pool,item,frameLocal,owner,method,descriptor,bytes,declared);globalAccesses.add(item);
                }
            }
            List<GlobalRead> globalReads=new ArrayList<>();List<GlobalUnset> globalUnsets=new ArrayList<>();
            if(schema>=8) {
                int frame=runtimeGeneratedFrame(methodCode,pool);var declared=PhpOwnershipExecution.readFramePlan(bytes,method,descriptor);Set<Integer> sites=new HashSet<>();
                for(GlobalAccess item:globalAccesses)sites.add(item.id());
                for(int i=0,n=runtimeCount(data);i<n;i++) {
                    GlobalRead item=new GlobalRead(data.readInt(),data.readInt(),data.readUTF(),data.readBoolean(),data.readInt(),data.readInt());
                    if(!sites.add(item.id())||globalImports.stream().noneMatch(value->value.local()==item.root())||producers.stream().noneMatch(value->value.id()==item.id()&&value.kind().equals("GLOBAL_RESULT")))throw new IOException("Named global READ lacks its declared root/RESULT");
                    validateRuntimeGlobalLifecycle(methodCode,pool,item.id(),item.root(),item.key(),item.cell(),item.helper(),item.read(),true,frame,owner,declared);globalReads.add(item);
                }
                for(int i=0,n=runtimeCount(data);i<n;i++) {
                    GlobalUnset item=new GlobalUnset(data.readInt(),data.readInt(),data.readUTF(),data.readInt(),data.readInt());
                    if(item.id()<4_000_000||!sites.add(item.id())||globalImports.stream().noneMatch(value->value.local()==item.root()))throw new IOException("Named global UNSET lacks its declared root/site");
                    validateRuntimeGlobalLifecycle(methodCode,pool,item.id(),item.root(),item.key(),false,item.helper(),item.read(),false,frame,owner,declared);globalUnsets.add(item);
                }
            }
            for(Producer producer:producers)if(producer.kind().equals("GLOBAL_RESULT")&&globalReads.stream().noneMatch(value->value.id()==producer.id()))throw new IOException("Named global RESULT lacks its actual READ protocol");
            List<GlobalScalar> globalScalars=new ArrayList<>();
            if(schema>=9)for(int i=0,n=runtimeCount(data);i<n;i++) {
                GlobalScalar scalar=new GlobalScalar(data.readInt(),data.readLong(),data.readInt());int at=scalar.helper(),frame=runtimeGeneratedFrame(methodCode,pool);var ops=methodCode.instructions();
                if(globalScalars.stream().anyMatch(value->value.id()==scalar.id())||producers.stream().noneMatch(value->value.id()==scalar.id()&&value.kind().equals("SCALAR_LITERAL")&&value.consumers().equals(Set.of("GLOBAL")))||globalAccesses.stream().noneMatch(value->value.sourceEval()==scalar.id()&&value.mode()==0))throw new IOException("Scalar global RHS lacks its actual producer and GLOBAL write");
                if(at<3||at>=ops.size()||ops.get(at-3).opcode()!=25||ops.get(at-3).operand()!=frame||runtimeSmallInt(ops.get(at-2),pool)!=scalar.id()||runtimeLongLiteral(ops.get(at-1),pool)!=scalar.value())throw new IOException("Scalar global RHS differs from its actual Frame/id/literal");
                runtimeMethod(ops.get(at),pool,184,"php/runtime/PhpRuntimeOwnership","globalScalar","(Lphp/runtime/PhpRuntimeOwnership$Frame;IJ)Lphp/runtime/Memory;");globalScalars.add(scalar);
            }
            for(Producer producer:producers)if(producer.kind().equals("SCALAR_LITERAL")&&globalScalars.stream().noneMatch(value->value.id()==producer.id()))throw new IOException("Scalar global RHS lacks its exact allocation/evaluation site");
            List<ArrayReference> arrayReferences=new ArrayList<>();
            if(schema>=10) {
                int frame=runtimeGeneratedFrame(methodCode,pool);var declared=PhpOwnershipExecution.readFramePlan(bytes,method,descriptor);var ops=methodCode.instructions();
                for(int i=0,n=runtimeCount(data);i<n;i++) {
                    ArrayReference item=new ArrayReference(data.readInt(),data.readInt(),data.readUTF(),data.readInt(),data.readInt());int at=item.helper();
                    if(arrayReferences.stream().anyMatch(value->value.id()==item.id())||producers.stream().noneMatch(value->value.id()==item.id()&&value.kind().equals("ARRAY_REF_RESULT"))||at<9||at>=ops.size()||item.read()!=at-6)throw new IOException("ARRAY reference lacks its actual result/site operands");
                    var local=declared.locals().stream().filter(value->value.id()==item.root()&&value.storage()==PhpOwnershipExecution.Storage.ADDRESS&&!value.name().equals("GLOBALS")).findFirst().orElseThrow(()->new IOException("ARRAY reference requires its actual addressable CV"));
                    runtimeMethod(ops.get(at),pool,184,"php/runtime/PhpOwnershipArrays","arrayReference","(Lphp/runtime/PhpRuntimeOwnership$Frame;IILphp/runtime/Memory;Ljava/lang/String;Lphp/runtime/env/Environment;Lphp/runtime/env/TraceInfo;)Lphp/runtime/Memory;");
                    if(ops.get(at-9).opcode()!=25||ops.get(at-9).operand()!=frame||runtimeSmallInt(ops.get(at-8),pool)!=item.id()||runtimeSmallInt(ops.get(at-7),pool)!=item.root()||ops.get(at-6).opcode()!=25||ops.get(at-6).operand()!=local.jvmSlot()||!item.key().equals(runtimeLiteralString(ops.get(at-5),pool))||ops.get(at-4).opcode()!=25||ops.get(at-4).operand()!=runtimeGeneratedEnvironment(methodCode,pool,frame)||ops.get(at-1).opcode()!=50)throw new IOException("ARRAY reference changed its actual CV/key/Frame/Environment");
                    runtimeMethod(ops.get(at-3),pool,178,owner,"$TRC","[Lphp/runtime/env/TraceInfo;");if(runtimeSmallInt(ops.get(at-2),pool)<0)throw new IOException("ARRAY reference trace is not generated");
                    arrayReferences.add(item);
                }
            }
            for(Producer producer:producers)if(producer.kind().equals("ARRAY_REF_RESULT")&&arrayReferences.stream().noneMatch(value->value.id()==producer.id()))throw new IOException("ARRAY reference RESULT lacks its exact helper proof");
            List<FunctionCall> functionCalls=new ArrayList<>();
            if(schema>=11) {
                int frame=runtimeGeneratedFrame(methodCode,pool);var declared=PhpOwnershipExecution.readFramePlan(bytes,method,descriptor);
                for(int i=0,n=runtimeCount(data);i<n;i++) {
                    FunctionCall site=new FunctionCall(data.readInt(),data.readUTF(),data.readUTF(),data.readUTF(),data.readUTF(),data.readInt(),data.readInt(),data.readInt(),data.readInt(),data.readInt(),data.readInt(),data.readInt(),data.readInt());
                    if(functionCalls.stream().anyMatch(value->value.id()==site.id())||producers.stream().noneMatch(value->value.id()==site.id()&&value.kind().equals("FUNCTION_RESULT"))||!site.descriptor().equals("(Lphp/runtime/env/Environment;[Lphp/runtime/Memory;)Lphp/runtime/Memory;")||site.owner().isEmpty()||site.member().isEmpty()||site.name().isEmpty())throw new IOException("FUNCTION result lacks its exact target dependency");
                    validateRuntimeFunctionCall(methodCode,pool,site,frame,declared);if(runtimeRequiresCellResult(methodCode,pool,site.id(),frame))throw new IOException("FUNCTION VALUE result needs its reference warning protocol");functionCalls.add(new FunctionCall(site.id(),site.name(),site.owner(),site.member(),site.descriptor(),site.local(),site.factory(),site.send(),site.read(),site.invoke(),site.start(),site.fallback(),site.end(),runtimeInvocationDiscarded(methodCode.instructions().get(site.invoke()),pool,"php/runtime/PhpRuntimeOwnership$FunctionPacket")));
                }
            }
            for(Producer producer:producers)if(producer.kind().equals("FUNCTION_RESULT")&&functionCalls.stream().noneMatch(value->value.id()==producer.id()))throw new IOException("FUNCTION RESULT lacks its actual CALL/SEND protocol");
            List<ArrayIncrement> arrayIncrements=new ArrayList<>();
            if(schema>=12) {
                int frame=runtimeGeneratedFrame(methodCode,pool);var declared=PhpOwnershipExecution.readFramePlan(bytes,method,descriptor);var ops=methodCode.instructions();
                for(int i=0,n=runtimeCount(data);i<n;i++) {
                    ArrayIncrement item=new ArrayIncrement(data.readInt(),data.readInt(),data.readUTF(),data.readBoolean(),data.readBoolean(),data.readInt(),data.readInt());int at=item.helper();
                    if(arrayIncrements.stream().anyMatch(value->value.id()==item.id())||producers.stream().noneMatch(value->value.id()==item.id()&&value.kind().equals("ARRAY_INCREMENT_RESULT"))||at<14||at>=ops.size()||item.read()!=at-11)throw new IOException("ARRAY increment lacks its actual result/site operands");
                    var local=declared.locals().stream().filter(value->value.id()==item.root()&&value.storage()==PhpOwnershipExecution.Storage.ADDRESS&&!value.name().equals("GLOBALS")).findFirst().orElseThrow(()->new IOException("ARRAY increment requires its actual addressable CV"));
                    runtimeMethod(ops.get(at),pool,184,"php/runtime/PhpArithmetic","arrayIncrement","(Lphp/runtime/PhpRuntimeOwnership$Frame;IILphp/runtime/Memory;Ljava/lang/String;ZZLphp/runtime/env/Environment;Lphp/runtime/env/TraceInfo;Lphp/runtime/env/TraceInfo;)Lphp/runtime/Memory;");
                    if(ops.get(at-14).opcode()!=25||ops.get(at-14).operand()!=frame||runtimeSmallInt(ops.get(at-13),pool)!=item.id()||runtimeSmallInt(ops.get(at-12),pool)!=item.root()||ops.get(at-11).opcode()!=25||ops.get(at-11).operand()!=local.jvmSlot()||!item.key().equals(runtimeLiteralString(ops.get(at-10),pool))||runtimeSmallInt(ops.get(at-9),pool)!=(item.decrement()?1:0)||runtimeSmallInt(ops.get(at-8),pool)!=(item.post()?1:0)||ops.get(at-7).opcode()!=25||ops.get(at-7).operand()!=runtimeGeneratedEnvironment(methodCode,pool,frame)||ops.get(at-4).opcode()!=50)throw new IOException("ARRAY increment changed its actual CV/key/opcode/Frame/Environment");
                    runtimeMethod(ops.get(at-6),pool,178,owner,"$TRC","[Lphp/runtime/env/TraceInfo;");if(runtimeSmallInt(ops.get(at-5),pool)<0||runtimeSmallInt(ops.get(at-2),pool)<0||ops.get(at-1).opcode()!=50)throw new IOException("ARRAY increment traces are not generated");
                    runtimeMethod(ops.get(at-3),pool,178,owner,"$TRC","[Lphp/runtime/env/TraceInfo;");arrayIncrements.add(item);
                }
            }
            for(Producer producer:producers)if(producer.kind().equals("ARRAY_INCREMENT_RESULT")&&arrayIncrements.stream().noneMatch(value->value.id()==producer.id()))throw new IOException("ARRAY increment RESULT lacks its exact helper proof");
            List<ObjectNew> objectNews=new ArrayList<>();
            if(schema>=13) {
                int frame=runtimeGeneratedFrame(methodCode,pool);var ops=methodCode.instructions();
                for(int i=0,n=runtimeCount(data);i<n;i++) {
                    ObjectNew item=new ObjectNew(data.readInt(),data.readUTF(),data.readUTF(),data.readUTF(),data.readInt(),data.readInt(),data.readInt(),data.readInt());int at=item.helper();
                    if(objectNews.stream().anyMatch(value->value.id()==item.id())||producers.stream().noneMatch(value->value.id()==item.id()&&value.kind().equals("OBJECT_NEW"))||item.name().isEmpty()||item.owner().isEmpty()||!item.fingerprint().matches("[0-9a-f]{64}")||at<5||at+2>=ops.size()||item.start()!=at-3||item.fallback()!=at+2||item.end()<=item.fallback()||item.end()>ops.size())throw new IOException("Object NEW lacks its exact literal producer/default proof");
                    runtimeMethod(ops.get(at),pool,182,"php/runtime/PhpRuntimeOwnership$Frame","objectNew","(ILjava/lang/String;)Lphp/runtime/Memory;");
                    if(ops.get(at-5).opcode()!=25||ops.get(at-5).operand()!=frame||ops.get(at-4).opcode()!=198||ops.get(at-4).operand()!=ops.get(item.fallback()).offset()||ops.get(at-3).opcode()!=25||ops.get(at-3).operand()!=frame||runtimeSmallInt(ops.get(at-2),pool)!=item.id()||!item.name().equals(runtimeLiteralString(ops.get(at-1),pool)))throw new IOException("Object NEW changed its actual Frame/class/allocation site");
                    int end=item.end()==ops.size()?ops.getLast().next():ops.get(item.end()).offset();var jump=ops.get(at+1);
                    if((jump.opcode()!=167&&jump.opcode()!=200)||jump.operand()!=end)throw new IOException("Object NEW lacks its exact passive branch join");
                    int first=ops.get(item.start()).offset(),last=ops.get(at+1).next();
                    for(int ordinal=0;ordinal<ops.size();ordinal++) {
                        var op=ops.get(ordinal);if(op.opcode()==170||op.opcode()==171||op.opcode()==168||op.opcode()==169||op.opcode()==201)throw new IOException("Object NEW requires its direct closed control flow");
                        if(ordinal>=item.start()&&ordinal<=at+1||ordinal==at-4)continue;
                        if((op.opcode()>=153&&op.opcode()<=167||op.opcode()==198||op.opcode()==199||op.opcode()==200)&&op.operand()>=first&&op.operand()<last)throw new IOException("Object NEW has an unrelated incoming branch");
                    }
                    for(var guard:methodCode.guards())if(guard.handler()>=first&&guard.handler()<last)throw new IOException("Object NEW has an unrelated handler entry");
                    if(runtimeRequiresCellResult(methodCode,pool,item.id(),frame))throw new IOException("Object NEW VALUE result needs its reference warning protocol");objectNews.add(item);
                }
            }
            for(Producer producer:producers)if(producer.kind().equals("OBJECT_NEW")&&objectNews.stream().noneMatch(value->value.id()==producer.id()))throw new IOException("Object NEW producer lacks its actual loaded-shell/default protocol");
            List<ObjectCv> objectCvs=new ArrayList<>();List<PropertyReference> propertyReferences=new ArrayList<>();
            if(schema>=14) {
                int frame=runtimeGeneratedFrame(methodCode,pool);var ops=methodCode.instructions();var declared=PhpOwnershipExecution.readFramePlan(bytes,method,descriptor);
                for(int i=0,n=runtimeCount(data);i<n;i++) {
                    ObjectCv cv=new ObjectCv(data.readInt(),data.readInt(),data.readUTF(),data.readInt());int at=cv.publication();
                    ObjectNew source=objectNews.stream().filter(value->value.id()==cv.producer()&&value.owner().equals(cv.owner())).findFirst().orElseThrow(()->new IOException("Object CV has no exact NEW origin"));
                    if(objectCvs.stream().anyMatch(value->value.producer()==cv.producer()&&value.local()==cv.local())||at<8||at>=ops.size()||source.end()>at-8||producers.stream().noneMatch(value->value.id()==cv.producer()&&value.consumers().contains("CV")))throw new IOException("Object CV lacks its prior actual producer publication");
                    declared.locals().stream().filter(value->value.id()==cv.local()&&value.storage()==PhpOwnershipExecution.Storage.ADDRESS).findFirst().orElseThrow(()->new IOException("Object CV is not an addressed local"));
                    runtimeMethod(ops.get(at),pool,182,"php/runtime/PhpRuntimeOwnership$Frame","assignLocalFromEval","(IILphp/runtime/Memory;I)Lphp/runtime/Memory;");
                    if(ops.get(at-8).opcode()!=58||ops.get(at-7).opcode()!=25||ops.get(at-7).operand()!=frame||ops.get(at-6).opcode()!=198||ops.get(at-5).opcode()!=25||ops.get(at-5).operand()!=frame||runtimeSmallInt(ops.get(at-4),pool)!=cv.producer()||runtimeSmallInt(ops.get(at-3),pool)!=cv.local()||ops.get(at-2).opcode()!=25||ops.get(at-2).operand()!=ops.get(at-8).operand()||runtimeSmallInt(ops.get(at-1),pool)!=0)throw new IOException("Object CV changed its actual producer/local/Frame operands");
                    if(at+2>=ops.size()||ops.get(at-6).operand()!=ops.get(at+2).offset()||ops.get(at+1).opcode()!=167||ops.get(at+1).operand()<=ops.get(at+2).offset())throw new IOException("Object CV changed its exact null-Frame fallback/join");
                    for(int op=source.end();op<at-8;op++) {
                        if(ops.get(op).opcode()==89) {
                            if(op!=source.end()||op+8>at-8)throw new IOException("Object CV observer escapes its exact NEW/CV interval");
                            validateRuntimeTypedCreated(bytes,owner,method,descriptor,methodCode,pool,op,source,cv);op+=7;
                        }else runtimeMethod(ops.get(op),pool,182,"php/runtime/Memory","toImmutable","()Lphp/runtime/Memory;");
                    }
                    objectCvs.add(cv);
                }
                for(int i=0,n=runtimeCount(data);i<n;i++) {
                    PropertyReference item=new PropertyReference(data.readInt(),data.readInt(),data.readInt(),data.readUTF(),data.readInt(),data.readInt(),data.readInt(),data.readInt());int at=item.helper();
                    ObjectCv source=objectCvs.stream().filter(value->value.producer()==item.producer()&&value.local()==item.root()&&value.publication()<item.read()).findFirst().orElseThrow(()->new IOException("Property reference lacks its earlier actual Object CV"));
                    var local=declared.locals().stream().filter(value->value.id()==item.root()&&value.storage()==PhpOwnershipExecution.Storage.ADDRESS&&!value.name().equals("GLOBALS")).findFirst().orElseThrow(()->new IOException("Property reference requires an exact addressed receiver"));
                    if(propertyReferences.stream().anyMatch(value->value.id()==item.id())||producers.stream().noneMatch(value->value.id()==item.id()&&value.kind().equals("PROPERTY_REF_RESULT"))||item.key().isEmpty()||at<9||at>=ops.size()||item.read()!=at-6)throw new IOException("Property reference lacks its actual FIELD result operands");
                    runtimeMethod(ops.get(at),pool,184,"php/runtime/PhpRuntimeOwnership","propertyReference","(Lphp/runtime/PhpRuntimeOwnership$Frame;IILphp/runtime/Memory;Ljava/lang/String;Lphp/runtime/env/Environment;Lphp/runtime/env/TraceInfo;)Lphp/runtime/Memory;");
                    if(ops.get(at-9).opcode()!=25||ops.get(at-9).operand()!=frame||runtimeSmallInt(ops.get(at-8),pool)!=item.id()||runtimeSmallInt(ops.get(at-7),pool)!=item.root()||ops.get(at-6).opcode()!=25||ops.get(at-6).operand()!=local.jvmSlot()||!item.key().equals(runtimeLiteralString(ops.get(at-5),pool))||ops.get(at-4).opcode()!=25||ops.get(at-4).operand()!=runtimeGeneratedEnvironment(methodCode,pool,frame)||ops.get(at-1).opcode()!=50||runtimeSmallInt(ops.get(at-2),pool)<0)throw new IOException("Property reference changed its actual Frame/site/receiver/FIELD/Environment");
                    runtimeMethod(ops.get(at-3),pool,178,owner,"$TRC","[Lphp/runtime/env/TraceInfo;");
                    int sourceJoin=-1,joinOffset=ops.get(source.publication()+1).operand();
                    for(int op=source.publication()+2;op<=item.read();op++)if(ops.get(op).offset()==joinOffset){sourceJoin=op;break;}
                    if(sourceJoin<0)throw new IOException("Property source CV has no exact prior ordinary join");
                    for(int op=sourceJoin;op<item.read();op++) {
                        if(ops.get(op).opcode()==58&&ops.get(op).operand()==local.jvmSlot()||runtimeMutatesLocal(ops,pool,op,item.root()))throw new IOException("Property receiver was changed after its attested publication");
                    }
                    int result=item.resultPublication();
                    int exported=validateRuntimeFieldExport(cellPayload,owner,method,descriptor,methodCode,pool,declared,at+1);
                    if(result!=exported+9||result+2>=ops.size()||producers.stream().noneMatch(value->value.id()==item.id()&&value.consumers().equals(Set.of("CV"))))throw new IOException("FIELD result lacks its immediate actual CELL/CV consumer");
                    declared.locals().stream().filter(value->value.id()==item.resultLocal()&&value.storage()==PhpOwnershipExecution.Storage.ADDRESS).findFirst().orElseThrow(()->new IOException("FIELD result needs its actual addressed reference CV"));
                    
                    runtimeMethod(ops.get(result),pool,182,"php/runtime/PhpRuntimeOwnership$Frame","assignLocalFromEval","(IILphp/runtime/Memory;I)Lphp/runtime/Memory;");
                    if(ops.get(result-8).opcode()!=58||ops.get(result-7).opcode()!=25||ops.get(result-7).operand()!=frame||ops.get(result-6).opcode()!=198||ops.get(result-6).operand()!=ops.get(result+2).offset()||ops.get(result-5).opcode()!=25||ops.get(result-5).operand()!=frame||runtimeSmallInt(ops.get(result-4),pool)!=item.id()||runtimeSmallInt(ops.get(result-3),pool)!=item.resultLocal()||ops.get(result-2).opcode()!=25||ops.get(result-2).operand()!=ops.get(result-8).operand()||runtimeSmallInt(ops.get(result-1),pool)!=1||ops.get(result+1).opcode()!=167||ops.get(result+1).operand()<=ops.get(result+2).offset())throw new IOException("FIELD result changed its actual CELL producer/CV consumer/fallback");
                    propertyReferences.add(item);
                }
            }
            List<ConditionalSite> conditionals=new ArrayList<>();Set<Integer> quietGlobalReads=new LinkedHashSet<>();List<ConditionalLocal> conditionalLocals=new ArrayList<>();
            if(schema>=15) {
                int frame=runtimeGeneratedFrame(methodCode,pool);
                for(int i=0,n=runtimeCount(data);i<n;i++) {
                    ConditionalSite site=new ConditionalSite(data.readInt(),data.readInt(),data.readInt(),data.readBoolean(),data.readInt(),data.readInt(),data.readInt(),data.readInt(),data.readInt(),data.readInt(),data.readInt(),data.readInt(),data.readInt(),data.readInt(),data.readUTF(),data.readUTF());
                    if(conditionals.stream().anyMatch(value->value.id()==site.id()))throw new IOException("Duplicate actual conditional join");
                    validateRuntimeConditional(methodCode,pool,site,frame,producers,globalReads);conditionals.add(site);
                }
                for(int i=0,n=runtimeCount(data);i<n;i++) {
                    int id=data.readInt();GlobalRead read=globalReads.stream().filter(value->value.id()==id&&!value.cell()).findFirst().orElseThrow(()->new IOException("Quiet READ has no actual named VALUE fetch"));
                    if(!quietGlobalReads.add(id)||conditionals.stream().noneMatch(value->value.source()==id&&value.kind()==0))throw new IOException("Quiet READ is not the actual coalesce test source");
                    runtimeMethod(methodCode.instructions().get(read.helper()),pool,184,"php/runtime/PhpRuntimeOwnership","globalReadQuiet","(Lphp/runtime/PhpRuntimeOwnership$Frame;IILphp/runtime/Memory;Ljava/lang/String;ZLphp/runtime/env/Environment;Lphp/runtime/env/TraceInfo;)Lphp/runtime/Memory;");
                }
            }
            if(schema>=15) {
                var ops=methodCode.instructions();Set<String> inputs=new HashSet<>();Set<Integer> results=new HashSet<>();
                int frameLocal=runtimeGeneratedFrame(methodCode,pool);var declaredFrame=PhpOwnershipExecution.readFramePlan(bytes,method,descriptor);
                for(int i=0,n=runtimeCount(data);i<n;i++) {
                    int id=data.readInt(),index=data.readInt(),borrow=data.readInt(),conversion=data.readInt(),last=data.readInt();
                    NativeMember member=nativeMembers.stream().filter(value->value.id()==id).findFirst().orElseThrow(()->new IOException("Scalar conversion has no selected native member"));
                    List<String> arguments=runtimeNativeArguments(member);String type=index<0||index>=arguments.size()?"":arguments.get(index);
                    if(!inputs.add(id+":"+index)||!Set.of("I","Ljava/lang/String;").contains(type)||borrow<9||conversion<=borrow||last>=nativeBodyEnters.getOrDefault(id,-1)||!nativeBorrowSites.getOrDefault(id,Set.of()).contains(borrow))throw new IOException("Native scalar conversion escapes its exact SEND/BODY");
                    runtimeMethod(ops.get(borrow),pool,184,"php/runtime/PhpRuntimeOwnership","nativeBorrowSend","(Lphp/runtime/PhpRuntimeOwnership$NativePacket;ILphp/runtime/Memory;IZZ)Lphp/runtime/Memory;");
                    if(ops.get(borrow-7).opcode()!=58||ops.get(borrow-6).opcode()!=25||ops.get(borrow-6).operand()!=ops.get(nativeBodyEnters.get(id)-1).operand()||ops.get(borrow-4).opcode()!=25||ops.get(borrow-4).operand()!=ops.get(borrow-7).operand()||runtimeSmallInt(ops.get(borrow-3),pool)!=-1)throw new IOException("Scalar native input lost its exact constant transport");
                    runtimeMethod(ops.get(borrow-8),pool,184,type.equals("I")?"php/runtime/memory/LongMemory":"php/runtime/memory/StringMemory","valueOf",type.equals("I")?"(J)Lphp/runtime/Memory;":"(Ljava/lang/String;)Lphp/runtime/Memory;");
                    if(type.equals("I"))runtimeLongLiteral(ops.get(borrow-9),pool);else runtimeLiteralString(ops.get(borrow-9),pool);
                    if(runtimeSmallInt(ops.get(borrow-5),pool)!=index||conversion>borrow+2)throw new IOException("Scalar conversion changed its actual argument");
                    if(conversion==borrow+2)runtimeMethod(ops.get(borrow+1),pool,182,"php/runtime/Memory","toValue","()Lphp/runtime/Memory;");
                    if(type.equals("I")) {
                        runtimeMethod(ops.get(conversion),pool,182,"php/runtime/Memory","toLong","()J");
                        if(last!=conversion+1||ops.get(last).opcode()!=136)throw new IOException("Native int argument changed its exact L2I narrowing");
                    }else {
                        runtimeMethod(ops.get(conversion),pool,182,"php/runtime/Memory","toString","()Ljava/lang/String;");
                        if(last!=conversion)throw new IOException("Native STRING argument changed its exact conversion");
                    }
                }
                for(int i=0,n=runtimeCount(data);i<n;i++) {
                    int id=data.readInt(),box=data.readInt(),result=data.readInt();NativeMember member=nativeMembers.stream().filter(value->value.id()==id).findFirst().orElseThrow(()->new IOException("Scalar result has no selected native member"));
                    String type=member.descriptor().substring(member.descriptor().indexOf(')')+1);
                    if(!results.add(id)||!Set.of("I","J","Z").contains(type)||box<0||result!=box+5||result>=ops.size()||ops.get(box).offset()!=nativeSuccessJoins.getOrDefault(id,-1))throw new IOException("Scalar result escapes its exact BODY success join");
                    runtimeMethod(ops.get(box),pool,184,type.equals("Z")?"php/runtime/memory/TrueMemory":"php/runtime/memory/LongMemory","valueOf","("+type+")Lphp/runtime/Memory;");
                    runtimeMethod(ops.get(result),pool,184,"php/runtime/PhpRuntimeOwnership","nativeResult","(Lphp/runtime/PhpRuntimeOwnership$NativePacket;Lphp/runtime/Memory;I)Lphp/runtime/Memory;");
                    if(ops.get(box+1).opcode()!=58||ops.get(box+2).opcode()!=25||ops.get(box+2).operand()!=ops.get(nativeBodyEnters.get(id)-1).operand()||ops.get(box+3).opcode()!=25||ops.get(box+3).operand()!=ops.get(box+1).operand()||runtimeSmallInt(ops.get(box+4),pool)!=id)throw new IOException("Scalar result lost its actual boxed candidate/result ID");
                }
                for(NativeMember member:nativeMembers) {
                    List<String> arguments=runtimeNativeArguments(member);
                    for(int i=0;i<arguments.size();i++)if(Set.of("I","Ljava/lang/String;").contains(arguments.get(i))&&!inputs.contains(member.id()+":"+i))throw new IOException("Native scalar SEND lacks its exact selected conversion");
                    String type=member.descriptor().substring(member.descriptor().indexOf(')')+1);
                    if(Set.of("I","J","Z").contains(type)&&!results.contains(member.id()))throw new IOException("Native scalar RESULT lacks its exact BODY boxing");
                }
                Set<Integer> localIds=new HashSet<>();
                for(int i=0,n=runtimeCount(data);i<n;i++) {
                    ConditionalLocal item=new ConditionalLocal(data.readInt(),data.readInt(),data.readInt(),data.readInt());
                    if(declaredFrame==null)throw new IOException("Conditional CV read has no actual frame declaration");
                    var local=declaredFrame.locals().stream().filter(value->value.id()==item.local()).findFirst().orElseThrow(()->new IOException("Conditional local read has no declared CV"));
                    int at=item.helper();
                    if(!localIds.add(item.id())||local.storage()!=PhpOwnershipExecution.Storage.ADDRESS||globalImports.stream().anyMatch(value->value.local()==item.local())||at<4||at>=ops.size()||item.read()!=at-1||ops.get(at-4).opcode()!=25||ops.get(at-4).operand()!=frameLocal||runtimeSmallInt(ops.get(at-3),pool)!=item.id()||runtimeSmallInt(ops.get(at-2),pool)!=item.local()||ops.get(at-1).opcode()!=25||ops.get(at-1).operand()!=local.jvmSlot())throw new IOException("Conditional CV read changed its actual address/Frame/source");
                    runtimeMethod(ops.get(at),pool,184,"php/runtime/PhpRuntimeOwnership","conditionalLocal","(Lphp/runtime/PhpRuntimeOwnership$Frame;IILphp/runtime/Memory;)Lphp/runtime/Memory;");
                    if(producers.stream().noneMatch(value->value.id()==item.id()&&value.kind().equals("CV_READ_RESULT")&&value.consumers().equals(Set.of("JOIN")))||conditionals.stream().noneMatch(value->value.source()==item.id()&&(value.test()-8==at||value.test()-9==at)))throw new IOException("Conditional CV read lacks its exact test EVAL consumer");
                    conditionalLocals.add(item);
                }
            }
            boolean lifecycle=false;List<LifecycleReceiver> lifecycleReceivers=new ArrayList<>();List<LifecycleDiscard> lifecycleDiscards=new ArrayList<>();
            if(schema>=16) {
                lifecycle=data.readBoolean();if(schema==16&&!lifecycle||methodCode==null)throw new IOException("Lifecycle schema lacks its actual entry body");var declared=PhpOwnershipExecution.readFramePlan(bytes,method,descriptor);
                if(lifecycle&&(declared==null||declared.entryKind()!=PhpOwnershipExecution.EntryKind.METHOD||(methodAccess&8)!=0||!method.matches("(?i)__destruct\\$[0-9]+")||!descriptor.equals("(Lphp/runtime/env/Environment;[Lphp/runtime/Memory;)Lphp/runtime/Memory;")))throw new IOException("Lifecycle entry is not an actual instance destructor body");
                Set<Integer> receiverIds=new HashSet<>();var ops=methodCode.instructions();int frame=runtimeGeneratedFrame(methodCode,pool);
                if(lifecycle&&ops.stream().anyMatch(op->op.opcode()==168||op.opcode()==169||op.opcode()==201||op.opcode()==196))throw new IOException("Lifecycle entry requires its closed direct control-flow protocol");
                for(int i=0,n=runtimeCount(data);i<n;i++) {
                    LifecycleReceiver item=new LifecycleReceiver(data.readInt(),data.readInt(),data.readInt(),data.readInt(),data.readInt(),data.readInt(),data.readInt(),data.readInt());
                    if(!lifecycle||!receiverIds.add(item.local()))throw new IOException("Receiver import lacks its lifecycle entry");
                    var local=declared.locals().stream().filter(value->value.id()==item.local()).findFirst().orElseThrow(()->new IOException("Lifecycle receiver has no declared CV"));
                    int at=item.helper();
                    if(!local.name().equals("this")||local.storage()!=PhpOwnershipExecution.Storage.ADDRESS||local.importKind()!=PhpOwnershipExecution.ImportKind.NONE||at<6||at+3>=ops.size()||item.start()!=at-6||item.read()!=at-2||item.boxing()!=at-1||item.store()!=at+1||item.fallback()!=at+3||item.end()<=item.fallback()||item.end()>=ops.size())throw new IOException("Lifecycle receiver escapes its exact import guard");
                    if(ops.get(at-6).opcode()!=25||ops.get(at-6).operand()!=frame||ops.get(at-5).opcode()!=198||ops.get(at-5).operand()!=ops.get(item.fallback()).offset()||ops.get(at-4).opcode()!=25||ops.get(at-4).operand()!=frame||runtimeSmallInt(ops.get(at-3),pool)!=item.local()||ops.get(at-2).opcode()!=25||ops.get(at-2).operand()!=0||ops.get(at+1).opcode()!=58||ops.get(at+1).operand()!=local.jvmSlot()||ops.get(at+2).opcode()!=167||ops.get(at+2).operand()!=ops.get(item.end()).offset())throw new IOException("Lifecycle receiver Frame/source/store operands changed");
                    runtimeMethod(ops.get(at-1),pool,184,"php/runtime/memory/ObjectMemory","valueOf","(Lphp/runtime/lang/IObject;)Lphp/runtime/Memory;");
                    runtimeMethod(ops.get(at),pool,182,"php/runtime/PhpRuntimeOwnership$Frame","importLifecycleReceiver","(ILphp/runtime/Memory;)Lphp/runtime/Memory;");
                    int first=ops.get(item.start()).offset(),passive=ops.get(item.fallback()).offset(),end=ops.get(item.end()).offset();
                    for(int position=0;position<ops.size();position++) {
                        boolean insidePassive=position>=item.fallback()&&position<item.end();
                        for(int destination:runtimeFlowTargets(methodCode,position)) {
                            if(destination>=first&&destination<passive)throw new IOException("Lifecycle receiver import has an external or repeated entry");
                            if(destination>=passive&&destination<end&&position!=at-5&&!insidePassive)throw new IOException("Lifecycle receiver fallback has an external active entry");
                            if(insidePassive&&(destination<passive||destination>end))throw new IOException("Lifecycle receiver fallback escapes its exact join");
                        }
                    }
                    for(var guard:methodCode.guards())if(guard.handler()>=first&&guard.handler()<end)throw new IOException("Lifecycle receiver import/fallback has an external exception entry");
                    lifecycleReceivers.add(item);
                }
                Set<Integer> discards=new HashSet<>();
                for(int i=0,n=runtimeCount(data);i<n;i++) {
                    LifecycleDiscard item=new LifecycleDiscard(data.readInt(),data.readInt(),data.readInt(),data.readInt(),data.readInt());
                    LifecycleReceiver imported=lifecycleReceivers.stream().filter(value->value.local()==item.local()).findFirst().orElseThrow(()->new IOException("Discard lacks its actual receiver import"));
                    var local=declared.locals().stream().filter(value->value.id()==item.local()).findFirst().orElseThrow();int at=item.read();
                    if(!discards.add(at)||at<imported.end()||at+12>=ops.size()||item.branch()!=at+3||item.exception()!=at+11||item.pop()!=at+12||ops.get(at).opcode()!=25||ops.get(at).operand()!=local.jvmSlot()||ops.get(at+1).opcode()!=89||ops.get(at+3).opcode()!=153||ops.get(at+3).operand()!=ops.get(at+12).offset()||ops.get(at+4).opcode()!=25||ops.get(at+4).operand()!=1||runtimeSmallInt(ops.get(at+6),pool)<0||ops.get(at+7).opcode()!=50||ops.get(at+8).opcode()!=18||!runtimeClass(pool,ops.get(at+8).operand()).equals("php/runtime/lang/exception/BaseError")||!runtimeLiteralString(ops.get(at+9),pool).equals("Using $this when not in object context")||ops.get(at+10).opcode()!=1||ops.get(at+12).opcode()!=87)throw new IOException("Lifecycle receiver discard changed its exact null-only guard");
                    runtimeMethod(ops.get(at+2),pool,182,"php/runtime/Memory","isNull","()Z");runtimeMethod(ops.get(at+5),pool,178,owner,"$TRC","[Lphp/runtime/env/TraceInfo;");runtimeMethod(ops.get(at+11),pool,182,"php/runtime/env/Environment","exception","(Lphp/runtime/env/TraceInfo;Ljava/lang/Class;Ljava/lang/String;[Ljava/lang/Object;)V");
                    int first=ops.get(at).offset(),last=ops.get(at+12).offset();
                    boolean tickJoin=runtimeLifecycleTickJoin(methodCode,pool,owner,imported,at);
                    for(int position=0;position<ops.size();position++)if(position!=at+3)for(int destination:runtimeFlowTargets(methodCode,position))if(destination>=first&&destination<=last) {
                        boolean importJoin=imported.end()==at&&destination==first&&(ops.get(position).opcode()==167||ops.get(position).opcode()==200)
                                &&(position==imported.helper()+2||(position>=imported.fallback()&&position<imported.end()));
                        if(!importJoin&&!(tickJoin&&position==at-2&&destination==first))throw new IOException("Lifecycle discard has an external guard bypass");
                    }
                    for(var guard:methodCode.guards())if(guard.handler()>=first&&guard.handler()<=last)throw new IOException("Lifecycle discard has an external exception entry");
                    lifecycleDiscards.add(item);
                }
                if(lifecycle) {
                    for(var local:declared.locals())if(local.name().equals("this")&&lifecycleReceivers.stream().noneMatch(value->value.local()==local.id()))throw new IOException("Lifecycle self CV lacks its actual borrowed import");
                    for(int at=0;at<ops.size();at++)if(ops.get(at).opcode()==25||ops.get(at).opcode()==58) {
                        final int position=at;
                        if(lifecycleReceivers.stream().anyMatch(value->position>=value.fallback()&&position<value.end()))continue;
                        if(ops.get(at).operand()==0&&(ops.get(at).opcode()!=25||lifecycleReceivers.stream().noneMatch(value->value.read()==position)))throw new IOException("Lifecycle receiver escapes its nonowning import");
                        for(var local:declared.locals())if(local.name().equals("this")&&ops.get(at).operand()==local.jvmSlot()) {
                            LifecycleReceiver imported=lifecycleReceivers.stream().filter(value->value.local()==local.id()).findFirst().orElseThrow();
                            if(ops.get(at).opcode()==58&&at==imported.store())continue;
                            if(ops.get(at).opcode()==25&&(at>=imported.end()&&at+1<ops.size()&&ops.get(at+1).opcode()==87||lifecycleDiscards.stream().anyMatch(value->value.local()==local.id()&&value.read()==position)))continue;
                            throw new IOException("Lifecycle receiver read/write requires its exact borrowed consumer protocol");
                        }
                    }
                }
            }
            List<GlobalNull> globalNulls=new ArrayList<>();
            if(schema>=17)for(int i=0,n=runtimeCount(data);i<n;i++) {
                GlobalNull item=new GlobalNull(data.readInt(),data.readInt());int at=item.helper(),frame=runtimeGeneratedFrame(methodCode,pool);var ops=methodCode.instructions();
                if(globalNulls.stream().anyMatch(value->value.id()==item.id())||producers.stream().noneMatch(value->value.id()==item.id()&&value.kind().equals("NULL_LITERAL")&&value.consumers().equals(Set.of("GLOBAL"))))throw new IOException("NULL global RHS lacks its exact producer/GLOBAL consumer");
                List<GlobalAccess> accesses=globalAccesses.stream().filter(value->value.sourceEval()==item.id()).toList();
                if(accesses.size()!=1||accesses.get(0).mode()!=0||accesses.get(0).sourceLocal()!=-1||accesses.get(0).index()!=at-3||accesses.get(0).assignment()!=at+5)throw new IOException("NULL global RHS lacks its adjacent actual named VALUE publication");
                if(at<3||at+5>=ops.size()||ops.get(at-2).opcode()!=25||ops.get(at-2).operand()!=frame||runtimeSmallInt(ops.get(at-1),pool)!=item.id())throw new IOException("NULL global RHS changed its actual Frame/id operands");
                runtimeMethod(ops.get(at),pool,184,"php/runtime/PhpRuntimeOwnership","globalNull","(Lphp/runtime/PhpRuntimeOwnership$Frame;I)Lphp/runtime/Memory;");globalNulls.add(item);
            }
            if(schema==17&&globalNulls.isEmpty())throw new IOException("NULL schema lacks its actual literal producer");
            for(Producer producer:producers)if(producer.kind().equals("NULL_LITERAL")&&globalNulls.stream().noneMatch(value->value.id()==producer.id()))throw new IOException("NULL global RHS lacks its actual literal allocation/EVAL site");
            List<EntryScalar> entryScalars=new ArrayList<>();
            if(schema>=18)for(int i=0,n=runtimeCount(data);i<n;i++) {
                EntryScalar item=new EntryScalar(data.readInt(),data.readLong(),data.readInt());int at=item.helper(),frame=runtimeGeneratedFrame(methodCode,pool);var ops=methodCode.instructions();
                if(entryScalars.stream().anyMatch(value->value.id()==item.id())||producers.stream().noneMatch(value->value.id()==item.id()&&value.kind().equals("ENTRY_SCALAR_LITERAL")&&value.consumers().equals(Set.of("ENTRY"))))throw new IOException("Scalar ENTRY RHS lacks its exact producer/ENTRY consumer");
                List<ArrayEntrySite> entries=arrayEntries.stream().filter(value->value.producer()==item.id()).toList();
                if(entries.size()!=1||entries.get(0).assignment()!=at+3||entries.get(0).step()>=at-3)throw new IOException("Scalar ENTRY RHS lacks its adjacent actual VALUE publication");
                if(at<3||at+3>=ops.size()||ops.get(at-3).opcode()!=25||ops.get(at-3).operand()!=frame||runtimeSmallInt(ops.get(at-2),pool)!=item.id()||runtimeLongLiteral(ops.get(at-1),pool)!=item.value())throw new IOException("Scalar ENTRY RHS changed its actual Frame/id/literal operands");
                runtimeMethod(ops.get(at),pool,184,"php/runtime/PhpRuntimeOwnership","entryScalar","(Lphp/runtime/PhpRuntimeOwnership$Frame;IJ)Lphp/runtime/Memory;");
                int first=ops.get(at-3).offset(),end=ops.get(at+3).offset();
                for(int ordinal=0;ordinal<ops.size();ordinal++)for(int destination:runtimeFlowTargets(methodCode,ordinal))if(destination>=first&&destination<=end)throw new IOException("Scalar ENTRY RHS has a nonsequential producer/publication entry");
                for(var guard:methodCode.guards())if(guard.handler()>=first&&guard.handler()<=end)throw new IOException("Scalar ENTRY RHS has an exception publication entry");
                entryScalars.add(item);
            }
            if(schema==18&&entryScalars.isEmpty())throw new IOException("Scalar ENTRY schema lacks its actual literal producer");
            for(Producer producer:producers)if(producer.kind().equals("ENTRY_SCALAR_LITERAL")&&entryScalars.stream().noneMatch(value->value.id()==producer.id()))throw new IOException("Scalar ENTRY RHS lacks its actual literal allocation/EVAL site");
            List<ObjectCast> objectCasts=new ArrayList<>();
            if(schema>=19)for(int i=0,n=runtimeCount(data);i<n;i++) {
                ObjectCast item=new ObjectCast(data.readInt(),data.readInt(),data.readInt());int at=item.helper(),frame=runtimeGeneratedFrame(methodCode,pool);var ops=methodCode.instructions();
                if(item.id()==item.source()||objectCasts.stream().anyMatch(value->value.id()==item.id()||value.source()==item.source())||producers.stream().noneMatch(value->value.id()==item.id()&&value.kind().equals("OBJECT_CAST_RESULT"))||producers.stream().noneMatch(value->value.id()==item.source()&&value.kind().equals("ARRAY_LITERAL")&&value.consumers().equals(Set.of("CAST"))))throw new IOException("OBJECT cast lacks its exact ARRAY producer/CAST consumer");
                if(at<4||at>=ops.size()||ops.get(at-4).opcode()!=25||ops.get(at-4).operand()!=frame||runtimeSmallInt(ops.get(at-3),pool)!=item.id()||runtimeSmallInt(ops.get(at-2),pool)!=item.source()||ops.get(at-1).opcode()!=25||ops.get(at-1).operand()!=runtimeGeneratedEnvironment(methodCode,pool,frame))throw new IOException("OBJECT cast changed its actual Frame/result/source/Environment operands");
                runtimeMethod(ops.get(at),pool,184,"php/runtime/PhpRuntimeOwnership","objectCast","(Lphp/runtime/Memory;Lphp/runtime/PhpRuntimeOwnership$Frame;IILphp/runtime/env/Environment;)Lphp/runtime/Memory;");
                int first=ops.get(at-4).offset(),end=ops.get(at).offset();
                for(int ordinal=0;ordinal<ops.size();ordinal++)for(int destination:runtimeFlowTargets(methodCode,ordinal))if(destination>=first&&destination<=end&&!(destination==first&&runtimeLiteralReleaseJoin(methodCode,pool,ordinal,at-4,frame,producers)))throw new IOException("OBJECT cast has a nonsequential producer entry");
                for(var guard:methodCode.guards())if(guard.handler()>=first&&guard.handler()<=end)throw new IOException("OBJECT cast has an exception producer entry");
                objectCasts.add(item);
            }
            if(schema==19&&objectCasts.isEmpty())throw new IOException("OBJECT cast schema lacks its actual producer");
            for(Producer producer:producers)if(producer.kind().equals("OBJECT_CAST_RESULT")&&objectCasts.stream().noneMatch(value->value.id()==producer.id())||producer.consumers().contains("CAST")&&objectCasts.stream().noneMatch(value->value.source()==producer.id()))throw new IOException("OBJECT cast producer/consumer lacks its actual byte-bound site");
            List<LiteralEntry> literalEntries=new ArrayList<>();
            if(schema>=20)for(int i=0,n=runtimeCount(data);i<n;i++) {
                LiteralEntry item=new LiteralEntry(data.readInt(),data.readInt(),data.readInt());int at=item.helper(),wrapper=item.wrapper(),frame=runtimeGeneratedFrame(methodCode,pool);var ops=methodCode.instructions();
                if(producers.stream().noneMatch(value->value.id()==item.parent()&&value.kind().equals("ARRAY_LITERAL"))||literalEntries.stream().anyMatch(value->value.wrapper()==wrapper||value.helper()==at))throw new IOException("Keyed literal lacks its unique actual ARRAY producer/publication");
                if(at<7||at>=ops.size()||wrapper<0||wrapper>=at||ops.get(at-6).opcode()!=25||ops.get(at-6).operand()!=runtimeGeneratedEnvironment(methodCode,pool,frame)||ops.get(at-3).opcode()!=50||ops.get(at-2).opcode()!=25||ops.get(at-2).operand()!=frame||runtimeSmallInt(ops.get(at-1),pool)!=item.parent()||runtimeSmallInt(ops.get(at-4),pool)<0)throw new IOException("Keyed literal changed its actual Environment/trace/Frame/ARRAY operands");
                var trace=ops.get(at-5);if(trace.opcode()!=178||trace.operand()<=0||trace.operand()>=pool.length||!(pool[trace.operand()] instanceof RuntimePair traceRef)||traceRef.second()<=0||traceRef.second()>=pool.length||!(pool[traceRef.second()] instanceof RuntimePair traceType))throw new IOException("Keyed literal lacks its actual trace array");
                if(!runtimeClass(pool,traceRef.first()).equals(owner)||!runtimeUtf(pool,traceType.first()).equals("$TRC")||!runtimeUtf(pool,traceType.second()).equals("[Lphp/runtime/env/TraceInfo;"))throw new IOException("Keyed literal changed its actual trace owner");
                runtimeMethod(ops.get(wrapper),pool,184,"php/runtime/memory/KeyValueMemory","valueOf","(Lphp/runtime/Memory;Lphp/runtime/Memory;)Lphp/runtime/Memory;");
                if(wrapper==at-8)runtimeMethod(ops.get(wrapper+1),pool,182,"php/runtime/Memory","toImmutable","()Lphp/runtime/Memory;");else if(wrapper!=at-7)throw new IOException("Keyed literal wrapper is not its adjacent actual publication operand");
                runtimeMethod(ops.get(at),pool,184,"php/runtime/PhpRuntimeOwnership","literalEntry","(Lphp/runtime/memory/ArrayMemory;Lphp/runtime/Memory;Lphp/runtime/env/Environment;Lphp/runtime/env/TraceInfo;Lphp/runtime/PhpRuntimeOwnership$Frame;I)Lphp/runtime/memory/ReferenceMemory;");
                int fresh=0;for(int before=3;before<wrapper;before++)if(runtimeNamedMethod(ops.get(before),pool,"php/runtime/PhpOwnershipArrays","runtimeFresh")&&runtimeSmallInt(ops.get(before-2),pool)==item.parent()) {
                    runtimeMethod(ops.get(before),pool,184,"php/runtime/PhpOwnershipArrays","runtimeFresh","(Lphp/runtime/PhpRuntimeOwnership$Frame;ILphp/runtime/memory/ArrayMemory;)Lphp/runtime/memory/ArrayMemory;");
                    if(ops.get(before-3).opcode()!=25||ops.get(before-3).operand()!=frame||ops.get(before-1).opcode()!=25)throw new IOException("Keyed literal changed its actual parent allocation/Frame");fresh++;
                }
                if(fresh!=1)throw new IOException("Keyed literal lacks one actual preceding ARRAY allocation");
                int first=ops.get(wrapper).offset(),end=ops.get(at).offset();
                for(int ordinal=0;ordinal<ops.size();ordinal++)for(int destination:runtimeFlowTargets(methodCode,ordinal))if(destination>=first&&destination<=end&&!(destination==first&&(runtimeLiteralReleaseJoin(methodCode,pool,ordinal,wrapper,frame,producers)||runtimeLiteralObjectNewJoin(ordinal,wrapper,objectNews,producers))))throw new IOException("Keyed literal has a nonsequential wrapper/publication entry");
                for(var guard:methodCode.guards())if(guard.handler()>=first&&guard.handler()<=end)throw new IOException("Keyed literal has an exception publication entry");
                literalEntries.add(item);
            }
            if(schema==20&&literalEntries.isEmpty())throw new IOException("Keyed literal schema lacks its actual publication");
            if(schema>=20){var ops=methodCode.instructions();for(int at=0;at<ops.size();at++)if(runtimeNamedMethod(ops.get(at),pool,"php/runtime/PhpRuntimeOwnership","literalEntry")){int ordinal=at;if(literalEntries.stream().noneMatch(value->value.helper()==ordinal))throw new IOException("Keyed literal publication lacks its actual byte-bound row");}}
            List<LocalIncrement> localIncrements=new ArrayList<>();
            if(schema>=21) {
                int frame=runtimeGeneratedFrame(methodCode,pool);var declared=PhpOwnershipExecution.readFramePlan(bytes,method,descriptor);var ops=methodCode.instructions();
                for(int i=0,n=runtimeCount(data);i<n;i++) {
                    LocalIncrement item=new LocalIncrement(data.readInt(),data.readInt(),data.readBoolean(),data.readBoolean(),data.readInt(),data.readInt());int at=item.helper();
                    if(localIncrements.stream().anyMatch(value->value.id()==item.id()||value.helper()==at)||producers.stream().noneMatch(value->value.id()==item.id()&&value.kind().equals("LOCAL_INCREMENT_RESULT"))||at<10||at>=ops.size()||item.read()!=at-7)throw new IOException("Local increment lacks its actual result/site operands");
                    var local=declared.locals().stream().filter(value->value.id()==item.root()&&value.storage()==PhpOwnershipExecution.Storage.ADDRESS&&!value.name().equals("GLOBALS")).findFirst().orElseThrow(()->new IOException("Local increment requires its actual addressable CV"));
                    runtimeMethod(ops.get(at),pool,184,"php/runtime/PhpArithmetic","localIncrement","(Lphp/runtime/PhpRuntimeOwnership$Frame;IILphp/runtime/Memory;ZZLphp/runtime/env/Environment;Lphp/runtime/env/TraceInfo;)Lphp/runtime/Memory;");
                    if(ops.get(at-10).opcode()!=25||ops.get(at-10).operand()!=frame||runtimeSmallInt(ops.get(at-9),pool)!=item.id()||runtimeSmallInt(ops.get(at-8),pool)!=item.root()||ops.get(at-7).opcode()!=25||ops.get(at-7).operand()!=local.jvmSlot()||runtimeSmallInt(ops.get(at-6),pool)!=(item.decrement()?1:0)||runtimeSmallInt(ops.get(at-5),pool)!=(item.post()?1:0)||ops.get(at-4).opcode()!=25||ops.get(at-4).operand()!=runtimeGeneratedEnvironment(methodCode,pool,frame)||runtimeSmallInt(ops.get(at-2),pool)<0||ops.get(at-1).opcode()!=50)throw new IOException("Local increment changed its actual Frame/CV/opcode/Environment/trace");
                    runtimeMethod(ops.get(at-3),pool,178,owner,"$TRC","[Lphp/runtime/env/TraceInfo;");
                    int first=ops.get(at-10).offset(),last=ops.get(at).offset();
                    for(int ordinal=0;ordinal<ops.size();ordinal++)for(int destination:runtimeFlowTargets(methodCode,ordinal))if(destination>first&&destination<=last)throw new IOException("Local increment has an interior operand/publication entry");
                    for(var guard:methodCode.guards())if(guard.handler()>first&&guard.handler()<=last)throw new IOException("Local increment has an interior exception entry");
                    localIncrements.add(item);
                }
                if(schema==21&&localIncrements.isEmpty())throw new IOException("Local increment schema lacks its actual opcode");
            }
            {var ops=methodCode.instructions();for(int at=0;at<ops.size();at++)if(runtimeNamedMethod(ops.get(at),pool,"php/runtime/PhpArithmetic","localIncrement")){int ordinal=at;if(localIncrements.stream().noneMatch(value->value.helper()==ordinal))throw new IOException("Local increment helper lacks its exact byte-bound row");}}
            for(Producer producer:producers)if(producer.kind().equals("LOCAL_INCREMENT_RESULT")&&localIncrements.stream().noneMatch(value->value.id()==producer.id()))throw new IOException("Local increment result lacks its actual publisher protocol");
            Map<Integer,Integer> comparisonFallbacks=new LinkedHashMap<>();
            if(schema>=22){for(int i=0,n=runtimeCount(data);i<n;i++){int id=data.readInt(),at=data.readInt();if(comparisonFallbacks.putIfAbsent(id,at)!=null||at<0||at>=methodCode.instructions().size())throw new IOException("Comparison fallback has duplicate/invalid opcode identity");}if(schema==22&&comparisonFallbacks.isEmpty())throw new IOException("Comparison fallback schema lacks its actual direct lane");}
            if(schema>=3)validateRuntimeComparisonFallbacks(owner,methodCode,pool,nativeMembers,nativeFactories,nativeBodyOpcodes,nativeBorrowSites,comparisonFallbacks);
            for(GlobalRead read:globalReads)if(runtimeNamedMethod(methodCode.instructions().get(read.helper()),pool,"php/runtime/PhpRuntimeOwnership","globalReadQuiet")&&!quietGlobalReads.contains(read.id()))throw new IOException("Quiet named READ lacks its exact coalesce lineage");
            List<NamedGlobal> namedGlobals=new ArrayList<>();List<EntryString> entryStrings=new ArrayList<>();List<ReturnBoundary> returnBoundaries=new ArrayList<>();
            if(schema>=23) {
                var declared=PhpOwnershipExecution.readFramePlan(bytes,method,descriptor);var ops=methodCode.instructions();int frame=runtimeGeneratedFrame(methodCode,pool);
                for(int i=0,n=runtimeCount(data);i<n;i++) {
                    NamedGlobal item=new NamedGlobal(data.readInt(),data.readUTF(),data.readInt(),data.readInt(),data.readInt(),data.readInt());
                    if(namedGlobals.stream().anyMatch(v->v.helper()==item.helper()))throw new IOException("Duplicate named global import");
                    validateNamedGlobal(methodCode,pool,item,frame,declared);namedGlobals.add(item);
                }
                for(int i=0,n=runtimeCount(data);i<n;i++) {
                    EntryString item=new EntryString(data.readInt(),data.readUTF(),data.readBoolean(),data.readInt());int at=item.helper();
                    if(entryStrings.stream().anyMatch(v->v.id()==item.id()||v.helper()==at)||producers.stream().noneMatch(v->v.id()==item.id()&&v.kind().equals("ENTRY_STRING_LITERAL")&&v.consumers().equals(Set.of("ENTRY"))))throw new IOException("String ENTRY lacks its exclusive producer");
                    List<ArrayEntrySite> entries=arrayEntries.stream().filter(v->v.producer()==item.id()).toList();
                    if(entries.size()!=1||entries.get(0).assignment()!=at+3||entries.get(0).step()>=at-4||at<4||at+3>=ops.size()||ops.get(at-4).opcode()!=25||ops.get(at-4).operand()!=frame||runtimeSmallInt(ops.get(at-3),pool)!=item.id()||!item.value().equals(runtimeLiteralString(ops.get(at-2),pool))||runtimeSmallInt(ops.get(at-1),pool)!=(item.raw()?1:0))throw new IOException("String ENTRY changed its actual Frame/id/literal/publication");
                    if(item.raw())for(int j=0;j<item.value().length();j++)if(item.value().charAt(j)>255)throw new IOException("String ENTRY raw literal is not bytes");
                    runtimeMethod(ops.get(at),pool,184,"php/runtime/PhpRuntimeOwnership","entryString","(Lphp/runtime/PhpRuntimeOwnership$Frame;ILjava/lang/String;Z)Lphp/runtime/Memory;");
                    rejectBoundaryEntry(methodCode,at-4,at+3);entryStrings.add(item);
                }
                for(int i=0,n=runtimeCount(data);i<n;i++) {
                    ReturnBoundary item=new ReturnBoundary(data.readInt(),data.readInt());int at=item.helper();
                    if(returnBoundaries.stream().anyMatch(v->v.helper()==at)||at<10||at>=ops.size()||ops.get(at-1).opcode()!=25||ops.get(at-1).operand()!=item.candidate())throw new IOException("Return normalizer changed its actual value");
                    runtimeMethod(ops.get(at),pool,184,"php/runtime/invoke/InvokeHelper","normalizeExplicitReturn","(Lphp/runtime/Memory;)Lphp/runtime/Memory;");
                    // Keep the existing helper/descriptor and attest its actual Entry/value guard.
                    if(ops.get(at-10).opcode()!=58||ops.get(at-10).operand()!=item.candidate()||ops.get(at-9).opcode()!=25||ops.get(at-8).opcode()!=198||ops.get(at-8).operand()!=ops.get(at-1).offset()||ops.get(at-7).opcode()!=25||ops.get(at-7).operand()!=ops.get(at-9).operand()||ops.get(at-6).opcode()!=25||ops.get(at-6).operand()!=item.candidate())throw new IOException("Return normalizer escaped its actual Entry/value guard");
                    runtimeMethod(ops.get(at-5),pool,182,"php/runtime/Memory","toImmutable","()Lphp/runtime/Memory;");
                    if(runtimeSmallInt(ops.get(at-4),pool)!=0)throw new IOException("Return normalizer changed its Entry mode");
                    runtimeMethod(ops.get(at-3),pool,182,"php/runtime/PhpOwnershipExecution$Entry","normalizeReturn","(Lphp/runtime/Memory;I)Lphp/runtime/Memory;");
                    if(ops.get(at-2).opcode()!=167||at+1>=ops.size()||ops.get(at-2).operand()!=ops.get(at+1).offset())throw new IOException("Return normalizer changed its branch join");
                    rejectBoundaryEntry(methodCode,at,at);returnBoundaries.add(item);
                }
            }
            if(schema==23&&namedGlobals.isEmpty()&&entryStrings.isEmpty()&&returnBoundaries.isEmpty())throw new IOException("Callback boundary schema lacks an exact physical operation");
            for(Producer producer:producers)if(producer.kind().equals("ENTRY_STRING_LITERAL")&&entryStrings.stream().noneMatch(v->v.id()==producer.id()))throw new IOException("String ENTRY lacks its actual literal proof");
            {var ops=methodCode.instructions();for(int at=0;at<ops.size();at++) {
                int ordinal=at;
                if(runtimeNamedMethod(ops.get(at),pool,"php/runtime/PhpRuntimeOwnership","namedGlobal")&&namedGlobals.stream().noneMatch(v->v.helper()==ordinal)
                        ||runtimeNamedMethod(ops.get(at),pool,"php/runtime/PhpRuntimeOwnership","entryString")&&entryStrings.stream().noneMatch(v->v.helper()==ordinal)
                        ||runtimeNamedMethod(ops.get(at),pool,"php/runtime/invoke/InvokeHelper","normalizeExplicitReturn")&&returnBoundaries.stream().noneMatch(v->v.helper()==ordinal))throw new IOException("Callback boundary lacks an exact physical proof");
            }}
            for(Producer producer:producers) {
                if(producer.kind().equals("JOIN_RESULT")&&conditionals.stream().noneMatch(value->value.id()==producer.id()))throw new IOException("Conditional RESULT lacks its actual branch publication");
                if(producer.kind().equals("CV_READ_RESULT")&&conditionalLocals.stream().noneMatch(value->value.id()==producer.id()))throw new IOException("Conditional CV RESULT lacks its actual address read");
                if(producer.consumers().contains("JOIN")&&conditionals.stream().noneMatch(value->value.source()==producer.id()||value.trueSource()==producer.id()||value.falseSource()==producer.id()))throw new IOException("Producer JOIN has no actual conditional consumer");
            }
            for(Producer producer:producers)if(producer.kind().equals("PROPERTY_REF_RESULT")&&propertyReferences.stream().noneMatch(value->value.id()==producer.id()))throw new IOException("Property reference RESULT lacks its actual FIELD proof");
            if(!globalImports.isEmpty()) {
                var declared=PhpOwnershipExecution.readFramePlan(bytes,method,descriptor);var ops=methodCode.instructions();
                for(GlobalImport imported:globalImports) {
                    int slot=declared.locals().stream().filter(value->value.id()==imported.local()).findFirst().orElseThrow().jvmSlot();
                    Set<Integer> allowed=new HashSet<>(runtimeInactiveNativeSessionReads(methodCode,pool,nativeMembers));for(GlobalAccess value:globalAccesses)if(value.root()==imported.local())allowed.add(value.factory()-8);
                    for(GlobalRead value:globalReads)if(value.root()==imported.local())allowed.add(value.read());for(GlobalUnset value:globalUnsets)if(value.root()==imported.local())allowed.add(value.read());
                    for(int ordinal=0;ordinal<ops.size();ordinal++)if(ops.get(ordinal).opcode()==25&&ops.get(ordinal).operand()==slot&&!allowed.contains(ordinal))throw new IOException("Whole GLOBALS table has no VALUE/SEND/capture owner protocol");
                }
            }
            for(GlobalRead read:globalReads)if(!read.cell()&&(runtimeRequiresCellResult(methodCode,pool,read.id(),runtimeGeneratedFrame(methodCode,pool))||globalAccesses.stream().anyMatch(value->value.sourceEval()==read.id()&&value.mode()==1)))throw new IOException("Named VALUE read cannot claim an unperformed reference export");
            for(NativeMember member:nativeMembers)if(member.owner().equals("php/runtime/ext/core/LangFunctions")&&member.name().equals("set_error_handler")&&handlers.stream().noneMatch(h->h.call()==member.id()))unsupported.add("handler-field-protocol-missing:"+member.id());
            for(Producer producer:producers)if(producer.kind().equals("CALL_RESULT")&&!callIds.contains(producer.id()))throw new IOException("CALL RESULT lacks its closed source/opcode protocol");
            var argumentPlan=PhpOwnershipExecution.readFramePlan(bytes,method,descriptor);
            if(argumentPlan.entryKind()==PhpOwnershipExecution.EntryKind.FUNCTION)for(var local:argumentPlan.locals())if(local.importKind()==PhpOwnershipExecution.ImportKind.ARGUMENT&&local.importMode()==PhpOwnershipExecution.Mode.CELL) {
                var ops=methodCode.instructions();int matched=0,frame=runtimeGeneratedFrame(methodCode,pool);
                for(int at=5;at<ops.size();at++) {
                    var operation=ops.get(at);if(operation.opcode()!=182)continue;
                    RuntimePair memberRef=operation.operand()>0&&operation.operand()<pool.length&&pool[operation.operand()] instanceof RuntimePair?(RuntimePair)pool[operation.operand()]:null;
                    if(memberRef==null||!runtimeClass(pool,memberRef.first()).equals("php/runtime/PhpRuntimeOwnership$Frame"))continue;
                    RuntimePair type=(RuntimePair)pool[memberRef.second()];if(!runtimeUtf(pool,type.first()).equals("importArgument"))continue;
                    if(runtimeSmallInt(ops.get(at-3),pool)!=local.id())continue;
                    runtimeMethod(operation,pool,182,"php/runtime/PhpRuntimeOwnership$Frame","importArgument","(IILphp/runtime/Memory;I)Lphp/runtime/Memory;");
                    if(local.storage()!=PhpOwnershipExecution.Storage.ADDRESS||local.importIndex()!=0||ops.get(at-5).opcode()!=25||ops.get(at-5).operand()!=frame||runtimeSmallInt(ops.get(at-4),pool)!=0||ops.get(at-2).opcode()!=25||runtimeSmallInt(ops.get(at-1),pool)!=1)throw new IOException("FUNCTION reference import changed its actual SEND/CV operands");
                    int candidate=ops.get(at-2).operand(),stored=-1;for(int before=at-3;before>=0;before--)if(ops.get(before).opcode()==58&&ops.get(before).operand()==candidate){stored=before;break;}
                    if(stored<3||ops.get(stored-1).opcode()!=50||runtimeSmallInt(ops.get(stored-2),pool)!=0||ops.get(stored-3).opcode()!=25||ops.get(stored-3).operand()!=1)throw new IOException("FUNCTION reference import is not the actual argument array operand");matched++;
                }
                if(matched!=1)throw new IOException("FUNCTION reference parameter lacks one exact actual SEND import");
            }
            if(data.read()!=-1)throw new IOException("Trailing runtime frame data");
            return new RuntimePlan(owner,method,descriptor,producers,unsupported,nativeMembers,closureCvs,calls,literals,clones,arrayEntries,handlers,globalImports,globalAccesses,globalReads,globalUnsets,globalScalars,arrayReferences,functionCalls,arrayIncrements,objectNews,objectCvs,propertyReferences,conditionals,quietGlobalReads,conditionalLocals,lifecycle,lifecycleReceivers,lifecycleDiscards,globalNulls,entryScalars,objectCasts,literalEntries,localIncrements,namedGlobals,entryStrings,returnBoundaries);
        }catch(IllegalArgumentException failure){throw new IOException("Invalid runtime frame plan",failure);}
    }
    /** A literal child retirement may join the following complete producer prefix, never its interior. */
    private static boolean runtimeLiteralReleaseJoin(RuntimeCode code,Object[] pool,int branch,int first,int frame,List<Producer> producers)throws IOException {
        var ops=code.instructions();if(first<5||branch!=first-4||ops.get(branch).opcode()!=198||ops.get(first-5).opcode()!=25||ops.get(first-5).operand()!=frame||ops.get(first-3).opcode()!=25||ops.get(first-3).operand()!=frame)return false;
        int child=runtimeSmallInt(ops.get(first-2),pool);if(producers.stream().noneMatch(value->value.id()==child&&value.kind().equals("ARRAY_LITERAL")&&value.consumers().contains("ENTRY")))return false;
        runtimeMethod(ops.get(first-1),pool,182,"php/runtime/PhpRuntimeOwnership$Frame","releaseEval","(I)V");return true;
    }
    /** Only the passive join of an already physically validated OBJECT_NEW may feed the whole wrapper. */
    private static boolean runtimeLiteralObjectNewJoin(int branch,int wrapper,List<ObjectNew> objectNews,List<Producer> producers) {
        return objectNews.stream().anyMatch(value->value.helper()+1==branch&&value.end()==wrapper
                &&producers.stream().anyMatch(producer->producer.id()==value.id()&&producer.kind().equals("OBJECT_NEW")&&producer.consumers().contains("ENTRY")));
    }
    private static void rejectBoundaryEntry(RuntimeCode code,int first,int last)throws IOException {
        var ops=code.instructions();int start=ops.get(first).offset(),end=ops.get(last).offset();
        for(int ordinal=0;ordinal<ops.size();ordinal++)for(int destination:runtimeFlowTargets(code,ordinal))if(destination>start&&destination<=end)throw new IOException("Callback boundary has an interior control-flow entry");
        for(var guard:code.guards())if(guard.handler()>start&&guard.handler()<=end)throw new IOException("Callback boundary has an interior exception entry");
    }
    private static void validateNamedGlobal(RuntimeCode code,Object[] pool,NamedGlobal item,int frame,PhpOwnershipExecution.FramePlan declared)throws IOException {
        var ops=code.instructions();int at=item.helper();
        var local=declared.locals().stream().filter(v->v.id()==item.local()&&v.name().equals(item.name())&&v.storage()==PhpOwnershipExecution.Storage.ADDRESS&&!v.name().equals("GLOBALS")).findFirst().orElseThrow(()->new IOException("Named global lacks its exact ADDRESS declaration"));
        if(at<6||item.start()!=at-4||item.fallback()<=at+1||item.end()<=item.fallback()||item.end()>ops.size()||ops.get(at-6).opcode()!=25||ops.get(at-6).operand()!=frame||ops.get(at-5).opcode()!=198||ops.get(at-5).operand()!=ops.get(item.fallback()).offset()||ops.get(at-4).opcode()!=25||ops.get(at-4).operand()!=frame||runtimeSmallInt(ops.get(at-3),pool)!=item.local()||!item.name().equals(runtimeLiteralString(ops.get(at-2),pool))||ops.get(at-1).opcode()!=25||ops.get(at-1).operand()!=runtimeGeneratedEnvironment(code,pool,frame))throw new IOException("Named global changed its actual guard/Frame/local/name/Environment");
        runtimeMethod(ops.get(at),pool,184,"php/runtime/PhpRuntimeOwnership","namedGlobal","(Lphp/runtime/PhpRuntimeOwnership$Frame;ILjava/lang/String;Lphp/runtime/env/Environment;)Lphp/runtime/Memory;");
        int join=at+1,publication=join+21;
        if(publication+10>=item.fallback()||ops.get(join).opcode()!=58)throw new IOException("Named global lacks its actual candidate publication");int candidate=ops.get(join).operand();
        if(ops.get(join+14).opcode()!=25||ops.get(join+14).operand()!=frame||ops.get(join+15).opcode()!=198||ops.get(join+15).operand()!=ops.get(join+30).offset()||ops.get(publication-5).opcode()!=25||ops.get(publication-5).operand()!=frame||runtimeSmallInt(ops.get(publication-4),pool)!=item.local()||!item.name().equals(runtimeLiteralString(ops.get(publication-3),pool))||ops.get(publication-2).opcode()!=25||ops.get(publication-2).operand()!=candidate||runtimeSmallInt(ops.get(publication-1),pool)!=2)throw new IOException("Named global changed its exact publication candidate/local/Frame");
        runtimeMethod(ops.get(publication),pool,182,"php/runtime/PhpRuntimeOwnership$Frame","prepareLocal","(ILjava/lang/String;Lphp/runtime/Memory;I)Lphp/runtime/PhpRuntimeOwnership$Publication;");
        if(ops.get(publication+1).opcode()!=58||ops.get(publication+2).opcode()!=25||ops.get(publication+2).operand()!=candidate||ops.get(publication+3).opcode()!=58||ops.get(publication+3).operand()!=local.jvmSlot())throw new IOException("Named global changed its actual CV store");int token=ops.get(publication+1).operand();
        if(ops.get(publication+4).opcode()!=25||ops.get(publication+4).operand()!=token||ops.get(publication+6).opcode()!=25||ops.get(publication+6).operand()!=token)throw new IOException("Named global changed its publication token");
        runtimeMethod(ops.get(publication+5),pool,182,"php/runtime/PhpRuntimeOwnership$Publication","afterLocalStore","()V");runtimeMethod(ops.get(publication+7),pool,182,"php/runtime/PhpRuntimeOwnership$Publication","commit","()V");
        int end=item.end()==ops.size()?ops.getLast().next():ops.get(item.end()).offset();var jump=ops.get(item.fallback()-1);if(jump.opcode()!=167||jump.operand()!=end)throw new IOException("Named global changed its ordinary fallback join");
        rejectBoundaryEntry(code,at-3,at);
    }
    private static void validateRuntimeGlobalImport(RuntimeCode code,Object[] pool,GlobalImport item,int frame,PhpOwnershipExecution.FramePlan declared)throws IOException {
        var ops=code.instructions();int at=item.helper();
        if(at<4||item.start()!=at-2||item.fallback()<=at+1||item.end()<=item.fallback()||item.end()>ops.size())throw new IOException("Literal GLOBALS import escapes its exact guard");
        runtimeMethod(ops.get(at),pool,182,"php/runtime/PhpRuntimeOwnership$Frame","globalsAddress","(I)Lphp/runtime/memory/ReferenceMemory;");
        if(ops.get(at-2).opcode()!=25||ops.get(at-2).operand()!=frame||runtimeSmallInt(ops.get(at-1),pool)!=item.local()||ops.get(at-4).opcode()!=25||ops.get(at-4).operand()!=frame||ops.get(at-3).opcode()!=198||ops.get(at-3).operand()!=ops.get(item.fallback()).offset())throw new IOException("Literal GLOBALS changes its Frame/local operands");
        int end=item.end()==ops.size()?ops.getLast().next():ops.get(item.end()).offset();var jump=ops.get(at+1);if((jump.opcode()!=167&&jump.opcode()!=200)||jump.operand()!=end)throw new IOException("Literal GLOBALS does not join its null-Frame fallback");
        var local=declared.locals().stream().filter(value->value.id()==item.local()&&value.name().equals("GLOBALS")&&value.storage()==PhpOwnershipExecution.Storage.ADDRESS).findFirst().orElseThrow(()->new IOException("Literal GLOBALS lacks its actual address declaration"));
        int join=item.end(),publication=join+21;
        if(publication+10>=ops.size()||ops.get(join).opcode()!=58)throw new IOException("Literal GLOBALS lacks its actual CV publication");
        int candidate=ops.get(join).operand();
        if(ops.get(join+1).opcode()!=25||ops.get(join+2).opcode()!=198||ops.get(join+2).operand()!=ops.get(join+14).offset()||ops.get(join+3).opcode()!=25||ops.get(join+3).operand()!=ops.get(join+1).operand())throw new IOException("Literal GLOBALS does not enter its exact ordinary publication branch");
        if(ops.get(join+14).opcode()!=25||ops.get(join+14).operand()!=frame||ops.get(join+15).opcode()!=198||ops.get(join+15).operand()!=ops.get(join+30).offset()
                ||ops.get(publication-5).opcode()!=25||ops.get(publication-5).operand()!=frame||runtimeSmallInt(ops.get(publication-4),pool)!=item.local()
                ||!"GLOBALS".equals(runtimeLiteralString(ops.get(publication-3),pool))||ops.get(publication-2).opcode()!=25||ops.get(publication-2).operand()!=candidate||runtimeSmallInt(ops.get(publication-1),pool)!=2)
            throw new IOException("Literal GLOBALS publication changed its candidate/Frame/local");
        runtimeMethod(ops.get(publication),pool,182,"php/runtime/PhpRuntimeOwnership$Frame","prepareLocal","(ILjava/lang/String;Lphp/runtime/Memory;I)Lphp/runtime/PhpRuntimeOwnership$Publication;");
        if(ops.get(publication+1).opcode()!=58||ops.get(publication+2).opcode()!=25||ops.get(publication+2).operand()!=candidate||ops.get(publication+3).opcode()!=58||ops.get(publication+3).operand()!=local.jvmSlot())throw new IOException("Literal GLOBALS does not store its prepared candidate into the declared CV");
        int token=ops.get(publication+1).operand();
        if(ops.get(publication+4).opcode()!=25||ops.get(publication+4).operand()!=token||ops.get(publication+6).opcode()!=25||ops.get(publication+6).operand()!=token)throw new IOException("Literal GLOBALS changed its publication token");
        runtimeMethod(ops.get(publication+5),pool,182,"php/runtime/PhpRuntimeOwnership$Publication","afterLocalStore","()V");
        runtimeMethod(ops.get(publication+7),pool,182,"php/runtime/PhpRuntimeOwnership$Publication","commit","()V");
    }
    private static void validateRuntimeGlobalAccess(RuntimeCode code,Object[] pool,GlobalAccess item,int frame,String owner,String method,String descriptor,byte[] bytes,PhpOwnershipExecution.FramePlan declared)throws IOException {
        var ops=code.instructions();int at=item.factory(),index=item.index(),assign=item.assignment();
        if(at<8||index<=at+1||assign<index+5||assign>=ops.size())throw new IOException("Named global publication escapes its evaluation order");
        runtimeMethod(ops.get(at),pool,184,"php/runtime/NullCoalesceAssignment","variableGlobalsRuntime","(Lphp/runtime/Memory;Lphp/runtime/env/Environment;Lphp/runtime/env/TraceInfo;Lphp/runtime/PhpRuntimeOwnership$Frame;II)Lphp/runtime/NullCoalesceAssignment;");
        runtimeMethod(ops.get(index),pool,182,"php/runtime/NullCoalesceAssignment","indexGlobal","(Ljava/lang/String;)Lphp/runtime/NullCoalesceAssignment;");
        runtimeMethod(ops.get(assign),pool,182,"php/runtime/NullCoalesceAssignment","assignGlobalRuntime","(Lphp/runtime/Memory;Lphp/runtime/PhpRuntimeOwnership$Frame;III)Lphp/runtime/Memory;");
        if(!item.key().equals(runtimeLiteralString(ops.get(index-1),pool)))throw new IOException("Named global literal key differs from its actual operand");
        if(item.sourceLocal()>=0) {
            var source=declared.locals().stream().filter(local->local.id()==item.sourceLocal()&&!local.name().equals("GLOBALS")).findFirst().orElseThrow(()->new IOException("Named global source is not an actual CV"));
            if(item.read()<=index||item.read()>=assign||ops.get(item.read()).opcode()!=25||ops.get(item.read()).operand()!=source.jvmSlot())throw new IOException("Named global source is not loaded from its attested actual CV");
            for(int next=item.read()+1;next<assign-4;next++) {
                var operation=ops.get(next);
                if(operation.opcode()==95&&next+1<assign-4&&ops.get(next+1).opcode()==95){next++;continue;}
                if(operation.opcode()==182)runtimeMethod(operation,pool,182,"php/runtime/Memory","toImmutable","()Lphp/runtime/Memory;");
                else if(operation.opcode()==184&&item.mode()==1)runtimeMethod(operation,pool,184,"php/runtime/memory/ReferenceMemory","exportReference","(Lphp/runtime/Memory;)Lphp/runtime/Memory;");
                else if(item.mode()==1&&next+4<assign-4&&runtimeNamedMethod(ops.get(next+4),pool,"php/runtime/PhpPrimitiveCellRuntime","export")) {
                    runtimeMethod(ops.get(next+4),pool,184,"php/runtime/PhpPrimitiveCellRuntime","export","(Lphp/runtime/Memory;Ljava/lang/Class;Ljava/lang/String;Ljava/lang/String;I)Lphp/runtime/Memory;");
                    if(operation.opcode()!=18&&operation.opcode()!=19||!runtimeClass(pool,operation.operand()).equals(owner)
                            ||!runtimeLiteralString(ops.get(next+1),pool).equals(method)||!runtimeLiteralString(ops.get(next+2),pool).equals(descriptor))throw new IOException("GLOBAL export changed its exact member operands");
                    PhpPrimitiveCellPlan.validateExportSite(bytes,owner,method,descriptor,runtimeSmallInt(ops.get(next+3),pool),item.sourceLocal());next+=4;
                }
                else throw new IOException("Named global source LOAD does not feed its actual RHS publication");
            }
        }else if(item.read()!=-1)throw new IOException("Producer EVAL cannot claim a CV read witness");
        var local=declared.locals().stream().filter(l->l.id()==item.root()&&l.name().equals("GLOBALS")&&l.storage()==PhpOwnershipExecution.Storage.ADDRESS).findFirst().orElseThrow(()->new IOException("Named global root lacks its actual CV"));
        if(ops.get(at-8).opcode()!=25||ops.get(at-8).operand()!=local.jvmSlot()||ops.get(at-7).opcode()!=25||ops.get(at-7).operand()!=runtimeGeneratedEnvironment(code,pool,frame)||ops.get(at-4).opcode()!=50||ops.get(at-3).opcode()!=25||ops.get(at-3).operand()!=frame||runtimeSmallInt(ops.get(at-2),pool)!=item.id()||runtimeSmallInt(ops.get(at-1),pool)!=item.root())throw new IOException("Named global root/Environment/Frame operands differ");
        runtimeMethod(ops.get(at-6),pool,178,owner,"$TRC","[Lphp/runtime/env/TraceInfo;");if(runtimeSmallInt(ops.get(at-5),pool)<0)throw new IOException("Named global trace is not the actual compiler table entry");
        if(ops.get(assign-4).opcode()!=25||ops.get(assign-4).operand()!=frame||runtimeSmallInt(ops.get(assign-3),pool)!=item.sourceEval()||runtimeSmallInt(ops.get(assign-2),pool)!=item.sourceLocal()||runtimeSmallInt(ops.get(assign-1),pool)!=item.mode())throw new IOException("Named global publication changed its source/mode operands");
    }
    private static void validateRuntimeGlobalLifecycle(RuntimeCode code,Object[] pool,int id,int root,String key,boolean cell,int helper,int read,boolean fetch,int frame,String owner,PhpOwnershipExecution.FramePlan declared)throws IOException {
        var ops=code.instructions();int before=fetch?10:6;
        if(helper<before||helper>=ops.size()||read!=helper-(fetch?7:3))throw new IOException("Named global lifecycle escapes its actual operand publication");
        var local=declared.locals().stream().filter(value->value.id()==root&&value.name().equals("GLOBALS")&&value.storage()==PhpOwnershipExecution.Storage.ADDRESS).findFirst().orElseThrow(()->new IOException("Named global lifecycle lacks its borrowed table CV"));
        String descriptor=fetch?"(Lphp/runtime/PhpRuntimeOwnership$Frame;IILphp/runtime/Memory;Ljava/lang/String;ZLphp/runtime/env/Environment;Lphp/runtime/env/TraceInfo;)Lphp/runtime/Memory;":"(Lphp/runtime/PhpRuntimeOwnership$Frame;IILphp/runtime/Memory;Ljava/lang/String;Lphp/runtime/env/Environment;)V";
        runtimeMethod(ops.get(helper),pool,184,"php/runtime/PhpRuntimeOwnership",fetch&&runtimeNamedMethod(ops.get(helper),pool,"php/runtime/PhpRuntimeOwnership","globalReadQuiet")?"globalReadQuiet":fetch?"globalRead":"globalUnset",descriptor);
        int first=helper-before;
        if(ops.get(first).opcode()!=25||ops.get(first).operand()!=frame||runtimeSmallInt(ops.get(first+1),pool)!=id||runtimeSmallInt(ops.get(first+2),pool)!=root||ops.get(read).opcode()!=25||ops.get(read).operand()!=local.jvmSlot()||!key.equals(runtimeLiteralString(ops.get(read+1),pool)))throw new IOException("Named global lifecycle changed its Frame/site/root/key");
        int environment=helper-(fetch?4:1);if(ops.get(environment).opcode()!=25||ops.get(environment).operand()!=runtimeGeneratedEnvironment(code,pool,frame))throw new IOException("Named global lifecycle changed its actual Environment");
        if(fetch) {
            if(runtimeSmallInt(ops.get(helper-5),pool)!=(cell?1:0)||ops.get(helper-1).opcode()!=50)throw new IOException("Named global READ changed its actual VALUE/CELL or trace");
            runtimeMethod(ops.get(helper-3),pool,178,owner,"$TRC","[Lphp/runtime/env/TraceInfo;");if(runtimeSmallInt(ops.get(helper-2),pool)<0)throw new IOException("Named global READ trace is not generated");
        }
    }
    private static long runtimeLongLiteral(RuntimeInstruction instruction,Object[] pool)throws IOException {
        if(instruction.opcode()==9)return 0;if(instruction.opcode()==10)return 1;
        if(instruction.opcode()!=18&&instruction.opcode()!=19&&instruction.opcode()!=20||instruction.operand()<=0||instruction.operand()>=pool.length||!(pool[instruction.operand()] instanceof Long))throw new IOException("Expected exact long literal operand");
        return (Long)pool[instruction.operand()];
    }
    private static String runtimeLiteralString(RuntimeInstruction instruction,Object[] pool)throws IOException {
        if(instruction.opcode()!=18&&instruction.opcode()!=19||instruction.operand()<=0||instruction.operand()>=pool.length||!(pool[instruction.operand()] instanceof RuntimeString))throw new IOException("Expected exact literal String operand");
        return runtimeUtf(pool,((RuntimeString)pool[instruction.operand()]).utf());
    }
    private static void validateRuntimeArrayEntry(RuntimeCode code,Object[] pool,ArrayEntrySite site,int frameLocal,String generatedOwner,PhpOwnershipExecution.FramePlan declaredFrame)throws IOException {
        var instructions=code.instructions();int factory=site.factory(),step=site.step(),assignment=site.assignment();
        if(factory<8||step<=factory||assignment<step+3||assignment>=instructions.size())throw new IOException("ARRAY ENTRY escapes its actual write order");
        runtimeMethod(instructions.get(factory),pool,184,"php/runtime/NullCoalesceAssignment","variableRuntime","(Lphp/runtime/Memory;Lphp/runtime/env/Environment;Lphp/runtime/env/TraceInfo;Lphp/runtime/PhpRuntimeOwnership$Frame;II)Lphp/runtime/NullCoalesceAssignment;");
        runtimeMethod(instructions.get(step),pool,182,"php/runtime/NullCoalesceAssignment",site.append()?"append":"index",site.append()?"()Lphp/runtime/NullCoalesceAssignment;":"(Lphp/runtime/Memory;)Lphp/runtime/NullCoalesceAssignment;");
        runtimeMethod(instructions.get(assignment),pool,182,"php/runtime/NullCoalesceAssignment","assignRuntime","(Lphp/runtime/Memory;Lphp/runtime/PhpRuntimeOwnership$Frame;I)Lphp/runtime/Memory;");
        if(instructions.get(factory-3).opcode()!=25||instructions.get(factory-3).operand()!=frameLocal||runtimeSmallInt(instructions.get(factory-2),pool)!=site.id()||runtimeSmallInt(instructions.get(factory-1),pool)!=site.local()
                ||instructions.get(assignment-2).opcode()!=25||instructions.get(assignment-2).operand()!=frameLocal||runtimeSmallInt(instructions.get(assignment-1),pool)!=site.producer())throw new IOException("ARRAY ENTRY source, site or Frame operands changed");
        var local=declaredFrame.locals().stream().filter(l->l.id()==site.local()&&l.storage()==PhpOwnershipExecution.Storage.ADDRESS).findFirst().orElseThrow(()->new IOException("ARRAY ENTRY has no actual addressable local declaration"));
        if(instructions.get(factory-8).opcode()!=25||instructions.get(factory-8).operand()!=local.jvmSlot()||instructions.get(factory-7).opcode()!=25||instructions.get(factory-7).operand()!=runtimeGeneratedEnvironment(code,pool,frameLocal)||instructions.get(factory-4).opcode()!=50)throw new IOException("ARRAY ENTRY root address or Environment differs from its actual frame declaration");
        runtimeMethod(instructions.get(factory-6),pool,178,generatedOwner,"$TRC","[Lphp/runtime/env/TraceInfo;");
        if(runtimeSmallInt(instructions.get(factory-5),pool)<0)throw new IOException("ARRAY ENTRY trace is not its compiler table entry");
        for(int i=factory+1;i<step;i++)if(instructions.get(i).opcode()==182||instructions.get(i).opcode()==184) {
            // Literal scalar boxing is the only initial indexed fetch contract; dynamic keys stay unadmitted.
            RuntimeInstruction op=instructions.get(i);RuntimePair reference=(RuntimePair)pool[op.operand()];String owner=runtimeClass(pool,reference.first());
            if(owner.equals("php/runtime/memory/LongMemory"))runtimeMethod(op,pool,184,owner,"valueOf","(J)Lphp/runtime/memory/LongMemory;");
            else if(owner.equals("php/runtime/memory/StringMemory"))runtimeMethod(op,pool,184,owner,"valueOf","(Ljava/lang/String;)Lphp/runtime/memory/StringMemory;");
            else throw new IOException("ARRAY ENTRY index has an unknown effectful producer");
        }
    }
    /** The result-use bit comes from the exact defined invocation instruction, never an annotation or mutable frame. */
    private static boolean runtimeInvocationDiscarded(RuntimeInstruction op,Object[] pool,String owner)throws IOException {
        if(runtimeNamedMethod(op,pool,182,owner,"invokeToEvalDiscarded")) {
            runtimeMethod(op,pool,182,owner,"invokeToEvalDiscarded","(I)Lphp/runtime/Memory;");return true;
        }
        runtimeMethod(op,pool,182,owner,"invokeToEval","(I)Lphp/runtime/Memory;");return false;
    }
    private static void validateRuntimeFunctionCall(RuntimeCode code,Object[] pool,FunctionCall site,int frame,PhpOwnershipExecution.FramePlan declared)throws IOException {
        var ops=code.instructions();int at=site.factory();
        if(at<6||site.start()!=at-4||site.send()!=at+6||site.read()!=at+4||site.invoke()!=at+10||site.fallback()!=at+12||site.end()<=site.fallback()||site.end()>ops.size())throw new IOException("FUNCTION packet escapes its sequential SEND/RESULT guard");
        var local=declared.locals().stream().filter(value->value.id()==site.local()&&value.storage()==PhpOwnershipExecution.Storage.ADDRESS&&!value.name().equals("GLOBALS")).findFirst().orElseThrow(()->new IOException("FUNCTION reference SEND lacks its actual ADDRESS CV"));
        runtimeMethod(ops.get(at),pool,182,"php/runtime/PhpRuntimeOwnership$Frame","functionPacket","(ILjava/lang/String;Lphp/runtime/env/TraceInfo;)Lphp/runtime/PhpRuntimeOwnership$FunctionPacket;");
        runtimeMethod(ops.get(site.send()),pool,182,"php/runtime/PhpRuntimeOwnership$FunctionPacket","sendCell","(ILphp/runtime/Memory;I)Lphp/runtime/Memory;");
        runtimeInvocationDiscarded(ops.get(site.invoke()),pool,"php/runtime/PhpRuntimeOwnership$FunctionPacket");
        for(int ordinal=0;ordinal<ops.size();ordinal++) {
            var branch=ops.get(ordinal);int opcode=branch.opcode();
            if(opcode==170||opcode==171||opcode==168||opcode==169||opcode==201)throw new IOException("FUNCTION packet requires its closed direct control-flow protocol");
            if(opcode>=153&&opcode<=167||opcode==198||opcode==199||opcode==200) {
                int destination=branch.operand();boolean enters=destination>=ops.get(site.start()).offset()&&destination<ops.get(site.fallback()).offset();
                if(enters&&(ordinal<site.start()||ordinal>=site.fallback()))throw new IOException("External branch enters an acquired FUNCTION packet");
                if(ordinal>=site.start()&&ordinal<site.fallback()&&ordinal!=site.invoke()+1)throw new IOException("FUNCTION packet skips its sequential SEND/RESULT");
            }
        }
        for(var guard:code.guards())if(guard.handler()>=ops.get(site.start()).offset()&&guard.handler()<ops.get(site.fallback()).offset())throw new IOException("Throwable handler enters an acquired FUNCTION packet");
        int packet=ops.get(at+1).operand();int end=site.end()==ops.size()?ops.getLast().next():ops.get(site.end()).offset();
        if(ops.get(at-6).opcode()!=25||ops.get(at-6).operand()!=frame||ops.get(at-5).opcode()!=198||ops.get(at-5).operand()!=ops.get(site.fallback()).offset()||ops.get(at-4).opcode()!=25||ops.get(at-4).operand()!=frame||runtimeSmallInt(ops.get(at-3),pool)!=site.id()||!site.name().equals(runtimeLiteralString(ops.get(at-2),pool))||ops.get(at-1).opcode()!=25||ops.get(at+1).opcode()!=58||ops.get(at+2).opcode()!=25||ops.get(at+2).operand()!=packet||runtimeSmallInt(ops.get(at+3),pool)!=0||ops.get(at+4).opcode()!=25||ops.get(at+4).operand()!=local.jvmSlot()||runtimeSmallInt(ops.get(at+5),pool)!=site.local()||ops.get(at+7).opcode()!=87||ops.get(at+8).opcode()!=25||ops.get(at+8).operand()!=packet||runtimeSmallInt(ops.get(at+9),pool)!=site.id()||(ops.get(at+11).opcode()!=167&&ops.get(at+11).opcode()!=200)||ops.get(at+11).operand()!=end)throw new IOException("FUNCTION packet changed its Frame/CV/SEND/RESULT operands");
    }
    private static void validateRuntimeCall(RuntimeCode code,Object[] pool,CallSite site,int frameLocal)throws IOException {
        var instructions=code.instructions();int factory=site.factory(),invoke=site.invoke();
        if(factory<7||factory+1>=instructions.size()||invoke<factory+3||invoke>=instructions.size())throw new IOException("CALL site escapes its actual body");
        runtimeMethod(instructions.get(factory),pool,182,"php/runtime/PhpRuntimeOwnership$Frame","callable","(ILphp/runtime/Memory;IILphp/runtime/env/TraceInfo;I)Lphp/runtime/PhpRuntimeOwnership$CallPacket;");
        runtimeInvocationDiscarded(instructions.get(invoke),pool,"php/runtime/PhpRuntimeOwnership$CallPacket");
        if(runtimeSmallInt(instructions.get(factory-6),pool)!=site.id()||instructions.get(factory-5).opcode()!=25||runtimeSmallInt(instructions.get(factory-4),pool)!=site.local()||runtimeSmallInt(instructions.get(factory-3),pool)!=site.evaluation()||instructions.get(factory-2).opcode()!=25||runtimeSmallInt(instructions.get(factory-1),pool)!=site.arity())throw new IOException("CALL acquisition operands differ from their source/arity proof");
        if(site.start()!=factory-7||site.start()<2||site.fallback()<=invoke+1||site.fallback()>=instructions.size()||site.end()<=site.fallback()||site.end()>instructions.size())throw new IOException("CALL guard boundaries differ from the acquisition");
        RuntimeInstruction frame=instructions.get(factory-7),guard=instructions.get(site.start()-1),guardFrame=instructions.get(site.start()-2),jump=instructions.get(invoke+1);
        int endOffset=site.end()==instructions.size()?instructions.getLast().next():instructions.get(site.end()).offset();
        if(frame.opcode()!=25||frame.operand()!=frameLocal||guardFrame.opcode()!=25||frame.operand()!=guardFrame.operand()||guard.opcode()!=198||guard.operand()!=instructions.get(site.fallback()).offset()||(jump.opcode()!=167&&jump.opcode()!=200)||jump.operand()!=endOffset)throw new IOException("CALL does not have its exact same-Frame null fallback");
        RuntimeInstruction stored=instructions.get(factory+1),loaded=instructions.get(invoke-2);
        if(stored.opcode()!=58||loaded.opcode()!=25||stored.operand()!=loaded.operand()||runtimeSmallInt(instructions.get(invoke-1),pool)!=site.id())throw new IOException("CALL invocation does not consume its actual packet/RESULT identity");
    }
    private static String runtimeClosureClass(int id,List<ClosureLiteral> literals,List<CloneSite> clones,Set<Integer> path)throws IOException {
        if(!path.add(id))throw new IOException("Cyclic Closure producer lineage");
        for(ClosureLiteral literal:literals)if(literal.producer()==id)return literal.closure();
        CloneSite clone=clones.stream().filter(site->site.id()==id).findFirst().orElseThrow(()->new IOException("Closure producer has no exact literal-rooted allocation"));
        String source=runtimeClosureClass(clone.producer(),literals,clones,path);
        if(!source.equals(clone.closure()))throw new IOException("CLONE source class differs from its allocation lineage");return source;
    }
    private static void validateRuntimeClone(RuntimeCode code,Object[] pool,CloneSite site,int frameLocal)throws IOException {
        var instructions=code.instructions();int at=site.helper();
        if(at<6||at+1>=instructions.size()||site.start()!=at-6||site.start()<2||site.fallback()<=at+1||site.fallback()>=instructions.size()||site.end()<=site.fallback()||site.end()>instructions.size())throw new IOException("CLONE escapes its exact guarded allocation");
        runtimeCall(instructions.get(at),pool,"php/runtime/PhpOwnershipClosures","cloneLocalRuntime","(Lphp/runtime/PhpRuntimeOwnership$Frame;IILphp/runtime/Memory;Lphp/runtime/env/Environment;Lphp/runtime/env/TraceInfo;)Lphp/runtime/Memory;");
        if(instructions.get(at-6).opcode()!=25||instructions.get(at-6).operand()!=frameLocal||runtimeSmallInt(instructions.get(at-5),pool)!=site.id()||runtimeSmallInt(instructions.get(at-4),pool)!=site.local()||instructions.get(at-3).opcode()!=25||instructions.get(at-2).opcode()!=25||instructions.get(at-1).opcode()!=25)throw new IOException("CLONE operands differ from its exact CV/producer");
        RuntimeInstruction guard=instructions.get(site.start()-1),load=instructions.get(site.start()-2),jump=instructions.get(at+1);
        int end=site.end()==instructions.size()?instructions.getLast().next():instructions.get(site.end()).offset();
        if(load.opcode()!=25||load.operand()!=frameLocal||guard.opcode()!=198||guard.operand()!=instructions.get(site.fallback()).offset()||(jump.opcode()!=167&&jump.opcode()!=200)||jump.operand()!=end)throw new IOException("CLONE lacks its exact Frame-null fallback");
    }
    private static List<String> runtimeNativeArguments(NativeMember member)throws IOException {
        String descriptor=member.descriptor();List<String> arguments=new ArrayList<>();int at=1;
        while(at<descriptor.length()&&descriptor.charAt(at)!=')') {
            int start=at;while(at<descriptor.length()&&descriptor.charAt(at)=='[')at++;
            if(at>=descriptor.length())throw new IOException("Truncated native parameter type");
            if(descriptor.charAt(at)=='L'){int end=descriptor.indexOf(';',at);if(end<0)throw new IOException("Truncated native class type");at=end+1;}
            else {if("ZBCSIJFD".indexOf(descriptor.charAt(at))<0)throw new IOException("Invalid native parameter type");at++;}
            String type=descriptor.substring(start,at);if(!Set.of("Lphp/runtime/env/Environment;","Lphp/runtime/env/TraceInfo;").contains(type))arguments.add(type);
        }
        if(at>=descriptor.length()||arguments.size()!=member.argc())throw new IOException("Native scalar arity differs from its selected Java parameters");return arguments;
    }
    private static void validateRuntimeConditional(RuntimeCode code,Object[] pool,ConditionalSite site,int frame,List<Producer> producers,List<GlobalRead> reads)throws IOException {
        var ops=code.instructions();int test=site.test(),yes=site.trueResult(),no=site.falseResult();
        if(site.kind()!=0&&site.kind()!=1||site.kind()==0&&!site.retainTrue()||site.id()==site.source()||site.source()<4_000_000||site.scratch()<0||test<7||site.branch()!=test+1||yes<=site.branch()||site.jump()!=yes+1||site.alternative()<=site.jump()||no<=site.alternative()||site.end()!=no+1||site.end()>ops.size())throw new IOException("Conditional escapes its closed branch ordering");
        if(producers.stream().noneMatch(value->value.id()==site.id()&&value.kind().equals("JOIN_RESULT"))||producers.stream().noneMatch(value->value.id()==site.source()&&value.consumers().contains("JOIN")))throw new IOException("Conditional lacks its actual source/result producers");
        String runtime="php/runtime/PhpRuntimeOwnership";
        runtimeMethod(ops.get(test),pool,184,runtime,"conditionalTest","(Lphp/runtime/PhpRuntimeOwnership$Frame;IILphp/runtime/Memory;IZ)Z");
        if(ops.get(test-7).opcode()!=58||ops.get(test-7).operand()!=site.scratch()||ops.get(test-6).opcode()!=25||ops.get(test-6).operand()!=frame||runtimeSmallInt(ops.get(test-5),pool)!=site.id()||runtimeSmallInt(ops.get(test-4),pool)!=site.source()||ops.get(test-3).opcode()!=25||ops.get(test-3).operand()!=site.scratch()||runtimeSmallInt(ops.get(test-2),pool)!=site.kind()||runtimeSmallInt(ops.get(test-1),pool)!=(site.retainTrue()?1:0))throw new IOException("Conditional test changed its actual source/scratch/kind");
        int end=site.end()==ops.size()?ops.getLast().next():ops.get(site.end()).offset();
        if(ops.get(site.branch()).opcode()!=153||ops.get(site.branch()).operand()!=ops.get(site.alternative()).offset()||ops.get(site.jump()).opcode()!=167||ops.get(site.jump()).operand()!=end)throw new IOException("Conditional jumps do not select their exact arm/join");
        runtimeConditionalProducer(ops,pool,test-7,site.source(),frame,reads);
        for(boolean trueArm:new boolean[]{true,false}) {
            int at=trueArm?yes:no,source=trueArm?site.trueSource():site.falseSource();String literal=trueArm?site.trueLiteral():site.falseLiteral();
            if(at-7<=(trueArm?site.branch():site.alternative()-1))throw new IOException("Conditional scratch publication escapes its selected arm");
            runtimeMethod(ops.get(at),pool,184,runtime,"conditionalResult","(Lphp/runtime/PhpRuntimeOwnership$Frame;IIILphp/runtime/Memory;Z)Lphp/runtime/Memory;");
            if(at<7||ops.get(at-7).opcode()!=58||ops.get(at-7).operand()!=site.scratch()||ops.get(at-6).opcode()!=25||ops.get(at-6).operand()!=frame||runtimeSmallInt(ops.get(at-5),pool)!=site.id()||runtimeSmallInt(ops.get(at-4),pool)!=site.id()||runtimeSmallInt(ops.get(at-3),pool)!=source||ops.get(at-2).opcode()!=25||ops.get(at-2).operand()!=site.scratch()||runtimeSmallInt(ops.get(at-1),pool)!=(trueArm?1:0))throw new IOException("Conditional result changed its selected source/scratch/arm");
            if(source>=0) {
                if(!literal.isEmpty()||source==site.id()||producers.stream().noneMatch(value->value.id()==source&&value.consumers().contains("JOIN")))throw new IOException("Conditional arm lacks its actual child EVAL");
                if(trueArm&&site.retainTrue()) {
                    if(source!=site.source()||at<8||ops.get(at-8).opcode()!=25||ops.get(at-8).operand()!=site.scratch())throw new IOException("Retained true arm is not its original test operand");
                }else {
                    if(source==site.source())throw new IOException("Conditional uses a test EVAL after its retirement");
                    runtimeConditionalProducer(ops,pool,at-7,source,frame,reads);
                }
            }else if(source!=-1||!runtimeConditionalLiteral(ops,pool,at-7).equals(literal))throw new IOException("Conditional scalar arm lacks its exact primitive span");
        }
    }
    private static String runtimeConditionalLiteral(List<RuntimeInstruction> ops,Object[] pool,int store)throws IOException {
        if(store<1)throw new IOException("Conditional primitive has no actual producer");
        RuntimeInstruction raw=ops.get(store-1);
        if(raw.opcode()==178){runtimeMethod(raw,pool,178,"php/runtime/Memory","NULL","Lphp/runtime/Memory;");return "N";}
        runtimeMethod(raw,pool,184,"php/runtime/memory/StringMemory","valueOf","(Ljava/lang/String;)Lphp/runtime/Memory;");
        if(store<2)throw new IOException("Conditional STRING has no exact constant");return "S"+runtimeLiteralString(ops.get(store-2),pool);
    }
    private static void runtimeConditionalProducer(List<RuntimeInstruction> ops,Object[] pool,int store,int source,int frame,List<GlobalRead> reads)throws IOException {
        if(store<1)throw new IOException("Conditional EVAL has no actual producer span");int at=store-1;
        if(ops.get(at).opcode()==182){runtimeMethod(ops.get(at),pool,182,"php/runtime/Memory","toImmutable","()Lphp/runtime/Memory;");if(--at<0)throw new IOException("Conditional conversion has no producer");}
        if(at>=2&&ops.get(at).opcode()==58&&ops.get(at-1).opcode()==1&&runtimeNamedMethod(ops.get(at-2),pool,"php/runtime/PhpRuntimeOwnership","nativeResult")) {
            runtimeNativeResultEnd(ops,pool,at-2);at-=2;
        }
        final int helper=at;
        if(reads.stream().anyMatch(value->value.id()==source&&value.helper()==helper))return;
        if(runtimeNamedMethod(ops.get(at),pool,"php/runtime/PhpRuntimeOwnership","conditionalLocal")) {
            runtimeMethod(ops.get(at),pool,184,"php/runtime/PhpRuntimeOwnership","conditionalLocal","(Lphp/runtime/PhpRuntimeOwnership$Frame;IILphp/runtime/Memory;)Lphp/runtime/Memory;");
            if(at<4||ops.get(at-4).opcode()!=25||ops.get(at-4).operand()!=frame||runtimeSmallInt(ops.get(at-3),pool)!=source)throw new IOException("Conditional CV source changed");return;
        }
        if(runtimeNamedMethod(ops.get(at),pool,"php/runtime/PhpRuntimeOwnership","nativeResult")) {
            runtimeMethod(ops.get(at),pool,184,"php/runtime/PhpRuntimeOwnership","nativeResult","(Lphp/runtime/PhpRuntimeOwnership$NativePacket;Lphp/runtime/Memory;I)Lphp/runtime/Memory;");
            if(at<1||runtimeSmallInt(ops.get(at-1),pool)!=source)throw new IOException("Conditional native RESULT source changed");return;
        }
        if(runtimeNamedMethod(ops.get(at),pool,"php/runtime/PhpOwnershipArrays","runtimeFresh")) {
            runtimeMethod(ops.get(at),pool,184,"php/runtime/PhpOwnershipArrays","runtimeFresh","(Lphp/runtime/PhpRuntimeOwnership$Frame;ILphp/runtime/memory/ArrayMemory;)Lphp/runtime/memory/ArrayMemory;");
            if(at<3||ops.get(at-3).opcode()!=25||ops.get(at-3).operand()!=frame||runtimeSmallInt(ops.get(at-2),pool)!=source)throw new IOException("Conditional ARRAY source changed");return;
        }
        throw new IOException("Conditional arm has no immediate actual producer result");
    }
    /** Accept legacy RESULT spans and verify the exact new scratch retirement pair. */
    private static int runtimeNativeResultEnd(List<RuntimeInstruction> ops,Object[] pool,int result)throws IOException {
        if(result+2>=ops.size()||ops.get(result+1).opcode()!=1||ops.get(result+2).opcode()!=58)return result+1;
        runtimeMethod(ops.get(result),pool,184,"php/runtime/PhpRuntimeOwnership","nativeResult","(Lphp/runtime/PhpRuntimeOwnership$NativePacket;Lphp/runtime/Memory;I)Lphp/runtime/Memory;");
        if(result<4||ops.get(result-4).opcode()!=58||ops.get(result-2).opcode()!=25
                ||ops.get(result-4).operand()!=ops.get(result-2).operand()||ops.get(result+2).operand()!=ops.get(result-4).operand())
            throw new IOException("Native RESULT cleanup changed its actual raw scratch");
        return result+3;
    }
    /** Neutral instrumentation must carry the same complete physical typed-source proof as its runtime issuer. */
    private static void validateRuntimeTypedCreated(byte[] bytes,String owner,String method,String descriptor,RuntimeCode code,Object[] pool,int at,ObjectNew source,ObjectCv cv)throws IOException {
        var ops=code.instructions();
        runtimeMethod(ops.get(at+7),pool,184,PhpTypedSourcePlan.RUNTIME,"created",PhpTypedSourcePlan.OBSERVER);
        if((ops.get(at+1).opcode()!=18&&ops.get(at+1).opcode()!=19)||!runtimeClass(pool,ops.get(at+1).operand()).equals(source.owner())
                ||(ops.get(at+2).opcode()!=18&&ops.get(at+2).opcode()!=19)||!runtimeClass(pool,ops.get(at+2).operand()).equals(owner)
                ||!runtimeLiteralString(ops.get(at+3),pool).equals(method)||!runtimeLiteralString(ops.get(at+4),pool).equals(descriptor)
                ||runtimeSmallInt(ops.get(at+6),pool)!=cv.local())throw new IOException("Object CV observer changed its exact declaration/member/address");
        int id=runtimeSmallInt(ops.get(at+5),pool);PhpTypedSourcePlan.Draft proof=null;
        for(var member:java.lang.classfile.ClassFile.of().parse(bytes).methods())if(member.methodName().equalsString(method)&&member.methodType().equalsString(descriptor))proof=PhpTypedSourcePlan.inspect(member).get(id);
        if(proof==null||proof.local()!=cv.local()||!proof.declaration().equals(source.owner())||proof.createdBci()!=ops.get(at+7).offset())throw new IOException("Object CV observer lacks its complete exact typed-source proof");
        runtimeTransportIngress(code,at+1,at+8);
    }
    /** FIELD retains the original raw reference publication; CELL metadata describes that same exact producer. */
    private static int validateRuntimeFieldExport(byte[] payload,String owner,String method,String descriptor,RuntimeCode code,Object[] pool,PhpOwnershipExecution.FramePlan declared,int at)throws IOException {
        var ops=code.instructions();if(at>=ops.size())throw new IOException("FIELD export escapes its actual body");
        if(runtimeNamedMethod(ops.get(at),pool,"php/runtime/memory/ReferenceMemory","exportReference")) {
            runtimeMethod(ops.get(at),pool,184,"php/runtime/memory/ReferenceMemory","exportReference","(Lphp/runtime/Memory;)Lphp/runtime/Memory;");return at;
        }
        if(at+4>=ops.size())throw new IOException("FIELD export omits its CELL metadata");
        runtimeMethod(ops.get(at+4),pool,184,PhpPrimitiveCellPlan.RUNTIME,"export","(Lphp/runtime/Memory;Ljava/lang/Class;Ljava/lang/String;Ljava/lang/String;I)Lphp/runtime/Memory;");
        if((ops.get(at).opcode()!=18&&ops.get(at).opcode()!=19)||!runtimeClass(pool,ops.get(at).operand()).equals(owner)
                ||!runtimeLiteralString(ops.get(at+1),pool).equals(method)||!runtimeLiteralString(ops.get(at+2),pool).equals(descriptor))throw new IOException("FIELD export changed its exact member operands");
        int id=runtimeSmallInt(ops.get(at+3),pool);validateRuntimeCellExportRow(payload,owner,method,descriptor,declared,id);
        int occurrences=0;for(int i=4;i<ops.size();i++)if(runtimeNamedMethod(ops.get(i),pool,PhpPrimitiveCellPlan.RUNTIME,"export")&&runtimeSmallInt(ops.get(i-1),pool)==id)occurrences++;
        if(occurrences!=1)throw new IOException("FIELD export repeats its physical site");runtimeTransportIngress(code,at,at+5);return at+4;
    }
    private static void validateRuntimeCellExportRow(byte[] payload,String owner,String method,String descriptor,PhpOwnershipExecution.FramePlan declared,int site)throws IOException {
        if(payload==null||declared==null)throw new IOException("FIELD export lacks its physical CELL/common CV plans");
        var input=new DataInputStream(new ByteArrayInputStream(payload));
        if(input.readInt()!=1||!input.readUTF().equals(owner)||!input.readUTF().equals(method)||!input.readUTF().equals(descriptor))throw new IOException("FIELD export changed its CELL plan member");
        input.readBoolean();Map<Integer,String> locals=new LinkedHashMap<>();
        for(int i=0,n=runtimeCount(input);i<n;i++)if(locals.putIfAbsent(input.readInt(),input.readUTF())!=null)throw new IOException("Duplicate FIELD export CV");
        Map<Integer,String> actual=new LinkedHashMap<>();for(var cv:declared.locals())if(cv.id()!=cv.jvmSlot()||actual.putIfAbsent(cv.jvmSlot(),cv.name())!=null)throw new IOException("Ambiguous FIELD export CV address");
        if(!actual.equals(locals))throw new IOException("FIELD export CELL addresses differ from actual common CV plan");
        Set<Integer> statics=new HashSet<>();Set<String> keys=new HashSet<>();for(int i=0,n=runtimeCount(input);i<n;i++){int local=input.readInt();String key=input.readUTF();if(!locals.containsKey(local)||!statics.add(local)||key.isEmpty()||!keys.add(key))throw new IOException("Invalid FIELD export static row");}
        boolean matched=false;Set<Integer> sites=new HashSet<>();for(int i=0,n=runtimeCount(input);i<n;i++){int id=input.readInt();String helper=input.readUTF();int local=input.readInt();if(id<0||!sites.add(id))throw new IOException("Duplicate FIELD export site");if(id==site)matched=helper.equals("export")&&local==-1;}
        if(input.read()!=-1||!matched)throw new IOException("FIELD result export lacks its exact non-CV source row");
    }
    /** No foreign branch, switch or handler may enter an attested stack operand/metadata span. */
    private static void runtimeTransportIngress(RuntimeCode code,int first,int end)throws IOException {
        var ops=code.instructions();int start=ops.get(first).offset(),last=end==ops.size()?ops.getLast().next():ops.get(end).offset();
        for(int i=0;i<ops.size();i++)if(i<first||i>=end){var op=ops.get(i);int opcode=op.opcode();
            if((opcode>=153&&opcode<=168||opcode==198||opcode==199||opcode==200||opcode==201)&&op.operand()>=start&&op.operand()<last)throw new IOException("Foreign branch enters a physical transport envelope");
            for(int target:code.switches().getOrDefault(op.offset(),List.of()))if(target>=start&&target<last)throw new IOException("Foreign switch enters a physical transport envelope");
        }
        for(var guard:code.guards())if(guard.handler()>=start&&guard.handler()<last)throw new IOException("Foreign handler enters a physical transport envelope");
    }
    private static boolean runtimeMutatesLocal(List<RuntimeInstruction> ops,Object[] pool,int at,int local)throws IOException {
        RuntimeInstruction op=ops.get(at);if(op.opcode()!=182&&op.opcode()!=184)return false;
        if(op.operand()<=0||op.operand()>=pool.length||!(pool[op.operand()] instanceof RuntimePair))throw new IOException("Invalid receiver mutation member");
        var member=(RuntimePair)pool[op.operand()];
        if(member.second()<=0||member.second()>=pool.length||!(pool[member.second()] instanceof RuntimePair))throw new IOException("Invalid receiver mutation type");
        var type=(RuntimePair)pool[member.second()];String owner=runtimeClass(pool,member.first()),name=runtimeUtf(pool,type.first());int distance;String descriptor;
        if(owner.equals("php/runtime/PhpRuntimeOwnership$Frame")&&op.opcode()==182) {
            if(name.equals("assignLocal")){distance=3;descriptor="(ILphp/runtime/Memory;I)Lphp/runtime/Memory;";}
            else if(name.equals("assignLocalFromEval")){distance=3;descriptor="(IILphp/runtime/Memory;I)Lphp/runtime/Memory;";}
            else if(name.equals("unsetLocal")){distance=2;descriptor="(ILphp/runtime/Memory;)V";}
            else return false;
        }else if(owner.equals("php/runtime/PhpRuntimeOwnership")&&op.opcode()==184&&name.equals("unsetLocal")) {
            distance=3;descriptor="(Lphp/runtime/PhpRuntimeOwnership$Frame;ILphp/runtime/Memory;Lphp/runtime/env/Environment;)V";
        }else if(owner.equals("php/runtime/PhpPrimitiveCellRuntime")&&op.opcode()==184&&name.equals("unset")) {
            distance=7;descriptor="(Lphp/runtime/PhpRuntimeOwnership$Frame;ILphp/runtime/Memory;Lphp/runtime/env/Environment;Ljava/lang/Class;Ljava/lang/String;Ljava/lang/String;I)V";
        }else return false;
        runtimeMethod(op,pool,op.opcode(),owner,name,descriptor);
        if(at<distance)throw new IOException("Receiver mutation lacks its actual local operand");
        return runtimeSmallInt(ops.get(at-distance),pool)==local;
    }
    private static boolean runtimeRequiresCellResult(RuntimeCode code,Object[] pool,int id,int frameLocal)throws IOException {
        var instructions=code.instructions();boolean required=false;
        for(int i=0;i<instructions.size();i++) {
            RuntimeInstruction instruction=instructions.get(i);if(instruction.opcode()!=182)continue;
            if(instruction.operand()<=0||instruction.operand()>=pool.length||!(pool[instruction.operand()] instanceof RuntimePair))throw new IOException("Invalid CALL consumer member");
            RuntimePair member=(RuntimePair)pool[instruction.operand()];
            if(member.second()<=0||member.second()>=pool.length||!(pool[member.second()] instanceof RuntimePair))throw new IOException("Invalid CALL consumer type");
            RuntimePair type=(RuntimePair)pool[member.second()];if(!runtimeClass(pool,member.first()).equals("php/runtime/PhpRuntimeOwnership$Frame"))continue;
            String name=runtimeUtf(pool,type.first());
            if(name.equals("assignLocalFromEval")&&i>=5&&runtimeSmallInt(instructions.get(i-4),pool)==id) {
                runtimeMethod(instruction,pool,182,"php/runtime/PhpRuntimeOwnership$Frame",name,"(IILphp/runtime/Memory;I)Lphp/runtime/Memory;");
                if(instructions.get(i-5).opcode()!=25||instructions.get(i-5).operand()!=frameLocal)throw new IOException("CALL CV consumer uses another Frame");
                int mode=runtimeSmallInt(instructions.get(i-1),pool);if(mode!=0&&mode!=1)throw new IOException("Invalid CALL CV consumer role");required|=mode==1;
            }else if(name.equals("prepareReturn")&&i>=4&&runtimeSmallInt(instructions.get(i-2),pool)==id) {
                runtimeMethod(instruction,pool,182,"php/runtime/PhpRuntimeOwnership$Frame",name,"(Lphp/runtime/Memory;II)Lphp/runtime/PhpRuntimeOwnership$Publication;");
                if(instructions.get(i-4).opcode()!=25||instructions.get(i-4).operand()!=frameLocal)throw new IOException("CALL RETURN consumer uses another Frame");
                int mode=runtimeSmallInt(instructions.get(i-1),pool);if(mode!=0&&mode!=1)throw new IOException("Invalid CALL RETURN consumer role");required|=mode==1;
            }
        }
        return required;
    }
    /** The debug tick can join a borrowed self discard only through its exact generated guards. */
    private static boolean runtimeLifecycleTickJoin(RuntimeCode code,Object[] pool,String owner,LifecycleReceiver imported,int read)throws IOException {
        var ops=code.instructions();int start=imported.end(),first=imported.start();
        if(start+10!=read||ops.get(read-2).opcode()!=167||ops.get(read-2).operand()!=ops.get(read).offset())return false;
        if(ops.get(start).opcode()!=25||ops.get(start).operand()!=1||runtimeSmallInt(ops.get(start+2),pool)<0||ops.get(start+3).opcode()!=50||ops.get(start+4).opcode()!=25||ops.get(start+5).opcode()!=25||ops.get(start+6).opcode()!=198||ops.get(start+6).operand()!=ops.get(start+9).offset())throw new IOException("Lifecycle discard tick changed its exact operands/guard");
        runtimeMethod(ops.get(start+1),pool,178,owner,"$TRC","[Lphp/runtime/env/TraceInfo;");
        runtimeMethod(ops.get(start+7),pool,184,"php/runtime/PhpOwnershipNativeCalls","tickEmpty","(Lphp/runtime/env/Environment;Lphp/runtime/env/TraceInfo;Lphp/runtime/memory/ArrayMemory;)V");
        runtimeMethod(ops.get(start+9),pool,182,"php/runtime/env/Environment","__tick","(Lphp/runtime/env/TraceInfo;Lphp/runtime/memory/ArrayMemory;)V");
        int entry=ops.get(start+5).operand(),table=ops.get(start+4).operand(),entryCall=-1,frameCall=-1,entryStores=0,tableStores=0;
        for(int i=4;i+1<ops.size();i++) {
            if(runtimeNamedMethod(ops.get(i),pool,"php/runtime/PhpOwnershipExecution","enterGenerated")) {
                if(entryCall>=0)throw new IOException("Lifecycle tick has multiple Session entries");entryCall=i;
                runtimeMethod(ops.get(i),pool,184,"php/runtime/PhpOwnershipExecution","enterGenerated","(Lphp/runtime/env/Environment;Ljava/lang/Class;Ljava/lang/String;Ljava/lang/String;)Lphp/runtime/PhpOwnershipExecution$Entry;");
                if(ops.get(i+1).opcode()!=58||ops.get(i+1).operand()!=entry||ops.get(i-4).opcode()!=25||ops.get(i-4).operand()!=1)throw new IOException("Lifecycle tick uses another Session entry");
            }
            if(runtimeNamedMethod(ops.get(i),pool,"php/runtime/PhpRuntimeOwnership","enterGenerated"))frameCall=i;
        }
        if(entryCall<0||frameCall!=entryCall+6)throw new IOException("Lifecycle tick lacks adjacent generated entry targets");
        for(int back=1;back<=4;back++)if(ops.get(entryCall-back).opcode()!=ops.get(frameCall-back).opcode()||ops.get(entryCall-back).operand()!=ops.get(frameCall-back).operand())throw new IOException("Lifecycle tick Session target differs from its Frame");
        for(var op:ops)if(op.opcode()==58){if(op.operand()==entry)entryStores++;if(op.operand()==table)tableStores++;}
        if(entryStores!=1||tableStores!=1||first<8||ops.get(first-8).opcode()!=25||ops.get(first-8).operand()!=entry||ops.get(first-7).opcode()!=198||ops.get(first-7).operand()!=ops.get(first-3).offset()||ops.get(first-6).opcode()!=25||ops.get(first-6).operand()!=entry||ops.get(first-4).opcode()!=167||ops.get(first-4).operand()!=ops.get(first-1).offset()||ops.get(first-3).opcode()!=1||ops.get(first-1).opcode()!=58||ops.get(first-1).operand()!=table)throw new IOException("Lifecycle tick lacks its exact debug local-table publication");
        runtimeMethod(ops.get(first-5),pool,182,"php/runtime/PhpOwnershipExecution$Entry","localTable","()Lphp/runtime/memory/ArrayMemory;");
        runtimeMethod(ops.get(first-2),pool,184,"php/runtime/memory/ArrayMemory","valueOfRef","(Lphp/runtime/memory/ArrayMemory;)Lphp/runtime/memory/ArrayMemory;");
        int begin=ops.get(start).offset(),end=ops.get(read).offset();
        for(int position=0;position<ops.size();position++)for(int destination:runtimeFlowTargets(code,position))if(destination>=begin&&destination<end) {
            boolean importJoin=destination==begin&&(ops.get(position).opcode()==167||ops.get(position).opcode()==200)&&(position==imported.helper()+2||(position>=imported.fallback()&&position<imported.end()));
            if(position!=start+6&&!importJoin)throw new IOException("Lifecycle discard tick has an external guard bypass");
        }
        for(var guard:code.guards())if(guard.handler()>=begin&&guard.handler()<end)throw new IOException("Lifecycle discard tick has an external exception entry");
        return true;
    }

    /** Every ordinary native factory must identify its recorded member before any module effects. */
    private static Map<Integer,Integer> validateRuntimeNativeFactories(RuntimeCode code,Object[] pool,List<NativeMember> members)throws IOException {
        var ops=code.instructions();Map<Integer,Integer> factories=new HashMap<>();int frame=runtimeGeneratedFrame(code,pool);
        for(int at=0;at<ops.size();at++)if(runtimeNamedMethod(ops.get(at),pool,"php/runtime/PhpRuntimeOwnership","nativeCall")) {
            runtimeMethod(ops.get(at),pool,184,"php/runtime/PhpRuntimeOwnership","nativeCall","(Lphp/runtime/PhpRuntimeOwnership$Frame;ILjava/lang/Class;Ljava/lang/String;Ljava/lang/String;I)Lphp/runtime/PhpRuntimeOwnership$NativePacket;");
            if(at<6||ops.get(at-6).opcode()!=25||ops.get(at-6).operand()!=frame)throw new IOException("Native factory lost its exact generated Frame");
            int id=runtimeSmallInt(ops.get(at-5),pool);NativeMember member=members.stream().filter(value->value.id()==id).findFirst().orElseThrow(()->new IOException("Native factory has no exact recorded member"));
            var owner=ops.get(at-4);
            if(factories.putIfAbsent(id,at)!=null||(owner.opcode()!=18&&owner.opcode()!=19)||!runtimeClass(pool,owner.operand()).equals(member.owner())||!runtimeString(ops.get(at-3),pool).equals(member.name())||!runtimeString(ops.get(at-2),pool).equals(member.descriptor())||runtimeSmallInt(ops.get(at-1),pool)!=member.argc())throw new IOException("Native factory differs from its exact member");
        }
        if(factories.size()!=members.size())throw new IOException("Native dependency lacks its exact generated factory");
        return factories;
    }

    /** Legacy Session operands cannot execute in an entered ordinary Frame; validate their actual guard. */
    private static Set<Integer> runtimeInactiveComparisonSessionLanes(RuntimeCode code,Object[] pool)throws IOException {
        var ops=code.instructions();int frame=runtimeGeneratedFrame(code,pool),environment=runtimeGeneratedEnvironment(code,pool,frame),entry=-1,entryCall=-1,frameCall=-1;
        for(int at=4;at+1<ops.size();at++) {
            if(runtimeNamedMethod(ops.get(at),pool,"php/runtime/PhpRuntimeOwnership","enterGenerated"))frameCall=at;
            if(!runtimeNamedMethod(ops.get(at),pool,"php/runtime/PhpOwnershipExecution","enterGenerated"))continue;
            runtimeMethod(ops.get(at),pool,184,"php/runtime/PhpOwnershipExecution","enterGenerated","(Lphp/runtime/env/Environment;Ljava/lang/Class;Ljava/lang/String;Ljava/lang/String;)Lphp/runtime/PhpOwnershipExecution$Entry;");
            if(entry>=0||ops.get(at-4).opcode()!=25||ops.get(at-4).operand()!=environment||ops.get(at+1).opcode()!=58)throw new IOException("Inactive comparison Session has no exact entry publication");entry=ops.get(at+1).operand();entryCall=at;
        }
        if(entry<0)return Set.of();
        if(frameCall!=entryCall+6)throw new IOException("Inactive comparison Session has no adjacent ordinary entry");
        for(int back=1;back<=4;back++)if(ops.get(entryCall-back).opcode()!=ops.get(frameCall-back).opcode()||ops.get(entryCall-back).operand()!=ops.get(frameCall-back).operand())throw new IOException("Inactive comparison entries use different target operands");
        int entryStores=0,frameStores=0;for(var op:ops)if(op.opcode()==58){if(op.operand()==entry)entryStores++;if(op.operand()==frame)frameStores++;}
        if(entryStores!=1||frameStores!=1)throw new IOException("Inactive comparison entry was replaced");
        Set<Integer> inactive=new HashSet<>();
        for(int guard=1;guard+1<ops.size();guard++)if(ops.get(guard).opcode()==198&&ops.get(guard-1).opcode()==25&&ops.get(guard-1).operand()==entry) {
            int first=ops.get(guard+1).offset(),end=ops.get(guard).operand();if(end<=first)continue;boolean closed=true;
            for(int at=0;at<ops.size();at++)for(int destination:runtimeFlowTargets(code,at))if(destination>=first&&destination<end&&(ops.get(at).offset()<first||ops.get(at).offset()>=end))closed=false;
            for(var handler:code.guards())if(handler.handler()>=first&&handler.handler()<end&&(handler.start()<first||handler.end()>end))closed=false;
            if(closed)for(int at=guard+1;at<ops.size()&&ops.get(at).offset()<end;at++)inactive.add(at);
        }
        return inactive;
    }

    /** The direct comparison lane is executable only with its exact generated Frame null. */
    private static void validateRuntimeComparisonFallbacks(String owner,RuntimeCode code,Object[] pool,List<NativeMember> members,Map<Integer,Integer> factories,Map<Integer,Integer> bodies,Map<Integer,Set<Integer>> borrows,Map<Integer,Integer> fallbacks)throws IOException {
        var ops=code.instructions();int frame=runtimeGeneratedFrame(code,pool),environment=runtimeGeneratedEnvironment(code,pool,frame);
        Set<Integer> claimedBodies=new HashSet<>();for(NativeMember member:members)if(Set.of("php/runtime/PhpComparisonCalls","php/runtime/PhpComparison").contains(member.owner()))claimedBodies.add(bodies.get(member.id()));
        Set<Integer> paired=new HashSet<>();
        if(!fallbacks.isEmpty()){int stores=0;for(var op:ops)if(op.opcode()==58&&op.operand()==frame)stores++;if(stores!=1)throw new IOException("Comparison direct lane requires its unchanged entered Frame");}
        for(var fallback:fallbacks.entrySet()) {
            int at=fallback.getValue();var op=ops.get(at);
            if(at<10||at+2>=ops.size())throw new IOException("Comparison fallback lacks its exact direct span");
            NativeMember expected=members.stream().filter(member->member.id()==fallback.getKey()).findFirst().orElseThrow(()->new IOException("Comparison fallback lacks its exact recorded twin"));
            String name=expected.name(),descriptor=expected.descriptor();
            if(!Set.of("equal","notEqual","smaller","smallerEq","greater","greaterEq","spaceshipCompare").contains(name))throw new IOException("Comparison fallback has an unknown selected body");
            boolean booleanResult=descriptor.endsWith(")Z"),boxedBoolean=booleanResult&&runtimeNamedMethod(ops.get(at+1),pool,"php/runtime/memory/TrueMemory","valueOf");int jump=at+(boxedBoolean?2:1),start=jump+1,factory=start+6;
            if(factory>=ops.size())throw new IOException("Comparison fallback has no managed twin");
            int id=runtimeSmallInt(ops.get(factory-5),pool);NativeMember selected=members.stream().filter(member->member.id()==id).findFirst().orElseThrow(()->new IOException("Comparison fallback has no recorded managed twin"));
            if(id!=fallback.getKey()||!selected.owner().equals("php/runtime/PhpComparisonCalls")||!selected.name().equals(name)||!selected.descriptor().equals(descriptor)||selected.argc()!=2||factories.getOrDefault(id,-1)!=factory||!paired.add(id))throw new IOException("Comparison fallback differs from its selected BODY");
            boolean directBoolean=booleanResult&&runtimeNamedMethod(op,pool,"php/runtime/PhpComparison",selected.name());
            runtimeMethod(op,pool,184,directBoolean?"php/runtime/PhpComparison":selected.owner(),selected.name(),selected.descriptor());
            if(boxedBoolean)runtimeMethod(ops.get(at+1),pool,184,"php/runtime/memory/TrueMemory","valueOf","(Z)Lphp/runtime/Memory;");
            else if(!booleanResult&&!descriptor.endsWith(")Lphp/runtime/Memory;"))throw new IOException("Comparison fallback result changed");
            if(ops.get(at-10).opcode()!=58||ops.get(at-9).opcode()!=58||ops.get(at-8).opcode()!=25||ops.get(at-8).operand()!=frame||ops.get(at-7).opcode()!=199||ops.get(at-7).operand()!=ops.get(start).offset()
                    ||ops.get(at-6).opcode()!=25||ops.get(at-6).operand()!=ops.get(at-9).operand()||ops.get(at-5).opcode()!=25||ops.get(at-5).operand()!=ops.get(at-10).operand()||ops.get(at-4).opcode()!=25||ops.get(at-4).operand()!=environment||ops.get(at-1).opcode()!=50||ops.get(jump).opcode()!=167&&ops.get(jump).opcode()!=200)throw new IOException("Comparison fallback lost its exact Frame/operand/Environment guard");
            runtimeMethod(ops.get(at-3),pool,178,owner,"$TRC","[Lphp/runtime/env/TraceInfo;");if(runtimeSmallInt(ops.get(at-2),pool)<0)throw new IOException("Comparison fallback trace changed");
            var sends=borrows.getOrDefault(id,Set.of());if(sends.size()!=2)throw new IOException("Comparison managed twin lost its two SENDs");
            for(int send:sends) {
                int argument=runtimeSmallInt(ops.get(send-5),pool);int actual=argument==0?ops.get(at-9).operand():argument==1?ops.get(at-10).operand():-1;
                if(actual<0||send<8||ops.get(send-8).opcode()!=25||ops.get(send-8).operand()!=actual||ops.get(send-7).opcode()!=58||ops.get(send-4).opcode()!=25||ops.get(send-4).operand()!=ops.get(send-7).operand()||ops.get(send-2).opcode()!=3||ops.get(send-1).opcode()!=3)throw new IOException("Comparison lanes no longer use the same actual operands");
            }
            int result=-1;for(int next=bodies.get(id)+1;next+1<ops.size();next++)if(runtimeNamedMethod(ops.get(next),pool,"php/runtime/PhpRuntimeOwnership","nativeResult")){result=next;break;}
            int afterResult=result<0?-1:runtimeNativeResultEnd(ops,pool,result);
            int join=afterResult+(booleanResult&&!boxedBoolean?1:0);
            if(result<0||join>=ops.size()||runtimeSmallInt(ops.get(result-1),pool)!=id||ops.get(jump).operand()!=ops.get(join).offset())throw new IOException("Comparison fallback bypasses its actual RESULT join");
            if(booleanResult&&!boxedBoolean)runtimeMethod(ops.get(afterResult),pool,182,"php/runtime/Memory","toBoolean","()Z");
            int first=ops.get(at-10).offset(),end=ops.get(start).offset();
            for(int position=0;position<ops.size();position++)for(int destination:runtimeFlowTargets(code,position))if(destination>=first&&destination<end)throw new IOException("External branch enters the direct comparison operands");
            for(var guard:code.guards())if(guard.handler()>=first&&guard.handler()<end)throw new IOException("Exception handler enters the direct comparison lane");
        }
        Set<Integer> claimed=new HashSet<>(claimedBodies);if(!claimed.addAll(fallbacks.values())&&!fallbacks.isEmpty())throw new IOException("Comparison direct lane overlaps its managed BODY");
        if(claimed.size()!=claimedBodies.size()+fallbacks.size())throw new IOException("Comparison direct lane has duplicate opcode claims");
        Set<Integer> inactiveSession=null;
        for(int at=0;at<ops.size();at++)if(ops.get(at).opcode()==184&&!claimed.contains(at)) {
            var op=ops.get(at);if(op.operand()<=0||op.operand()>=pool.length||!(pool[op.operand()] instanceof RuntimePair reference))throw new IOException("Invalid comparison member constant");
            if(Set.of("php/runtime/PhpComparisonCalls","php/runtime/PhpComparison").contains(runtimeClass(pool,reference.first()))) {
                if(inactiveSession==null)inactiveSession=runtimeInactiveComparisonSessionLanes(code,pool);
                if(!inactiveSession.contains(at))throw new IOException("Comparison call lacks its exact managed BODY or direct guard");
            }
        }
    }

    /** Only the guarded Session lane of an attested native envelope is unreachable for an ordinary Frame. */
    private static Set<Integer> runtimeInactiveNativeSessionReads(RuntimeCode code,Object[] pool,List<NativeMember> members)throws IOException {
        Set<Integer> excluded=new HashSet<>();var ops=code.instructions();int frame=runtimeGeneratedFrame(code,pool),env=runtimeGeneratedEnvironment(code,pool,frame),entry=-1,entryCall=-1,frameCall=-1;
        String runtime="php/runtime/PhpRuntimeOwnership",session="php/runtime/PhpOwnershipExecution";
        for(int i=4;i+1<ops.size();i++) {
            if(runtimeNamedMethod(ops.get(i),pool,runtime,"enterGenerated"))frameCall=i;
            if(!runtimeNamedMethod(ops.get(i),pool,session,"enterGenerated"))continue;
            runtimeCall(ops.get(i),pool,session,"enterGenerated","(Lphp/runtime/env/Environment;Ljava/lang/Class;Ljava/lang/String;Ljava/lang/String;)Lphp/runtime/PhpOwnershipExecution$Entry;");
            if(entry>=0||ops.get(i-4).opcode()!=25||ops.get(i-4).operand()!=env||ops.get(i+1).opcode()!=58)throw new IOException("Inactive Session lane lacks its exact Environment/entry publication");entry=ops.get(i+1).operand();entryCall=i;
        }
        if(entry<0)return excluded;
        if(frameCall!=entryCall+6)throw new IOException("Inactive Session lane requires adjacent generated entry publications");
        for(int back=1;back<=4;back++)if(ops.get(entryCall-back).opcode()!=ops.get(frameCall-back).opcode()||ops.get(entryCall-back).operand()!=ops.get(frameCall-back).operand())throw new IOException("Inactive Session lane uses different generated target operands");
        int publications=0;for(var op:ops)if(op.opcode()==58&&op.operand()==entry)publications++;
        if(publications!=1)throw new IOException("Inactive Session entry was replaced");
        for(int factory=6;factory+1<ops.size();factory++)if(runtimeNamedMethod(ops.get(factory),pool,runtime,"nativeCall")) {
            runtimeCall(ops.get(factory),pool,runtime,"nativeCall","(Lphp/runtime/PhpRuntimeOwnership$Frame;ILjava/lang/Class;Ljava/lang/String;Ljava/lang/String;I)Lphp/runtime/PhpRuntimeOwnership$NativePacket;");
            int start=factory-6;if(ops.get(start).opcode()!=25||ops.get(start).operand()!=frame)continue;
            int id=runtimeSmallInt(ops.get(factory-5),pool);NativeMember member=members.stream().filter(value->value.id()==id).findFirst().orElse(null);if(member==null)continue;
            var owner=ops.get(factory-4);if((owner.opcode()!=18&&owner.opcode()!=19)||!runtimeClass(pool,owner.operand()).equals(member.owner())||!runtimeString(ops.get(factory-3),pool).equals(member.name())||!runtimeString(ops.get(factory-2),pool).equals(member.descriptor())||runtimeSmallInt(ops.get(factory-1),pool)!=member.argc())throw new IOException("Native factory differs from its exact member");
            if(start<2||ops.get(start-1).opcode()!=167||ops.get(factory+1).opcode()!=58)continue;
            int joinOffset=ops.get(start-1).operand(),packet=ops.get(factory+1).operand(),result=-1;
            for(int i=factory+5;i+1<ops.size()&&ops.get(i).offset()<joinOffset;i++)if(runtimeNamedMethod(ops.get(i),pool,runtime,"nativeResult")) {
                runtimeCall(ops.get(i),pool,runtime,"nativeResult","(Lphp/runtime/PhpRuntimeOwnership$NativePacket;Lphp/runtime/Memory;I)Lphp/runtime/Memory;");
                if(ops.get(i-3).opcode()==25&&ops.get(i-3).operand()==packet&&runtimeSmallInt(ops.get(i-1),pool)==id&&ops.get(runtimeNativeResultEnd(ops,pool,i)).offset()==joinOffset)result=i;
            }
            if(result<0)continue;
            boolean selectedBody=false;for(int i=factory+4;i<result;i++)if(runtimeNamedMethod(ops.get(i),pool,member.physicalOwner(),member.physicalName())) {
                runtimeCall(ops.get(i),pool,member.physicalOwner(),member.physicalName(),member.physicalDescriptor());
                if(runtimeNamedMethod(ops.get(i-2),pool,runtime,"nativeEnterBody")&&ops.get(i-3).opcode()==25&&ops.get(i-3).operand()==packet)selectedBody=true;
            }
            if(!selectedBody)continue;
            int guard=-1;for(int i=1;i<start-1;i++)if(ops.get(i).opcode()==198&&ops.get(i).operand()==ops.get(start).offset()&&ops.get(i-1).opcode()==25&&ops.get(i-1).operand()==entry)guard=i;
            if(guard<0)continue;
            int first=ops.get(guard+1).offset(),end=ops.get(start).offset();boolean closed=true;
            for(int i=0;i<ops.size();i++) {
                var op=ops.get(i);if(i>guard&&i<start)continue;
                if(op.opcode()==170||op.opcode()==171||op.opcode()==168||op.opcode()==169||op.opcode()==201){closed=false;break;}
                if((op.opcode()>=153&&op.opcode()<=167||op.opcode()==198||op.opcode()==199||op.opcode()==200)&&op.operand()>=first&&op.operand()<end){closed=false;break;}
            }
            for(var handler:code.guards())if(handler.handler()>=first&&handler.handler()<end)closed=false;
            if(closed)for(int i=guard+1;i<start;i++)excluded.add(i);
        }
        return excluded;
    }
    private static boolean runtimeNamedMethod(RuntimeInstruction op,Object[] pool,String owner,String name)throws IOException {
        return runtimeNamedMethod(op,pool,184,owner,name);
    }
    private static boolean runtimeNamedMethod(RuntimeInstruction op,Object[] pool,int opcode,String owner,String name)throws IOException {
        if(op.opcode()!=opcode)return false;
        if(op.operand()<=0||op.operand()>=pool.length||!(pool[op.operand()] instanceof RuntimePair))throw new IOException("Invalid runtime method constant");
        var member=(RuntimePair)pool[op.operand()];if(member.second()<=0||member.second()>=pool.length||!(pool[member.second()] instanceof RuntimePair))throw new IOException("Invalid runtime method type");
        var type=(RuntimePair)pool[member.second()];return runtimeClass(pool,member.first()).equals(owner)&&runtimeUtf(pool,type.first()).equals(name);
    }
    private static int runtimeGeneratedEnvironment(RuntimeCode code,Object[] pool,int frameLocal)throws IOException {
        var instructions=code.instructions();for(int i=4;i+1<instructions.size();i++)if(instructions.get(i+1).opcode()==58&&instructions.get(i+1).operand()==frameLocal) {
            runtimeCall(instructions.get(i),pool,"php/runtime/PhpRuntimeOwnership","enterGenerated","(Lphp/runtime/env/Environment;Ljava/lang/Class;Ljava/lang/String;Ljava/lang/String;)Lphp/runtime/PhpRuntimeOwnership$Frame;");
            if(instructions.get(i-4).opcode()!=25)throw new IOException("Generated entry has no exact Environment local");return instructions.get(i-4).operand();
        }throw new IOException("Generated frame has no exact Environment publication");
    }
    private static int runtimeGeneratedFrame(RuntimeCode code,Object[] pool)throws IOException {
        int frame=-1;var instructions=code.instructions();
        for(int i=0;i<instructions.size();i++) {
            RuntimeInstruction instruction=instructions.get(i);if(instruction.opcode()!=184)continue;
            if(instruction.operand()<=0||instruction.operand()>=pool.length||!(pool[instruction.operand()] instanceof RuntimePair))throw new IOException("Invalid generated method constant");
            RuntimePair member=(RuntimePair)pool[instruction.operand()];
            if(member.second()<=0||member.second()>=pool.length||!(pool[member.second()] instanceof RuntimePair))throw new IOException("Invalid generated method type");
            RuntimePair type=(RuntimePair)pool[member.second()];
            if(!runtimeClass(pool,member.first()).equals("php/runtime/PhpRuntimeOwnership")||!runtimeUtf(pool,type.first()).equals("enterGenerated"))continue;
            runtimeCall(instruction,pool,"php/runtime/PhpRuntimeOwnership","enterGenerated","(Lphp/runtime/env/Environment;Ljava/lang/Class;Ljava/lang/String;Ljava/lang/String;)Lphp/runtime/PhpRuntimeOwnership$Frame;");
            if(frame>=0||i+1>=instructions.size()||instructions.get(i+1).opcode()!=58)throw new IOException("CALL has no unique actual generated Frame publication");frame=instructions.get(i+1).operand();
        }
        if(frame<0)throw new IOException("CALL has no actual generated Frame entry");return frame;
    }
    private record RuntimeString(int utf) {}
    private record RuntimePair(int first,int second) {}
    private record RuntimeGuard(int start,int end,int handler,String type) {}
    private record RuntimeInstruction(int offset,int next,int opcode,int operand) {}
    private record RuntimeCode(List<RuntimeInstruction> instructions,List<RuntimeGuard> guards,Map<Integer,List<Integer>> switches) {}
    private static List<Integer> runtimeFlowTargets(RuntimeCode code,int ordinal) {
        RuntimeInstruction op=code.instructions().get(ordinal);
        if(op.opcode()==170||op.opcode()==171)return code.switches().getOrDefault(ordinal,List.of());
        return op.opcode()>=153&&op.opcode()<=168||op.opcode()==198||op.opcode()==199||op.opcode()==200||op.opcode()==201?List.of(op.operand()):List.of();
    }
    private static RuntimeCode runtimeCode(byte[] bytes,Object[] pool)throws IOException {
        DataInputStream in=new DataInputStream(new ByteArrayInputStream(bytes));in.readUnsignedShort();in.readUnsignedShort();
        int length=in.readInt();if(length<0||length>65535||length>in.available())throw new IOException("Invalid Code length");
        byte[] code=in.readNBytes(length);List<RuntimeInstruction> instructions=new ArrayList<>();Map<Integer,List<Integer>> switches=new HashMap<>();
        for(int pc=0;pc<code.length;) {
            int start=pc,opcode=code[pc]&255,operand=-1,size;
            if(opcode<=15||opcode>=26&&opcode<=53||opcode>=59&&opcode<=131||opcode>=133&&opcode<=152||opcode>=172&&opcode<=177||opcode==190||opcode==191||opcode==194||opcode==195)size=1;
            else if(opcode==16||opcode==18||opcode>=21&&opcode<=25||opcode>=54&&opcode<=58||opcode==169||opcode==188)size=2;
            else if(opcode==17||opcode==19||opcode==20||opcode==132||opcode>=153&&opcode<=168||opcode>=178&&opcode<=184||opcode==187||opcode==189||opcode==192||opcode==193||opcode==198||opcode==199)size=3;
            else if(opcode==185||opcode==186||opcode==200||opcode==201)size=5;
            else if(opcode==197)size=4;
            else if(opcode==196){if(pc+1>=code.length)throw new IOException("Truncated wide instruction");int wide=code[pc+1]&255;if(wide!=132&&!(wide>=21&&wide<=25)&&!(wide>=54&&wide<=58)&&wide!=169)throw new IOException("Invalid wide opcode");size=wide==132?6:4;}
            else if(opcode==170||opcode==171) {
                int aligned=(pc+4)&~3;if(aligned+8>code.length)throw new IOException("Truncated switch");
                int n;if(opcode==170){if(aligned+12>code.length)throw new IOException("Truncated tableswitch");long count=(long)runtimeInt(code,aligned+8)-runtimeInt(code,aligned+4)+1;if(count<0||count>65535)throw new IOException("Invalid switch range");n=(int)count;size=aligned-pc+12+4*n;}
                else{n=runtimeInt(code,aligned+4);if(n<0||n>65535)throw new IOException("Invalid lookup count");size=aligned-pc+8+8*n;}
            }else throw new IOException("Unknown JVM opcode");
            if(pc+size>code.length)throw new IOException("Truncated JVM instruction");
            if(opcode==170||opcode==171) {
                int aligned=(pc+4)&~3;List<Integer> targets=new ArrayList<>();targets.add(start+runtimeInt(code,aligned));
                if(opcode==170) {int n=(size-(aligned-pc)-12)/4;for(int i=0;i<n;i++)targets.add(start+runtimeInt(code,aligned+12+4*i));}
                else {int n=runtimeInt(code,aligned+4);for(int i=0;i<n;i++)targets.add(start+runtimeInt(code,aligned+12+8*i));}
                switches.put(instructions.size(),List.copyOf(targets));
            }
            if(opcode==16)operand=code[pc+1];if(opcode==17)operand=(short)((code[pc+1]&255)<<8|(code[pc+2]&255));
            if(opcode==18)operand=code[pc+1]&255;if(opcode==19||opcode==20)operand=(code[pc+1]&255)<<8|(code[pc+2]&255);
            if(opcode==192)operand=(code[pc+1]&255)<<8|(code[pc+2]&255);
            if(opcode>=153&&opcode<=168||opcode==198||opcode==199)operand=start+(short)((code[pc+1]&255)<<8|(code[pc+2]&255));if(opcode==200||opcode==201)operand=start+runtimeInt(code,pc+1);
            if(opcode>=178&&opcode<=184)operand=(code[pc+1]&255)<<8|(code[pc+2]&255);
            if(opcode==25||opcode==58)operand=code[pc+1]&255;
            if(opcode>=42&&opcode<=45){operand=opcode-42;opcode=25;}
            if(opcode>=75&&opcode<=78){operand=opcode-75;opcode=58;}
            if(opcode==196&&(code[pc+1]&255)==25||opcode==196&&(code[pc+1]&255)==58){opcode=code[pc+1]&255;operand=(code[pc+2]&255)<<8|(code[pc+3]&255);}
            pc+=size;instructions.add(new RuntimeInstruction(start,pc,opcode,operand));
        }
        List<RuntimeGuard> guards=new ArrayList<>();int count=in.readUnsignedShort();for(int i=0;i<count;i++){
            int start=in.readUnsignedShort(),end=in.readUnsignedShort(),handler=in.readUnsignedShort(),type=in.readUnsignedShort();
            guards.add(new RuntimeGuard(start,end,handler,type==0?null:runtimeClass(pool,type)));
        }
        skipRuntimeAttributes(in,pool);if(in.read()!=-1)throw new IOException("Trailing Code data");Set<Integer> boundaries=new HashSet<>();for(var op:instructions)boundaries.add(op.offset());
        for(var targets:switches.values())for(int target:targets)if(!boundaries.contains(target))throw new IOException("Switch target is not an actual instruction boundary");
        return new RuntimeCode(instructions,guards,Map.copyOf(switches));
    }
    private static String runtimeString(RuntimeInstruction instruction,Object[] pool)throws IOException {
        if((instruction.opcode()==18||instruction.opcode()==19)&&instruction.operand()>0&&instruction.operand()<pool.length&&pool[instruction.operand()] instanceof RuntimeString)return runtimeUtf(pool,((RuntimeString)pool[instruction.operand()]).utf());
        throw new IOException("Closure module identity is not its compiler string literal");
    }
    private static int runtimeSmallInt(RuntimeInstruction instruction,Object[] pool)throws IOException {
        if(instruction.opcode()>=2&&instruction.opcode()<=8)return instruction.opcode()-3;
        if(instruction.opcode()==16||instruction.opcode()==17)return instruction.operand();
        if((instruction.opcode()==18||instruction.opcode()==19)&&instruction.operand()>0&&instruction.operand()<pool.length&&pool[instruction.operand()] instanceof Integer)return (Integer)pool[instruction.operand()];
        throw new IOException("Native argument index is not its compiler literal");
    }
    private static int runtimeInt(byte[] bytes,int offset){return (bytes[offset]&255)<<24|(bytes[offset+1]&255)<<16|(bytes[offset+2]&255)<<8|(bytes[offset+3]&255);}
    private static String runtimeClass(Object[] pool,int index)throws IOException {if(index<=0||index>=pool.length||!(pool[index] instanceof Integer))throw new IOException("Invalid class constant");return runtimeUtf(pool,(Integer)pool[index]);}
    private static void runtimeCall(RuntimeInstruction instruction,Object[] pool,String owner,String name,String descriptor)throws IOException {
        runtimeMethod(instruction,pool,184,owner,name,descriptor);
    }
    private static void runtimeMethod(RuntimeInstruction instruction,Object[] pool,int opcode,String owner,String name,String descriptor)throws IOException {
        if(instruction.opcode()!=opcode||instruction.operand()<=0||instruction.operand()>=pool.length||!(pool[instruction.operand()] instanceof RuntimePair))throw new IOException("Native BODY requires its exact INVOKESTATIC: "+owner+"."+name+" at "+instruction);
        RuntimePair method=(RuntimePair)pool[instruction.operand()];if(method.second()<=0||method.second()>=pool.length||!(pool[method.second()] instanceof RuntimePair))throw new IOException("Invalid method constant");
        RuntimePair type=(RuntimePair)pool[method.second()];if(!runtimeClass(pool,method.first()).equals(owner)||!runtimeUtf(pool,type.first()).equals(name)||!runtimeUtf(pool,type.second()).equals(descriptor))throw new IOException("Native BODY helper/member differs from its exact proof");
    }
    private static void validateRuntimeBody(RuntimeCode code,Object[] pool,NativeMember member,int enter,int body,int leave,int failure,int guard)throws IOException {
        if(code==null||enter<1||body!=enter+2||leave!=body+2||failure<leave+4||failure+1>=code.instructions().size()||guard<0||guard>=code.guards().size())throw new IOException("Invalid native BODY region");
        List<RuntimeInstruction> ins=code.instructions();String runtime="php/runtime/PhpRuntimeOwnership",token="Lphp/runtime/PhpRuntimeOwnership$NativeBody;";
        runtimeCall(ins.get(enter),pool,runtime,"nativeEnterBody","(Lphp/runtime/PhpRuntimeOwnership$NativePacket;)"+token);
        runtimeCall(ins.get(body),pool,member.physicalOwner(),member.physicalName(),member.physicalDescriptor());
        runtimeCall(ins.get(leave),pool,runtime,"nativeLeaveBody","("+token+")V");
        runtimeCall(ins.get(failure),pool,runtime,"nativeFailBody","("+token+"Ljava/lang/Throwable;)Ljava/lang/Throwable;");
        RuntimeInstruction store=ins.get(enter+1),load=ins.get(leave-1),failedToken=ins.get(failure-2),failedValue=ins.get(failure-1);
        if(ins.get(enter-1).opcode()!=25||store.opcode()!=58||load.opcode()!=25||load.operand()!=store.operand()||failedToken.opcode()!=25||failedToken.operand()!=store.operand()||failedValue.opcode()!=25||ins.get(failure+1).opcode()!=191)throw new IOException("Native BODY token or failure custody changed");
        RuntimeGuard g=code.guards().get(guard);RuntimeInstruction handler=ins.get(failure-3);
        if(!"java/lang/Throwable".equals(g.type())||g.start()!=ins.get(body).offset()||g.end()!=ins.get(body).next()||g.handler()!=handler.offset()||handler.opcode()!=58||handler.operand()!=failedValue.operand()||ins.get(leave+1).opcode()!=167)throw new IOException("Native BODY requires its exact single-call Throwable handler");
        for(int i=0;i<guard;i++){RuntimeGuard earlier=code.guards().get(i);if(earlier.start()<=g.start()&&earlier.end()>g.start())throw new IOException("An earlier handler intercepts native BODY failure");}
    }
    private static String runtimeUtf(Object[] pool,int index)throws IOException {
        if(index<=0||index>=pool.length||!(pool[index] instanceof String))throw new IOException("Invalid runtime UTF constant");return (String)pool[index];
    }
    private static void skipRuntimeAttributes(DataInputStream in,Object[] pool)throws IOException {
        int n=in.readUnsignedShort();for(int i=0;i<n;i++){runtimeUtf(pool,in.readUnsignedShort());long size=Integer.toUnsignedLong(in.readInt());if(size>in.available())throw new IOException("Truncated runtime attribute");in.skipNBytes(size);}
    }
    private static int runtimeCount(DataInputStream input)throws IOException {
        int n=input.readInt();if(n<0||n>65535)throw new IOException("Invalid runtime plan count");return n;
    }
    private static Set<String> runtimeStrings(DataInputStream input)throws IOException {
        Set<String> result=new LinkedHashSet<>();int size=runtimeCount(input);
        for(int i=0;i<size;i++){String value=input.readUTF();if(value.isEmpty()||!result.add(value))throw new IOException("Invalid duplicate runtime capability");}return result;
    }

    private PhpRuntimeOwnership() {}
    public static Authority current(Environment environment){return environment.getRuntimeOwnership();}
    private static Authority releasingAuthority(Authority origin){
        Object context=origin.domain.currentContext();
        if(!(context instanceof Environment))throw new IllegalStateException("Runtime heap requires a releasing Environment");
        Authority current=((Environment)context).getRuntimeOwnership();
        if(current==null||current.domain!=origin.domain)throw new IllegalArgumentException("Releasing Environment has no matching heap authority");
        current.open();return current;
    }

    /** Compiler prologue borrows the exact outer invocation frame; it never closes before return validation. */
    public static Memory conditionalLocal(Frame frame,int producer,int local,Memory actual)throws Throwable {
        return frame==null?actual.toImmutable():frame.conditionalLocal(producer,local,actual);
    }
    public static boolean conditionalTest(Frame frame,int site,int source,Memory actual,int kind,boolean retainTrue)throws Throwable {
        if(frame==null)return kind==0?actual.isNotNull():actual.toBoolean();
        return frame.conditionalTest(site,source,actual,kind,retainTrue);
    }
    public static Memory conditionalResult(Frame frame,int site,int result,int selected,Memory actual,boolean trueArm)throws Throwable {
        return frame==null?actual:frame.conditionalResult(site,result,selected,actual,trueArm);
    }
    public static Memory globalReadQuiet(Frame frame,int site,int root,Memory actualRoot,String key,boolean cell,Environment environment,php.runtime.env.TraceInfo trace)throws Throwable {
        if(cell)throw new IllegalArgumentException("Quiet global fetch is a VALUE read");
        if(frame==null){ReferenceMemory carrier=environment.getGlobals().getByScalar(ArrayMemory.toKey(php.runtime.memory.StringMemory.valueOf(key)));return carrier==null?Memory.NULL:carrier.toValue().toImmutable();}
        if(frame.authority.environment!=environment)throw new IllegalArgumentException("Quiet global fetch uses another Environment");
        return frame.readGlobal(site,root,actualRoot,key,false,trace,true);
    }
    public static Memory propertyReference(Frame frame,int siteId,int rootLocalId,Memory actualRoot,String key,Environment env,php.runtime.env.TraceInfo trace)throws Throwable {
        if(frame==null)return php.runtime.invoke.ObjectInvokeHelper.getRefProperty(actualRoot,key,env,trace,null,0);
        if(frame.authority.environment!=env)throw new IllegalArgumentException("Property reference uses another Environment");
        return frame.propertyReference(siteId,rootLocalId,actualRoot,key);
    }
    public static boolean isInvoking(Environment environment,php.runtime.reflection.FunctionEntity entity){
        Authority authority=current(environment);return authority!=null&&!authority.invocations.isEmpty()&&authority.invocations.peek().target.entity==entity;
    }
    public static Memory invokeFunctionBody(php.runtime.reflection.FunctionEntity entity,Environment environment,
            php.runtime.env.TraceInfo trace,Memory[] args)throws Throwable {
        if(!isInvoking(environment,entity))throw new IllegalStateException("Ordinary function needs its actual SEND envelope");
        try{return php.runtime.invoke.InvokeHelper.checkReturnType(environment,trace,(Memory)entity.getNativeMethod().invoke(null,environment,args),entity);}
        catch(java.lang.reflect.InvocationTargetException failure){return environment.__throwException(failure);}
    }
    public static boolean isInvoking(Environment environment,php.runtime.reflection.MethodEntity entity){
        Authority authority=current(environment);return authority!=null&&!authority.invocations.isEmpty()&&authority.invocations.peek().target.entity==entity;
    }
    public static Memory invokeMethodBody(php.runtime.reflection.MethodEntity entity,php.runtime.lang.IObject receiver,
            Environment environment,php.runtime.env.TraceInfo trace,Memory[] args)throws Throwable {
        if(!isInvoking(environment,entity))throw new IllegalStateException("Ordinary method needs its actual receiver/SEND envelope");
        Invocation invocation=current(environment).invocations.peek();
        if(invocation.receiver!=receiver)throw new IllegalArgumentException("Method receiver differs from its real invocation root");
        try{return php.runtime.invoke.InvokeHelper.checkReturnType(environment,trace,(Memory)entity.getNativeMethod().invoke(receiver,environment,args),entity);}
        catch(java.lang.reflect.InvocationTargetException failure){return environment.__throwException(failure);}
    }
    public static boolean isIncluding(Environment environment,php.runtime.reflection.ModuleEntity entity){
        Authority authority=current(environment);return authority!=null&&!authority.invocations.isEmpty()&&authority.invocations.peek().target.entity==entity;
    }
    public static Memory includeBody(php.runtime.reflection.ModuleEntity entity,Environment environment,ArrayMemory locals)throws Throwable {
        if(!isIncluding(environment,entity))throw new IllegalStateException("Ordinary module needs its outer logical frame");
        if(locals!=environment.getGlobals())throw new IllegalArgumentException("External include locals need their complete address protocol");
        environment.getModuleManager().markIncluded(entity.getContext().getFileName());
        try{return (Memory)entity.getNativeMethod().invoke(null,environment,new Memory[0],locals);}
        catch(java.lang.reflect.InvocationTargetException failure){return environment.__throwException(failure);}
    }
    public static Frame enterGenerated(Environment environment,Class<?> owner,String name,String descriptor){
        if(PhpOwnershipExecution.current(environment)!=null)return null;
        Authority authority=current(environment);if(authority==null)return null;
        Invocation invocation=authority.invocations.peek();
        if(invocation==null||invocation.entered||invocation.target.member.getDeclaringClass()!=owner
                ||!invocation.target.member.getName().equals(name)||!PhpOwnershipExecution.descriptor(invocation.target.member).equals(descriptor))
            throw new IllegalStateException("Generated ordinary entry needs its exact outer invocation");
        if(invocation.target.runtimePlan.lifecycle()){
            if(invocation.lifecycleEntry==null)throw new IllegalArgumentException("Lifecycle body lacks its exact nonowning entry");
            invocation.lifecycleEntry.validateReceiver(authority.domain,environment,(php.runtime.reflection.MethodEntity)invocation.target.entity,invocation.receiver);
        }
        invocation.entered=true;return invocation.frame;
    }
    /** A registered exact native provider must declare its complete borrow/result/callback protocol. */
    public interface CompleteNativeProtocol {
        void validateMember(java.lang.reflect.Method actual);
        void validateArguments(List<Value> actual);
    }
    /** Real returned address export; no scalar temporary or virtual carrier is promoted. */
    public static Memory prepareReturnReference(Frame frame,Memory raw,Environment env,php.runtime.env.TraceInfo trace) {
        if(frame==null)return php.runtime.invoke.InvokeHelper.prepareReturnReference(raw,env,trace);
        frame.open();if(frame.authority.environment!=env)throw new IllegalArgumentException("Return reference belongs to another invocation");
        if(raw.getClass()==php.runtime.memory.support.ArrayMapEntryMemory.class)return frame.returnDebugAddress((php.runtime.memory.support.ArrayMapEntryMemory)raw);
        Memory current=raw;Set<ReferenceMemory> visited=Collections.newSetFromMap(new IdentityHashMap<>());
        while(current!=null&&current.getClass()==ReferenceMemory.class) {
            ReferenceMemory cell=(ReferenceMemory)current;if(!visited.add(cell))throw new IllegalArgumentException("Cyclic structural return address");
            if(cell.getRuntimeCell()!=null||cell.getOwnershipCell()!=null){frame.authority.value(cell,Role.CELL);return cell;}
            Holder holder=cell.getRuntimeHolder();if(holder!=null){if(holder.authority().domain!=frame.authority.domain)throw new IllegalArgumentException("Foreign return address");return holder.reference();}
            current=cell.getRawValue();
        }
        throw new IllegalArgumentException("Reference return needs its exact owned address publisher");
    }
    /** The key/value wrapper is structural; only the exact physical ARRAY EVAL receives its payload. */
    public static php.runtime.memory.ReferenceMemory literalEntry(php.runtime.memory.ArrayMemory actual,Memory pair,Environment environment,php.runtime.env.TraceInfo trace,Frame frame,int parent)throws Throwable {
        if(frame==null)return actual.addPhp8(pair,environment,trace);
        frame.open();Invocation invocation=frame.authority.invocations.peek();
        if(frame.authority.environment!=environment||invocation==null||invocation.frame!=frame||!invocation.entered||frame.authority.currentFrame()!=frame||invocation.target.runtimePlan.literalEntries().stream().noneMatch(site->site.parent()==parent))throw new IllegalArgumentException("Keyed literal lacks its actual generated ARRAY publication");
        Holder publisher=frame.evalHolder(parent);if(publisher==null)throw new IllegalArgumentException("Keyed literal ARRAY producer was consumed");publisher.requireProducerEvaluation();
        if(publisher.role()!=Role.VALUE||publisher.raw()!=actual||actual.getClass()!=php.runtime.memory.ArrayMemory.class||pair.getClass()!=php.runtime.memory.KeyValueMemory.class)throw new IllegalArgumentException("Keyed literal changed its physical ARRAY/wrapper publisher");
        frame.authority.value(actual,Role.VALUE);
        var wrapper=(php.runtime.memory.KeyValueMemory)pair;Memory key=wrapper.key;
        if(key==null||key.isReference()||!Set.of(Memory.Type.STRING,Memory.Type.INT).contains(key.getRealType()))throw new IllegalArgumentException("Keyed literal key lacks a scalar conversion protocol");
        Memory value=wrapper.getValue();Value next=frame.authority.value(value,value.isReference()?Role.CELL:Role.VALUE);
        actual.checkCopied();
        var existing=actual.getByScalar(wrapper.getArrayKey());
        if(existing!=null&&!value.isReference()) {
            Holder destination=existing.getRuntimeHolder();
            if(existing.getClass()!=php.runtime.memory.support.ArrayMapEntryMemory.class||existing.getRuntimeCell()!=null||destination==null||destination.facet!=null||destination.authority.domain!=frame.authority.domain||!destination.matches(existing)||destination.edge.owner()!=publisher.node()||destination.role!=Role.VALUE&&destination.role!=Role.CELL)throw new IllegalArgumentException("Repeated literal key lacks its exact physical bucket facet");
            // Literal construction replaces the bucket zval; assigning through its old CELL would alter aliases.
            try(Publication publication=new Publication(destination,next,null,null,null)) {
                existing.publishRuntimeRaw(publication);publication.commit();return existing;
            }
        }
        return actual.addPhp8(pair,environment,trace);
    }
    public static Memory objectCast(Memory actual,Frame frame,int result,int source,Environment environment)throws Throwable {
        if(frame==null)return actual.toObject(environment);
        frame.open();Invocation invocation=frame.authority.invocations.peek();
        if(frame.authority.environment!=environment||invocation==null||invocation.frame!=frame||!invocation.entered||frame.authority.currentFrame()!=frame||invocation.target.runtimePlan.objectCasts().stream().noneMatch(site->site.id()==result&&site.source()==source))throw new IllegalArgumentException("OBJECT cast lacks its actual generated producer site");
        return frame.authority.stdClassPlan().cast(frame,result,source,actual);
    }
    public static Memory globalNull(Frame frame,int id)throws Throwable {
        Memory value=Memory.NULL;if(frame==null)return value;
        frame.open();Invocation invocation=frame.authority.invocations.peek();
        if(invocation==null||invocation.frame!=frame||!invocation.entered||frame.authority.currentFrame()!=frame||invocation.target.runtimePlan.globalNulls().stream().noneMatch(site->site.id()==id))throw new IllegalArgumentException("NULL global RHS lacks its actual generated producer");
        try(Publication publication=frame.prepareEval(id,value,Role.VALUE)){publication.storeDirect();publication.commit();}return value;
    }
    public static Memory globalScalar(Frame frame,int id,long literal)throws Throwable {
        Memory value=php.runtime.memory.LongMemory.valueOf(literal);if(frame==null)return value;
        frame.open();Invocation invocation=frame.authority.invocations.peek();
        if(invocation==null||invocation.frame!=frame||!invocation.entered||frame.authority.currentFrame()!=frame||invocation.target.runtimePlan.globalScalars().stream().noneMatch(site->site.id()==id&&site.value()==literal))throw new IllegalArgumentException("Scalar global RHS lacks its actual generated producer");
        try(Publication publication=frame.prepareEval(id,value,Role.VALUE)){publication.storeDirect();publication.commit();}return value;
    }
    public static Memory namedGlobal(Frame frame,int local,String name,Environment environment)throws Throwable {
        if(frame==null)return environment.getOrCreateGlobal(name);
        frame.open();Invocation invocation=frame.authority.invocations.peek();
        if(frame.authority.environment!=environment||invocation==null||invocation.frame!=frame||!invocation.entered||frame.authority.currentFrame()!=frame||invocation.target.runtimePlan.namedGlobals().stream().noneMatch(v->v.local()==local&&v.name().equals(name)))throw new IllegalArgumentException("Named global lacks its actual generated address import");
        return ReferenceMemory.valueOf(frame.authority.globalAddress(name));
    }
    public static Memory entryString(Frame frame,int id,String literal,boolean raw)throws Throwable {
        Memory value=raw?php.runtime.memory.PhpByteStringMemory.fromBinaryString(literal):php.runtime.memory.StringMemory.valueOf(literal);
        if(frame==null)return value;
        frame.open();Invocation invocation=frame.authority.invocations.peek();
        if(invocation==null||invocation.frame!=frame||!invocation.entered||frame.authority.currentFrame()!=frame||invocation.target.runtimePlan.entryStrings().stream().noneMatch(v->v.id()==id&&v.value().equals(literal)&&v.raw()==raw))throw new IllegalArgumentException("String ENTRY lacks its actual generated literal producer");
        try(Publication publication=frame.prepareEval(id,value,Role.VALUE)){publication.storeDirect();publication.commit();}return value;
    }
    public static Memory entryScalar(Frame frame,int id,long literal)throws Throwable {
        Memory value=php.runtime.memory.LongMemory.valueOf(literal);if(frame==null)return value;
        frame.open();Invocation invocation=frame.authority.invocations.peek();
        if(invocation==null||invocation.frame!=frame||!invocation.entered||frame.authority.currentFrame()!=frame||invocation.target.runtimePlan.entryScalars().stream().noneMatch(site->site.id()==id&&site.value()==literal))throw new IllegalArgumentException("Scalar ENTRY RHS lacks its actual generated producer");
        try(Publication publication=frame.prepareEval(id,value,Role.VALUE)){publication.storeDirect();publication.commit();}return value;
    }
    public static Memory globalRead(Frame frame,int site,int root,Memory actualRoot,String key,boolean cell,Environment environment,php.runtime.env.TraceInfo trace)throws Throwable {
        if(frame==null)return cell?ReferenceMemory.exportReference(actualRoot.refOfIndex(trace,key)):actualRoot.valueOfIndex(trace,key);
        if(frame.authority.environment!=environment)throw new IllegalArgumentException("Named global READ changed its Environment");
        return frame.readGlobal(site,root,actualRoot,key,cell,trace);
    }
    public static void globalUnset(Frame frame,int site,int root,Memory actualRoot,String key,Environment environment)throws Throwable {
        if(frame==null){actualRoot.unsetOfIndex(null,php.runtime.memory.StringMemory.valueOf(key));return;}
        if(frame.authority.environment!=environment)throw new IllegalArgumentException("Named global UNSET changed its Environment");
        frame.unsetGlobal(site,root,actualRoot,key);
    }
    public static void unsetLocal(Frame frame,int id,Memory actualCv,Environment environment) {
        if(frame==null){
            // This entry receives the compiler's CV address. Retire that address, not its shared payload.
            if(actualCv instanceof ReferenceMemory carrier
                    &&(carrier.getClass()==ReferenceMemory.class||carrier.getClass()==php.runtime.memory.support.ArrayMapEntryMemory.class)
                    &&carrier.getRawValue() instanceof ReferenceMemory canonical&&canonical.isCanonicalReferenceCell())carrier.unset();
            else actualCv.manualUnset(environment);
            return;
        }
        if(frame.authority.environment!=environment)throw new IllegalArgumentException("Unset has another execution Environment");
        frame.unsetLocal(id,actualCv);
    }
    public static Memory localAddress(Memory actualTable,String name,Frame frame) {
        return frame==null?actualTable.refOfIndex(name):frame.debugAddress(actualTable,name);
    }
    public static Memory bindStaticLocal(Frame frame,int id,String name,Memory cv,Memory staticSlot) {
        return frame==null?Memory.assignRefRight(staticSlot,cv):frame.bindStaticLocal(id,name,cv,staticSlot);
    }
    public static NativePacket nativeCall(Frame frame,int callId,Class<?> owner,String member,String descriptor,int count)throws Throwable {
        return frame==null?null:frame.nativeCall(callId,owner,member,descriptor,count);
    }
    public static Memory nativeSend(NativePacket packet,int index,Memory raw,int evalId)throws Throwable {
        if(packet!=null)packet.send(index,raw,evalId);return raw;
    }
    /** The Java borrow wrapper is metadata; SEND owns its exact semantic payload. */
    public static Memory nativeBorrowSend(NativePacket packet,int index,Memory raw,int evalId,boolean fast,boolean wrappedTransport)throws Throwable {
        if(packet!=null) {
            packet.open();if(index!=packet.sent||index>=packet.sends.length)throw new IllegalArgumentException("Native borrow has another SEND position");
            if(evalId>=0)packet.frame.producerEval(evalId);
            var trait=packet.member.getAnnotation(php.runtime.annotation.Runtime.Frameless.class);
            boolean expected=trait!=null&&java.util.Arrays.stream(trait.value()).anyMatch(n->n==packet.sends.length);
            if(fast!=expected)throw new IllegalArgumentException("Native borrow differs from its exact member/arity opcode contract"); 
            if(packet.frame.authority.nativeWrappedArgument(packet.member,index)!=wrappedTransport)throw new IllegalArgumentException("Native borrow has another Java transport parameter");
        }
        Memory transport=fast?php.runtime.invoke.PhpCallFrame.borrowNativeFastArgument(raw):php.runtime.invoke.PhpCallFrame.borrowNativeArgument(raw);
        if(packet==null)return wrappedTransport?transport:raw;
        Memory semantic=transport;
        boolean existingCell=fast&&transport==raw&&transport.getClass()==ReferenceMemory.class
                &&(((ReferenceMemory)transport).getRuntimeCell()!=null||((ReferenceMemory)transport).getOwnershipCell()!=null);
        if(!existingCell&&transport instanceof ReferenceMemory) {
            if(transport.getClass()!=ReferenceMemory.class)throw new IllegalArgumentException("Native borrow factory returned an unknown structural carrier");
            semantic=((ReferenceMemory)transport).getRawValue();
        }
        packet.send(index,semantic,evalId);return wrappedTransport?transport:semantic;
    }
    public static Memory nativeDirectCvSend(NativePacket packet,int argument,Memory actual,int local)throws Throwable {
        if(packet==null)return actual;
        return packet.sendDirectCv(argument,actual,local);
    }

    private static void requireNativeJoinBody(java.lang.reflect.Method actual) {
        if(!actual.isAnnotationPresent(php.runtime.annotation.Runtime.StringJoin.class))
            throw new IllegalArgumentException("Native string join lacks its explicit registered BODY contract");
        var signature=actual.getAnnotation(php.runtime.annotation.Reflection.Signature.class);
        if(signature==null||signature.value().length!=2||!signature.value()[0].typeDeclaration().equals("array|string")
                ||signature.value()[1].type()!=php.runtime.common.HintType.ARRAY||!signature.value()[1].nullable()
                ||signature.result().type()!=php.runtime.common.HintType.STRING)
            throw new IllegalArgumentException("Native string callback lacks its exact join declaration");
        Class<?>[] transport=actual.getParameterTypes();
        if((transport.length!=3&&transport.length!=4)||transport[0]!=Environment.class||transport[1]!=php.runtime.env.TraceInfo.class
                ||java.util.Arrays.stream(transport).skip(2).anyMatch(type->type!=Memory.class)||actual.getReturnType()!=Memory.class)
            throw new IllegalArgumentException("Native join BODY lacks its exact Java transport descriptor");
    }

    private static void requireNativeScalarStringBody(java.lang.reflect.Method actual,int argument) {
        var contract=actual.getAnnotation(php.runtime.annotation.Runtime.ScalarStringCallback.class);
        var signature=actual.getAnnotation(php.runtime.annotation.Reflection.Signature.class);
        if(contract==null||argument!=0||contract.argument()!=argument||signature==null||signature.value().length!=1
                ||signature.value()[argument].type()!=php.runtime.common.HintType.STRING
                ||!signature.value()[argument].typeDeclaration().isEmpty()||signature.value()[argument].nullable()
                ||signature.value()[argument].reference()||signature.value()[argument].variadic()
                ||signature.value()[argument].optional().exists()||signature.namedVariadic()
                ||signature.result().type()!=php.runtime.common.HintType.INT||signature.result().nullable()
                ||signature.result().reference()||!signature.result().typeDeclaration().isEmpty())
            throw new IllegalArgumentException("Native scalar Stringable lacks its exact unary STRING/INT BODY contract");
        Class<?>[] transport=actual.getParameterTypes();
        if(!java.lang.reflect.Modifier.isStatic(actual.getModifiers())||transport.length!=3
                ||transport[0]!=Environment.class||transport[1]!=php.runtime.env.TraceInfo.class
                ||transport[2]!=Memory.class||actual.getReturnType()!=long.class)
            throw new IllegalArgumentException("Native scalar Stringable lacks its exact Java transport descriptor");
    }

    private static void requireNativeDirectJoinBody(java.lang.reflect.Method actual) {
        requireNativeJoinBody(actual);
        var direct=actual.getAnnotation(php.runtime.annotation.Runtime.DirectCall.class);
        if(direct!=null&&direct.liveArrayVariables()) {
            var function=new php.runtime.ext.support.compile.CompileFunction(actual.getName());function.addMethod(actual);
            function.findDirect(actual.getParameterTypes().length-2);return;
        }
        int matches=0;
        for(var ordinary:actual.getDeclaringClass().getDeclaredMethods()) {
            var bridge=ordinary.getAnnotation(php.runtime.annotation.Runtime.DirectCall.class);
            if(bridge==null||!bridge.liveArrayVariables()||!bridge.value().equals(actual.getName())
                    ||!Arrays.equals(ordinary.getParameterTypes(),actual.getParameterTypes()))continue;
            var function=new php.runtime.ext.support.compile.CompileFunction(ordinary.getName());function.addMethod(ordinary);
            if(!function.findDirect(actual.getParameterTypes().length-2).method.equals(actual))
                throw new IllegalArgumentException("Native direct body differs from its declared physical target");
            matches++;
        }
        if(matches!=1)throw new IllegalArgumentException("Native direct body has no unique published source");
    }
    public static Memory nativeResult(NativePacket packet,Memory raw,int evalId)throws Throwable {
        if(packet!=null)packet.resultToEval(raw,evalId);return raw;
    }
    public interface CompletePhysicalProtocol {
        void validate(ArrayMemory actual);
    }

    /** Domain-owned identity/facade metadata. These sets acquire no edges themselves. */
    static final class Shared {
        final PhpOwnershipDomain domain;
        final IdentityHashMap<Object,ArrayOrigin> arrays=new IdentityHashMap<>();
        final IdentityHashMap<ReferenceMemory,CellOrigin> cells=new IdentityHashMap<>();
        final Set<Holder> holders=Collections.newSetFromMap(new IdentityHashMap<>());
        final WeakHashMap<PhpOwnershipArrays.PublisherFacet,java.lang.ref.WeakReference<Holder>> facets=new WeakHashMap<>();
        Shared(PhpOwnershipDomain domain){this.domain=domain;}
    }
    /** An authority is explicitly bound by the host/runtime allocation protocol. */
    public static final class Authority implements AutoCloseable {
        private final Environment environment;
        private final PhpOwnershipDomain domain;
        private final PhpOwnershipDomain.Participant participant;
        private final Shared shared;
        private final IdentityHashMap<Object,ArrayOrigin> arrays;
        private final IdentityHashMap<ReferenceMemory,CellOrigin> cells;
        private final Set<Holder> holders;
        private final Deque<Frame> frames=new ArrayDeque<>();
        private final IdentityHashMap<Object,Target> targets=new IdentityHashMap<>();
        private final Map<Object,GlobalSlot> globals=new LinkedHashMap<>();
        private ArrayMemory.NativeGlobals globalTable;
        private final Deque<Invocation> invocations=new ArrayDeque<>();
        private final Deque<NativeBody> nativeBodies=new ArrayDeque<>();
        private final Map<java.lang.reflect.Method,CompleteNativeProtocol> nativeMembers=new HashMap<>();
        private PhpOwnershipArrays arrayAdapter;
        private PhpOwnershipObjects objectAdapter;
        private PhpOwnershipStdClass.Plan stdClassPlan;
        private PhpOwnershipStdClass.Plan stdClassPlan() {
            if(stdClassPlan==null)stdClassPlan=PhpOwnershipStdClass.prepare(environment);
            stdClassPlan.validate();return stdClassPlan;
        }
        private final IdentityHashMap<php.runtime.reflection.ClassEntity,php.runtime.reflection.helper.PhpObjectShellVerifier.LiteralDefaultsPlan> literalObjectPlans=new IdentityHashMap<>();
        private PhpOwnershipClosures closureAdapter;
        private final IdentityHashMap<php.runtime.reflection.helper.ClosureEntity,PhpOwnershipClosures.Provider> lexicalProviders=new IdentityHashMap<>();
        private boolean closed;
        public Authority(Environment environment,PhpOwnershipDomain domain) {
            this.environment=Objects.requireNonNull(environment);this.domain=Objects.requireNonNull(domain);
            participant=domain.participant(environment);shared=domain.runtimeOwnership();arrays=shared.arrays;cells=shared.cells;holders=shared.holders;environment.bindRuntimeOwnership(this);
        }
        public Environment environment() {return environment;}
        public PhpOwnershipDomain domain() {return domain;}
        public PhpOwnershipDomain.Execution enter() {open();return participant.enter();}
        /** Cheap check at completed allocation/call boundaries; no scan or scope allocation below threshold. */
        public void collectGarbageIfNeeded() throws Throwable {
            open();if(environment.isGcEnabled() && domain.graph().shouldCollectCycles())environment.collectGarbageIfNeeded();
        }
        private void open() {domain.graph().checkMutationAccess();if(closed)throw new IllegalStateException("Runtime authority is closed");}
        public Frame frame() {open();domain.currentContext();Frame frame=new Frame(this);frames.push(frame);return frame;}
        public Frame currentFrame() {open();return frames.peek();}
        public PhpOwnershipArrays arrays() {
            open();domain.currentContext();if(arrayAdapter==null)arrayAdapter=new PhpOwnershipArrays(domain.heap());return arrayAdapter;
        }
        public PhpOwnershipObjects objects() {
            open();domain.currentContext();if(objectAdapter==null)objectAdapter=new PhpOwnershipObjects(domain.heap(),arrays());return objectAdapter;
        }
        private php.runtime.reflection.helper.PhpObjectShellVerifier.LiteralDefaultsPlan literalObjectPlan(Target target,ObjectNew site) {
            php.runtime.reflection.ModuleEntity module=target.entity instanceof php.runtime.loader.dump.PhpAttributeDumper.ArgumentSource source?source.module():target.entity instanceof php.runtime.reflection.ModuleEntity?(php.runtime.reflection.ModuleEntity)target.entity:target.entity instanceof php.runtime.reflection.FunctionEntity?((php.runtime.reflection.FunctionEntity)target.entity).getModule():target.entity instanceof php.runtime.reflection.MethodEntity?((php.runtime.reflection.MethodEntity)target.entity).getClazz().getModule():null;
            if(module==null)throw new IllegalArgumentException("Object NEW has no actual compiler module");
            php.runtime.reflection.ClassEntity entity=module.findClass(site.name());
            if(entity==null||entity.getClass()!=php.runtime.reflection.ClassEntity.class||entity.getModule()!=module||!entity.getInternalName().equals(site.owner())||entity.getNativeClass()==null||!entity.getNativeClass().getName().replace('.','/').equals(site.owner()))throw new IllegalArgumentException("Object NEW class differs from its byte-bound module declaration");
            var existing=environment.fetchClass(site.name(),false);if(existing!=null&&existing!=entity)throw new IllegalArgumentException("Object NEW resolves to a different declared class");
            var plan=literalObjectPlans.get(entity);
            if(plan==null) {
                PhpOwnershipObjects.CompleteDestructorProtocol lifecycle=null;
                if(entity.methodDestruct!=null) {
                    validateDestructor(entity,entity.methodDestruct);
                    lifecycle=new PhpOwnershipObjects.CompleteDestructorProtocol() {
                        public void validateMember(php.runtime.reflection.ClassEntity actual,php.runtime.reflection.MethodEntity method,Environment env) {
                            if(actual!=entity||env!=environment)throw new IllegalArgumentException("Literal NEW lifecycle changed its class/Environment");
                            validateDestructor(actual,method);
                        }
                        public void invoke(PhpOwnershipObjects.ExecutionEntry entry,Environment env,php.runtime.env.TraceInfo trace)throws Throwable {
                            if(env!=environment)throw new IllegalArgumentException("Literal NEW destructor uses another Environment");invokeDestructor(entry,trace);
                        }
                    };
                }
                plan=php.runtime.reflection.helper.PhpObjectShellVerifier.prepareLiteralDefaults(entity,environment,lifecycle);literalObjectPlans.put(entity,plan);
            }
            plan.validate();if(!plan.fingerprint().equals(site.fingerprint()))throw new IllegalArgumentException("Object NEW defaults differ from their compiler descriptor");return plan;
        }
        private php.runtime.reflection.PropertyEntity literalProperty(Target target,PropertyReference site) {
            ObjectNew origin=target.runtimePlan.objectNews().stream().filter(value->value.id()==site.producer()).findFirst().orElseThrow(()->new IllegalArgumentException("FIELD has no actual NEW lineage"));
            var plan=literalObjectPlan(target,origin);var entity=plan.entity();
            for(var current=entity;current!=null;current=current.getParent())if(current.methodMagicGet!=null||current.methodMagicSet!=null||current.methodMagicUnset!=null||current.methodMagicIsset!=null)throw new IllegalArgumentException("FIELD reference has an unsupported magic-property callback");
            var property=entity.findProperty(site.key());
            if(property==null||property.getClass()!=php.runtime.reflection.PropertyEntity.class||property.isStatic()||property.getModifier()!=php.runtime.common.Modifier.PUBLIC||property.getPhpSetModifier()!=null||property.getGetter()!=null||property.getSetter()!=null||property.getDefaultValue()==null||property.getDefaultValue().isUndefined())throw new IllegalArgumentException("FIELD reference lacks its public initialized declared publisher");
            return property;
        }
        public PhpOwnershipClosures closures() {
            open();domain.currentContext();if(closureAdapter==null)closureAdapter=new PhpOwnershipClosures(objects(),arrays());return closureAdapter;
        }
        /** Only the byte-verified generated empty lexical shell receives this producer capability. */
        public PhpOwnershipClosures.Provider lexicalProvider(php.runtime.reflection.helper.ClosureEntity entity) {
            open();domain.currentContext();Objects.requireNonNull(entity);
            try {
                var constructor=Objects.requireNonNull(entity.getNativeClass()).getConstructor(Environment.class,
                        php.runtime.reflection.ClassEntity.class,Memory.class,String.class,Memory[].class);
                PhpOwnershipClosures.validateLexicalShell(entity,constructor);
                var provider=lexicalProviders.get(entity);
                if(provider==null) {
                    provider=closures().registerLexical(entity,constructor,(actual,env)->{
                        if(actual!=entity)throw new IllegalArgumentException("Lexical shell entity changed");
                        PhpOwnershipClosures.validateLexicalShell(entity,constructor);
                    });lexicalProviders.put(entity,provider);
                }
                return provider;
            }catch(NoSuchMethodException failure){throw new IllegalArgumentException("Missing exact lexical shell constructor",failure);}
        }
        public void registerNative(java.lang.reflect.Method exact,CompleteNativeProtocol protocol) {
            open();domain.currentContext();Objects.requireNonNull(exact);Objects.requireNonNull(protocol);
            if(!java.lang.reflect.Modifier.isStatic(exact.getModifiers()))throw new IllegalArgumentException("Native target is not an exact static function");
            protocol.validateMember(exact);if(nativeMembers.putIfAbsent(exact,protocol)!=null)throw new IllegalArgumentException("Native member already registered");
        }
        private java.lang.reflect.Method nativeMember(String owner,String name,String descriptor) {
            for(var method:nativeMembers.keySet())if(method.getDeclaringClass().getName().replace('.','/').equals(owner)&&method.getName().equals(name)&&PhpOwnershipExecution.descriptor(method).equals(descriptor))return nativeMember(method.getDeclaringClass(),name,descriptor);
            throw new IllegalArgumentException("Native dependency has no exact registered provider");
        }
        private boolean exactNativeDependency(RuntimePlan plan,String required) {
            if(!required.startsWith("function-call:"))return false;
            int split=required.lastIndexOf(':');if(split<="function-call:".length())return false;
            int argc;try{argc=Integer.parseInt(required.substring(split+1));}catch(NumberFormatException invalid){return false;}
            var function=environment.fetchFunction(required.substring("function-call:".length(),split));
            if(function==null||function.getClass()!=php.runtime.reflection.CompileFunctionEntity.class)return false;
            var nativeMethod=((php.runtime.reflection.CompileFunctionEntity)function).getCompileFunction().find(argc);
            if(nativeMethod==null||nativeMethod.isVarArg()||nativeMethod.argsCount!=argc)return false;
            String owner=nativeMethod.method.getDeclaringClass().getName().replace('.','/'),descriptor=PhpOwnershipExecution.descriptor(nativeMethod.method);
            if(plan.nativeMembers().stream().anyMatch(n->n.argc()==argc&&n.owner().equals(owner)&&n.name().equals(nativeMethod.method.getName())&&n.descriptor().equals(descriptor)))return true;
            // Direct PHP entry is admitted only through the declaration's validated physical sibling.
            if(nativeMethod.method.getAnnotation(php.runtime.annotation.Runtime.DirectCall.class)==null)return false;
            var direct=((php.runtime.reflection.CompileFunctionEntity)function).getCompileFunction().findDirect(argc);
            if(direct==null||direct.argsCount!=argc||direct.isVarArg())return false;
            String directOwner=direct.method.getDeclaringClass().getName().replace('.','/'),directDescriptor=PhpOwnershipExecution.descriptor(direct.method);
            return plan.nativeMembers().stream().anyMatch(n->n.argc()==argc&&n.owner().equals(directOwner)&&n.name().equals(direct.method.getName())&&n.descriptor().equals(directDescriptor));
        }
        private Target functionDependency(FunctionCall site) {
            var entity=environment.fetchFunction(site.name());Target target=targets.get(entity);
            if(entity==null||entity.getClass()!=php.runtime.reflection.FunctionEntity.class||target==null||target.plan.entryKind()!=PhpOwnershipExecution.EntryKind.FUNCTION||!target.member.getDeclaringClass().getName().replace('.','/').equals(site.owner())||!target.member.getName().equals(site.member())||!PhpOwnershipExecution.descriptor(target.member).equals(site.descriptor()))throw new IllegalArgumentException("FUNCTION dependency is not its registered byte-bound member");
            var params=entity.getParameters();if(entity.isReturnReference()||params==null||params.length!=1||!params[0].isReference()||params[0].getTypeChecker()!=null||params[0].isVariadic()||params[0].getDefaultValue()!=null||params[0].getDefaultInitializer()!=null)throw new IllegalArgumentException("FUNCTION argument/return contract needs its complete protocol");return target;
        }
        private boolean exactFunctionDependency(RuntimePlan plan,String required) {
            for(FunctionCall site:plan.functionCalls())if(required.equals("function-call:"+site.name()+":1")){functionDependency(site);return true;}return false;
        }
        private RuntimePlan requireRuntimePlan(byte[] bytes,java.lang.reflect.Method member)throws IOException {
            RuntimePlan plan=readRuntimePlan(bytes,member.getName(),PhpOwnershipExecution.descriptor(member));
            if(plan==null||!plan.unsupported().isEmpty())throw new IllegalArgumentException("Missing or incomplete ordinary producer/consumer plan: "+(plan==null?"missing":plan.unsupported()));
            validateNativeDependencies(plan);return plan;
        }
        private void validateNativeDependencies(RuntimePlan plan) {
            for(NativeMember member:plan.nativeMembers()) {
                var actual=nativeMember(member.owner(),member.name(),member.descriptor());
                php.runtime.invoke.PhpNativeResultUse.require(actual,member.physicalOwner(),member.physicalName(),member.physicalDescriptor(),member.discardedResult());
                if(member.hasBorrow()) {
                    var trait=actual.getAnnotation(php.runtime.annotation.Runtime.Frameless.class);
                    boolean expected=trait!=null&&java.util.Arrays.stream(trait.value()).anyMatch(n->n==member.argc());
                    if(member.framelessBorrow()!=expected)throw new IllegalArgumentException("Native argument borrow differs from its exact frameless trait and arity");
                    for(var wrapped:member.wrappedArguments().entrySet())if(nativeWrappedArgument(actual,wrapped.getKey())!=wrapped.getValue())throw new IllegalArgumentException("Native Java transport differs from its exact parameter annotation");
                    if(!member.directCvArguments().isEmpty())requireNativeDirectJoinBody(actual);
                }
            }
        }
        private boolean nativeWrappedArgument(java.lang.reflect.Method member,int argument) {
            int php=0;for(var parameter:member.getParameters()) {
                Class<?> type=parameter.getType();if(type==Environment.class||type==php.runtime.env.TraceInfo.class||parameter.isAnnotationPresent(php.runtime.annotation.Runtime.GetLocals.class))continue;
                if(php==argument||type==Memory[].class&&argument>=php)return parameter.isAnnotationPresent(php.runtime.annotation.Runtime.Reference.class);
                php++;
            }
            throw new IllegalArgumentException("Native argument has no actual Java parameter");
        }
        private java.lang.reflect.Method nativeMember(Class<?> owner,String name,String descriptor) {
            for(var method:owner.getDeclaredMethods())if(method.getName().equals(name)&&PhpOwnershipExecution.descriptor(method).equals(descriptor)) {
                var protocol=nativeMembers.get(method);if(protocol==null)throw new IllegalArgumentException("Native target lacks its exact ordinary provider");
                protocol.validateMember(method);return method;
            }
            throw new IllegalArgumentException("Missing exact native member");
        }
        /** Explicit exact body registration is an interim coverage witness, never an ABI/name inference. */
        public void registerAttributeArgument(php.runtime.reflection.ModuleEntity module,php.runtime.reflection.support.Entity declaration,php.runtime.reflection.support.PhpAttribute attribute,php.runtime.reflection.support.PhpAttribute.Argument argument,PhpOwnershipExecution.CompleteFrameProtocol protocol)throws Throwable {
            open();domain.currentContext();Objects.requireNonNull(protocol);
            var source=php.runtime.loader.dump.PhpAttributeDumper.resolveArgument(environment,module,declaration,attribute,argument);var member=source.member();byte[] bytes=source.bytes();
            var plan=PhpOwnershipExecution.readFramePlan(bytes,member.getName(),PhpOwnershipExecution.descriptor(member));
            if(plan==null||plan.entryKind()!=PhpOwnershipExecution.EntryKind.CLASS_ENV_INITIALIZER||plan.thrownIds().length!=0||plan.returnIds().length!=0)throw new IllegalArgumentException("Attribute argument initializer coverage is incomplete");
            RuntimePlan runtime=requireRuntimePlan(bytes,member);
            for(String required:plan.requiredCapabilities())if(!required.equals("frame-local-publication-v1")&&!required.equals("empty-tick-handler")&&!required.equals("literal-buffered-output")&&!exactNativeDependency(runtime,required)&&!exactFunctionDependency(runtime,required))throw new IllegalArgumentException("Attribute argument dependency has no complete protocol: "+required);
            protocol.validate(plan,member,bytes.clone());targets.put(source,new Target(source,member,bytes,plan,runtime));
        }
        private Target attributeTarget(php.runtime.reflection.support.PhpAttribute.Argument argument){
            open();for(Target target:targets.values())if(target.entity instanceof php.runtime.loader.dump.PhpAttributeDumper.ArgumentSource source&&source.argument()==argument){target.validate(this);return target;}
            throw new IllegalArgumentException("Attribute argument has no admitted actual runtime initializer");
        }
        public void requireAttributeArgument(php.runtime.reflection.support.PhpAttribute.Argument argument){attributeTarget(argument);}
        public Returned invokeAttributeArgument(php.runtime.reflection.support.PhpAttribute.Argument argument)throws Throwable {
            Target target=attributeTarget(argument);if(currentFrame()==null)throw new IllegalArgumentException("Attribute argument needs its actual caller invocation");
            Frame frame=frame();Invocation invocation=new Invocation(target,frame);invocations.push(invocation);Holder result=null;Throwable primary=null;
            try {
                Memory raw;try{raw=(Memory)target.member.invoke(null,environment);}catch(java.lang.reflect.InvocationTargetException failure){throw failure.getCause();}
                if(!invocation.entered)throw new IllegalStateException("Attribute initializer bypassed its actual generated frame");
                result=new Holder(this,domain.graph().root(),null);
                try(Publication publication=frame.prepareResult(result,raw,Role.VALUE)){publication.storeDirect();publication.commit();}
            }catch(Throwable failure){primary=failure;}
            finally{invocations.pop();try{frame.close();}catch(Throwable cleanup){primary=domain.graph().composeFailure(primary,cleanup);}}
            if(primary!=null){if(result!=null)primary=result.closeAfterFailure(primary);throw primary;}return new Returned(result);
        }
        public void registerFunction(php.runtime.reflection.FunctionEntity entity,byte[] bytes,
                PhpOwnershipExecution.CompleteFrameProtocol protocol) throws Throwable {
            open();domain.currentContext();Objects.requireNonNull(protocol);if(environment.getClass()!=Environment.class)throw new IllegalArgumentException("Custom Environment callback protocol is incomplete");
            java.lang.reflect.Method member=Objects.requireNonNull(entity.getNativeMethod());
            if(!(member.getDeclaringClass().getClassLoader() instanceof php.runtime.loader.RuntimeClassLoader)
                    ||!((php.runtime.loader.RuntimeClassLoader)member.getDeclaringClass().getClassLoader()).matchesDefinedBytecode(member.getDeclaringClass(),bytes))
                throw new IllegalArgumentException("Function proof does not match actual defined bytecode");
            var plan=PhpOwnershipExecution.readFramePlan(bytes,member.getName(),PhpOwnershipExecution.descriptor(member));
            if(plan==null||plan.entryKind()!=PhpOwnershipExecution.EntryKind.FUNCTION
                    ||plan.thrownIds().length!=0||plan.returnIds().length!=0)
                throw new IllegalArgumentException("Ordinary execution coverage is incomplete for this body");
            RuntimePlan runtimePlan=requireRuntimePlan(bytes,member);
            for(String required:plan.requiredCapabilities())
                if(!required.equals("frame-local-publication-v1")&&!required.equals("empty-tick-handler")&&!required.equals("literal-buffered-output")&&!exactNativeDependency(runtimePlan,required)&&!exactFunctionDependency(runtimePlan,required))
                    throw new IllegalArgumentException("Ordinary call/callback capability is not connected: "+required);
            if(entity.getParameters()!=null)for(var parameter:entity.getParameters())
                if(parameter.isVariadic()||parameter.getDefaultInitializer()!=null||parameter.isReference()&&(parameter.getTypeChecker()!=null||parameter.getDefaultValue()!=null||entity.getParameters().length!=1||entity.isReturnReference()))
                    throw new IllegalArgumentException("Ordinary argument/default custody is incomplete");

            if(entity.getParameters()!=null&&entity.getParameters().length==1&&entity.getParameters()[0].isReference()&&plan.locals().stream().noneMatch(local->local.importKind()==PhpOwnershipExecution.ImportKind.ARGUMENT&&local.importIndex()==0&&local.importMode()==PhpOwnershipExecution.Mode.CELL&&local.storage()==PhpOwnershipExecution.Storage.ADDRESS&&local.name().equals(entity.getParameters()[0].getName())))throw new IllegalArgumentException("Reference function lacks its declared actual parameter CV");
            if(plan.requiredCapabilities().contains("literal-buffered-output"))PhpOwnershipNativeCalls.requireLiteralBufferedOutput(environment);
            protocol.validate(plan,member,bytes);targets.put(entity,new Target(entity,member,bytes.clone(),plan,runtimePlan));
        }
        public void registerMethod(php.runtime.reflection.MethodEntity entity,byte[] bytes,PhpOwnershipExecution.CompleteFrameProtocol protocol)throws Throwable {
            open();domain.currentContext();Objects.requireNonNull(protocol);if(environment.getClass()!=Environment.class)throw new IllegalArgumentException("Custom Environment callback protocol is incomplete");var member=Objects.requireNonNull(entity.getNativeMethod());
            if(!(member.getDeclaringClass().getClassLoader() instanceof php.runtime.loader.RuntimeClassLoader)
                    ||!((php.runtime.loader.RuntimeClassLoader)member.getDeclaringClass().getClassLoader()).matchesDefinedBytecode(member.getDeclaringClass(),bytes))
                throw new IllegalArgumentException("Method proof does not match actual defined bytes");
            var plan=PhpOwnershipExecution.readFramePlan(bytes,member.getName(),PhpOwnershipExecution.descriptor(member));
            boolean lexical=entity.getClazz() instanceof php.runtime.reflection.helper.ClosureEntity;
            if(plan==null||plan.entryKind()!=(lexical?PhpOwnershipExecution.EntryKind.LEXICAL_CLOSURE:PhpOwnershipExecution.EntryKind.METHOD)
                    ||plan.thrownIds().length!=0||plan.returnIds().length!=0)
                throw new IllegalArgumentException("Ordinary method custody frontier is incomplete");
            RuntimePlan runtime=requireRuntimePlan(bytes,member);
            for(String required:plan.requiredCapabilities())if(!required.equals("frame-local-publication-v1")&&!required.equals("empty-tick-handler")&&!required.equals("literal-buffered-output")&&!exactNativeDependency(runtime,required))
                throw new IllegalArgumentException("Method callback capability is incomplete: "+required);
            if(entity.getParameters()!=null)for(var parameter:entity.getParameters())if(parameter.isReference()||parameter.isVariadic()||parameter.getDefaultInitializer()!=null)
                throw new IllegalArgumentException("Method SEND/default protocol is incomplete");
            if(runtime.lifecycle()&&(lexical||entity.isStatic()||entity.isAbstract()||entity.isReturnReference()||!entity.getName().equalsIgnoreCase("__destruct")||entity.getClazz().methodDestruct!=entity||entity.getParameters()!=null&&entity.getParameters().length!=0))throw new IllegalArgumentException("Lifecycle plan does not bind its exact destructor entity");
            if(lexical)lexicalProvider((php.runtime.reflection.helper.ClosureEntity)entity.getClazz());

            if(plan.requiredCapabilities().contains("literal-buffered-output"))PhpOwnershipNativeCalls.requireLiteralBufferedOutput(environment);
            protocol.validate(plan,member,bytes);targets.put(entity,new Target(entity,member,bytes.clone(),plan,runtime));
        }
        public Returned invoke(php.runtime.reflection.MethodEntity entity,php.runtime.lang.IObject receiver,php.runtime.env.TraceInfo trace,Memory... args)throws Throwable {
            open();domain.currentContext();if(PhpOwnershipExecution.current(environment)!=null)throw new IllegalStateException("Explicit execution owns this method invocation");
            Target target=targets.get(entity);if(target==null)throw new IllegalArgumentException("Method has no complete ordinary protocol");if(target.runtimePlan.lifecycle())throw new IllegalArgumentException("Lifecycle-only body requires its nonowning destruction entry");target.validate(this);
            if(!entity.isStatic()&&(receiver==null||!entity.getNativeMethod().getDeclaringClass().isInstance(receiver)))
                throw new IllegalArgumentException("Method needs its exact real receiver");
            if(receiver instanceof php.runtime.lang.Closure)((php.runtime.lang.Closure)receiver).ownershipClosure().validate();
            if(target.plan.requiredCapabilities().contains("empty-tick-handler")&&environment.getScope().getTickHandler()!=null)
                throw new IllegalArgumentException("Ordinary debug tick callback is incomplete");
            if(receiver!=null)value(new ObjectMemory(receiver),Role.VALUE);
            Frame frame=frame();Invocation invocation=new Invocation(target,frame,receiver);invocations.push(invocation);
            Holder result=null;Throwable primary=null;
            try {
                if(receiver!=null)try(Publication publication=frame.prepareSend(-1_000_000,new ObjectMemory(receiver),Role.VALUE)){publication.storeDirect();publication.commit();}
                for(int i=0;i<args.length;i++)try(Publication publication=frame.prepareSend(-1-i,args[i],Role.VALUE)){publication.storeDirect();publication.commit();}
                Memory raw=entity.invokeDynamic(receiver,environment,trace,args);
                if(!invocation.entered)throw new IllegalStateException("Method did not enter its exact ordinary frame");
                result=new Holder(this,domain.graph().root(),null);
                try(Publication publication=frame.prepareResult(result,raw,entity.isReturnReference()?Role.CELL:Role.VALUE)){publication.storeDirect();publication.commit();}
            }catch(Throwable failure){primary=failure;}
            finally{invocations.pop();try{frame.close();}catch(Throwable cleanup){primary=domain.graph().composeFailure(primary,cleanup);}}
            if(primary!=null){if(result!=null)primary=result.closeAfterFailure(primary);throw primary;}return new Returned(result);
        }
        /** Preflight for a Provider's exact destructor protocol; it does not create a receiver owner. */
        public void validateDestructor(php.runtime.reflection.ClassEntity actualClass,php.runtime.reflection.MethodEntity method) {
            open();domain.currentContext();Target target=targets.get(method);
            if(current(environment)!=this||PhpOwnershipExecution.current(environment)!=null||target==null||!target.runtimePlan.lifecycle()||actualClass.methodDestruct!=method||method.isStatic()||method.isAbstract()||method.isReturnReference()||!method.getName().equalsIgnoreCase("__destruct")||method.getParameters()!=null&&method.getParameters().length!=0||target.plan.entryKind()!=PhpOwnershipExecution.EntryKind.METHOD||!target.member.getDeclaringClass().isAssignableFrom(actualClass.getNativeClass()))throw new IllegalArgumentException("Destructor lacks its exact registered nonowning body protocol");
            target.validate(this);
        }
        /** A FINALIZING object is borrowed from its one-shot lifecycle ticket, never put in SEND. */
        public void invokeDestructor(PhpOwnershipObjects.ExecutionEntry entry,php.runtime.env.TraceInfo trace)throws Throwable {
            Objects.requireNonNull(entry);entry.validateEnvironment(environment);var method=entry.method();var receiver=entry.receiver();
            validateDestructor(receiver.getReflection(),method);Target target=targets.get(method);
            receiver=entry.consume(domain,environment,method);
            Frame frame=frame();Invocation invocation=new Invocation(target,frame,receiver);invocation.lifecycleEntry=entry;invocations.push(invocation);
            Holder result=null;Throwable primary=null;
            try {
                Memory raw=method.invokeDynamic(receiver,environment,trace,new Memory[0]);
                if(!invocation.entered)throw new IllegalStateException("Destructor did not enter its exact ordinary frame");
                entry.validateReceiver(domain,environment,method,receiver);
                result=new Holder(this,domain.graph().root(),null,Purpose.RETURN);
                try(Publication publication=frame.prepareResult(result,raw,Role.VALUE)){publication.storeDirect();publication.commit();}
            }catch(Throwable failure){primary=failure;}
            finally{invocations.pop();try{frame.close();}catch(Throwable cleanup){primary=domain.graph().composeFailure(primary,cleanup);}}
            if(result!=null)primary=result.closeAfterFailure(primary);
            if(primary!=null)throw primary;
        }
        private Target errorHandlerTarget(php.runtime.invoke.Invoker invoker,Memory callable) {
            open();domain.currentContext();
            if(invoker.getClass()!=php.runtime.invoke.DynamicMethodInvoker.class||invoker.getEnvironment()!=environment
                    ||!(callable instanceof ObjectMemory)||!(((ObjectMemory)callable).value instanceof php.runtime.lang.Closure))
                throw new IllegalArgumentException("Ordinary error handler needs an exact lexical callable target");
            var dynamic=(php.runtime.invoke.DynamicMethodInvoker)invoker;
            var closure=(php.runtime.lang.Closure)((ObjectMemory)callable).value;closure.ownershipClosure().validate();
            var method=closure.getReflection().findMethod("__invoke");Target target=targets.get(method);
            if(dynamic.getObject()!=closure||dynamic.getMethod()!=method||target==null||method.getClazz()!=closure.getReflection()
                    ||target.plan.entryKind()!=PhpOwnershipExecution.EntryKind.LEXICAL_CLOSURE||target.member.getDeclaringClass()!=closure.getClass())
                throw new IllegalArgumentException("Error handler has no exact admitted lexical member");
            target.validate(this);value(callable,Role.VALUE);
            var parameters=method.getParameters();
            if(parameters!=null){
                if(parameters.length>4)throw new IllegalArgumentException("Error handler needs more than the four actual callback arguments");
                for(var parameter:parameters)if(parameter.isReference()||parameter.isVariadic()||parameter.getDefaultValue()!=null
                        ||parameter.getDefaultInitializer()!=null||parameter.getTypeChecker()!=null)
                    throw new IllegalArgumentException("Ordinary typed/default/reference handler arguments require their checked internal-call protocol");
            }
            return target;
        }
        private Target handlerDependency(HandlerSite site) {
            return targets.values().stream().filter(t->t.entity instanceof php.runtime.reflection.MethodEntity&&t.plan.entryKind()==PhpOwnershipExecution.EntryKind.LEXICAL_CLOSURE&&t.member.getDeclaringClass().getName().replace('.','/').equals(site.closure())).findFirst().orElseThrow(()->new IllegalArgumentException("Handler FIELD dependency has no registered exact lexical target"));
        }
        private void requireHandlerParameters(Target target) {
            var method=(php.runtime.reflection.MethodEntity)target.entity;var parameters=method.getParameters();
            if(parameters!=null) {
                if(parameters.length>4)throw new IllegalArgumentException("Handler requires more than its four actual arguments");
                for(var parameter:parameters)if(parameter.isReference()||parameter.isVariadic()||parameter.getDefaultValue()!=null||parameter.getDefaultInitializer()!=null||parameter.getTypeChecker()!=null)throw new IllegalArgumentException("Handler argument checks need their actual internal-call protocol");
            }
        }
        public HandlerRegistration retainErrorHandler(php.runtime.invoke.Invoker invoker,Memory callable)throws Throwable {
            Target target=errorHandlerTarget(invoker,callable);NativeBody body=currentNativeBody(environment);
            if(body==null||body.packet.frame.authority!=this||body.member().getDeclaringClass()!=php.runtime.ext.core.LangFunctions.class
                    ||!body.member().getName().equals("set_error_handler")||body.packet.sent<1)
                throw new IllegalArgumentException("Handler retention needs its exact set_error_handler BODY and SEND");
            Invocation invocation=invocations.peek();
            HandlerSite site=invocation==null?null:invocation.target.runtimePlan.handlers().stream().filter(h->h.call()==body.packet.callId).findFirst().orElse(null);
            if(site==null||handlerDependency(site)!=target||!callable.getClass().equals(ObjectMemory.class)
                    ||!((ObjectMemory)callable).value.getClass().getName().replace('.','/').equals(site.closure()))throw new IllegalArgumentException("Handler FIELD has no exact generated literal/SEND site");
            environment.requireRuntimeErrorHandlerState(this);
            Value send=body.packet.values.getFirst(),actual=value(callable,Role.VALUE);
            if(send.role!=Role.VALUE||send.node!=actual.node||!(send.raw instanceof ObjectMemory)
                    ||((ObjectMemory)send.raw).value!=((ObjectMemory)callable).value)
                throw new IllegalArgumentException("Handler FIELD differs from its actual native SEND");
            return new HandlerRegistration(this,invoker,target,actual);
        }
        public boolean isExecuting(){return !closed&&!frames.isEmpty();}
        /** A requested native symbol has a persistent root; a table borrow owns no PHP ARRAY. */
        private ArrayMemory.NativeGlobals globalTable() {
            open();domain.currentContext();
            if(globalTable!=null){globalTable.validate(environment);return globalTable;}
            ArrayMemory.NativeGlobals prepared=environment.getRuntimeGlobalsTable().prepareNativeGlobals(environment,carrier->{
                if(carrier.getClass()!=php.runtime.memory.support.ArrayMapEntryMemory.class||carrier.getOwnershipEntry()!=null||carrier.getOwnershipCell()!=null||carrier.getRuntimeCell()!=null||carrier.getGlobalTableBorrow()!=null)
                    throw new IllegalArgumentException("Native global table contains an unsupported physical publisher");
                Holder holder=carrier.getRuntimeHolder();
                if(holder!=null&&(holder.authority!=this||!holder.matches(carrier)))throw new IllegalArgumentException("Native global carrier has a foreign or stale publisher");
                Environment.SyntheticGlobalsOrigin origin=carrier.getSyntheticGlobalsOrigin();if(origin!=null){if(origin.environment()!=environment)throw new IllegalArgumentException("Foreign synthetic global origin");origin.validateCarrier(carrier);}
            });
            Environment.SyntheticGlobalsOrigin origin=environment.getSyntheticGlobalsOrigin();
            if(origin!=null&&!origin.isRetired()) {
                var old=prepared.slot("GLOBALS");
                if(old.carrier()!=origin.carrier())throw new IllegalArgumentException("Synthetic global origin differs from its actual table slot");
                try(var retirement=prepared.prepareSyntheticRetirement(old,origin)){retirement.beginStore();retirement.store();}
            }
            globalTable=prepared;return prepared;
        }
        public GlobalSlot globalSlot(Object normalizedKey)throws Throwable {
            open();domain.currentContext();ArrayMemory.NativeGlobals table=globalTable();
            var physical=table.slot(normalizedKey);GlobalSlot existing=globals.get(normalizedKey);
            if(existing!=null){existing.validate();if(existing.physical!=physical)throw new IllegalArgumentException("Native global registry changed its actual address");return existing;}
            ReferenceMemory carrier=physical.carrier();
            if(carrier.getSyntheticGlobalsOrigin()!=null||carrier.getGlobalTableBorrow()!=null||carrier.getOwnershipEntry()!=null||carrier.getRuntimeHolder()!=null)
                throw new IllegalArgumentException("Global address already belongs to another publisher");
            Value initial=value(carrier.getRawValue(),Role.VALUE);
            Holder holder=new Holder(this,domain.graph().root(),carrier);GlobalSlot slot=new GlobalSlot(this,table,physical,holder);holder.globalSlot=slot;
            try{holder.initialize(initial);carrier.attachRuntimeHolder(holder);globals.put(normalizedKey,slot);return slot;}
            catch(Throwable failure){throw holder.closeAfterFailure(failure);}
        }
        public ReferenceMemory globalAddress(String name)throws Throwable {
            Objects.requireNonNull(name);return globalSlot(ArrayMemory.toKey(new php.runtime.memory.StringMemory(name))).carrier();
        }
        public void assignGlobal(String name,Memory semanticValue)throws Throwable{globalAddress(name).assign(semanticValue);}
        public void registerModule(php.runtime.reflection.ModuleEntity entity,byte[] bytes,PhpOwnershipExecution.CompleteFrameProtocol protocol)throws Throwable {
            open();domain.currentContext();if(environment.getClass()!=Environment.class)throw new IllegalArgumentException("Custom Environment callback protocol is incomplete");java.lang.reflect.Method member=entity.getNativeMethod();
            if(!(member.getDeclaringClass().getClassLoader() instanceof php.runtime.loader.RuntimeClassLoader)
                    ||!((php.runtime.loader.RuntimeClassLoader)member.getDeclaringClass().getClassLoader()).matchesDefinedBytecode(member.getDeclaringClass(),bytes))
                throw new IllegalArgumentException("Module proof does not match its actual defined class");
            var plan=PhpOwnershipExecution.readFramePlan(bytes,member.getName(),PhpOwnershipExecution.descriptor(member));
            if(plan==null||plan.entryKind()!=PhpOwnershipExecution.EntryKind.MODULE
                    ||plan.thrownIds().length!=0||plan.returnIds().length!=0)
                throw new IllegalArgumentException("Module producer/callback coverage is incomplete");
            RuntimePlan runtimePlan=requireRuntimePlan(bytes,member);
            for(String required:plan.requiredCapabilities())if(!required.equals("frame-local-publication-v1")&&!required.equals("empty-tick-handler")&&!required.equals("literal-buffered-output")&&!exactNativeDependency(runtimePlan,required)&&!exactFunctionDependency(runtimePlan,required))
                throw new IllegalArgumentException("Module capability is not connected: "+required);
            if(plan.requiredCapabilities().contains("literal-buffered-output"))PhpOwnershipNativeCalls.requireLiteralBufferedOutput(environment);
            protocol.validate(plan,member,bytes);targets.put(entity,new Target(entity,member,bytes.clone(),plan,runtimePlan));
        }
        /** Host adapters preflight the same exact entry before changing any actual table. */
        public void requireModuleEntry(php.runtime.reflection.ModuleEntity entity) {
            open();domain.currentContext();
            if(PhpOwnershipExecution.current(environment)!=null)throw new IllegalStateException("Explicit module invocation already owns custody");
            Target target=targets.get(entity);
            if(target==null)throw new IllegalArgumentException("Module has no complete ordinary producer protocol");
            target.validate(this);
        }
        public Returned include(php.runtime.reflection.ModuleEntity entity)throws Throwable {
            open();domain.currentContext();if(PhpOwnershipExecution.current(environment)!=null)throw new IllegalStateException("Explicit module invocation already owns custody");
            Target target=targets.get(entity);if(target==null)throw new IllegalArgumentException("Module has no complete ordinary producer protocol");
            target.validate(this);Frame frame=frame();Invocation invocation=new Invocation(target,frame);invocations.push(invocation);
            Holder result=null;Throwable primary=null;
            try {
                Memory raw=entity.include(environment);
                if(!invocation.entered)throw new IllegalStateException("Actual module did not enter its registered logical frame");
                result=new Holder(this,domain.graph().root(),null);
                try(Publication publication=frame.prepareResult(result,raw,Role.VALUE)){publication.storeDirect();publication.commit();}
            }catch(Throwable failure){primary=failure;}
            finally{invocations.pop();try{frame.close();}catch(Throwable cleanup){primary=domain.graph().composeFailure(primary,cleanup);}}
            if(primary!=null){if(result!=null)primary=result.closeAfterFailure(primary);throw primary;}
            return new Returned(result);
        }
        public Returned invoke(php.runtime.reflection.FunctionEntity entity,php.runtime.env.TraceInfo trace,Memory... args) throws Throwable {
            open();domain.currentContext();if(PhpOwnershipExecution.current(environment)!=null)
                throw new IllegalStateException("Explicit execution already owns this invocation");
            Target target=targets.get(entity);if(target==null)throw new IllegalArgumentException("Function has no complete ordinary frame protocol");
            if(entity.getParameters()!=null&&java.util.Arrays.stream(entity.getParameters()).anyMatch(parameter->parameter.isReference()))throw new IllegalArgumentException("Reference invocation requires its actual compiler ADDRESS SEND packet");
            target.validate(this);
            if(target.plan.requiredCapabilities().contains("empty-tick-handler")&&environment.getScope().getTickHandler()!=null)
                throw new IllegalArgumentException("Ordinary debug tick callback is not connected");
            for(Memory argument:args)if(argument instanceof ObjectMemory&&((ObjectMemory)argument).value.getReflection().methodDestruct!=null)
                throw new IllegalArgumentException("Ordinary destructor invocation requires its complete target protocol");
            Frame frame=frame();Invocation invocation=new Invocation(target,frame);invocations.push(invocation);
            Holder result=null;Throwable primary=null;
            try {
                for(int i=0;i<args.length;i++)try(Publication publication=frame.prepareSend(-1-i,args[i],Role.VALUE)){
                    publication.storeDirect();publication.commit();
                }
                Memory raw=entity.invoke(environment,trace,args);
                if(!invocation.entered)throw new IllegalStateException("Actual function never entered its registered frame");
                result=new Holder(this,domain.graph().root(),null);
                try(Publication publication=frame.prepareResult(result,raw,entity.isReturnReference()?Role.CELL:Role.VALUE)){
                    publication.storeDirect();publication.commit();
                }
            }catch(Throwable failure){primary=failure;}
            finally {
                invocations.pop();try{frame.close();}catch(Throwable cleanup){primary=domain.graph().composeFailure(primary,cleanup);}
            }
            if(primary!=null){if(result!=null)primary=result.closeAfterFailure(primary);throw primary;}
            return new Returned(result);
        }
        public Value value(Memory raw,Role role) {
            open();domain.currentContext();Objects.requireNonNull(raw);Objects.requireNonNull(role);
            if(role==Role.BORROW_ADDRESS)throw new IllegalArgumentException("A borrowed address is not an owned value");
            PhpOwnership.Node node=null;
            if(role==Role.CELL) {
                if(raw.getClass()!=ReferenceMemory.class)throw new IllegalArgumentException("CELL requires the exact canonical representation");
                ReferenceMemory cell=(ReferenceMemory)raw;
                CellOrigin origin=cell.getRuntimeCell();
                if(origin!=null) {origin.validate(this);node=origin.node;}
                else {
                    var cert=cell.getOwnershipCell();
                    if(cert==null||cert.domain()!=domain)throw new IllegalArgumentException("CELL has no matching allocation origin");
                    node=cert.node();
                }
                PhpOwnershipValues.bindCell(domain,cell,node);
            } else {
                if(raw instanceof ReferenceMemory)throw new IllegalArgumentException("VALUE requires a semantic read before publication");
                if(raw instanceof ArrayMemory) {
                    ArrayMemory array=(ArrayMemory)raw;
                    var cert=array.getOwnershipStorage();
                    if(cert!=null) {
                        if(cert.domain()!=domain)throw new IllegalArgumentException("Foreign ARRAY origin");
                        cert.validateAttachment(array);node=cert.node();
                    } else {
                        ArrayOrigin origin=arrays.get(array.getStorageIdentity());
                        if(origin==null)throw new IllegalArgumentException("ARRAY allocation has no complete physical protocol");
                        origin.validate(array);node=origin.node;
                    }
                } else if(raw instanceof ObjectMemory) {
                    ObjectMemory object=(ObjectMemory)raw;
                    if(object.value instanceof IPhpOwnershipObject) {
                        var cert=((IPhpOwnershipObject)object.value).getOwnershipObject();
                        if(cert==null)throw new IllegalArgumentException("OBJECT allocation has no complete origin");
                        node=cert.node();
                    } else throw new IllegalArgumentException("OBJECT requires its complete allocation origin");
                }
                PhpOwnershipValues.bindValue(domain,raw,node).requireCertifiedObject();
            }
            return new Value(this,raw,role,node);
        }
        /** Borrow an existing certified physical publisher; this installs metadata only, never a graph edge. */
        public Holder adopt(PhpOwnershipArrays.PublisherFacet facet) {
            open();domain.currentContext();Objects.requireNonNull(facet);
            if(facet.domain()!=domain)throw new IllegalArgumentException("Foreign physical publisher");
            var snapshot=facet.current();
            value(snapshot.binding().rawValue(),snapshot.binding().role()==PhpOwnershipValues.Role.CELL?Role.CELL:Role.VALUE);
            var cached=shared.facets.get(facet);Holder existing=cached==null?null:cached.get();
            if(existing!=null){existing.open();return existing;}
            Holder holder=new Holder(this,facet.borrowedSlot(),facet.carrier());holder.facet=facet;
            holder.refreshFacet(snapshot);holders.remove(holder);shared.facets.put(facet,new java.lang.ref.WeakReference<>(holder));return holder;
        }
        public Holder physical(PhpOwnership.Node owner,ReferenceMemory carrier,Value initial) throws Throwable {
            open();initial.validate(this);Objects.requireNonNull(carrier);
            if(carrier.getRuntimeHolder()!=null||carrier.getOwnershipEntry()!=null)
                throw new IllegalArgumentException("Physical address already has a publisher");
            if(carrier.getRawValue()!=initial.raw)throw new IllegalArgumentException("Physical payload differs from initial binding");
            if(owner==null||owner.graph()!=domain.graph())throw new IllegalArgumentException("Physical owner belongs to another heap");
            Holder holder=new Holder(this,domain.graph().edge(owner),carrier);
            try {holder.initialize(initial);carrier.attachRuntimeHolder(holder);return holder;}
            catch(Throwable failure){throw holder.closeAfterFailure(failure);}
        }
        public CellOrigin canonicalCell(ReferenceMemory cell,Value payload) throws Throwable {
            open();payload.validate(this);
            if(payload.role!=Role.VALUE||cell.getClass()!=ReferenceMemory.class||cell.getRawValue()!=payload.raw)
                throw new IllegalArgumentException("Canonical CELL needs its exact VALUE payload");
            if(cell.getRuntimeCell()!=null||cell.getRuntimeHolder()!=null||cell.getOwnershipCell()!=null||cell.getOwnershipEntry()!=null)
                throw new IllegalArgumentException("CELL payload already has a publisher");
            PhpOwnership.Node node=domain.canonicalCell(cell);
            CellOrigin origin=new CellOrigin(this,node,cell);
            Holder holder=new Holder(this,domain.graph().edge(node),cell);origin.payload=holder;
            try {holder.initialize(payload);cell.attachRuntimeCell(origin);cell.attachRuntimeHolder(holder);cells.put(cell,origin);return origin;}
            catch(Throwable failure){throw holder.closeAfterFailure(failure);}
        }
        public ArrayOrigin arrayOrigin(ArrayMemory array,CompletePhysicalProtocol protocol) {return arrayOrigin(array,array,protocol);}
        public ArrayOrigin arrayOrigin(ArrayMemory array,ArrayMemory layout,CompletePhysicalProtocol protocol) {
            open();domain.currentContext();Objects.requireNonNull(protocol).validate(array);
            if(array.getOwnershipStorage()!=null)throw new IllegalArgumentException("Certified storage requires publisher adoption, not another edge set");
            if(array instanceof php.runtime.memory.PhpObjectProperties)throw new IllegalArgumentException("TABLE has its separate allocation protocol");
            if(layout.getStorageIdentity()!=array.getStorageIdentity())throw new IllegalArgumentException("Captured layout belongs to another storage");
            Object identity=array.getStorageIdentity();ArrayOrigin existing=arrays.get(identity);
            if(existing!=null){existing.validate(array);return existing;}
            PhpOwnership.Node node=domain.storage(identity);
            ArrayOrigin origin=new ArrayOrigin(this,node,identity,layout);arrays.put(identity,origin);return origin;
        }
        private void retireDisposedHolders() {
            for(Iterator<Holder> it=holders.iterator();it.hasNext();) {
                Holder holder=it.next();
                if(holder.edge.isClosed()){holder.closed=true;holder.raw=Memory.UNDEFINED;holder.role=Role.VALUE;holder.revision++;if(holder.facet!=null)shared.facets.remove(holder.facet);it.remove();}
            }
        }
        @Override public void close() throws Exception {
            open();if(!frames.isEmpty())throw new IllegalStateException("Close actual runtime frames before authority shutdown");
            for(Holder holder:holders)if(holder.authority==this&&!holder.closed&&holder.purpose==Purpose.FIELD)throw new IllegalStateException("Finalize the actual Environment handler fields before authority shutdown");
            try(var cleanup=participant.enter()) {
                retireDisposedHolders();
                try{domain.graph().deferDestruction(()->{for(GlobalSlot global:new ArrayList<>(globals.values())){global.unset();global.holder.close();}globals.clear();});}catch(Throwable failure){fail(failure);}
                for(Holder holder:holders)if(holder.authority==this&&holder.edge.owner()==null)throw new IllegalStateException("An actual caller result/root remains live");
                participant.close();closed=true;
            }
        }
    }

    /** Exact named table address and its single persistent global root. */
    public static final class GlobalSlot {
        private final Authority authority;
        private final ArrayMemory.NativeGlobals table;
        private final php.runtime.memory.support.ArrayMemoryMap.NativeGlobalSlot physical;
        private final Holder holder;
        private GlobalSlot(Authority authority,ArrayMemory.NativeGlobals table,php.runtime.memory.support.ArrayMemoryMap.NativeGlobalSlot physical,Holder holder){this.authority=authority;this.table=table;this.physical=physical;this.holder=holder;}
        private void validate(){authority.open();authority.domain.currentContext();table.validate(authority.environment);physical.validate();if(holder.globalSlot!=this||holder.carrier!=physical.carrier())throw new IllegalArgumentException("Native global slot has a stale publisher");}
        public Object key(){validate();return physical.key();}
        public ReferenceMemory carrier(){validate();holder.open();return holder.carrier;}
        public Holder holder(){validate();holder.open();return holder;}
        public boolean isLinked(){validate();return physical.isLinked();}
        /** Reading a detached address does not insert a placeholder bucket. */
        public Memory read(){validate();holder.open();return holder.role==Role.CELL?((ReferenceMemory)holder.raw).getRawValue():holder.raw;}
        public Memory assign(Memory semantic){validate();return holder.assign(semantic);}
        public ReferenceMemory reference(){validate();return holder.reference();}
        public void unset(){validate();holder.unset();}
        private ArrayMemory.NativeGlobals.NativeGlobalPlacement prepare(boolean link) {
            validate();if(physical.isLinked()==link)return null;
            return link?table.prepareLink(physical):table.prepareUnlink(physical);
        }
    }
    private enum GlobalOperation { NONE, STORE, UNSET }

    public static final class Value {
        private final Authority authority;
        private final Memory raw;
        private final Role role;
        private final PhpOwnership.Node node;
        private Value(Authority authority,Memory raw,Role role,PhpOwnership.Node node){this.authority=authority;this.raw=raw;this.role=role;this.node=node;}
        public Authority authority(){return authority;}
        public Memory raw(){return raw;}
        public Role role(){return role;}
        public PhpOwnership.Node node(){return node;}
        private void validate(Authority expected){
            if(authority.domain!=expected.domain)throw new IllegalArgumentException("Foreign runtime binding");
            if(node!=null&&(node.graph()!=expected.domain.graph()||node.state()==PhpOwnership.State.DISPOSED))throw new IllegalStateException("Runtime binding is disposed or foreign");
        }
    }

    private enum Purpose { OWNER, PRODUCER_EVAL, DIAGNOSTIC, SEND, RETURN, FIELD }

    public static final class Holder implements AutoCloseable {
        private final Authority authority;
        private final PhpOwnership.Slot edge;
        private final ReferenceMemory carrier;
        private final Purpose purpose;
        private PhpOwnershipArrays.PublisherFacet facet;
        private NativeProducer nativeProducer;
        private GlobalSlot globalSlot;
        private Memory raw=Memory.UNDEFINED;
        private Role role=Role.VALUE;
        private long revision;
        private boolean closed;
        private Holder(Authority authority,PhpOwnership.Slot edge,ReferenceMemory carrier){this(authority,edge,carrier,Purpose.OWNER);}
        private Holder(Authority authority,PhpOwnership.Slot edge,ReferenceMemory carrier,Purpose purpose){this.authority=authority;this.edge=edge;this.carrier=carrier;this.purpose=purpose;authority.holders.add(this);}
        private void initialize(Value initial) throws Throwable {raw=initial.raw;role=initial.role;edge.replace(initial.node);}
        private void refreshFacet(PhpOwnershipHeap.Snapshot snapshot){
            var binding=snapshot.binding();raw=binding.rawValue();role=binding.role()==PhpOwnershipValues.Role.CELL?Role.CELL:Role.VALUE;revision=snapshot.revision();
        }
        private void retireFacet(){closed=true;raw=Memory.UNDEFINED;role=Role.VALUE;revision++;authority.holders.remove(this);authority.shared.facets.remove(facet);}
        private void open(){
            authority.domain.currentContext();
            if(facet!=null&&!closed){if(facet.isDisposed())retireFacet();else refreshFacet(facet.current());}
            if(closed||edge.isClosed())throw new IllegalStateException("Runtime holder was retired");
            if(globalSlot!=null)globalSlot.validate();
        }
        /** Uses the Entry's original private store permit and can transfer only a real producer EVAL. */
        public PhpOwnershipArrays.RawPublication prepareFacetValue(PhpOwnershipHeap.Snapshot expected,
                PhpOwnershipValues.Binding next,Holder source){
            open();if(facet==null)throw new IllegalArgumentException("Holder is not an adopted publisher");
            return facet.prepareValue(expected,next,source);
        }
        public PhpOwnershipHeap.Snapshot facetSnapshot(){open();if(facet==null)throw new IllegalArgumentException("Holder is not an adopted publisher");return facet.current();}
        public void requireProducerEvaluation(){
            open();Frame frame=authority().currentFrame();
            if(purpose!=Purpose.PRODUCER_EVAL||edge.owner()!=null||frame==null||!frame.evaluations.containsValue(this))
                throw new IllegalArgumentException("Operation needs a producer EVAL, not diagnostic, SEND, CV or caller custody");
        }
        FacetSource requireFacetSource(PhpOwnershipDomain expected,PhpOwnershipValues.Binding next) {
            open();if(expected!=authority.domain||next.domain()!=expected||edge.owner()!=null)throw new IllegalArgumentException("Facet transfer needs a same-domain actual EVAL root");
            requireProducerEvaluation();Authority context=authority();
            Role expectedRole=next.role()==PhpOwnershipValues.Role.CELL?Role.CELL:Role.VALUE;
            if(role!=expectedRole||edge.target()!=next.node())throw new IllegalArgumentException("Facet transfer does not identify its actual EVAL value");
            Value actual=context.value(next.rawValue(),expectedRole);
            if(actual.node!=edge.target()||actual.node==null&&!raw.identical(actual.raw))throw new IllegalArgumentException("Facet transfer candidate differs from its producer");
            return new FacetSource(this,revision,raw);
        }
        private PhpOwnershipHeap.Publisher valuePublisher;
        /** Same physical VALUE edge, exposed only as a stable read snapshot for certified COW preparation. */
        public PhpOwnershipHeap.Publisher borrowValuePublisher() {
            open();if(role!=Role.VALUE)throw new IllegalArgumentException("COW snapshot needs the actual VALUE payload holder");
            if(valuePublisher==null)valuePublisher=new PhpOwnershipHeap.Publisher() {
                private PhpOwnershipHeap.Snapshot snapshot;
                public PhpOwnershipDomain domain(){return Holder.this.domain();}
                public PhpOwnershipHeap.Snapshot current() {
                    Holder.this.open();if(role!=Role.VALUE)throw new IllegalArgumentException("COW publisher no longer holds VALUE");
                    Value actual=authority().value(raw,Role.VALUE);
                    if(actual.node!=edge.target())throw new IllegalArgumentException("COW publisher drifted from its actual edge");
                    if(snapshot==null||snapshot.revision()!=revision||snapshot.binding().rawValue()!=raw||snapshot.binding().node()!=actual.node)
                        snapshot=new PhpOwnershipHeap.Snapshot(this,PhpOwnershipValues.bindValue(domain(),raw,actual.node),revision);
                    return snapshot;
                }
                public PhpOwnershipHeap.Publication prepare(PhpOwnershipHeap.Snapshot expected,PhpOwnershipValues.Binding next) {
                    throw new IllegalArgumentException("COW requires the original carrier or actual JVM CV publication protocol");
                }
            };
            return valuePublisher;
        }
        /** The scalar operator token, rather than an arbitrary callback, proves this captured address. */
        public IncrementLease captureIncrement(ReferenceMemory.IncrementCell token) {return new IncrementLease(this,token);}
        public PhpOwnershipDomain domain(){return authority.domain;}
        public Authority authority(){
            Object context=authority.domain.currentContext();
            if(!(context instanceof Environment))throw new IllegalStateException("Runtime holder requires a releasing Environment");
            Authority active=((Environment)context).getRuntimeOwnership();
            if(active==null||active.domain!=authority.domain)throw new IllegalArgumentException("Releasing participant lacks this heap's authority");
            return active;
        }
        public PhpOwnership.Node node(){open();return edge.target();}
        public Memory raw(){open();return raw;}
        public Role role(){open();return role;}
        public boolean hasPhysicalCarrier(){return carrier!=null;}
        public boolean matches(ReferenceMemory actual){if(facet!=null){open();return facet.carrier()==actual;}return !closed&&carrier==actual&&actual.getRawValue()==raw;}
        public Publication prepare(Memory candidate,Role role){return prepare(authority().value(candidate,role));}
        public Publication prepare(Value candidate){
            open();candidate.validate(authority);
            if(facet!=null)throw new IllegalStateException("Adopted publisher requires its original Entry permit");
            if(carrier!=null&&candidate.role==Role.CELL)throw new IllegalArgumentException("Physical reference binding needs its Source-change permit");
            if(carrier!=null&&candidate.role==Role.VALUE) {
                if(role==Role.CELL)throw new IllegalArgumentException("VALUE assignment must use the current canonical payload publisher");
                Memory checked=carrier.validateOwnershipAssignment(candidate.raw);
                candidate=authority().value(checked,Role.VALUE);
            }
            return new Publication(this,candidate,null,null,null,GlobalOperation.STORE);
        }
        /** The insertion token proves a prospective actual mixed bucket, never a generic field callback. */
        public Publication prepareArrayInsertion(ArrayMemory.RuntimeInsertion token,Memory candidate) {
            open();Objects.requireNonNull(token);token.checkHolder(this,carrier);
            if(carrier==null||role!=Role.VALUE||!raw.isNull())throw new IllegalArgumentException("Insertion needs its exact empty physical carrier");
            if(candidate instanceof ReferenceMemory) {
                Value next=authority().value(candidate,Role.CELL);
                var change=carrier.prepareRuntimeReference((ReferenceMemory)candidate,false);
                if(change.checkedTarget()!=((ReferenceMemory)candidate).getRawValue())
                    throw new IllegalArgumentException("Fresh insertion cannot perform an unstaged payload coercion");
                return new Publication(this,next,change);
            }
            return prepare(candidate,Role.VALUE);
        }
        public Publication prepareArrayRemoval(ArrayMemory.RuntimeRemoval token) {
            open();Objects.requireNonNull(token);token.checkHolder(this,carrier);
            if(carrier==null||carrier.getRuntimeCell()!=null)throw new IllegalArgumentException("Removal needs the bucket facet, not CELL payload");
            var removal=carrier.prepareOwnershipSourceRemoval();
            return new Publication(this,authority().value(Memory.UNDEFINED,Role.VALUE),null,null,removal);
        }
        public Memory assign(Memory candidate){
            open();if(facet!=null){
                try{Value next=authority().value(candidate,Role.VALUE);facet.physicalSlot().payloadPublisher().assign(PhpOwnershipValues.bindValue(domain(),next.raw,next.node));return facet.physicalSlot().payloadPublisher().current().binding().rawValue();}
                catch(Throwable failure){return fail(failure);}
            }
            if(carrier==null)throw new IllegalStateException("Direct JVM CV assignment requires prepare, pure ASTORE, then commit");
            if(role==Role.CELL) {
                CellOrigin origin=((ReferenceMemory)raw).getRuntimeCell();
                if(origin==null) {
                    authority().value(raw,Role.CELL);var certificate=authority().arrays().certificate((ReferenceMemory)raw);
                    if(!(certificate.value() instanceof PhpOwnershipArrays.Entry))throw new IllegalArgumentException("Certified CELL has no actual canonical payload publisher");
                    return authority().adopt(((PhpOwnershipArrays.Entry)certificate.value()).runtimeFacet()).assign(candidate);
                }
                return origin.payload.assign(candidate);
            }
            Memory checked=carrier==null?candidate:carrier.validateOwnershipAssignment(candidate);
            try(Publication publication=prepare(checked,Role.VALUE)){
                if(carrier!=null)carrier.publishRuntimeRaw(publication);else publication.storeDirect();
                publication.commit();return checked;
            }catch(Throwable failure){return fail(failure);}
        }
        public ReferenceMemory reference(){
            open();if(facet!=null){
                if(facet.isCanonicalPayload())throw new IllegalStateException("Canonical payload is not an alias address");
                if(role==Role.CELL)return (ReferenceMemory)raw;
                try(var publication=facet.physicalSlot().preparePromotion(facet.current())){ReferenceMemory cell=publication.cell();publication.store();publication.commit();return cell;}
                catch(Throwable failure){return fail(failure);}
            }
            if(carrier!=null&&carrier.getRuntimeCell()!=null)throw new IllegalStateException("Canonical payload is not an alias address");
            if(role==Role.CELL)return (ReferenceMemory)raw;
            if(carrier==null)throw new IllegalStateException("Reference export needs its actual address publisher");
            try {
                final ReferenceMemory[] result=new ReferenceMemory[1];
                authority.domain.graph().deferDestruction(()->{
                    Memory initial=raw.isUndefined()?Memory.NULL:raw;
                    ReferenceMemory cell=new ReferenceMemory(initial);
                    CellOrigin origin=authority().canonicalCell(cell,authority().value(initial,Role.VALUE));
                    try(PhpOwnership.Pin pin=authority.domain.graph().pin(origin.node)){
                        var change=carrier.prepareRuntimeReference(cell,true);
                        try(Publication publication=new Publication(this,authority().value(cell,Role.CELL),change,null,null,GlobalOperation.STORE)){
                            carrier.publishRuntimeReference(change,publication);publication.commit();
                        }
                    }
                    result[0]=cell;
                });return result[0];
            }catch(Throwable failure){return fail(failure);}
        }
        public ReferenceMemory rebind(ReferenceMemory target){
            open();if(facet!=null){
                if(facet.isCanonicalPayload())throw new IllegalStateException("Canonical payload cannot be rebound");
                try(var publication=facet.physicalSlot().prepareRebind(facet.current(),PhpOwnershipValues.bindCell(domain(),target,authority().value(target,Role.CELL).node))){publication.store();publication.commit();return target;}
                catch(Throwable failure){return fail(failure);}
            }
            if(carrier!=null&&carrier.getRuntimeCell()!=null)throw new IllegalStateException("Canonical payload cannot be rebound");
            if(carrier==null)throw new IllegalStateException("Reference rebind needs its actual address publisher");
            Value next=authority().value(target,Role.CELL);
            var change=carrier.prepareRuntimeReference(target,false);
            if(change.checkedTarget()!=target.getRawValue()) {
                CellOrigin origin=target.getRuntimeCell();
                if(origin==null)throw new IllegalStateException("Rebinding requires the actual payload publisher");
                origin.payload.assign(change.checkedTarget());change.validate();
            }
            try(Publication publication=new Publication(this,next,change,null,null,GlobalOperation.STORE)){
                carrier.publishRuntimeReference(change,publication);publication.commit();return target;
            }catch(Throwable failure){return fail(failure);}
        }
        public void unset(){
            open();if(facet!=null)throw new IllegalStateException("Certified facet removal requires its actual container address token");if(carrier==null)throw new IllegalStateException("Direct JVM CV unset requires its pure ASTORE publication");if(carrier!=null&&carrier.getRuntimeCell()!=null)throw new IllegalStateException("Unset requires the alias holder, not its canonical payload");
            var removal=carrier==null?null:carrier.prepareOwnershipSourceRemoval();
            try(Publication publication=new Publication(this,authority().value(Memory.UNDEFINED,Role.VALUE),null,null,null,GlobalOperation.UNSET)){
                if(carrier!=null)carrier.publishRuntimeUnset(publication,removal);else publication.storeDirect();
                publication.commit();
            }catch(Throwable failure){fail(failure);}
        }
        @Override public void close() throws Exception {
            if(closed)return;if(facet!=null){authority.domain.currentContext();retireFacet();return;}open();closed=true;raw=Memory.UNDEFINED;role=Role.VALUE;revision++;authority.holders.remove(this);
            try{edge.close();}catch(Throwable failure){fail(failure);}
        }
        private Throwable closeAfterFailure(Throwable primary){try{close();}catch(Throwable cleanup){return authority.domain.graph().composeFailure(primary,cleanup);}return primary;}
    }

    /** A captured scalar target is retained by a real diagnostic owner, never a transferable EVAL. */
    public static final class IncrementLease implements AutoCloseable {
        private final Holder holder;
        private final ReferenceMemory.IncrementCell token;
        private final Diagnostic retention;
        private boolean closed;
        private IncrementLease(Holder holder,ReferenceMemory.IncrementCell token) {
            holder.open();token.validateRuntimeCarrier(holder,holder.carrier);
            if(holder.facet!=null||holder.carrier==null||holder.purpose!=Purpose.OWNER||holder.edge.owner()==null)
                throw new IllegalArgumentException("Increment lease needs its actual runtime bucket or canonical payload");
            Frame frame=token.runtimeFrame();
            if(frame.authority.domain!=holder.authority.domain||holder.authority().currentFrame()!=frame)
                throw new IllegalArgumentException("Increment lease belongs to another active frame");
            this.holder=holder;this.token=token;
            try {
                PhpOwnership.Node owner=holder.edge.owner();
                if(owner.kind()==PhpOwnership.Kind.CELL) {
                    CellOrigin cell=holder.carrier.getRuntimeCell();
                    if(cell==null||cell.node!=owner||cell.payload!=holder)
                        throw new IllegalArgumentException("Increment lease lacks its captured canonical CELL");
                    retention=frame.pinTemporary(cell.cell,Role.CELL,Purpose.DIAGNOSTIC);
                } else {
                    ArrayOrigin origin=holder.authority.shared.arrays.values().stream().filter(value->value.node==owner).findFirst()
                            .orElseThrow(()->new IllegalArgumentException("Increment bucket lacks its actual ARRAY allocation origin"));
                    origin.validate(origin.layout);
                    // Zend captures this scalar TEMP, not another PHP VALUE owner of its ARRAY.
                    // The original bucket remains writable only while a genuine backing owner survives.
                    retention=frame.pinTemporary(token.runtimeScalar(),Role.VALUE,Purpose.DIAGNOSTIC);
                }
            }catch(Throwable failure){throw PhpRuntimeOwnership.<RuntimeException,RuntimeException>fail(failure);}
        }
        public Publication prepare(ReferenceMemory.IncrementCell actual,Memory candidate,boolean restore) {
            holder.authority.domain.currentContext();
            if(closed||actual!=token)throw new IllegalArgumentException("Increment lease was consumed or substituted");
            token.validateRuntimeCarrier(holder,holder.carrier);token.validateRuntimeValue(candidate,restore);
            Value next=holder.authority().value(candidate,Role.VALUE);
            if(next.node!=null)throw new IllegalArgumentException("Captured scalar increment cannot publish a heap value");
            // A removed bucket's captured TEMP can finish, but never relinks its old address.
            if(holder.closed||holder.edge.isClosed())return null;
            holder.open();return new Publication(holder,next);
        }
        @Override public void close()throws Exception {if(!closed){closed=true;retention.close();}}
    }

    /** Package-bound exact EVAL producer proof used by the existing Entry permit, not another owner. */
    static final class FacetSource {
        private final Holder holder;private final long revision;private final Memory raw;private boolean consumed;
        private FacetSource(Holder holder,long revision,Memory raw){this.holder=holder;this.revision=revision;this.raw=raw;}
        void validate(){holder.open();if(consumed||holder.revision!=revision||holder.raw!=raw)throw new IllegalArgumentException("Facet producer is stale or already transferred");}
        PhpOwnership.Slot slot(){validate();return holder.edge;}
        /** Called only by the private actual Entry permit after its pure raw field store. */
        void consumeAfterRaw(){consumed=true;holder.raw=Memory.UNDEFINED;holder.role=Role.VALUE;holder.revision++;if(holder.nativeProducer!=null)holder.nativeProducer.clearedAfterRaw();}
    }

    /** Prepare before ASTORE/private field store. No callback or throwing work may occur between store and commit. */
    public static final class Publication implements AutoCloseable {
        private final Holder holder;
        private final GlobalTableBorrow tableBorrow;
        private Frame borrowFrame;private Map<Integer,Holder> nextLocals;private Set<Holder> nextBorrowed;private Map<Integer,ReferenceMemory> nextBorrowedAddresses;private Holder retiredLocal;private boolean attachCarrier,borrowCarrier;
        private Frame closureOriginFrame;private Map<Integer,CvOrigin> nextClosureOrigins;
        private Frame objectOriginFrame;private Map<Integer,ObjectOrigin> nextObjectOrigins;
        private final Value next;
        private final Memory before;
        private final long revision;
        private final PhpOwnership.Publication graph;
        private final ArrayMemory.NativeGlobals.NativeGlobalPlacement globalPlacement;
        private final ReferenceMemory.OwnershipReferenceChange referenceChange;
        private final Holder source;
        private final ReferenceMemory.OwnershipSourceRemoval sourceRemoval;
        private boolean stored,finished;
        private Publication(Holder holder,Value next){this(holder,next,null,null);}
        private Publication(Holder holder,Value next,ReferenceMemory.OwnershipReferenceChange referenceChange){this(holder,next,referenceChange,null);}
        private Publication(Holder holder,Value next,ReferenceMemory.OwnershipReferenceChange referenceChange,Holder source){this(holder,next,referenceChange,source,null);}
        private Publication(Holder holder,Value next,ReferenceMemory.OwnershipReferenceChange referenceChange,Holder source,ReferenceMemory.OwnershipSourceRemoval removal){
            this(holder,next,referenceChange,source,removal,GlobalOperation.NONE);
        }
        private Publication(Holder holder,Value next,ReferenceMemory.OwnershipReferenceChange referenceChange,Holder source,ReferenceMemory.OwnershipSourceRemoval removal,GlobalOperation operation){
            this.holder=holder;this.tableBorrow=null;this.next=next;this.referenceChange=referenceChange;this.source=source;this.sourceRemoval=removal;before=holder.raw;revision=holder.revision;
            if(holder.carrier!=null&&!holder.matches(holder.carrier))throw new IllegalStateException("Physical address drifted before preparation");
            if(source!=null){source.open();if(source.edge.target()!=next.node)throw new IllegalArgumentException("Transferred producer has a different heap identity");}
            globalPlacement=holder.globalSlot==null||operation==GlobalOperation.NONE?null:holder.globalSlot.prepare(operation==GlobalOperation.STORE);
            try{graph=source!=null?source.edge.prepareTransferTo(holder.edge):holder.edge.preparePublication(next.node);}
            catch(Throwable failure){if(globalPlacement!=null)globalPlacement.close();throw failure;}
        }
        private Publication(GlobalTableBorrow borrow){
            borrow.validateCarrier(borrow.carrier);tableBorrow=borrow;holder=null;next=null;referenceChange=null;source=null;sourceRemoval=null;before=borrow.table;revision=0;graph=null;globalPlacement=null;
        }
        public Holder holder(){if(tableBorrow!=null)throw new IllegalArgumentException("Table borrow has no ownership holder");return holder;}
        public Memory next(){return tableBorrow==null?next.raw:tableBorrow.carrier;}
        public Role role(){return tableBorrow==null?next.role:Role.BORROW_ADDRESS;}
        public void validateStore(Holder expected,ReferenceMemory actual){
            if(tableBorrow!=null)throw new IllegalArgumentException("Native table borrow requires its pure JVM CV store");
            if(finished||stored||holder!=expected||holder.carrier!=actual||holder.revision!=revision||holder.raw!=before)
                throw new IllegalStateException("Publication no longer identifies its actual address");
            if(actual!=null&&actual.getRawValue()!=before)throw new IllegalStateException("Physical payload drifted during publication");
        }
        public boolean isReferencePublication(){return referenceChange!=null;}
        public boolean claimsRuntimeCarrierAttachment(){return attachCarrier;}
        public boolean claimsRuntimeBorrowCarrier(){return borrowCarrier;}
        public Memory consumeRaw(ReferenceMemory target){
            if(referenceChange!=null)throw new IllegalArgumentException("Reference publication must consume its exact Source change");
            validateStore(holder,target);if(globalPlacement!=null)globalPlacement.beginStore();stored=true;return next.raw;
        }
        public Memory consumeReference(ReferenceMemory target,ReferenceMemory.OwnershipReferenceChange change){
            if(change!=referenceChange||change==null)throw new IllegalArgumentException("Publication has another Source change");
            change.validate();validateStore(holder,target);if(globalPlacement!=null)globalPlacement.beginStore();stored=true;return next.raw;
        }
        public void storeRuntimeRemoval(ArrayMemory.RuntimeRemoval token) {
            Objects.requireNonNull(token);token.checkHolder(holder,holder.carrier);
            if(sourceRemoval==null||referenceChange!=null||next.raw!=Memory.UNDEFINED)throw new IllegalArgumentException("Not an exact removal publication");
            holder.carrier.publishRuntimeUnset(this,sourceRemoval);
        }
        public void storeRuntimeInsertion(ArrayMemory.RuntimeInsertion token) {
            Objects.requireNonNull(token);token.checkHolder(holder,holder.carrier);
            if(referenceChange!=null)holder.carrier.publishRuntimeReference(referenceChange,this);
            else holder.carrier.publishRuntimeRaw(this);
        }
        /** Called immediately after a pure external ASTORE; the JVM local itself is the raw publisher. */
        public void storeDirect(){validateStore(holder,null);stored=true;}
        /** Address initialization stores only the borrowed carrier into the JVM CV. */
        public void afterLocalStore(){
            if(tableBorrow!=null){if(stored||finished)throw new IllegalStateException("Table borrow publication is already consumed");tableBorrow.validateCarrier(tableBorrow.carrier);stored=true;return;}
            if(holder.carrier==null)storeDirect();
            else {validateStore(holder,holder.carrier);if(next.raw!=before)throw new IllegalStateException("Borrowed address initialization may not mutate payload");stored=true;}
        }
        public void commit() throws Throwable {
            if(finished||!stored)throw new IllegalStateException("Actual raw publication must precede its ownership commit");
            if(tableBorrow!=null){if(borrowFrame!=null)borrowFrame.locals=nextLocals;finished=true;if(retiredLocal!=null)retiredLocal.close();return;}
            if(globalPlacement!=null)globalPlacement.store();
            holder.raw=next.raw;holder.role=next.role;holder.revision++;
            if(holder.nativeProducer!=null)holder.nativeProducer.publishedAfterRaw();
            if(source!=null){source.raw=Memory.UNDEFINED;source.role=Role.VALUE;source.revision++;if(source.nativeProducer!=null)source.nativeProducer.clearedAfterRaw();}
            if(borrowFrame!=null){borrowFrame.locals=nextLocals;borrowFrame.borrowed=nextBorrowed;borrowFrame.borrowedAddresses=nextBorrowedAddresses;}
            if(closureOriginFrame!=null)closureOriginFrame.closureOrigins=nextClosureOrigins;
            if(objectOriginFrame!=null)objectOriginFrame.objectOrigins=nextObjectOrigins;
            finished=true;
            graph.commit();
            if(retiredLocal!=null)retiredLocal.close();
        }
        @Override public void close() throws Exception {
            if(finished)return;
            if(stored)throw new IllegalStateException("A published raw store must commit immediately");
            finished=true;if(tableBorrow!=null)return;try{graph.close();}finally{if(globalPlacement!=null)globalPlacement.close();}
        }
    }

    /** Literal GLOBALS is a borrowed native table address, never a named slot or PHP ARRAY owner. */
    public static final class GlobalTableBorrow {
        private final Frame frame;
        private final int local;
        private final ReferenceMemory carrier;
        private final ArrayMemory table;
        private GlobalTableBorrow(Frame frame,int local,ReferenceMemory carrier,ArrayMemory table){this.frame=frame;this.local=local;this.carrier=carrier;this.table=table;}
        public Environment environment(){return frame.authority.environment;}
        public Frame frame(){return frame;}
        public int localId(){return local;}
        public ReferenceMemory carrier(){return carrier;}
        public void validateCarrier(ReferenceMemory actual){
            frame.open();
            if(actual!=carrier||frame.globalBorrows.get(local)!=this||carrier.getRawValue()!=table
                    ||frame.authority.environment.getRuntimeGlobalsTable()!=table)
                throw new IllegalArgumentException("Literal GLOBALS does not identify its actual borrowed frame/table address");
        }
        public void rejectMutation(){throw new IllegalArgumentException("Literal GLOBALS is a table borrow, not an assignable VALUE or reference");}
    }
    private record ObjectOrigin(int producer,Holder holder,long revision,php.runtime.reflection.ClassEntity entity) {}
    private record CvOrigin(int producer,String closure,Holder holder,long revision) {}
    public static final class Frame implements AutoCloseable {
        private final Authority authority;
        private Map<Integer,Holder> locals=new LinkedHashMap<>();private final Map<Integer,Holder> evaluations=new LinkedHashMap<>();
        private Set<Holder> borrowed=Collections.newSetFromMap(new IdentityHashMap<>());
        private Map<Integer,ReferenceMemory> borrowedAddresses=new HashMap<>();
        private Map<Integer,CvOrigin> closureOrigins=new HashMap<>();
        private Map<Integer,ObjectOrigin> objectOrigins=new HashMap<>();
        private final Deque<AutoCloseable> cleanupOrder=new ArrayDeque<>();
        private ArrayMemory debugTable;
        private final Map<Integer,GlobalTableBorrow> globalBorrows=new HashMap<>();
        private final Map<Integer,ObjectMemory> lifecycleReceivers=new HashMap<>();
        private boolean closed;
        private Frame(Authority authority){this.authority=authority;}
        public Authority authority(){return authority;}
        private void open(){authority.open();if(closed)throw new IllegalStateException("Runtime frame is closed");}
        private Holder track(Holder holder){cleanupOrder.push(holder);return holder;}
        private Holder root(Map<Integer,Holder> map,int id){return root(map,id,Purpose.OWNER);}
        private Holder root(Map<Integer,Holder> map,int id,Purpose purpose){
            open();Holder existing=map.get(id);
            if(existing!=null){if(existing.purpose!=purpose)throw new IllegalArgumentException("Runtime root purpose cannot change");return existing;}
            Holder holder=track(new Holder(authority,authority.domain.graph().root(),null,purpose));map.put(id,holder);return holder;
        }
        private Holder producerEval(int id){
            open();Holder holder=evaluations.get(id);
            if(holder==null||holder.purpose!=Purpose.PRODUCER_EVAL)throw new IllegalArgumentException("Missing transferable producer EVAL");
            holder.open();return holder;
        }
        private Publication borrowPublication(int id,ReferenceMemory wrapper,Holder physical,Holder retired) {
            Map<Integer,Holder> nextLocals=new LinkedHashMap<>(locals);nextLocals.put(id,physical);
            Set<Holder> nextBorrowed=Collections.newSetFromMap(new IdentityHashMap<>());nextBorrowed.addAll(borrowed);nextBorrowed.add(physical);
            Map<Integer,ReferenceMemory> nextAddresses=new HashMap<>(borrowedAddresses);nextAddresses.put(id,wrapper);
            Publication publication=new Publication(physical,authority.value(physical.raw,physical.role));
            publication.borrowFrame=this;publication.nextLocals=nextLocals;publication.nextBorrowed=nextBorrowed;publication.nextBorrowedAddresses=nextAddresses;publication.retiredLocal=retired;return publication;
        }
        public Memory importLifecycleReceiver(int localId,Memory raw) {
            open();Invocation invocation=authority.invocations.peek();
            if(invocation==null||invocation.frame!=this||!invocation.entered||invocation.lifecycleEntry==null||!invocation.target.runtimePlan.lifecycle()||invocation.target.runtimePlan.lifecycleReceivers().stream().noneMatch(value->value.local()==localId)||raw==null||raw.getClass()!=ObjectMemory.class||((ObjectMemory)raw).value!=invocation.receiver||locals.containsKey(localId)||lifecycleReceivers.containsKey(localId))throw new IllegalArgumentException("Lifecycle self import lacks its exact borrowed origin");
            invocation.lifecycleEntry.validateReceiver(authority.domain,authority.environment,(php.runtime.reflection.MethodEntity)invocation.target.entity,((ObjectMemory)raw).value);
            lifecycleReceivers.put(localId,(ObjectMemory)raw);return raw;
        }
        public ReferenceMemory globalsAddress(int localId) {
            open();Invocation invocation=authority.invocations.peek();
            if(invocation==null||invocation.frame!=this||!invocation.entered||invocation.target.runtimePlan.globalImports().stream().noneMatch(item->item.local()==localId)||invocation.target.plan.locals().stream().noneMatch(l->l.id()==localId&&l.name().equals("GLOBALS")&&l.storage()==PhpOwnershipExecution.Storage.ADDRESS))
                throw new IllegalArgumentException("Literal GLOBALS requires its actual declared generated CV");
            GlobalTableBorrow existing=globalBorrows.get(localId);if(existing!=null){existing.validateCarrier(existing.carrier);return existing.carrier;}
            authority.globalTable();ArrayMemory table=authority.environment.getRuntimeGlobalsTable();ReferenceMemory carrier=new ReferenceMemory(table);
            GlobalTableBorrow borrow=new GlobalTableBorrow(this,localId,carrier,table);globalBorrows.put(localId,borrow);carrier.attachGlobalTableBorrow(borrow);return carrier;
        }
        private Invocation globalRoot(int rootLocalId,Memory actualRoot) {
            open();Invocation invocation=authority.invocations.peek();GlobalTableBorrow borrow=globalBorrows.get(rootLocalId);
            if(invocation==null||invocation.frame!=this||!invocation.entered||authority.currentFrame()!=this||borrow==null||actualRoot!=borrow.carrier)throw new IllegalArgumentException("Named global access needs its actual generated borrowed table CV");
            borrow.validateCarrier(borrow.carrier);return invocation;
        }
        public Memory readGlobal(int siteId,int rootLocalId,Memory actualRoot,String key,boolean cell,php.runtime.env.TraceInfo trace)throws Throwable {
            return readGlobal(siteId,rootLocalId,actualRoot,key,cell,trace,false);
        }
        private Memory readGlobal(int siteId,int rootLocalId,Memory actualRoot,String key,boolean cell,php.runtime.env.TraceInfo trace,boolean quiet)throws Throwable {
            Invocation invocation=globalRoot(rootLocalId,actualRoot);
            if(invocation.target.runtimePlan.quietGlobalReads().contains(siteId)!=quiet)throw new IllegalArgumentException("Named READ changed its actual diagnostic mode");
            GlobalRead site=invocation.target.runtimePlan.globalReads().stream().filter(value->value.id()==siteId&&value.root()==rootLocalId&&value.key().equals(key)&&value.cell()==cell).findFirst().orElseThrow(()->new IllegalArgumentException("Named global READ lacks its actual recorded producer"));
            GlobalSlot slot=authority.globalSlot(ArrayMemory.toKey(php.runtime.memory.StringMemory.valueOf(site.key())));
            boolean missing=!slot.isLinked();Memory raw=cell?slot.reference():missing?Memory.NULL:slot.read().toImmutable();
            try(Publication publication=prepareEval(site.id(),raw,cell?Role.CELL:Role.VALUE)){publication.storeDirect();publication.commit();}
            if(!cell&&missing&&!quiet)authority.environment.warning(trace,"Undefined global variable $%s",site.key());
            return evalHolder(site.id()).raw();
        }
        public void unsetGlobal(int siteId,int rootLocalId,Memory actualRoot,String key)throws Throwable {
            Invocation invocation=globalRoot(rootLocalId,actualRoot);
            GlobalUnset site=invocation.target.runtimePlan.globalUnsets().stream().filter(value->value.id()==siteId&&value.root()==rootLocalId&&value.key().equals(key)).findFirst().orElseThrow(()->new IllegalArgumentException("Named global UNSET lacks its actual recorded placement"));
            authority.globalSlot(ArrayMemory.toKey(php.runtime.memory.StringMemory.valueOf(site.key()))).unset();
        }
        private GlobalAccess globalAccess(int siteId,int rootLocalId,Memory actualRoot) {
            Invocation invocation=globalRoot(rootLocalId,actualRoot);
            return invocation.target.runtimePlan.globalAccesses().stream().filter(item->item.id()==siteId&&item.root()==rootLocalId).findFirst().orElseThrow(()->new IllegalArgumentException("Named global access lacks its exact recorded site"));
        }
        public void requireGlobalsTarget(int siteId,int rootLocalId,Memory actualRoot){globalAccess(siteId,rootLocalId,actualRoot);}
        private Value globalSource(GlobalAccess site,Memory candidate,int sourceEval,int sourceLocal,int mode) {
            if(site.sourceEval()!=sourceEval||site.sourceLocal()!=sourceLocal||site.mode()!=mode)throw new IllegalArgumentException("Named global write differs from its actual source/mode");
            Holder source=sourceEval>=0?producerEval(sourceEval):localHolder(sourceLocal);source.open();
            if(sourceEval>=0)source.requireProducerEvaluation();else if(source.carrier!=null&&!source.matches(source.carrier))throw new IllegalArgumentException("Named global source CV drifted from its publisher");
            Memory semantic=mode==0&&source.role==Role.CELL?((ReferenceMemory)source.raw).getRawValue():source.raw;
            Role wanted=mode==1?Role.CELL:Role.VALUE;if(mode==1&&source.role!=Role.CELL)throw new IllegalArgumentException("Named reference assignment needs its actual exported CELL");
            Value expected=authority.value(semantic,wanted);
            boolean address=sourceLocal>=0&&(candidate==source.carrier||candidate==borrowedAddresses.get(sourceLocal));
            if(address&&candidate==borrowedAddresses.get(sourceLocal)&&((ReferenceMemory)candidate).getRawValue()!=source.carrier)throw new IllegalArgumentException("Named global source borrow no longer identifies its physical address");
            if(!address) {
                Value actual=authority.value(candidate,wanted);
                if(actual.node!=expected.node||actual.node==null&&!semantic.identical(candidate))throw new IllegalArgumentException("Named global source is not its actual current CV/producer value");
            }else if(mode==1&&candidate!=source.raw&&candidate.getClass()!=ReferenceMemory.class&&candidate.getClass()!=php.runtime.memory.support.ArrayMapEntryMemory.class)throw new IllegalArgumentException("Named reference source is not its actual physical carrier");
            return expected;
        }
        /** EVAL transfers retire their mirror before drain; a local copy adds only a destination VALUE edge. */
        public Memory assignGlobal(int siteId,int rootLocalId,Memory actualRoot,Memory key,Memory candidate,int sourceEval,int sourceLocal,int mode)throws Throwable {
            GlobalAccess site=globalAccess(siteId,rootLocalId,actualRoot);
            if(!(key instanceof php.runtime.memory.StringMemory)||key.getClass()!=php.runtime.memory.StringMemory.class||!site.key().equals(key.toString()))throw new IllegalArgumentException("Named global key differs from its exact literal operand");
            Value value=globalSource(site,candidate,sourceEval,sourceLocal,mode);GlobalSlot slot=authority.globalSlot(ArrayMemory.toKey(key));Holder destination=slot.holder();
            Holder source=sourceEval>=0?producerEval(sourceEval):localHolder(sourceLocal);
            if(mode==1) {
                ReferenceMemory cell=(ReferenceMemory)value.raw;var change=destination.carrier.prepareRuntimeReference(cell,false);
                if(change.checkedTarget()!=cell.getRawValue())throw new IllegalArgumentException("Named reference coercion needs its actual payload producer protocol");
                globalAccess(siteId,rootLocalId,actualRoot);globalSource(site,candidate,sourceEval,sourceLocal,mode);
                try(Publication publication=new Publication(destination,value,change,sourceEval>=0?source:null,null,GlobalOperation.STORE)){destination.carrier.publishRuntimeReference(change,publication);publication.commit();return cell;}
            }
            if(destination.role==Role.CELL){CellOrigin origin=((ReferenceMemory)destination.raw).getRuntimeCell();if(origin==null)throw new IllegalArgumentException("Named global CELL requires its original payload publisher");destination=origin.payload;}
            Memory checked=destination.carrier.validateOwnershipAssignment(value.raw.toImmutable());
            globalAccess(siteId,rootLocalId,actualRoot);globalSource(site,candidate,sourceEval,sourceLocal,mode);slot.validate();Value next=authority.value(checked,Role.VALUE);
            if(sourceEval<0) {
                try(Publication publication=new Publication(destination,next,null,null,null,GlobalOperation.STORE)){destination.carrier.publishRuntimeRaw(publication);publication.commit();return checked;}
            }
            if(source.role==Role.VALUE&&source.edge.target()==next.node) {
                try(Publication publication=new Publication(destination,next,null,source,null,GlobalOperation.STORE)){destination.carrier.publishRuntimeRaw(publication);publication.commit();return checked;}
            }
            if(source.role!=Role.CELL)throw new IllegalArgumentException("Named global coercion changed the producer node without its transformation protocol");
            Holder actualDestination=destination;
            authority.domain.graph().deferDestruction(()->{
                try(Publication publication=new Publication(actualDestination,next,null,null,null,GlobalOperation.STORE)){actualDestination.carrier.publishRuntimeRaw(publication);publication.commit();}
                try(Publication release=source.prepare(Memory.UNDEFINED,Role.VALUE)){release.storeDirect();release.commit();}
            });return checked;
        }
        private ReferenceMemory debugAddress(Memory raw,String name) {
            open();Invocation invocation=authority.invocations.peek();
            if(invocation==null||raw.getClass()!=ArrayMemory.class||invocation.target.plan.locals().stream().noneMatch(l->l.name().equals(name)))throw new IllegalArgumentException("Debug address lacks its exact generated local/table witness");
            ArrayMemory table=(ArrayMemory)raw;
            if(invocation.target.plan.entryKind()==PhpOwnershipExecution.EntryKind.MODULE) {
                if(table!=authority.environment.getGlobals())throw new IllegalArgumentException("Module address is not its actual Globals table");
                try{return authority.globalAddress(name);}catch(Throwable failure){return fail(failure);}
            }
            if(debugTable==null){if(table.size()!=0||table.getOwnershipStorage()!=null||table.getRuntimeOrigin()!=null)throw new IllegalArgumentException("Debug local table must be its fresh empty symbol table");debugTable=table;}
            if(debugTable!=table)throw new IllegalArgumentException("Debug local table identity changed");
            ReferenceMemory address=table.getByScalarOrCreate(name);
            if(address.getClass()!=ReferenceMemory.class&&address.getClass()!=php.runtime.memory.support.ArrayMapEntryMemory.class)throw new IllegalArgumentException("Debug local does not have an exact physical carrier");
            return address;
        }
        public Publication prepareLocal(int id,String name,Memory raw,Role role){
            Objects.requireNonNull(name);
            if(raw instanceof ReferenceMemory&&((ReferenceMemory)raw).getGlobalTableBorrow()!=null){
                GlobalTableBorrow borrow=((ReferenceMemory)raw).getGlobalTableBorrow();
                if(role!=Role.BORROW_ADDRESS||!name.equals("GLOBALS")||borrow.frame!=this||borrow.local!=id)throw new IllegalArgumentException("Literal GLOBALS may only import its exact borrowed CV");
                Holder old=locals.get(id);
                if(old!=null&&(old.role!=Role.VALUE||old.edge.target()!=null||!old.raw.isUndefined()&&!old.raw.isNull()||borrowed.contains(old)))throw new IllegalArgumentException("Literal GLOBALS cannot replace a live owned CV");
                Publication publication=new Publication(borrow);
                if(old!=null){Map<Integer,Holder> next=new LinkedHashMap<>(locals);next.remove(id);publication.borrowFrame=this;publication.nextLocals=next;publication.nextBorrowed=borrowed;publication.nextBorrowedAddresses=borrowedAddresses;publication.retiredLocal=old;}
                return publication;
            }
            if(role==Role.BORROW_ADDRESS){
                if(!(raw instanceof ReferenceMemory))throw new IllegalArgumentException("Address local requires its actual carrier");
                ReferenceMemory carrier=(ReferenceMemory)raw;
                Invocation invocation=authority.invocations.peek();
                if(carrier.getClass()!=ReferenceMemory.class&&(carrier.getClass()!=php.runtime.memory.support.ArrayMapEntryMemory.class
                        ||!(debugTable!=null&&debugTable.getByScalar(name)==carrier||invocation!=null&&invocation.target.plan.entryKind()==PhpOwnershipExecution.EntryKind.MODULE&&carrier.getRuntimeHolder()!=null&&carrier.getRuntimeHolder().globalSlot!=null&&carrier.getRuntimeHolder().globalSlot.key().equals(ArrayMemory.toKey(new php.runtime.memory.StringMemory(name))))))throw new IllegalArgumentException("CV requires its exact physical address, not a virtual carrier");
                if(invocation!=null&&invocation.target.plan.entryKind()==PhpOwnershipExecution.EntryKind.MODULE){
                    try {
                        ReferenceMemory global=authority.globalAddress(name);
                        if(global!=carrier)throw new IllegalArgumentException("Module CV is not the actual global address");
                        Holder holder=global.getRuntimeHolder();locals.put(id,holder);borrowed.add(holder);return new Publication(holder,authority.value(holder.raw,holder.role));
                    }catch(Throwable failure){return fail(failure);}
                }
                Holder existing=locals.get(id);
                if(existing!=null&&borrowedAddresses.get(id)==carrier) {
                    if(carrier.getRawValue()!=existing.carrier||!existing.matches(existing.carrier))throw new IllegalArgumentException("Borrowed static CV address drifted");
                    return new Publication(existing,authority.value(existing.raw,existing.role));
                }
                if(existing!=null&&existing.matches(carrier))return new Publication(existing,authority.value(existing.raw,existing.role));
                Holder physical=carrier.getRuntimeHolder();
                Memory pointer=carrier.getRawValue();
                // A static CV can be a plain wrapper around an actual physical static address.
                if(physical==null&&pointer!=null&&pointer.getClass()==ReferenceMemory.class
                        &&((ReferenceMemory)pointer).getRuntimeCell()==null)physical=((ReferenceMemory)pointer).getRuntimeHolder();
                // Literal named-global import may borrow only its exact live table bucket, not arbitrary virtual references.
                if(physical==null&&pointer instanceof ReferenceMemory globalCarrier
                        &&globalCarrier.getClass()==php.runtime.memory.support.ArrayMapEntryMemory.class
                        &&invocation!=null&&invocation.target.runtimePlan.namedGlobals().stream().anyMatch(v->v.local()==id&&v.name().equals(name))) {
                    Holder global=globalCarrier.getRuntimeHolder();
                    if(global!=null&&global.globalSlot!=null&&global.globalSlot.authority==authority
                            &&global.globalSlot.key().equals(ArrayMemory.toKey(new php.runtime.memory.StringMemory(name)))
                            &&global.carrier==globalCarrier&&global.matches(globalCarrier))physical=global;
                }
                if(physical!=null) {
                    physical.open();if(physical.authority.domain!=authority.domain)throw new IllegalArgumentException("Foreign borrowed address");
                    if(carrier.getRuntimeCell()!=null)throw new IllegalArgumentException("Canonical payload cannot be imported as a borrowed CV address");
                    if(existing!=null) {
                        if(existing.role!=Role.VALUE||!existing.raw.isNull()&&!existing.raw.isUndefined()||existing.edge.target()!=null||borrowed.contains(existing)
                                ||invocation==null||!(invocation.receiver instanceof php.runtime.lang.Closure
                                &&physical.edge.owner()==((php.runtime.lang.Closure)invocation.receiver).ownershipClosure().staticStorage().node()
                                ||invocation.target.runtimePlan.namedGlobals().stream().anyMatch(v->v.local()==id&&v.name().equals(name))
                                &&physical.globalSlot!=null&&physical.globalSlot.authority==authority&&physical.globalSlot.key().equals(ArrayMemory.toKey(new php.runtime.memory.StringMemory(name)))&&pointer==physical.carrier))
                            throw new IllegalArgumentException("Static CV replacement lacks its exact empty address and physical table witness");
                        return borrowPublication(id,carrier,physical,existing);
                    }
                    return borrowPublication(id,carrier,physical,null);
                }
                if(existing!=null)throw new IllegalArgumentException("Local address changed without its publisher");
                if(carrier.getOwnershipEntry()!=null)throw new IllegalArgumentException("Certified physical address needs its same-publisher adoption");
                Holder holder=track(new Holder(authority,authority.domain.graph().root(),carrier));holder.raw=carrier.getRawValue();
                if(holder.raw instanceof ReferenceMemory||!(holder.raw.isNull()||holder.raw.isUndefined())) {
                    var local=invocation==null?null:invocation.target.plan.locals().stream().filter(item->item.id()==id).findFirst().orElse(null);
                    if(local==null||local.importKind()!=PhpOwnershipExecution.ImportKind.CAPTURE||local.importMode()!=PhpOwnershipExecution.Mode.CELL
                            ||!(invocation.receiver instanceof php.runtime.lang.Closure))throw new IllegalArgumentException("Nonempty CV requires its exact CELL capture import");
                    var binding=((php.runtime.lang.Closure)invocation.receiver).ownershipClosure().captureBindings().get(local.importIndex());
                    if(binding.role()!=PhpOwnershipValues.Role.CELL||holder.raw!=binding.rawValue())throw new IllegalArgumentException("Capture CV does not identify the actual canonical CELL");
                    Value value=authority.value(holder.raw,Role.CELL);locals.put(id,holder);carrier.attachRuntimeHolder(holder);
                    return new Publication(holder,value);
                }
                locals.put(id,holder);carrier.attachRuntimeHolder(holder);return holder.prepare(authority.value(holder.raw,Role.VALUE));
            }
            if(raw==null)raw=Memory.NULL;
            Holder destination=root(locals,id);Invocation invocation=authority.invocations.peek();
            var local=invocation==null?null:invocation.target.plan.locals().stream().filter(item->item.id()==id).findFirst().orElse(null);
            if(local!=null&&local.importKind()==PhpOwnershipExecution.ImportKind.ARGUMENT) {
                Holder send=invocation.argument(local.importIndex());
                if(send!=null&&send.raw!=Memory.UNDEFINED) {
                    Value candidate=authority.value(raw,role);
                    if(send.edge.target()==candidate.node&&(candidate.node!=null||send.raw.identical(candidate.raw)))
                        return new Publication(destination,candidate,null,send);
                }
            }
            return destination.prepare(raw,role);
        }
        public Publication prepareLocal(int id,String name,Memory raw,int role){return prepareLocal(id,name,raw,Role.values()[role]);}
        private ReferenceMemory returnDebugAddress(php.runtime.memory.support.ArrayMapEntryMemory carrier) {
            open();if(debugTable==null||debugTable.getByScalar(carrier.getKey())!=carrier)throw new IllegalArgumentException("Reference return does not identify its actual debug table carrier");
            for(var local:locals.entrySet()) {
                Holder holder=local.getValue();
                if(borrowedAddresses.get(local.getKey())==carrier&&carrier.getRawValue()==holder.carrier) {
                    Invocation invocation=authority.invocations.peek();
                    if(invocation==null||!(invocation.receiver instanceof php.runtime.lang.Closure)||holder.edge.owner()!=((php.runtime.lang.Closure)invocation.receiver).ownershipClosure().staticStorage().node())throw new IllegalArgumentException("Returned static address has no actual table witness");
                    return holder.reference();
                }
                if(holder.carrier==carrier&&holder.matches(carrier)&&!borrowed.contains(holder))return holder.reference();
            }
            throw new IllegalArgumentException("Reference return has no actual CV publisher");
        }
        /** A static CV is an address borrow, not another owner of the static value or CELL. */
        public Memory bindStaticLocal(int id,String name,Memory actualCv,Memory actualStatic) {
            open();Invocation invocation=authority.invocations.peek();
            if(invocation==null||!(invocation.receiver instanceof php.runtime.lang.Closure)||!(actualCv instanceof ReferenceMemory)||!(actualStatic instanceof ReferenceMemory))throw new IllegalArgumentException("Static binding needs its actual lexical invocation and addresses");
            var table=((php.runtime.lang.Closure)invocation.receiver).ownershipClosure().staticStorage();
            if(table.get(name)!=actualStatic)throw new IllegalArgumentException("Static binding does not identify the actual table slot");
            Holder physical=((ReferenceMemory)actualStatic).getRuntimeHolder(),existing=localHolder(id);ReferenceMemory carrier=(ReferenceMemory)actualCv;
            if(carrier.getClass()!=ReferenceMemory.class&&(debugTable==null||debugTable.getByScalar(name)!=carrier||carrier.getClass()!=php.runtime.memory.support.ArrayMapEntryMemory.class))throw new IllegalArgumentException("Static CV is not its actual physical debug carrier");
            if(physical==null||physical.edge.owner()!=table.node()||physical.authority.domain!=authority.domain)throw new IllegalArgumentException("Static binding lacks its actual physical publisher");
            if(borrowedAddresses.get(id)==carrier&&existing==physical&&carrier.getRawValue()==actualStatic)return actualStatic;
            if(!existing.matches(carrier)||existing.carrier.getRuntimeCell()!=null||borrowed.contains(existing))throw new IllegalArgumentException("Static binding cannot replace an unknown borrowed address");
            Map<Integer,Holder> nextLocals=new LinkedHashMap<>(locals);nextLocals.put(id,physical);
            Set<Holder> nextBorrowed=Collections.newSetFromMap(new IdentityHashMap<>());nextBorrowed.addAll(borrowed);nextBorrowed.add(physical);
            Map<Integer,ReferenceMemory> nextAddresses=new HashMap<>(borrowedAddresses);nextAddresses.put(id,carrier);
            Value borrow=new Value(authority,actualStatic,Role.BORROW_ADDRESS,null);
            try(Publication publication=new Publication(existing,borrow)) {
                publication.borrowFrame=this;publication.nextLocals=nextLocals;publication.nextBorrowed=nextBorrowed;publication.nextBorrowedAddresses=nextAddresses;publication.retiredLocal=existing;publication.borrowCarrier=true;
                carrier.publishRuntimeRaw(publication);publication.commit();return actualStatic;
            }catch(Throwable failure){return fail(failure);}
        }
        public void unsetLocal(int id,Memory actualCv) {
            open();Holder current=localHolder(id);ReferenceMemory wrapper=borrowedAddresses.get(id);
            if(wrapper!=null) {
                if(actualCv!=wrapper||wrapper.getRawValue()!=current.carrier||wrapper.getRuntimeHolder()!=null||wrapper.getOwnershipEntry()!=null||wrapper.getRuntimeCell()!=null
                        ||wrapper.getClass()!=ReferenceMemory.class&&(wrapper.getClass()!=php.runtime.memory.support.ArrayMapEntryMemory.class||debugTable==null||debugTable.getByScalar(((php.runtime.memory.support.ArrayMapEntryMemory)wrapper).getKey())!=wrapper))
                    throw new IllegalArgumentException("Unset does not identify its exact borrowed static CV");
                Invocation invocation=authority.invocations.peek();
                if(invocation==null||!(invocation.receiver instanceof php.runtime.lang.Closure)||current.edge.owner()!=((php.runtime.lang.Closure)invocation.receiver).ownershipClosure().staticStorage().node())
                    throw new IllegalArgumentException("Borrowed unset has no actual lexical static table");
                Holder owned=track(new Holder(authority,authority.domain.graph().root(),wrapper));owned.raw=wrapper.getRawValue();owned.role=Role.BORROW_ADDRESS;
                Map<Integer,Holder> nextLocals=new LinkedHashMap<>(locals);nextLocals.put(id,owned);
                Map<Integer,ReferenceMemory> nextAddresses=new HashMap<>(borrowedAddresses);nextAddresses.remove(id);
                Set<Holder> nextBorrowed=Collections.newSetFromMap(new IdentityHashMap<>());nextBorrowed.addAll(borrowed);
                try(Publication publication=new Publication(owned,authority.value(Memory.UNDEFINED,Role.VALUE))) {
                    publication.attachCarrier=true;publication.borrowFrame=this;publication.nextLocals=nextLocals;publication.nextBorrowed=nextBorrowed;publication.nextBorrowedAddresses=nextAddresses;
                    wrapper.publishRuntimeRaw(publication);publication.commit();
                }catch(Throwable failure){fail(failure);}return;
            }
            if(actualCv!=current.carrier||!current.matches(current.carrier))throw new IllegalArgumentException("Unset needs its exact owning or GLOBAL CV carrier");
            current.unset();
        }
        public Memory assignLocal(int id,Memory raw,int mode) {
            Holder holder=localHolder(id);if(!holder.hasPhysicalCarrier())throw new IllegalArgumentException("Address assignment needs its actual carrier");
            ReferenceMemory wrapper=borrowedAddresses.get(id);
            if(mode==1&&wrapper!=null) {
                if(wrapper.getClass()!=ReferenceMemory.class&&(wrapper.getClass()!=php.runtime.memory.support.ArrayMapEntryMemory.class||debugTable==null||debugTable.getByScalar(((php.runtime.memory.support.ArrayMapEntryMemory)wrapper).getKey())!=wrapper)||wrapper.getRuntimeHolder()!=null||wrapper.getOwnershipEntry()!=null||wrapper.getRuntimeCell()!=null||wrapper.getRawValue()!=holder.carrier)throw new IllegalArgumentException("Static CV detach lacks its exact borrowed address");
                Value candidate=authority.value(raw,Role.CELL);var change=wrapper.prepareRuntimeReference((ReferenceMemory)raw,false);
                if(change.checkedTarget()!=((ReferenceMemory)raw).getRawValue())throw new IllegalArgumentException("Static CV detach cannot perform an unstaged coercion");
                Holder owned=track(new Holder(authority,authority.domain.graph().root(),wrapper));owned.raw=wrapper.getRawValue();owned.role=Role.BORROW_ADDRESS;
                Map<Integer,Holder> nextLocals=new LinkedHashMap<>(locals);nextLocals.put(id,owned);
                Set<Holder> nextBorrowed=Collections.newSetFromMap(new IdentityHashMap<>());nextBorrowed.addAll(borrowed);
                Map<Integer,ReferenceMemory> nextAddresses=new HashMap<>(borrowedAddresses);nextAddresses.remove(id);
                try(Publication publication=new Publication(owned,candidate,change)) {
                    publication.borrowFrame=this;publication.nextLocals=nextLocals;publication.nextBorrowed=nextBorrowed;publication.nextBorrowedAddresses=nextAddresses;publication.attachCarrier=true;
                    wrapper.publishRuntimeReference(change,publication);publication.commit();return raw;
                }catch(Throwable failure){return fail(failure);}
            }
            if(mode==1)return holder.rebind((ReferenceMemory)raw);
            if(mode==0)return holder.assign(raw.toImmutable());throw new IllegalArgumentException("Unknown local assignment mode");
        }
        public Memory importArgument(int argument,int id,Memory raw,int mode) {
            if(raw==null)return localHolder(id).carrier;
            Invocation invocation=authority.invocations.peek();
            if(mode==1) {
                if(invocation==null||invocation.frame!=this||invocation.functionPacket==null||argument!=0)throw new IllegalArgumentException("Reference argument lacks its actual FUNCTION SEND packet");
                Holder source=invocation.argument(argument),destination=localHolder(id);
                if(source==null||source.role!=Role.CELL||source.raw!=raw||!destination.hasPhysicalCarrier()||destination.role!=Role.VALUE||(!destination.raw.isUndefined()&&!destination.raw.isNull()))throw new IllegalArgumentException("Reference import differs from its actual SEND or empty CV");
                ReferenceMemory cell=(ReferenceMemory)raw;authority.value(cell,Role.CELL);var change=cell.getRuntimeCell()!=null?destination.carrier.prepareRuntimeReference(cell,false):destination.carrier.prepareOwnershipReference(cell,false);
                if(change.checkedTarget()!=cell.getRawValue())throw new IllegalArgumentException("Reference parameter coercion needs its payload protocol");
                try(Publication publication=new Publication(destination,authority.value(cell,Role.CELL),change,source)){destination.carrier.publishRuntimeReference(change,publication);publication.commit();return destination.carrier;}
                catch(Throwable failure){return fail(failure);}
            }
            if(mode!=0)throw new IllegalArgumentException("Unknown argument import mode");
            if(invocation==null||argument<0||invocation.argument(argument)==null)throw new IllegalArgumentException("Argument lacks its actual SEND root");
            Holder destination=localHolder(id),source=invocation.argument(argument);
            if(!destination.hasPhysicalCarrier()||destination.role!=Role.VALUE)throw new IllegalArgumentException("Argument import needs its empty VALUE address");
            Memory checked=destination.carrier.validateOwnershipAssignment(raw.toImmutable());
            try(Publication publication=new Publication(destination,authority.value(checked,Role.VALUE),null,source)) {
                destination.carrier.publishRuntimeRaw(publication);publication.commit();return destination.carrier;
            }catch(Throwable failure){return fail(failure);}
        }
        public Memory importCapture(int capture,int id,Memory raw,int mode) {
            Invocation invocation=authority.invocations.peek();
            if(invocation==null||!(invocation.receiver instanceof php.runtime.lang.Closure))throw new IllegalArgumentException("Capture lacks its actual receiver root");
            var bindings=((php.runtime.lang.Closure)invocation.receiver).ownershipClosure().captureBindings();
            if(capture<0||capture>=bindings.size())throw new IllegalArgumentException("Unknown capture import");
            var expected=bindings.get(capture);Value actual=authority.value(mode==1?raw:raw.toImmutable(),mode==1?Role.CELL:Role.VALUE);
            if(expected.node()!=actual.node||(expected.role()==PhpOwnershipValues.Role.CELL)!=(mode==1)
                    ||expected.node()==null&&!expected.rawValue().identical(actual.raw))throw new IllegalArgumentException("Capture differs from its actual holder");
            return assignLocal(id,raw,mode);
        }
        /** Pure preflight, before warnings, initialization or COW can mutate a destination. */
        public void requireArrayValue(int evalId,Memory candidate) {
            open();Holder source=producerEval(evalId);source.requireProducerEvaluation();
            if(source.role!=Role.VALUE||candidate instanceof ReferenceMemory)throw new IllegalArgumentException("ARRAY entry needs its actual VALUE producer");
            Value actual=authority.value(candidate,Role.VALUE);
            if(actual.node!=source.edge.target()||actual.node==null&&!source.raw.identical(candidate))throw new IllegalArgumentException("ARRAY entry differs from its current producer");
        }
        public Publication prepareArrayInsertion(ArrayMemory.RuntimeInsertion token,Memory candidate,int evalId) {
            requireArrayValue(evalId,candidate);Holder destination=token.holder();destination.open();
            token.checkHolder(destination,token.carrier());
            if(destination.domain()!=authority.domain||destination.facet!=null||destination.role!=Role.VALUE||!destination.raw.isNull())throw new IllegalArgumentException("Insertion needs its exact empty physical publisher");
            Memory checked=token.carrier().validateOwnershipAssignment(candidate);token.checkHolder(destination,token.carrier());
            requireArrayValue(evalId,candidate);Value next=authority.value(checked,Role.VALUE);
            if(next.node!=producerEval(evalId).edge.target())throw new IllegalArgumentException("Entry coercion requires an explicit different-node protocol");
            return new Publication(destination,next,null,producerEval(evalId));
        }
        public Publication prepareArrayValue(ArrayMemory.RuntimeValueAssignment token,Memory candidate,int evalId) {
            requireArrayValue(evalId,candidate);Holder destination=token.holder();destination.open();
            token.checkHolder(destination,token.carrier());
            if(destination.domain()!=authority.domain||destination.facet!=null||destination.role!=Role.VALUE)throw new IllegalArgumentException("Entry assignment needs its exact VALUE payload publisher");
            Memory checked=token.carrier().validateOwnershipAssignment(candidate);token.checkHolder(destination,token.carrier());
            requireArrayValue(evalId,candidate);Value next=authority.value(checked,Role.VALUE);
            if(next.node!=producerEval(evalId).edge.target())throw new IllegalArgumentException("Entry coercion requires an explicit different-node protocol");
            return new Publication(destination,next,null,producerEval(evalId));
        }
        public void requireArrayTarget(int siteId,int rootLocalId,Memory actualRoot) {
            open();Invocation invocation=authority.invocations.peek();
            if(invocation==null||invocation.frame!=this||!invocation.entered)throw new IllegalArgumentException("ARRAY target needs its exact generated invocation");
            ArrayEntrySite site=invocation.target.runtimePlan.arrayEntries().stream().filter(item->item.id()==siteId&&item.local()==rootLocalId).findFirst().orElseThrow(()->new IllegalArgumentException("ARRAY target has no actual site/local proof"));
            Holder local=localHolder(rootLocalId);ReferenceMemory borrowedAddress=borrowedAddresses.get(rootLocalId);
            if(!(actualRoot instanceof ReferenceMemory)||actualRoot!=local.carrier&&actualRoot!=borrowedAddress)throw new IllegalArgumentException("ARRAY target differs from its actual CV address");
            if(local.carrier!=null&&!local.matches(local.carrier))throw new IllegalArgumentException("ARRAY target CV drifted from its publisher");
        }
        /** A borrowed CV writes through its validated physical carrier, including named-global COW. */
        public ArrayMemory writableArrayTarget(int siteId,int rootLocalId,Memory actualRoot) {
            requireArrayTarget(siteId,rootLocalId,actualRoot);
            Holder writer=arrayValueHolder(rootLocalId,actualRoot);
            ArrayMemory writable=PhpOwnershipArrays.runtimeWritable(writer);
            // Publication/temporary retirement can drain callbacks; retain the actual site and writer proof afterwards.
            requireArrayTarget(siteId,rootLocalId,actualRoot);
            Holder current=arrayValueHolder(rootLocalId,actualRoot);
            if(current!=writer||current.raw()!=writable)
                throw new IllegalArgumentException("ARRAY writer changed during its physical COW publication");
            return writable;
        }
        /** A canceled diagnostic write discards only its attested nonreturned ENTRY producer. */
        public void cancelArrayEntry(int siteId,int rootLocalId,Memory actualRoot,int evalId,Memory candidate)throws Exception {
            requireArrayTarget(siteId,rootLocalId,actualRoot);
            Invocation invocation=authority.invocations.peek();
            ArrayEntrySite site=invocation.target.runtimePlan.arrayEntries().stream().filter(item->item.id()==siteId&&item.local()==rootLocalId).findFirst().orElseThrow();
            if(site.producer()!=evalId)throw new IllegalArgumentException("Canceled ARRAY entry differs from its actual producer site");
            requireArrayValue(evalId,candidate);releaseEval(evalId);
        }
        /** Consumes a nonreturned ADDRESS assignment's actual result producer. */
        /** Resolve an actual ADDRESS CV to its one ARRAY VALUE edge, including a certified canonical payload. */
        /** Actual literal object allocation, rooted before its first owning consumer. */
        public Memory objectNew(int siteId,String literalClass)throws Throwable {
            open();Invocation invocation=authority.invocations.peek();
            if(invocation==null||invocation.frame!=this||authority.currentFrame()!=this)throw new IllegalStateException("Object NEW requires its exact generated invocation");
            ObjectNew site=invocation.target.runtimePlan.objectNews().stream().filter(value->value.id()==siteId&&value.name().equals(literalClass)).findFirst().orElseThrow(()->new IllegalArgumentException("Object NEW lacks its exact loaded-shell producer site"));
            var plan=authority.literalObjectPlan(invocation.target,site);
            if(authority.environment.fetchClass(literalClass,false)!=plan.entity())throw new IllegalArgumentException("Object NEW declaration has not been executed");
            var objects=authority.objects();var provider=plan.initializeAndRegister(objects);
            try(var allocation=objects.allocate(provider,authority.environment,plan.certifiedDefaults(objects))) {
                Memory result=stageAllocation(siteId,allocation);authority.collectGarbageIfNeeded();return result;
            }
        }
        private Memory propertyReference(int siteId,int rootLocalId,Memory actualRoot,String key)throws Throwable {
            open();Invocation invocation=authority.invocations.peek();
            if(invocation==null||invocation.frame!=this||authority.currentFrame()!=this)throw new IllegalStateException("FIELD reference needs its exact generated invocation");
            PropertyReference site=invocation.target.runtimePlan.propertyReferences().stream().filter(value->value.id()==siteId&&value.root()==rootLocalId&&value.key().equals(key)).findFirst().orElseThrow(()->new IllegalArgumentException("FIELD reference lacks its actual byte-bound receiver/property site"));
            var property=authority.literalProperty(invocation.target,site);Holder holder=localHolder(rootLocalId);ObjectOrigin origin=objectOrigins.get(rootLocalId);
            if(origin==null||origin.producer()!=site.producer()||origin.holder()!=holder||origin.revision()!=holder.revision||holder.role!=Role.VALUE||actualRoot!=holder.carrier||!holder.matches(holder.carrier)||!(holder.raw instanceof ObjectMemory))throw new IllegalArgumentException("FIELD receiver differs from its actual NEW/CV publication");
            var object=((ObjectMemory)holder.raw).value;
            if(object.getReflection()!=origin.entity()||!(object instanceof php.runtime.lang.BaseObject))throw new IllegalArgumentException("FIELD receiver changed its certified class");
            var certificate=((php.runtime.lang.BaseObject)object).getOwnershipObject();
            if(certificate==null)throw new IllegalArgumentException("FIELD receiver lacks its actual OBJECT/TABLE origin");certificate.validateBinding(authority.domain,holder.edge.target(),object);
            var properties=certificate.properties();ReferenceMemory carrier=properties.table().getByScalar(property.getSpecificName());
            if(carrier==null||carrier.getRawValue().isUndefined())throw new IllegalArgumentException("FIELD reference has no initialized exact physical address");
            ReferenceMemory cell=authority.adopt(properties.entry(carrier).ownerPublisher().runtimeFacet()).reference();return stageArrayReferenceResult(siteId,cell);
        }
        public void validateLocalIncrement(int siteId,int rootLocalId,Memory actualRoot,boolean decrement,boolean post) {
            open();Invocation invocation=authority.invocations.peek();
            if(invocation==null||invocation.frame!=this||!invocation.entered||authority.currentFrame()!=this)throw new IllegalStateException("Local increment needs its exact entered generated invocation");
            invocation.target.runtimePlan.localIncrements().stream().filter(site->site.id()==siteId&&site.root()==rootLocalId&&site.decrement()==decrement&&site.post()==post).findFirst().orElseThrow(()->new IllegalArgumentException("Local increment lacks its byte-bound CV/opcode site"));
            Holder holder=localHolder(rootLocalId);ReferenceMemory borrowed=borrowedAddresses.get(rootLocalId);
            if(!holder.hasPhysicalCarrier()||actualRoot!=holder.carrier&&actualRoot!=borrowed||!holder.matches(holder.carrier)||!(actualRoot instanceof ReferenceMemory))throw new IllegalArgumentException("Local increment changed its actual ADDRESS CV publisher");
        }
        public Holder arrayIncrementHolder(int siteId,int rootLocalId,Memory actualRoot,String key,boolean decrement,boolean post) {
            open();Invocation invocation=authority.invocations.peek();
            if(invocation==null||invocation.frame!=this||authority.currentFrame()!=this)throw new IllegalStateException("ARRAY increment needs its exact generated invocation");
            invocation.target.runtimePlan.arrayIncrements().stream().filter(site->site.id()==siteId&&site.root()==rootLocalId&&site.key().equals(key)&&site.decrement()==decrement&&site.post()==post).findFirst().orElseThrow(()->new IllegalArgumentException("ARRAY increment lacks its byte-bound CV/key/opcode site"));return arrayValueHolder(rootLocalId,actualRoot);
        }
        public Holder arrayReferenceHolder(int siteId,int rootLocalId,Memory actualRoot,String key) {
            open();Invocation invocation=authority.invocations.peek();
            if(invocation==null||invocation.frame!=this||authority.currentFrame()!=this)throw new IllegalStateException("ARRAY reference needs its exact generated invocation");
            invocation.target.runtimePlan.arrayReferences().stream().filter(site->site.id()==siteId&&site.root()==rootLocalId&&site.key().equals(key)).findFirst().orElseThrow(()->new IllegalArgumentException("ARRAY reference lacks its byte-bound CV/key site"));
            return arrayValueHolder(rootLocalId,actualRoot);
        }
        public Holder arrayValueHolder(int localId,Memory actualRoot) {
            open();Holder holder=localHolder(localId);ReferenceMemory wrapper=borrowedAddresses.get(localId);
            if(!holder.hasPhysicalCarrier()||actualRoot!=holder.carrier&&actualRoot!=wrapper||!holder.matches(holder.carrier))
                throw new IllegalArgumentException("ARRAY write needs its exact current ADDRESS CV");
            if(actualRoot==wrapper&&wrapper.getRawValue()!=holder.carrier)throw new IllegalArgumentException("ARRAY borrowed CV address drifted from its actual publisher");
            if(holder.role==Role.CELL) {
                ReferenceMemory cell=(ReferenceMemory)holder.raw;authority.value(cell,Role.CELL);
                CellOrigin runtime=cell.getRuntimeCell();
                if(runtime!=null)holder=runtime.payload();
                else {
                    var certificate=authority.arrays().certificate(cell);
                    if(!(certificate.value() instanceof PhpOwnershipArrays.Entry))throw new IllegalArgumentException("Certified CELL lacks its actual payload publisher");
                    holder=authority.adopt(((PhpOwnershipArrays.Entry)certificate.value()).runtimeFacet());
                }
            }
            holder.open();if(holder.role!=Role.VALUE||!(holder.raw instanceof ArrayMemory)||!holder.hasPhysicalCarrier())throw new IllegalArgumentException("ARRAY write needs the actual physical VALUE payload");
            authority.value(holder.raw,Role.VALUE);return holder;
        }
        /** Move an actual separated COW producer into its original physical writer before callback drain. */
        public void publishArrayCow(Holder writer,PhpOwnershipArrays.ArrayWrite write,int evalId)throws Throwable {
            open();if(authority.currentFrame()!=this||writer.domain()!=authority.domain)throw new IllegalArgumentException("COW publication needs its actual current frame");
            write.requireOwner(writer.borrowValuePublisher());if(!write.separated())return;
            Holder source=producerEval(evalId);source.requireProducerEvaluation();var binding=write.binding();
            if(source.role!=Role.VALUE||source.raw!=binding.rawValue()||source.edge.target()!=binding.node())throw new IllegalArgumentException("COW publication differs from its staged allocation");
            if(writer.facet!=null) {
                var carrier=writer.facet.carrier();var before=writer.facet.current();
                try(var publication=writer.prepareFacetValue(before,binding,source)){carrier.publishOwnershipRaw(publication);publication.commit();}
            }else {
                if(!writer.hasPhysicalCarrier()||writer.role!=Role.VALUE)throw new IllegalArgumentException("COW carrier publication cannot replace a JVM CV mirror");
                Memory checked=writer.carrier.validateOwnershipAssignment(binding.rawValue());if(checked!=binding.rawValue())throw new IllegalArgumentException("COW coercion changed the actual ARRAY allocation");
                try(Publication publication=new Publication(writer,authority.value(checked,Role.VALUE),null,source,null,GlobalOperation.STORE)){writer.carrier.publishRuntimeRaw(publication);publication.commit();}
            }
        }
        public Memory stageArrayReferenceResult(int evalId,ReferenceMemory canonical)throws Throwable {
            open();authority.value(canonical,Role.CELL);
            try(Publication publication=prepareEval(evalId,canonical,Role.CELL)){publication.storeDirect();publication.commit();}return canonical;
        }
        public Memory assignLocalFromEval(int evalId,int localId,Memory actualRaw,int mode)throws Throwable {
            open();Holder source=producerEval(evalId),destination=localHolder(localId);
            if(destination.facet!=null||!destination.hasPhysicalCarrier())throw new IllegalArgumentException("Result assignment needs its actual runtime CV publisher");
            Memory sourceRaw=source.raw;
            Memory expected=mode==0&&source.role==Role.CELL?((ReferenceMemory)sourceRaw).getRawValue():sourceRaw;
            Value expectedValue=authority.value(expected,mode==1?Role.CELL:Role.VALUE),actualValue=authority.value(actualRaw,mode==1?Role.CELL:Role.VALUE);
            if(expectedValue.node!=actualValue.node||expectedValue.node==null&&!expected.identical(actualRaw))throw new IllegalArgumentException("Assignment lost its actual producer read");
            if(mode==1) {
                if(source.role!=Role.CELL||destination.carrier.getRuntimeCell()!=null||borrowedAddresses.containsKey(localId))throw new IllegalArgumentException("Reference result needs an owning CV and exact CELL producer");
                ReferenceMemory cell=(ReferenceMemory)sourceRaw;var change=cell.getRuntimeCell()!=null?destination.carrier.prepareRuntimeReference(cell,false):destination.carrier.prepareOwnershipReference(cell,false);
                if(change.checkedTarget()!=cell.getRawValue())throw new IllegalArgumentException("Reference result coercion needs its payload publication protocol");
                try(Publication publication=new Publication(destination,authority.value(cell,Role.CELL),change,source,null,GlobalOperation.STORE)){destination.carrier.publishRuntimeReference(change,publication);publication.commit();return cell;}
            }
            if(mode!=0)throw new IllegalArgumentException("Unknown result assignment mode");
            Memory value=source.role==Role.CELL?((ReferenceMemory)sourceRaw).getRawValue().toImmutable():sourceRaw.toImmutable();
            if(destination.role==Role.CELL) {
                CellOrigin origin=((ReferenceMemory)destination.raw).getRuntimeCell();
                if(origin==null)throw new IllegalArgumentException("Certified payload needs its same-publisher result transfer");destination=origin.payload;
            }
            Memory checked=destination.carrier.validateOwnershipAssignment(value);Value next=authority.value(checked,Role.VALUE);
            Map<Integer,CvOrigin> origins=closureOriginPublication(evalId,localId,destination,next);
            Map<Integer,ObjectOrigin> objectSources=objectOriginPublication(evalId,localId,destination,next);
            if(source.role==Role.VALUE&&source.edge.target()==next.node) {
                try(Publication publication=new Publication(destination,next,null,source,null,GlobalOperation.STORE)){if(origins!=null){publication.closureOriginFrame=this;publication.nextClosureOrigins=origins;}if(objectSources!=null){publication.objectOriginFrame=this;publication.nextObjectOrigins=objectSources;}destination.carrier.publishRuntimeRaw(publication);publication.commit();return checked;}
            }
            // CELL result and payload VALUE have distinct nodes: publish payload custody before dropping CELL custody.
            Holder actualDestination=destination;
            authority.domain.graph().deferDestruction(()->{
                try(Publication publication=new Publication(actualDestination,next,null,null,null,GlobalOperation.STORE)){actualDestination.carrier.publishRuntimeRaw(publication);publication.commit();}
                try(Publication release=source.prepare(Memory.UNDEFINED,Role.VALUE)){release.storeDirect();release.commit();}
            });return checked;
        }
        private Map<Integer,ObjectOrigin> objectOriginPublication(int producer,int local,Holder destination,Value value) {
            Invocation invocation=authority.invocations.peek();if(invocation==null||invocation.frame!=this)return null;
            ObjectCv site=invocation.target.runtimePlan.objectCvs().stream().filter(item->item.producer()==producer&&item.local()==local).findFirst().orElse(null);if(site==null)return null;
            ObjectNew allocation=invocation.target.runtimePlan.objectNews().stream().filter(item->item.id()==producer&&item.owner().equals(site.owner())).findFirst().orElseThrow();var plan=authority.literalObjectPlan(invocation.target,allocation);
            if(value.role!=Role.VALUE||!(value.raw instanceof ObjectMemory)||((ObjectMemory)value.raw).value.getReflection()!=plan.entity())throw new IllegalArgumentException("Object CV differs from its attested NEW allocation");
            Map<Integer,ObjectOrigin> next=new HashMap<>(objectOrigins);next.put(local,new ObjectOrigin(producer,destination,destination.revision+1,plan.entity()));return next;
        }
        private Map<Integer,CvOrigin> closureOriginPublication(int producer,int local,Holder destination,Value value) {
            Invocation invocation=authority.invocations.peek();if(invocation==null||invocation.frame!=this)return null;
            ClosureCv site=invocation.target.runtimePlan.closureCvs().stream().filter(item->item.producer()==producer&&item.local()==local).findFirst().orElse(null);if(site==null)return null;
            if(value.role!=Role.VALUE||!(value.raw instanceof ObjectMemory)||!((ObjectMemory)value.raw).value.getClass().getName().replace('.','/').equals(site.closure()))throw new IllegalArgumentException("Closure CV publication differs from its compiler source class");
            Map<Integer,CvOrigin> next=new HashMap<>(closureOrigins);next.put(local,new CvOrigin(producer,site.closure(),destination,destination.revision+1));return next;
        }
        public Publication prepareLocalFromEval(int evalId,int localId,String name,Memory actualRaw,Role role){
            open();Holder source=producerEval(evalId);
            Value next=authority.value(actualRaw,role);Holder destination=root(locals,localId);
            if(destination.carrier!=null)throw new IllegalArgumentException("Address transfer needs its physical publication permit");
            return new Publication(destination,next,null,source);
        }
        public Publication prepareArrayWrite(int localId)throws Throwable{
            open();Holder writer=localHolder(localId);ArrayMemory candidate=PhpOwnershipArrays.runtimeWriteCandidate(writer);
            if(candidate==writer.raw)return writer.prepare(candidate,Role.VALUE);
            int eval=diagnosticId++;
            var origin=candidate.prepareRuntimeOrigin(authority);
            try(Publication stage=prepareEval(eval,candidate,Role.VALUE)){stage.storeDirect();stage.commit();}
            candidate.initializeRuntimeEntries(origin,evalHolder(eval));
            return prepareLocalFromEval(eval,localId,"array-write",candidate,Role.VALUE);
        }
        /** Output accepts semantic scalars or a witnessed local/result read; it never discovers virtual lvalues. */
        public Memory scalarOutput(Memory operand) {
            open();if(authority.currentFrame()!=this)throw new IllegalArgumentException("Output needs its actual executing frame");Memory semantic=operand;
            if(operand instanceof ReferenceMemory) {
                boolean witnessed=false;
                for(var entry:locals.entrySet()) {
                    Holder holder=entry.getValue();
                    if(operand==holder.carrier||operand==borrowedAddresses.get(entry.getKey())) {
                        if(!holder.matches(holder.carrier))throw new IllegalArgumentException("Output CV address drifted");
                        semantic=holder.role==Role.CELL?((ReferenceMemory)holder.raw).getRawValue():holder.raw;witnessed=true;break;
                    }
                }
                if(!witnessed)for(Holder holder:evaluations.values())if(holder.raw==operand&&holder.role==Role.CELL&&!holder.closed){semantic=((ReferenceMemory)holder.raw).getRawValue();witnessed=true;break;}
                if(!witnessed)throw new IllegalArgumentException("Output carrier has no actual CV or result witness");
            }
            authority.value(semantic,Role.VALUE);
            if(semantic.type!=Memory.Type.NULL&&semantic.type!=Memory.Type.BOOL&&semantic.type!=Memory.Type.INT&&semantic.type!=Memory.Type.DOUBLE&&semantic.type!=Memory.Type.STRING)throw new IllegalArgumentException("Output coercion/callback protocol is incomplete");
            return semantic;
        }
        public Holder localHolder(int id){open();Holder holder=locals.get(id);if(holder==null)throw new IllegalArgumentException("Unknown actual local");return holder;}
        private final Set<NativePacket> nativePackets=new LinkedHashSet<>();
        public NativePacket nativeCall(int callId,Class<?> owner,String name,String descriptor,int argc) {
            open();if(callId<4_000_000||argc<0||argc>65535)throw new IllegalArgumentException("Invalid native SEND identity/arity");
            Invocation invocation=authority.invocations.peek();
            java.lang.reflect.Method member=authority.nativeMember(owner,name,descriptor);
            NativeSite site;
            if(invocation!=null&&invocation.frame==this) {
                if(!invocation.entered)throw new IllegalArgumentException("Native call has not entered its admitted caller frame");
                site=invocation.target.nativeSites.get(callId);
                if(site==null)throw new IllegalArgumentException("Native call has no bound semantic/physical producer proof");
                NativeMember expected=site.plan();
                if(!expected.owner().equals(owner.getName().replace('.','/'))||!expected.name().equals(name)||!expected.descriptor().equals(descriptor)||expected.argc()!=argc||!member.equals(site.semantic()))throw new IllegalArgumentException("Native call differs from its exact generated dependency");
            }else {
                // Existing explicit host-native protocols may invoke only their canonical body.
                // A discarded bridge still requires the generated byte-bound site above.
                NativeMember host=new NativeMember(callId,owner.getName().replace('.','/'),name,descriptor,argc);
                site=new NativeSite(host,member,php.runtime.invoke.PhpNativeResultUse.require(member,host.owner(),host.name(),host.descriptor(),false));
            }
            if(argc==0)authority.nativeMembers.get(member).validateArguments(List.of());
            NativePacket packet=new NativePacket(this,callId,site,argc);nativePackets.add(packet);cleanupOrder.push(packet);return packet;
        }
        public Memory borrowLocalCloneSource(int cloneEvalId,int localId,Memory operand) {
            open();Invocation invocation=authority.invocations.peek();if(invocation==null||invocation.frame!=this||!invocation.entered)throw new IllegalArgumentException("Local CLONE needs its exact generated frame");
            CloneSite site=invocation.target.runtimePlan.clones().stream().filter(item->item.id()==cloneEvalId&&item.local()==localId).findFirst().orElseThrow(()->new IllegalArgumentException("CLONE has no actual source/opcode witness"));
            Holder local=localHolder(localId);CvOrigin origin=closureOrigins.get(localId);
            if(origin==null||origin.holder()!=local||origin.revision()!=local.revision||origin.producer()!=site.producer()||!origin.closure().equals(site.closure()))throw new IllegalArgumentException("CLONE source CV publication became stale");
            ReferenceMemory borrowedAddress=borrowedAddresses.get(localId);
            if(operand instanceof ReferenceMemory&&operand!=local.carrier&&operand!=borrowedAddress)throw new IllegalArgumentException("CLONE is not its exact CV address");
            Memory semantic=local.raw;if(local.role==Role.CELL)semantic=((ReferenceMemory)semantic).getRawValue();
            if(!(semantic instanceof ObjectMemory)||!(((ObjectMemory)semantic).value instanceof php.runtime.lang.Closure)||!((ObjectMemory)semantic).value.getClass().getName().replace('.','/').equals(site.closure()))throw new IllegalArgumentException("CLONE source is not its witnessed lexical VALUE");
            if(!(operand instanceof ReferenceMemory)&&operand!=semantic&&(!(operand instanceof ObjectMemory)||((ObjectMemory)operand).value!=((ObjectMemory)semantic).value))throw new IllegalArgumentException("CLONE differs from its actual CV read");
            var closure=(php.runtime.lang.Closure)((ObjectMemory)semantic).value;closure.ownershipClosure().validate();authority.value(semantic,Role.VALUE);return semantic;
        }
        /** Acquisition pins the exact lexical callable before any argument expression runs. */
        public FunctionPacket functionPacket(int callId,String name,php.runtime.env.TraceInfo trace)throws Throwable {
            open();Invocation invocation=authority.invocations.peek();
            if(invocation==null||invocation.frame!=this||authority.currentFrame()!=this)throw new IllegalStateException("FUNCTION acquisition needs its actual caller invocation");
            FunctionCall site=invocation.target.runtimePlan.functionCalls().stream().filter(value->value.id()==callId&&value.name().equals(name)).findFirst().orElseThrow(()->new IllegalArgumentException("FUNCTION acquisition lacks its exact generated site"));
            Target target=authority.functionDependency(site);target.validate(authority);FunctionPacket packet=new FunctionPacket(this,site,target,trace);cleanupOrder.push(packet);return packet;
        }
        public CallPacket callable(int callId,Memory operand,int sourceLocalId,int sourceEvalId,php.runtime.env.TraceInfo trace,int arity)throws Throwable {
            open();if(authority.currentFrame()!=this||arity<0||callId<4_000_000)throw new IllegalArgumentException("Call acquisition needs its actual caller frame and producer identity");
            Holder source=null;Memory semantic;
            if(sourceEvalId>=0) {
                source=producerEval(sourceEvalId);if(source.role!=Role.VALUE)throw new IllegalArgumentException("Callable EVAL must supply its actual VALUE producer");semantic=source.raw;
                if(operand!=semantic&&(!(operand instanceof ObjectMemory)||!(semantic instanceof ObjectMemory)||((ObjectMemory)operand).value!=((ObjectMemory)semantic).value))throw new IllegalArgumentException("Callable differs from its producer");
            } else {
                if(sourceLocalId<0)throw new IllegalArgumentException("Callable needs a compiler-witnessed CV or EVAL source");
                Holder local=localHolder(sourceLocalId);ReferenceMemory borrowedAddress=borrowedAddresses.get(sourceLocalId);
                if(operand instanceof ReferenceMemory&&operand!=local.carrier&&operand!=borrowedAddress)throw new IllegalArgumentException("Callable is not its exact CV address");
                semantic=local.raw;
                if(local.role==Role.CELL)semantic=((ReferenceMemory)semantic).getRawValue();
                if(!(operand instanceof ReferenceMemory)&&operand!=semantic&&(!(operand instanceof ObjectMemory)||!(semantic instanceof ObjectMemory)||((ObjectMemory)operand).value!=((ObjectMemory)semantic).value))throw new IllegalArgumentException("Callable is not its exact CV read");
            }
            if(!(semantic instanceof ObjectMemory)||!(((ObjectMemory)semantic).value instanceof php.runtime.lang.Closure))throw new IllegalArgumentException("Ordinary callable protocol supports exact lexical Closure only");
            var closure=(php.runtime.lang.Closure)((ObjectMemory)semantic).value;var certificate=closure.ownershipClosure();certificate.validate();
            Invocation invocation=authority.invocations.peek();if(invocation!=null&&invocation.frame==this) {
                CallSite call=invocation.target.runtimePlan.calls().stream().filter(item->item.id()==callId).findFirst().orElseThrow(()->new IllegalArgumentException("CALL has no generated source/opcode proof"));
                if(call.local()!=sourceLocalId||call.evaluation()!=sourceEvalId||call.arity()!=arity||!closure.getClass().getName().replace('.','/').equals(call.closure()))throw new IllegalArgumentException("CALL acquisition differs from its actual producer/dependency");
                if(sourceLocalId>=0){CvOrigin origin=closureOrigins.get(sourceLocalId);Holder local=localHolder(sourceLocalId);if(origin==null||origin.holder()!=local||origin.revision()!=local.revision||origin.producer()!=call.producer()||!origin.closure().equals(call.closure()))throw new IllegalArgumentException("CALL CV publication witness became stale");}
            }
            if(!(closure.getReflection() instanceof php.runtime.reflection.helper.ClosureEntity))throw new IllegalArgumentException("Callable has no lexical allocation witness");
            var method=closure.getReflection().findMethod("__invoke");Target target=authority.targets.get(method);
            if(target==null||target.entity!=method||method.getClazz()!=closure.getReflection()||target.plan.entryKind()!=PhpOwnershipExecution.EntryKind.LEXICAL_CLOSURE||target.member.getDeclaringClass()!=closure.getClass())throw new IllegalArgumentException("Callable has no exact admitted lexical target");
            target.validate(authority);authority.value(new ObjectMemory(closure),Role.VALUE);
            var parameters=method.getParameters();if(parameters!=null) {
                if(arity!=parameters.length)throw new IllegalArgumentException("Ordinary default/variadic call custody is incomplete");
                for(var parameter:parameters)if(parameter.isReference()||parameter.isVariadic()||parameter.getDefaultValue()!=null||parameter.getDefaultInitializer()!=null)throw new IllegalArgumentException("Ordinary default/reference SEND is incomplete");
            }else if(arity!=0)throw new IllegalArgumentException("Unknown callable argument contract");
            CallPacket packet=new CallPacket(this,callId,target,closure,trace,arity);
            try(Publication publication=new Publication(packet.receiver,authority.value(new ObjectMemory(closure),Role.VALUE),null,source)){publication.storeDirect();publication.commit();}
            catch(Throwable failure){throw packet.closeAfterFailure(failure);}
            cleanupOrder.push(packet);return packet;
        }
        private Memory conditionalLocal(int producer,int localId,Memory actual)throws Throwable {
            open();Invocation invocation=authority.invocations.peek();
            if(invocation==null||invocation.frame!=this||authority.currentFrame()!=this)throw new IllegalArgumentException("Conditional CV read needs its actual invocation");
            if(invocation.target.runtimePlan.conditionalLocals().stream().noneMatch(item->item.id()==producer&&item.local()==localId))throw new IllegalArgumentException("Conditional CV read lacks its actual address/source witness");
            Holder local=localHolder(localId);local.open();
            if(actual!=local.carrier&&actual!=borrowedAddresses.get(localId))throw new IllegalArgumentException("Conditional CV read differs from its actual carrier");
            Memory semantic=local.raw;
            if(local.role==Role.CELL)semantic=((ReferenceMemory)semantic).getRawValue();
            Value checked=authority.value(semantic,Role.VALUE);Memory value=semantic.toImmutable();Value candidate=authority.value(value,Role.VALUE);
            if(checked.node!=candidate.node||checked.node==null&&!semantic.identical(value))throw new IllegalArgumentException("Conditional CV read changed its semantic payload");
            try(Publication publication=prepareEval(producer,value,Role.VALUE)){publication.storeDirect();publication.commit();}
            return value;
        }
        private record ConditionalSelection(ConditionalSite site,boolean trueArm) {}

        private final Map<Integer,ConditionalSelection> conditionalSelections=new HashMap<>();
        private ConditionalSite conditionalSite(int id) {
            open();Invocation invocation=authority.invocations.peek();
            if(invocation==null||invocation.frame!=this||authority.currentFrame()!=this)throw new IllegalArgumentException("Conditional needs its actual caller invocation");
            return invocation.target.runtimePlan.conditionals().stream().filter(value->value.id()==id).findFirst().orElseThrow(()->new IllegalArgumentException("Conditional lacks its actual branch proof"));
        }
        private Value conditionalSource(int id,Memory actual) {
            Holder source=producerEval(id);source.requireProducerEvaluation();
            if(source.role!=Role.VALUE)throw new IllegalArgumentException("Conditional operand is not its semantic VALUE producer");
            Value expected=authority.value(source.raw,Role.VALUE),candidate=authority.value(actual,Role.VALUE);
            if(expected.node!=candidate.node||expected.node==null&&!source.raw.identical(actual))throw new IllegalArgumentException("Conditional lost its actual producer operand");
            return candidate;
        }
        private boolean conditionalTest(int id,int sourceId,Memory actual,int kind,boolean retainTrue)throws Throwable {
            ConditionalSite site=conditionalSite(id);
            if(site.source()!=sourceId||site.kind()!=kind||site.retainTrue()!=retainTrue||conditionalSelections.containsKey(id))throw new IllegalArgumentException("Conditional test changed its exact source or active branch");
            conditionalSource(sourceId,actual);boolean chosen=kind==0?actual.isNotNull():actual.toBoolean();
            conditionalSelections.put(id,new ConditionalSelection(site,chosen));
            if(!chosen||!retainTrue)releaseEval(sourceId);
            return chosen;
        }
        private Memory conditionalResult(int id,int resultId,int selected,Memory actual,boolean trueArm)throws Throwable {
            ConditionalSite site=conditionalSite(id);ConditionalSelection selection=conditionalSelections.get(id);
            if(resultId!=id||selection==null||selection.site()!=site||selection.trueArm()!=trueArm||selected!=(trueArm?site.trueSource():site.falseSource()))throw new IllegalArgumentException("Conditional result is not its selected actual arm");
            Value candidate;Holder source=null;
            if(selected>=0){candidate=conditionalSource(selected,actual);source=producerEval(selected);}
            else {
                String literal=trueArm?site.trueLiteral():site.falseLiteral();
                if(literal.equals("N")){if(actual!=Memory.NULL)throw new IllegalArgumentException("Conditional NULL literal changed");}
                else if(!literal.startsWith("S")||actual.getClass()!=php.runtime.memory.StringMemory.class||!literal.substring(1).equals(actual.toString()))throw new IllegalArgumentException("Conditional primitive literal changed");
                candidate=authority.value(actual,Role.VALUE);
            }
            Holder destination=root(evaluations,id,Purpose.PRODUCER_EVAL);
            try(Publication publication=new Publication(destination,candidate,null,source)){publication.storeDirect();publication.commit();}
            conditionalSelections.remove(id);return destination.raw;
        }
        private Holder pendingReturn;
        public Publication prepareReturn(Memory raw,int evalId,int mode) {
            open();Role role=mode==1?Role.CELL:Role.VALUE;Value candidate=authority.value(raw,role);
            if(pendingReturn==null)pendingReturn=track(new Holder(authority,authority.domain.graph().root(),null,Purpose.RETURN));
            Holder source=evalId<0?null:producerEval(evalId);
            if(evalId>=0&&source==null)throw new IllegalArgumentException("RETURN lost its actual EVAL producer");
            return new Publication(pendingReturn,candidate,null,source);
        }
        private Publication prepareResult(Holder destination,Memory raw,Role role) {
            Value candidate=authority.value(raw,role);
            if(pendingReturn!=null&&pendingReturn.edge.target()==candidate.node)return new Publication(destination,candidate,null,pendingReturn);
            if(candidate.node!=null)throw new IllegalArgumentException("Heap result has no actual RETURN custody");
            return destination.prepare(candidate);
        }
        public Publication prepareEval(int id,Memory raw,Role role){return root(evaluations,id,Purpose.PRODUCER_EVAL).prepare(raw,role);}
        private Publication prepareSend(int id,Memory raw,Role role){return root(evaluations,id,Purpose.SEND).prepare(raw,role);}
        public Holder evalHolder(int id){open();return evaluations.get(id);}
        public Memory stageAllocation(int evalId,PhpOwnershipObjects.Allocation allocation)throws Throwable {
            var binding=allocation.binding();return stageAllocation(evalId,binding,allocation::close);
        }
        public Memory stageAllocation(int evalId,PhpOwnershipArrays.Allocation allocation)throws Throwable {
            var binding=allocation.binding();return stageAllocation(evalId,binding,allocation::close);
        }
        public Memory stageAllocation(int evalId,PhpOwnershipClosures.Allocation allocation)throws Throwable {
            var binding=allocation.binding();return stageAllocation(evalId,binding,allocation::close);
        }
        /** A consumed producer keeps its physical slot; the next execution may reuse only that empty EVAL. */
        public void requireAvailableAllocationEvaluation(int evalId) {
            open();Holder existing=evaluations.get(evalId);if(existing==null)return;
            existing.requireProducerEvaluation();
            if(existing.role()!=Role.VALUE || existing.raw()!=Memory.UNDEFINED || existing.node()!=null)
                throw new IllegalArgumentException("Allocation EVAL still holds an actual result");
        }
        private Memory stageAllocation(int evalId,PhpOwnershipValues.Binding binding,PhpOwnership.Work releaseLease)throws Throwable {
            open();if(binding.domain()!=authority.domain)throw new IllegalArgumentException("Foreign allocation producer");
            Memory raw=binding.rawValue();Role role=binding.role()==PhpOwnershipValues.Role.CELL?Role.CELL:Role.VALUE;
            Value value=authority.value(raw,role);
            requireAvailableAllocationEvaluation(evalId);
            authority.domain.graph().deferDestruction(()->{
                try(Publication publication=root(evaluations,evalId,Purpose.PRODUCER_EVAL).prepare(value)){publication.storeDirect();publication.commit();}
                releaseLease.run();
            });return raw;
        }

        private int diagnosticId=Integer.MIN_VALUE;
        /** A writable-fetch retention lease is a real owner but cannot become a producer transfer. */
        public Diagnostic pinDiagnostic(ArrayMemory actual) throws Throwable {return pinTemporary(actual,Role.VALUE,Purpose.DIAGNOSTIC);}
        /** A native allocation/COW temporary is a real transferable producer EVAL. */
        public Diagnostic pinTemporary(Memory actual,Role role) throws Throwable {return pinTemporary(actual,role,Purpose.PRODUCER_EVAL);}
        private Diagnostic pinTemporary(Memory actual,Role role,Purpose purpose) throws Throwable {
            open();int id=diagnosticId++;
            try(Publication publication=root(evaluations,id,purpose).prepare(actual,role)){publication.storeDirect();publication.commit();}
            return new Diagnostic(this,id,evalHolder(id));
        }
        public void releaseEval(int id) throws Exception{open();Holder holder=evaluations.remove(id);if(holder!=null)holder.close();}
        @Override public void close() throws Exception {
            open();if(authority.frames.peek()!=this)throw new IllegalStateException("Runtime frames must close in LIFO order");
            Throwable primary=null;
            // Collect before retiring this frame: callbacks still have a valid PHP execution context.
            try{authority.collectGarbageIfNeeded();}catch(Throwable failure){primary=authority.domain.graph().composeFailure(primary,failure);}
            // Actual creation order interleaves EVAL and SEND packets during unwind.
            while(!cleanupOrder.isEmpty())try{cleanupOrder.pop().close();}catch(Throwable failure){primary=authority.domain.graph().composeFailure(primary,failure);}
            closed=true;authority.frames.pop();pendingReturn=null;evaluations.clear();locals.clear();borrowed.clear();borrowedAddresses.clear();closureOrigins.clear();objectOrigins.clear();globalBorrows.clear();conditionalSelections.clear();lifecycleReceivers.clear();
            if(primary!=null)fail(primary);
        }
    }

    /** Real lexical CALL custody; Java argument arrays borrow these published SEND holders. */
    public static final class FunctionPacket implements AutoCloseable {
        private final Frame caller;private final FunctionCall site;private final Target target;private final php.runtime.env.TraceInfo trace;private Holder send;private boolean closed,invoked;
        private FunctionPacket(Frame caller,FunctionCall site,Target target,php.runtime.env.TraceInfo trace){this.caller=caller;this.site=site;this.target=target;this.trace=trace;}
        private void open(){caller.open();if(closed)throw new IllegalStateException("FUNCTION packet is closed");}
        public Memory sendCell(int index,Memory actual,int sourceLocal)throws Throwable {
            open();if(index!=0||send!=null||invoked||sourceLocal!=site.local()||caller.authority.currentFrame()!=caller)throw new IllegalArgumentException("FUNCTION SEND order/source changed");
            Holder source=caller.localHolder(sourceLocal);ReferenceMemory borrowed=caller.borrowedAddresses.get(sourceLocal);
            if(!source.hasPhysicalCarrier()||actual!=source.carrier&&actual!=borrowed||!source.matches(source.carrier)||actual==borrowed&&borrowed.getRawValue()!=source.carrier)throw new IllegalArgumentException("FUNCTION SEND is not its actual current CV");
            ReferenceMemory cell=source.reference();Holder retained=new Holder(caller.authority,caller.authority.domain.graph().root(),null,Purpose.SEND);
            try(Publication publication=retained.prepare(caller.authority.value(cell,Role.CELL))){publication.storeDirect();publication.commit();send=retained;return cell;}
            catch(Throwable failure){throw retained.closeAfterFailure(failure);}
        }
        public Memory invokeToEval(int id)throws Throwable {return invokeToEval(id,false);}
        public Memory invokeToEvalDiscarded(int id)throws Throwable {return invokeToEval(id,true);}
        private Memory invokeToEval(int id,boolean discarded)throws Throwable {
            open();if(discarded!=site.discardedResult())throw new IllegalArgumentException("FUNCTION result use differs from its physical invocation instruction");
            if(invoked||send==null||id!=site.id()||caller.authority.currentFrame()!=caller||caller.evaluations.containsKey(id))throw new IllegalArgumentException("FUNCTION RESULT lacks its actual completed SEND");
            target.validate(caller.authority);invoked=true;Frame callee=caller.authority.frame();Invocation invocation=new Invocation(target,callee);invocation.functionPacket=this;caller.authority.invocations.push(invocation);
            Holder destination=null;Memory raw=null;Throwable primary=null;boolean pushed=false;
            try {
                var entity=(php.runtime.reflection.FunctionEntity)target.entity;Memory[] args={send.raw};caller.authority.environment.pushCall(trace,null,args,entity.getName(),null,null);pushed=true;
                if(discarded)php.runtime.invoke.PhpNoDiscardInvocation.beforeBody(caller.authority.environment,trace,entity);
                raw=entity.invoke(caller.authority.environment,trace,args);if(!invocation.entered)throw new IllegalStateException("FUNCTION body did not enter its exact frame");
                destination=caller.root(caller.evaluations,id,Purpose.PRODUCER_EVAL);try(Publication publication=callee.prepareResult(destination,raw,Role.VALUE)){publication.storeDirect();publication.commit();}
            }catch(Throwable failure){primary=failure;}
            finally{if(pushed)caller.authority.environment.popCall();caller.authority.invocations.pop();try{callee.close();}catch(Throwable cleanup){primary=caller.authority.domain.graph().composeFailure(primary,cleanup);}primary=closeAfterFailure(primary);}
            if(primary!=null){if(destination!=null)primary=destination.closeAfterFailure(primary);throw primary;}return raw;
        }
        private Throwable closeAfterFailure(Throwable primary){try{close();}catch(Throwable cleanup){primary=caller.authority.domain.graph().composeFailure(primary,cleanup);}return primary;}
        public void close()throws Exception{if(closed)return;open();closed=true;if(send!=null)send.close();}
    }

    public static final class CallPacket implements AutoCloseable {
        private final Frame caller;private final int callId;private final Target target;private final php.runtime.lang.Closure closure;private final php.runtime.env.TraceInfo trace;
        private final Holder receiver;private final Holder[] sends;private int sent;private boolean closed,invoked,internalCallback;
        private CallPacket(Frame caller,int callId,Target target,php.runtime.lang.Closure closure,php.runtime.env.TraceInfo trace,int arity){this.caller=caller;this.callId=callId;this.target=target;this.closure=closure;this.trace=trace;receiver=new Holder(caller.authority,caller.authority.domain.graph().root(),null,Purpose.SEND);sends=new Holder[arity];}
        private void open(){caller.open();if(closed)throw new IllegalStateException("Call packet is closed");}
        public Memory send(int index,Memory raw,int sourceEvalId)throws Throwable {
            open();if(invoked||caller.authority.currentFrame()!=caller||index!=sent||index>=sends.length)throw new IllegalArgumentException("CALL SEND order changed");
            Value value=caller.authority.value(raw,Role.VALUE);Holder source=sourceEvalId<0?null:caller.producerEval(sourceEvalId);
            Holder send=new Holder(caller.authority,caller.authority.domain.graph().root(),null,Purpose.SEND);
            try(Publication publication=new Publication(send,value,null,source)){publication.storeDirect();publication.commit();}
            catch(Throwable failure){throw send.closeAfterFailure(failure);}sends[sent++]=send;return raw;
        }
        public Memory invokeToEval(int resultEvalId)throws Throwable {return invokeToEval(resultEvalId,false);}
        public Memory invokeToEvalDiscarded(int resultEvalId)throws Throwable {return invokeToEval(resultEvalId,true);}
        private Memory invokeToEval(int resultEvalId,boolean discarded)throws Throwable {
            open();Invocation callerInvocation=caller.authority.invocations.peek();
            if(internalCallback){if(discarded)throw new IllegalArgumentException("Internal handler result use cannot be discarded");}
            else if(callerInvocation==null) {
                // A standalone host Frame consumes the result; discarded use needs a physical caller witness.
                if(discarded)throw new IllegalArgumentException("CALL has no actual caller invocation");
            }
            else {
                if(callerInvocation.frame!=caller)throw new IllegalArgumentException("CALL has no actual caller invocation");
                CallSite site=callerInvocation.target.runtimePlan.calls().stream().filter(value->value.id()==callId).findFirst().orElseThrow(()->new IllegalArgumentException("CALL has no byte-bound result-use proof"));
                if(discarded!=site.discardedResult())throw new IllegalArgumentException("CALL result use differs from its physical invocation instruction");
            }
            if(invoked||sent!=sends.length||caller.authority.currentFrame()!=caller||resultEvalId!=callId||caller.evaluations.containsKey(resultEvalId))throw new IllegalArgumentException("CALL RESULT needs its completed actual packet");
            target.validate(caller.authority);closure.ownershipClosure().validate();invoked=true;
            Memory[] args=new Memory[sent];for(int i=0;i<sent;i++)args[i]=sends[i].raw;
            Frame callee=caller.authority.frame();Invocation invocation=new Invocation(target,callee,closure,this);caller.authority.invocations.push(invocation);
            Holder destination=null;Memory raw=null;Throwable primary=null;boolean pushed=false;
            try {
                var method=(php.runtime.reflection.MethodEntity)target.entity;Memory[] passed=args;
                if(discarded)php.runtime.invoke.PhpNoDiscardInvocation.beforeBody(caller.authority.environment,trace,method);
                if(internalCallback) {
                    String className=closure.getReflection().getName();String scope=closure.hasPhp8Binding()?closure.getScope():method.getClazz().getName();
                    passed=php.runtime.invoke.InvokeArgumentHelper.makeArguments(caller.authority.environment,args,method.getParameters(args.length),php.runtime.invoke.InvokeArgumentHelper.closureCallName(closure),null,scope==null?"":scope,trace,true);
                    String staticClass=closure.hasPhp8Binding()?closure.getCalledClass():closure.getScope();
                    String stackClass=closure.getReflection().isHiddenInCallStack()?staticClass:method.getClazz().getName();
                    caller.authority.environment.pushCall(trace,closure,args,method.getName(),stackClass,staticClass);pushed=true;
                }
                raw=method.invokeDynamic(closure,caller.authority.environment,trace,passed);
                if(!invocation.entered)throw new IllegalStateException("Callable body did not enter its exact invocation");
                destination=caller.root(caller.evaluations,resultEvalId,Purpose.PRODUCER_EVAL);
                try(Publication publication=callee.prepareResult(destination,raw,((php.runtime.reflection.MethodEntity)target.entity).isReturnReference()?Role.CELL:Role.VALUE)){publication.storeDirect();publication.commit();}
            }catch(Throwable failure){primary=failure;}
            finally {
                if(pushed)caller.authority.environment.popCall();
                caller.authority.invocations.pop();try{callee.close();}catch(Throwable cleanup){primary=caller.authority.domain.graph().composeFailure(primary,cleanup);}
                primary=closeAfterFailure(primary);
            }
            if(primary!=null){if(destination!=null)primary=destination.closeAfterFailure(primary);throw primary;}return raw;
        }
        private Throwable closeAfterFailure(Throwable primary){try{close();}catch(Throwable cleanup){primary=caller.authority.domain.graph().composeFailure(primary,cleanup);}return primary;}
        @Override public void close()throws Exception {
            if(closed)return;open();closed=true;Throwable primary=null;
            for(int i=sent-1;i>=0;i--)try{sends[i].close();}catch(Throwable cleanup){primary=caller.authority.domain.graph().composeFailure(primary,cleanup);}
            try{receiver.close();}catch(Throwable cleanup){primary=caller.authority.domain.graph().composeFailure(primary,cleanup);}if(primary!=null)fail(primary);
        }
    }

    /** One actual native registration record owns its callable across Env active, stack and suspended positions. */
    public static final class HandlerRegistration implements AutoCloseable {
        private final Authority authority;private final php.runtime.invoke.Invoker invoker;private final Target target;
        private final Holder retained;private final PhpOwnershipValues.Binding binding;private boolean installed,closed;
        private HandlerRegistration(Authority authority,php.runtime.invoke.Invoker invoker,Target target,Value value)throws Throwable {
            this.authority=authority;this.invoker=invoker;this.target=target;
            binding=PhpOwnershipValues.bindValue(authority.domain,value.raw,value.node);
            retained=new Holder(authority,authority.domain.graph().root(),null,Purpose.FIELD);
            try(Publication publication=retained.prepare(value)){publication.storeDirect();publication.commit();}
            catch(Throwable failure){throw retained.closeAfterFailure(failure);}
        }
        private void open(){authority.open();if(closed)throw new IllegalStateException("Error handler FIELD is closed");retained.open();}
        public PhpOwnershipValues.Binding binding(){open();return binding;}
        public boolean belongsTo(Authority expected){return !closed&&authority==expected;}
        public void requireInvoker(php.runtime.invoke.Invoker actual){open();if(actual!=invoker)throw new IllegalArgumentException("Handler invoker changed");}
        public void install(Environment env){open();if(installed||authority.environment!=env||current(env)!=authority)throw new IllegalArgumentException("Handler FIELD is already installed or foreign");installed=true;}
        public boolean invokeHandled(php.runtime.invoke.Invoker actual,Memory[] args)throws Throwable {
            try(var scope=authority.enter()) {
                requireInvoker(actual);if(!installed||args.length!=4)throw new IllegalArgumentException("Handler dispatch requires its four actual sequential arguments");
                authority.errorHandlerTarget(actual,retained.raw);
                Frame caller=authority.frame();Throwable primary=null;boolean handled=false;
                try {
                    int id=caller.diagnosticId++;var closure=(php.runtime.lang.Closure)((ObjectMemory)retained.raw).value;
                    CallPacket packet=new CallPacket(caller,id,target,closure,php.runtime.env.TraceInfo.UNKNOWN,4);packet.internalCallback=true;
                    try(Publication publication=packet.receiver.prepare(authority.value(retained.raw,Role.VALUE))){publication.storeDirect();publication.commit();}
                    catch(Throwable failure){throw packet.closeAfterFailure(failure);}
                    caller.cleanupOrder.push(packet);
                    for(int i=0;i<args.length;i++)packet.send(i,args[i],-1);
                    Memory result=packet.invokeToEval(id);Holder owner=caller.producerEval(id);
                    if(owner.raw!=result)throw new IllegalArgumentException("Handler checked RETURN lost its actual result owner");
                    handled=result.toValue()!=Memory.FALSE;caller.releaseEval(id);
                }catch(Throwable failure){primary=failure;}
                finally{try{caller.close();}catch(Throwable cleanup){primary=authority.domain.graph().composeFailure(primary,cleanup);}}
                if(primary!=null)return fail(primary);return handled;
            }
        }
        public Memory publishCurrentNativeResult(Environment env,php.runtime.env.handler.ErrorHandler selected)throws Throwable {
            open();NativeBody body=currentNativeBody(env);
            if (env!=authority.environment || env.getErrorHandler()!=selected || selected.getClass()!=php.runtime.env.handler.ErrorHandler.class
                    || selected.getOwnershipRegistration()==null || !selected.getOwnershipRegistration().belongsTo(authority)
                    || selected.getOwnershipRegistration().binding().rawValue()!=retained.raw || selected.invoker!=invoker
                    || body==null || body.packet.frame.authority!=authority || !body.packet.values.isEmpty()
                    || body.member().getDeclaringClass()!=php.runtime.ext.core.Php85LangFunctions.class
                    || !body.member().getName().equals("get_error_handler") || body.member().getReturnType()!=Memory.class
                    || body.member().getParameterCount()!=1 || body.member().getParameterTypes()[0]!=Environment.class)
                throw new IllegalArgumentException("Current handler RESULT lacks its exact Environment/registration/getter BODY");
            NativeProducer producer=body.arena.create();
            try{producer.publish(authority.value(retained.raw,Role.VALUE));return producer.publishResult();}
            catch(Throwable failure){throw producer.closeAfterFailure(failure);}
        }
        public Memory publishNativeResult(Environment env)throws Throwable {
            open();NativeBody body=currentNativeBody(env);
            if(body==null||body.packet.frame.authority!=authority||body.member().getDeclaringClass()!=php.runtime.ext.core.LangFunctions.class||!body.member().getName().equals("set_error_handler"))throw new IllegalArgumentException("Previous handler RESULT requires its exact registration BODY");
            NativeProducer producer=body.arena.create();
            try{producer.publish(authority.value(retained.raw,Role.VALUE));return producer.publishResult();}
            catch(Throwable failure){throw producer.closeAfterFailure(failure);}
        }
        public Throwable composeFailure(Throwable primary,Throwable cleanup){try(var scope=authority.enter()){return authority.domain.graph().composeFailure(primary,cleanup);}}
        @Override public void close()throws Exception {
            if(closed)return;try(var scope=authority.enter()){open();closed=true;retained.close();}
        }
    }

    /** Minted by exact Target admission, never by an arbitrary caller-provided result-use boolean. */
    private record NativeSite(NativeMember plan,java.lang.reflect.Method semantic,php.runtime.invoke.PhpNativeResultUse.Proof proof) {}

    /** SEND custody lives before later argument evaluation, RESULT is published before SEND retirement. */
    public static final class NativePacket implements AutoCloseable {
        private final Frame frame;private final int callId;private final java.lang.reflect.Method member;private final NativeSite site;
        private final Holder[] sends;private final List<Value> values=new ArrayList<>();private int sent;private boolean closed;
        private NativeBody body;
        private final Map<Integer,ReferenceMemory> directCvs=new HashMap<>();
        private NativePacket(Frame frame,int callId,NativeSite site,int argc){this.frame=frame;this.callId=callId;this.site=site;this.member=site.semantic();sends=new Holder[argc];}
        private void open(){frame.open();if(closed)throw new IllegalStateException("Native packet is closed");}
        private Memory sendDirectCv(int index,Memory actual,int local)throws Throwable {
            open();if(index!=sent||index>=sends.length||frame.authority.currentFrame()!=frame)
                throw new IllegalArgumentException("Native direct CV has another SEND/frame position");
            requireNativeDirectJoinBody(member);
            Invocation invocation=frame.authority.invocations.peek();
            NativeMember site=invocation==null||invocation.frame!=frame?null:invocation.target.runtimePlan.nativeMembers().stream()
                    .filter(n->n.id()==callId).findFirst().orElse(null);
            if(site==null||!Objects.equals(site.directCvArguments().get(index),local))
                throw new IllegalArgumentException("Native direct CV lacks its exact generated source capability");
            Holder source=frame.localHolder(local);source.open();
            if(!source.hasPhysicalCarrier()) {
                if(source.role!=Role.VALUE||source.raw!=actual)throw new IllegalArgumentException("Native VALUE CV changed its exact physical source");
                send(index,actual,-1);return actual;
            }
            ReferenceMemory wrapper=frame.borrowedAddresses.get(local);
            if(actual!=source.carrier&&actual!=wrapper||!source.matches(source.carrier)||actual==wrapper&&wrapper.getRawValue()!=source.carrier)
                throw new IllegalArgumentException("Native direct CV is not its actual current physical address");
            // The caller frame owns this slot throughout the native BODY; this borrow does not export a CELL.
            directCvs.put(index,(ReferenceMemory)actual);
            values.add(new Value(frame.authority,actual,Role.BORROW_ADDRESS,null));sent++;
            if(sent==sends.length)frame.authority.nativeMembers.get(member).validateArguments(List.copyOf(values));
            return actual;
        }
        public void send(int index,Memory raw,int evalId)throws Throwable {
            open();if(index!=sent||index>=sends.length)throw new IllegalArgumentException("Native SEND order does not match actual argument evaluation");
            Role role=Role.VALUE;Memory snapshot=raw;
            if(raw.getClass()==ReferenceMemory.class&&(((ReferenceMemory)raw).getRuntimeCell()!=null||((ReferenceMemory)raw).getOwnershipCell()!=null)) {
                Memory payload=((ReferenceMemory)raw).getRawValue();
                if(payload instanceof ArrayMemory||payload instanceof ObjectMemory||payload instanceof ReferenceMemory)throw new IllegalArgumentException("Direct native CELL transport requires an existing scalar canonical cell");role=Role.CELL;
            } else if(raw instanceof ReferenceMemory)throw new IllegalArgumentException("Native SEND requires its actual post-borrow semantic operand");
            else if(raw instanceof ObjectMemory)snapshot=new ObjectMemory(((ObjectMemory)raw).value);
            Value candidate=frame.authority.value(snapshot,role);Holder source=evalId<0?null:frame.producerEval(evalId);
            if(evalId>=0&&source==null)throw new IllegalArgumentException("Native SEND lost its exact EVAL source");
            Holder holder=new Holder(frame.authority,frame.authority.domain.graph().root(),null,Purpose.SEND);
            try(Publication publication=new Publication(holder,candidate,null,source)){publication.storeDirect();publication.commit();}
            catch(Throwable failure){throw holder.closeAfterFailure(failure);}
            sends[index]=holder;values.add(candidate);sent++;
            if(sent==sends.length) {
                if(member.getDeclaringClass()==PhpComparisonCalls.class)PhpComparisonCalls.validateOwnershipArguments(frame.authority,List.copyOf(values));
                frame.authority.nativeMembers.get(member).validateArguments(List.copyOf(values));
            }
        }
        public Memory argument(int index){open();if(index<0||index>=sent)throw new IllegalArgumentException("Unpublished native argument");return directCvs.containsKey(index)?directCvs.get(index):sends[index].raw();}
        public java.lang.reflect.Method member(){open();return member;}
        public java.lang.reflect.Method physicalMember(){open();return site.proof().physical();}
        public boolean discardedResult(){open();return site.proof().discarded();}
        public void resultToEval(Memory raw,int evalId)throws Throwable {
            open();if(sent!=sends.length||evalId!=callId)throw new IllegalArgumentException("Native RESULT does not match completed SEND packet");
            if(body==null||body.phase!=NativePhase.RETURNED)throw new IllegalStateException("Native RESULT requires a completed exact BODY");
            body.arena.transferResult(raw,evalId);close();
        }
        @Override public void close()throws Exception {
            if(closed)return;open();Throwable primary=null;
            if(body!=null){if(body.phase==NativePhase.ACTIVE)primary=body.fail(null);else primary=body.arena.closeAfterFailure(null);}
            closed=true;frame.nativePackets.remove(this);directCvs.clear();
            for(int i=sent-1;i>=0;i--)try{if(sends[i]!=null)sends[i].close();}catch(Throwable failure){primary=frame.authority.domain.graph().composeFailure(primary,failure);}
            if(primary!=null)fail(primary);
        }
    }

    /** Only the selected native Java body installs this context; argument evaluation never does. */
    public static NativeBody currentNativeBody(Environment environment) {
        Authority authority=current(environment);if(authority==null)return null;
        authority.open();NativeBody body=authority.nativeBodies.peek();
        return body!=null&&body.phase==NativePhase.ACTIVE&&body.packet.frame==authority.currentFrame()?body:null;
    }
    public static NativeBody nativeEnterBody(NativePacket packet) {
        if(packet==null)return null;packet.open();Frame frame=packet.frame;
        if(frame.authority.currentFrame()!=frame||packet.sent!=packet.sends.length||packet.body!=null)
            throw new IllegalStateException("Native BODY needs its exact completed SEND packet");
        NativeBody body=new NativeBody(packet);packet.body=body;frame.authority.nativeBodies.push(body);return body;
    }
    public static void nativeLeaveBody(NativeBody body) {if(body!=null)body.leave();}
    public static Throwable nativeFailBody(NativeBody body,Throwable primary) {return body==null?primary:body.fail(primary);}
    private enum NativePhase { ACTIVE, RETURNED, FAILED }
    public static final class NativeBody {
        private final NativePacket packet;private final NativeArena arena;private NativePhase phase=NativePhase.ACTIVE;
        private NativeBody(NativePacket packet){this.packet=packet;arena=new NativeArena(this);}
        private void active(){packet.open();if(phase!=NativePhase.ACTIVE||packet.frame.authority.currentFrame()!=packet.frame||packet.frame.authority.nativeBodies.peek()!=this)
            throw new IllegalStateException("Native operation is outside its exact BODY");}
        public java.lang.reflect.Method member(){active();return packet.member;}
        public Diagnostic retainArrayRead(ArrayMemory source,ArrayMemory snapshot)throws Throwable {
            active();requireNativeJoinBody(packet.member);
            boolean argument=false;for(int i=0;i<packet.sent;i++)if(packet.argument(i).toValue()==source)argument=true;
            Value original=packet.frame.authority.value(source,Role.VALUE),copy=packet.frame.authority.value(snapshot,Role.VALUE);
            if(!argument||original.node!=copy.node)throw new IllegalArgumentException("Native ARRAY read is not its actual parsed argument backing");
            return packet.frame.pinTemporary(snapshot,Role.VALUE,Purpose.DIAGNOSTIC);
        }
        public Memory stringable(ObjectMemory object,php.runtime.env.TraceInfo trace)throws Throwable {
            active();requireNativeJoinBody(packet.member);
            return checkedStringable(object,trace);
        }
        /** The actual unary native SEND owns this receiver throughout coercion and checked RETURN. */
        public Memory scalarStringable(int argument,ObjectMemory object,php.runtime.env.TraceInfo trace)throws Throwable {
            active();requireNativeScalarStringBody(packet.member,argument);
            if(packet.sent!=packet.sends.length||packet.sends.length!=1||packet.directCvs.containsKey(argument))
                throw new IllegalArgumentException("Native scalar Stringable needs its completed VALUE SEND");
            Holder send=packet.sends[argument];
            if(send==null)throw new IllegalArgumentException("Native scalar Stringable has no actual SEND holder");
            send.open();Memory retained=send.raw;
            Value actual=packet.frame.authority.value(object,Role.VALUE);
            if(send.purpose!=Purpose.SEND||send.role!=Role.VALUE||!(retained instanceof ObjectMemory)
                    ||((ObjectMemory)retained).value!=object.value||actual.node==null||send.node()!=actual.node)
                throw new IllegalArgumentException("Native scalar Stringable is not its actual retained argument receiver");
            return checkedStringable(object,trace);
        }
        private Memory checkedStringable(ObjectMemory object,php.runtime.env.TraceInfo trace)throws Throwable {
            var authority=packet.frame.authority;authority.value(object,Role.VALUE);
            var method=object.getReflection().methodMagicToString;
            if(method==null)throw new IllegalArgumentException("Native Stringable has no actual magic method");
            authority.environment.pushCall(trace,object.value,method.getName(),new Memory[0]);
            try(var result=authority.invoke(method,object.value,trace)) {
                Memory raw=result.raw().toValue();
                if(!raw.isString())authority.environment.exception(trace,php.runtime.lang.exception.BaseTypeError.class,
                        "%s::__toString(): Return value must be of type string, %s returned",object.getReflection().getName(),raw.getRealType().toString());
                return raw.toImmutable();
            } finally {authority.environment.popCall();}
        }
        public NativeArena arena(){active();return arena;}
        private void leave(){active();packet.frame.authority.nativeBodies.pop();phase=NativePhase.RETURNED;}
        private Throwable fail(Throwable primary){
            if(phase==NativePhase.ACTIVE){active();packet.frame.authority.nativeBodies.pop();phase=NativePhase.FAILED;}
            else if(phase!=NativePhase.FAILED)throw new IllegalStateException("Successful native BODY cannot fail again");
            return arena.closeAfterFailure(primary);
        }
    }
    /** Actual native parser TEMP addresses, on the same graph as compiler CV/SEND holders. */
    public static final class NativeArena {
        private final NativeBody body;private final Deque<NativeProducer> producers=new ArrayDeque<>();
        private NativeProducer selected;private Memory selectedRaw;private boolean closed;
        private NativeArena(NativeBody body){this.body=body;}
        public Authority authority(){body.active();return body.packet.frame.authority;}
        private void active(){body.active();if(closed)throw new IllegalStateException("Native arena is closed");}
        public NativeProducer empty(){active();return create();}
        public NativeProducer temp(Memory semantic)throws Throwable {
            active();Value value=authority().value(semantic,Role.VALUE);
            // Heap allocations require a concrete allocation lease, not rediscovery of Java data.
            if(value.node!=null)throw new IllegalArgumentException("Native heap TEMP requires its actual allocation or placement");
            NativeProducer producer=create();try{producer.publish(value);return producer;}catch(Throwable failure){throw producer.closeAfterFailure(failure);}
        }
        private NativeProducer create(){
            Frame frame=body.packet.frame;int id=frame.diagnosticId++;
            Holder holder=frame.root(frame.evaluations,id,Purpose.PRODUCER_EVAL);
            NativeProducer producer=new NativeProducer(this,id,holder);holder.nativeProducer=producer;producers.push(producer);return producer;
        }
        public NativeProducer allocateArray()throws Throwable {
            active();try(var allocation=authority().arrays().allocate(true)){
                var assembly=allocation.restoration();return stage(allocation.binding(),assembly,allocation::close);
            }
        }
        public NativeProducer stageAllocation(PhpOwnershipObjects.Allocation allocation,PhpOwnershipArrays.RestorationAssembly assembly)throws Throwable {
            active();Objects.requireNonNull(allocation);return stage(allocation.binding(),assembly,allocation::close);
        }
        private NativeProducer stage(PhpOwnershipValues.Binding binding,PhpOwnershipArrays.RestorationAssembly assembly,PhpOwnership.Work closeLease)throws Throwable {
            active();Authority authority=authority();if(binding.domain()!=authority.domain||assembly==null||assembly.domain()!=authority.domain)
                throw new IllegalArgumentException("Native allocation and assembly have another origin");
            Memory raw=binding.rawValue();
            if(raw instanceof ArrayMemory){if(!assembly.matchesArray((ArrayMemory)raw))throw new IllegalArgumentException("Native ARRAY assembly differs from its allocation");}
            else if(raw instanceof ObjectMemory){if(!assembly.matchesTable(((ObjectMemory)raw).value.getProperties()))throw new IllegalArgumentException("Native OBJECT TABLE assembly differs from its allocation");}
            else throw new IllegalArgumentException("Native restoration allocation must be ARRAY or OBJECT");
            Value value=authority.value(raw,Role.VALUE);NativeProducer producer=create();producer.assembly=assembly;
            try{authority.domain.graph().deferDestruction(()->{producer.publish(value);closeLease.run();});return producer;}
            catch(Throwable failure){throw producer.closeAfterFailure(failure);}
        }
        private Memory select(NativeProducer producer){
            active();producer.open();if(producer.empty)throw new IllegalArgumentException("Empty native TEMP is not a result");
            selected=producer;selectedRaw=producer.valueResult();return selectedRaw;
        }
        private void transferResult(Memory actual,int evalId)throws Throwable {
            Frame frame=body.packet.frame;if(closed)throw new IllegalStateException("Native arena is closed");
            Value result=frame.authority.value(actual,Role.VALUE);
            if(selected==null){if(result.node!=null)throw new IllegalArgumentException("Native heap RESULT lacks its actual producer selection");
                try(Publication publication=frame.prepareEval(evalId,actual,Role.VALUE)){publication.storeDirect();publication.commit();}
            }else {
                selected.open();Value expected=frame.authority.value(selectedRaw,Role.VALUE);
                if(result.node!=expected.node||result.node==null&&!actual.identical(selectedRaw))throw new IllegalArgumentException("Native RESULT differs from its selected producer");
                Holder destination=frame.root(frame.evaluations,evalId,Purpose.PRODUCER_EVAL);
                frame.authority.domain.graph().deferDestruction(()->{
                    Holder source=selected.holder;
                    try(Publication publication=new Publication(destination,result,null,source.role==Role.VALUE?source:null)){
                        publication.storeDirect();publication.commit();
                    }
                    // CELL root -> by-value result retains payload first, then retires the CELL root.
                    selected.close();
                });
            }
            Throwable failure=closeAfterFailure(null);if(failure!=null)fail(failure);
        }
        private Throwable closeAfterFailure(Throwable primary){
            if(closed)return primary;closed=true;
            while(!producers.isEmpty())primary=producers.pop().closeAfterFailure(primary);return primary;
        }
    }
    /** Private-minted, actual native TEMP publisher. Wire IDs and Java parser objects own nothing. */
    public static final class NativeProducer implements PhpOwnershipHeap.Publisher,AutoCloseable {
        private final NativeArena arena;private final int id;private final Holder holder;
        private PhpOwnershipHeap.Snapshot snapshot;private PhpOwnershipArrays.RestorationAssembly assembly;
        private boolean empty=true,closed;
        private NativeProducer(NativeArena arena,int id,Holder holder){this.arena=arena;this.id=id;this.holder=holder;}
        private void open(){if(closed||arena.closed)throw new IllegalStateException("Native producer is closed");holder.open();
            if(arena.body.packet.frame.authority.currentFrame()!=arena.body.packet.frame)throw new IllegalStateException("Native producer belongs to another execution frame");}
        private void publishedAfterRaw(){empty=false;snapshot=null;}
        private void clearedAfterRaw(){empty=true;snapshot=null;}
        public PhpOwnershipDomain domain(){return holder.domain();}
        public PhpOwnershipHeap.Snapshot current(){open();if(snapshot==null)snapshot=new PhpOwnershipHeap.Snapshot(this,empty?null:bindingValue(),holder.revision);return snapshot;}
        private PhpOwnershipValues.Binding bindingValue(){return holder.role==Role.CELL?PhpOwnershipValues.bindCell(domain(),(ReferenceMemory)holder.raw,holder.edge.target()):PhpOwnershipValues.bindValue(domain(),holder.raw,holder.edge.target());}
        public PhpOwnershipValues.Binding binding(){return current().binding();}
        public Memory raw(){open();return holder.raw;}
        public Holder holder(){open();return holder;}
        public PhpOwnershipArrays.RestorationAssembly assembly(){arena.active();open();if(assembly==null)throw new IllegalStateException("Native TEMP has no restoration assembly");return assembly;}
        private void publish(Value value)throws Throwable {try(Publication publication=holder.prepare(value)){publication.storeDirect();publication.commit();}}
        public PhpOwnershipHeap.Publication prepare(PhpOwnershipHeap.Snapshot expected,PhpOwnershipValues.Binding next){
            arena.active();open();if(expected==null||expected!=current()||next.domain()!=domain())throw new IllegalArgumentException("Stale or foreign native TEMP publication");
            Publication publication=holder.prepare(holder.authority().value(next.rawValue(),next.role()==PhpOwnershipValues.Role.CELL?Role.CELL:Role.VALUE));
            return new PhpOwnershipHeap.Publication(){private boolean committed;public void commit()throws Throwable{publication.storeDirect();committed=true;publication.commit();}public void close()throws Exception{publication.close();}public boolean isCommitted(){return committed;}};
        }
        FacetSource requireRestorationSource(PhpOwnershipHeap.Snapshot expected,PhpOwnershipValues.Binding next){
            arena.active();open();if(expected==null||expected!=current()||empty)throw new IllegalArgumentException("Native restoration TEMP is empty or stale");
            return holder.requireFacetSource(domain(),next);
        }
        NativeDestination requireRestorationDestination(PhpOwnershipHeap.Snapshot expected){
            arena.active();open();if(expected==null||expected!=current()||!empty||holder.edge.target()!=null)
                throw new IllegalArgumentException("Native restoration cleanup needs its exact empty TEMP");
            return new NativeDestination(this,expected);
        }
        public ReferenceMemory promote()throws Throwable {
            arena.active();open();if(empty)throw new IllegalArgumentException("An empty native TEMP cannot become CELL");
            if(holder.role==Role.CELL)return (ReferenceMemory)holder.raw;
            try(var allocation=holder.authority().arrays().allocateCell(binding())) {
                Value next=holder.authority().value(allocation.cell(),Role.CELL);final ReferenceMemory cell=allocation.cell();
                domain().graph().deferDestruction(()->{publish(next);allocation.close();});return cell;
            }
        }
        private Memory valueResult(){
            Memory value=holder.raw;if(holder.role==Role.CELL){ReferenceMemory cell=(ReferenceMemory)value;var certificate=cell.getOwnershipCell();
                if(certificate==null)throw new IllegalArgumentException("Native CELL result requires its exact payload certificate");value=certificate.value().current().binding().rawValue();}
            return value.toImmutable();
        }
        public Memory publishResult(){return arena.select(this);}
        @Override public void close()throws Exception {
            if(closed)return;holder.authority.domain.currentContext();closed=true;snapshot=null;assembly=null;
            arena.body.packet.frame.evaluations.remove(id,holder);holder.close();
        }
        private Throwable closeAfterFailure(Throwable primary){try{close();}catch(Throwable cleanup){return domain().graph().composeFailure(primary,cleanup);}return primary;}
    }

    /** Exact reverse placement into an empty native cleanup TEMP, minted before the raw section. */
    static final class NativeDestination {
        private final NativeProducer producer;private final PhpOwnershipHeap.Snapshot before;private boolean consumed;
        private PhpOwnershipHeap.Snapshot after;private Memory afterRaw;private Role afterRole;private long afterRevision;
        private NativeDestination(NativeProducer producer,PhpOwnershipHeap.Snapshot before){this.producer=producer;this.before=before;}
        void validate(){producer.open();if(consumed||producer.current()!=before||!producer.empty)throw new IllegalArgumentException("Native cleanup TEMP changed after preparation");}
        PhpOwnership.Slot slot(){validate();return producer.holder.edge;}
        void prepareAfter(PhpOwnershipHeap.Snapshot after){
            validate();if(this.after!=null||after==null||after.publisher()!=producer||after.binding()==null||after.revision()!=before.revision()+1)
                throw new IllegalArgumentException("Native cleanup result snapshot has another origin");
            var binding=after.binding();if(binding.domain()!=producer.domain())throw new IllegalArgumentException("Foreign native cleanup value");
            this.after=after;afterRaw=binding.rawValue();afterRole=binding.role()==PhpOwnershipValues.Role.CELL?Role.CELL:Role.VALUE;afterRevision=after.revision();
        }
        void publishAfterRaw(PhpOwnershipHeap.Snapshot after){
            // All binding validation/getters ran before the graph/raw publication barrier.
            consumed=true;producer.holder.raw=afterRaw;producer.holder.role=afterRole;
            producer.holder.revision=afterRevision;producer.empty=false;producer.snapshot=this.after;
        }
    }

    public static final class Diagnostic implements AutoCloseable {
        private final Frame frame;private final int id;private final Holder holder;private boolean closed;
        private Diagnostic(Frame frame,int id,Holder holder){this.frame=frame;this.id=id;this.holder=holder;}
        public Holder holder(){if(closed)throw new IllegalStateException("Diagnostic EVAL was released");return holder;}
        @Override public void close() throws Exception{if(!closed){frame.releaseEval(id);closed=true;}}
    }

    public static final class CellOrigin {
        private final Authority authority;
        private final PhpOwnership.Node node;
        private final ReferenceMemory cell;
        private Holder payload;
        private CellOrigin(Authority authority,PhpOwnership.Node node,ReferenceMemory cell){this.authority=authority;this.node=node;this.cell=cell;}
        public Authority authority(){return releasingAuthority(authority);}
        public PhpOwnership.Node node(){authority.domain.currentContext();validate(authority);return node;}
        public ReferenceMemory cell(){return cell;}
        public Holder payload(){authority.domain.currentContext();validate(authority);return payload;}
        private void validate(Authority expected){
            if(expected.domain!=authority.domain||cell.getRuntimeCell()!=this||node.state()==PhpOwnership.State.DISPOSED)
                throw new IllegalArgumentException("CELL origin is foreign, stale or disposed");
        }
    }

    public static final class ArrayOrigin {
        private final Authority authority;
        private final PhpOwnership.Node node;
        private final Object identity;
        private final ArrayMemory layout;
        private ArrayOrigin(Authority authority,PhpOwnership.Node node,Object identity,ArrayMemory layout){this.authority=authority;this.node=node;this.identity=identity;this.layout=layout;}
        public Authority authority(){return releasingAuthority(authority);}
        public PhpOwnership.Node node(){return node;}
        public Object identity(){return identity;}
        public ArrayMemory layout(){return layout;}
        public void validate(ArrayMemory actual){
            if(actual.getStorageIdentity()!=identity||node.state()==PhpOwnership.State.DISPOSED)throw new IllegalArgumentException("ARRAY origin is stale or disposed");
        }
        /** ASSIGN_DIM resumes through its current CV only while the fetched backing still has an actual VALUE owner. */
        public boolean hasOwnersAfterDiagnostic(Holder diagnostic){
            authority.domain.currentContext();Objects.requireNonNull(diagnostic);diagnostic.open();Frame frame=authority().currentFrame();
            if(diagnostic.authority.domain!=authority.domain||diagnostic.purpose!=Purpose.DIAGNOSTIC
                    ||diagnostic.edge.owner()!=null||diagnostic.edge.target()!=node||diagnostic.role!=Role.VALUE
                    ||frame==null||!frame.evaluations.containsValue(diagnostic)||!(diagnostic.raw instanceof ArrayMemory))
                throw new IllegalArgumentException("Storage survival needs its exact live diagnostic retention");
            validate((ArrayMemory)diagnostic.raw);return actualOwners(diagnostic).remaining()>0;
        }
        public ArrayMemory writableLayoutAfterDiagnostic(){return writableLayoutAfterDiagnostic(null);}
        public ArrayMemory writableLayoutAfterDiagnostic(Holder diagnostic){
            authority.domain.currentContext();
            if(diagnostic!=null){
                diagnostic.open();Frame frame=authority().currentFrame();
                if(diagnostic.authority.domain!=authority.domain||diagnostic.purpose!=Purpose.DIAGNOSTIC
                        ||diagnostic.edge.owner()!=null||diagnostic.edge.target()!=node||diagnostic.role!=Role.VALUE
                        ||frame==null||!frame.evaluations.containsValue(diagnostic)||!(diagnostic.raw instanceof ArrayMemory))
                    throw new IllegalArgumentException("Storage exclusion needs its exact live diagnostic retention");
                validate((ArrayMemory)diagnostic.raw);
            }
            var owners=actualOwners(diagnostic);return owners.remaining()==1?owners.sole():null;
        }
        private record StorageOwners(long remaining,ArrayMemory sole) {}
        /** Borrowed certified facets describe existing physical edges and never add a retention root. */
        private StorageOwners actualOwners(Holder diagnostic){
            long known=0,remaining=0;ArrayMemory sole=null;Set<PhpOwnership.Slot> seen=Collections.newSetFromMap(new IdentityHashMap<>());
            for(Holder holder:authority.holders)if(!holder.closed&&!holder.edge.isClosed()&&holder.edge.target()==node){
                if(!seen.add(holder.edge))throw new IllegalStateException("Storage has duplicate raw holder facades");known++;
                if(holder.role!=Role.VALUE||!(holder.raw instanceof ArrayMemory))throw new IllegalStateException("Storage owner has no actual ARRAY value");
                ArrayMemory actual=(ArrayMemory)holder.raw;validate(actual);if(holder!=diagnostic){remaining++;sole=actual;}
            }
            for(var facet:authority.shared.facets.keySet())if(!facet.isDisposed()){
                var edge=facet.borrowedSlot();if(edge.isClosed()||edge.target()!=node)continue;
                var snapshot=facet.current();var binding=snapshot.binding();
                if(binding.role()!=PhpOwnershipValues.Role.VALUE||binding.node()!=node||!(binding.rawValue() instanceof ArrayMemory))throw new IllegalStateException("Storage facet has no actual ARRAY value");
                ArrayMemory actual=(ArrayMemory)binding.rawValue();validate(actual);
                if(seen.add(edge)){known++;remaining++;sole=actual;}
            }
            if(known!=node.incoming())throw new IllegalStateException("Storage has an owner without an actual raw holder facade");
            return new StorageOwners(remaining,sole);
        }
    }

    public static final class Returned implements AutoCloseable {
        private final Holder holder;private Returned(Holder holder){this.holder=holder;}
        public Memory raw(){return holder.raw();}public Holder holder(){return holder;}
        @Override public void close()throws Exception{holder.close();}
    }
    private static final class Invocation {
        private final Target target;private final Frame frame;private final php.runtime.lang.IObject receiver;private final CallPacket packet;private FunctionPacket functionPacket;private PhpOwnershipObjects.ExecutionEntry lifecycleEntry;private boolean entered;
        private Invocation(Target target,Frame frame){this(target,frame,null,null);}
        private Invocation(Target target,Frame frame,php.runtime.lang.IObject receiver){this(target,frame,receiver,null);}
        private Invocation(Target target,Frame frame,php.runtime.lang.IObject receiver,CallPacket packet){this.target=target;this.frame=frame;this.receiver=receiver;this.packet=packet;}
        private Holder argument(int index){if(functionPacket!=null)return index==0?functionPacket.send:null;return packet==null?frame.evaluations.get(-1-index):index>=0&&index<packet.sent?packet.sends[index]:null;}
    }
    private static final class Target {
        private final Object entity;private final java.lang.reflect.Method member;
        private final byte[] bytes;private final PhpOwnershipExecution.FramePlan plan;private final RuntimePlan runtimePlan;
        private final Map<Integer,NativeSite> nativeSites=new HashMap<>();
        private Target(Object entity,java.lang.reflect.Method member,byte[] bytes,PhpOwnershipExecution.FramePlan plan,RuntimePlan runtimePlan){this.entity=entity;this.member=member;this.bytes=bytes;this.plan=plan;this.runtimePlan=runtimePlan;}
        private void validate(Authority authority){validate(authority,Collections.newSetFromMap(new IdentityHashMap<>()));}
        private void validate(Authority authority,Set<Target> path){
            if(!path.add(this))throw new IllegalArgumentException("Recursive CALL admission needs its complete protocol");
            if(entity instanceof php.runtime.loader.dump.PhpAttributeDumper.ArgumentSource source)source.validate(authority.environment);
            java.lang.reflect.Method current=entity instanceof php.runtime.loader.dump.PhpAttributeDumper.ArgumentSource source?source.member():entity instanceof php.runtime.reflection.FunctionEntity?((php.runtime.reflection.FunctionEntity)entity).getNativeMethod():entity instanceof php.runtime.reflection.MethodEntity?((php.runtime.reflection.MethodEntity)entity).getNativeMethod():((php.runtime.reflection.ModuleEntity)entity).getNativeMethod();
            if(current!=member||!((php.runtime.loader.RuntimeClassLoader)member.getDeclaringClass().getClassLoader()).matchesDefinedBytecode(member.getDeclaringClass(),bytes))
                throw new IllegalArgumentException("Function capability changed before invocation");
            authority.validateNativeDependencies(runtimePlan);
            for(NativeMember nativeMember:runtimePlan.nativeMembers()) {
                var semantic=authority.nativeMember(nativeMember.owner(),nativeMember.name(),nativeMember.descriptor());
                var proof=php.runtime.invoke.PhpNativeResultUse.require(semantic,nativeMember.physicalOwner(),nativeMember.physicalName(),nativeMember.physicalDescriptor(),nativeMember.discardedResult());
                NativeSite previous=nativeSites.get(nativeMember.id());
                if(previous==null)nativeSites.put(nativeMember.id(),new NativeSite(nativeMember,semantic,proof));
                else if(!previous.semantic().equals(semantic)||previous.proof()!=proof)throw new IllegalArgumentException("Native physical result site changed after admission");
            }
            if(!runtimePlan.objectCasts().isEmpty())authority.stdClassPlan();
            boolean comparisons=runtimePlan.nativeMembers().stream().anyMatch(nativeMember->nativeMember.owner().equals("php/runtime/PhpComparisonCalls"));
            for(ObjectNew site:runtimePlan.objectNews()) {
                var objectPlan=authority.literalObjectPlan(this,site);
                if(comparisons)PhpComparisonCalls.validateClass(objectPlan.entity());
            }
            for(PropertyReference site:runtimePlan.propertyReferences())authority.literalProperty(this,site);
            for(ClosureLiteral literal:runtimePlan.literals()) {
                var entity=authority.environment.__getClosure(literal.module(),literal.index());
                if(!(entity instanceof php.runtime.reflection.helper.ClosureEntity)||!entity.getInternalName().equals(literal.closure()))throw new IllegalArgumentException("Closure literal source no longer identifies its module entity");
                authority.lexicalProvider((php.runtime.reflection.helper.ClosureEntity)entity);
            }
            for(ArrayEntrySite site:runtimePlan.arrayEntries())if(plan.locals().stream().noneMatch(l->l.id()==site.local()&&l.storage()==PhpOwnershipExecution.Storage.ADDRESS))throw new IllegalArgumentException("ARRAY ENTRY target has no declared addressable CV");
            boolean handlerNative=runtimePlan.nativeMembers().stream().anyMatch(n->n.owner().equals("php/runtime/ext/core/LangFunctions")&&Set.of("set_error_handler","restore_error_handler","trigger_error").contains(n.name()));
            if(handlerNative)authority.environment.requireRuntimeErrorHandlerState(authority);
            for(HandlerSite handler:runtimePlan.handlers()) {
                Target dependency=authority.handlerDependency(handler);authority.requireHandlerParameters(dependency);dependency.validate(authority,path);
            }
            for(FunctionCall site:runtimePlan.functionCalls())authority.functionDependency(site).validate(authority,path);
            for(CallSite call:runtimePlan.calls()) {
                Target dependency=authority.targets.values().stream().filter(target->target.entity instanceof php.runtime.reflection.MethodEntity&&((php.runtime.reflection.MethodEntity)target.entity).getClazz() instanceof php.runtime.reflection.helper.ClosureEntity&&target.member.getDeclaringClass().getName().replace('.','/').equals(call.closure())).findFirst().orElseThrow(()->new IllegalArgumentException("CALL dependency has no registered exact lexical target"));
                if(dependency.plan.entryKind()!=PhpOwnershipExecution.EntryKind.LEXICAL_CLOSURE)throw new IllegalArgumentException("CALL dependency is not a lexical invocation");
                if(call.requiresCellResult()&&!((php.runtime.reflection.MethodEntity)dependency.entity).isReturnReference())throw new IllegalArgumentException("By-reference VALUE CALL result needs its warning protocol before invocation");dependency.validate(authority,path);
            }
            if(plan.requiredCapabilities().contains("literal-buffered-output"))PhpOwnershipNativeCalls.requireLiteralBufferedOutput(authority.environment);
            for(String required:plan.requiredCapabilities())if(required.startsWith("function-call:")&&!authority.exactNativeDependency(runtimePlan,required)&&!authority.exactFunctionDependency(runtimePlan,required))throw new IllegalArgumentException("Native dependency resolution changed before invocation");
            path.remove(this);
        }
    }
    @SuppressWarnings("unchecked") private static <T,E extends Throwable>T fail(Throwable failure)throws E{throw (E)failure;}
}
