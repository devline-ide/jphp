package org.develnext.jphp.zend.ext.standard.date;
import php.runtime.annotation.Reflection.Ignore;
/** Implementation-only immutable value access; never a PHP-visible property or interface. */
@Ignore
interface PhpDateCarrier extends PhpDateTimeInterface {
    PhpDateValue dateValue();
    void dateValue(PhpDateValue value);
    boolean dateImmutable();
}
