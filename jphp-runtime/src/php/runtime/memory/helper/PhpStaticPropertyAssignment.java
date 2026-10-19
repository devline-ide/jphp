package php.runtime.memory.helper;
import php.runtime.Memory;
import php.runtime.PhpPropertyAccess;
import php.runtime.env.Environment;
import php.runtime.env.TraceInfo;
import php.runtime.memory.*;
import php.runtime.reflection.PropertyEntity;
/** Only restricted static ASSIGN uses this transient view; references export the real checked backing CELL. */
public final class PhpStaticPropertyAssignment extends ReferenceMemory {
    private final ReferenceMemory cell;
    private final PropertyEntity property;
    private final Environment env;
    private final TraceInfo trace;
    public PhpStaticPropertyAssignment(ReferenceMemory cell,PropertyEntity property,Environment env,TraceInfo trace){
        super(cell);this.cell=cell;this.property=property;this.env=env;this.trace=trace;
    }
    @Override public Memory assign(Memory value){PhpPropertyAccess.check(property,env,trace,PhpPropertyAccess.Intent.ASSIGN);return cell.assign(value);}
    @Override public Memory assign(long value){return assign(LongMemory.valueOf(value));}
    @Override public Memory assign(double value){return assign(DoubleMemory.valueOf(value));}
    @Override public Memory assign(boolean value){return assign(value?Memory.TRUE:Memory.FALSE);}
    @Override public Memory assign(String value){return assign(StringMemory.valueOf(value));}
    @Override public ReferenceMemory getReferenceForBinding(){
        PhpPropertyAccess.check(property,env,trace,PhpPropertyAccess.Intent.MUTABLE_BACKING_ADDRESS);return cell.getReferenceForBinding();
    }
}
