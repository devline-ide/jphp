package org.develnext.jphp.core.compiler.jvm;

import org.junit.Test;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.Context;
import php.runtime.env.Environment;
import php.runtime.loader.dump.ModuleDumper;
import java.io.*;
import static org.junit.Assert.*;

/** Keep source line endings and document bytes intact across compilation and PHB. */
public class PhpHeredocLineEndingTest extends JvmCompilerCase {
    private void check(String source, String expected) throws Throwable {
        for (PhpLanguageProfile profile : PhpLanguageProfile.values())
            for (boolean debug : new boolean[]{false,true})
                for (boolean dumped : new boolean[]{false,true}) {
                    var scope = newScope();
                    scope.setPhpLanguageProfile(profile);
                    scope.setDebugMode(debug);
                    scope.registerExtension(new org.develnext.jphp.zend.ext.standard.StandardExtension());
                    var env = new Environment(scope, new ByteArrayOutputStream());
                    try {
                        var context = new Context(source); context.setPhpLanguageProfile(profile);
                        var module = new JvmCompiler(env, context).compile(false);
                        if (dumped) {
                            var dumper = new ModuleDumper(context,env,true);
                            var bytes = new ByteArrayOutputStream(); dumper.save(module,bytes);
                            module = dumper.load(new ByteArrayInputStream(bytes.toByteArray()));
                        }
                        scope.loadModule(module); scope.addUserModule(module); env.registerModule(module);
                        module.include(env);
                        assertEquals(profile+" debug="+debug+" PHB="+dumped,expected,env.getDefaultBuffer().getContents().toString());
                    } finally { env.doFinal(); scope.getClassLoader().close(); }
                }
    }
    @Test public void documentBytesInterpolationAndFollowingLineSourceAndDump() throws Throwable {
        for (String newline : new String[]{"\n","\r\n"})
            for (String label : new String[]{"DOC","\"DOC\"","'DOC'"}) {
                String text = label.equals("'DOC'") ? "first $x\\n"+newline+"second" : "first value"+newline+"second";
                String body = label.equals("'DOC'") ? "first $x\\n"+newline+"second" : "first $x"+newline+"second";
                check("$x='value';$s=<<<"+label+newline+body+newline+"DOC;"+newline+"echo bin2hex($s),'|',__LINE__;",
                        java.util.HexFormat.of().formatHex(text.getBytes(java.nio.charset.StandardCharsets.UTF_8))+"|5");
            }
    }
    @Test public void emptyDocumentsAndClosingLabelAtEofSourceAndDump() throws Throwable {
        for (String newline : new String[]{"\n","\r\n"})
            for (String label : new String[]{"DOC","\"DOC\"","'DOC'"}) {
                check("$s=<<<"+label+newline+"DOC;"+newline+"echo serialize($s),'|',__LINE__;","s:0:\"\";|3");
                check("echo <<<"+label+newline+"text"+newline+"DOC;","text");
                check("$s=<<<"+label+newline+"DOCsuffix"+newline+"DOC;"+newline+"echo $s;","DOCsuffix");
            }
    }
}
