package org.develnext.jphp.core.compiler.jvm;

import org.develnext.jphp.zend.ext.standard.StandardExtension;
import org.junit.Test;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.Context;
import php.runtime.env.Environment;
import php.runtime.loader.dump.ModuleDumper;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

import static org.junit.Assert.assertEquals;

public class PositionalVectorLifetimeTest extends JvmCompilerCase {
    @Test public void generatorsCapturesAndFallbackCallsKeepTheirArgumentValuesAfterFrameClose() throws Throwable {
        String source = "function delayed($value){yield $value;yield $value+1;}"
                + "class VectorFixture {"
                + "public function delayed($value){yield $value;yield $value+1;}"
                + "public function change($values){$values[0]=9;return $values[0];}"
                + "public function bump(&$value){$value+=2;return $value;}"
                + "public static function typed(int $value):int{return $value;}"
                + "} $object=new VectorFixture();"
                + "$first=delayed(13);$second=$object->delayed(17);"
                + "$captured=(function($value){return function()use($value){return $value;};})(19);"
                + "$values=[3];$copy=$object->change($values);$original=$values[0];"
                + "$reference=&$values[0];$bumped=$object->bump($reference);"
                + "$named=VectorFixture::typed(value:'7');$spread=VectorFixture::typed(...['value'=>'8']);"
                + "$a=$first->current();$first->next();$b=$first->current();$first->next();"
                + "$c=$second->current();$second->next();$d=$second->current();$second->next();"
                + "return [$a,$b,$c,$d,$captured(),$original,$copy,$named,$spread,count($values),$bumped,$values[0]];";
        for (boolean dumped : new boolean[]{false, true}) {
            var scope = newScope(); scope.setDebugMode(false); scope.registerExtension(new StandardExtension());
            var env = new Environment(scope, new ByteArrayOutputStream());
            try {
                var context = new Context(source); context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
                var module = new JvmCompiler(env, context).compile(false);
                if (dumped) {
                    var dumper = new ModuleDumper(context, env, true);
                    var bytes = new ByteArrayOutputStream(); dumper.save(module, bytes);
                    module = dumper.load(new ByteArrayInputStream(bytes.toByteArray()));
                }
                scope.loadModule(module); scope.addUserModule(module); env.registerModule(module);
                var result = module.include(env);
                long[] expected = {13, 14, 17, 18, 19, 3, 9, 7, 8, 1, 5, 5};
                for (int i = 0; i < expected.length; i++)
                    assertEquals("PHB=" + dumped + " row " + i, expected[i], result.valueOfIndex(null, i).toLong());
            } finally { env.doFinal(); }
        }
    }
}
