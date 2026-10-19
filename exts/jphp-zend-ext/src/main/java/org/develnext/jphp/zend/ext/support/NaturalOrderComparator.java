package org.develnext.jphp.zend.ext.support;

/** Preserve the extension API while sharing the runtime sorting implementation. */
public class NaturalOrderComparator extends php.runtime.common.NaturalOrderComparator {
    public NaturalOrderComparator(boolean ignoreCase, boolean revert) {
        super(ignoreCase, revert);
    }
}
