package org.develnext.jphp.pcre;
import java.nio.file.Path;
import java.util.Map;
import php.runtime.env.CompileScope;
import php.runtime.env.Environment;
import php.runtime.ext.support.Extension;
/** Real PCRE2 provider; standalone legacy php/util/Regex retains its separate Java-regex contract. */
public final class PcreExtension extends Extension {
    private final Path explicitLibrary;
    private final String explicitHash;
    private volatile PcreNativePackage.Metadata metadata;
    private volatile EngineResult initialized;
    public PcreExtension(){this(null,null);}
    public PcreExtension(Path library,String sha256){
        if((library==null)!=(sha256==null))throw new IllegalArgumentException("Explicit PCRE2 path and digest must be supplied together");
        explicitLibrary=library;explicitHash=sha256;
    }
    private record EngineResult(Pcre2Bytes engine,IllegalStateException failure) {}
    private PcreNativePackage.Metadata metadata(){
        PcreNativePackage.Metadata value=metadata;
        if(value==null)synchronized(this){
            value=metadata;
            if(value==null){
                try{value=PcreNativePackage.metadata();}
                catch(Exception failure){throw new IllegalStateException("PCRE2 package metadata unavailable",failure);}
                if(explicitLibrary!=null&&!value.sha256().equals(explicitHash))
                    throw new IllegalStateException("Explicit PCRE2 payload does not match packaged configuration");
                metadata=value;
            }
        }
        return value;
    }
    /** No Environment is retained by the shared engine or its cached initialization failure. */
    Pcre2Bytes engine(){
        EngineResult value=initialized;
        if(value==null)synchronized(this){
            value=initialized;
            if(value==null){
                try{
                    PcreNativePackage.Metadata declared=metadata();
                    PcreNativePackage.requireHost(declared);
                    Path library=explicitLibrary==null?PcreNativePackage.packaged(declared)
                            :PcreNativePackage.verified(explicitLibrary,explicitHash);
                    Pcre2Bytes actual=new Pcre2Bytes(library);
                    if(!declared.version().equals(actual.version())||declared.jit()!=actual.jitSupported())
                        throw new IllegalStateException("PCRE2 runtime configuration does not match packaged metadata");
                    value=new EngineResult(actual,null);
                }catch(VirtualMachineError|ThreadDeath fatal){throw fatal;}
                catch(Throwable failure){value=new EngineResult(null,new IllegalStateException("PCRE2 runtime unavailable: "+failure.getMessage(),failure));}
                initialized=value;
            }
        }
        if(value.failure()!=null)throw value.failure();
        return value.engine();
    }
    public String nativeVersion()throws Throwable{return engine().version();}
    public boolean nativeJitSupported()throws Throwable{return engine().jitSupported();}
    @Override public String getName(){return "pcre";}
    @Override public Status getStatus(){return Status.BETA;}
    @Override public Map<String,String> getINIEntries(){return Map.of("pcre.backtrack_limit","1000000","pcre.recursion_limit","100000","pcre.jit","1");}
    @Override public void onRegister(CompileScope scope){
        PcreNativePackage.Metadata declared=metadata();
        // These constants describe the digest-pinned build target, independently of the compiler host.
        registerConstants(new PcreConstants(declared.version(),declared.jit()));
        for(var functions:new php.runtime.ext.support.compile.FunctionsContainer[]{new PregFunctions(),new PregReplaceFunctions(),new PregCallbackFunctions()})
            for(var function:functions.getFunctionSpecs())scope.registerPhp85Function(this,function);
        for(var entry:getINIEntries().entrySet())
            scope.configuration.putIfAbsent(entry.getKey(),php.runtime.memory.StringMemory.valueOf(entry.getValue()));
    }
    @Override public void onLoad(Environment env){env.setUserValue(new PcreState(this));}
}
