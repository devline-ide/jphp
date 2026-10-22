package php.runtime.ext.core.reflection;

import php.runtime.Memory;
import php.runtime.env.Environment;
import php.runtime.lang.BaseObject;
import php.runtime.lang.PhpFiber;
import php.runtime.lang.exception.BaseError;
import php.runtime.memory.ArrayMemory;
import php.runtime.memory.LongMemory;
import php.runtime.memory.ObjectMemory;
import php.runtime.memory.StringMemory;
import php.runtime.reflection.ClassEntity;
import static php.runtime.annotation.Reflection.*;

/** PHP-frame view of the actual Fiber; no carrier-thread Java stack is presented as PHP. */
@Name("ReflectionFiber") @Final @NativePhpSignature
public class ReflectionFiber extends BaseObject {
    private PhpFiber fiber;
    public ReflectionFiber(Environment env,ClassEntity clazz) {super(env,clazz);}
    @Signature(@Arg(value="fiber",typeDeclaration="Fiber"))
    public Memory __construct(Environment env,Memory...args) {fiber=(PhpFiber)args[0].toValue(ObjectMemory.class).value;return Memory.NULL;}
    private void requireStack(Environment env) {
        if(fiber==null || !fiber.hasStack())env.exception(BaseError.class,
                "Cannot fetch information from a fiber that has not been started or is terminated");
    }
    @Signature(result=@Arg(typeDeclaration="Fiber"))
    public Memory getFiber(Environment env,Memory...args) {return new ObjectMemory(fiber);}
    @Signature(result=@Arg(typeDeclaration="callable"))
    public Memory getCallable(Environment env,Memory...args) {
        if(fiber.callable()==null)env.exception(BaseError.class,"Cannot fetch the callable from a fiber that has terminated");
        return fiber.callable().toImmutable();
    }
    @Signature(result=@Arg(typeDeclaration="string"))
    public Memory getExecutingFile(Environment env,Memory...args) {
        requireStack(env);return new StringMemory(fiber.suspensionTrace().getFileName());
    }
    @Signature(result=@Arg(typeDeclaration="int"))
    public Memory getExecutingLine(Environment env,Memory...args) {
        requireStack(env);return LongMemory.valueOf(fiber.suspensionTrace().getStartLine()+1);
    }
    @Signature(value=@Arg(value="options",typeDeclaration="int",optional=@Optional(value="1",type=php.runtime.common.HintType.INT)),result=@Arg(typeDeclaration="array"))
    public Memory getTrace(Environment env,Memory...args) {
        requireStack(env);int options=args[0].toInteger();var result=new ArrayMemory();
        var frames=fiber.stackSnapshot();
        for(int i=frames.length-1;i>=0;i--)result.add(frames[i].toArray(env,(options&1)!=0,(options&2)!=0));
        return result.toImmutable();
    }
}
