package org.develnext.jphp.core.compiler.jvm;

import org.junit.Test;

/** Actual native controls kept separate so a known unrelated baseline gap stays visible. */
public class PhpDeprecatedEffectsAdditionalTest {
    @Test(timeout=120000) public void duplicateSyntacticTraitUseMatchesNativeSourcePhbDebug() throws Throwable {
        PhpDeprecatedPhysicalEffectsTest.standalone("duplicate-use");
    }
    @Test(timeout=120000) public void normalAndUnrelatedPublicationMatchesNativeWithoutExpectedRewriting() throws Throwable {
        PhpDeprecatedPhysicalEffectsTest.standalone("normal-unrelated");
    }
    @Test(timeout=120000) public void handlerThrowRollsBackDependentClassesAndRetryMatchesActualNative() throws Throwable {
        PhpDeprecatedPhysicalEffectsTest.standalone("trait-dependent-visibility");
    }
}
