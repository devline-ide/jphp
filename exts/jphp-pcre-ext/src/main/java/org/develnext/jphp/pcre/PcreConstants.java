package org.develnext.jphp.pcre;
import php.runtime.ext.support.compile.ConstantsContainer;
public final class PcreConstants extends ConstantsContainer {
    public static final int PREG_PATTERN_ORDER=1, PREG_SET_ORDER=2, PREG_OFFSET_CAPTURE=256, PREG_UNMATCHED_AS_NULL=512;
    public static final int PREG_SPLIT_NO_EMPTY=1, PREG_SPLIT_DELIM_CAPTURE=2, PREG_SPLIT_OFFSET_CAPTURE=4;
    public static final int PREG_GREP_INVERT=1;
    public static final int PREG_NO_ERROR=0, PREG_INTERNAL_ERROR=1, PREG_BACKTRACK_LIMIT_ERROR=2, PREG_RECURSION_LIMIT_ERROR=3;
    public static final int PREG_BAD_UTF8_ERROR=4, PREG_BAD_UTF8_OFFSET_ERROR=5, PREG_JIT_STACKLIMIT_ERROR=6;
    public final String PCRE_VERSION;
    public final int PCRE_VERSION_MAJOR, PCRE_VERSION_MINOR;
    public final boolean PCRE_JIT_SUPPORT;
    PcreConstants(String version,boolean jit) {
        PCRE_VERSION=version;PCRE_JIT_SUPPORT=jit;
        int dot=version.indexOf('.'),space=version.indexOf(' ');
        PCRE_VERSION_MAJOR=Integer.parseInt(version.substring(0,dot));
        PCRE_VERSION_MINOR=Integer.parseInt(version.substring(dot+1,space<0?version.length():space));
    }
}
