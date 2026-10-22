package org.develnext.jphp.core.compiler.jvm;
import org.junit.*;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.Environment;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import static org.junit.Assert.*;
/** Modern native contract; legacy wire baseline1abec035 is pinned separately from native parity. */
public class PhpSplGetArrayCopyTest {
 private static Path fixtures;
 @BeforeClass public static void inputs()throws Exception {fixtures=PhpPortableTestInputs.fixture("qa.spl.getarraycopy","spl-getarraycopy-tombstone");}
 @AfterClass public static void cleanup()throws Exception {PhpPortableTestInputs.release(fixtures);fixtures=null;}
 private void program(String name,int rows,PhpLanguageProfile profile)throws Throwable {
  String source=Files.readString(fixtures.resolve(name+".php"),StandardCharsets.UTF_8);assertTrue(source.startsWith("<?php"));
  byte[] expected=Files.readAllBytes(fixtures.resolve(name+(profile==PhpLanguageProfile.JPHP_LEGACY?".legacy.stdout":".stdout")));assertEquals(rows,new String(expected,StandardCharsets.UTF_8).lines().count());assertEquals(0,Files.size(fixtures.resolve(name+".stderr")));
  for(boolean debug:new boolean[]{false,true})for(boolean phb:new boolean[]{false,true}) {
   var output=new ByteArrayOutputStream();var env=new Environment(PhpJsonEncodeContractTest.scope(debug),output);Throwable primary=null;
   try{PhpJsonEncodeContractTest.compile(env,source.substring(5),phb,debug,profile).include(env);env.flushAll();assertArrayEquals(name+" profile="+profile+" debug="+debug+" PHB="+phb,expected,output.toByteArray());}
   catch(Throwable failure){primary=failure;throw failure;}
   finally{try{env.doFinal();}catch(Throwable cleanup){if(primary==null)throw cleanup;else if(primary!=cleanup)primary.addSuppressed(cleanup);}}
  }
 }
 @Test(timeout=90000) public void modernAbsentPropertiesKeepNullVisibilityReferencesAndValueCopies()throws Throwable {program("modern",3,PhpLanguageProfile.PHP_8_5);program("numeric",6,PhpLanguageProfile.PHP_8_5);}
 @Test(timeout=90000) public void legacyObjectAndBothArrayProfilesKeepTheirReferenceAndCowPolicy()throws Throwable {program("common",2,PhpLanguageProfile.JPHP_LEGACY);System.out.println("GETARRAYCOPY_LEGACY_ALL4_BASELINE_POLICY_VARIANTS_PASS");program("common",2,PhpLanguageProfile.PHP_8_5);}
}
