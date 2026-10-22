package php.runtime.lang.spl.iterator;

import php.runtime.Memory;
import php.runtime.common.HintType;
import php.runtime.common.PhpClassNames;
import php.runtime.env.Environment;
import php.runtime.env.TraceInfo;
import php.runtime.exceptions.support.ErrorType;
import php.runtime.lang.BaseObject;
import php.runtime.lang.IObject;
import php.runtime.lang.exception.BaseTypeError;
import php.runtime.lang.spl.ArrayAccess;
import php.runtime.lang.spl.Countable;
import php.runtime.lang.spl.Serializable;
import php.runtime.lang.spl.exception.UnexpectedValueException;
import php.runtime.lang.support.ICloneableObject;
import php.runtime.memory.*;
import php.runtime.memory.output.serialization.Deserializer;
import php.runtime.memory.output.serialization.Serializer;
import php.runtime.reflection.ClassEntity;
import php.runtime.reflection.MethodEntity;
import php.runtime.reflection.support.PhpTypeDeclaration;
import java.util.ArrayList;
import java.util.List;
import static php.runtime.annotation.Reflection.*;

/** SPL aggregate with its own members/flags and replaceable shared iterator backing. */
@Name("ArrayObject")
public class ArrayObject extends BaseObject implements IteratorAggregate, ArrayAccess, Countable, Serializable,
        SplArrayAccess, ICloneableObject<ArrayObject> {
    public static final int STD_PROP_LIST = 1;
    public static final int ARRAY_AS_PROPS = 2;
    private final ArrayIterator data;
    private long flags;
    private ClassEntity iteratorClass;
    private boolean selfBacked;

    public ArrayObject(Environment env) { super(env); data = new ArrayIterator(env); }
    public ArrayObject(Environment env, ClassEntity clazz) { super(env, clazz); data = new ArrayIterator(env); }

    @Ignore public ArrayIterator nativeBacking() { return data; }
    private void writable(Environment env) {
        if (php.runtime.PhpOwnershipExecution.current(env) != null)
            throw new IllegalArgumentException("ArrayObject storage has no issued ownership publisher");
        data.nativeWritable(env);
    }
    private static String type(Memory value) {
        return value.isNull() ? "null" : value.isObject() ? value.toValue(ObjectMemory.class).getReflection().getName()
                : value.type == Memory.Type.BOOL ? value.toBoolean() ? "true" : "false"
                : value.isArray() ? "array" : value.isString() ? "string" : value.type == Memory.Type.DOUBLE ? "float" : "int";
    }
    private void replace(Environment env, Memory value, String method) {
        writable(env); value = value.toValue();
        if (!value.isArray() && !value.isObject())
            env.exception(BaseTypeError.class, "ArrayObject::%s(): Argument #1 ($array) must be of type array, %s given", method, type(value));
        boolean self = value.isObject() && value.toValue(ObjectMemory.class).value == this;
        if (value.isObject() && !self && PhpClassNames.isModern(env))
            env.error(env.trace() == null ? TraceInfo.UNKNOWN : env.trace(), ErrorType.E_DEPRECATED,
                    "ArrayObject::%s(): Using an object as a backing array for ArrayObject is deprecated, as it allows violating class constraints and invariants", method);
        if (self && PhpClassNames.isModern(env))
            env.error(env.trace() == null ? TraceInfo.UNKNOWN : env.trace(), ErrorType.E_DEPRECATED,
                    "ArrayObject::%s(): Using an object as a backing array for ArrayObject is deprecated, as it allows violating class constraints and invariants", method);
        data.nativeReplaceBacking(env, value, self ? getProperties() : null);
        selfBacked = self;
    }
    private ClassEntity selectIterator(Environment env, Memory name, String method, int argument) {
        String spelling=name.toString();if(spelling.startsWith("\\"))spelling=spelling.substring(1);
        ClassEntity selected = env.fetchClass(spelling, true);
        ClassEntity base = env.fetchClass("ArrayIterator", false);
        if (selected == null || !selected.isInstanceOf(base))
            env.exception(BaseTypeError.class, "ArrayObject::%s(): Argument #%d ($iteratorClass) must be a class name derived from ArrayIterator, %s given", method, argument, name);
        return selected;
    }
    private Object key(Environment env, Memory value, String operation) {
        value=value.toValue();
        if (value.isArray() || value.isObject())
            env.exception(BaseTypeError.class, operation.equals("exists") ? "Cannot access offset of type %s in isset or empty"
                    : operation.equals("unset") ? "Cannot unset offset of type %s on ArrayObject" : "Cannot access offset of type %s on ArrayObject", type(value));
        return ArrayMemory.toKey(value);
    }
    private void checked(Environment env, Memory value, String operation) { if (!value.isNull()) key(env,value,operation); }
    @Ignore public boolean arrayAsProperties() { return (flags & ARRAY_AS_PROPS) != 0; }
    @Ignore public Memory propertyGet(Environment env,String key) { return env.invokeMethodNoThrow(this,"offsetGet",StringMemory.valueOf(key)); }
    @Ignore public Memory propertySet(Environment env,String key,Memory value) { env.invokeMethodNoThrow(this,"offsetSet",StringMemory.valueOf(key),value);return value; }
    @Ignore public Memory propertyUnset(Environment env,String key) { return env.invokeMethodNoThrow(this,"offsetUnset",StringMemory.valueOf(key)); }
    @Ignore public Memory propertyIsset(Environment env,String key) {
        var member=getReflection().findMethod("offsetexists");
        if(member!=null && member.getNativeMethod()!=null && member.getNativeMethod().getDeclaringClass()==ArrayObject.class)
            return offsetIsset(env,StringMemory.valueOf(key));
        return env.invokeMethodNoThrow(this,"offsetExists",StringMemory.valueOf(key)).toBoolean()?Memory.TRUE:Memory.NULL;
    }
    @Ignore public Memory offsetAddress(Environment env,Memory key) { writable(env);checked(env,key,"get");return data.offsetAddress(env,key); }
    @Ignore public Memory offsetIsset(Environment env,Memory key) { checked(env,key,"exists");return data.offsetIsset(env,key); }
    @Ignore public Memory castArray(Environment env) { return (flags & STD_PROP_LIST) != 0 ? getProperties().duplicate() : getArrayCopy(env); }

    @Signature({@Arg(value="array",optional=@Optional(value="",type=HintType.ARRAY)),
            @Arg(value="flags",type=HintType.INT,optional=@Optional(value="0",type=HintType.INT)),
            @Arg(value="iteratorClass",type=HintType.STRING,optional=@Optional("ArrayIterator"))})
    public Memory __construct(Environment env,Memory... args) {
        writable(env);
        Memory input=args.length==0?new ArrayMemory():args[0].toValue();
        if(!input.isArray()&&!input.isObject())env.exception(BaseTypeError.class,"ArrayObject::__construct(): Argument #1 ($array) must be of type array, %s given",type(input));
        ClassEntity selected=selectIterator(env,args.length>2?args[2]:StringMemory.valueOf("ArrayIterator"),"__construct",3);
        replace(env,input,"__construct");
        flags=args.length>1?args[1].toLong():0;iteratorClass=selected;return Memory.NULL;
    }
    @Signature public Memory count(Environment env,Memory... args) { return data.count(env); }
    @Signature public Memory getArrayCopy(Environment env,Memory... args) { return data.getArrayCopy(env); }
    @Signature(@Arg("array")) public Memory exchangeArray(Environment env,Memory... args) {
        Memory previous=getArrayCopy(env);replace(env,args[0],"exchangeArray");return previous;
    }
    @Signature public Memory getIterator(Environment env,Memory... args) {
        writable(env);
        ClassEntity selected=iteratorClass==null?env.fetchClass("ArrayIterator",false):iteratorClass;
        selected.checkCanInstance(env,env.trace());
        ArrayIterator iterator=selected.newObjectWithoutConstruct(env);
        iterator.nativeAttachAggregate(this,flags);
        return ObjectMemory.valueOf(iterator);
    }
    @Ignore @Override public php.runtime.lang.ForeachIterator getNewIterator(Environment env) {
        return getNewIterator(env,false,false);
    }
    @Ignore @Override public php.runtime.lang.ForeachIterator getNewIterator(Environment env,boolean references,boolean keyReferences) {
        // Use the PHP virtual aggregate factory, preserving custom iterators and their real owner link.
        return env.invokeMethodNoThrow(this,"getIterator").getNewIterator(env,references,keyReferences);
    }
    @Signature public Memory getIteratorClass(Environment env,Memory... args) { return StringMemory.valueOf(iteratorClass==null?"ArrayIterator":iteratorClass.getName()); }
    @Signature(@Arg(value="iteratorClass",type=HintType.STRING)) public Memory setIteratorClass(Environment env,Memory... args) {
        writable(env);iteratorClass=selectIterator(env,args[0],"setIteratorClass",1);return Memory.NULL;
    }
    @Signature public Memory getFlags(Environment env,Memory... args) { return LongMemory.valueOf(flags); }
    @Signature(@Arg(value="flags",type=HintType.INT)) public Memory setFlags(Environment env,Memory... args) { writable(env);flags=args[0].toLong();return Memory.NULL; }
    @Signature(@Arg("key")) public Memory offsetExists(Environment env,Memory... args) { checked(env,args[0],"exists");return data.offsetExists(env,args); }
    @Signature(@Arg("key")) public Memory offsetGet(Environment env,Memory... args) { checked(env,args[0],"get");return data.offsetGet(env,args); }
    @Signature({@Arg("key"),@Arg("value")}) public Memory offsetSet(Environment env,Memory... args) { writable(env);checked(env,args[0],"set");return data.offsetSet(env,args); }
    @Signature(@Arg("key")) public Memory offsetUnset(Environment env,Memory... args) { writable(env);checked(env,args[0],"unset");return data.offsetUnset(env,args); }
    @Signature(@Arg("value")) public Memory append(Environment env,Memory... args) { return offsetSet(env,Memory.NULL,args[0]); }
    @Signature(@Arg(value="flags",type=HintType.INT,optional=@Optional(value="0",type=HintType.INT))) public Memory asort(Environment env,Memory... args)throws Throwable { writable(env);return data.asort(env,args); }
    @Signature(@Arg(value="flags",type=HintType.INT,optional=@Optional(value="0",type=HintType.INT))) public Memory ksort(Environment env,Memory... args)throws Throwable { writable(env);return data.ksort(env,args); }
    @Signature public Memory natsort(Environment env,Memory... args)throws Throwable { writable(env);return data.natsort(env); }
    @Signature public Memory natcasesort(Environment env,Memory... args)throws Throwable { writable(env);return data.natcasesort(env); }
    @Signature(@Arg(value="callback",type=HintType.CALLABLE)) public Memory uasort(Environment env,Memory... args)throws Throwable { writable(env);return data.uasort(env,args); }
    @Signature(@Arg(value="callback",type=HintType.CALLABLE)) public Memory uksort(Environment env,Memory... args)throws Throwable { writable(env);return data.uksort(env,args); }
    @Signature public Memory __debugInfo(Environment env,Memory... args) {
        ArrayMemory result=getProperties().duplicate();result.put("\0ArrayObject\0storage",data.nativeObjectBacking().isNull()?getArrayCopy(env):data.nativeObjectBacking());return result;
    }
    @Signature public Memory __serialize(Environment env,Memory... args) {
        ArrayMemory result=new ArrayMemory();result.add(LongMemory.valueOf(flags|(selfBacked?16777216:0)));
        result.add(selfBacked?Memory.NULL:data.nativeObjectBacking().isNull()?getArrayCopy(env):data.nativeObjectBacking());
        result.add(getProperties().duplicate());result.add(iteratorClass==null||iteratorClass.getName().equals("ArrayIterator")?Memory.NULL:getIteratorClass(env));return result;
    }
    private void restoreProperties(Environment env,ArrayMemory props) {
        var it=props.foreachIterator(false,false);
        while(it.next()) {
            String k=it.getKey().toString();
            var property=getProperties() instanceof PhpObjectProperties?((PhpObjectProperties)getProperties()).declaration(k):null;
            var before=getProperties().getByScalar(k);
            if(property!=null && property.isPhpReadonly() && before!=null && !before.isUndefined())
                env.exception(php.runtime.lang.exception.BaseError.class,"Cannot modify readonly property %s::$%s",property.getClazz().getName(),property.getName());
            getReflection().checkDynamicPropertyRestoration(env,env.trace(),getProperties(),k);
            // Native SPL loads raw own-property zvals: no type coercion or hook setter, and absent keys survive.
            Memory value=it.getValue();value=value.isReference()?value:value.toImmutable();
            if(property!=null && getProperties() instanceof PhpObjectProperties)
                ((PhpObjectProperties)getProperties()).restoreSplArrayObjectProperty(env,this,k,value);
            else getProperties().put(k,value);
        }
    }
    @Signature(@Arg(value="data",type=HintType.ARRAY)) public Memory __unserialize(Environment env,Memory... args) {
        writable(env);ArrayMemory v=args[0].toValue(ArrayMemory.class);
        Memory f=v.valueOfIndex(env.trace(),0).toValue(),a=v.valueOfIndex(env.trace(),1).toValue(),props=v.valueOfIndex(env.trace(),2).toValue(),ic=v.valueOfIndex(env.trace(),3).toValue();
        if(v.size()<3||f.type!=Memory.Type.INT||!props.isArray()||(!ic.isNull()&&!ic.isString()))env.exception(UnexpectedValueException.class,"Incomplete or ill-typed serialization data");
        ClassEntity selected=selectIterator(env,ic.isNull()?StringMemory.valueOf("ArrayIterator"):ic,"__unserialize",4);
        boolean self=(f.toLong()&16777216)!=0;
        if(self){data.nativeReplaceBacking(env,ObjectMemory.valueOf(this),getProperties());selfBacked=true;}
        else replace(env,a,"__unserialize");
        flags=f.toLong()&~16777216;iteratorClass=selected;
        restoreProperties(env,props.toValue(ArrayMemory.class));return Memory.NULL;
    }
    private String wire(Environment env,Memory v){StringBuilder text=new StringBuilder();new Serializer(env,env.trace(),text).write(v);return text.toString();}
    @Signature public Memory serialize(Environment env,Memory... args) {
        if(!PhpClassNames.isModern(env))
            return StringMemory.valueOf(selfBacked?"x:"+wire(env,LongMemory.valueOf(flags|16777216))+"m:"+wire(env,getProperties())
                    :"x:"+wire(env,LongMemory.valueOf(flags))+wire(env,data.nativeObjectBacking().isNull()?getArrayCopy(env):data.nativeObjectBacking())+";m:"+wire(env,getProperties()));
        var executing=php.runtime.PhpPropertyWriteContext.current(env);
        TraceInfo trace=executing==null?env.trace():executing.trace;
        try {
            return php.runtime.memory.output.serialization.PhpSerialization.serializeSplArrayObject(env,trace,
                    LongMemory.valueOf(flags|(selfBacked?16777216:0)),
                    selfBacked?null:data.nativeObjectBacking().isNull()?getArrayCopy(env):data.nativeObjectBacking(),getProperties());
        }catch(Throwable failure){return ArrayObject.<RuntimeException,Memory>raise(failure);}
    }
    private static final class Parser extends Deserializer {
        Parser(Environment env){super(env,env.trace());} int position(){return pos;}
        @Override protected Memory error(int offset,int length){throw new IllegalArgumentException(Integer.toString(offset));}
    }
    @Signature(@Arg(value="data",type=HintType.STRING)) public Memory unserialize(Environment env,Memory... args) {
        writable(env);
        if(PhpClassNames.isModern(env))return unserializeBytes(env,args[0]);
        String text=args[0].toString();if(text.isEmpty())return Memory.NULL;int offset=0;
        try {
            if(!text.startsWith("x:"))throw new IllegalArgumentException();Parser parser=new Parser(env);Memory f=parser.read(text,2);offset=parser.position();
            if(f.type!=Memory.Type.INT)throw new IllegalArgumentException();
            if((f.toLong()&16777216)!=0) {
                data.nativeReplaceBacking(env,ObjectMemory.valueOf(this),getProperties());selfBacked=true;flags=f.toLong()&~16777216;
                if(!text.startsWith("m:",offset))throw new IllegalArgumentException();offset+=2;
            } else {
                Memory a=parser.read(text,offset);offset=parser.position();
                if(!a.isArray()&&!a.isObject()){offset=6;throw new IllegalArgumentException();}
                if(a.isArray()){data.nativeRestoreBacking(env,a.toValue(ArrayMemory.class));selfBacked=false;}else replace(env,a,"unserialize");flags=f.toLong();
                if(!text.startsWith(";m:",offset))throw new IllegalArgumentException();offset+=3;
            }
            Memory props=parser.read(text,offset);offset=parser.position();
            if(!props.isArray())throw new IllegalArgumentException();restoreProperties(env,props.toValue(ArrayMemory.class));
        }catch(IllegalArgumentException failure){if(failure.getMessage()!=null)try{offset=Integer.parseInt(failure.getMessage());}catch(NumberFormatException ignored){}env.exception(UnexpectedValueException.class,"Error at offset %s of %s bytes",offset,text.length());}
        return Memory.NULL;
    }
    private Memory unserializeBytes(Environment env,Memory input) {
        byte[] text=input.getBinaryBytes(java.nio.charset.StandardCharsets.UTF_8);if(text.length==0)return Memory.NULL;int offset=0;
        var executing=php.runtime.PhpPropertyWriteContext.current(env);TraceInfo trace=executing==null?env.trace():executing.trace;
        try {
            if(text.length<2 || text[0]!='x' || text[1]!=':')throw new IllegalArgumentException();
            var parser=new php.runtime.memory.output.serialization.PhpSerialization.SplPrefixReader(env,trace,text);
            var part=parser.read(2);Memory f=part.value();offset=part.nextOffset();
            if(f.type!=Memory.Type.INT)throw new IllegalArgumentException();
            if((f.toLong()&16777216)!=0) {
                data.nativeReplaceBacking(env,ObjectMemory.valueOf(this),getProperties());selfBacked=true;flags=f.toLong()&~16777216;
                if(offset+2>text.length || text[offset]!='m' || text[offset+1]!=':')throw new IllegalArgumentException();offset+=2;
            } else {
                part=parser.read(offset);Memory a=part.value();offset=part.nextOffset();
                if(!a.isArray()&&!a.isObject()){offset=6;throw new IllegalArgumentException();}
                if(a.isArray()){data.nativeRestoreBacking(env,a.toValue(ArrayMemory.class));selfBacked=false;}else replace(env,a,"unserialize");flags=f.toLong();
                if(offset+3>text.length || text[offset]!=';' || text[offset+1]!='m' || text[offset+2]!=':')throw new IllegalArgumentException();offset+=3;
            }
            part=parser.read(offset);Memory props=part.value();offset=part.nextOffset();
            if(!props.isArray())throw new IllegalArgumentException();restoreProperties(env,props.toValue(ArrayMemory.class));
        }catch(IllegalArgumentException failure){if(failure.getMessage()!=null)try{offset=Integer.parseInt(failure.getMessage());}catch(NumberFormatException ignored){}env.exception(UnexpectedValueException.class,"Error at offset %s of %s bytes",offset,text.length);}
        catch(Throwable failure){return ArrayObject.<RuntimeException,Memory>raise(failure);}
        return Memory.NULL;
    }
    @Ignore @Override public ArrayObject __clone(Environment env,TraceInfo trace) {
        writable(env);ArrayObject copy=getReflection().newObjectWithoutConstruct(env);
        var properties=getProperties().foreachIterator(false,false);
        while(properties.next()){Memory value=properties.getValue();copy.getProperties().put(properties.getKey(),value.isReference()?value:value.toImmutable());}
        copy.selfBacked=selfBacked;
        if(selfBacked)copy.data.nativeReplaceBacking(env,ObjectMemory.valueOf(copy),copy.getProperties());
        else copy.data.nativeCloneBackingFrom(env,data);
        copy.flags=flags;copy.iteratorClass=iteratorClass;
        if(getReflection().methodMagicClone!=null) {
            var scope=php.runtime.PhpReadonlyProperty.enterClone(copy);
            try { php.runtime.invoke.ObjectInvokeHelper.invokeMethod(copy,getReflection().methodMagicClone,env,trace,null,true); }
            catch(Throwable failure){return ArrayObject.<RuntimeException,ArrayObject>raise(failure);}
            finally {php.runtime.PhpReadonlyProperty.leaveClone(scope);}
        }
        return copy;
    }
    private static <T extends Throwable,R> R raise(Throwable failure)throws T {throw (T)failure;}
    @Ignore public static PhpTypeDeclaration tentativeReturnType(MethodEntity method) {
        var member=method.getNativeMethod();if(member==null||member.getDeclaringClass()!=ArrayObject.class)return null;
        String type=switch(member.getName()){
            case "__construct"->null;case "offsetExists"->"bool";case "offsetGet"->"mixed";
            case "getArrayCopy","exchangeArray","__serialize","__debugInfo"->"array";case "count","getFlags"->"int";
            case "getIterator"->"Iterator";case "getIteratorClass","serialize"->"string";
            case "asort","ksort","uasort","uksort","natsort","natcasesort"->"true";default->"void";};
        if(type==null)return null;ArrayList<List<String>> choices=new ArrayList<>();for(String atom:type.split("\\|"))choices.add(List.of(atom));return new PhpTypeDeclaration(choices);
    }
}
