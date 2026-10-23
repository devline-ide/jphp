package org.develnext.jphp.core.compiler.jvm;

import org.junit.Test;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.Environment;
import java.io.ByteArrayOutputStream;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertEquals;

public class EvaluatedComparisonTest extends JvmCompilerCase {
    @Test public void ternaryArrayValueSurvivesLogicalStrictComparisonAndArgumentPassing() throws Throwable {
        for (PhpLanguageProfile profile : new PhpLanguageProfile[]{PhpLanguageProfile.PHP_8_5}) {
            Environment env = new Environment(newScope(), new ByteArrayOutputStream());
            env.getScope().setPhpLanguageProfile(profile);
            try {
                assertEquals("keep", env.eval("class Rendering { function pass($id, $type) { return $type; } " +
                    "function run() { $b=['type'=>'keep','open'=>true]; " +
                    "$type=$b['type']==='gate' && $b['open']?'gate_open':$b['type']; return $this->pass('b1',$type); } } " +
                    "return (new Rendering())->run();").toString());
            } finally { env.doFinal(); }
        }
    }
    @Test public void mixedEvaluatedScalarAndArrayValueKeepPhysicalStackOrder() throws Throwable {
        for (PhpLanguageProfile profile : new PhpLanguageProfile[]{PhpLanguageProfile.JPHP_LEGACY, PhpLanguageProfile.PHP_8_5}) {
            Environment env = new Environment(newScope(), new ByteArrayOutputStream());
            env.getScope().setPhpLanguageProfile(profile);
            try {
                assertTrue(env.eval("function value($n) { return $n; } $cell = [3, true]; " +
                        "return (int)value(3) === $cell[0] && (int)value(4) !== $cell[0] " +
                        "&& $cell[0] === (int)value(3) && $cell[0] !== (int)value(4) " +
                        "&& (bool)value(1) === $cell[1] && (float)value(3) !== $cell[0] " +
                        "&& (int)value(2) < $cell[0] && $cell[0] > (int)value(2);").toBoolean());
            } finally { env.doFinal(); }
        }
    }
}
