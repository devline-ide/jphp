package php.runtime;

import php.runtime.env.Environment;
import php.runtime.env.TraceInfo;
import php.runtime.memory.ArrayMemory;
import php.runtime.memory.LongMemory;
import php.runtime.memory.ObjectMemory;
import php.runtime.lang.support.IComparableObject;
import java.util.*;

/** Selected native comparison bodies borrow their real SEND values; they do not discover new heap owners. */
public final class PhpComparisonCalls {
    private PhpComparisonCalls() {}

    public static boolean equal(Memory left,Memory right,Environment env,TraceInfo trace){return PhpComparison.equal(left,right,env,trace);}
    public static boolean notEqual(Memory left,Memory right,Environment env,TraceInfo trace){return PhpComparison.notEqual(left,right,env,trace);}
    public static boolean smaller(Memory left,Memory right,Environment env,TraceInfo trace){return PhpComparison.smaller(left,right,env,trace);}
    public static boolean smallerEq(Memory left,Memory right,Environment env,TraceInfo trace){return PhpComparison.smallerEq(left,right,env,trace);}
    public static boolean greater(Memory left,Memory right,Environment env,TraceInfo trace){return PhpComparison.greater(left,right,env,trace);}
    public static boolean greaterEq(Memory left,Memory right,Environment env,TraceInfo trace){return PhpComparison.greaterEq(left,right,env,trace);}
    public static Memory spaceshipCompare(Memory left,Memory right,Environment env,TraceInfo trace){return LongMemory.valueOf(PhpComparison.spaceshipCompare(left,right,env,trace));}

    /** Callback admission precedes the selected BODY, including nested object conversions. */
    public static void validateOwnershipArguments(PhpRuntimeOwnership.Authority authority,List<PhpRuntimeOwnership.Value> arguments) {
        if(arguments.size()!=2)throw new IllegalArgumentException("Comparison needs its two actual SEND operands");
        Set<Object> visited=Collections.newSetFromMap(new IdentityHashMap<>());
        for(var argument:arguments)validateValue(authority,argument.raw(),visited);
    }
    private static void validateValue(PhpRuntimeOwnership.Authority authority,Memory raw,Set<Object> visited) {
        Memory value=raw.toValue();authority.value(value,PhpRuntimeOwnership.Role.VALUE);
        if(value instanceof ArrayMemory array) {
            if(!visited.add(array.getStorageIdentity()))return;
            for(Object key:array.keySet()){var child=array.getByScalar(key);if(child!=null)validateValue(authority,child,visited);}
        }else if(value instanceof ObjectMemory object) {
            validateClass(object.getReflection());
            if(!visited.add(object.value))return;
            var properties=object.value.getProperties();
            for(Object key:properties.keySet()){var child=properties.getByScalar(key);if(child!=null)validateValue(authority,child,visited);}
        }
    }
    public static void validateClass(php.runtime.reflection.ClassEntity entity) {
        if(entity.methodMagicToString!=null || IComparableObject.class.isAssignableFrom(entity.getNativeClass()))
            throw new IllegalArgumentException("Comparison object conversion/native comparator needs its complete callback protocol");
    }
}
