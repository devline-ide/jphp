package scripting;

import org.develnext.jphp.scripting.JPHPScriptEngine;
import org.develnext.jphp.scripting.util.ReaderInputStream;
import org.junit.Test;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.Environment;
import javax.script.*;
import java.io.*;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.Assert.*;

/** Pull encoding and real JSR-223 compiles; no worker-count timing or application-speed assertion. */
public class ReaderCompilationTest {
    private static class Chunks extends Reader {
        final String text;final int chunk;int offset,closes,reads,maxRequest;boolean zero;
        final List<Thread> callers=new ArrayList<>();
        Chunks(String text,int chunk){this.text=text;this.chunk=chunk;}
        @Override public int read(char[] target,int off,int len)throws IOException {
            readers();maxRequest=Math.max(maxRequest,len);
            if(zero){zero=false;return 0;}
            if(offset==text.length())return -1;
            int n=Math.min(len,Math.min(chunk,text.length()-offset));text.getChars(offset,offset+n,target,off);offset+=n;return n;
        }
        void readers(){reads++;callers.add(Thread.currentThread());}
        @Override public void close()throws IOException{closes++;}
        void callerOnly(){assertFalse(callers.isEmpty());for(Thread actual:callers)assertSame("Reader executed on a helper thread",Thread.currentThread(),actual);}
    }
    private static JPHPScriptEngine engine(boolean modern)throws Exception {
        var engine=new JPHPScriptEngine();var field=JPHPScriptEngine.class.getDeclaredField("environment");field.setAccessible(true);
        ((Environment)field.get(engine)).getScope().setPhpLanguageProfile(modern?PhpLanguageProfile.PHP_8_5:PhpLanguageProfile.JPHP_LEGACY);return engine;
    }
    @Test public void realReaderUnicodeCompilesAcrossSingleCharacterChunksWithoutWorker()throws Exception {
        String value="\u0420\u0443\u0441\u4e2d\ud83d\ude80";
        for(boolean modern:new boolean[]{false,true}) {
            var input=new Chunks("<?php return '"+value+"';",1);var engine=engine(modern);
            assertEquals(value,engine.compile(input).eval().toString());input.callerOnly();assertEquals(1,input.closes);
        }
    }
    @Test public void explicitCharsetsPreserveSurrogateReplacementAndEncoderState()throws Exception {
        String text="x".repeat(8191)+"\ud83d\ude80|\ud800|\udc00|\u00e9|\u4e2d";
        for(String encoding:new String[]{"UTF-8","UTF-16","UTF-16LE","ISO-8859-1"})for(int chunk:new int[]{1,7,8192}) {
            var input=new Chunks(text,chunk);byte[] actual;
            try(var stream=new ReaderInputStream(input,encoding)){actual=stream.readAllBytes();assertEquals(-1,stream.read());}
            assertArrayEquals(encoding+" chunk="+chunk,text.getBytes(Charset.forName(encoding)),actual);assertEquals(1,input.closes);input.callerOnly();
        }
    }
    @Test public void publicDefaultCharsetConstructorKeepsItsJavaEncodingContract()throws Exception {
        String text="\u0420\u00e9\ud83d\ude80\ud800";var input=new Chunks(text,1);
        try(var stream=new ReaderInputStream(input)){assertArrayEquals(text.getBytes(Charset.defaultCharset()),stream.readAllBytes());}
        assertEquals(1,input.closes);input.callerOnly();
    }
    @Test public void emptyReaderFlushesWithoutSyntheticBytesAndClosesAtEof()throws Exception {
        for(String charset:new String[]{"UTF-8","UTF-16"}) {
            var input=new Chunks("",1);try(var stream=new ReaderInputStream(input,charset)){assertEquals(-1,stream.read());assertEquals(1,input.closes);assertArrayEquals(new byte[0],stream.readAllBytes());}
            assertEquals(1,input.closes);input.callerOnly();
        }
    }
    @Test public void invalidCharsetKeepsCheckedUnsupportedEncodingErrorWithoutReading()throws Exception {
        for(String encoding:new String[]{"not-an-encoding",":invalid:"}) {
            var reader=new Chunks("data",1);assertThrows(UnsupportedEncodingException.class,()->new ReaderInputStream(reader,encoding));assertEquals(0,reader.reads);assertEquals(0,reader.closes);
        }
    }
    @Test public void largeReaderUsesBoundedPullBuffersAndActualCompile()throws Exception {
        // Input is generated lazily; the production adapter must not request or allocate a source-sized buffer.
        int count=240000;var generated=new Reader(){int pos,closes,max;final String prefix="<?php /*",suffix="*/ return 73;";
            public int read(char[] target,int off,int len){max=Math.max(max,len);if(pos>=prefix.length()+count+suffix.length())return -1;
                int n=Math.min(len,prefix.length()+count+suffix.length()-pos);for(int i=0;i<n;i++,pos++)target[off+i]=pos<prefix.length()?prefix.charAt(pos):pos<prefix.length()+count?'x':suffix.charAt(pos-prefix.length()-count);return n;}
            public void close(){closes++;}
        };
        assertEquals("73",engine(true).compile(generated).eval().toString());assertEquals(1,generated.closes);assertTrue("Source-sized read request",generated.max<=8192);
    }
    @Test public void occasionalEmptyReaderChunkDoesNotTerminateOrSpinForever()throws Exception {
        var input=new Chunks("<?php return 29;",3);input.zero=true;
        assertEquals("29",engine(true).compile(input).eval().toString());assertEquals(1,input.closes);input.callerOnly();
    }
    @Test public void readFailurePropagatesItsPrimaryCauseAndSuppressedCloseFailure()throws Exception {
        IOException primary=new IOException("actual Reader failure"),cleanup=new IOException("actual close failure");
        var input=new Chunks("<?php return 999;",8192){
            @Override public int read(char[] target,int off,int len)throws IOException {if(offset==text.length())throw primary;return super.read(target,off,len);}
            @Override public void close()throws IOException {super.close();throw cleanup;}
        };
        var engine=engine(true);ScriptException failure=assertThrows(ScriptException.class,()->engine.compile(input));
        assertSame(primary,failure.getCause());assertArrayEquals(new Throwable[]{cleanup},primary.getSuppressed());assertEquals(1,input.closes);
        assertEquals("7",engine.compile("<?php return 7;").eval().toString());
    }
    @Test public void endOfReaderCloseFailurePropagatesAndNeverDoubleCloses()throws Exception {
        IOException cleanup=new IOException("EOF close failure");var input=new Chunks("<?php return 999;",1){@Override public void close()throws IOException{super.close();throw cleanup;}};
        ScriptException failure=assertThrows(ScriptException.class,()->engine(false).compile(input));assertSame(cleanup,failure.getCause());assertEquals(1,input.closes);
    }
    @Test public void parseFailureClosesReaderAndNextCompileUsesUnchangedBindings()throws Exception {
        var input=new Chunks("<?php function broken( {",2);var engine=engine(true);engine.put("reader_value","retained");
        assertThrows(ScriptException.class,()->engine.compile(input));assertEquals(1,input.closes);
        var valid=new Chunks("<?php return $reader_value;",2);var compiled=engine.compile(valid);
        assertSame(engine,compiled.getEngine());assertEquals("retained",compiled.eval().toString());assertEquals(1,valid.closes);
    }
    @Test public void explicitCloseWithoutReadAndAfterPartialReadOwnsReaderOnce()throws Exception {
        for(boolean partial:new boolean[]{false,true}) {
            var input=new Chunks("abcdef",1);var stream=new ReaderInputStream(input,"UTF-8");
            if(partial)assertEquals('a',stream.read());stream.close();stream.close();assertEquals(1,input.closes);
            assertThrows(IOException.class,()->stream.read());assertThrows(IOException.class,()->stream.available());
            if(!partial)assertEquals(0,input.reads);
        }
    }
    @Test public void singleBulkSkipAndEmptyReadPreserveEncodedByteOrder()throws Exception {
        String text="\u00e9\u4e2d\ud83d\ude80abc";byte[] expected=text.getBytes(StandardCharsets.UTF_8);var input=new Chunks(text,1);
        try(var stream=new ReaderInputStream(input,"UTF-8")) {
            assertEquals(0,stream.read(new byte[0]));assertEquals(0,stream.skip(-1));assertEquals(expected[0]&255,stream.read());
            assertTrue(stream.available()>=1);assertEquals(2,stream.skip(2));var output=new ByteArrayOutputStream();byte[] part=new byte[3];
            int n;while((n=stream.read(part,1,2))!=-1)output.write(part,1,n);assertArrayEquals(Arrays.copyOfRange(expected,3,expected.length),output.toByteArray());
            assertEquals(0,stream.available());assertEquals(0,stream.skip(50));assertEquals(1,input.closes);
        }
        assertEquals(1,input.closes);input.callerOnly();
    }
    @Test public void bufferedBytesAreDeliveredBeforeAnyLaterReaderFailure()throws Exception {
        IOException later=new IOException("later pull");var input=new Chunks("abc",3){@Override public int read(char[] target,int off,int len)throws IOException{if(offset==text.length())throw later;return super.read(target,off,len);}};
        try(var stream=new ReaderInputStream(input,"UTF-8")) {
            assertEquals('a',stream.read());assertEquals('b',stream.read());assertEquals('c',stream.read());assertSame(later,assertThrows(IOException.class,()->stream.read()));
        }
        assertEquals(1,input.closes);input.callerOnly();
    }
    @Test public void readerCompileUsesUtf8EvenWithNonUtf8JvmDefault()throws Exception {
        String value="\u0420\u0443\u4e2d\ud83d\ude80";
        for(boolean modern:new boolean[]{false,true})assertEquals(value,engine(modern).compile(new Chunks("<?php return '"+value+"';",1)).eval().toString());
    }
    @Test public void uncheckedReadFailureRetainsPrimaryAndClosesDirectAdapterOnce()throws Exception {
        for(boolean error:new boolean[]{false,true})for(int closeKind=0;closeKind<3;closeKind++) {
            Throwable primary=error?new AssertionError("read error"):new UncheckedIOException(new IOException("read unchecked"));
            Throwable cleanup=closeKind==0?new IOException("close checked"):closeKind==1?new IllegalStateException("close unchecked"):new AssertionError("close error");
            var input=new Chunks("",1){
                @Override public int read(char[] target,int off,int len){readers();if(primary instanceof Error e)throw e;throw (RuntimeException)primary;}
                @Override public void close()throws IOException{super.close();if(cleanup instanceof IOException e)throw e;if(cleanup instanceof Error e)throw e;throw (RuntimeException)cleanup;}
            };
            var stream=new ReaderInputStream(input,"UTF-8");
            Throwable actual=error?assertThrows(AssertionError.class,()->stream.read()):assertThrows(UncheckedIOException.class,()->stream.read());
            assertSame(primary,actual);assertArrayEquals(new Throwable[]{cleanup},primary.getSuppressed());assertEquals(1,input.closes);input.callerOnly();
            assertThrows(IOException.class,()->stream.read());assertThrows(IOException.class,()->stream.available());stream.close();assertEquals(1,input.closes);
        }
    }
    @Test public void checkedReadFailureRetainsPrimaryWhenCloseFailsUnchecked()throws Exception {
        for(boolean error:new boolean[]{false,true}) {
            IOException primary=new IOException("checked read");Throwable cleanup=error?new AssertionError("close error"):new IllegalStateException("close unchecked");
            var input=new Chunks("",1){
                @Override public int read(char[] target,int off,int len)throws IOException{readers();throw primary;}
                @Override public void close()throws IOException{super.close();if(cleanup instanceof Error e)throw e;throw (RuntimeException)cleanup;}
            };
            var stream=new ReaderInputStream(input,"UTF-8");assertSame(primary,assertThrows(IOException.class,()->stream.read()));
            assertArrayEquals(new Throwable[]{cleanup},primary.getSuppressed());stream.close();assertEquals(1,input.closes);assertThrows(IOException.class,()->stream.available());
        }
    }
    @Test public void uncheckedCloseFailureAtEofAndExplicitCloseLeavesAdapterClosed()throws Exception {
        for(boolean eof:new boolean[]{false,true})for(boolean error:new boolean[]{false,true}) {
            Throwable primary=error?new AssertionError("close error"):new IllegalStateException("close unchecked");
            var input=new Chunks("",1){@Override public void close()throws IOException{super.close();if(primary instanceof Error e)throw e;throw (RuntimeException)primary;}};
            var stream=new ReaderInputStream(input,"UTF-8");
            Throwable actual=error?assertThrows(AssertionError.class,()->{if(eof)stream.read();else stream.close();}):assertThrows(IllegalStateException.class,()->{if(eof)stream.read();else stream.close();});
            assertSame(primary,actual);assertEquals(0,primary.getSuppressed().length);assertEquals(1,input.closes);stream.close();assertEquals(1,input.closes);
            assertThrows(IOException.class,()->stream.read());assertThrows(IOException.class,()->stream.available());
        }
    }
    @Test public void identicalUncheckedReadAndCleanupFailureDoesNotSelfSuppress()throws Exception {
        var primary=new IllegalStateException("same read and close failure");
        var input=new Chunks("",1){@Override public int read(char[] target,int off,int len){readers();throw primary;}@Override public void close()throws IOException{super.close();throw primary;}};
        var stream=new ReaderInputStream(input,"UTF-8");assertSame(primary,assertThrows(IllegalStateException.class,()->stream.read()));
        assertEquals(0,primary.getSuppressed().length);assertEquals(1,input.closes);stream.close();assertEquals(1,input.closes);assertThrows(IOException.class,()->stream.read());
    }
}
