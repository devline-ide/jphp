package php.runtime.lang.support;

import php.runtime.PhpOwnershipObjects;
import php.runtime.annotation.Reflection.Ignore;
import php.runtime.lang.IObject;

/** Explicit actual-object origin hook. Implementing this interface alone grants no allocation capability. */
@Ignore
public interface IPhpOwnershipObject extends IObject {
    PhpOwnershipObjects.ObjectCertificate getOwnershipObject();
    void attachOwnershipObject(PhpOwnershipObjects.ObjectCertificate certificate,
                               PhpOwnershipObjects.AllocationWitness witness);
}
