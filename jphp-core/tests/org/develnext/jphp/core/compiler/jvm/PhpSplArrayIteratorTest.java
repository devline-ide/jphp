package org.develnext.jphp.core.compiler.jvm;

import org.junit.Test;

/** Keep the unchanged native built-in requirement visible while extending SPL coverage. */
public class PhpSplArrayIteratorTest {
    @Test(timeout = 12000)
    public void nativeArrayIteratorDelegatesValuesKeysAndNullResult() throws Throwable {
        new PhpGeneratorDelegationTest().checkDelegation("function outer(){$r=yield from new ArrayIterator(['x'=>4,9=>5]);echo is_null($r)?'null;':'other;';}foreach(outer() as $k=>$v)echo $k,':',$v,';';", "x:4;9:5;null;");
    }
}
