package php.runtime.output;

import php.runtime.Memory;
import php.runtime.env.Environment;
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

    private int status;

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
        return level;
    }

    public void setLevel(int level) {
        this.level = level;
    }

    public String getName() {
        return invoker != null ? invoker.getName() : name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Type getType() {
        return type;
    }

    public void setType(Type type) {
        this.type = type;
    }

    public void setImplicitFlush(boolean implicitFlush) {
        this.implicitFlush = implicitFlush;
    }

    public boolean isImplicitFlush() {
        return implicitFlush;
    }

    public Memory getCallback() {
        return callback;
    }

    public void setOutput(OutputStream output) {
        this.output = output;
    }

    public OutputStream getOutput() {
        return output;
    }

    /** Isolates one embedding invocation's bytes while retaining the actual OutputBuffer identity. */
    public ScopedOutput scopeOutput(OutputStream target,boolean pureBuffered) {
        if(pureBuffered && (callback!=null || getClass()!=OutputBuffer.class))
            throw new IllegalArgumentException("Embedding output needs its exact pure buffer protocol");
        return new ScopedOutput(target,pureBuffered);
    }
    public final class ScopedOutput implements AutoCloseable {
        private final ByteArrayOutputStream previousBuffer=buffer;
        private final ByteArrayOutputStream scopedBuffer=new ByteArrayOutputStream(4098);
        private final OutputStream previousOutput=output;
        private final boolean previousBinary=binaryInBuffer,previousImplicit=implicitFlush,previousFlushed=isFlushed,previousDisabled=handlerDisabled;
        private final int previousChunk=chunkSize,previousStatus=status,previousLock=lock,previousCapabilities=capabilities;
        private final TraceInfo previousTrace=trace;
        private boolean closed;
        private ScopedOutput(OutputStream target,boolean pureBuffered) {
            buffer=scopedBuffer;binaryInBuffer=false;status=HANDLER_START;isFlushed=false;handlerDisabled=false;capabilities &= ~PHP85_ENDED;
            if(isRoot())output=target;
            if(pureBuffered){implicitFlush=false;chunkSize=0;}
        }
        @Override public void close() {
            if(closed)return;
            if(buffer!=scopedBuffer)throw new IllegalStateException("Embedding output scopes must close in reverse order");
            buffer=previousBuffer;output=previousOutput;binaryInBuffer=previousBinary;implicitFlush=previousImplicit;
            isFlushed=previousFlushed;handlerDisabled=previousDisabled;capabilities=previousCapabilities;chunkSize=previousChunk;status=previousStatus;lock=previousLock;trace=previousTrace;
            closed=true;
        }
    }


    public void setCallback(Memory callback, Invoker invoker) {
        this.handlerDisabled = false;
        this.callback = callback;
        this.invoker = invoker;
    }

    public void setCallback(Memory callback) {
        this.handlerDisabled = false;
        this.callback = callback;

        if (callback != null)
            this.invoker = Invoker.valueOf(environment, null, callback);
        else
            this.invoker = null;
    }

    public int getChunkSize() {
        return chunkSize;
    }

    public void setChunkSize(int chunkSize) {
        this.chunkSize = chunkSize;
    }

    public boolean isErase() {
        return (capabilities & 64) != 0;
    }

    public void setErase(boolean erase) {
        capabilities = (capabilities & ~64) | (erase ? 64 : 0);
    }

    public int getCapabilities() { return capabilities & 112; }
    public void setPhp85Capabilities(int flags) { capabilities = (flags & 112) | PHP85_BUFFER; }

    public Memory getContents() throws UnsupportedEncodingException {
        if (!binaryInBuffer){
            return new StringMemory(buffer.toString(environment.getDefaultCharset().name()));
        } else
            return new BinaryMemory(buffer.toByteArray());
    }

    protected void reset(){
        buffer.reset();
        binaryInBuffer = false;
    }

    public void clean() throws Throwable {
        if (invoker != null){
            doFlush(false);
        }
        status = HANDLER_CLEAN;
        reset();
    }

    /** Explicit PHP flush/final phases; callback failures retire through the same raw-byte path. */
    public void flushPhp85(boolean last) throws Throwable {
        int phase = (last ? HANDLER_FINAL : HANDLER_FLUSH)
                | (status == HANDLER_START ? HANDLER_START : 0);
        if (last && (capabilities & PHP85_ENDED) != 0) return;
        isFlushed = true;
        try { doFlush(true, phase); }
        finally { if (last) capabilities |= PHP85_ENDED; }
        if (output != null) output.flush();
    }

    /** PHP 8 cleanup phases: START is sent once; a final clean never forwards callback output. */
    public void cleanPhp85(boolean last) throws Throwable {
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

    protected void doFlush(boolean flush) throws Throwable {
        doFlush(flush, -1);
    }

    private void doFlush(boolean flush, int phase) throws Throwable {
        if (handlerDisabled) {
            if (flush && buffer.size() != 0) {
                if (output == null && parentOutput == null) return;
                byte[] pending = buffer.toByteArray();
                if (output != null) output.write(pending);
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

        if (invoker != null){
            invoker.setTrace(trace == null ? TraceInfo.UNKNOWN : trace);
            Memory[] args;
            if (!binaryInBuffer){
                args = new Memory[]{ new StringMemory(buffer.toString(environment.getDefaultCharset().name())), null };
            } else
                args = new Memory[]{ new BinaryMemory(buffer.toByteArray()), null };

            args[1] = LongMemory.valueOf(status);

            invoker.setTrace(trace == null ? TraceInfo.UNKNOWN : trace);
            Memory result;
            try {
                incLock();
                try { result = invoker.call(args).toValue(); }
                finally { decLock(); }
            } catch (Throwable failure) {
                if (phase >= 0) {
                    handlerDisabled = true;
                    if (flush) try { doFlush(true, -1); }
                    catch (Throwable cleanup) { if (cleanup != failure) failure.addSuppressed(cleanup); }
                }
                throw failure;
            }
            if (result != Memory.FALSE){
                byte[] data = result instanceof BinaryMemory || result instanceof php.runtime.memory.PhpByteStringMemory
                        ? result.getBinaryBytes(environment.getDefaultCharset()) : result.toString().getBytes(environment.getDefaultCharset());
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
                        output.write(data);
                    }

                    reset();
                    status = HANDLER_FLUSH;
                }

                return;
            }
        }

        if (flush){
            status |= HANDLER_FLUSH;
            if (output == null) {
                if (parentOutput == null) return; // Keep root contents available to the embedding host.
                parentOutput.write(buffer.toByteArray());
            } else {
                buffer.writeTo(output);
            }

            reset();
        }
    }

    public void write(String content) throws Throwable {
        if (!isLock())
            _write(content.getBytes(environment.getDefaultCharset()));
    }

    /** A modern source echo carries its actual trace into Stringable conversion. Ordinary scalars stay unboxed. */
    public static void echoPhp85(Environment env, Memory value, TraceInfo trace) throws Throwable {
        php.runtime.PhpPrimitiveCellRuntime.beforeEcho(value,env);
        Memory actual=value.toValue();
        if(actual.isObject()) actual=php.runtime.PhpByteStrings.stringValue(actual,env,trace);
        env.echo(actual);
    }

    public void write(Memory content) throws Throwable {
        if (!isLock()){
            content = content.toValue();
            if (content instanceof BinaryMemory || content instanceof php.runtime.memory.PhpByteStringMemory)
                write(content.getBinaryBytes(environment.getDefaultCharset()));
            else
                write(content.toString());
        }
    }

    protected void _write(byte[] bytes) throws Throwable {
        _write(bytes, bytes.length);
    }

    protected void _write(byte[] bytes, int length) throws Throwable {
        if (isLock()) return;
        if (handlerDisabled) {
            if (output != null) output.write(bytes, 0, length);
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
        if (!isLock()){
            binaryInBuffer = true;
            _write(bytes, length);
        }
    }

    public void write(byte[] bytes) throws Throwable {
        write(bytes, bytes.length);
    }

    public void flush() throws Throwable {
        if (!isLock()){
            status = HANDLER_FLUSH;
            doFlush(true);
            if (output != null)
                output.flush();
        }
    }

    public void close() throws Throwable {
        if ((capabilities & PHP85_BUFFER) != 0) {
            if ((capabilities & PHP85_ENDED) == 0) flushPhp85(true);
        } else if (!isBufferEmpty()) doFlush(true);
    }

    public int getBufferSize(){
        return buffer.size();
    }

    public boolean isRoot(){
        return level == 0;
    }

    public void decLock(){
        if (isRoot())
            lock--;
        else
            environment.getDefaultBuffer().decLock();
    }

    public void incLock(){
        if (isRoot())
            lock++;
        else
            environment.getDefaultBuffer().incLock();
    }

    public boolean isLock(){
        return isRoot() ? lock > 0 : environment.getDefaultBuffer().isLock();
    }

    public boolean isFlushed() {
        return isFlushed;
    }

    public Invoker getInvoker() {
        return invoker;
    }

    public OutputBuffer getParentOutput() {
        return parentOutput;
    }

    public void setTrace(TraceInfo trace) {
        this.trace = trace;
    }

    public int getStatus() {
        return status;
    }

    public void setStatus(int status) {
        this.status = status;
    }

    public boolean isBufferEmpty(){
        return buffer.size() == 0;
    }
}
