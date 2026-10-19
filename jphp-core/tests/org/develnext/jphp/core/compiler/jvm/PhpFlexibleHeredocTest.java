package org.develnext.jphp.core.compiler.jvm;

import org.junit.Test;
import php.runtime.Memory;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.*;
import php.runtime.env.handler.ErrorReportHandler;
import php.runtime.env.message.SystemMessage;
import php.runtime.exceptions.support.ErrorException;
import php.runtime.ext.*;
import php.runtime.loader.dump.ModuleDumper;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.Assert.*;

/** Actual native pinned PHP source, compiled and independently transported through PHB. */
public class PhpFlexibleHeredocTest {
    private void group(String group) throws Throwable {
        var stream=getClass().getClassLoader().getResourceAsStream("resources/strings/php85_flexible_heredoc.tsv");
        assertNotNull(stream);int count=0;
        for(String line:new String(stream.readAllBytes(),StandardCharsets.UTF_8).split("\\R")) {
            String[] pin=line.split("\t",-1);if(!pin[0].equals(group))continue;count++;
            String source=new String(Base64.getDecoder().decode(pin[2]),StandardCharsets.UTF_8);
            for(boolean debug:new boolean[]{false,true})for(boolean dumped:new boolean[]{false,true}) {
                String label=pin[1]+",debug="+debug+",PHB="+dumped;
                var scope=new CompileScope();scope.registerExtension(new CoreExtension());scope.registerExtension(new SPLExtension());
                scope.registerExtension(new org.develnext.jphp.zend.ext.ZendExtension());
                scope.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);scope.setDebugMode(debug);
                var output=new ByteArrayOutputStream();var env=new Environment(scope,output);var warnings=new ArrayList<String>();var warningLines=new ArrayList<String>();
                env.setErrorReportHandler(new ErrorReportHandler(){
                    public boolean onError(SystemMessage message){warnings.add(message.getType().value+":"+message.getMessage());warningLines.add(Integer.toString(message.getTrace().trace.getStartLine()+1));return true;}
                    public boolean onFatal(ErrorException failure){throw new AssertionError(failure);}
                });
                try {
                    var context=new Context(source);context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
                    php.runtime.reflection.ModuleEntity module;
                    if (!pin[6].isEmpty()) {
                        try {new JvmCompiler(env,context).compile(false);fail(label+",invalid indentation accepted");}
                        catch (php.runtime.exceptions.ParseException failure) {
                            assertEquals(label,new String(Base64.getDecoder().decode(pin[6]),StandardCharsets.UTF_8),failure.getMessage());
                            assertEquals(label,Integer.parseInt(pin[7]),failure.getTraceInfo().getStartLine()+1);
                        }
                        continue;
                    }
                    module=new JvmCompiler(env,context).compile(false);
                    if(dumped){var dumper=new ModuleDumper(context,env,true);var bytes=new ByteArrayOutputStream();dumper.save(module,bytes);module=dumper.load(new ByteArrayInputStream(bytes.toByteArray()));}
                    scope.loadModule(module);scope.addUserModule(module);env.registerModule(module);
                    Memory result=module.include(env);env.flushAll();
                    assertEquals(label+",return",pin[3],HexFormat.of().formatHex(result.getBinaryBytes(StandardCharsets.UTF_8)));
                    assertEquals(label+",output",pin[4],HexFormat.of().formatHex(output.toByteArray()));
                    assertEquals(label+",warnings",new String(Base64.getDecoder().decode(pin[5]),StandardCharsets.UTF_8),String.join("\n",warnings));
                    assertEquals(label+",warningLines",pin[8],String.join(",",warningLines));
                } finally { env.doFinal(); }
            }
        }
        assertTrue(group,count>0);
    }
    @Test public void nativeFlexibleSyntax()throws Throwable{group("syntax");}
    @Test public void nativeFlexibleRawInterpolation()throws Throwable{group("raw");}
    @Test public void nativePhysicalInterpolationLines()throws Throwable{group("origin");}
    @Test public void legacyDocumentSemanticsRemainUnstripped()throws Throwable {
        String source="$s=<<<TXT\n  A\n  TXT;\nTXT;\nreturn $s;";
        for(boolean debug:new boolean[]{false,true})for(boolean dumped:new boolean[]{false,true}) {
            var scope=new CompileScope();scope.registerExtension(new CoreExtension());scope.setPhpLanguageProfile(PhpLanguageProfile.JPHP_LEGACY);scope.setDebugMode(debug);
            var env=new Environment(scope,new ByteArrayOutputStream());
            try {
                var context=new Context(source);context.setPhpLanguageProfile(PhpLanguageProfile.JPHP_LEGACY);
                var module=new JvmCompiler(env,context).compile(false);
                if(dumped){var dumper=new ModuleDumper(context,env,true);var bytes=new ByteArrayOutputStream();dumper.save(module,bytes);module=dumper.load(new ByteArrayInputStream(bytes.toByteArray()));}
                scope.loadModule(module);scope.addUserModule(module);env.registerModule(module);
                assertEquals("  A\n  TXT;",module.include(env).toString());
            } finally {env.doFinal();}
        }
    }
    @Test public void physicalTokenSpansAndSlicesRetainRawPositions()throws Throwable {
        for(String nl:new String[]{"\n","\r\n"}) {
            String source="$s=<<<TXT"+nl+"  \\xff$xé"+nl+"  TXT;return 1;";
            var context=new Context(source);context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
            var lexer=new org.develnext.jphp.core.tokenizer.Tokenizer(context);lexer.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
            org.develnext.jphp.core.tokenizer.token.expr.value.StringExprToken literal=null;org.develnext.jphp.core.tokenizer.token.Token token;
            while((token=lexer.nextToken())!=null)if(token instanceof org.develnext.jphp.core.tokenizer.token.expr.value.StringExprToken)literal=(org.develnext.jphp.core.tokenizer.token.expr.value.StringExprToken)token;
            assertNotNull(literal);assertEquals(source.indexOf("<<<"),literal.getMeta().getStartIndex());
            assertEquals(source.lastIndexOf("TXT")+3,literal.getMeta().getEndIndex());assertEquals(0,literal.getMeta().getStartLine());assertEquals(2,literal.getMeta().getEndLine());
            assertEquals("ff2478c3a9",HexFormat.of().formatHex(literal.getPhp85Bytes()));
            var segment=literal.getSegments().get(0);assertEquals(1,segment.from);assertEquals(3,segment.to);
            assertTrue(segment instanceof org.develnext.jphp.core.tokenizer.token.expr.value.StringExprToken.SourceSegment);
            var origin=(org.develnext.jphp.core.tokenizer.token.expr.value.StringExprToken.SourceSegment)segment;assertEquals(1,origin.sourceLine);assertEquals(6,origin.sourcePosition);
            assertEquals("ff",HexFormat.of().formatHex(literal.slice(0,1).getPhp85Bytes()));assertEquals("c3a9",HexFormat.of().formatHex(literal.slice(3,4).getPhp85Bytes()));
            var sliced=literal.slice(1,3);assertEquals(0,sliced.getSegments().get(0).from);assertEquals(2,sliced.getSegments().get(0).to);
            assertEquals(origin.sourceLine,((org.develnext.jphp.core.tokenizer.token.expr.value.StringExprToken.SourceSegment)sliced.getSegments().get(0)).sourceLine);
        }
    }
}
