package php.runtime.lang.spl.iterator;

import php.runtime.Memory;
import php.runtime.env.Environment;

/** Internal native SPL offset/property bridge; does not certify managed storage. */
@php.runtime.annotation.Reflection.Ignore
public interface SplArrayAccess {
    boolean arrayAsProperties();
    Memory propertyGet(Environment env, String key);
    Memory propertySet(Environment env, String key, Memory value);
    Memory propertyUnset(Environment env, String key);
    Memory propertyIsset(Environment env, String key);
    Memory offsetAddress(Environment env, Memory key);
    Memory offsetIsset(Environment env, Memory key);
}
