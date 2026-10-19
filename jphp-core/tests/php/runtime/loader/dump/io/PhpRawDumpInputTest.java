package php.runtime.loader.dump.io;
import org.junit.Test;
import java.io.*;
import java.util.Arrays;
import static org.junit.Assert.*;
public class PhpRawDumpInputTest {
 private byte[] frame(int length,byte[] payload)throws IOException{
  var bytes=new ByteArrayOutputStream();var out=new DataOutputStream(bytes);out.writeInt(length);out.write(payload);return bytes.toByteArray();
 }
 @Test public void truncatedHugeDeclarationFailsWithIOExceptionWithoutHugeAllocation()throws Exception{
  byte[] bytes=frame(Integer.MAX_VALUE,new byte[]{1,2,3});
  try(var input=new DumpInputStream(new ByteArrayInputStream(bytes))){assertThrows(IOException.class,()->input.readRawData(Integer.MAX_VALUE));}
 }
 @Test public void validAndShortReadingStreamsRetainExactBytes()throws Exception{
  byte[] payload=new byte[16391];for(int i=0;i<payload.length;i++)payload[i]=(byte)(i*37);
  byte[] bytes=frame(payload.length,payload);
  try(var input=new DumpInputStream(new ByteArrayInputStream(bytes))){assertArrayEquals(payload,input.readRawData(payload.length));}
  var slow=new FilterInputStream(new ByteArrayInputStream(bytes)){
   @Override public int read(byte[] b,int off,int len)throws IOException{return super.read(b,off,Math.min(len,3));}
  };
  try(var input=new DumpInputStream(slow)){assertArrayEquals(payload,input.readRawData(payload.length));}
 }
 @Test public void zeroNegativeAndLimitRulesAreUnchanged()throws Exception{
  try(var input=new DumpInputStream(new ByteArrayInputStream(frame(0,new byte[0])))){assertEquals(0,input.readRawData(0).length);}
  for(int size:new int[]{-1,-99,5})try(var input=new DumpInputStream(new ByteArrayInputStream(frame(size,new byte[0])))){assertThrows(IOException.class,()->input.readRawData(4));}
 }
 @Test public void primaryStreamFailureIsPreserved()throws Exception{
  byte[] bytes=frame(20,new byte[20]);var primary=new IOException("primary-read-failure");
  var failing=new InputStream(){int at;@Override public int read()throws IOException{if(at==7)throw primary;return bytes[at++];}};
  try(var input=new DumpInputStream(failing)){assertSame(primary,assertThrows(IOException.class,()->input.readRawData(20)));}
 }
}
