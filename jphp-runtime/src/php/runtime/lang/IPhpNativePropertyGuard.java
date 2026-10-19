package php.runtime.lang;
import php.runtime.env.Environment;
import php.runtime.env.TraceInfo;
import php.runtime.Memory;
public interface IPhpNativePropertyGuard {
    void validateNativePropertyWrite(Environment env,TraceInfo trace,String property);
    void validateNativePropertyReference(Environment env,TraceInfo trace,String property,Memory current);
}
