package org.develnext.jphp.pcre;
import php.runtime.env.Environment;
final class PcreState {
    private final PcreExtension extension;
    int error;
    PcreState(PcreExtension extension){this.extension=extension;}
    Pcre2Bytes engine(){return extension.engine();}
    static PcreState of(Environment env){
        PcreState state=env.getUserValue(PcreState.class);
        if(state==null)throw new IllegalStateException("PCRE extension not loaded in this Environment");
        return state;
    }
}
