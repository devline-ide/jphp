package org.develnext.jphp.core.compiler.jvm;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import static org.junit.Assert.*;

/** Captured native fixtures load from module resources, including when resources are in a JAR. */
final class PhpNativeTestResources {
    private static final String ROOT="resources/native-oracle/";
    static byte[] read(String name) throws IOException {
        try(var input=PhpNativeTestResources.class.getClassLoader().getResourceAsStream(ROOT+name)) {
            assertNotNull("Missing captured native resource "+name,input);return input.readAllBytes();
        }
    }
    static String text(String name) throws IOException {return new String(read(name),StandardCharsets.UTF_8);}
    static Fixture fixture(String group,String entry,String... siblings) throws IOException {
        var directory=Files.createTempDirectory("jphp-native-owned-fixture-");
        var fixture=new Fixture(directory,entry);
        try {
            for(String name:siblings) {
                if(!name.equals(Path.of(name).getFileName().toString())||name.equals(".")||name.equals(".."))throw new IllegalArgumentException("Fixture must be a sibling filename");
                Path target=directory.resolve(name);if(fixture.files.contains(target))continue;
                fixture.files.add(target);Files.write(target,read(group+"/"+name));
            }
            if(!Files.isRegularFile(fixture.entry))throw new IOException("Fixture entry not included: "+entry);
            return fixture;
        }catch(IOException|RuntimeException|Error failure) {
            try{fixture.close();}catch(IOException cleanup){failure.addSuppressed(cleanup);}throw failure;
        }
    }
    /** Relative source trees preserve real ../native/helper.php and declaration sibling includes. */
    static Fixture treeFixture(String group,String entry,String... names)throws IOException {
        var directory=Files.createTempDirectory("jphp-native-owned-fixture-");
        var fixture=new Fixture(directory,entry);
        try {
            for(String name:names) {
                Path relative=Path.of(name);
                if(relative.isAbsolute()||relative.getNameCount()==0||name.contains("\\")
                        ||!relative.normalize().equals(relative)||name.equals(".")||name.equals(".."))
                    throw new IllegalArgumentException("Fixture tree needs confined relative source names");
                Path target=directory.resolve(relative).normalize();
                if(!target.startsWith(directory))throw new IllegalArgumentException("Fixture tree escapes its owner");
                if(fixture.files.contains(target))continue;
                var parents=new ArrayDeque<Path>();
                for(Path parent=target.getParent();!parent.equals(directory);parent=parent.getParent())parents.push(parent);
                while(!parents.isEmpty()) {
                    Path parent=parents.pop();
                    if(!Files.exists(parent)){Files.createDirectory(parent);fixture.directories.add(parent);}
                }
                fixture.files.add(target);Files.write(target,read(group+"/"+name));
            }
            if(!fixture.files.contains(fixture.entry)||!Files.isRegularFile(fixture.entry))
                throw new IOException("Fixture entry not included: "+entry);
            return fixture;
        }catch(IOException|RuntimeException|Error failure) {
            try{fixture.close();}catch(IOException cleanup){failure.addSuppressed(cleanup);}throw failure;
        }
    }
    static String capturedDiagnosticContext(String group,String name)throws IOException {
        Properties contexts=new Properties();contexts.load(new StringReader(text(group+"/diagnostic-contexts.properties")));
        String result=contexts.getProperty(name);
        if(result==null)throw new IOException("Missing captured diagnostic source "+group+"/"+name);
        return result;
    }
    static byte[] remapNoDiscardDiagnostics(byte[] output,Fixture fixture,String group)throws IOException {
        return remapDiagnostics(output,fixture,group+"/diagnostic-contexts.properties",true);
    }
    /** Only captured caller filenames in serialized error records are relocatable identities. */
    static byte[] remapCallerDiagnostics(byte[] output,Fixture fixture,String group) throws IOException {
        return remapDiagnostics(output,fixture,"attributes/"+group+"/diagnostic-contexts.properties",false);
    }
    private static byte[] remapDiagnostics(byte[] output,Fixture fixture,String contexts,boolean closureNames)throws IOException {
        Properties names=new Properties();
        names.load(new StringReader(text(contexts)));
        String actual=new String(output,StandardCharsets.US_ASCII);
        StringBuilder remapped=new StringBuilder();
        for(String line:actual.split("\n",-1)) {
            if(remapped.length()!=0)remapped.append('\n');
            int equals=line.indexOf('=');
            boolean closureWarning=equals>=0&&closureNames&&line.substring(0,equals).equals("warning");
            boolean handledWarning=equals>=0&&closureNames&&line.substring(0,equals).equals("handled");
            if(equals<0 || !line.substring(0,equals).endsWith("-error")&&!closureWarning&&!handledWarning) {remapped.append(line);continue;}
            byte[] data=HexFormat.of().parseHex(line.substring(equals+1));
            var bytes=new ByteArrayOutputStream();
            for(int at=0;at<data.length;) {
                if(at+3<data.length && data[at]=='s' && data[at+1]==':' && data[at+2]>='0' && data[at+2]<='9') {
                    int end=at+2;while(end<data.length && data[end]>='0' && data[end]<='9')end++;
                    if(end+1>=data.length || data[end]!=':' || data[end+1]!='"')throw new IOException("Malformed captured serialized string");
                    int length=Integer.parseInt(new String(data,at+2,end-at-2,StandardCharsets.US_ASCII));
                    int start=end+2,tail=Math.addExact(start,length);
                    if(tail+1>=data.length || data[tail]!='"' || data[tail+1]!=';')throw new IOException("Invalid captured serialized string boundary");
                    String value=new String(data,start,length,StandardCharsets.UTF_8);
                    for(String name:names.stringPropertyNames()) {
                        if(!fixture.contains(name))continue;
                        value=value.replace("called in "+fixture.sibling(name)+" on line ","called in "+names.getProperty(name)+" on line ");
                        if(closureNames&&value.startsWith("Too few arguments to function ")) {
                            value=value.replace(" passed in "+fixture.sibling(name)+" on line "," passed in "+names.getProperty(name)+" on line ");
                        }
                        if(handledWarning&&at>=4&&data[at-4]=='i'&&data[at-3]==':'&&data[at-2]=='2'&&data[at-1]==';'
                                &&value.equals(fixture.sibling(name).toString()))value=names.getProperty(name);
                        if(closureWarning) {
                            String prefix="The return value of function {closure:"+fixture.sibling(name)+":";
                            if(value.startsWith(prefix))value="The return value of function {closure:"+names.getProperty(name)+":"+value.substring(prefix.length());
                        }
                    }
                    byte[] mapped=value.getBytes(StandardCharsets.UTF_8);
                    bytes.write(("s:"+mapped.length+":\"").getBytes(StandardCharsets.US_ASCII));bytes.write(mapped);bytes.write('"');bytes.write(';');at=tail+2;
                }else bytes.write(data[at++]);
            }
            remapped.append(line,0,equals+1).append(HexFormat.of().formatHex(bytes.toByteArray()));
        }
        return remapped.toString().getBytes(StandardCharsets.US_ASCII);
    }
    static final class Fixture implements AutoCloseable {
        private final Path directory,entry;
        private final List<Path> files=new ArrayList<>();
        private final List<Path> directories=new ArrayList<>();
        private Fixture(Path directory,String name) {this.directory=directory;this.entry=directory.resolve(name);}
        File file() {return entry.toFile();}
        boolean contains(String name) {return files.contains(directory.resolve(name));}
        Path sibling(String name) {return directory.resolve(name);}
        @Override public void close() throws IOException {
            IOException failure=null;
            for(Path file:files)try{Files.deleteIfExists(file);}catch(IOException error){if(failure==null)failure=error;else failure.addSuppressed(error);}
            for(int i=directories.size()-1;i>=0;i--)try{Files.deleteIfExists(directories.get(i));}catch(IOException error){if(failure==null)failure=error;else failure.addSuppressed(error);}
            try{Files.deleteIfExists(directory);}catch(IOException error){if(failure==null)failure=error;else failure.addSuppressed(error);}
            if(failure!=null)throw failure;
        }
    }
}
