package org.develnext.jphp.core.compiler.jvm;

import org.junit.Assert;
import org.junit.Test;

public class ReflectionCommentTest extends JvmCompilerCase {
    @Test
    public void missingCommentsReturnFalseAndPassWeakStringParameters() {
        Assert.assertTrue(runDynamic("class Undocumented { public $value; function method() {} } "
                + "function undocumented() {} function comment(string $text) { return $text; } "
                + "return (new \\ReflectionClass('Undocumented'))->getDocComment() === false "
                + "&& (new \\ReflectionProperty('Undocumented', 'value'))->getDocComment() === false "
                + "&& (new \\ReflectionMethod('Undocumented', 'method'))->getDocComment() === false "
                + "&& (new \\ReflectionFunction('undocumented'))->getDocComment() === false "
                + "&& (new \\ReflectionFunction(function () {}))->getDocComment() === false "
                + "&& comment((new \\ReflectionMethod('Undocumented', 'method'))->getDocComment()) === ''", false).toBoolean());
    }
}
