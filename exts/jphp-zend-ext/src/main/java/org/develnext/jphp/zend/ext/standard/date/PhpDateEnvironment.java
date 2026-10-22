package org.develnext.jphp.zend.ext.standard.date;

import php.runtime.Memory;
import php.runtime.env.Environment;
import php.runtime.memory.ArrayMemory;
import java.util.List;

/** Java-only Env local configuration/diagnostics; no hidden PHP heap values. */
final class PhpDateEnvironment {
    private static final String KEY=PhpDateEnvironment.class.getName();
    record State(PhpDateZone zone,List<PhpDateParser.Diagnostic> warnings,List<PhpDateParser.Diagnostic> errors) {
        State{warnings=List.copyOf(warnings);errors=List.copyOf(errors);}
    }
    static State state(Environment env){State state=env.getUserValue(KEY,State.class);return state==null?new State(PhpDateZone.utc(),List.of(),List.of()):state;}
    static PhpDateZone zone(Environment env){return state(env).zone();}
    static void zone(Environment env,PhpDateZone zone){State old=state(env);env.setUserValue(KEY,new State(zone,old.warnings(),old.errors()));}
    static void parsed(Environment env,List<PhpDateParser.Diagnostic>warnings){env.setUserValue(KEY,new State(zone(env),warnings,List.of()));}
    static void failed(Environment env,int at,String message){env.setUserValue(KEY,new State(zone(env),List.of(),List.of(new PhpDateParser.Diagnostic(at,message))));}
    static void failed(Environment env,PhpDateParser.Failure failure){env.setUserValue(KEY,new State(zone(env),failure.warnings,failure.errors));}
    static Memory errors(Environment env) {
        State state=state(env);if(state.warnings().isEmpty()&&state.errors().isEmpty())return Memory.FALSE;
        var result=new ArrayMemory();var warnings=new ArrayMemory();var errors=new ArrayMemory();
        for(var item:state.warnings())warnings.refOfIndex(item.position()).assign(item.message());
        for(var item:state.errors())errors.refOfIndex(item.position()).assign(item.message());
        result.refOfIndex("warning_count").assign(state.warnings().size());result.refOfIndex("warnings").assign(warnings);
        result.refOfIndex("error_count").assign(state.errors().size());result.refOfIndex("errors").assign(errors);return result.toConstant();
    }
}
