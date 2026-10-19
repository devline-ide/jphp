package php.runtime.loader.dump;
import java.io.*;
import java.util.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import php.runtime.env.Context;
import php.runtime.loader.dump.io.*;
import php.runtime.reflection.*;
import php.runtime.reflection.support.*;

/** ABI20 typed declaration sidecar. Decoding/physical inspection never loads a PHP/Java provider. */
public final class PhpAttributeDumper {
    public static final int MAX_BYTES=16*1024*1024;
    private static final int STAMP=0x41544d44,VERSION=1,MAX_ENTRIES=65535;
    private static final String DESCRIPTOR="(Lphp/runtime/env/Environment;)Lphp/runtime/Memory;";
    private static final String CERTIFICATE="Lphp/runtime/annotation/PhpAttributeInitializer;";
    private record Declaration(Entity entity,int target,String physical,String scope,String logicalName) {}
    private record Catalog(Map<String,Declaration> declarations,Map<String,byte[]> bytes) {}
    private PhpAttributeDumper() {}
    public static String site(int target,String declaration,String attribute,int occurrence,int argument) {
        return target+"\u0000"+declaration+"\u0000"+attribute+"\u0000"+occurrence+"\u0000"+argument;
    }
    private static void add(Map<String,Declaration> entries,String key,Entity entity,int target,String physical,String scope) throws IOException {
        String logicalName=entity instanceof php.runtime.reflection.helper.ClosureEntity || target==PhpAttribute.FUNCTION && entity instanceof MethodEntity
                ? null : entity.getName();
        Declaration old=entries.putIfAbsent(key,new Declaration(entity,target,physical,scope,logicalName));
        if(old!=null && old.entity()!=entity) throw new DumpException("Duplicate attribute declaration identity");
        if(entries.size()>MAX_ENTRIES) throw new DumpException("Too many attribute declarations");
    }
    private static void parameters(Map<String,Declaration> entries,String owner,ParameterEntity[] parameters,String physical,String scope,String callable) throws IOException {
        if(parameters==null) return;
        for(int i=0;i<parameters.length;i++) {
            String key=owner+"/P"+i;add(entries,key,parameters[i],PhpAttribute.PARAMETER,physical,scope);
            entries.put(key,new Declaration(parameters[i],PhpAttribute.PARAMETER,physical,scope,callable+"/P"+i));
        }
    }
    private static void klass(Map<String,Declaration> entries,Map<String,byte[]> bytes,String key,ClassEntity klass) throws IOException {
        String physical=klass.getCompiledInternalName().replace('/','.');bytes.put(physical,klass.getData());
        add(entries,key,klass,PhpAttribute.CLASS,physical,klass.getName());
        for(ConstantEntity constant:klass.getConstants()) if(constant.getClazz()==klass)
            add(entries,key+"/C/"+constant.getName(),constant,PhpAttribute.CLASS_CONSTANT,physical,klass.getName());
        for(PropertyEntity property:klass.getProperties()) if(property.getClazz()==klass)
            add(entries,key+"/P/"+property.getName(),property,PhpAttribute.PROPERTY,physical,klass.getName());
        for(PropertyEntity property:klass.getProperties()) if(property.getClazz()==klass && property.getPhpHooks()!=null) {
            for(var hook:property.getPhpHooks().declaredOrder()) {
                if(hook.declaration()!=property || hook.method().getClazz()!=klass)
                    throw new DumpException("Foreign owned hook attribute declaration");
                String member=key+"/H/"+property.getName()+"/"+hook.kind().name();
                add(entries,member,hook.method(),PhpAttribute.METHOD,physical,klass.getName());
                parameters(entries,member,hook.method().getParameters(),physical,klass.getName(),hook.method().getName());
            }
        }
        for(PropertyEntity property:klass.getStaticProperties()) if(property.getClazz()==klass)
            add(entries,key+"/S/"+property.getName(),property,PhpAttribute.PROPERTY,physical,klass.getName());
        Set<MethodEntity> seen=Collections.newSetFromMap(new IdentityHashMap<>());
        for(MethodEntity method:klass.getMethods().values()) if(method.getClazz()==klass && seen.add(method)) {
            String member=key+"/M/"+method.getName();
            add(entries,member,method,PhpAttribute.METHOD,physical,klass.getName());
            parameters(entries,member,method.getParameters(),physical,klass.getName(),method.getName());
        }
    }
    private static String lexicalScope(byte[] bytes,ModuleEntity module) throws IOException {
        if(bytes==null || bytes.length==0) return null;
        ClassNode node=new ClassNode();try {new ClassReader(bytes).accept(node,ClassReader.SKIP_CODE|ClassReader.SKIP_DEBUG|ClassReader.SKIP_FRAMES);}
        catch(RuntimeException failure) {throw new DumpException("Invalid closure attribute lexical carrier");}
        String scope=null;boolean seen=false;
        for(FieldNode field:node.fields) if(field.name.equals("__$attributeLexicalScope")) {
            if(seen || field.access!=(Opcodes.ACC_PUBLIC|Opcodes.ACC_STATIC|Opcodes.ACC_FINAL)
                    || !field.desc.equals("Ljava/lang/String;") || !(field.value instanceof String))
                throw new DumpException("Invalid physical closure attribute lexical scope");
            seen=true;scope=(String)field.value;
            if(scope.isEmpty()) throw new DumpException("Empty closure attribute lexical scope");
            boolean declared=false;
            for(ClassEntity klass:module.getClasses()) if(klass.getName().equals(scope))declared=true;
            for(ClassEntity klass:module.getDeferredClasses()) if(klass.getName().equals(scope))declared=true;
            if(!declared) throw new DumpException("Closure attribute scope lacks its module declaration");
        }
        return scope;
    }
    public static void validateDeclaration(ModuleEntity module,Entity entity,ClassEntity donor) throws IOException {
        Map<String,Declaration> declarations=new LinkedHashMap<>();Map<String,byte[]> physical=new HashMap<>();
        if(donor.getModule()!=module) throw new DumpException("Attribute donor belongs to a different module");
        klass(declarations,physical,"C/"+donor.getName(),donor);Catalog catalog=new Catalog(declarations,physical);Declaration selected=null;
        for(Declaration declaration:catalog.declarations().values()) if(declaration.entity()==entity) {selected=declaration;break;}
        if(selected==null) throw new DumpException("Attribute helper donor is not a module declaration");
        verify(catalog,module,selected,entity.getPhpAttributes());
    }
    /** Exact declaration/occurrence/argument identity plus the cold inspected physical helper. */
    public static final class ArgumentSource {
        private final ModuleEntity module;private final Entity declaration;private final PhpAttribute attribute;
        private final PhpAttribute.Argument argument;private final byte[] bytes;private final java.lang.reflect.Method member;
        private ArgumentSource(ModuleEntity module,Entity declaration,PhpAttribute attribute,PhpAttribute.Argument argument,byte[] bytes,java.lang.reflect.Method member){this.module=module;this.declaration=declaration;this.attribute=attribute;this.argument=argument;this.bytes=bytes.clone();this.member=member;}
        public ModuleEntity module(){return module;}public PhpAttribute.Argument argument(){return argument;}
        public java.lang.reflect.Method member(){return member;}public byte[] bytes(){return bytes.clone();}
        public void validate(php.runtime.env.Environment env){
            try {
                ArgumentSource actual=resolveArgument(env,module,declaration,attribute,argument);
                if(!member.equals(actual.member)||!Arrays.equals(bytes,actual.bytes))throw new IllegalArgumentException("Attribute argument source changed after admission");
            }catch(IOException failure){throw new IllegalArgumentException("Attribute argument source lost its declaration proof",failure);}
        }
    }
    public static ArgumentSource resolveArgument(php.runtime.env.Environment env,ModuleEntity module,Entity entity,PhpAttribute attribute,PhpAttribute.Argument argument)throws IOException {
        if(module==null||env.getScope().getClassLoader().getModule(module.getInternalName())!=module||attribute==null||argument==null||argument.initializer()==null)
            throw new DumpException("Attribute argument needs its exact loaded source module");
        boolean occurrence=false,argumentIdentity=false;
        for(PhpAttribute candidate:entity.getPhpAttributes())if(candidate==attribute)occurrence=true;
        for(PhpAttribute.Argument candidate:attribute.arguments())if(candidate==argument)argumentIdentity=true;
        if(!occurrence||!argumentIdentity)throw new DumpException("Attribute argument is not its declaration occurrence");
        Catalog catalog=catalog(module);Declaration selected=null;
        for(Declaration declaration:catalog.declarations().values())if(declaration.entity()==entity){selected=declaration;break;}
        if(selected==null)throw new DumpException("Attribute argument declaration is not its source module member");
        verify(catalog,module,selected,entity.getPhpAttributes());
        byte[] bytes=catalog.bytes().get(argument.initializer().javaClass());
        try {
            Class<?> owner=env.getScope().getClassLoader().loadClass(argument.initializer().javaClass());
            var member=owner.getDeclaredMethod(argument.initializer().method(),php.runtime.env.Environment.class);
            if(!env.getScope().getClassLoader().matchesDefinedBytecode(owner,bytes))throw new DumpException("Attribute argument differs from actual defined class bytes");
            return new ArgumentSource(module,entity,attribute,argument,bytes,member);
        }catch(ReflectiveOperationException failure){throw new DumpException("Attribute argument physical member is unavailable");}
    }
    private static Catalog catalog(ModuleEntity module) throws IOException {
        Map<String,Declaration> entries=new LinkedHashMap<>();Map<String,byte[]> bytes=new HashMap<>();
        bytes.put(module.getInternalName().replace('/','.'),module.getData());
        for(ConstantEntity constant:module.getConstants()) add(entries,"G/"+constant.getName(),constant,PhpAttribute.CONSTANT,module.getInternalName().replace('/','.'),null);
        for(ConstantEntity constant:module.getDeferredGlobalConstantDeclarations())
            add(entries,"G/"+constant.getName(),constant,PhpAttribute.CONSTANT,module.getInternalName().replace('/','.'),null);
        for(FunctionEntity function:module.getFunctions()) {
            String physical=function.getInternalName().replace('/','.');bytes.put(physical,function.getData());
            String key="F/"+physical;add(entries,key,function,PhpAttribute.FUNCTION,physical,null);
            parameters(entries,key,function.getParameters(),physical,null,function.getName());
        }
        int ordinal=0;
        for(var closure:module.getClosures()) {
            String physical=closure.getCompiledInternalName().replace('/','.');bytes.put(physical,closure.getData());
            String key="L/"+ordinal++,scope=lexicalScope(closure.getData(),module);
            add(entries,key,closure,PhpAttribute.FUNCTION,physical,scope);
            add(entries,key+"/I",closure.methodMagicInvoke,PhpAttribute.FUNCTION,physical,scope);
            parameters(entries,key,closure.parameters,physical,scope,"__invoke");
            parameters(entries,key+"/I",closure.methodMagicInvoke.getParameters(),physical,scope,"__invoke");
        }
        ordinal=0;for(var generator:module.getGenerators()) klass(entries,bytes,"Y/"+ordinal++,generator);
        for(ClassEntity klass:module.getClasses()) klass(entries,bytes,"C/"+klass.getName(),klass);
        ordinal=0;for(ClassEntity klass:module.getDeferredClasses()) klass(entries,bytes,"D/"+ordinal++,klass);
        return new Catalog(entries,bytes);
    }
    private static int count(DumpInputStream in) throws IOException {
        int count=in.readInt();if(count<0 || count>MAX_ENTRIES) throw new DumpException("Invalid attribute metadata count");return count;
    }
    private static String name(DumpInputStream in,boolean nullable) throws IOException {
        String name=in.readName();if(name==null && !nullable || name!=null && (name.isEmpty() || name.length()>8192))
            throw new DumpException("Invalid attribute metadata name");return name;
    }
    private static void verify(Catalog catalog,ModuleEntity module,Declaration declaration,List<PhpAttribute> attributes) throws IOException {
        int occurrence=0;
        Map<String,ClassNode> parsed=new HashMap<>();
        for(PhpAttribute attribute:attributes) {
            if(attribute.target()!=declaration.target()) throw new DumpException("Attribute target does not match its declaration");
            if(attribute.name().equalsIgnoreCase("AllowDynamicProperties")) {
                String rejection=PhpAllowDynamicProperties.declarationRejection(declaration.entity() instanceof ClassEntity owner?owner:null,attribute.target(),attributes);
                if(rejection!=null)throw new DumpException(rejection);
            }
            if(attribute.name().equalsIgnoreCase("NoDiscard")) {
                String rejection=php.runtime.reflection.support.PhpNoDiscardValidation.rejection(declaration.entity(),attribute.target(),attributes);
                if(rejection!=null)throw new DumpException(rejection);
            }
            int argumentIndex=0;Set<String> keys=new HashSet<>();boolean named=false;
            for(PhpAttribute.Argument argument:attribute.arguments()) {
                if(argument.name()!=null) {named=true;if(!keys.add(argument.name())) throw new DumpException("Duplicate attribute argument key");}
                else if(named) throw new DumpException("Positional attribute argument follows named argument");
                if(argument.nativeInteger()!=null) throw new DumpException("Native declaration arguments cannot enter source attribute metadata");
                PhpAttribute.Initializer initializer=argument.initializer();
                if(!initializer.moduleName().equals(module.getInternalName()) || !initializer.javaClass().equals(declaration.physical())
                        || !Objects.equals(initializer.declarationScope(),declaration.scope()))
                    throw new DumpException("Foreign attribute argument initializer");
                byte[] physical=catalog.bytes().get(initializer.javaClass());
                if(physical==null || physical.length==0) throw new DumpException("Executable attribute metadata requires physical initializer bytes");
                ClassNode clazz=parsed.get(initializer.javaClass());
                if(clazz==null) {
                    clazz=new ClassNode();try {new ClassReader(physical).accept(clazz,ClassReader.SKIP_DEBUG|ClassReader.SKIP_FRAMES);}
                    catch(RuntimeException bad) {throw new DumpException("Invalid physical attribute initializer class");}
                    if(!clazz.name.replace('/','.').equals(initializer.javaClass())) throw new DumpException("Attribute physical class identity mismatch");
                    parsed.put(initializer.javaClass(),clazz);
                }
                MethodNode selected=null;
                for(MethodNode method:clazz.methods) if(method.name.equals(initializer.method())) {
                    if(selected!=null) throw new DumpException("Ambiguous physical attribute initializer");selected=method;
                }
                if(selected==null || !selected.desc.equals(DESCRIPTOR) || selected.access!=(Opcodes.ACC_PUBLIC|Opcodes.ACC_STATIC))
                    throw new DumpException("Missing or invalid physical attribute argument initializer");
                String logicalName=declaration.logicalName();
                String expected=site(attribute.target(),logicalName,attribute.name(),occurrence,argumentIndex++);
                String actual=null;
                if(selected.invisibleAnnotations!=null) for(AnnotationNode annotation:selected.invisibleAnnotations) if(annotation.desc.equals(CERTIFICATE)) {
                    if(actual!=null || annotation.values==null || annotation.values.size()!=2 || !annotation.values.get(0).equals("site")
                            || !(annotation.values.get(1) instanceof String)) throw new DumpException("Invalid attribute initializer certificate");
                    actual=(String)annotation.values.get(1);
                }
                if(!expected.equals(actual)) throw new DumpException("Attribute initializer belongs to a different declaration occurrence");
            }
            occurrence++;
        }
    }
    public static byte[] save(ModuleEntity module,boolean includeData) throws IOException {
        Catalog catalog=catalog(module);
        List<Map.Entry<String,Declaration>> attributed=catalog.declarations().entrySet().stream().filter(e->!e.getValue().entity().getPhpAttributes().isEmpty()).toList();
        if(!includeData && attributed.stream().anyMatch(e->e.getValue().entity().getPhpAttributes().stream().anyMatch(a->!a.arguments().isEmpty())))
            throw new DumpException("Executable attribute arguments cannot be saved without bytecode");
        var bytes=new ByteArrayOutputStream();var out=new DumpOutputStream(bytes,true);
        out.writeInt(STAMP);out.writeInt(VERSION);out.writeName(module.getInternalName());out.writeInt(attributed.size());
        for(var entry:attributed) {
            List<PhpAttribute> attributes=entry.getValue().entity().getPhpAttributes();verify(catalog,module,entry.getValue(),attributes);
            out.writeName(entry.getKey());out.writeInt(attributes.size());
            for(PhpAttribute attribute:attributes) {
                out.writeName(attribute.name());out.writeTrace(attribute.trace());out.writeInt(attribute.target());out.writeBoolean(attribute.strictTypes());
                out.writeInt(attribute.arguments().size());
                for(PhpAttribute.Argument argument:attribute.arguments()) {
                    out.writeName(argument.name());var init=argument.initializer();
                    out.writeName(init.moduleName());out.writeName(init.javaClass());out.writeName(init.method());out.writeName(init.declarationScope());
                }
            }
        }
        if(bytes.size()>MAX_BYTES) throw new DumpException("Attribute metadata is too large");return bytes.toByteArray();
    }
    public static void restore(ModuleEntity module,byte[] frame,Context context) throws IOException {
        Catalog catalog=catalog(module);var in=new DumpInputStream(new ByteArrayInputStream(frame),true);
        if(in.readInt()!=STAMP || in.readInt()!=VERSION || !module.getInternalName().equals(name(in,false)))
            throw new DumpException("Invalid attribute metadata frame");
        int declarationCount=count(in);Map<Declaration,List<PhpAttribute>> decoded=new LinkedHashMap<>();
        Set<String> identities=new HashSet<>();
        for(int i=0;i<declarationCount;i++) {
            String identity=name(in,false);Declaration declaration=catalog.declarations().get(identity);
            if(declaration==null || !identities.add(identity)) throw new DumpException("Invalid attribute declaration identity");
            int occurrenceCount=count(in);if(occurrenceCount==0) throw new DumpException("Empty attribute declaration record");
            List<PhpAttribute> attributes=new ArrayList<>();
            for(int j=0;j<occurrenceCount;j++) {
                String attributeName=name(in,false);var trace=in.readTrace(context);int target=in.readInt();
                trace=php.runtime.env.TraceInfo.valueOf(trace.getFileName(),trace.getStartLine(),trace.getStartPosition(),module.getInternalName());
                int strict=in.readUnsignedByte();if(strict>1) throw new DumpException("Invalid attribute source strictness");
                int argumentCount=count(in);List<PhpAttribute.Argument> arguments=new ArrayList<>();
                for(int k=0;k<argumentCount;k++) {
                    String key=name(in,true);
                    try {arguments.add(new PhpAttribute.Argument(key,new PhpAttribute.Initializer(name(in,false),name(in,false),name(in,false),name(in,true))));}
                    catch(IllegalArgumentException|NullPointerException bad) {throw new DumpException("Invalid attribute argument descriptor");}
                }
                try {attributes.add(new PhpAttribute(attributeName,trace,target,strict==1,arguments));}
                catch(IllegalArgumentException|NullPointerException bad) {throw new DumpException("Invalid attribute occurrence descriptor");}
            }
            verify(catalog,module,declaration,attributes);decoded.put(declaration,List.copyOf(attributes));
        }
        if(in.read()!=-1) throw new DumpException("Trailing attribute metadata data");
        // Publish only after all records and actual physical helpers pass without provider effects.
        for(var entry:decoded.entrySet()) entry.getKey().entity().setPhpAttributes(entry.getValue());
    }
}
