package php.runtime.env.handler;

import php.runtime.Memory;
import php.runtime.env.DieException;
import php.runtime.env.Environment;
import php.runtime.env.TraceInfo;
import php.runtime.env.message.SystemMessage;
import php.runtime.exceptions.support.ErrorException;
import php.runtime.exceptions.support.ErrorType;
import php.runtime.invoke.Invoker;
import php.runtime.lang.BaseException;
import php.runtime.memory.ArrayMemory;
import php.runtime.memory.LongMemory;
import php.runtime.memory.StringMemory;

public class ErrorHandler {
    public final Invoker invoker;
    public final Memory invokerMemory;
    public final int errorHandlerFlags;
    private final php.runtime.PhpOwnershipNativeCalls.Registration ownershipRegistration;

    public ErrorHandler(Invoker invoker, Memory invokerMemory, int errorHandlerFlags) {
        this(invoker,invokerMemory,errorHandlerFlags,null);
    }

    public ErrorHandler(Invoker invoker, Memory invokerMemory, int errorHandlerFlags,
                        php.runtime.PhpOwnershipNativeCalls.Registration ownershipRegistration) {
        if (ownershipRegistration != null && ownershipRegistration.binding().rawValue() != invokerMemory)
            throw new IllegalArgumentException("Native handler registration does not identify the actual callable");
        if (ownershipRegistration != null) ownershipRegistration.requireInvoker(invoker);
        this.invoker = invoker;
        this.invokerMemory = invokerMemory;
        this.errorHandlerFlags = errorHandlerFlags;
        this.ownershipRegistration = ownershipRegistration;
    }

    public php.runtime.PhpOwnershipNativeCalls.Registration getOwnershipRegistration() {return ownershipRegistration;}

    public boolean onError(Environment env, SystemMessage message) {
        if (ErrorType.check(errorHandlerFlags, message.getType())){
            TraceInfo trace = message.getTrace().trace;
            int argCount = ownershipRegistration != null && ownershipRegistration.isOrdinary() ? 4 : invoker.getArgumentCount();
            if (argCount < 4) argCount = 4;
            else if (argCount > 5) argCount = 5;

            Memory[] args = new Memory[argCount];
            args[0] = LongMemory.valueOf(message.getType().value);
            args[1] = new StringMemory(message.getMessage());
            args[2] = new StringMemory(trace.getFileName());
            args[3] = LongMemory.valueOf(trace.getStartLine() + 1);
            if (argCount > 4)
                args[4] = new ArrayMemory(false, message.getTrace().args);

            try {
                invoker.setTrace(null);
                return ownershipRegistration == null ? invoker.call(args).toValue() != Memory.FALSE
                        : ownershipRegistration.invokeHandled(invoker,args);
            } catch (ErrorException e){
                throw e;
            } catch (BaseException e){
                throw e;
            } catch (DieException e){
                throw e;
            } catch (Throwable throwable) {
                if (throwable instanceof php.runtime.lang.exception.BaseBaseException
                        && env.getModuleManager().getPhpLanguageProfile(trace) == php.runtime.common.PhpLanguageProfile.PHP_8_5)
                    ErrorHandler.<RuntimeException>raise(throwable);
                throw new RuntimeException(throwable);
            }
        }
        return false;
    }
    @SuppressWarnings("unchecked")
    private static <T extends Throwable> void raise(Throwable failure) throws T { throw (T) failure; }
}
