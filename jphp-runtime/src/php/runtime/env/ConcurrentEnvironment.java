package php.runtime.env;

import php.runtime.Memory;
import php.runtime.exceptions.CriticalException;
import php.runtime.output.OutputBuffer;
import php.runtime.reflection.ClassEntity;
import php.runtime.reflection.ModuleEntity;

import java.io.OutputStream;
import java.util.Locale;
import java.util.Set;
import java.util.Stack;

public class ConcurrentEnvironment extends Environment {
    private static final class OutputLineage {
        final Stack<OutputBuffer> buffers=new Stack<>();
        int handlerDepth;
        OutputLineage(OutputBuffer root) {buffers.push(root);}
    }
    private volatile ThreadLocal<OutputLineage> outputBuffers;
    private final ThreadLocal<FiberState> fiberState=new ThreadLocal<>();
    private final ThreadLocal<OutputLineage> generatorOutput=new ThreadLocal<>();
    Object captureGeneratorOutput(){return outputLineage();}
    final class GeneratorOutputBinding implements AutoCloseable {
        private final Thread owner=Thread.currentThread();private final OutputLineage previous;private boolean closed;
        GeneratorOutputBinding(Object context){previous=generatorOutput.get();generatorOutput.set((OutputLineage)context);}
        @Override public void close(){if(closed)return;if(owner!=Thread.currentThread())throw new IllegalStateException("Foreign Generator output restoration");
            if(previous==null)generatorOutput.remove();else generatorOutput.set(previous);closed=true;}
    }
    GeneratorOutputBinding bindGeneratorOutput(Object context){return new GeneratorOutputBinding(context);}

    FiberState activeFiberState() {return fiberState==null?null:fiberState.get();}
    void installFiberState(FiberState state) {if(state==null)fiberState.remove();else fiberState.set(state);}
    FiberState captureFiberState() {
        FiberState active=activeFiberState();
        return active!=null?active:new FiberState(this,callStack.get(),super.getErrorFlags(),silentFlags.get(),outputLineage());
    }
    private OutputLineage outputLineage() {
        FiberState active=activeFiberState();
        if(active!=null)return (OutputLineage)active.outputContext;
        OutputLineage delegated=generatorOutput==null?null:generatorOutput.get();if(delegated!=null)return delegated;
        if(outputBuffers==null)synchronized(this) {
            if(outputBuffers==null)outputBuffers=ThreadLocal.withInitial(()->new OutputLineage(getDefaultBuffer()));
        }
        return outputBuffers.get();
    }
    private Stack<Integer> currentSilentFlags() {
        FiberState active=activeFiberState();return active==null?silentFlags.get():active.silent;
    }
    @Override public int getErrorFlags() {
        FiberState active=activeFiberState();return active==null?super.getErrorFlags():active.errorFlags;
    }
    @Override public boolean isOutputHandlerActive() {return outputLineage().handlerDepth>0;}
    @Override public void changeOutputHandlerDepth(int delta) {
        OutputLineage lineage=outputLineage();
        if(lineage.handlerDepth+delta<0)throw new IllegalStateException("Unbalanced output handler depth");
        lineage.handlerDepth+=delta;
    }

    private ThreadLocal<Stack<Integer>> silentFlags = new ThreadLocal<Stack<Integer>>(){
        @Override
        protected Stack<Integer> initialValue() {
            return new Stack<Integer>();
        }
    };

    private ThreadLocal<CallStack> callStack = new ThreadLocal<CallStack>() {
        @Override
        protected CallStack initialValue() {
            return new CallStack(ConcurrentEnvironment.this);
        }
    };

    public ConcurrentEnvironment(Environment parent) {
        super(parent);
    }

    public ConcurrentEnvironment(CompileScope scope, OutputStream output) {
        super(scope, output);
    }

    public ConcurrentEnvironment(OutputStream output) {
        super(output);
    }

    public ConcurrentEnvironment(CompileScope scope) {
        super(scope);
    }

    @Override
    public Stack<OutputBuffer> getOutputBuffers() {return outputLineage().buffers;}

    @Override
    public void __replaceCallStack(CallStack stack) {
        FiberState active=activeFiberState();
        if(active==null)callStack.set(stack);else active.stack=stack;
    }

    @Override
    public CallStack getCallStack() {
        FiberState active=activeFiberState();return active==null?callStack.get():active.stack;
    }

    @Override
    public void addIncludePath(String value) {
        super.addIncludePath(value);
    }

    @Override
    public ClassEntity autoloadCall(String name, String lowerName) {
        // PHP callbacks may suspend; metadata/root-sink locks live below this boundary.
        return super.autoloadCall(name, lowerName);
    }

    @Override
    public void setIncludePaths(Set<String> includePaths) {
        super.setIncludePaths(includePaths);
    }

    @Override
    public boolean defineConstant(String name, Memory value, boolean caseSensitise) {
        return super.defineConstant(name,value,caseSensitise);
    }

    @Override
    public Memory setConfigValue(String name, Memory value) {
        return super.setConfigValue(name,value);
    }

    @Override
    public void setLocale(Locale locale) {
        synchronized (this) {
            super.setLocale(locale);
        }
    }

    @Override
    public void setUserValue(String name, Object value) {
        super.setUserValue(name,value);
    }

    @Override
    public void setErrorFlags(int errorFlags) {
        FiberState active=activeFiberState();
        if(active!=null){active.errorFlags=errorFlags;return;}
        synchronized (this) {super.setErrorFlags(errorFlags);}
    }

    public void __pushSilent(){
        currentSilentFlags().push(getErrorFlags());
        setErrorFlags(0);
    }

    public void __popSilent(){
        Integer flags = currentSilentFlags().pop();
        setErrorFlags(flags);
    }

    public void __clearSilent(){
        Stack<Integer> silents = currentSilentFlags();
        Integer flags = null;
        while (!silents.empty())
            flags = silents.pop();

        if (flags != null) {
            setErrorFlags(flags);
        }
    }

    @Override
    public void __defineFunction(TraceInfo trace, String moduleIndex, int index) {
        super.__defineFunction(trace,moduleIndex,index);
    }

    @Override
    public void registerModule(ModuleEntity module) {
        super.registerModule(module,true);
    }

    @Override
    public ModuleEntity importCompiledModule(Context context, boolean debugInformation) throws Throwable {
        // Compilation/restoration may autoload PHP; scope metadata is synchronized by Environment.
        return super.importCompiledModule(context, debugInformation);
    }

    @Override
    public ModuleEntity importModule(Context context) throws Throwable {
        // Compilation/restoration may autoload PHP; scope metadata is synchronized by Environment.
        return super.importModule(context);
    }

    @Override
    public void registerAutoloader(SplClassLoader classLoader, boolean prepend) {
        synchronized (classLoaders){
            super.registerAutoloader(classLoader, prepend);
        }
    }

    @Override
    public boolean unRegisterAutoloader(SplClassLoader classLoader) {
        synchronized (classLoaders){
            return super.unRegisterAutoloader(classLoader);
        }
    }

    @Override
    public Memory getOrCreateGlobal(String name) {
        synchronized (this.globals) {
            return super.getOrCreateGlobal(name);
        }
    }

    @Override
    public Memory getOrCreateStatic(String name, Memory initValue) {
        synchronized (this.statics) {
            return super.getOrCreateStatic(name, initValue);
        }
    }

    @Override
    public void echo(byte[] bytes, int length) {
        // PHP callbacks may suspend; metadata/root-sink locks live below this boundary.
        super.echo(bytes, length);
    }

    @Override
    public void echo(Memory value) {
        // PHP callbacks may suspend; metadata/root-sink locks live below this boundary.
        super.echo(value);
    }

    @Override
    public void echo(String value) {
        // PHP callbacks may suspend; metadata/root-sink locks live below this boundary.
        super.echo(value);
    }

    @Override
    public void flushAll() throws Throwable {
        // PHP callbacks may suspend; metadata/root-sink locks live below this boundary.
        super.flushAll();
    }
}
