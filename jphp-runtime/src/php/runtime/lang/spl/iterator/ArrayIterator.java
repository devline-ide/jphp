package php.runtime.lang.spl.iterator;

import php.runtime.Memory;
import php.runtime.PhpComparison;
import php.runtime.common.HintType;
import php.runtime.common.NaturalOrderComparator;
import php.runtime.common.PhpClassNames;
import php.runtime.env.Environment;
import php.runtime.env.TraceInfo;
import php.runtime.exceptions.support.ErrorType;
import php.runtime.invoke.Invoker;
import php.runtime.lang.BaseObject;
import php.runtime.lang.ForeachIterator;
import php.runtime.lang.IObject;
import php.runtime.lang.exception.BaseError;
import php.runtime.lang.exception.BaseTypeError;
import php.runtime.lang.spl.ArrayAccess;
import php.runtime.lang.spl.Countable;
import php.runtime.lang.spl.Serializable;
import php.runtime.lang.spl.exception.InvalidArgumentException;
import php.runtime.lang.spl.exception.OutOfBoundsException;
import php.runtime.lang.spl.exception.UnexpectedValueException;
import php.runtime.lang.support.ICloneableObject;
import php.runtime.memory.*;
import php.runtime.memory.output.serialization.Deserializer;
import php.runtime.memory.output.serialization.Serializer;
import php.runtime.reflection.ClassEntity;
import php.runtime.reflection.MethodEntity;
import php.runtime.reflection.support.PhpTypeDeclaration;
import java.util.List;
import java.util.ArrayList;
import java.util.HashMap;
import static php.runtime.annotation.Reflection.*;

/** SPL's mutable storage iterator. Cursor state belongs to the iterator, not its backing array. */
@Name("ArrayIterator")
public class ArrayIterator extends BaseObject implements SeekableIterator, ArrayAccess, Countable, Serializable,
        ICloneableObject<ArrayIterator>, SplArrayAccess {
    public static final int STD_PROP_LIST = 1;
    public static final int ARRAY_AS_PROPS = 2;
    private static final class Storage {
        ArrayMemory array = new ArrayMemory();
        Memory object = Memory.NULL;
        int sorting;
        ArrayList<Object> keys;
    }
    @Ignore public static PhpTypeDeclaration tentativeReturnType(MethodEntity method) {
        java.lang.reflect.Method nativeMethod = method.getNativeMethod();
        if (nativeMethod == null || nativeMethod.getDeclaringClass() != ArrayIterator.class) return null;
        String name = nativeMethod.getName();
        String type = switch (name) {
            case "__construct" -> null;
            case "offsetExists", "valid" -> "bool";
            case "offsetGet", "current" -> "mixed";
            case "getArrayCopy", "__serialize", "__debugInfo" -> "array";
            case "count", "getFlags" -> "int";
            case "asort", "ksort", "uasort", "uksort", "natsort", "natcasesort" -> "true";
            case "serialize" -> "string";
            case "key" -> "string|int|null";
            default -> "void";
        };
        if (type == null) return null;
        ArrayList<List<String>> alternatives = new ArrayList<>();
        for (String atom : type.split("\\|")) alternatives.add(List.of(atom));
        return new PhpTypeDeclaration(alternatives);
    }

    private Storage storage = new Storage();
    private ArrayObject aggregateOwner;
    private ArrayIterator storageOwner;
    private Storage backingStorage() { return aggregateOwner != null ? aggregateOwner.nativeBacking().backingStorage() : storageOwner != null ? storageOwner.backingStorage() : storage; }
    private long flags;
    private int position;

    public ArrayIterator(Environment env) { super(env); }
    public ArrayIterator(Environment env, ClassEntity clazz) { super(env, clazz); }

    private void writable(Environment env) {
        if (backingStorage().sorting != 0) env.exception(BaseError.class, "Modification of ArrayObject during sorting is prohibited");
    }
    private void backing(Environment env, Memory raw, String method) {
        Memory value=raw.toValue();
        if(value instanceof ObjectMemory date && date.value instanceof php.runtime.lang.support.IPhpNativeDateView && PhpClassNames.isModern(env))
            env.error(env.trace()==null?TraceInfo.UNKNOWN:env.trace(),ErrorType.E_DEPRECATED,
                    "ArrayIterator::%s(): Using an object as a backing array for ArrayIterator is deprecated, as it allows violating class constraints and invariants",method);
        rejectDateBacking(env,value,"ArrayIterator");
        aggregateOwner = null; storageOwner = null;
        if (value.isArray()) {
            Storage fresh = new Storage(); fresh.array = value.toValue(ArrayMemory.class).duplicate(); storage = fresh;
        } else if (value.isObject()) {
            if (PhpClassNames.isModern(env)) env.error(env.trace() == null ? TraceInfo.UNKNOWN : env.trace(), ErrorType.E_DEPRECATED,
                    "ArrayIterator::%s(): Using an object as a backing array for ArrayIterator is deprecated, as it allows violating class constraints and invariants", method);
            IObject object = value.toValue(ObjectMemory.class).value;
            if (object instanceof ArrayObject) { requireAcyclicBacking(((ArrayObject)object).nativeBacking()); aggregateOwner = (ArrayObject)object; storage = aggregateOwner.nativeBacking().backingStorage(); }
            else if (object instanceof ArrayIterator) storage = ((ArrayIterator)object).backingStorage();
            else { Storage fresh = new Storage(); fresh.object = value; fresh.array = object.getProperties(); storage = fresh; }
        } else env.exception(InvalidArgumentException.class, "Passed variable is not an array or object");
        position = 0;
    }
    private static void rejectDateBacking(Environment env,Memory raw,String container) {
        if(raw instanceof ObjectMemory object && object.value instanceof php.runtime.lang.support.IPhpNativeDateView)
            env.exception(InvalidArgumentException.class,"Overloaded object of type %s is not compatible with %s",object.value.getReflection().getName(),container);
    }
    private void requireAcyclicBacking(ArrayIterator owner) {
        var visited=new java.util.IdentityHashMap<ArrayIterator,Boolean>();
        for(ArrayIterator current=owner;current!=null;current=current.aggregateOwner!=null?current.aggregateOwner.nativeBacking():current.storageOwner) {
            if(current==this || visited.put(current,Boolean.TRUE)!=null)
                throw new IllegalArgumentException("Circular SPL backing is not supported");
        }
    }
    @Ignore public void nativeWritable(Environment env) { writable(env); }
    @Ignore public void nativeAttachAggregate(ArrayObject owner, long flags) { requireAcyclicBacking(owner.nativeBacking());aggregateOwner=owner;storageOwner=null;this.flags=flags;position=0; }
    @Ignore public Memory nativeObjectBacking() { return aggregateOwner!=null ? ObjectMemory.valueOf(aggregateOwner) : storageOwner!=null ? ObjectMemory.valueOf(storageOwner) : storage.object; }
    @Ignore public ArrayMemory nativeDetachedCopy() {
        ArrayMemory result=new ArrayMemory();var entries=backingStorage().array.foreachIterator(false,false);
        while(entries.next())if(!entries.getValue().isUndefined()) {
            Memory value=entries.getValue();result.insertBucket(entries.getKey(),value.isReference()?value:value.toImmutable());
        }
        return result;
    }
    @Ignore public void nativeCloneBackingFrom(Environment env,ArrayIterator original) {
        writable(env);Storage detached=new Storage();detached.array=original.nativeDetachedCopy();
        aggregateOwner=null;storageOwner=null;storage=detached;position=0;
    }
    @Ignore public void nativeRestoreBacking(Environment env,ArrayMemory restored) {
        writable(env);Storage next=new Storage();next.array=restored;
        aggregateOwner=null;storageOwner=null;storage=next;position=0;
    }
    @Ignore public void nativeReplaceBacking(Environment env,Memory raw,ArrayMemory selfProperties) {
        writable(env);Memory value=raw.toValue();rejectDateBacking(env,value,"ArrayObject");aggregateOwner=null;storageOwner=null;Storage next=new Storage();
        if(selfProperties!=null){next.array=selfProperties;next.object=raw;}
        else if(value.isArray()) {
            var entries=value.toValue(ArrayMemory.class).foreachIterator(false,false);
            while(entries.next()){Memory entry=entries.getValue();next.array.insertBucket(entries.getKey(),entry.isReference()?entry:entry.toImmutable());}
        } else if(value.isObject()) {
            var object=value.toValue(ObjectMemory.class).value;
            if(object instanceof ArrayObject){requireAcyclicBacking(((ArrayObject)object).nativeBacking());aggregateOwner=(ArrayObject)object;position=0;return;}
            if(object instanceof ArrayIterator){requireAcyclicBacking((ArrayIterator)object);storageOwner=(ArrayIterator)object;position=0;return;}
            next.array=object.getProperties();next.object=value;
        } else throw new IllegalArgumentException("ArrayObject backing requires array or object");
        storage=next;position=0;
    }
    private ArrayList<Object> keys(boolean all) {
        // ARRAY storage has no external structural publisher. Clones share this cache.
        // OBJECT properties may change independently and require a live public-key scan.
        if (backingStorage().object.isNull() && backingStorage().keys != null) return backingStorage().keys;
        ArrayList<Object> result = new ArrayList<>();
        ForeachIterator iterator = backingStorage().array.foreachIterator(false, false);
        while (iterator.next()) {
            String key = iterator.getKey().toString();
            if (!iterator.getValue().isUndefined() && (all || backingStorage().object.isNull() || key.indexOf('\0') < 0)) result.add(iterator.getKey());
        }
        if (backingStorage().object.isNull()) backingStorage().keys = result;
        return result;
    }
    private Object cursor() { ArrayList<Object> keys = keys(false); return position < keys.size() ? keys.get(position) : null; }
    private static Memory keyMemory(Object key) { return key == null ? Memory.NULL : key instanceof Memory ? (Memory)key : StringMemory.valueOf(key.toString()); }
    private Object offset(Environment env, Memory raw, String operation) {
        Memory value = raw.toValue();
        if (value.isArray() || value.isObject()) {
            String type = value.isArray() ? "array" : value.toValue(ObjectMemory.class).getReflection().getName();
            String message = operation.equals("exists") ? "Cannot access offset of type %s in isset or empty"
                    : operation.equals("unset") ? "Cannot unset offset of type %s on ArrayIterator" : "Cannot access offset of type %s on ArrayIterator";
            env.exception(BaseTypeError.class, message, type);
        }
        return ArrayMemory.toKey(value);
    }
    private Memory missing(Environment env, Object key) {
        env.error(env.trace() == null ? TraceInfo.UNKNOWN : env.trace(), ErrorType.E_WARNING, "Undefined array key %s", key instanceof LongMemory ? key.toString() : "\"" + key + "\"");
        return Memory.NULL;
    }
    @Ignore public boolean arrayAsProperties() { return (flags & ARRAY_AS_PROPS) != 0; }
    @Ignore public Memory propertyGet(Environment env, String key) { return offsetGet(env, StringMemory.valueOf(key)); }
    @Ignore public Memory propertySet(Environment env, String key, Memory value) { return offsetSet(env, StringMemory.valueOf(key), value); }
    @Ignore public Memory propertyUnset(Environment env, String key) { return offsetUnset(env, StringMemory.valueOf(key)); }
    @Ignore public Memory propertyIsset(Environment env, String key) {
        ReferenceMemory value = backingStorage().array.getByScalar(key); return value != null && !value.isNull() && !value.isUndefined() ? Memory.TRUE : Memory.NULL;
    }

    @Signature({@Arg(value="array", optional=@Optional(value="", type=HintType.ARRAY)), @Arg(value="flags", type=HintType.INT, optional=@Optional(value="0", type=HintType.INT))})
    public Memory __construct(Environment env, Memory... args) {
        writable(env);
        Memory value = args.length == 0 ? new ArrayMemory() : args[0].toValue();
        if (!value.isArray() && !value.isObject()) {
            String type = value.isNull() ? "null" : value.type == Memory.Type.BOOL ? value.toBoolean() ? "true" : "false" : value.isString() ? "string" : value.type == Memory.Type.DOUBLE ? "float" : "int";
            env.exception(BaseTypeError.class, "ArrayIterator::__construct(): Argument #1 ($array) must be of type array, %s given", type);
        }
        backing(env, value, "__construct"); flags = args.length > 1 ? args[1].toLong() : 0; return Memory.NULL;
    }
    @Signature public Memory current(Environment env, Memory... args) {
        Object key = cursor(); return key == null ? Memory.NULL : backingStorage().array.getByScalar(key).toValue();
    }
    @Signature public Memory key(Environment env, Memory... args) { return keyMemory(cursor()); }
    @Signature public Memory valid(Environment env, Memory... args) { return cursor() == null ? Memory.FALSE : Memory.TRUE; }
    @Signature public Memory next(Environment env, Memory... args) { if (cursor() != null) position++; return Memory.NULL; }
    @Signature public Memory rewind(Environment env, Memory... args) { position = 0; return Memory.NULL; }
    @Signature(@Arg(value="offset",type=HintType.INT)) public Memory seek(Environment env, Memory... args) {
        long requested = args[0].toLong();
        if (requested < 0) env.exception(OutOfBoundsException.class, "Seek position %s is out of range", requested);
        position = (int)Math.min(requested, keys(false).size());
        if (requested >= keys(false).size()) env.exception(OutOfBoundsException.class, "Seek position %s is out of range", requested);
        return Memory.NULL;
    }
    @Signature public Memory count(Environment env, Memory... args) { return LongMemory.valueOf(keys(false).size()); }
    @Signature public Memory getFlags(Environment env, Memory... args) { return LongMemory.valueOf(flags); }
    @Signature(@Arg(value="flags",type=HintType.INT)) public Memory setFlags(Environment env, Memory... args) { flags = args[0].toLong(); return Memory.NULL; }
    @Signature public Memory getArrayCopy(Environment env, Memory... args) {
        if (!backingStorage().object.isNull() && PhpClassNames.isModern(env)) {
            ArrayMemory copy = new ArrayMemory();
            ForeachIterator entries = backingStorage().array.foreachIterator(false, false);
            while (entries.next()) {
                Memory value = entries.getValue();
                if (!value.isUndefined()) copy.insertBucket(entries.getKey(), value.isReference() ? value : value.toImmutable());
            }
            return copy;
        }
        return backingStorage().array.duplicate();
    }
    @Signature(@Arg("key")) public Memory offsetExists(Environment env, Memory... args) {
        ReferenceMemory value = backingStorage().array.getByScalar(offset(env,args[0],"exists")); return value != null && !value.isUndefined() ? Memory.TRUE : Memory.FALSE;
    }
    @Signature(@Arg("key")) public Memory offsetGet(Environment env, Memory... args) {
        Object key = offset(env,args[0],"get"); ReferenceMemory value = backingStorage().array.getByScalar(key);
        return value == null || value.isUndefined() ? missing(env,key) : value;
    }
    @Ignore public Memory offsetAddress(Environment env, Memory key) {
        writable(env);
        Object normalized = key.isNull() ? null : offset(env,key,"get");
        boolean fresh = normalized == null || backingStorage().array.getByScalar(normalized) == null;
        ReferenceMemory result = normalized == null ? (ReferenceMemory) backingStorage().array.refOfPush(env.trace())
                : backingStorage().array.getByScalarOrCreate(normalized, Memory.NULL);
        if (fresh && backingStorage().keys != null) backingStorage().keys.add(normalized == null ? ArrayMemory.toKey(backingStorage().array.peekKey()) : normalized);
        return result;
    }
    @Ignore public Memory offsetIsset(Environment env, Memory key) {
        ReferenceMemory value = backingStorage().array.getByScalar(offset(env,key,"exists"));
        return value == null || value.isNull() || value.isUndefined() ? Memory.NULL : Memory.TRUE;
    }
    @Signature({@Arg("key"),@Arg("value")}) public Memory offsetSet(Environment env, Memory... args) {
        writable(env);
        Object key = args[0].isNull() ? null : offset(env,args[0],"set");
        boolean fresh = key == null || backingStorage().array.getByScalar(key) == null;
        if (key == null) backingStorage().array.add(args[1].toImmutable());
        else backingStorage().array.put(key, args[1].toImmutable());
        if (fresh && backingStorage().keys != null) backingStorage().keys.add(key == null ? ArrayMemory.toKey(backingStorage().array.peekKey()) : key);
        return Memory.NULL;
    }
    @Signature(@Arg("key")) public Memory offsetUnset(Environment env, Memory... args) {
        writable(env); Object key = offset(env,args[0],"unset"); int index = keys(false).indexOf(key);
        backingStorage().array.remove(keyMemory(key));
        if (backingStorage().keys != null && index >= 0) backingStorage().keys.remove(index);
        if (index >= 0 && index < position) position--; return Memory.NULL;
    }
    @Signature(@Arg("value")) public Memory append(Environment env, Memory... args) { return offsetSet(env,Memory.NULL,args[0]); }

    private Memory sort(Environment env, Memory argument, boolean byKey, int mode, boolean callback) throws Throwable {
        ArrayList<Object> ordered = new ArrayList<>(keys(true)); HashMap<Object,ReferenceMemory> values = new HashMap<>();
        for (Object key:ordered) values.put(key,backingStorage().array.getByScalar(key));
        php.runtime.invoke.PhpCallFrame invoker = null;
        if (callback) {
            try { invoker = php.runtime.invoke.PhpCallFrame.acquireInternalRetainedCallable(argument, env, env.trace()); }
            catch (php.runtime.invoke.PhpMethodResolver.InvalidCallback failure) {
                env.exception(BaseTypeError.class, "%s(): Argument #2 ($callback) must be a valid callback, %s",
                        byKey ? "uksort" : "uasort", failure.getMessage());
            }
        }
        final php.runtime.invoke.PhpCallFrame selected = invoker;
        final boolean[] warnedBoolean = {false};
        backingStorage().sorting++;
        try {
            Throwable comparisonFailure = null;
            try {
            php.runtime.common.PhpArraySort.sort(ordered, (a,b) -> {
                Memory left = byKey ? keyMemory(a) : values.get(a).toValue(); Memory right = byKey ? keyMemory(b) : values.get(b).toValue();
                try {
                    if (selected != null) {
                        Memory comparison = selected.invokeInternal(new Memory[]{left,right}).toValue();
                        if (comparison.type == Memory.Type.BOOL) {
                            if (!warnedBoolean[0]) {
                                warnedBoolean[0] = true;
                                env.error(env.trace() == null ? TraceInfo.UNKNOWN : env.trace(), ErrorType.E_DEPRECATED,
                                        "%s(): Returning bool from comparison function is deprecated, return an integer less than, equal to, or greater than zero",
                                        byKey ? "uksort" : "uasort");
                            }
                            if (!comparison.toBoolean())
                                return -Long.compare(selected.invokeInternal(new Memory[]{right,left}).toLong(), 0);
                        }
                        return Long.compare(comparison.toLong(), 0);
                    }
                    if ((mode & ~8) == 1) return Double.compare(left.toDouble(),right.toDouble());
                    if ((mode & ~8) == 6) return new NaturalOrderComparator((mode & 8)!=0,false).compare(left.toString(),right.toString());
                    if ((mode & ~8) == 2 || (mode & ~8) == 5) return (mode & 8)!=0 ? left.toString().compareToIgnoreCase(right.toString()) : left.toString().compareTo(right.toString());
                    return (int)PhpComparison.spaceshipCompare(left,right,env,env.trace());
                } catch (Throwable failure) { return ArrayIterator.<RuntimeException,Integer>raise(failure); }
            });
            } catch (Throwable failure) { comparisonFailure = failure; }
            if (backingStorage().object.isNull()) {
                ArrayMemory array = new ArrayMemory();
                for (Object key:ordered) array.put(key,values.get(key).getValue()); backingStorage().array = array; backingStorage().keys = ordered;
            } else {
                ForeachIterator hidden = backingStorage().array.foreachIterator(false, false);
                while (hidden.next()) if (hidden.getValue().isUndefined()) ordered.add(hidden.getKey());
                backingStorage().array.reorderKeys(ordered);
            }
            if (comparisonFailure != null) throw comparisonFailure;
            return Memory.TRUE;
        } finally { backingStorage().sorting--; }
    }
    private static <T extends Throwable,R> R raise(Throwable failure) throws T { throw (T)failure; }
    @Signature(@Arg(value="flags",type=HintType.INT,optional=@Optional(value="0",type=HintType.INT))) public Memory asort(Environment env, Memory... args) throws Throwable {return sort(env,Memory.NULL,false,args[0].toInteger(),false);}
    @Signature(@Arg(value="flags",type=HintType.INT,optional=@Optional(value="0",type=HintType.INT))) public Memory ksort(Environment env, Memory... args) throws Throwable {return sort(env,Memory.NULL,true,args[0].toInteger(),false);}
    @Signature public Memory natsort(Environment env, Memory... args) throws Throwable {return sort(env,Memory.NULL,false,6,false);}
    @Signature public Memory natcasesort(Environment env, Memory... args) throws Throwable {return sort(env,Memory.NULL,false,14,false);}
    @Signature(@Arg(value="callback",type=HintType.CALLABLE)) public Memory uasort(Environment env, Memory... args) throws Throwable {return sort(env,args[0],false,0,true);}
    @Signature(@Arg(value="callback",type=HintType.CALLABLE)) public Memory uksort(Environment env, Memory... args) throws Throwable {return sort(env,args[0],true,0,true);}

    @Signature public Memory __debugInfo(Environment env, Memory... args) {
        ArrayMemory result = getProperties().duplicate(); result.put("\0ArrayIterator\0storage", backingStorage().object.isNull() ? getArrayCopy(env) : backingStorage().object); return result;
    }
    @Signature public Memory __serialize(Environment env, Memory... args) {
        ArrayMemory result = new ArrayMemory(); result.add(LongMemory.valueOf(flags)); result.add(backingStorage().object.isNull() ? getArrayCopy(env) : backingStorage().object);
        result.add(getProperties().duplicate()); result.add(Memory.NULL); return result;
    }
    @Signature(@Arg(value="data",type=HintType.ARRAY)) public Memory __unserialize(Environment env, Memory... args) {
        writable(env); Memory data = args[0].toValue();
        if (!data.isArray()) env.exception(UnexpectedValueException.class,"Incomplete or ill-typed serialization data");
        ArrayMemory values = data.toValue(ArrayMemory.class);
        Memory f = values.valueOfIndex(env.trace(),0).toValue(), a = values.valueOfIndex(env.trace(),1).toValue(), p = values.valueOfIndex(env.trace(),2).toValue();
        if (values.size() < 3 || f.type != Memory.Type.INT || !p.isArray()) env.exception(UnexpectedValueException.class,"Incomplete or ill-typed serialization data");
        backing(env,a,"__unserialize"); flags=f.toLong(); restoreProperties(env,p.toValue(ArrayMemory.class)); return Memory.NULL;
    }
    private void restoreProperties(Environment env, ArrayMemory properties) {
        getProperties().clear(); ForeachIterator iterator=properties.foreachIterator(false,false);
        while(iterator.next()) {
            String key=iterator.getKey().toString(); getReflection().checkDynamicPropertyRestoration(env,env.trace(),getProperties(),key);
            getProperties().put(key,iterator.getValue().toImmutable());
        }
    }
    private String wire(Environment env,Memory value) {StringBuilder buffer=new StringBuilder();new Serializer(env,env.trace(),buffer).write(value);return buffer.toString();}
    @Signature public Memory serialize(Environment env, Memory... args) {
        return StringMemory.valueOf("x:"+wire(env,LongMemory.valueOf(flags))+wire(env,backingStorage().object.isNull()?getArrayCopy(env):backingStorage().object)+";m:"+wire(env,getProperties()));
    }
    private static final class Parser extends Deserializer {
        Parser(Environment env){super(env,env.trace());}
        int position(){return pos;}
        @Override protected Memory error(int offset,int length){throw new IllegalArgumentException(Integer.toString(offset));}
    }
    @Signature(@Arg(value="data",type=HintType.STRING)) public Memory unserialize(Environment env, Memory... args) {
        writable(env);String data=args[0].toString(); if(data.isEmpty())return Memory.NULL; int offset=0;
        try {
            if(!data.startsWith("x:"))throw new IllegalArgumentException(); Parser parser=new Parser(env);
            Memory f=parser.read(data,2);offset=parser.position(); if(f.type != Memory.Type.INT)throw new IllegalArgumentException();
            Memory a=parser.read(data,offset);offset=parser.position(); if(!a.isArray()&&!a.isObject()){offset=6;throw new IllegalArgumentException();}
            backing(env,a,"unserialize");flags=f.toLong();
            if(!data.startsWith(";m:",offset))throw new IllegalArgumentException();offset+=3;
            Memory p=parser.read(data,offset);offset=parser.position();if(!p.isArray())throw new IllegalArgumentException();
            restoreProperties(env,p.toValue(ArrayMemory.class));
        }catch(IllegalArgumentException failure){if(failure.getMessage()!=null)try{offset=Integer.parseInt(failure.getMessage());}catch(NumberFormatException ignored){} env.exception(UnexpectedValueException.class,"Error at offset %s of %s bytes",offset,data.length());}
        return Memory.NULL;
    }
    @Ignore @Override public ArrayIterator __clone(Environment env, TraceInfo trace) {
        ArrayIterator copy=getReflection().newObjectWithoutConstruct(env); copy.storage=storage;copy.aggregateOwner=aggregateOwner;copy.storageOwner=storageOwner;copy.flags=flags;copy.position=0;copy.getProperties().putAll(getProperties());return copy;
    }
    @Ignore @Override public ForeachIterator getNewIterator(Environment env) {return internalIterator(env,false,false);}
    @Ignore @Override public ForeachIterator getNewIterator(Environment env, boolean refs, boolean keyRefs) {return internalIterator(env,refs,keyRefs);}
    @Ignore public ForeachIterator internalIterator(Environment env, boolean references, boolean keyReferences) {
        if(references && getReflection().findMethod("current").getClazz().getNativeClazz()!=ArrayIterator.class)
            env.exception(BaseError.class,"An iterator cannot be used with foreach by reference");
        return new ForeachIterator(references,keyReferences,false) {
            boolean advance;
            protected boolean init(){env.invokeMethodNoThrow(ArrayIterator.this,"rewind");advance=false;return true;}
            protected boolean nextValue(){return move(true);}
            @Override public boolean next(boolean fetchValue) {
                if(fetchValue)return super.next();
                currentKeyMemory=null;
                if(!init){init=true;if(!init())return false;}
                return move(false);
            }
            private boolean move(boolean fetchValue){
                if(advance)env.invokeMethodNoThrow(ArrayIterator.this,"next");advance=true;
                if(!env.invokeMethodNoThrow(ArrayIterator.this,"valid").toBoolean())return false;
                if(fetchValue){
                    currentKey=env.invokeMethodNoThrow(ArrayIterator.this,"key");
                    currentValue=references?backingStorage().array.getByScalar(cursor()):env.invokeMethodNoThrow(ArrayIterator.this,"current").toImmutable();
                }
                return true;
            }
            protected boolean prevValue(){return false;}
            public void reset(){init=false;advance=false;}
        };
    }
}
