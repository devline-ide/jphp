package php.runtime.ext.core;

import php.runtime.Memory;
import php.runtime.annotation.Runtime;
import php.runtime.env.Environment;
import php.runtime.env.TraceInfo;
import php.runtime.ext.support.compile.FunctionsContainer;
import php.runtime.memory.output.serialization.Deserializer;
import php.runtime.memory.output.serialization.Serializer;
import php.runtime.memory.output.serialization.PhpSerialization;
import php.runtime.ext.support.compile.CompileFunctionSpec;
import java.util.ArrayList;
import java.util.Collection;

public class StringFunctions extends FunctionsContainer {
    @Override
    public Collection<CompileFunctionSpec> getFunctionSpecs() {
        Collection<CompileFunctionSpec> result = new ArrayList<>();
        for (CompileFunctionSpec spec : super.getFunctionSpecs()) {
            if (!spec.getName().equals("serialize") && !spec.getName().equals("unserialize")) result.add(spec);
        }
        CompileFunctionSpec serialize = new CompileFunctionSpec("serialize");
        serialize.addMethod(getNative(PhpSerialization.class, "serialize", Environment.class, TraceInfo.class, Memory.class));
        result.add(serialize);
        CompileFunctionSpec unserialize = new CompileFunctionSpec("unserialize");
        unserialize.addMethod(getNative(PhpSerialization.class, "unserialize", Environment.class, TraceInfo.class, Memory.class));
        unserialize.addMethod(getNative(PhpSerialization.class, "unserialize", Environment.class, TraceInfo.class, Memory.class, Memory.class));
        result.add(unserialize);
        return result;
    }

    @Runtime.Immutable
    public static String chr(int codePoint){
        return String.valueOf((char) codePoint);
    }

    @Runtime.Immutable
    public static int ord(char value){
        return (int) value;
    }

    public static String serialize(Environment env, TraceInfo trace, Memory value){
        StringBuilder writer = new StringBuilder();
        Serializer serializer = new Serializer(env, trace, writer);

        serializer.write(value);
        return writer.toString();
    }

    public static Memory unserialize(Environment env, TraceInfo trace, String value){
        Deserializer deserializer = new Deserializer(env, trace);
        return deserializer.read(value);
    }
}
