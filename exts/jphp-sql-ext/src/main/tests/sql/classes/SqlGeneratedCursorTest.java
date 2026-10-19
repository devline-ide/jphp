package sql.classes;

import org.develnext.jphp.ext.sql.SqlExtension;
import org.develnext.jphp.ext.sql.classes.*;
import org.junit.Test;
import php.runtime.Memory;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.*;
import php.runtime.ext.CoreExtension;
import php.runtime.ext.SPLExtension;
import php.runtime.ext.core.classes.stream.Stream;
import java.io.*;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;
import static org.junit.Assert.*;

/** The scalar key method owns its cursor; an exported SqlResult keeps its actual cursor open. */
public class SqlGeneratedCursorTest {
    private static final byte[] BYTES=HexFormat.of().parseHex("00017f80ffc328");
    private static final class Fixture implements AutoCloseable {
        final CompileScope scope=new CompileScope();final Environment env;
        Fixture(PhpLanguageProfile profile) {
            scope.registerExtension(new CoreExtension());scope.registerExtension(new SPLExtension());scope.registerExtension(new SqlExtension());
            scope.setPhpLanguageProfile(profile);env=new Environment(scope,new ByteArrayOutputStream());
        }
        public void close() throws Exception {
            try{env.doFinal();}catch(Throwable failure){throw new AssertionError(failure);}finally{scope.getClassLoader().close();}
        }
    }
    private interface Check {void run(Fixture fixture)throws Throwable;}
    private void profiles(Check check)throws Throwable {
        for(var profile:new PhpLanguageProfile[]{PhpLanguageProfile.JPHP_LEGACY,PhpLanguageProfile.PHP_8_5})try(var fixture=new Fixture(profile)){check.run(fixture);}
    }
    private static <T>T proxy(Class<T> type,InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(SqlGeneratedCursorTest.class.getClassLoader(),new Class<?>[]{type},(instance,method,args)->{
            if(method.getDeclaringClass()==Object.class) {
                if(method.getName().equals("toString"))return "JDBC cursor contract "+type.getSimpleName();
                if(method.getName().equals("hashCode"))return System.identityHashCode(instance);
                if(method.getName().equals("equals"))return instance==args[0];
            }
            return handler.invoke(instance,method,args);
        }));
    }
    private static final class Cursor {
        boolean row=true,closed,nullCursor;int closes,streamCloses;
        int type=Types.BIGINT;
        SQLException nextFailure,readFailure,metadataFailure,closeFailure;
        IOException streamFailure;
        void open()throws SQLException {if(closed)throw new SQLException("Cursor has been closed");}
        ResultSet result() {
            ResultSetMetaData metadata=proxy(ResultSetMetaData.class,(p,m,a)->{
                open();if(m.getName().equals("getColumnType"))return type;
                throw new AssertionError(m);
            });
            return proxy(ResultSet.class,(p,m,a)->{
                if(m.getName().equals("close")){closes++;closed=true;if(closeFailure!=null)throw closeFailure;return null;}
                open();
                switch(m.getName()) {
                    case "next":if(nextFailure!=null)throw nextFailure;return row;
                    case "getMetaData":if(metadataFailure!=null)throw metadataFailure;return metadata;
                    case "getObject":if(readFailure!=null)throw readFailure;return type==Types.BIGINT?Long.MAX_VALUE:BYTES;
                    case "getString":return "9223372036854775807";
                    case "getBytes":return BYTES.clone();
                    case "getBlob":return proxy(Blob.class,(p2,m2,a2)->{
                        if(!m2.getName().equals("getBinaryStream"))throw new AssertionError(m2);
                        return new InputStream(){
                            int at;boolean streamClosed;
                            public int read()throws IOException {
                                if(closed||streamClosed)throw new IOException("JDBC Blob stream is closed");
                                if(streamFailure!=null)throw streamFailure;
                                return at==BYTES.length?-1:BYTES[at++]&255;
                            }
                            public void close(){streamCloses++;streamClosed=true;}
                        };
                    });
                    default:throw new AssertionError(m);
                }
            });
        }
        PSqlStatement statement(Environment env,ResultSet keys) {
            return new PSqlStatement(env,proxy(PreparedStatement.class,(p,m,a)->{
                if(m.getName().equals("getGeneratedKeys"))return keys;
                throw new AssertionError(m);
            }));
        }
    }
    @Test public void lastInsertIdClosesItsExactCursorAfterScalarOrRawByteRead()throws Throwable {
        profiles(f->{
            for(int type:new int[]{Types.BIGINT,Types.VARBINARY}) {
                Cursor cursor=new Cursor();cursor.type=type;
                Memory value=cursor.statement(f.env,cursor.result()).getLastInsertId(f.env);
                if(type==Types.BIGINT)assertEquals("9223372036854775807",value.toString());
                else assertArrayEquals(BYTES,value.getBinaryBytes(StandardCharsets.UTF_8));
                assertTrue(cursor.closed);assertEquals(1,cursor.closes);
            }
        });
    }
    @Test public void lastInsertIdEmptyOrAbsentCursorReturnsNullAndClosesExactlyOnce()throws Throwable {
        profiles(f->{Cursor empty=new Cursor();empty.row=false;
            assertSame(Memory.NULL,empty.statement(f.env,empty.result()).getLastInsertId(f.env));assertEquals(1,empty.closes);
            Cursor absent=new Cursor();assertSame(Memory.NULL,absent.statement(f.env,null).getLastInsertId(f.env));assertEquals(0,absent.closes);
        });
    }
    @Test public void lastInsertIdReadFailureKeepsPrimaryAndSuppressedCloseFailure()throws Throwable {
        profiles(f->{for(boolean duringNext:new boolean[]{true,false}) {
            Cursor cursor=new Cursor();SQLException primary=new SQLException("Key read failed","HY001");
            if(duringNext)cursor.nextFailure=primary;else cursor.readFailure=primary;
            cursor.closeFailure=new SQLException("Close failed","HY002");
            try{cursor.statement(f.env,cursor.result()).getLastInsertId(f.env);fail("Failed key read was accepted");}
            catch(SQLException failure){assertSame(primary,failure);assertArrayEquals(new Throwable[]{cursor.closeFailure},failure.getSuppressed());}
            assertEquals(1,cursor.closes);
        }});
    }
    @Test public void generatedKeysEmptyCursorIsClosedAndAbsentCursorRemainsNull()throws Throwable {
        profiles(f->{Cursor empty=new Cursor();empty.row=false;
            assertSame(Memory.NULL,empty.statement(f.env,empty.result()).getGeneratedKeys(f.env));assertEquals(1,empty.closes);
            Cursor absent=new Cursor();assertSame(Memory.NULL,absent.statement(f.env,null).getGeneratedKeys(f.env));assertEquals(0,absent.closes);
        });
    }
    @Test public void nonemptyGeneratedKeysTransfersReadableCursorWithoutClosingIt()throws Throwable {
        profiles(f->{Cursor cursor=new Cursor();ResultSet keys=cursor.result();
            PSqlResult exported=cursor.statement(f.env,keys).getGeneratedKeys(f.env).toObject(PSqlResult.class);
            assertEquals(0,cursor.closes);assertFalse(cursor.closed);
            assertEquals("9223372036854775807",exported.getTyped(f.env,0).toString());
            keys.close();assertEquals(1,cursor.closes);
        });
    }
    @Test public void failedGeneratedKeysAdvanceOrWrapperConstructionClosesWithoutMaskingPrimary()throws Throwable {
        profiles(f->{for(boolean duringNext:new boolean[]{true,false}) {
            Cursor cursor=new Cursor();SQLException primary=new SQLException("Key export failed","HY003");
            if(duringNext)cursor.nextFailure=primary;else cursor.metadataFailure=primary;
            cursor.closeFailure=new SQLException("Close export failed","HY004");
            try{cursor.statement(f.env,cursor.result()).getGeneratedKeys(f.env);fail("Failed key export was accepted");}
            catch(SQLException failure){assertTrue(duringNext);assertSame(primary,failure);assertArrayEquals(new Throwable[]{cursor.closeFailure},failure.getSuppressed());}
            catch(WrapSqlException failure){assertFalse(duringNext);assertSame(primary,failure.getThrowable());assertArrayEquals(new Throwable[]{cursor.closeFailure},failure.getSuppressed());}
            assertEquals(1,cursor.closes);
        }});
    }
    @Test public void blobGeneratedIdRetainsIndependentReadableStreamAfterCursorClosure()throws Throwable {
        profiles(f->{Cursor cursor=new Cursor();cursor.type=Types.BLOB;
            Memory value=cursor.statement(f.env,cursor.result()).getLastInsertId(f.env);
            assertTrue(value.instanceOf(Stream.class));assertEquals(1,cursor.closes);assertEquals(1,cursor.streamCloses);
            try(InputStream input=Stream.getInputStream(f.env,value)){assertArrayEquals(BYTES,input.readAllBytes());}
        });
    }
    @Test public void successfulKeyReadStillPropagatesItsCloseFailure()throws Throwable {
        profiles(f->{Cursor cursor=new Cursor();cursor.closeFailure=new SQLException("Close failed","HY005");
            try{cursor.statement(f.env,cursor.result()).getLastInsertId(f.env);fail("Cursor close failure was suppressed");}
            catch(SQLException failure){assertSame(cursor.closeFailure,failure);}
            assertEquals(1,cursor.closes);
        });
    }
}
