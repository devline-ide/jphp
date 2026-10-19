package php.runtime.ext.core;

import php.runtime.Memory;
import php.runtime.env.Environment;
import php.runtime.env.TraceInfo;
import php.runtime.exceptions.support.ErrorType;
import php.runtime.ext.support.compile.FunctionsContainer;
import php.runtime.invoke.Invoker;
import php.runtime.memory.ArrayMemory;
import php.runtime.memory.LongMemory;
import php.runtime.output.OutputBuffer;

import java.io.UnsupportedEncodingException;
import java.util.ArrayList;

public class OutputFunctions extends FunctionsContainer {

    public static Memory print(Environment environment, Memory memory){
        environment.echo(memory);
        return Memory.CONST_INT_1;
    }

    public static void flush(Environment env) throws Throwable {
        OutputBuffer root = env.getDefaultBuffer();
        if (root != null) {
            root.flush();
        }
    }

    @php.runtime.annotation.Reflection.Signature(value = {
            @php.runtime.annotation.Reflection.Arg(value = "callback", optional = @php.runtime.annotation.Reflection.Optional("null")),
            @php.runtime.annotation.Reflection.Arg(value = "chunk_size", type = php.runtime.common.HintType.INT,
                    optional = @php.runtime.annotation.Reflection.Optional("0")),
            @php.runtime.annotation.Reflection.Arg(value = "flags", type = php.runtime.common.HintType.INT,
                    optional = @php.runtime.annotation.Reflection.Optional("112"))
    }, result = @php.runtime.annotation.Reflection.Arg(type = php.runtime.common.HintType.BOOLEAN))
    public static Memory ob_start(Environment env, TraceInfo trace, Memory outputCallback, Memory _chunkSize,
                                  Memory erase){
        if (env.getModuleManager().getPhpLanguageProfile(trace) == php.runtime.common.PhpLanguageProfile.PHP_8_5)
            return startPhp85(env, trace, outputCallback, _chunkSize, erase);
        Invoker invoker;
        if (!outputCallback.isNull()){
            invoker = expectingCallback(env, trace, 1, outputCallback);
            if (invoker == null)
                return Memory.FALSE;
            invoker.check("ob_start", trace);
        }

        switch (_chunkSize.getRealType()){
            case ARRAY:case STRING:case OBJECT:
                env.warning(trace,
                        "ob_start() expects parameter 2 to be long, "+_chunkSize.getRealType().toString()+" given");
                return Memory.NULL;
        }

        switch (erase.getRealType()){
            case ARRAY:case STRING:case OBJECT:
                env.warning(trace,
                        "ob_start() expects parameter 3 to be long, "+erase.getRealType().toString()+" given");
                return Memory.NULL;
        }

        int chunkSize = _chunkSize.toInteger();
        if (chunkSize < 0)
            chunkSize = 0;

        if (chunkSize < 0){
            env.warning(trace, "ob_start(): chunk_size must be grater or equal than zero");
            return Memory.FALSE;
        }

        OutputBuffer buffer = env.peekOutputBuffer();
        if (buffer != null && buffer.isLock())
            env.error(trace, "ob_start(): Cannot use output buffering in output buffering display handlers");

        env.pushOutputBuffer(outputCallback, chunkSize, erase.toBoolean());
        return Memory.TRUE;
    }

    public static Memory ob_start(Environment env, TraceInfo trace, Memory outputCallback, Memory chunkSize){
        return ob_start(env, trace, outputCallback, chunkSize, defaultOutputFlags(env, trace));
    }

    public static Memory ob_start(Environment env, TraceInfo trace, Memory outputCallback){
        return ob_start(env, trace, outputCallback, Memory.CONST_INT_0, defaultOutputFlags(env, trace));
    }

    public static Memory ob_start(Environment env, TraceInfo trace){
        return ob_start(env, trace, Memory.NULL, Memory.CONST_INT_0, defaultOutputFlags(env, trace));
    }

    private static final php.runtime.reflection.support.TypeChecker OUTPUT_INTEGER =
            php.runtime.reflection.support.TypeChecker.of(php.runtime.common.HintType.INT);

    private static long outputInteger(Environment env, TraceInfo trace, Memory input, int index, String name) {
        Memory value = input.toValue();
        if (value.getRealType() == Memory.Type.INT) return value.toLong();
        boolean strict = php.runtime.invoke.NativeFunctionContext.isStrict(env, trace, "ob_start");
        if (value.isNull() && !strict) {
            env.error(trace, ErrorType.E_DEPRECATED,
                    "ob_start(): Passing null to parameter #%s ($%s) of type int is deprecated", index, name);
            return 0;
        }
        Memory converted = OUTPUT_INTEGER.apply(env, value, false, strict);
        if (converted == null) {
            String given = value.isResource() ? "resource" : value.getRealType() == Memory.Type.BOOL
                    ? (value.toBoolean() ? "true" : "false") : Php85LangFunctions.get_debug_type(value);
            env.exception(trace, php.runtime.lang.exception.BaseTypeError.class,
                    "ob_start(): Argument #%s ($%s) must be of type int, %s given", index, name, given);
            return 0;
        }
        Memory numeric = value.isString() ? php.runtime.PhpNumericString.parseStrict(value.toString()) : value;
        if (numeric != null) php.runtime.PhpNumericConversions.warnIntegerPrecisionLoss(value, numeric, env, trace);
        return converted.toLong();
    }

    private static Memory startPhp85(Environment env, TraceInfo trace, Memory callback, Memory chunk, Memory flags) {
        long size = outputInteger(env, trace, chunk, 2, "chunk_size");
        int capabilities = (int) outputInteger(env, trace, flags, 3, "flags");
        php.runtime.invoke.Invoker selected = null;
        if (!callback.isNull()) {
            env.pushCall(trace,null,new Memory[]{callback},"ob_start",null,null);
            try (var frame = php.runtime.invoke.PhpCallFrame.acquireInternalFromCallable(callback, env, trace)) {
                selected=frame.resolvedTarget();
            } catch (php.runtime.invoke.PhpMethodResolver.InvalidCallback invalid) {
                env.error(trace, ErrorType.E_WARNING, "ob_start(): %s", invalid.getMessage());
                env.error(trace, ErrorType.E_NOTICE, "ob_start(): Failed to create buffer");
                return Memory.FALSE;
            } finally {env.popCall();}
        }
        OutputBuffer current = env.peekOutputBuffer();
        if (current != null && current.isLock())
            env.error(trace, "ob_start(): Cannot use output buffering in output buffering display handlers");
        int chunkSize = (int) Math.min(Integer.MAX_VALUE, Math.max(0L, size));
        // The selected target is authoritative; creating the buffer must not resolve or autoload again.
        OutputBuffer created = env.pushOutputBuffer(null, chunkSize, true);
        created.setCallback(callback,selected);
        created.setPhp85Capabilities(capabilities);
        return Memory.TRUE;
    }

    private static Memory defaultOutputFlags(Environment env, TraceInfo trace) {
        return env.getModuleManager().getPhpLanguageProfile(trace) == php.runtime.common.PhpLanguageProfile.PHP_8_5
                ? LongMemory.valueOf(OutputConstants.PHP_OUTPUT_HANDLER_STDFLAGS) : Memory.TRUE;
    }

    private static boolean requireOutputCapability(Environment env, TraceInfo trace, OutputBuffer buffer,
                                                   int capability, String operation, String action) {
        if ((buffer.getCapabilities() & capability) != 0) return true;
        env.error(trace, ErrorType.E_NOTICE, "%s(): Failed to %s buffer of %s (%d)",
                operation, action, buffer.getName(), buffer.getLevel() - 1);
        return false;
    }

    public static boolean ob_clean(Environment env, TraceInfo trace) throws Throwable {
        OutputBuffer buffer = env.peekOutputBuffer();
        if (buffer != null && !buffer.isRoot()){
            boolean modern = env.getModuleManager().getPhpLanguageProfile(trace) == php.runtime.common.PhpLanguageProfile.PHP_8_5;
            if (modern && buffer.isLock()) {
                env.error(trace, "ob_clean(): Cannot use output buffering in output buffering display handlers");
                return false;
            }
            if (modern && !requireOutputCapability(env, trace, buffer, 16, "ob_clean", "delete")) return false;
            buffer.setTrace(trace);
            if (modern) buffer.cleanPhp85(false); else buffer.clean();
            return true;
        } else {
            env.error(trace, ErrorType.E_NOTICE, "ob_clean(): failed to delete buffer. No buffer to delete");
            return false;
        }
    }

    public static boolean ob_end_clean(Environment env, TraceInfo trace) throws Throwable {
        OutputBuffer buffer = env.peekOutputBuffer();
        if (buffer != null && !buffer.isRoot()){
            boolean modern = env.getModuleManager().getPhpLanguageProfile(trace) == php.runtime.common.PhpLanguageProfile.PHP_8_5;
            if (modern && buffer.isLock()) {
                env.error(trace, "ob_end_clean(): Cannot use output buffering in output buffering display handlers");
                return false;
            }
            if (modern && !requireOutputCapability(env, trace, buffer, 64, "ob_end_clean", "discard")) return false;
            buffer.setTrace(trace);
            if (modern) {
                try { buffer.cleanPhp85(true); } finally { env.popOutputBuffer(); }
            } else {
                buffer.setStatus(OutputBuffer.HANDLER_FINAL);
                buffer.clean();
                env.popOutputBuffer();
            }
            return true;
        } else {
            env.error(trace, ErrorType.E_NOTICE, "ob_end_clean(): failed to delete buffer. No buffer to delete");
            return false;
        }
    }

    public static boolean ob_end_flush(Environment env, TraceInfo trace) throws Throwable {
        OutputBuffer buffer = env.peekOutputBuffer();
        boolean modern = env.getModuleManager().getPhpLanguageProfile(trace) == php.runtime.common.PhpLanguageProfile.PHP_8_5;
        if (buffer != null && !buffer.isRoot()){
            buffer.setTrace(trace);
            if (modern) {
                if (buffer.isLock()) { env.error(trace, "ob_end_flush(): Cannot use output buffering in output buffering display handlers"); return false; }
                if (!requireOutputCapability(env, trace, buffer, 64, "ob_end_flush", "send")) return false;
                try { buffer.flushPhp85(true); } finally { env.popOutputBuffer(); }
            } else {
                buffer.flush();
                env.popOutputBuffer();
            }
            return true;
        } else {
            env.error(trace, ErrorType.E_NOTICE, modern ? "ob_end_flush(): Failed to delete and flush buffer. No buffer to delete or flush" : "ob_end_flush(): failed to delete and flush buffer. No buffer to delete or flush");
            return false;
        }
    }

    public static Memory ob_get_contents(Environment env, TraceInfo trace) throws UnsupportedEncodingException {
        OutputBuffer buffer = env.peekOutputBuffer();
        if (buffer == null || buffer.isRoot())
            return Memory.FALSE;

        return buffer.getContents();
    }

    public static boolean ob_flush(Environment env, TraceInfo trace) throws Throwable {
        OutputBuffer buffer = env.peekOutputBuffer();
        boolean modern = env.getModuleManager().getPhpLanguageProfile(trace) == php.runtime.common.PhpLanguageProfile.PHP_8_5;
        if (buffer != null && !buffer.isRoot()) {
            buffer.setTrace(trace);
            if (modern) {
                if (buffer.isLock()) { env.error(trace, "ob_flush(): Cannot use output buffering in output buffering display handlers"); return false; }
                if (!requireOutputCapability(env, trace, buffer, 32, "ob_flush", "flush")) return false;
                buffer.flushPhp85(false);
            } else buffer.flush();
            return true;
        } else {
            env.error(trace, ErrorType.E_NOTICE, modern ? "ob_flush(): Failed to flush buffer. No buffer to flush" : "ob_flush(): failed to flush buffer. No buffer to flush");
            return false;
        }
    }

    public static Memory ob_get_clean(Environment env, TraceInfo trace) throws Throwable {
        OutputBuffer buffer = env.peekOutputBuffer();
        if (buffer != null && !buffer.isRoot()){
            Memory result = buffer.getContents();
            boolean modern = env.getModuleManager().getPhpLanguageProfile(trace) == php.runtime.common.PhpLanguageProfile.PHP_8_5;
            if (modern && buffer.isLock()) {
                env.error(trace, "ob_get_clean(): Cannot use output buffering in output buffering display handlers");
                return Memory.FALSE;
            }
            if (modern && !requireOutputCapability(env, trace, buffer, 64, "ob_get_clean", "discard")) {
                env.error(trace, ErrorType.E_NOTICE, "ob_get_clean(): Failed to delete buffer of %s (%d)", buffer.getName(), buffer.getLevel() - 1);
                return result;
            }
            buffer.setTrace(trace);
            if (modern) {
                try { buffer.cleanPhp85(true); } finally { env.popOutputBuffer(); }
            } else buffer.clean();
            return result;
        } else {
            //env.error(trace, ErrorType.E_NOTICE, "ob_get_clean(): failed to delete and clean buffer. No buffer to delete or clean");
            return Memory.FALSE;
        }
    }

    public static Memory ob_get_flush(Environment env, TraceInfo trace) throws Throwable {
        OutputBuffer buffer = env.peekOutputBuffer();
        boolean modern = env.getModuleManager().getPhpLanguageProfile(trace) == php.runtime.common.PhpLanguageProfile.PHP_8_5;
        if (buffer != null && (!modern || !buffer.isRoot())){
            if (buffer.isLock()){
                env.error(trace, "ob_get_flush(): Cannot use output buffering in output buffering display handlers");
                return Memory.FALSE;
            }

            Memory result = buffer.getContents();
            if (modern && !requireOutputCapability(env, trace, buffer, 64, "ob_get_flush", "send")) {
                env.error(trace, ErrorType.E_NOTICE, "ob_get_flush(): Failed to delete buffer of %s (%d)", buffer.getName(), buffer.getLevel() - 1);
                return result;
            }
            buffer.setTrace(trace);
            if (modern) {
                try { buffer.flushPhp85(true); } finally { env.popOutputBuffer(); }
            } else buffer.flush();
            return result;
        } else {
            env.error(trace, ErrorType.E_NOTICE, modern ? "ob_get_flush(): Failed to delete and flush buffer. No buffer to delete or flush" : "ob_get_flush(): failed to delete and flush buffer. No buffer to delete or flush");
            return Memory.FALSE;
        }
    }

    public static Memory ob_get_length(Environment env) throws Throwable {
        OutputBuffer buffer = env.peekOutputBuffer();
        if (buffer != null && !buffer.isRoot()){
            return LongMemory.valueOf(buffer.getBufferSize());
        } else
            return Memory.FALSE;
    }

    public static Memory ob_get_level(Environment env) throws Throwable {
        OutputBuffer buffer = env.peekOutputBuffer();
        if (buffer != null){
            return LongMemory.valueOf(buffer.getLevel());
        } else
            return Memory.FALSE;
    }

    public static void ob_implicit_flush(Environment env, TraceInfo trace, Memory value){
        OutputBuffer root = env.getDefaultBuffer();
        switch (value.getRealType()){
            case ARRAY:
            case STRING:
            case OBJECT:
                env.warning(trace,
                        "ob_implicit_flush() expects parameter 1 to be long, " + value.getRealType().toString() + " given"
                );
                return;
        }
        if (root != null)
            root.setImplicitFlush(value.toBoolean());
    }

    public static void ob_implicit_flush(Environment env, TraceInfo trace){
        ob_implicit_flush(env, trace, Memory.TRUE);
    }

    private static ArrayMemory _get_status(OutputBuffer buffer){
        ArrayMemory result = new ArrayMemory();
        result.refOfIndex("name").assign(buffer.getName());
        result.refOfIndex("type").assign(buffer.getInvoker() == null ? 0 : 1);
        result.refOfIndex("flags").assign(buffer.getStatus());
        result.refOfIndex("level").assign(buffer.getLevel() - 1);
        result.refOfIndex("chunk_size").assign(buffer.getChunkSize());
        result.refOfIndex("buffer_used").assign(buffer.getBufferSize());
        return result;
    }

    public static Memory ob_get_status(Environment env, boolean fullStatus){
        if (fullStatus){
            ArrayMemory result = new ArrayMemory();
            OutputBuffer peek = env.peekOutputBuffer();

            ArrayList<OutputBuffer> list = new ArrayList<OutputBuffer>();
            while (peek != null && !peek.isRoot()){
                list.add(0, peek);
                peek = peek.getParentOutput();
            }

            for(OutputBuffer e : list){
                result.add(_get_status(e));
            }

            return result;
        } else {
            OutputBuffer buffer = env.peekOutputBuffer();
            if (buffer == null || buffer.isRoot())
                return new ArrayMemory().toConstant();

            return _get_status(buffer).toConstant();
        }
    }

    public static Memory ob_list_handlers(Environment env){
        ArrayMemory result = new ArrayMemory();
        OutputBuffer peek = env.peekOutputBuffer();

        ArrayList<OutputBuffer> list = new ArrayList<OutputBuffer>();
        while (peek != null && !peek.isRoot()){
            list.add(0, peek);
            peek = peek.getParentOutput();
        }

        for(OutputBuffer e : list){
            result.refOfPush().assign(e.getName());
        }

        return result.toConstant();
    }

    public static Memory ob_get_status(Environment env){
        return ob_get_status(env, false);
    }
}
