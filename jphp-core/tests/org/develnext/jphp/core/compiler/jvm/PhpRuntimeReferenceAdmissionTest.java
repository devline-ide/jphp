package org.develnext.jphp.core.compiler.jvm;

import org.junit.Test;
import php.runtime.*;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.*;
import php.runtime.ext.*;
import php.runtime.loader.dump.ModuleDumper;
import php.runtime.memory.ArrayMemory;
import php.runtime.memory.output.serialization.PhpSerialization;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.*;
import static org.junit.Assert.*;

/** Original native lifetime corpus through connected ordinary producers. Default tests stay independent. */
public class PhpRuntimeReferenceAdmissionTest {
    private void check(String family,Set<String> supported,int expectedComparisons) throws Throwable {
        int comparisons=0,rows=0;
        try(var resource=getClass().getClassLoader().getResourceAsStream("resources/type_hinting/php85_runtime_reference_owners.tsv")) {
            assertNotNull(resource);
            for(String line:new String(resource.readAllBytes(),StandardCharsets.UTF_8).split("\\R")) {
                if(line.isEmpty())continue;
                String[] row=line.split("\t",-1);assertEquals(5,row.length);rows++;
                if(!row[0].equals(family)||!supported.contains(row[1]))continue;
                String source=new String(Base64.getDecoder().decode(row[3]),StandardCharsets.UTF_8);
                String expected=new String(Base64.getDecoder().decode(row[4]),StandardCharsets.UTF_8);
                Matcher literal=Pattern.compile("unserialize\\('([^']*)'\\)").matcher(source);
                String wire=literal.find()?literal.group(1):null;
                for(boolean debug:new boolean[]{false,true})for(boolean dumped:new boolean[]{false,true}) {
                    comparisons++;
                    String label=row[1]+"/"+row[2]+"/debug="+debug+"/dump="+dumped;
                    var scope=new CompileScope();scope.registerExtension(new CoreExtension());scope.registerExtension(new SPLExtension());
                    scope.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);scope.setDebugMode(debug);
                    var output=new ByteArrayOutputStream();var env=new Environment(scope,output);var domain=new PhpOwnershipDomain();
                    env.getDefaultBuffer().setChunkSize(0);
                    try {
                        var context=new Context(source.startsWith("<?php")?source.substring(5):source);
                        context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
                        var module=new JvmCompiler(env,context).compile(false);
                        if(dumped) {
                            var bytes=new ByteArrayOutputStream();var dumper=new ModuleDumper(context,env,true);
                            dumper.save(module,bytes);module=dumper.load(new ByteArrayInputStream(bytes.toByteArray()));
                        }
                        scope.loadModule(module);scope.addUserModule(module);env.registerModule(module);
                        var authority=new PhpRuntimeOwnership.Authority(env,domain);
                        try(var execution=authority.enter()) {
                            try {
                                if(wire!=null) {
                                    var reader=PhpSerialization.class.getMethod("unserialize",Environment.class,TraceInfo.class,Memory.class);
                                    var writer=PhpSerialization.class.getMethod("serialize",Environment.class,TraceInfo.class,Memory.class);
                                    authority.registerNative(reader,new PhpRuntimeOwnership.CompleteNativeProtocol() {
                                        public void validateMember(java.lang.reflect.Method actual){assertEquals(reader,actual);}
                                        public void validateArguments(List<PhpRuntimeOwnership.Value> arguments) {
                                            assertEquals(1,arguments.size());var argument=arguments.getFirst();
                                            assertEquals(PhpRuntimeOwnership.Role.VALUE,argument.role());assertNull(argument.node());
                                            assertEquals(wire,argument.raw().toString());
                                        }
                                    });
                                    authority.registerNative(writer,new PhpRuntimeOwnership.CompleteNativeProtocol() {
                                        public void validateMember(java.lang.reflect.Method actual){assertEquals(writer,actual);}
                                        public void validateArguments(List<PhpRuntimeOwnership.Value> arguments) {
                                            assertEquals(1,arguments.size());var argument=arguments.getFirst();
                                            assertEquals(PhpRuntimeOwnership.Role.VALUE,argument.role());assertNotNull(argument.node());
                                            assertTrue(argument.raw() instanceof ArrayMemory);
                                        }
                                    });
                                }
                                for(var closure:module.getClosures()) {
                                    var method=closure.findMethod("__invoke");assertNotNull(method);
                                    authority.registerMethod(method,closure.getData(),(plan,member,bytes)->{
                                        assertEquals(PhpOwnershipExecution.EntryKind.LEXICAL_CLOSURE,plan.entryKind());
                                        assertEquals(method.getNativeMethod(),member);assertArrayEquals(closure.getData(),bytes);
                                    });
                                }
                                var exactModule=module;
                                authority.registerModule(module,module.getData(),(plan,member,bytes)->{
                                    assertEquals(PhpOwnershipExecution.EntryKind.MODULE,plan.entryKind());
                                    assertEquals(exactModule.getNativeMethod(),member);assertArrayEquals(exactModule.getData(),bytes);
                                });
                                try(var result=authority.include(module)){assertTrue(result.raw().isNull());}
                                env.getDefaultBuffer().flush();assertEquals(label,expected,output.toString(StandardCharsets.UTF_8));
                                assertNull(PhpRuntimeOwnership.currentNativeBody(env));
                            } finally {
                                authority.close();domain.collectCycles();assertEquals(label+" released graph",0,domain.graph().liveNodes());
                            }
                        }
                    } finally {env.doFinal();}
                }
            }
        }
        assertEquals("Original native corpus must stay complete",24,rows);
        assertEquals("All admitted native variants must execute",expectedComparisons,comparisons);
    }
    @Test public void admittedOriginalCycleLifetimesSourceDumpStrictAndDebug() throws Throwable {
        check("cycle",Set.of("single","single-copy","single-external-root","repeat","single-external-cell","single-external-cell-unset"),48);
    }
    @Test public void admittedOriginalStaticLifetimesSourceDumpStrictAndDebug() throws Throwable {
        check("static",Set.of("static-live","static-released","static-rebound","static-capture-retains","static-capture-released"),40);
    }
}
