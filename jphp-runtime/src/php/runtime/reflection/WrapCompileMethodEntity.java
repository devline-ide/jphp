package php.runtime.reflection;

import php.runtime.Memory;
import php.runtime.env.Environment;
import php.runtime.env.TraceInfo;
import php.runtime.lang.IObject;
import php.runtime.common.CallbackW;
import php.runtime.memory.support.MemoryOperation;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import php.runtime.ext.support.Extension;
import php.runtime.lang.BaseWrapper;

public class WrapCompileMethodEntity extends CompileMethodEntity {
    public WrapCompileMethodEntity(Extension extension) {
        super(extension);
    }

    @Override
    public boolean addMethod(Method method, boolean skipConflicts,
                             CallbackW<MemoryOperation, Class<?>, Type> unknownTypeFetcher) {
        return addMethod(method, skipConflicts, unknownTypeFetcher, true);
    }

    public boolean addDirectMethod(Method method, boolean skipConflicts) {
        CompileMethod.Method previous = function.shadowWrappedSlot(method);
        try {
            boolean added = addMethod(method, skipConflicts, null, false);
            if (!added && previous != null) function.restoreWrappedSlot(previous, method.isVarArgs());
            return added;
        } catch (RuntimeException | Error failure) {
            if (previous != null) function.restoreWrappedSlot(previous, method.isVarArgs());
            throw failure;
        }
    }

    @Override
    public Memory invokeDynamic(IObject receiver, Environment env, TraceInfo trace, Memory... arguments) throws Throwable {
        return super.invokeDynamic((Object) (BaseWrapper) receiver, env, trace, arguments);
    }

    @Override
    protected Object resolveReceiver(CompileMethod.Method method, Object receiver) {
        if (!method.wrappedReceiver || !(receiver instanceof BaseWrapper)) return receiver;
        return ((BaseWrapper) receiver).getWrappedObject();
    }
}
