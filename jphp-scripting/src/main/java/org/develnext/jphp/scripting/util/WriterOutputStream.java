package org.develnext.jphp.scripting.util;

import java.io.IOException;
import java.io.OutputStream;
import java.io.Writer;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;
import java.util.Objects;

/** Incremental decoding preserves multibyte characters split between PHP output chunks. */
public final class WriterOutputStream extends OutputStream {
    private final Writer writer;
    private final CharsetDecoder decoder;
    private final ByteBuffer bytes=ByteBuffer.allocate(8192);
    private final CharBuffer chars=CharBuffer.allocate(8192);
    private IOException failure;
    private boolean finished;

    public WriterOutputStream(Writer writer,Charset charset) {
        this.writer=Objects.requireNonNull(writer);
        decoder=Objects.requireNonNull(charset).newDecoder()
                .onMalformedInput(CodingErrorAction.REPLACE).onUnmappableCharacter(CodingErrorAction.REPLACE);
    }
    public IOException failure(){return failure;}
    private void requireWritable() throws IOException {
        if(failure!=null)throw failure;
        if(finished)throw new IOException("ScriptContext output is finished");
    }
    @Override public void write(int value)throws IOException {write(new byte[]{(byte)value},0,1);}
    @Override public void write(byte[] values,int offset,int length)throws IOException {
        Objects.checkFromIndexSize(offset,length,values.length);requireWritable();
        try {
            while(length>0) {
                int amount=Math.min(length,bytes.remaining());bytes.put(values,offset,amount);
                offset+=amount;length-=amount;decode(false);
            }
        }catch(IOException error){failure=error;throw error;}
    }
    private void emit()throws IOException {
        chars.flip();if(chars.hasRemaining())writer.write(chars.array(),chars.position(),chars.remaining());chars.clear();
    }
    private void decode(boolean end)throws IOException {
        bytes.flip();
        try {
            while(true) {
                CoderResult result=decoder.decode(bytes,chars,end);emit();
                if(result.isError())result.throwException();
                if(!result.isOverflow())break;
            }
        }finally{bytes.compact();}
    }
    @Override public void flush()throws IOException {
        requireWritable();
        try{decode(false);writer.flush();}catch(IOException error){failure=error;throw error;}
    }
    /** Completes this invocation without closing the host's Writer. */
    public void finish()throws IOException {
        if(finished)return;
        if(failure!=null){finished=true;return;}
        try {
            decode(true);
            while(true){CoderResult result=decoder.flush(chars);emit();if(result.isError())result.throwException();if(!result.isOverflow())break;}
            writer.flush();
        }catch(IOException error){failure=error;throw error;}finally{finished=true;}
    }
    @Override public void close()throws IOException {finish();}
}
