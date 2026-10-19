package org.develnext.jphp.scripting.util;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.IllegalCharsetNameException;
import java.nio.charset.UnsupportedCharsetException;
import java.util.Objects;

/** Pull-based character encoding; the adapter owns and closes its Reader exactly once. */
public class ReaderInputStream extends InputStream {
    private final Reader reader;
    private final CharsetEncoder encoder;
    private final CharBuffer characters = CharBuffer.allocate(8192);
    private final ByteBuffer bytes;
    private boolean endOfInput;
    private boolean flushing;
    private boolean finished;
    private boolean closed;
    private boolean readerClosed;
    private Throwable readFailure;

    public ReaderInputStream(Reader reader) throws IOException {
        this(reader, null);
    }

    public ReaderInputStream(Reader reader, String encoding) throws IOException {
        this.reader = Objects.requireNonNull(reader, "reader");
        Charset charset;
        try {
            charset = encoding == null ? Charset.defaultCharset() : Charset.forName(encoding);
        } catch (IllegalCharsetNameException | UnsupportedCharsetException invalid) {
            UnsupportedEncodingException failure = new UnsupportedEncodingException(encoding);
            failure.initCause(invalid);
            throw failure;
        }
        encoder = charset.newEncoder().onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE);
        bytes = ByteBuffer.allocate(Math.max(8192, (int) Math.ceil(encoder.maxBytesPerChar())));
        characters.limit(0);
        bytes.limit(0);
    }

    /** Exact pull/encoding/EOF failure, before any compiler-specific exception translation. */
    public Throwable getReadFailure() {
        return readFailure;
    }

    private void ensureOpen() throws IOException {
        if (closed) throw new IOException("Stream closed");
    }

    private void closeReader() throws IOException {
        if (!readerClosed) {
            readerClosed = true;
            reader.close();
        }
    }

    private boolean fill() throws IOException {
        ensureOpen();
        if (bytes.hasRemaining()) return true;
        if (finished) return false;
        bytes.clear();
        try {
            while (bytes.position() == 0 && !finished) {
                CoderResult result = flushing ? encoder.flush(bytes) : encoder.encode(characters, bytes, endOfInput);
                if (result.isError()) result.throwException();
                if (result.isOverflow()) break;
                if (flushing) {
                    finished = true;
                    closeReader();
                } else if (endOfInput) {
                    flushing = true;
                } else if (bytes.position() > 0) {
                    break;
                } else {
                    // compact retains an incomplete surrogate across arbitrarily small Reader chunks.
                    characters.compact();
                    int count = reader.read(characters.array(), characters.position(), characters.remaining());
                    if (count < 0) endOfInput = true;
                    else characters.position(characters.position() + count);
                    characters.flip();
                }
            }
            bytes.flip();
            return bytes.hasRemaining();
        } catch (IOException | RuntimeException | Error failure) {
            readFailure = failure;
            closed = true;
            try {closeReader();} catch (IOException | RuntimeException | Error cleanup) {
                if (cleanup != failure) failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    @Override
    public int read() throws IOException {
        return fill() ? bytes.get() & 255 : -1;
    }

    @Override
    public int read(byte[] target, int offset, int length) throws IOException {
        Objects.checkFromIndexSize(offset, length, target.length);
        if (length == 0) return 0;
        if (!fill()) return -1;
        int count = Math.min(length, bytes.remaining());
        bytes.get(target, offset, count);
        return count;
    }

    @Override
    public int available() throws IOException {
        ensureOpen();
        return bytes.remaining();
    }

    @Override
    public void close() throws IOException {
        if (closed) return;
        closed = true;
        closeReader();
    }
}
