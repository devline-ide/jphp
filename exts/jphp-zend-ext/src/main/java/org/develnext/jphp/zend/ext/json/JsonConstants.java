package org.develnext.jphp.zend.ext.json;

import php.runtime.ext.support.compile.ConstantsContainer;

public class JsonConstants extends ConstantsContainer {
    public final static int JSON_HEX_TAG = 1;
    public final static int JSON_HEX_AMP = 2;
    public final static int JSON_HEX_APOS = 4;
    public final static int JSON_HEX_QUOT = 8;
    public final static int JSON_FORCE_OBJECT = 16;
    public final static int JSON_NUMERIC_CHECK = 32;
    public final static int JSON_OBJECT_AS_ARRAY = 1;
    public final static int JSON_BIGINT_AS_STRING = 2;
    public final static int JSON_PRETTY_PRINT = 128;
    public final static int JSON_UNESCAPED_SLASHES = 64;
    public final static int JSON_UNESCAPED_UNICODE = 256;
    public final static int JSON_PARTIAL_OUTPUT_ON_ERROR = 512;
    public final static int JSON_PRESERVE_ZERO_FRACTION = 1024;
    public final static int JSON_UNESCAPED_LINE_TERMINATORS = 2048;
    public final static int JSON_THROW_ON_ERROR = 4194304;
    public final static int JSON_INVALID_UTF8_IGNORE = 1048576;
    public final static int JSON_INVALID_UTF8_SUBSTITUTE = 2097152;

    public final static int JSON_ERROR_NONE = 0;
    public final static int JSON_ERROR_DEPTH = 1;
    public final static int JSON_ERROR_STATE_MISMATCH = 2;
    public final static int JSON_ERROR_CTRL_CHAR = 3;
    public final static int JSON_ERROR_SYNTAX = 4;
    public final static int JSON_ERROR_UTF8 = 5;
    public final static int JSON_ERROR_RECURSION = 6;
    public final static int JSON_ERROR_INF_OR_NAN = 7;
    public final static int JSON_ERROR_UNSUPPORTED_TYPE = 8;
    public final static int JSON_ERROR_INVALID_PROPERTY_NAME = 9;
    public final static int JSON_ERROR_UTF16 = 10;
    public final static int JSON_ERROR_NON_BACKED_ENUM = 11;
}
