package org.develnext.jphp.zend.ext.standard.date;
import php.runtime.env.CompileScope;
import php.runtime.ext.support.Extension;
/** New public date functionality is profile-scoped; legacy DateFunctions remain registered unchanged. */
public final class PhpDateProvider {
    private PhpDateProvider(){}
    public static void register(CompileScope scope,Extension extension) {
        scope.registerPhp85LazyClass(extension,PhpDateTimeInterface.class);
        scope.registerPhp85LazyClass(extension,PhpDateTime.class);
        scope.registerPhp85LazyClass(extension,PhpDateTimeImmutable.class);
        scope.registerPhp85LazyClass(extension,PhpDateTimeZone.class);
        scope.registerPhp85LazyClass(extension,PhpDateInterval.class);
        scope.registerPhp85LazyClass(extension,PhpDateException.class);
        scope.registerPhp85LazyClass(extension,PhpDateMalformedStringException.class);
        scope.registerPhp85LazyClass(extension,PhpDateMalformedIntervalStringException.class);
        scope.registerPhp85LazyClass(extension,PhpDateInvalidTimeZoneException.class);
        scope.registerPhp85LazyClass(extension,PhpDateError.class);
        scope.registerPhp85LazyClass(extension,PhpDateObjectError.class);
        scope.registerPhp85LazyClass(extension,PhpDateRangeError.class);
        for(var function:new PhpDateFunctions().getFunctionSpecs())scope.registerPhp85Function(extension,function);
    }
}
