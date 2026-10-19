package sql.classes;

import org.develnext.jphp.core.compiler.jvm.JvmCompiler;
import org.develnext.jphp.ext.sql.SqlExtension;
import org.develnext.jphp.ext.sql.classes.*;
import org.develnext.jphp.zend.ext.ZendExtension;
import org.junit.Test;
import php.runtime.Memory;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.*;
import php.runtime.ext.CoreExtension;
import php.runtime.ext.SPLExtension;
import php.runtime.ext.core.classes.stream.Stream;
import php.runtime.loader.dump.ModuleDumper;
import php.runtime.memory.*;

import java.io.*;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;
import javax.sql.rowset.serial.SerialBlob;
import static org.junit.Assert.*;

/** JDBC raw bytes and generated-key capability contracts, plus actual SQLite source/PHB execution. */
public class SqlValueBoundaryTest {
    private static final byte[] PAYLOAD = HexFormat.of().parseHex("00017f80ffc328");
    private static final PhpLanguageProfile[] PROFILES = {PhpLanguageProfile.JPHP_LEGACY, PhpLanguageProfile.PHP_8_5};

    private static final class Fixture implements AutoCloseable {
        final CompileScope scope = new CompileScope();
        final Environment env;
        Fixture(PhpLanguageProfile profile) {
            scope.registerExtension(new CoreExtension());scope.registerExtension(new SPLExtension());
            scope.registerExtension(new ZendExtension());scope.registerExtension(new SqlExtension());
            scope.setPhpLanguageProfile(profile);
            env = new Environment(scope, new ByteArrayOutputStream());
            env.getDefaultBuffer().setChunkSize(0);
        }
        public void close() throws Exception {
            try { env.doFinal(); } catch (Throwable failure) { throw new AssertionError(failure); }
            finally { scope.getClassLoader().close(); }
        }
    }
    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(SqlValueBoundaryTest.class.getClassLoader(), new Class<?>[]{type},
                (instance, method, args) -> {
                    if (method.getDeclaringClass() == Object.class) {
                        if (method.getName().equals("toString")) return "JDBC contract " + type.getSimpleName();
                        if (method.getName().equals("hashCode")) return System.identityHashCode(instance);
                        if (method.getName().equals("equals")) return instance == args[0];
                    }
                    return handler.invoke(instance, method, args);
                }));
    }
    private static AssertionError unexpected(Method method) { return new AssertionError("Unexpected JDBC operation " + method); }
    private static ResultSet column(int type, Object value, byte[] bytes, String text) {
        ResultSetMetaData metadata = proxy(ResultSetMetaData.class, (p,m,a) -> {
            if (m.getName().equals("getColumnType")) { assertEquals(1,a[0]); return type; }
            if (m.getName().equals("getColumnCount")) return 1;
            if (m.getName().equals("getColumnLabel")) return "payload";
            throw unexpected(m);
        });
        int[] rows = {0};
        return proxy(ResultSet.class, (p,m,a) -> {
            switch (m.getName()) {
                case "getMetaData": return metadata;
                case "getObject": assertEquals(1,a[0]); return value;
                case "getBytes": assertEquals(1,a[0]); return bytes;
                case "getString":
                case "getNString": assertEquals(1,a[0]); return text;
                case "getLong": return ((Number)value).longValue();
                case "getDouble": return ((Number)value).doubleValue();
                case "getBoolean": return value;
                case "getBlob": throw new SQLFeatureNotSupportedException("No Blob handle; raw bytes available");
                case "findColumn": assertEquals("payload",a[0]); return 1;
                case "next": return rows[0]++ == 0;
                case "close": return null;
                default: throw unexpected(m);
            }
        });
    }
    private static void exactBytes(Memory value, byte[] expected) {
        assertTrue("JDBC binary values must remain PHP strings", value.isString());
        assertArrayEquals(expected, value.getBinaryBytes(StandardCharsets.UTF_8));
    }
    private void binaryType(int type) throws Throwable {
        for (var profile : PROFILES) try (var f = new Fixture(profile)) {
            byte[] bytes = PAYLOAD.clone();
            var row = new PSqlResult(f.env, column(type,bytes,bytes,new String(bytes,StandardCharsets.UTF_8)));
            exactBytes(row.getTyped(f.env,0),PAYLOAD);
            exactBytes(row.get(f.env,StringMemory.valueOf("payload")),PAYLOAD);
            exactBytes(row.toArray(f.env,true).valueOfIndex("payload"),PAYLOAD);
            exactBytes(row.toArray(f.env,false).valueOfIndex(0),PAYLOAD);
        }
    }
    @Test public void fixedBinaryReadsNeverUseTextProjection() throws Throwable { binaryType(Types.BINARY); }
    @Test public void variableBinaryReadsNeverUseTextProjection() throws Throwable { binaryType(Types.VARBINARY); }
    @Test public void longVariableBinaryReadsNeverUseTextProjection() throws Throwable { binaryType(Types.LONGVARBINARY); }
    @Test public void modernBinaryResultOwnsItsImmutableByteValueAndLegacyKeepsBinaryCarrier() throws Throwable {
        for (var profile : PROFILES) try (var f = new Fixture(profile)) {
            byte[] bytes = PAYLOAD.clone();
            Memory value = new PSqlResult(f.env,column(Types.BINARY,bytes,bytes,"lost")).getTyped(f.env,0);
            if (profile == PhpLanguageProfile.PHP_8_5) {
                assertEquals(PhpByteStringMemory.class,value.getClass());
                bytes[3] = 'A';exactBytes(value,PAYLOAD);
                byte[] exported = value.getBinaryBytes(StandardCharsets.UTF_8);exported[4] = 'B';exactBytes(value,PAYLOAD);
                assertSame(value,value.toImmutable());
            } else assertEquals(BinaryMemory.class,value.getClass());
        }
    }
    @Test public void binaryNullEmptyAndBlobFallbackRemainDistinct() throws Throwable {
        for (var profile : PROFILES) try (var f = new Fixture(profile)) {
            for (int type : new int[]{Types.BINARY,Types.VARBINARY,Types.LONGVARBINARY,Types.BLOB}) {
                assertSame(Memory.NULL,new PSqlResult(f.env,column(type,null,null,null)).getTyped(f.env,0));
                exactBytes(new PSqlResult(f.env,column(type,new byte[0],new byte[0],"")).getTyped(f.env,0),new byte[0]);
                exactBytes(new PSqlResult(f.env,column(type,PAYLOAD,PAYLOAD,"lossy")).getTyped(f.env,0),PAYLOAD);
            }
            ResultSetMetaData metadata = proxy(ResultSetMetaData.class,(p,m,a)->{
                if (m.getName().equals("getColumnType")) return Types.BLOB;
                throw unexpected(m);
            });
            ResultSet stream = proxy(ResultSet.class,(p,m,a)->{
                if (m.getName().equals("getMetaData")) return metadata;
                if (m.getName().equals("getObject")) return new Object();
                if (m.getName().equals("getBlob")) return new SerialBlob(PAYLOAD);
                throw unexpected(m);
            });
            Memory result = new PSqlResult(f.env,stream).getTyped(f.env,0);
            assertTrue(result.isObject());
            InputStream input = Stream.getInputStream(f.env,result);
            try { assertArrayEquals(PAYLOAD,input.readAllBytes()); } finally { Stream.closeStream(f.env,input); }
        }
    }
    @Test public void textNullBigIntAndNumericReadsKeepExistingContracts() throws Throwable {
        for (var profile : PROFILES) try (var f = new Fixture(profile)) {
            for (int type : new int[]{Types.CHAR,Types.VARCHAR,Types.LONGVARCHAR,Types.NVARCHAR,Types.LONGNVARCHAR,Types.OTHER}) {
                Memory value = new PSqlResult(f.env,column(type,"Привет",null,"Привет")).getTyped(f.env,0);
                assertEquals(StringMemory.class,value.getClass());assertEquals("Привет",value.toString());
            }
            assertEquals("9223372036854775807",new PSqlResult(f.env,column(Types.BIGINT,Long.MAX_VALUE,null,"9223372036854775807")).getTyped(f.env,0).toString());
            assertEquals(42.125,new PSqlResult(f.env,column(Types.DECIMAL,42.125,null,null)).getTyped(f.env,0).toDouble(),0);
            assertEquals(17,new PSqlResult(f.env,column(Types.INTEGER,17,null,null)).getTyped(f.env,0).toLong());
            assertSame(Memory.NULL,new PSqlResult(f.env,column(Types.VARCHAR,null,null,null)).getTyped(f.env,0));
        }
    }
    @Test public void rawByteAndReferenceParametersBindWithoutCharsetDecoding() throws Throwable {
        for (var profile : PROFILES) try (var f = new Fixture(profile)) {
            List<byte[]> sent = new ArrayList<>();
            PreparedStatement statement = proxy(PreparedStatement.class,(p,m,a)->{
                if (m.getName().equals("setBytes")) { assertEquals(3,a[0]);sent.add(((byte[])a[1]).clone());return null; }
                throw unexpected(m);
            });
            var wrapper = new PSqlStatement(f.env,statement);
            wrapper.bind(f.env,2,new BinaryMemory(PAYLOAD.clone()));
            wrapper.bind(f.env,2,new PhpByteStringMemory(PAYLOAD));
            wrapper.bind(f.env,2,ReferenceMemory.valueOf(new PhpByteStringMemory(PAYLOAD)));
            assertEquals(3,sent.size());for (byte[] value : sent) assertArrayEquals(PAYLOAD,value);
        }
    }
    @Test public void ordinaryUnicodeAndScalarParametersKeepTypedJdbcBindings() throws Throwable {
        for (var profile : PROFILES) try (var f = new Fixture(profile)) {
            List<String> calls = new ArrayList<>();
            PreparedStatement statement = proxy(PreparedStatement.class,(p,m,a)->{
                assertEquals(1,a[0]);calls.add(m.getName());
                switch(m.getName()) {
                    case "setString": assertEquals("Привет",a[1]);break;
                    case "setNull": assertEquals(Types.NULL,a[1]);break;
                    case "setLong": assertEquals(41L,a[1]);break;
                    case "setDouble": assertEquals(42.125,(double)a[1],0);break;
                    case "setBoolean": assertEquals(true,a[1]);break;
                    default: throw unexpected(m);
                }
                return null;
            });
            var wrapper = new PSqlStatement(f.env,statement);
            for (Memory value : new Memory[]{StringMemory.valueOf("Привет"),Memory.NULL,LongMemory.valueOf(41),DoubleMemory.valueOf(42.125),Memory.TRUE}) wrapper.bind(f.env,0,value);
            assertEquals(List.of("setString","setNull","setLong","setDouble","setBoolean"),calls);
        }
    }
    private static final class DriverContract {
        final boolean supports;
        boolean requested,unsupportedFlag,emptyKeys;
        SQLException failure;
        int preparations;
        DriverContract(boolean supports) { this.supports=supports; }
        Connection connection() {
            var metadata = proxy(DatabaseMetaData.class,(p,m,a)->{
                if (m.getName().equals("supportsGetGeneratedKeys")) return supports;
                throw unexpected(m);
            });
            return proxy(Connection.class,(p,m,a)->{
                if (m.getName().equals("getMetaData")) return metadata;
                if (!m.getName().equals("prepareStatement")) throw unexpected(m);
                preparations++;
                if (failure!=null) throw failure;
                if (a.length==2) {
                    if (unsupportedFlag) throw new SQLFeatureNotSupportedException("Flag overload unsupported");
                    requested = (int)a[1] == Statement.RETURN_GENERATED_KEYS;
                } else assertEquals(1,a.length);
                return proxy(PreparedStatement.class,(p2,m2,a2)->{
                    if (m2.getName().equals("executeUpdate")) return 1;
                    if (m2.getName().equals("getGeneratedKeys")) {
                        if (!requested) throw new SQLException("Generated keys not requested", "HY000");
                        if (emptyKeys) return proxy(ResultSet.class,(p3,m3,a3)->{
                            if (m3.getName().equals("next")) return false;
                            if (m3.getName().equals("close")) return null;
                            throw unexpected(m3);
                        });
                        return column(Types.BIGINT,Long.MAX_VALUE,null,"9223372036854775807");
                    }
                    throw unexpected(m2);
                });
            });
        }
    }
    @Test public void defaultQueryProvidesStatementGeneratedKeysAndExactLargeInsertId() throws Throwable {
        for (var profile : PROFILES) try (var f = new Fixture(profile)) {
            var driver = new DriverContract(true);
            var query = new PSqlConnection(f.env,driver.connection()).query(f.env,"insert into sample default values");
            assertEquals(1,query.update(f.env));
            assertEquals("9223372036854775807",query.getLastInsertId(f.env).toString());
            var keys = query.getGeneratedKeys(f.env).toObject(PSqlResult.class);
            assertEquals("9223372036854775807",keys.getTyped(f.env,0).toString());
            driver.emptyKeys=true;assertSame(Memory.NULL,query.getLastInsertId(f.env));
            assertEquals(1,driver.preparations);
        }
    }
    @Test public void unsupportedKeyDriversRetainOrdinaryQueryPreparation() throws Throwable {
        for (var profile : PROFILES) try (var f = new Fixture(profile)) {
            for (boolean advertised : new boolean[]{false,true}) {
                var driver = new DriverContract(advertised);driver.unsupportedFlag=true;
                var query = new PSqlConnection(f.env,driver.connection()).query(f.env,"select 1");
                assertEquals(1,query.update(f.env));assertEquals(advertised?2:1,driver.preparations);
                assertFalse(driver.requested);
            }
        }
    }
    @Test public void ordinaryJdbcPreparationErrorsAreNeverRetriedOrReplaced() throws Throwable {
        for (var profile : PROFILES) try (var f = new Fixture(profile)) {
            var driver = new DriverContract(true);driver.failure=new SQLException("Invalid SQL", "42000",1064);
            try { new PSqlConnection(f.env,driver.connection()).query(f.env,"broken query");fail("Invalid SQL was suppressed"); }
            catch (SQLException actual) { assertSame(driver.failure,actual);assertEquals("42000",actual.getSQLState()); }
            assertEquals(1,driver.preparations);
        }
    }

    private void sqliteProgram(boolean dumped) throws Throwable {
        String php = "use php\\sql\\SqlDriverManager;SqlDriverManager::install('sqlite');"
                +"$c=SqlDriverManager::getConnection('sqlite::memory:');"
                +"$c->query('create table sample(id integer primary key autoincrement,payload blob,label text)')->update();"
                +"$s=$c->query('insert into sample(payload,label) values(?,?)',[hex2bin('00017f80ffc328'),'Привет']);$s->update();"
                +"$id=$s->getLastInsertId();$row=$c->query('select * from sample')->fetch();"
                +"if($id!=$row->get('id')||$id!=1)throw new Exception('Generated key changed');"
                +"echo bin2hex($row->get('payload')),'|',$row->get('label'),'|',$id,'|';"
                +"$c->query(\"insert into sample(payload,label) values(X'00017f80ffc328','server')\")->update();"
                +"echo bin2hex($c->query(\"select payload from sample where label='server'\")->fetch()->get('payload'));"
                +"if($c->query('select id from sample where id=-1')->fetch()!==null)throw new Exception('Empty fetch');$c->close();";
        for (var profile : PROFILES) for (boolean debug : new boolean[]{false,true}) try (var f = new Fixture(profile)) {
            f.scope.setDebugMode(debug);
            Context context = new Context(php);context.setPhpLanguageProfile(profile);
            var module = new JvmCompiler(f.env,context).compile(false);
            if (dumped) {
                var dumper = new ModuleDumper(context,f.env,true);var output = new ByteArrayOutputStream();
                dumper.save(module,output);module=dumper.load(new ByteArrayInputStream(output.toByteArray()));
            }
            f.scope.loadModule(module);f.scope.addUserModule(module);f.env.registerModule(module);module.include(f.env);
            assertEquals("00017f80ffc328|Привет|1|00017f80ffc328",f.env.getDefaultBuffer().getContents().toString());
        }
    }
    @Test public void actualSqlitePreparedBinaryServerLiteralAndGeneratedKeySource() throws Throwable { sqliteProgram(false); }
    @Test public void actualSqlitePreparedBinaryServerLiteralAndGeneratedKeyPhb() throws Throwable { sqliteProgram(true); }
}
