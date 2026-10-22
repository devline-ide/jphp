package php.runtime.lang.support;
import php.runtime.annotation.Reflection.Ignore;
import php.runtime.env.Environment;
import php.runtime.lang.IObject;
import php.runtime.memory.ArrayMemory;
/** Ordinary native date observation protocol; never grants an ownership certificate. */
@Ignore
public interface IPhpNativeDateView extends IObject {
    ArrayMemory phpDateExport(Environment environment);
    void phpDateCopyStateTo(IObject actualClone);
    /** Only native date values may opt into cross-declaration instant comparison. */
    default boolean phpDateComparable(IObject other){return false;}
}
