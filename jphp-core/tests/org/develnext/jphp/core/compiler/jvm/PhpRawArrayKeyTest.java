package org.develnext.jphp.core.compiler.jvm;
import org.junit.Test;
import php.runtime.Memory;
import php.runtime.memory.*;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.*;
import php.runtime.ext.*;
import php.runtime.loader.dump.ModuleDumper;
import java.io.*;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.*;
public class PhpRawArrayKeyTest {
 private void modes(String source,String expected) throws Throwable {
  for(boolean debug:new boolean[]{false,true})for(boolean dumped:new boolean[]{false,true}){
   var scope=new CompileScope();scope.registerExtension(new CoreExtension());scope.registerExtension(new SPLExtension());
   scope.registerExtension(new org.develnext.jphp.zend.ext.ZendExtension());scope.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);scope.setDebugMode(debug);
   var env=new Environment(scope,new ByteArrayOutputStream());
   try {var context=new Context(source);context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
    var module=new JvmCompiler(env,context).compile(false);
    if(dumped){var dumper=new ModuleDumper(context,env,true);var bytes=new ByteArrayOutputStream();dumper.save(module,bytes);module=dumper.load(new ByteArrayInputStream(bytes.toByteArray()));}
    scope.loadModule(module);scope.addUserModule(module);env.registerModule(module);
    assertEquals("debug="+debug+",dumped="+dumped+",source="+source,expected,module.include(env).toString());
   }finally{env.doFinal();}
  }
 }
 @Test public void immutableKeysDoNotAliasListOrReplacementText() {
  var a=new ArrayMemory();a.add(LongMemory.valueOf(42));var ff=new PhpByteStringMemory((byte)255);
  assertNull(a.get(ff));a.put(ff,LongMemory.valueOf(1));a.put(new PhpByteStringMemory((byte)254),LongMemory.valueOf(2));a.put("�",LongMemory.valueOf(3));
  assertEquals(4,a.size());assertEquals(1,a.get(new PhpByteStringMemory((byte)255)).toLong());assertEquals(2,a.get(new PhpByteStringMemory((byte)254)).toLong());assertEquals(3,a.get(StringMemory.valueOf("�")).toLong());
  var b=(ArrayMemory)a.toImmutable();b.checkCopied();b.put(new PhpByteStringMemory((byte)255),LongMemory.valueOf(8));
  assertEquals(1,a.get(ff).toLong());assertEquals(8,b.get(ff).toLong());
 }
 @Test public void validUtf8AndCanonicalNumericKeysUseExistingIdentity() {
  var a=new ArrayMemory();a.put("П",LongMemory.valueOf(1));a.put(new PhpByteStringMemory("П".getBytes(StandardCharsets.UTF_8)),LongMemory.valueOf(2));
  a.put(new PhpByteStringMemory(new byte[]{'1'}),LongMemory.valueOf(3));a.put(new PhpByteStringMemory(new byte[]{'0','1'}),LongMemory.valueOf(4));
  assertEquals(3,a.size());assertEquals(2,a.get(StringMemory.valueOf("П")).toLong());assertEquals(3,a.get(LongMemory.valueOf(1)).toLong());assertEquals(4,a.get(StringMemory.valueOf("01")).toLong());
 }
 @Test public void nativeConsumers() throws Throwable {
  modes("$a=[\"\\xFF\"=>1,\"\\xFE\"=>2];$s=\"\";foreach(array_keys($a) as $k){$s.=bin2hex($k).\"|\";}return $s;","ff|fe|");
  modes("$a=[\"\\xFF\"=>1];return bin2hex(array_rand($a)).\":\".bin2hex(key($a));","ff:ff");
  modes("$a=[\"\\xFF\"=>1,7=>2];$b=[...$a,\"\\xFE\"=>3];$s=\"\";foreach($b as $k=>$v){$s.=(is_int($k)?$k:bin2hex($k)).\":\".$v.\"|\";}return $s;","ff:1|0:2|fe:3|");
  modes("$a=[\"\\xFF\"=>1];$b=array_merge($a,[\"\\xFF\"=>2,\"\\xFE\"=>3]);$s=\"\";foreach($b as $k=>$v){$s.=bin2hex($k).\":\".$v.\"|\";}return $s;","ff:2|fe:3|");
 }
 @Test public void nativeCopyAndReference() throws Throwable {
  modes("$a=[\"\\xFF\"=>1,\"\\xFE\"=>2];$b=$a;$b[\"\\xFF\"]=7;unset($b[\"\\xFE\"]);return count($a).\":\".$a[\"\\xFF\"].\":\".$a[\"\\xFE\"].\":\".count($b).\":\".$b[\"\\xFF\"];","2:1:2:1:7");
  modes("$a=[\"\\xFF\"=>1];$r=&$a[\"\\xFF\"];$r=8;$a[\"\\xFF\"]++;return count($a).\":\".$r.\":\".$a[\"\\xFF\"];","1:9:9");
  modes("$a=[];$a[\"\\xFF\"]??=3;return count($a).\":\".$a[\"\\xFF\"];","1:3");
 }
 @Test public void nativeDistinct() throws Throwable {
  modes("$a=[\"\\xFF\"=>1,\"\\xFE\"=>2,\"�\"=>3];$s=\"\";foreach($a as $k=>$v){$s.=bin2hex($k).\":\".$v.\"|\";}return $s;","ff:1|fe:2|efbfbd:3|");
  modes("$a=[];$a[\"\\xFF\"]=1;$a[\"\\xFE\"]=2;$a[\"�\"]=3;return count($a).\":\".$a[\"\\xFF\"].\":\".$a[\"\\xFE\"].\":\".$a[\"�\"];","3:1:2:3");
 }
 @Test public void nativeDumpedDefaults() throws Throwable {
  modes("function f($a=[\"\\xFF\"=>\"\\xFE\"]){return bin2hex(array_keys($a)[0]).\":\".bin2hex($a[\"\\xFF\"]);}return f();","ff:fe");
 }
 @Test public void nativeGlobals() throws Throwable {
  modes("$GLOBALS[\"\\xFF\"]=1;$GLOBALS[\"\\xFE\"]=2;return $GLOBALS[\"\\xFF\"].\":\".$GLOBALS[\"\\xFE\"];","1:2");
  modes("$GLOBALS[\"\\xFF\"]=1;$r=&$GLOBALS[\"\\xFF\"];$r++;unset($GLOBALS[\"\\xFF\"]);return $r.\":\".(isset($GLOBALS[\"\\xFF\"])?\"bad\":\"absent\");","2:absent");
  modes("$GLOBALS[\"\\xFF\"]??=3;return $GLOBALS[\"\\xFF\"];","3");
 }
 @Test public void nativeList() throws Throwable {
  modes("$a=[42];return (isset($a[\"\\xFF\"])?\"bad\":\"absent\").\":\".$a[0];","absent:42");
 }
 @Test public void nativeNormalization() throws Throwable {
  modes("$a=[\"П\"=>1];$a[\"\\xD0\\x9F\"]=2;$a[\"\\xFF\"]=3;$a[\"ÿ\"]=4;return count($a).\":\".$a[\"П\"].\":\".$a[\"\\xFF\"].\":\".$a[\"ÿ\"];","3:2:3:4");
  modes("$a=[\"1\"=>1,\"01\"=>2,\"-1\"=>3,\"9223372036854775808\"=>4];$s=\"\";foreach($a as $k=>$v){$s.=gettype($k).\":\".$k.\":\".$v.\"|\";}return $s;","integer:1:1|string:01:2|integer:-1:3|string:9223372036854775808:4|");
 }
 @Test public void nativeReindex() throws Throwable {
  modes("$a=[5=>1,\"\\xFF\"=>2,9=>3];array_shift($a);array_unshift($a,4);$s=\"\";foreach($a as $k=>$v){$s.=(is_int($k)?$k:bin2hex($k)).\":\".$v.\"|\";}return $s;","0:4|ff:2|1:3|");
 }
 @Test public void nativeSerialization() throws Throwable {
  modes("$a=[\"\\xFF\"=>\"\\xFE\", \"\\0\\x80\"=>\"\\xFF\\0\"];return bin2hex(serialize($a));","613a323a7b733a313a22ff223b733a313a22fe223b733a323a220080223b733a323a22ff00223b7d");
  modes("$a=[\"\\xFF\"=>\"\\xFE\", \"\\0\\x80\"=>\"\\xFF\\0\"];$b=unserialize(serialize($a));$s=\"\";foreach($b as $k=>$v){$s.=bin2hex($k).\":\".bin2hex($v).\"|\";}return $s;","ff:fe|0080:ff00|");
  modes("$s=unserialize(\"s:1:\\\"\\xFF\\\";\");return bin2hex($s);","ff");
 }
}
