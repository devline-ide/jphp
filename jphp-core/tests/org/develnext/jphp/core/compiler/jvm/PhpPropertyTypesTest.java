package org.develnext.jphp.core.compiler.jvm;

import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.util.CheckClassAdapter;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.Context;
import php.runtime.env.Environment;
import php.runtime.exceptions.support.ErrorException;
import php.runtime.loader.dump.ModuleDumper;
import php.runtime.reflection.ModuleEntity;
import php.runtime.reflection.PropertyEntity;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.Assert.*;

public class PhpPropertyTypesTest extends JvmCompilerCase {
    @Test public void testNativePropertyMetadataAndInitialization() throws Throwable {
        for (boolean dumped : new boolean[] {false, true}) {
            Environment env = new Environment(newScope(), new ByteArrayOutputStream());
            env.getScope().registerExtension(new org.develnext.jphp.zend.ext.standard.StandardExtension());
            try {
                Context context = new Context(resource("php85_property_metadata.php").substring(5));
                context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
                var module = new JvmCompiler(env, context).compile(false);
                if (dumped) {
                    var dumper = new ModuleDumper(context, env, true); var bytes = new ByteArrayOutputStream();
                    dumper.save(module, bytes); module = dumper.load(new ByteArrayInputStream(bytes.toByteArray()));
                }
                env.getScope().setPhpLanguageProfile(PhpLanguageProfile.JPHP_LEGACY);
                env.getScope().loadModule(module); env.getScope().addUserModule(module); env.registerModule(module);
                var result = module.include(env);
                var trace = php.runtime.env.TraceInfo.valueOf(context.getFileName(), 0, 0, module.getInternalName());
                var expected = php.runtime.memory.output.serialization.PhpSerialization.unserialize(env, trace,
                        php.runtime.memory.StringMemory.valueOf(resource("php85_property_metadata.expected")));
                var differences = new ArrayList<String>();
                int count = expected.toValue(php.runtime.memory.ArrayMemory.class).size();
                assertEquals(count, result.toValue(php.runtime.memory.ArrayMemory.class).size());
                for (int i=0; i<count; i++) {
                    String wanted = php.runtime.memory.output.serialization.PhpSerialization.serialize(env, trace, expected.valueOfIndex(i)).toString();
                    String actual = php.runtime.memory.output.serialization.PhpSerialization.serialize(env, trace, result.valueOfIndex(i)).toString();
                    if (!wanted.equals(actual)) differences.add("row=" + i + " expected=" + wanted + " actual=" + actual);
                }
                assertEquals("dump=" + dumped + "\n" + String.join("\n", differences), 0, differences.size());
            } finally {env.doFinal();}
        }
    }

    @Test public void testNativeReflectionFlagsStayIsolatedFromLegacyCalls() throws Throwable {
        for (boolean dumped : new boolean[] {false, true}) {
            Environment env = new Environment(newScope(), new ByteArrayOutputStream());
            try {
                for (PhpLanguageProfile profile : PhpLanguageProfile.values()) {
                    String fn = profile == PhpLanguageProfile.PHP_8_5 ? "modernFlags" : "legacyFlags";
                    String code = "function " + fn + "() { $c='ReflectionProperty'; return [$c::IS_PUBLIC,$c::IS_STATIC,"
                            + "(new ReflectionClass($c))->getConstant('IS_PUBLIC'),"
                            + "(new ReflectionClassConstant($c,'IS_STATIC'))->getValue()]; }";
                    Context context = new Context(code); context.setPhpLanguageProfile(profile);
                    var module = new JvmCompiler(env, context).compile(false);
                    if (dumped) {
                        var dumper = new ModuleDumper(context, env, true); var bytes = new ByteArrayOutputStream();
                        dumper.save(module, bytes); module = dumper.load(new ByteArrayInputStream(bytes.toByteArray()));
                    }
                    env.getScope().loadModule(module); env.getScope().addUserModule(module); env.registerModule(module); module.include(env);
                }
                env.getScope().setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
                var result = env.eval("return [legacyFlags(),modernFlags(),legacyFlags(),modernFlags()];");
                for (int i=0; i<4; i++) {
                    int[] expected = i % 2 == 0 ? new int[]{256,1,256,1} : new int[]{1,16,1,16};
                    for (int j=0; j<expected.length; j++) assertEquals("dump=" + dumped + " row=" + i + " col=" + j,
                            expected[j], result.valueOfIndex(i).valueOfIndex(j).toLong());
                }
            } finally {env.doFinal();}
        }
    }

    @Test public void testNativePropertyRuntimeWritesAndReferences() throws Throwable {
        var differences=new ArrayList<String>();
        for (String fixture : new String[]{"php85_property_runtime", "php85_property_strict"}) for (boolean dumped : new boolean[]{false,true}) {
            Environment env=new Environment(newScope(),new ByteArrayOutputStream());
            env.getScope().registerExtension(new org.develnext.jphp.zend.ext.standard.StandardExtension());
            try {
                Context context=new Context(resource(fixture+".php").substring(5));context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
                var module=new JvmCompiler(env,context).compile(false);
                if (dumped) {var dumper=new ModuleDumper(context,env,true);var bytes=new ByteArrayOutputStream();dumper.save(module,bytes);module=dumper.load(new ByteArrayInputStream(bytes.toByteArray()));}
                env.getScope().setPhpLanguageProfile(PhpLanguageProfile.JPHP_LEGACY);
                env.getScope().loadModule(module);env.getScope().addUserModule(module);env.registerModule(module);
                var result=module.include(env);
                var trace=php.runtime.env.TraceInfo.valueOf(context.getFileName(),0,0,module.getInternalName());
                var expected=php.runtime.memory.output.serialization.PhpSerialization.unserialize(env,trace,
                        php.runtime.memory.StringMemory.valueOf(resource(fixture+".expected")));
                int count=expected.toValue(php.runtime.memory.ArrayMemory.class).size();
                assertEquals(fixture,count,result.toValue(php.runtime.memory.ArrayMemory.class).size());
                for (int i=0;i<count;i++) {
                    String wanted=php.runtime.memory.output.serialization.PhpSerialization.serialize(env,trace,expected.valueOfIndex(i)).toString();
                    String actual=php.runtime.memory.output.serialization.PhpSerialization.serialize(env,trace,result.valueOfIndex(i)).toString();
                    if (!wanted.equals(actual)) differences.add(fixture+" dump="+dumped+" row="+i+" expected="+wanted+" actual="+actual);
                }
            } finally {env.doFinal();}
        }
        if (!differences.isEmpty()) java.nio.file.Files.writeString(
                java.nio.file.Path.of(System.getProperty("java.io.tmpdir"),"jphp-property-runtime-differences.txt"),
                String.join("\n",differences),StandardCharsets.UTF_8);
        assertEquals("Differences="+differences.size()+"\n"+String.join("\n",differences.subList(0,Math.min(differences.size(),50))),0,differences.size());
    }

    private String resource(String name) throws IOException {
        try (var input = getClass().getClassLoader().getResourceAsStream("resources/type_hinting/" + name)) {
            assertNotNull(name, input); return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test public void testNativePropertyDeclarations() throws Throwable {
        String rows;
        try (var input = getClass().getClassLoader().getResourceAsStream("resources/type_hinting/php85_property_declarations.tsv")) {
            assertNotNull(input); rows = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        var differences = new ArrayList<String>();
        for (String row : rows.split("\\R")) {
            String[] fields = row.split("\t", -1);
            Environment env = new Environment(newScope(), new ByteArrayOutputStream());
            try {
                Context context = new Context(new String(Base64.getDecoder().decode(fields[4]), StandardCharsets.UTF_8)+";$GLOBALS['declarations_after']=1;");
                context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
                try {
                    var module = new JvmCompiler(env, context).compile(false);
                    var expected = declarations(module);
                    var dumper = new ModuleDumper(context, env, true); var bytes = new ByteArrayOutputStream();
                    dumper.save(module, bytes); module = dumper.load(new ByteArrayInputStream(bytes.toByteArray()));
                    assertEquals(fields[0], expected, declarations(module));
                    env.getScope().setPhpLanguageProfile(PhpLanguageProfile.JPHP_LEGACY);
                    env.getScope().loadModule(module); env.getScope().addUserModule(module); env.registerModule(module);
                    module.include(env);
                    if (fields[1].equals("reject")) {differences.add(fields[0] + ": unexpectedly accepted"); continue;}
                    assertEquals(fields[0],1,env.getGlobals().getByScalar("declarations_after").toLong());
                    for(var template:module.getDeferredClasses())
                        assertNotNull(fields[0]+": "+template.getName(),env.fetchClass(template.getName(),false,PhpLanguageProfile.PHP_8_5));
                    var diagnostics = new StringWriter(); var writer = new PrintWriter(diagnostics);
                    CheckClassAdapter.verify(new ClassReader(module.getData()), env.getScope().getClassLoader(), false, writer);
                    for (var clazz : module.getClasses()) if (clazz.getData() != null && clazz.getData().length != 0)
                        CheckClassAdapter.verify(new ClassReader(clazz.getData()), env.getScope().getClassLoader(), false, writer);
                    assertEquals(fields[0], "", diagnostics.toString());
                } catch (ErrorException error) {
                    assertNull(fields[0],env.getGlobals().getByScalar("declarations_after"));
                    String expected = new String(Base64.getDecoder().decode(fields[3]), StandardCharsets.UTF_8);
                    if (fields[1].equals("accept") || error.getType().value != Integer.parseInt(fields[2]) || !expected.equals(error.getMessage()))
                        differences.add(fields[0] + ": expected=" + fields[2] + ":" + expected + " actual=" + error.getType() + ":" + error.getMessage());
                }
            } finally {env.doFinal();}
        }
        assertEquals("Differences=" + differences.size() + "\n" + String.join("\n", differences), 0, differences.size());
    }

    private static List<String> declarations(ModuleEntity module) {
        var result = new ArrayList<String>();
        var catalog = new ArrayList<>(module.getClasses()); catalog.addAll(module.getDeferredClasses());
        for (var clazz : catalog) {
            var properties = new ArrayList<>(clazz.getProperties()); properties.addAll(clazz.getStaticProperties());
            for (PropertyEntity property : properties) {
                var value = property.getDefaultValue();
                result.add(clazz.getName() + ":" + property.getName() + ":" + property.isStatic() + ":" + property.isFinal()
                        + ":" + (property.getDeclaredType() == null ? null : property.getDeclaredType().getSignature())
                        + ":" + property.isDefault() + ":" + (value == null ? "deferred" : value.isUndefined() + ":" + value.getRealType() + ":" + value));
            }
        }
        return result;
    }
}
