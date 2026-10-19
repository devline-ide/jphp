package org.develnext.jphp.core.compiler.jvm;

import org.junit.Test;
import php.runtime.Memory;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.*;
import php.runtime.ext.*;
import php.runtime.loader.dump.ModuleDumper;
import java.io.*;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.*;

/** Native PHP reference ids follow actual serialized placement addresses after duplicate-key replacement. */
public class PhpSerializationReferencesTest {
    private interface Check {void verify(Memory result) throws Throwable;}
    private void modes(String source,String expected) throws Throwable {
        modes(source,result->assertEquals(expected,result.toString()));
    }
    private void modes(String source,Check check) throws Throwable {
        for (boolean debug:new boolean[]{false,true}) for (boolean dumped:new boolean[]{false,true}) {
            var scope=new CompileScope();scope.registerExtension(new CoreExtension());scope.registerExtension(new SPLExtension());
            scope.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);scope.setDebugMode(debug);
            var env=new Environment(scope,new ByteArrayOutputStream());
            try {
                var context=new Context(source);context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
                var module=new JvmCompiler(env,context).compile(false);
                if (dumped) {
                    var dumper=new ModuleDumper(context,env,true);var bytes=new ByteArrayOutputStream();dumper.save(module,bytes);
                    module=dumper.load(new ByteArrayInputStream(bytes.toByteArray()));
                }
                scope.loadModule(module);scope.addUserModule(module);env.registerModule(module);
                check.verify(module.include(env));
            } finally {env.doFinal();}
        }
    }
    @Test public void duplicateStringKeyReferenceUsesReplacementValueAndCurrentAddress() throws Throwable {
        modes("$a=unserialize('a:3:{s:1:\"x\";i:1;s:1:\"x\";i:2;s:1:\"y\";R:2;}');$before=serialize($a);$a['y']=9;return $before.'|'.serialize($a);",
                "a:2:{s:1:\"x\";i:2;s:1:\"y\";R:2;}|a:2:{s:1:\"x\";i:9;s:1:\"y\";R:2;}");
    }
    @Test public void duplicateIntegerKeyReferenceUsesReplacementValueAndCurrentAddress() throws Throwable {
        modes("$a=unserialize('a:3:{i:0;i:1;i:0;i:2;i:1;R:2;}');$before=serialize($a);$a[1]=9;return $before.'|'.serialize($a);",
                "a:2:{i:0;i:2;i:1;R:2;}|a:2:{i:0;i:9;i:1;R:2;}");
    }
    @Test public void overwriteSeparatesTheEarlierCellButReferenceIdFollowsItsOriginalAddress() throws Throwable {
        for (String source:new String[]{
                "$a=unserialize('a:4:{s:1:\"x\";i:1;s:1:\"y\";R:2;s:1:\"x\";i:2;s:1:\"z\";R:2;}');$a['y']=99;return serialize($a);",
                "$a=unserialize('a:4:{i:0;i:1;i:1;R:2;i:0;i:2;i:2;R:2;}');$a[1]=99;return serialize($a);"})
            modes(source,source.contains("s:1") ? "a:3:{s:1:\"x\";i:2;s:1:\"y\";i:99;s:1:\"z\";R:2;}" : "a:3:{i:0;i:2;i:1;i:99;i:2;R:2;}");
    }
    @Test public void nativeDuplicateAddressMatrixIncludesObjectIdentityTypeChangesAndRemovedContainers() throws Throwable {
        try (var input=getClass().getResourceAsStream("/resources/type_hinting/php85_reader_addresses.tsv")) {
            assertNotNull(input);
            for (String line:new String(input.readAllBytes(),StandardCharsets.UTF_8).split("\\r?\\n")) {
                if (line.isEmpty() || line.startsWith("#")) continue;
                String[] row=line.split("\\t",-1);assertEquals(6,row.length);
                String mutation=row[0].equals("array-intkey-cell-overwrite") ? "$a[1]=99;" : "$a['y']=99;";
                modes("$warnings=[];set_error_handler(function($n,$m)use(&$warnings){$warnings[]=[$n,$m];return true;});"
                        +"$a=unserialize('"+row[1]+"');$before=serialize($a);if(is_array($a)){"+mutation+"}"
                        +"$after=serialize($a);restore_error_handler();return[$before,$after,$warnings];",result->{
                            assertEquals(row[0],row[2],result.valueOfIndex(0).toString());
                            assertEquals(row[0],row[3],result.valueOfIndex(1).toString());
                            var warnings=result.valueOfIndex(2).toValue(php.runtime.memory.ArrayMemory.class);
                            assertEquals(row[0],row[4].equals("0")?0:1,warnings.size());
                            if (!row[4].equals("0")) {
                                assertEquals(row[0],Long.parseLong(row[4]),warnings.valueOfIndex(0).valueOfIndex(0).toLong());
                                assertEquals(row[0],row[5],warnings.valueOfIndex(0).valueOfIndex(1).toString());
                            }
                        });
            }
        }
    }
    @Test public void repeatedRootReferencePreservesNativeReturnCopyAndCycleShape() throws Throwable {
        modes("$a=unserialize('a:2:{i:0;R:1;i:1;R:1;}');return serialize($a);",
                "a:2:{i:0;a:2:{i:0;R:2;i:1;R:2;}i:1;R:2;}");
    }
    @Test public void singleRootReferenceUsesNativeCycleSerialization() throws Throwable {
        modes("$a=unserialize('a:3:{s:1:\"x\";R:1;s:1:\"x\";i:2;s:1:\"y\";R:1;}');return serialize($a);",
                "a:2:{s:1:\"x\";i:2;s:1:\"y\";N;}");
    }
    @Test public void nestedSelfReferencesPreserveTheirActualContainerAddress() throws Throwable {
        modes("$a=unserialize('a:1:{i:0;a:2:{i:0;R:2;i:1;R:2;}}');return serialize($a);",
                "a:1:{i:0;a:2:{i:0;R:2;i:1;R:2;}}");
    }
    @Test public void duplicatePropertyReplacesItsOwnerFacetWithoutWritingEarlierReferencePayload() throws Throwable {
        for (String name:new String[]{"P","T"}) {
            String type=name.equals("T") ? "int " : "";
            modes("class "+name+"{public "+type+"$x;public "+type+"$y;public "+type+"$z;}"
                    +"$p=unserialize('O:1:\""+name+"\":4:{s:1:\"x\";i:1;s:1:\"y\";R:2;s:1:\"x\";i:2;s:1:\"z\";R:2;}');"
                    +"$before=serialize($p);$p->y=99;$middle=serialize($p);$p->x=88;return $before.'|'.$middle.'|'.serialize($p);",
                    "O:1:\""+name+"\":3:{s:1:\"x\";i:2;s:1:\"y\";i:1;s:1:\"z\";R:2;}|"
                    +"O:1:\""+name+"\":3:{s:1:\"x\";i:2;s:1:\"y\";i:99;s:1:\"z\";R:2;}|"
                    +"O:1:\""+name+"\":3:{s:1:\"x\";i:88;s:1:\"y\";i:99;s:1:\"z\";R:2;}");
        }
    }
    @Test public void replacingDeclaredPropertyDetachesOnlyItsOldTypeSource() throws Throwable {
        modes("class S{public int $x;public $y;public int $z;}"
                +"$p=unserialize('O:1:\"S\":4:{s:1:\"x\";i:1;s:1:\"y\";R:2;s:1:\"x\";i:2;s:1:\"z\";R:2;}');"
                +"$p->y='released';return $p->y.'|'.$p->x.'|'.$p->z;","released|2|2");
    }
    @Test public void referenceToItsOwnOverwrittenDestinationIsInvalid() throws Throwable {
        modes("return unserialize('a:2:{i:0;i:1;i:0;R:2;}');",result->assertSame(Memory.FALSE,result));
    }
    @Test public void ordinaryPendingTypedArrayReferenceAttachesSourceAfterNestedParse() throws Throwable {
        modes("class ReaderP {public array $x;public $y;}$p=unserialize('O:7:\"ReaderP\":2:{s:1:\"x\";a:1:{i:0;R:2;}s:1:\"y\";R:2;}');$before=serialize($p);"
                +"try{$p->y='bad';$error='none';}catch(TypeError $e){$error=$e->getMessage();}"
                +"return $before.'|'.$error.'|'.serialize($p);",
                "O:7:\"ReaderP\":2:{s:1:\"x\";a:1:{i:0;R:2;}s:1:\"y\";R:2;}|Cannot assign string to reference held by property ReaderP::$x of type array|O:7:\"ReaderP\":2:{s:1:\"x\";a:1:{i:0;R:2;}s:1:\"y\";R:2;}");
    }
}
