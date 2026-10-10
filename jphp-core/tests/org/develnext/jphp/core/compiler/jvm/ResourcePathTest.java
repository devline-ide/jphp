package org.develnext.jphp.core.compiler.jvm;

import org.junit.Assert;
import org.junit.Test;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;
import php.runtime.env.Context;
import php.runtime.env.Environment;
import php.runtime.env.TraceInfo;
import php.runtime.Memory;
import php.runtime.loader.dump.ModuleDumper;
import php.runtime.reflection.ModuleEntity;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.io.File;

public class ResourcePathTest extends JvmCompilerCase {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void moduleSourceNameSurvivesDumpWithoutChangingDefault() throws Throwable {
        File source = temporary.newFile("Reader.php");
        File bytecode = temporary.newFile("Reader.phb");
        Files.writeString(source.toPath(), "<?php return __FILE__ . '|' . __DIR__;", StandardCharsets.UTF_8);
        String path = "'" + source.getPath().replace("\\", "/").replace("'", "\\'") + "'";
        String dumped = "'" + bytecode.getPath().replace("\\", "/").replace("'", "\\'") + "'";
        String expected = "res://app/behaviours/Reader.php|res://app/behaviours";
        Assert.assertEquals(expected, evaluate("$m = new \\php\\lang\\Module(" + path + ", false, true, 'res://app/behaviours/Reader.php'); return $m->call();"));
        evaluate("$m = new \\php\\lang\\Module(" + path + ", false, true, 'res://app/behaviours/Reader.php'); $m->dump(" + dumped + ");");
        Assert.assertEquals(expected, evaluate("$b = new \\php\\lang\\Module(" + dumped + ", true); return $b->call();"));
        String original = evaluate("$m = new \\php\\lang\\Module(" + path + ", false, true); return $m->call();");
        Assert.assertFalse(original.contains("res://"));
        Assert.assertEquals(original, evaluate("$m = new \\php\\lang\\Module(" + path + ", false, true, null); return $m->call();"));
    }

    private String evaluate(String code) throws Throwable {
        Environment env = new Environment(newScope());
        ModuleEntity module = new JvmCompiler(env, new Context(code)).compile(false);
        env.getScope().loadModule(module);
        env.registerModule(module);
        return module.include(env).toString();
    }

    @Test
    public void resourcePhpIncludesResolveBytecodeWithOneIncludedIdentity() throws Throwable {
        File resources = temporary.newFolder("resources");
        File bytecode = new File(resources, "Dependency.phb");
        Environment compiler = new Environment(newScope());
        Context context = new Context(new ByteArrayInputStream("<?php $counter++; return 'compiled';".getBytes(StandardCharsets.UTF_8)), "res://Dependency.php", StandardCharsets.UTF_8);
        ModuleEntity module = new JvmCompiler(compiler, context).compile(false);
        try (java.io.OutputStream out = Files.newOutputStream(bytecode.toPath())) { new ModuleDumper(context, compiler, true).save(module, out); }
        for (boolean compiledFirst : new boolean[]{false, true}) {
            Environment env = new Environment(newScope());
            env.getScope().getClassLoader().addLibrary(resources.toURI().toURL());
            env.getGlobals().put("counter", Memory.CONST_INT_0);
            String first = compiledFirst ? "res://Dependency.phb" : "res://Dependency.php";
            String second = compiledFirst ? "res://Dependency.php" : "res://Dependency.phb";
            Assert.assertEquals("compiled", env.__requireOnce(first, env.getGlobals(), TraceInfo.UNKNOWN).toString());
            Assert.assertSame(Memory.TRUE, env.__requireOnce(second, env.getGlobals(), TraceInfo.UNKNOWN));
            Assert.assertSame(Memory.TRUE, env.__requireOnce("res://nested/.././Dependency.php", env.getGlobals(), TraceInfo.UNKNOWN));
            Assert.assertSame(Memory.TRUE, env.__requireOnce("res://nested/../Dependency.phb", env.getGlobals(), TraceInfo.UNKNOWN));
            Assert.assertSame(env.getModuleManager().fetchModule(first), env.getModuleManager().fetchModule(second));
            Assert.assertSame(env.getModuleManager().findModule(first), env.getModuleManager().findModule(second));
            Assert.assertTrue(env.getModuleManager().hasModule(first) && env.getModuleManager().hasModule(second));
            Assert.assertEquals(1, env.getGlobals().valueOfIndex("counter").toLong());
            Assert.assertEquals(1, env.getModuleManager().getIncludedPaths().size());
            Assert.assertEquals("compiled", env.__include("res://Dependency.php").toString());
            Assert.assertEquals(2, env.getGlobals().valueOfIndex("counter").toLong());
            Assert.assertEquals(1, env.getModuleManager().getIncludedPaths().size());
        }
        Files.writeString(new File(resources, "Dependency.php").toPath(), "<?php return 'source';", StandardCharsets.UTF_8);
        Environment source = new Environment(newScope());
        source.getScope().getClassLoader().addLibrary(resources.toURI().toURL());
        Assert.assertEquals("source", source.__requireOnce("res://Dependency.php", source.getGlobals(), TraceInfo.UNKNOWN).toString());
        Assert.assertSame(Memory.TRUE, source.__requireOnce("res://nested/.././Dependency.php", source.getGlobals(), TraceInfo.UNKNOWN));
    }

    @Test
    public void resourceDirectorySurvivesCompilationAndBytecode() throws Exception {
        for (String source : new String[]{"res://package/php/Reader.php", "res://Reader.php"}) {
            String expected = source.equals("res://Reader.php") ? "res://" : "res://package/php";
            for (boolean bytecode : new boolean[]{false, true}) {
                Environment env = new Environment(newScope());
                Context context = new Context(new ByteArrayInputStream("<?php return __DIR__;".getBytes(StandardCharsets.UTF_8)), source, StandardCharsets.UTF_8);
                ModuleEntity module = new JvmCompiler(env, context, getSyntax(context)).compile();
                if (bytecode) {
                    ModuleDumper dumper = new ModuleDumper(context, env, true);
                    ByteArrayOutputStream output = new ByteArrayOutputStream();
                    dumper.save(module, output);
                    module = dumper.load(new ByteArrayInputStream(output.toByteArray()));
                }
                env.getScope().loadModule(module);
                env.registerModule(module);
                Assert.assertEquals(expected, module.includeNoThrow(env).toString());
            }
        }
    }
}
