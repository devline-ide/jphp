package org.develnext.jphp.pcre;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.ref.Reference;
import java.nio.file.Path;
import java.util.Arrays;

/**
 * Direct PCRE2 8-bit API, without Java String transcoding.
 * Library selection/extraction is deliberately outside this byte-engine boundary.
 * Only 64-bit targets are supported here; compiled handles are lexical/thread-confined.
 */
final class Pcre2Bytes {
    private static final MemorySegment NULL = MemorySegment.NULL;
    private final Arena libraryArena = Arena.ofAuto();
    private final MethodHandle config, compile, codeFree, patternInfo, errorMessage;
    private final MethodHandle compileContextCreate, compileContextFree, setCompileExtra, jitCompile, getMark;
    private final MethodHandle matchDataCreate, matchDataFree, matchContextCreate, matchContextFree;
    private final MethodHandle setMatchLimit, setDepthLimit, match, ovectorPointer, ovectorCount;

    Pcre2Bytes(Path explicitLibrary) {
        if (!explicitLibrary.isAbsolute() || ValueLayout.ADDRESS.byteSize() != 8)
            throw new IllegalArgumentException("Explicit PCRE2 library path and 64-bit ABI required");
        SymbolLookup symbols = SymbolLookup.libraryLookup(explicitLibrary, libraryArena);
        Linker linker = Linker.nativeLinker();
        config = bind(linker, symbols, "pcre2_config_8", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
        compile = bind(linker, symbols, "pcre2_compile_8", FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        codeFree = bind(linker, symbols, "pcre2_code_free_8", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
        compileContextCreate = bind(linker, symbols, "pcre2_compile_context_create_8", FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        compileContextFree = bind(linker, symbols, "pcre2_compile_context_free_8", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
        setCompileExtra = bind(linker, symbols, "pcre2_set_compile_extra_options_8", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
        jitCompile = bind(linker, symbols, "pcre2_jit_compile_8", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
        getMark = bind(linker, symbols, "pcre2_get_mark_8", FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        patternInfo = bind(linker, symbols, "pcre2_pattern_info_8", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
        errorMessage = bind(linker, symbols, "pcre2_get_error_message_8", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG));
        matchDataCreate = bind(linker, symbols, "pcre2_match_data_create_from_pattern_8", FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        matchDataFree = bind(linker, symbols, "pcre2_match_data_free_8", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
        matchContextCreate = bind(linker, symbols, "pcre2_match_context_create_8", FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        matchContextFree = bind(linker, symbols, "pcre2_match_context_free_8", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
        setMatchLimit = bind(linker, symbols, "pcre2_set_match_limit_8", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
        setDepthLimit = bind(linker, symbols, "pcre2_set_depth_limit_8", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
        match = bind(linker, symbols, "pcre2_match_8", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        ovectorPointer = bind(linker, symbols, "pcre2_get_ovector_pointer_8", FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        ovectorCount = bind(linker, symbols, "pcre2_get_ovector_count_8", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
    }

    private static MethodHandle bind(Linker linker, SymbolLookup symbols, String name, FunctionDescriptor descriptor) {
        return linker.downcallHandle(symbols.find(name).orElseThrow(() -> new UnsatisfiedLinkError(name)), descriptor);
    }

    String version() throws Throwable {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment buffer = arena.allocate(256);
            int result = (int) config.invokeExact(11, buffer); // PCRE2_CONFIG_VERSION
            if (result < 0) throw new IllegalStateException("PCRE2 version query failed: " + result);
            return buffer.getString(0);
        } finally { Reference.reachabilityFence(this); }
    }

    private int configInteger(int what) throws Throwable {
        if (what != 1 && what != 5 && what != 9 && what != 14) throw new IllegalArgumentException("Not an integer PCRE2 configuration selector");
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment value = arena.allocate(ValueLayout.JAVA_INT);
            int result = (int) config.invokeExact(what, value);
            if (result < 0) throw new IllegalStateException("PCRE2 configuration query: " + result);
            return value.get(ValueLayout.JAVA_INT, 0);
        } finally { Reference.reachabilityFence(this); }
    }
    boolean jitSupported() throws Throwable { return configInteger(1) != 0; }
    boolean unicodeSupported() throws Throwable { return configInteger(9) != 0; }
    int compiledWidths() throws Throwable { return configInteger(14); }
    int newline() throws Throwable { return configInteger(5); }

    Compiled compile(byte[] pattern, int options, int extraOptions, boolean jit) throws Throwable {
        MemorySegment context = (MemorySegment) compileContextCreate.invokeExact(NULL);
        if (context.address() == 0) throw new OutOfMemoryError("PCRE2 compile context");
        try (Arena arena = Arena.ofConfined()) {
            int extraSet = (int) setCompileExtra.invokeExact(context, extraOptions);
            if (extraSet != 0) throw new IllegalStateException("PCRE2 extra compile options: " + extraSet);
            MemorySegment error = arena.allocate(ValueLayout.JAVA_INT);
            MemorySegment offset = arena.allocate(ValueLayout.JAVA_LONG);
            MemorySegment code = (MemorySegment) compile.invokeExact(bytes(arena, pattern), (long) pattern.length, options, error, offset, context);
            if (code.address() == 0) throw new CompileFailure(error.get(ValueLayout.JAVA_INT, 0), offset.get(ValueLayout.JAVA_LONG, 0));
            try {
                // A pattern not supported by native JIT still uses the real PCRE2 interpreter, as PHP does.
                if (jit) { int result = (int) jitCompile.invokeExact(code, 1); }
                return new Compiled(code, Math.addExact(pattern.length, 1));
            } catch (Throwable failure) { codeFree.invokeExact(code); throw failure; }
        } finally { compileContextFree.invokeExact(context); Reference.reachabilityFence(this); }
    }

    String errorMessage(int error) throws Throwable {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment buffer = arena.allocate(512);
            int result = (int) errorMessage.invokeExact(error, buffer, 512L);
            if (result < 0) throw new IllegalStateException("PCRE2 error-message query failed: " + result);
            return buffer.getString(0);
        } finally { Reference.reachabilityFence(this); }
    }

    private static MemorySegment bytes(Arena arena, byte[] bytes) {
        MemorySegment segment = arena.allocate(Math.max(bytes.length, 1), 1);
        segment.asSlice(0, bytes.length).copyFrom(MemorySegment.ofArray(bytes));
        return segment;
    }

    static final class CompileFailure extends Exception {
        final int error;
        final long byteOffset;
        CompileFailure(int error, long byteOffset) {
            super("PCRE2 compile error " + error + " at byte " + byteOffset);
            this.error = error; this.byteOffset = byteOffset;
        }
    }
    record NamedCapture(int number, byte[] name) { }
    record Match(int result, long[] offsets, byte[] mark) { } // -1 pairs mean PCRE2_UNSET; never character offsets.

    final class Compiled implements AutoCloseable {
        private final Thread thread = Thread.currentThread();
        private MemorySegment code;
        private final int maxMarkBytes;
        final int captureCount;
        final NamedCapture[] names;
        private Compiled(MemorySegment code, int maxMarkBytes) throws Throwable {
            this.code = code; this.maxMarkBytes = maxMarkBytes;
            captureCount = infoInt(4); // PCRE2_INFO_CAPTURECOUNT
            int count = infoInt(17); // PCRE2_INFO_NAMECOUNT
            names = new NamedCapture[count];
            if (count != 0) {
                int entrySize = infoInt(18); // PCRE2_INFO_NAMEENTRYSIZE
                if (entrySize < 3) throw new IllegalStateException("Invalid PCRE2 name table");
                try (Arena arena = Arena.ofConfined()) {
                    MemorySegment pointer = arena.allocate(ValueLayout.ADDRESS);
                    int result = (int) patternInfo.invokeExact(code, 19, pointer); // PCRE2_INFO_NAMETABLE
                    if (result != 0) throw new IllegalStateException("PCRE2 name table query: " + result);
                    MemorySegment table = pointer.get(ValueLayout.ADDRESS, 0).reinterpret(Math.multiplyExact((long) count, entrySize));
                    for (int i = 0; i < count; i++) {
                        byte[] entry = table.asSlice((long) i * entrySize, entrySize).toArray(ValueLayout.JAVA_BYTE);
                        int number = (Byte.toUnsignedInt(entry[0]) << 8) | Byte.toUnsignedInt(entry[1]);
                        int end = 2; while (end < entry.length && entry[end] != 0) end++;
                        if (number > captureCount || end == entry.length) throw new IllegalStateException("Invalid PCRE2 name entry");
                        names[i] = new NamedCapture(number, Arrays.copyOfRange(entry, 2, end));
                    }
                }
            }
        }
        private int infoInt(int what) throws Throwable {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment output = arena.allocate(ValueLayout.JAVA_INT);
                int result = (int) patternInfo.invokeExact(code, what, output);
                if (result != 0) throw new IllegalStateException("PCRE2 pattern info: " + result);
                return output.get(ValueLayout.JAVA_INT, 0);
            }
        }
        private void checkThread() {
            if (thread != Thread.currentThread() || code.address() == 0) throw new IllegalStateException("Closed or foreign PCRE2 compiled pattern");
        }
        Match match(byte[] subject, long startByte, int options, int matchLimit, int depthLimit) throws Throwable {
            checkThread();
            if (startByte < 0 || startByte > subject.length)
                throw new IllegalArgumentException("PHP layer must validate offset before native entry");
            MemorySegment data = (MemorySegment) matchDataCreate.invokeExact(code, NULL);
            if (data.address() == 0) throw new OutOfMemoryError("PCRE2 match data");
            try {
                MemorySegment context = (MemorySegment) matchContextCreate.invokeExact(NULL);
                if (context.address() == 0) throw new OutOfMemoryError("PCRE2 match context");
                try (Arena arena = Arena.ofConfined()) {
                    int matchSet = (int) setMatchLimit.invokeExact(context, matchLimit);
                    int depthSet = (int) setDepthLimit.invokeExact(context, depthLimit);
                    if (matchSet != 0 || depthSet != 0) throw new IllegalStateException("PCRE2 limits rejected");
                    int result = (int) Pcre2Bytes.this.match.invokeExact(code, bytes(arena, subject), (long) subject.length, startByte, options, data, context);
                    if (result < 0) return new Match(result, new long[0], null);
                    int count = (int) ovectorCount.invokeExact(data);
                    if (count != captureCount + 1) throw new IllegalStateException("PCRE2 capture vector shape changed");
                    MemorySegment vector = ((MemorySegment) ovectorPointer.invokeExact(data)).reinterpret(Math.multiplyExact((long) count, 16));
                    MemorySegment markPointer = (MemorySegment) getMark.invokeExact(data);
                    byte[] mark = null;
                    if (markPointer.address() != 0) {
                        // MARK text is owned by the live compiled pattern. Copy before freeing code/data.
                        MemorySegment text = markPointer.reinterpret(maxMarkBytes);
                        int length = 0; while (length < maxMarkBytes && text.get(ValueLayout.JAVA_BYTE, length) != 0) length++;
                        if (length == maxMarkBytes) throw new IllegalStateException("Unterminated PCRE2 MARK");
                        mark = text.asSlice(0, length).toArray(ValueLayout.JAVA_BYTE);
                    }
                    return new Match(result, vector.toArray(ValueLayout.JAVA_LONG), mark);
                } finally { matchContextFree.invokeExact(context); }
            } finally { matchDataFree.invokeExact(data); Reference.reachabilityFence(Pcre2Bytes.this); }
        }
        FilterSession filter() throws Throwable {
            checkThread();
            return new FilterSession();
        }
        /** One array filter: reuse data/context, release each subject buffer before reading the next value. */
        final class FilterSession implements AutoCloseable {
            private MemorySegment data = NULL, context = NULL;
            private final int options;
            private int matchLimit, depthLimit;
            private boolean configured, closed;
            private FilterSession() throws Throwable {
                try {
                    options = (infoInt(0) & 0x00080000) != 0 ? 0 : 0x40000000;
                    data = (MemorySegment) matchDataCreate.invokeExact(code, NULL);
                    if (data.address() == 0) throw new OutOfMemoryError("PCRE2 filter match data");
                    context = (MemorySegment) matchContextCreate.invokeExact(NULL);
                    if (context.address() == 0) throw new OutOfMemoryError("PCRE2 filter match context");
                } catch (Throwable failure) {
                    closed = true;
                    try { release(); } catch (Throwable cleanup) { if (cleanup != failure) failure.addSuppressed(cleanup); }
                    throw failure;
                }
            }
            int test(byte[] subject, int matchLimit, int depthLimit) throws Throwable {
                Compiled.this.checkThread();
                if (closed) throw new IllegalStateException("Closed PCRE2 filter session");
                try (Arena arena = Arena.ofConfined()) {
                    if (!configured || this.matchLimit != matchLimit || this.depthLimit != depthLimit) {
                        int matchSet = (int) setMatchLimit.invokeExact(context, matchLimit);
                        int depthSet = (int) setDepthLimit.invokeExact(context, depthLimit);
                        if (matchSet != 0 || depthSet != 0) throw new IllegalStateException("PCRE2 filter limits rejected");
                        this.matchLimit = matchLimit; this.depthLimit = depthLimit; configured = true;
                    }
                    // A new UTF subject is validated every time. No offsets/MARK leave the native match data.
                    return (int) Pcre2Bytes.this.match.invokeExact(code, bytes(arena, subject), (long) subject.length, 0L, options, data, context);
                } finally { Reference.reachabilityFence(Pcre2Bytes.this); }
            }
            private void release() throws Throwable {
                try {
                    if (context.address() != 0) {
                        MemorySegment previous = context; context = NULL;
                        matchContextFree.invokeExact(previous);
                    }
                } finally {
                    if (data.address() != 0) {
                        MemorySegment previous = data; data = NULL;
                        matchDataFree.invokeExact(previous);
                    }
                }
            }
            @Override public void close() {
                if (thread != Thread.currentThread()) throw new IllegalStateException("Foreign PCRE2 filter session");
                if (closed) return;
                closed = true;
                try { release(); }
                catch (Throwable failure) { throw new IllegalStateException("PCRE2 filter session release failed", failure); }
                finally { Reference.reachabilityFence(Pcre2Bytes.this); }
            }
        }
        MatchSession scan(byte[] subject, int matchLimit, int depthLimit) throws Throwable {
            checkThread();
            return new MatchSession(subject, matchLimit, depthLimit);
        }
        /** One lexical global-match call: subject bytes, context and match data are allocated once. */
        final class MatchSession implements AutoCloseable {
            private final Arena arena = Arena.ofConfined();
            private MemorySegment subjectBytes = NULL, data = NULL, context = NULL;
            private final int subjectLength;
            private int matchLimit, depthLimit;
            final boolean utf;
            private boolean closed;
            private MatchSession(byte[] subject, int matchLimit, int depthLimit) throws Throwable {
                subjectLength = subject.length;
                try {
                    utf = (infoInt(0) & 0x00080000) != 0; // PCRE2_INFO_ALLOPTIONS / PCRE2_UTF, including (*UTF).
                    subjectBytes = bytes(arena, subject);
                    data = (MemorySegment) matchDataCreate.invokeExact(code, NULL);
                    if (data.address() == 0) throw new OutOfMemoryError("PCRE2 global match data");
                    context = (MemorySegment) matchContextCreate.invokeExact(NULL);
                    if (context.address() == 0) throw new OutOfMemoryError("PCRE2 global match context");
                    int matchSet = (int) setMatchLimit.invokeExact(context, matchLimit);
                    int depthSet = (int) setDepthLimit.invokeExact(context, depthLimit);
                    if (matchSet != 0 || depthSet != 0) throw new IllegalStateException("PCRE2 limits rejected");
                    this.matchLimit = matchLimit; this.depthLimit = depthLimit;
                } catch (Throwable failure) {
                    closed = true;
                    try { release(); } catch (Throwable cleanup) { if (cleanup != failure) failure.addSuppressed(cleanup); }
                    throw failure;
                }
            }
            /** A PHP callback may change INI limits before the next native match. */
            void limits(int matchLimit, int depthLimit) throws Throwable {
                Compiled.this.checkThread();
                if (closed) throw new IllegalStateException("Closed PCRE2 global match session");
                if (this.matchLimit == matchLimit && this.depthLimit == depthLimit) return;
                int matchSet = (int) setMatchLimit.invokeExact(context, matchLimit);
                int depthSet = (int) setDepthLimit.invokeExact(context, depthLimit);
                if (matchSet != 0 || depthSet != 0) throw new IllegalStateException("PCRE2 limits rejected");
                this.matchLimit = matchLimit; this.depthLimit = depthLimit;
            }
            Match match(long startByte, int options) throws Throwable {
                Compiled.this.checkThread();
                if (closed) throw new IllegalStateException("Closed PCRE2 global match session");
                if (startByte < 0 || startByte > subjectLength)
                    throw new IllegalArgumentException("PHP layer must validate offset before native entry");
                try {
                    int result = (int) Pcre2Bytes.this.match.invokeExact(code, subjectBytes, (long) subjectLength, startByte, options, data, context);
                    if (result < 0) return new Match(result, new long[0], null);
                    int count = (int) ovectorCount.invokeExact(data);
                    if (count != captureCount + 1) throw new IllegalStateException("PCRE2 capture vector shape changed");
                    MemorySegment vector = ((MemorySegment) ovectorPointer.invokeExact(data)).reinterpret(Math.multiplyExact((long) count, 16));
                    MemorySegment markPointer = (MemorySegment) getMark.invokeExact(data);
                    byte[] mark = null;
                    if (markPointer.address() != 0) {
                        MemorySegment text = markPointer.reinterpret(maxMarkBytes);
                        int length = 0;
                        while (length < maxMarkBytes && text.get(ValueLayout.JAVA_BYTE, length) != 0) length++;
                        if (length == maxMarkBytes) throw new IllegalStateException("Unterminated PCRE2 MARK");
                        mark = text.asSlice(0, length).toArray(ValueLayout.JAVA_BYTE);
                    }
                    // Both are copied before the next match overwrites the native match data.
                    return new Match(result, vector.toArray(ValueLayout.JAVA_LONG), mark);
                } finally { Reference.reachabilityFence(Pcre2Bytes.this); }
            }
            private void release() throws Throwable {
                try {
                    if (context.address() != 0) {
                        MemorySegment previous = context; context = NULL;
                        matchContextFree.invokeExact(previous);
                    }
                } finally {
                    try {
                        if (data.address() != 0) {
                            MemorySegment previous = data; data = NULL;
                            matchDataFree.invokeExact(previous);
                        }
                    } finally { arena.close(); subjectBytes = NULL; }
                }
            }
            @Override public void close() {
                if (thread != Thread.currentThread()) throw new IllegalStateException("Foreign PCRE2 global match session");
                if (closed) return;
                closed = true;
                try { release(); }
                catch (Throwable failure) { throw new IllegalStateException("PCRE2 global match session release failed", failure); }
                finally { Reference.reachabilityFence(Pcre2Bytes.this); }
            }
        }
        @Override public void close() {
            checkThread();
            try { codeFree.invokeExact(code); code = NULL; }
            catch (Throwable failure) { throw new IllegalStateException("PCRE2 code release failed", failure); }
            finally { Reference.reachabilityFence(Pcre2Bytes.this); }
        }
    }
}
