package php.runtime.ext.core;

import php.runtime.ext.support.compile.ConstantsContainer;

public class OutputConstants extends ConstantsContainer {
    public final static int PHP_OUTPUT_HANDLER_CLEANABLE = 16;
    public final static int PHP_OUTPUT_HANDLER_FLUSHABLE = 32;
    public final static int PHP_OUTPUT_HANDLER_REMOVABLE = 64;
    public final static int PHP_OUTPUT_HANDLER_STDFLAGS = 112;
    public final static int PHP_OUTPUT_HANDLER_START = 1;
    public final static int PHP_OUTPUT_HANDLER_WRITE = 0;
    public final static int PHP_OUTPUT_HANDLER_FLUSH = 4;
    public final static int PHP_OUTPUT_HANDLER_CLEAN = 2;
    public final static int PHP_OUTPUT_HANDLER_FINAL = 8;
    public final static int PHP_OUTPUT_HANDLER_CONT = 0;
    public final static int PHP_OUTPUT_HANDLER_END = 8;
}
