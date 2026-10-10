package php.runtime;

/** An identity-only internal marker, distinct from every PHP return value, including undefined/null. */
public final class PhpModuleContinuation {
    public static final Memory CONTINUE = new php.runtime.memory.NullMemory() {};
    private PhpModuleContinuation() {}
    public static boolean isContinue(Memory result) {return result == CONTINUE;}
}
