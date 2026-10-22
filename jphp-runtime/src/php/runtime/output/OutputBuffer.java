package php.runtime.output;

import php.runtime.Memory;
import php.runtime.env.Environment;
import php.runtime.env.PhpCallbackDomain;
import php.runtime.env.PhpOperationGate;
import php.runtime.env.TraceInfo;
import php.runtime.invoke.Invoker;
import php.runtime.memory.BinaryMemory;
import php.runtime.memory.LongMemory;
import php.runtime.memory.StringMemory;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;

public class OutputBuffer {
    public final static int HANDLER_START = 1;
    public final static int HANDLER_WRITE = 0;
    public final static int HANDLER_FLUSH = 4;
    public final static int HANDLER_CLEAN = 2;
    public final static int HANDLER_FINAL = 8;

    public enum Type { INTERNAL, USER }

    private OutputStream output;
    private ByteArrayOutputStream buffer;
    private boolean binaryInBuffer;

    private Memory callback;
    private Invoker invoker;

    private int chunkSize;
    private static final int PHP85_BUFFER = 1 << 16, PHP85_ENDED = 1 << 17;
    private int capabilities; // Native capability bits plus private lifecycle bits.

    private final Environment environment;
    private final OutputBuffer parentOutput;

    private int level;
    private Type type;
    private String name = "default output handler";

    private boolean implicitFlush;

    private int lock = 0;
    private boolean isFlushed = false;
    private boolean handlerDisabled;
    private TraceInfo trace;
    private String traceOperation;

    private int status;

    private final PhpOperationGate operationGate = new PhpOperationGate();
    private final java.util.concurrent.atomic.AtomicReference<Operation> idleOperation = new java.util.concurrent.atomic.AtomicReference<>();
    // Only accessed by the carrier owning the root operation lease.
    private int callbackDepth;
    // Bytes only: the root must not retain the parked handler, its lease, or carrier.
    private volatile ByteArrayOutputStream handlerOutputCapture;
    private volatile boolean forwardingHandlerDiagnostic;

    private void captureHandlerOutput(byte[] bytes, int length) {
        ByteArrayOutputStream capture = environment.getDefaultBuffer().handlerOutputCapture;
        if (capture != null && length != 0) capture.write(bytes, 0, length);
    }

    private void captureHandlerOutput(Memory value) {
        if (environment.getDefaultBuffer().handlerOutputCapture == null || value == null) return;
        value = value.toValue();
        byte[] bytes = value instanceof BinaryMemory || value instanceof php.runtime.memory.PhpByteStringMemory
                ? value.getBinaryBytes(environment.getDefaultCharset()) : value.toString().getBytes(environment.getDefaultCharset());
        captureHandlerOutput(bytes, bytes.length);
    }

    /** Domain first; nested internal calls retain protected hooks without admitting sink callbacks. */
    private Operation operation(boolean mutation) {
        if (level == 0 && idleOperation.get() == null
                && operationGate.isOwnedByCarrier(environment.executionLineage())) {
            if (mutation && callbackDepth != 0)
                throw new IllegalStateException("Root output mutation inside its active host callback");
            return Operation.NESTED;
        }
        PhpCallbackDomain domain = environment.scope.getClassLoader().getCallbackDomain();
        Operation operation = idleOperation.getAndSet(null);
        if (operation == null || operation.domain != domain) operation = new Operation(this, domain);
        operation.carrier = Thread.currentThread();operation.closed = false;
        try {
            operation.domainGeneration = domain.enterOutputFrame(operation.domainFrame, environment);
            if (level == 0) {
                operation.localGeneration = operationGate.enterOutputFrame(operation.localFrame,
                        environment.executionLineage(), PhpOperationGate.Reentry.SAME_CARRIER);
                if (mutation && callbackDepth != 0)
                    throw new IllegalStateException("Root output mutation inside its active host callback");
            }
            return operation;
        } catch (RuntimeException | Error failure) {
            try { operation.close(); }
            catch (RuntimeException | Error cleanup) { if (cleanup != failure) failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    private static final class Operation implements AutoCloseable {
        private static final Operation NESTED = new Operation(null, null);
        private final OutputBuffer owner;
        private final PhpCallbackDomain domain;
        private final PhpCallbackDomain.OutputFrame domainFrame;
        private final PhpOperationGate.OutputFrame localFrame;
        private long domainGeneration, localGeneration;
        private Thread carrier;
        private boolean closed;
        private Operation(OutputBuffer owner, PhpCallbackDomain domain) {
            this.owner = owner;this.domain = domain;
            domainFrame = domain == null ? null : domain.newOutputFrame();
            localFrame = owner == null ? null : owner.operationGate.newOutputFrame();
        }
        @Override public void close() {
            if (owner == null || closed) return;
            if (carrier != Thread.currentThread()) throw new IllegalStateException("Foreign private output scope release");
            try {
                try {
                    if (localGeneration != 0) { owner.operationGate.closeOutputFrame(localFrame, localGeneration);localGeneration = 0; }
                } finally {
                    if (domainGeneration != 0) { domain.closeOutputFrame(domainFrame, domainGeneration);domainGeneration = 0; }
                }
            } finally {
                if (localGeneration == 0 && domainGeneration == 0) {
                    closed = true;carrier = null;owner.idleOperation.compareAndSet(null, this);
                }
            }
        }
    }

    private final class CallbackBoundary implements AutoCloseable {
        private final boolean root = level == 0;
        private CallbackBoundary() { if (root) callbackDepth++; }
        @Override public void close() { if (root) callbackDepth--; }
    }

    private void writeOutput(byte[] bytes, int offset, int length) throws java.io.IOException {
        try (CallbackBoundary ignored = new CallbackBoundary()) { output.write(bytes, offset, length); }
    }
    private void writeOutput(byte[] bytes) throws java.io.IOException {
        try (CallbackBoundary ignored = new CallbackBoundary()) { output.write(bytes); }
    }
    private void flushOutput() throws java.io.IOException {
        try (CallbackBoundary ignored = new CallbackBoundary()) { output.flush(); }
    }

    public OutputBuffer(Environment environment, OutputBuffer parent, Memory callback, int chunkSize, boolean erase) {
        this.environment = environment;
        this.buffer = new ByteArrayOutputStream(4098);

        this.callback = callback;
        if (callback != null)
            this.invoker = Invoker.valueOf(environment, null, callback);

        this.chunkSize = chunkSize;
        this.capabilities = erase ? 112 : 48;
        this.parentOutput = parent;

        this.type = Type.INTERNAL;
        this.implicitFlush = false;
        this.status = HANDLER_START;
    }

    public OutputBuffer(Environment environment, OutputBuffer parent, Memory callback, int chunkSize) {
        this(environment, parent, callback, chunkSize, true);
    }

    public OutputBuffer(Environment environment, OutputBuffer parent, Memory callback) {
        this(environment, parent, callback, 4096);
    }

    public OutputBuffer(Environment environment, OutputBuffer parent) {
        this(environment, parent, null);
    }

    public int getLevel() {
        try (Operation ignored = operation(false)) {
            return level;
        }
    }

    public void setLevel(int level) {
        try (Operation ignored = operation(true)) {
            this.level = level;
        }
    }

    public String getName() {
        try (Operation ignored = operation(false)) {
            return invoker != null ? invoker.getName() : name;
        }
    }

    public void setName(String name) {
        try (Operation ignored = operation(true)) {
            this.name = name;
        }
    }

    public Type getType() {
        try (Operation ignored = operation(false)) {
            return type;
        }
    }

    public void setType(Type type) {
        try (Operation ignored = operation(true)) {
            this.type = type;
        }
    }

    public void setImplicitFlush(boolean implicitFlush) {
        try (Operation ignored = operation(true)) {
            this.implicitFlush = implicitFlush;
        }
    }

    public boolean isImplicitFlush() {
        try (Operation ignored = operation(false)) {
            return implicitFlush;
        }
    }

    public Memory getCallback() {
        try (Operation ignored = operation(false)) {
            return callback;
        }
    }

    public void setOutput(OutputStream output) {
        try (Operation ignored = operation(true)) {
            this.output = output;
        }
    }

    public OutputStream getOutput() {
        try (Operation ignored = operation(false)) {
            return output;
        }
    }

    /** Isolates one embedding invocation and serializes unrelated root users until restoration. */
    public ScopedOutput scopeOutput(OutputStream target,boolean pureBuffered) {
        PhpCallbackDomain.Lease domain = environment.enterCallbackDomain();
        try (Operation ignored = operation(true)) {
            if(pureBuffered && (callback!=null || getClass()!=OutputBuffer.class))
                throw new IllegalArgumentException("Embedding output needs its exact pure buffer protocol");
            return new ScopedOutput(target,pureBuffered,domain);
        } catch (RuntimeException | Error failure) {
            try { domain.close(); } catch (RuntimeException | Error cleanup) { if (cleanup != failure) failure.addSuppressed(cleanup); }
            throw failure;
        }
    }
    public final class ScopedOutput implements AutoCloseable {
        private final PhpCallbackDomain.Lease domain;
        private final Thread carrier = Thread.currentThread();
        private final ByteArrayOutputStream previousBuffer,scopedBuffer;
        private final OutputStream previousOutput;
        private final boolean previousBinary,previousImplicit,previousFlushed,previousDisabled;
        private final int previousChunk,previousStatus,previousLock,previousCapabilities;
        private final TraceInfo previousTrace;
        private final String previousTraceOperation;
        private boolean closed;
        private ScopedOutput(OutputStream target,boolean pureBuffered,PhpCallbackDomain.Lease domain) {
            this.domain=domain;previousBuffer=buffer;scopedBuffer=new ByteArrayOutputStream(4098);previousOutput=output;
            previousBinary=binaryInBuffer;previousImplicit=implicitFlush;previousFlushed=isFlushed;previousDisabled=handlerDisabled;
            previousChunk=chunkSize;previousStatus=status;previousLock=lock;previousCapabilities=capabilities;previousTrace=trace;previousTraceOperation=traceOperation;traceOperation=null;
            buffer=scopedBuffer;binaryInBuffer=false;status=HANDLER_START;isFlushed=false;handlerDisabled=false;capabilities &= ~PHP85_ENDED;
            if(isRoot())output=target;
            if(pureBuffered){implicitFlush=false;chunkSize=0;}
        }
        @Override public void close() {
            if(closed)return;
            if(carrier!=Thread.currentThread())throw new IllegalStateException("Embedding output scope must close on its carrier");
            try (Operation ignored = operation(true)) {
                if(buffer!=scopedBuffer)throw new IllegalStateException("Embedding output scopes must close in reverse order");
                buffer=previousBuffer;output=previousOutput;binaryInBuffer=previousBinary;implicitFlush=previousImplicit;
                isFlushed=previousFlushed;handlerDisabled=previousDisabled;capabilities=previousCapabilities;chunkSize=previousChunk;status=previousStatus;lock=previousLock;trace=previousTrace;traceOperation=previousTraceOperation;
                closed=true;
            } catch (RuntimeException | Error failure) {
                if(closed) try { domain.close(); }
                catch (RuntimeException | Error cleanup) { if(cleanup!=failure)failure.addSuppressed(cleanup); }
                throw failure;
            }
            if(closed)domain.close();
        }
    }


    public void setCallback(Memory callback, Invoker invoker) {
        try (Operation ignored = operation(true)) {
            this.handlerDisabled = false;
            this.callback = callback;
            this.invoker = invoker;
        }
    }

    public void setCallback(Memory callback) {
        try (Operation ignored = operation(true)) {
            Invoker resolved = null;
            if (callback != null) {
                try (CallbackBoundary boundary = new CallbackBoundary()) { resolved = Invoker.valueOf(environment, null, callback); }
            }
            this.handlerDisabled = false;
            this.callback = callback;
            this.invoker = resolved;
        }
    }

    public int getChunkSize() {
        try (Operation ignored = operation(false)) {
            return chunkSize;
        }
    }

    public void setChunkSize(int chunkSize) {
        try (Operation ignored = operation(true)) {
            this.chunkSize = chunkSize;
        }
    }

    public boolean isErase() {
        try (Operation ignored = operation(false)) {
            return (capabilities & 64) != 0;
        }
    }

    public void setErase(boolean erase) {
        try (Operation ignored = operation(true)) {
            capabilities = (capabilities & ~64) | (erase ? 64 : 0);
        }
    }

    public int getCapabilities() {
        try (Operation ignored = operation(false)) {
            return capabilities & 112;
        }
    }
    public void setPhp85Capabilities(int flags) {
        try (Operation ignored = operation(true)) {
            capabilities = (flags & 112) | PHP85_BUFFER;
        }
    }

    public Memory getContents() throws UnsupportedEncodingException {
        try (Operation ignored = operation(false)) {
            if (!binaryInBuffer){
                return new StringMemory(buffer.toString(environment.getDefaultCharset().name()));
            } else
                return new BinaryMemory(buffer.toByteArray());
        }
    }

    protected void reset(){
        try (Operation ignored = operation(true)) {
            buffer.reset();
            binaryInBuffer = false;
            traceOperation = null;
        }
    }

    public void clean() throws Throwable {
        try (Operation ignored = operation(true)) {
            if (invoker != null){
                doFlush(false);
            }
            status = HANDLER_CLEAN;
            reset();
        }
    }

    /** Explicit PHP flush/final phases; callback failures retire through the same raw-byte path. */
    public void flushPhp85(boolean last) throws Throwable {
        try (Operation ignored = operation(true)) {
            int phase = (last ? HANDLER_FINAL : HANDLER_FLUSH)
                    | (status == HANDLER_START ? HANDLER_START : 0);
            if (last && (capabilities & PHP85_ENDED) != 0) return;
            isFlushed = true;
            try { doFlush(true, phase); }
            finally { if (last) capabilities |= PHP85_ENDED; }
            if (output != null) flushOutput();
        }
    }

    /** PHP 8 cleanup phases: START is sent once; a final clean never forwards callback output. */
    public void cleanPhp85(boolean last) throws Throwable {
        try (Operation ignored = operation(true)) {
            int phase = HANDLER_CLEAN | (last ? HANDLER_FINAL : 0)
                    | (status == HANDLER_START ? HANDLER_START : 0);
            try {
                if (invoker != null && !handlerDisabled) doFlush(false, phase);
            } catch (Throwable failure) {
                handlerDisabled = true;
                throw failure;
            } finally {
                status = HANDLER_CLEAN | (last ? HANDLER_FINAL : 0);
                if (last) capabilities |= PHP85_ENDED;
                reset();
            }
        }
    }

    protected void doFlush(boolean flush) throws Throwable {
        try (Operation ignored = operation(true)) {
            doFlush(flush, -1);
        }
    }

    private void doFlush(boolean flush, int phase) throws Throwable {
        String operation = traceOperation;
        TraceInfo operationTrace = trace;
        traceOperation = null;
        if (handlerDisabled) {
            if (flush && buffer.size() != 0) {
                if (output == null && parentOutput == null) return;
                byte[] pending = buffer.toByteArray();
                if (output != null) writeOutput(pending);
                else parentOutput.write(pending);
            }
            reset();
            return;
        }
        /*if (buffer.size() == 0)
            return;*/
        if (phase >= 0) {
            status = phase;
        } else if (flush){
            isFlushed = true;
            if (status != HANDLER_START && status != HANDLER_FLUSH)
                status = HANDLER_WRITE;
        } else {
            if (status == HANDLER_FINAL)
                status |= HANDLER_CLEAN;
            else
                status = HANDLER_CLEAN;
        }

        Memory result = Memory.FALSE;
        Throwable diagnosticFailure = null;
        if (invoker != null){
            invoker.setTrace(trace == null ? TraceInfo.UNKNOWN : trace);
            Memory[] args;
            if (!binaryInBuffer){
                args = new Memory[]{ new StringMemory(buffer.toString(environment.getDefaultCharset().name())), null };
            } else
                args = new Memory[]{ new BinaryMemory(buffer.toByteArray()), null };

            args[1] = LongMemory.valueOf(status);

            invoker.setTrace(trace == null ? TraceInfo.UNKNOWN : trace);
            OutputBuffer root = environment.getDefaultBuffer();
            ByteArrayOutputStream previousCapture = root.handlerOutputCapture;
            ByteArrayOutputStream attemptedOutput = phase >= 0 ? new ByteArrayOutputStream(0) : null;
            if (attemptedOutput != null) root.handlerOutputCapture = attemptedOutput;
            try {
                incLock();
                try (CallbackBoundary ignored = new CallbackBoundary()) { result = invoker.call(args).toValue(); }
                finally { decLock(); }
            } catch (Throwable failure) {
                if (attemptedOutput != null) try { attemptedOutput.writeTo(buffer); }
                catch (Throwable cleanup) { if (cleanup != failure) failure.addSuppressed(cleanup); }
                if (phase >= 0) {
                    handlerDisabled = true;
                    if (flush) try { doFlush(true, -1); }
                    catch (Throwable cleanup) { if (cleanup != failure) failure.addSuppressed(cleanup); }
                }
                throw failure;
            } finally {
                if (attemptedOutput != null) root.handlerOutputCapture = previousCapture;
            }
            if (attemptedOutput != null && attemptedOutput.size() != 0) {
                attemptedOutput.writeTo(buffer);
                if (parentOutput != null) forwardingHandlerDiagnostic = true;
                try (CallbackBoundary ignored = new CallbackBoundary()) {
                    var caller = environment.peekCall(0);
                    String origin = operation != null ? operation
                            : caller == null || caller.function == null || "{main}".equals(caller.function) ? "main" : caller.function;
                    environment.error(operation != null && operationTrace != null ? operationTrace : environment.trace(), php.runtime.exceptions.support.ErrorType.E_DEPRECATED,
                            "%s(): Producing output from user output handler %s is deprecated", origin, invoker.getName());
                } catch (Throwable failure) { diagnosticFailure = failure; }
                finally { forwardingHandlerDiagnostic = false; }
            }
        }
        try {
            if (invoker != null && result != Memory.FALSE){
                byte[] data;
                try (CallbackBoundary ignored = new CallbackBoundary()) {
                    data = result instanceof BinaryMemory || result instanceof php.runtime.memory.PhpByteStringMemory
                            ? result.getBinaryBytes(environment.getDefaultCharset()) : result.toString().getBytes(environment.getDefaultCharset());
                }
                if (flush){
                    if (output == null) {
                        if (parentOutput == null) {
                            // A root without an output stream is an in-memory sink.
                            reset();
                            if (result instanceof php.runtime.memory.PhpByteStringMemory) binaryInBuffer = true;
                            buffer.write(data);
                            status = HANDLER_FLUSH;
                            return;
                        }
                        parentOutput.write(data);
                    } else {
                        writeOutput(data);
                    }

                    reset();
                    status = HANDLER_FLUSH;
                }

                return;
            }

            if (flush){
                status |= HANDLER_FLUSH;
                if (output == null) {
                    if (parentOutput == null) return; // Keep root contents available to the embedding host.
                    parentOutput.write(buffer.toByteArray());
                } else {
                    byte[] pending = buffer.toByteArray();
                    writeOutput(pending, 0, pending.length);
                }
                reset();
            }
        } catch (Throwable failure) {
            if (diagnosticFailure == null) throw failure;
            if (failure != diagnosticFailure) diagnosticFailure.addSuppressed(failure);
        } finally {
            if (diagnosticFailure != null) throw diagnosticFailure;
        }
    }

    public void write(String content) throws Throwable {
        if (forwardingHandlerDiagnostic) { parentOutput.write(content); return; }
        if (isLock()) {
            if (content != null && !content.isEmpty()) {
                byte[] bytes = content.getBytes(environment.getDefaultCharset());
                captureHandlerOutput(bytes, bytes.length);
            }
            return;
        }
        try (Operation ignored = operation(true)) {
            if (!isLock()) {
                byte[] bytes = content.getBytes(environment.getDefaultCharset());
                if (getClass() == OutputBuffer.class) writeBytes(bytes, bytes.length);
                else _write(bytes);
            }
        }
    }

    /** A modern source echo carries its actual trace into Stringable conversion. Ordinary scalars stay unboxed. */
    public static void echoPhp85(Environment env, Memory value, TraceInfo trace) throws Throwable {
        try (PhpCallbackDomain.Lease admission = env.enterCallbackDomain()) {
            php.runtime.PhpPrimitiveCellRuntime.beforeEcho(value,env);
            Memory actual=value.toValue();
            if(actual.isObject()) actual=php.runtime.PhpByteStrings.stringValue(actual,env,trace);
            env.echo(actual);
        }
    }

    /** Only the explicit PHP magic-method bridge may emit PHP output during conversion. */
    private String phpObjectString(php.runtime.memory.ObjectMemory object) {
        php.runtime.reflection.ClassEntity entity;
        php.runtime.reflection.MethodEntity method;
        Environment callbackEnvironment;
        TraceInfo methodTrace;
        String methodName, className;
        // Native IObject getters remain unknown host callbacks, even inside an exact ObjectMemory.
        try (CallbackBoundary boundary = new CallbackBoundary()) {
            entity = object.value.getReflection();
            method = entity.methodMagicToString;
            if (method == null) return "Object";
            callbackEnvironment = object.value.getEnvironment();
            if (callbackEnvironment == null) return "Object";
            methodTrace = method.getTrace(); methodName = method.getName(); className = entity.getName();
        }
        try (PhpCallbackDomain.Lease callbackAdmission = callbackEnvironment.enterCallbackDomain()) {
            try (CallbackBoundary boundary = new CallbackBoundary()) {
                callbackEnvironment.pushCall(methodTrace, object.value, null, methodName, className, null);
            }
            try {
                TraceInfo callbackTrace;
                try (CallbackBoundary boundary = new CallbackBoundary()) { callbackTrace = callbackEnvironment.trace(); }
                Memory result = method.invokeDynamic(object.value, callbackEnvironment, callbackTrace, (Memory[]) null);
                boolean string;
                try (CallbackBoundary boundary = new CallbackBoundary()) { string = result.isString(); }
                if (!string) {
                    String signature;
                    try (CallbackBoundary boundary = new CallbackBoundary()) { signature = method.getSignatureString(false); }
                    callbackEnvironment.error(php.runtime.exceptions.support.ErrorType.E_RECOVERABLE_ERROR,
                            "Method %s must return a string value", signature);
                    return "";
                }
                try (CallbackBoundary boundary = new CallbackBoundary()) { return result.toString(); }
            } catch (RuntimeException failure) { throw failure; }
            catch (Throwable failure) { throw new RuntimeException(failure); }
            finally {
                try (CallbackBoundary boundary = new CallbackBoundary()) { callbackEnvironment.popCall(); }
            }
        }
    }

    public void write(Memory content) throws Throwable {
        if (forwardingHandlerDiagnostic) { parentOutput.write(content); return; }
        if (isLock()) { captureHandlerOutput(content); return; }
        try (Operation ignored = operation(true)) {
            boolean reusable = getClass() == OutputBuffer.class && ignored.owner == this
                    && environment.getClass() == Environment.class
                    && environment.scope.getClass() == php.runtime.env.CompileScope.class;
            php.runtime.env.PhpExecutionLineage.Token admittedLineage = reusable ? environment.executionLineage() : null;
            int admittedLevel = level;
            if (!isLock()){
                if (content != null && content.getClass() == StringMemory.class) content = content.toValue();
                else try (CallbackBoundary boundary = new CallbackBoundary()) { content = content.toValue(); }
                if (content instanceof BinaryMemory || content instanceof php.runtime.memory.PhpByteStringMemory) {
                    byte[] bytes;
                    try (CallbackBoundary boundary = new CallbackBoundary()) { bytes = content.getBinaryBytes(environment.getDefaultCharset()); }
                    if (canReuseMemoryOperation(ignored, admittedLineage, admittedLevel)
                            && !forwardingHandlerDiagnostic && !isLock()) writeBinaryBytes(bytes, bytes.length);
                    else write(bytes);
                } else {
                    String text;
                    if (content.getClass() == php.runtime.memory.ObjectMemory.class)
                        text = phpObjectString((php.runtime.memory.ObjectMemory) content);
                    else if (content.getClass() == StringMemory.class) text = content.toString();
                    else try (CallbackBoundary boundary = new CallbackBoundary()) { text = content.toString(); }
                    if (canReuseMemoryOperation(ignored, admittedLineage, admittedLevel)
                            && !forwardingHandlerDiagnostic && !isLock()) {
                        byte[] bytes = text.getBytes(environment.getDefaultCharset());
                        writeBytes(bytes, bytes.length);
                    } else write(text);
                }
            }
        }
    }

    /** The full private scope remains live; changed execution or host contracts use public dispatch. */
    private boolean canReuseMemoryOperation(Operation operation, php.runtime.env.PhpExecutionLineage.Token lineage, int admittedLevel) {
        return lineage != null && operation.owner == this && !operation.closed
                && operation.carrier == Thread.currentThread() && operation.domainGeneration != 0
                && level == admittedLevel && (level != 0 || operation.localGeneration != 0 && callbackDepth == 0)
                && operation.domain == environment.scope.getClassLoader().getCallbackDomain()
                && environment.executionLineage() == lineage;
    }

    protected void _write(byte[] bytes) throws Throwable {
        _write(bytes, bytes.length);
    }

    protected void _write(byte[] bytes, int length) throws Throwable {
        if (forwardingHandlerDiagnostic) { parentOutput.write(bytes, length); return; }
        if (isLock()) { captureHandlerOutput(bytes, length); return; }
        try (Operation ignored = operation(true)) {
            writeBytes(bytes,length);
        }
    }

    private void writeBytes(byte[] bytes,int length) throws Throwable {
        if (isLock()) return;
        if (handlerDisabled) {
            if (output != null) writeOutput(bytes, 0, length);
            else if (parentOutput != null) parentOutput.write(bytes, length);
            else buffer.write(bytes, 0, length);
            return;
        }

        boolean needFlush = implicitFlush || (chunkSize > 0 && length + buffer.size() >= chunkSize);

        if (needFlush){
            if (chunkSize > 0){
                int cutLength = (length + buffer.size()) - chunkSize;
                if (cutLength > 0){
                    buffer.write(bytes, 0, length);
                    flushAutomatic();

                    /*byte[] tmp = new byte[cutLength];
                    System.arraycopy(bytes, bytes.length - cutLength, tmp, 0, cutLength);
                    _write(tmp);*/
                } else {
                    buffer.write(bytes, 0, length);
                    flushAutomatic();
                }
            } else {
                buffer.write(bytes, 0, length);
                flushAutomatic();
            }
        } else {
            buffer.write(bytes, 0, length);
        }
    }

    private void flushAutomatic() throws Throwable {
        if ((capabilities & PHP85_BUFFER) != 0) {
            isFlushed = true;
            doFlush(true, status == HANDLER_START ? HANDLER_START : HANDLER_WRITE);
        } else doFlush(true);
    }

    public void write(byte[] bytes, int length) throws Throwable {
        if (forwardingHandlerDiagnostic) { parentOutput.write(bytes, length); return; }
        if (isLock()) { captureHandlerOutput(bytes, length); return; }
        try (Operation ignored = operation(true)) {
            writeBinaryBytes(bytes,length);
        }
    }

    private void writeBinaryBytes(byte[] bytes,int length) throws Throwable {
        if (!isLock()){
            binaryInBuffer = true;
            if (getClass() == OutputBuffer.class) writeBytes(bytes, length);
            else _write(bytes, length);
        }
    }

    public void write(byte[] bytes) throws Throwable {
        write(bytes, bytes.length);
    }

    public void flush() throws Throwable {
        if (isLock()) return;
        try (Operation ignored = operation(true)) {
            flushBuffer();
        }
    }

    private void flushBuffer() throws Throwable {
        if (!isLock()){
            status = HANDLER_FLUSH;
            doFlush(true);
            if (output != null)
                flushOutput();
        }
    }

    public void close() throws Throwable {
        try (Operation ignored = operation(true)) {
            closeBuffer();
        }
    }

    private void closeBuffer() throws Throwable {
        if ((capabilities & PHP85_BUFFER) != 0) {
            if ((capabilities & PHP85_ENDED) == 0) flushPhp85(true);
        } else if (!isBufferEmpty()) doFlush(true);
    }

    public int getBufferSize(){
        try (Operation ignored = operation(false)) {
            return buffer.size();
        }
    }

    public boolean isRoot(){
        return level == 0;
    }

    public void decLock(){environment.changeOutputHandlerDepth(-1);}

    public void incLock(){environment.changeOutputHandlerDepth(1);}

    public boolean isLock(){return forwardingHandlerDiagnostic || environment.isOutputHandlerActive();}

    /** Environment's ordinary-host root storage; Concurrent hosts keep this in their output lineage. */
    public boolean __isRootHandlerActive() {return lock>0;}
    public void __changeRootHandlerDepth(int delta) {
        if(lock+delta<0)throw new IllegalStateException("Unbalanced output handler depth");
        lock+=delta;
    }

    public boolean isFlushed() {
        try (Operation ignored = operation(false)) {
            return isFlushed;
        }
    }

    public Invoker getInvoker() {
        try (Operation ignored = operation(false)) {
            return invoker;
        }
    }

    public OutputBuffer getParentOutput() {
        return parentOutput;
    }

    public void setTrace(TraceInfo trace) {
        try (Operation ignored = operation(true)) {
            this.trace = trace;
            this.traceOperation = null;
        }
    }

    public void setTrace(TraceInfo trace, String operation) {
        try (Operation ignored = operation(true)) { this.trace = trace; this.traceOperation = operation; }
    }

    public int getStatus() {
        try (Operation ignored = operation(false)) {
            return status;
        }
    }

    public void setStatus(int status) {
        try (Operation ignored = operation(true)) {
            this.status = status;
        }
    }

    public boolean isBufferEmpty(){
        try (Operation ignored = operation(false)) {
            return buffer.size() == 0;
        }
    }
}
