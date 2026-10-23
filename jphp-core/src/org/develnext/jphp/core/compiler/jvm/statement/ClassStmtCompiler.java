package org.develnext.jphp.core.compiler.jvm.statement;

import org.develnext.jphp.core.compiler.jvm.Constants;
import org.develnext.jphp.core.compiler.jvm.JPHPClassWriter;
import org.develnext.jphp.core.compiler.jvm.JvmCompiler;
import org.develnext.jphp.core.compiler.jvm.misc.LocalVariable;
import org.develnext.jphp.core.compiler.jvm.node.ClassNodeImpl;
import org.develnext.jphp.core.compiler.jvm.node.MethodNodeImpl;
import org.develnext.jphp.core.tokenizer.token.Token;
import org.develnext.jphp.core.tokenizer.token.expr.ValueExprToken;
import org.develnext.jphp.core.tokenizer.token.expr.value.FulledNameToken;
import org.develnext.jphp.core.tokenizer.token.expr.value.NameToken;
import org.develnext.jphp.core.tokenizer.token.expr.value.SelfExprToken;
import org.develnext.jphp.core.tokenizer.token.expr.value.StaticAccessExprToken;
import org.develnext.jphp.core.tokenizer.token.stmt.ClassStmtToken;
import org.develnext.jphp.core.tokenizer.token.stmt.ClassVarStmtToken;
import org.develnext.jphp.core.tokenizer.token.stmt.ConstStmtToken;
import org.develnext.jphp.core.tokenizer.token.stmt.MethodStmtToken;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;
import php.runtime.Memory;
import php.runtime.common.Function;
import php.runtime.common.Messages;
import php.runtime.common.PhpClassNames;
import php.runtime.env.Environment;
import php.runtime.env.TraceInfo;
import php.runtime.exceptions.CriticalException;
import php.runtime.exceptions.FatalException;
import php.runtime.exceptions.support.ErrorType;
import php.runtime.invoke.cache.*;
import php.runtime.lang.BaseObject;
import org.develnext.jphp.core.tokenizer.token.stmt.EnumCaseStmtToken;
import php.runtime.reflection.*;
import php.runtime.reflection.helper.GeneratorEntity;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.util.*;

import static org.objectweb.asm.Opcodes.*;

public class ClassStmtCompiler extends StmtCompiler<ClassEntity> {
    public void compilePhpAttributes(php.runtime.reflection.support.Entity declaration,
            org.develnext.jphp.core.tokenizer.token.stmt.StmtToken source,int target) {
        compilePhpAttributes(declaration,source,target,
                target==php.runtime.reflection.support.PhpAttribute.FUNCTION && isSystem ? functionName : declaration.getName());
    }
    public void compilePhpAttributes(php.runtime.reflection.support.Entity declaration,
            org.develnext.jphp.core.tokenizer.token.stmt.StmtToken source,int target,String logicalName) {
        if(source.getPhpAttributes().isEmpty()) return;
        var attributes=new ArrayList<php.runtime.reflection.support.PhpAttribute>();
        int occurrence=0;
        for(var attribute:source.getPhpAttributes()) {
            if(target==php.runtime.reflection.support.PhpAttribute.PARAMETER
                    && source instanceof org.develnext.jphp.core.tokenizer.token.stmt.ArgumentStmtToken parameter && parameter.getPromotionModifier()!=null
                    && attribute.getName().getName().equalsIgnoreCase("Override")) continue;
            if(target==php.runtime.reflection.support.PhpAttribute.CLASS && attribute.getName().getName().equalsIgnoreCase("Attribute")
                    && (entity.isAbstract() || entity.isInterface() || entity.isTrait() || entity.isEnum()))
                compiler.getEnvironment().error(attribute.toTraceInfo(compiler.getContext()),php.runtime.exceptions.support.ErrorType.E_COMPILE_ERROR,
                        "Only non-abstract classes can be declared as attribute classes");
            int argumentIndex=0;
            var arguments=new ArrayList<php.runtime.reflection.support.PhpAttribute.Argument>();
            for(var argument:attribute.getArguments()) {
                MethodNode initializer=new MethodNodeImpl();
                initializer.access=ACC_PUBLIC | ACC_STATIC;
                initializer.name="__$attributeArgument"+entity.nextMethodIndex();
                initializer.desc=Type.getMethodDescriptor(Type.getType(Memory.class),Type.getType(Environment.class));
                var certificate=new AnnotationNode(Type.getDescriptor(php.runtime.annotation.PhpAttributeInitializer.class));
                certificate.values=Arrays.asList("site",php.runtime.loader.dump.PhpAttributeDumper.site(target,
                        logicalName,
                        attribute.getName().getName(),occurrence,argumentIndex++));
                initializer.invisibleAnnotations=new ArrayList<>(List.of(certificate));
                MethodStmtCompiler method=new MethodStmtCompiler(this,initializer);
                method.setInitializer(true);
                String callableName="";
                if(target==php.runtime.reflection.support.PhpAttribute.PARAMETER)
                    callableName=logicalName.substring(0,logicalName.lastIndexOf("/P"));
                else if(target==php.runtime.reflection.support.PhpAttribute.METHOD)
                    callableName=logicalName;
                else if(target==php.runtime.reflection.support.PhpAttribute.FUNCTION)
                    callableName=logicalName==null ? "{closure}" : logicalName;
                method.setAttributeCallableName(callableName);
                ExpressionStmtCompiler expression=new ExpressionStmtCompiler(method,null);
                PhpConstantExpression.checkAttribute(argument.getExpression(),expression);
                method.writeHeader();
                LabelNode start=expression.makeLabel();method.addLocalVariable("~env",start,Environment.class);
                expression.writeExpression(argument.getExpression(),true,false,true);expression.writePopBoxing(true);
                initializer.instructions.add(new InsnNode(ARETURN));expression.stackPop();method.writeFooter();
                node.methods.add(initializer);
                arguments.add(new php.runtime.reflection.support.PhpAttribute.Argument(argument.getName(),
                        new php.runtime.reflection.support.PhpAttribute.Initializer(compiler.getModule().getInternalName(),
                                node.name.replace('/','.'),initializer.name,isSystem ? classContext==null ? null : classContext.getFulledName() : entity.getName())));
            }
            TraceInfo occurrenceTrace=attribute.toTraceInfo(compiler.getContext());
            occurrenceTrace=TraceInfo.valueOf(occurrenceTrace.getFileName(),occurrenceTrace.getStartLine(),occurrenceTrace.getStartPosition(),compiler.getModule().getInternalName());
            attributes.add(new php.runtime.reflection.support.PhpAttribute(attribute.getName().getName(),
                    occurrenceTrace,target,compiler.getModule().isStrictTypes(),arguments));
            occurrence++;
        }
        declaration.setPhpAttributes(attributes);
        php.runtime.reflection.support.PhpAllowDynamicProperties.validate(declaration,entity,compiler.getEnvironment());
        php.runtime.reflection.support.PhpNoDiscardValidation.validate(declaration,entity,compiler.getEnvironment());
        php.runtime.reflection.support.PhpDeprecatedValidation.validate(declaration,entity,compiler.getEnvironment());
    }
    private void importPhpAttributeHelpers(php.runtime.reflection.support.Entity source,
            php.runtime.reflection.support.Entity target,ClassEntity donor) {
        importPhpAttributeHelpers(source,target,donor,target.getName());
    }
    private void importPhpAttributeHelpers(php.runtime.reflection.support.Entity source,
            php.runtime.reflection.support.Entity target,ClassEntity donor,String logicalName) {
        if(source.getPhpAttributes().isEmpty()) return;
        try {php.runtime.loader.dump.PhpAttributeDumper.validateDeclaration(donor.getModule(),source,donor);}
        catch(java.io.IOException invalid) {throw new CriticalException(invalid);}
        var attributes=new ArrayList<php.runtime.reflection.support.PhpAttribute>();int occurrence=0;
        for(var attribute:source.getPhpAttributes()) {
            var arguments=new ArrayList<php.runtime.reflection.support.PhpAttribute.Argument>();int argumentIndex=0;
            for(var argument:attribute.arguments()) {
                var init=argument.initializer();
                if(!init.moduleName().equals(donor.getModule().getInternalName())
                        || !init.javaClass().replace('.','/').equals(donor.getCompiledInternalName().replace('.','/')))
                    throw new CriticalException("Attribute import lacks its exact physical trait donor");
                ClassNode physical=new ClassNode();new ClassReader(donor.getData()).accept(physical,0);
                MethodNode selected=null;for(MethodNode method:physical.methods) if(method.name.equals(init.method())) {
                    if(selected!=null) throw new CriticalException("Ambiguous trait attribute helper");selected=method;
                }
                if(selected==null) throw new CriticalException("Missing trait attribute helper");
                MethodNode copied=new MethodNodeImpl();copied.desc=selected.desc;copied.signature=selected.signature;
                copied.exceptions=new ArrayList<>(selected.exceptions);selected.accept(copied);
                copied.name="__$attributeArgument"+entity.nextMethodIndex();copied.access=ACC_PUBLIC|ACC_STATIC;
                if(copied.invisibleAnnotations==null) copied.invisibleAnnotations=new ArrayList<>();
                copied.invisibleAnnotations.removeIf(annotation->annotation.desc.equals(Type.getDescriptor(php.runtime.annotation.PhpAttributeInitializer.class)));
                var certificate=new AnnotationNode(Type.getDescriptor(php.runtime.annotation.PhpAttributeInitializer.class));
                certificate.values=Arrays.asList("site",php.runtime.loader.dump.PhpAttributeDumper.site(attribute.target(),logicalName,attribute.name(),occurrence,argumentIndex++));
                copied.invisibleAnnotations.add(certificate);node.methods.add(copied);
                arguments.add(new php.runtime.reflection.support.PhpAttribute.Argument(argument.name(),
                        new php.runtime.reflection.support.PhpAttribute.Initializer(compiler.getModule().getInternalName(),node.name.replace('/','.'),copied.name,entity.getName())));
            }
            attributes.add(new php.runtime.reflection.support.PhpAttribute(attribute.name(),attribute.trace(),attribute.target(),attribute.strictTypes(),arguments));occurrence++;
        }
        target.setPhpAttributes(attributes);
    }
    private static boolean compactLdcConstant(Object value) {
        return value instanceof String || value instanceof Integer || value instanceof Float
                || value instanceof Type type && (type.getSort()==Type.OBJECT || type.getSort()==Type.ARRAY);
    }

    private static void seedFrequentLdcConstants(ClassNode node,JPHPClassWriter writer) {
        int uses=0;
        outer: for(MethodNode method:node.methods) for(AbstractInsnNode instruction:method.instructions) {
            if(instruction instanceof LdcInsnNode ldc && compactLdcConstant(ldc.cst) && ++uses>255) break outer;
        }
        // LDC has only 255 narrow constant-pool slots; keep small classes allocation-free here.
        if(uses<=255) return;
        var counts=new LinkedHashMap<Object,Integer>();
        for(MethodNode method:node.methods) for(AbstractInsnNode instruction:method.instructions)
            if(instruction instanceof LdcInsnNode ldc && compactLdcConstant(ldc.cst)) counts.merge(ldc.cst,1,Integer::sum);
        var entries=new ArrayList<>(counts.entrySet());
        // Stable sort retains first physical occurrence order for equal frequencies.
        entries.sort((left,right)->Integer.compare(right.getValue(),left.getValue()));
        for(var entry:entries) writer.newConst(entry.getKey());
    }

    protected JPHPClassWriter cw;
    public final ClassNode node;
    public final ClassStmtToken statement;
    public final List<TraceInfo> traceList = new ArrayList<TraceInfo>();
    private Map<TraceInfo,TraitHookBodyCompiler.TraceOrigin> importedHookTraces;
    public final List<Memory> memoryConstants = new ArrayList<Memory>();
    public final List<Collection<Memory>> memoryArrayConstants = new ArrayList<Collection<Memory>>();

    private boolean external = false;
    private boolean isSystem = false;
    private boolean isInterfaceCheck = true;
    private String functionName = "";
    private String noDiscardClosureName;
    public void setNoDiscardClosureName(String name){noDiscardClosureName=name;}
    private String deprecatedClosureName;
    public void setDeprecatedClosureName(String name){deprecatedClosureName=name;}

    private boolean initDynamicExists = false;
    private int callFuncCount = 0;
    private int callClassCount = 0;
    private int callMethCount = 0;
    private int callConstCount = 0;
    private int callPropCount = 0;

    private GeneratorEntity generatorEntity;

    protected List<ConstStmtToken.Item> dynamicConstants = new ArrayList<ConstStmtToken.Item>();
    protected List<ClassVarStmtToken> dynamicProperties = new ArrayList<ClassVarStmtToken>();
    private ClassStmtToken classContext;
    private final Set<String> deferredTraitConstants = new HashSet<>();

    public ClassStmtCompiler(JvmCompiler compiler, ClassStmtToken statement) {
        super(compiler);
        this.statement = statement;
        this.node = new ClassNodeImpl();
    }

    public int getAndIncCallFuncCount() {
        return callFuncCount++;
    }

    public int getAndIncCallClassCount() {
        return callClassCount++;
    }

    public int getAndIncCallMethCount() {
        return callMethCount++;
    }

    public int getAndIncCallConstCount() {
        return callConstCount++;
    }

    public int getAndIncCallPropCount() {
        return callPropCount++;
    }

    public boolean isInitDynamicExists() {
        return initDynamicExists;
    }

    public boolean isClosure() {
        return functionName == null;
    }

    public String getFunctionName() {
        return functionName;
    }

    public void setFunctionName(String functionName) {
        this.functionName = functionName;
    }

    public GeneratorEntity getGeneratorEntity() {
        return generatorEntity;
    }

    public void setGeneratorEntity(GeneratorEntity generatorEntity) {
        this.generatorEntity = generatorEntity;
    }

    public boolean isSystem() {
        return isSystem;
    }

    public void setSystem(boolean system) {
        isSystem = system;
    }

    public void setInterfaceCheck(boolean check) {
        isInterfaceCheck = check;
    }

    public boolean isExternal() {
        return external;
    }

    public void setExternal(boolean external) {
        this.external = external;
    }

    TraceInfo makeTraceInfo(int line, int position) {
        return new TraceInfo(compiler.getContext(), line, 0, position, 0);
    }

    TraceInfo makeTraceInfo(Token token) {
        return token.toTraceInfo(compiler.getContext());
    }

    int addTraceInfo(int line, int position) {
        traceList.add(makeTraceInfo(line, position));
        return traceList.size() - 1;
    }

    int addTraceInfo(Token token) {
        traceList.add(makeTraceInfo(token));
        return traceList.size() - 1;
    }

    int addMemoryConstant(Memory memory) {
        memoryConstants.add(memory);
        return memoryConstants.size() - 1;
    }

    int addMemoryArray(Collection<Memory> memories) {
        memoryArrayConstants.add(memories);
        return memoryArrayConstants.size() - 1;
    }

    @SuppressWarnings("unchecked")
    protected void writeDestructor() {
        if (entity.methodDestruct != null) {
            MethodNode destructor = new MethodNodeImpl();
            destructor.name = "finalize";
            destructor.access = ACC_PUBLIC;
            destructor.desc = Type.getMethodDescriptor(Type.getType(void.class));

            MethodStmtCompiler methodCompiler = new MethodStmtCompiler(this, destructor);
            ExpressionStmtCompiler expressionCompiler = new ExpressionStmtCompiler(methodCompiler, null);
            methodCompiler.writeHeader();

            LabelNode l0 = writeLabel(destructor, statement.getMeta().getStartLine());
            methodCompiler.addLocalVariable("~this", l0);
            expressionCompiler.writeVarLoad("~this");
            expressionCompiler.writeSysStaticCall(php.runtime.invoke.PhpObjectLifecycle.class,
                    "enqueueJvmFinalizer", void.class, php.runtime.lang.IObject.class);

            destructor.instructions.add(new InsnNode(Opcodes.RETURN));
            methodCompiler.writeFooter();
            node.methods.add(destructor);
        }
    }

    protected void writeDefaultConstructors()
    {
        if (!isSystem && !isClosure() && entity.getParent() != null
                && entity.getParent().getNativeClass() != null
                && !BaseObject.class.isAssignableFrom(entity.getParent().getNativeClass())) {
            for (Constructor el : entity.getParent().getNativeClass().getConstructors()) {
                Class<?>[] parameterTypes = el.getParameterTypes();

                if (parameterTypes.length == 2
                        && parameterTypes[0] == Environment.class && parameterTypes[1] == ClassEntity.class) {
                    continue;
                }

                MethodNode constructor = new MethodNodeImpl();
                constructor.name       = Constants.INIT_METHOD;
                constructor.access     = el.getModifiers();
                constructor.exceptions = new ArrayList();

                MethodStmtCompiler methodCompiler = new MethodStmtCompiler(this, constructor);
                ExpressionStmtCompiler expressionCompiler = new ExpressionStmtCompiler(methodCompiler, null);

                LabelNode l0 = writeLabel(constructor, statement.getMeta().getStartLine());
                methodCompiler.addLocalVariable("~this", l0);

                Type[] argumentTypes = new Type[parameterTypes.length];

                int i = 0;
                for (Class type : parameterTypes) {
                    argumentTypes[i++] = Type.getType(type);

                    methodCompiler.addLocalVariable("arg" + i, l0, type);
                }

                constructor.desc = Type.getMethodDescriptor(
                        Type.getType(void.class), argumentTypes
                );

                methodCompiler.writeHeader();

                expressionCompiler.writeVarLoad("~this");
                for (i = 0; i < argumentTypes.length; i++) {
                    expressionCompiler.writeVarLoad("arg" + (i + 1));
                }

                constructor.instructions.add(new MethodInsnNode(
                        INVOKESPECIAL,
                        node.superName,
                        Constants.INIT_METHOD,
                        constructor.desc,
                        false
                ));

                methodCompiler.writeFooter();
                constructor.instructions.add(new InsnNode(RETURN));
                node.methods.add(constructor);
            }
        }
    }

    @SuppressWarnings("unchecked")
    protected void writeConstructor() {
        MethodNode constructor = new MethodNodeImpl();
        constructor.name = Constants.INIT_METHOD;
        constructor.access = ACC_PUBLIC;
        constructor.exceptions = new ArrayList();

        MethodStmtCompiler methodCompiler = new MethodStmtCompiler(this, constructor);
        methodCompiler.setInitializer(true);
        ExpressionStmtCompiler expressionCompiler = new ExpressionStmtCompiler(methodCompiler, null);
        methodCompiler.writeHeader();

        LabelNode l0 = writeLabel(constructor, statement.getMeta().getStartLine());
        methodCompiler.addLocalVariable("~this", l0);

        if (isClosure() || generatorEntity != null) {
            constructor.desc = Type.getMethodDescriptor(
                    Type.getType(void.class),
                    Type.getType(Environment.class),
                    Type.getType(ClassEntity.class),
                    Type.getType(Memory.class),
                    Type.getType(Memory[].class)
            );

            if (isClosure()) {
                constructor.desc = Type.getMethodDescriptor(
                        Type.getType(void.class),
                        Type.getType(Environment.class),
                        Type.getType(ClassEntity.class),
                        Type.getType(Memory.class),
                        Type.getType(String.class),
                        Type.getType(Memory[].class)
                );
            }

            methodCompiler.addLocalVariable("~env", l0, Environment.class);
            methodCompiler.addLocalVariable("~class", l0, ClassEntity.class);
            methodCompiler.addLocalVariable("~self", l0, Memory.class);

            if (isClosure()) {
                methodCompiler.addLocalVariable("~context", l0, String.class);
            }

            methodCompiler.addLocalVariable("~uses", l0, Memory[].class);

            methodCompiler.writeHeader();
            expressionCompiler.writeVarLoad("~this");

            expressionCompiler.writeVarLoad("~env");
            expressionCompiler.writeVarLoad("~class");
            expressionCompiler.writeVarLoad("~self");

            if (isClosure()) {
                expressionCompiler.writeVarLoad("~context");
            }

            expressionCompiler.writeVarLoad("~uses");
            constructor.instructions.add(new MethodInsnNode(
                    INVOKESPECIAL,
                    node.superName,
                    Constants.INIT_METHOD,
                    constructor.desc,
                    false
            ));
        } else {
            constructor.desc = Type.getMethodDescriptor(
                    Type.getType(void.class), Type.getType(Environment.class), Type.getType(ClassEntity.class)
            );
            methodCompiler.addLocalVariable("~env", l0, Environment.class);
            methodCompiler.addLocalVariable("~class", l0, String.class);

            expressionCompiler.writeVarLoad("~this");
            expressionCompiler.writeVarLoad("~env");
            expressionCompiler.writeVarLoad("~class");
            constructor.instructions.add(new MethodInsnNode(
                    INVOKESPECIAL,
                    node.superName,
                    Constants.INIT_METHOD,
                    constructor.desc,
                    false
            ));

            // PROPERTIES
            for (ClassVarStmtToken property : statement.getProperties()) {
                ExpressionStmtCompiler expressionStmtCompiler = new ExpressionStmtCompiler(methodCompiler, null);
                Memory value = property.getDeclaredType() == null ? Memory.NULL : Memory.UNDEFINED;
                boolean modern = compiler.getModule().getPhpLanguageProfile() == php.runtime.common.PhpLanguageProfile.PHP_8_5;
                if (property.getValue() != null) {
                    value = modern ? PhpConstantExpression.check(property.getValue(), expressionStmtCompiler)
                            : expressionStmtCompiler.writeExpression(property.getValue(), true, true, false);
                }

                PropertyEntity prop = new PropertyEntity(compiler.getContext());
                prop.setName(property.getVariable().getName());
                compilePhpAttributes(prop,property,php.runtime.reflection.support.PhpAttribute.PROPERTY);
                prop.setModifier(property.getModifier());
                prop.setStatic(property.isStatic());
                prop.setDefaultValue(value);
                prop.setDefault(property.getValue() != null);
                prop.setTrace(property.toTraceInfo(compiler.getContext()));
                prop.setDeclaredType(property.getDeclaredType());
                prop.setFinal(property.isFinal());
                prop.setPromoted(property.isPromoted());
                prop.setPhpReadonly(property.isPhpReadonly());
                prop.setPhpSetModifier(property.getPhpSetModifier());
                if(modern && prop.isPhpReadonly()) {
                    if(prop.getDeclaredType()==null) compiler.getEnvironment().error(prop.getTrace(),ErrorType.E_COMPILE_ERROR,"Readonly property %s::$%s must have type",entity.getName(),prop.getName());
                    if(prop.isDefault()) compiler.getEnvironment().error(prop.getTrace(),ErrorType.E_COMPILE_ERROR,"Readonly property %s::$%s cannot have default value",entity.getName(),prop.getName());
                    if(prop.isStatic()) compiler.getEnvironment().error(prop.getTrace(),ErrorType.E_COMPILE_ERROR,"Static property %s::$%s cannot be readonly",entity.getName(),prop.getName());
                }
                if (modern && property.isFinal() && prop.isPrivate())
                    compiler.getEnvironment().error(prop.getTrace(), ErrorType.E_COMPILE_ERROR,
                            "Private property %s::$%s cannot be final", entity.getName(), prop.getName());
                if (value != null && prop.getDeclaredType() != null && property.getValue() != null
                        && !(value instanceof php.runtime.memory.helper.ConstantMemory) && !(value instanceof php.runtime.memory.helper.ClassConstantMemory))
                    prop.setDefaultValue(prop.validateDefaultValue(compiler.getEnvironment(), value, entity, true));

                if (property.getDocComment() != null) {
                    prop.setDocComment(new DocumentComment(property.getDocComment().getComment()));
                }

                ClassEntity.PropertyResult result = entity.addProperty(prop);
                result.check(compiler.getEnvironment());

                if (value == null && property.getValue() != null) {
                    if (modern || property.getValue().isSingle() && ValueExprToken.isConstable(property.getValue().getSingle(), true))
                        dynamicProperties.add(property);
                    else
                        compiler.getEnvironment().error(
                                property.getVariable().toTraceInfo(compiler.getContext()),
                                ErrorType.E_COMPILE_ERROR,
                                Messages.ERR_EXPECTED_CONST_VALUE.fetch(entity.getName() + "::$" + property.getVariable().getName())
                        );
                }
            }
        }

        methodCompiler.writeFooter();
        constructor.instructions.add(new InsnNode(RETURN));
        node.methods.add(constructor);
    }

    protected void writePropertyHooks() {
        if(statement.getProperties()==null) return;
        for(ClassVarStmtToken declaration:statement.getProperties()) {
            if(!declaration.hasPropertyHooks()) continue;
            PropertyEntity property=entity.findProperty(declaration.getVariable().getName());
            if(property==null || property.getClazz()!=entity) {
                // Interface properties have no instance constructor/storage emitter.
                property=new PropertyEntity(compiler.getContext());property.setClazz(entity);
                property.setName(declaration.getVariable().getName());property.setModifier(declaration.getModifier());
                property.setDeclaredType(declaration.getDeclaredType());property.setFinal(declaration.isFinal());
                property.setPhpSetModifier(declaration.getPhpSetModifier());
                property.setPromoted(declaration.isPromoted());property.setTrace(declaration.toTraceInfo(compiler.getContext()));
                property.setDefault(false);property.setDefaultValue(Memory.UNDEFINED);
                entity.addProperty(property).check(compiler.getEnvironment());
            }
            java.util.List<php.runtime.reflection.support.PhpPropertyHooks.Hook> hooks=new java.util.ArrayList<>();
            for(org.develnext.jphp.core.tokenizer.token.stmt.PropertyHookStmtToken hook:declaration.getPropertyHooks()) {
                MethodEntity method=compiler.compileMethod(this,hook.getMethod(),external,generatorEntity);
                // Hidden hook bodies publish their actual generator through the same module ordinal catalog.
                if(method.getGeneratorEntity()!=null)compiler.getModule().addGenerator(method.getGeneratorEntity());
                method.setImmutable(false);method.setResult(null);
                if(hook.getKind()==org.develnext.jphp.core.tokenizer.token.stmt.PropertyHookStmtToken.Kind.SET) {
                    var type=property.getDeclaredType()==null
                            ?php.runtime.reflection.support.PhpTypeDeclaration.fromChecker(null,false):property.getDeclaredType();
                    var parameter=method.getParameters()[0];
                    var accepted=php.runtime.reflection.support.PhpTypeDeclaration.fromChecker(parameter.getTypeChecker(),parameter.isNullable());
                    // Builtin-only declarations are fully decidable before any PHP body; named relations resolve at cold class binding.
                    boolean builtinType=type.getAlternatives().stream().flatMap(java.util.Collection::stream)
                            .allMatch(name->new php.runtime.reflection.support.PhpTypeDeclaration(java.util.List.of(java.util.List.of(name))).isBuiltin());
                    boolean builtinParameter=accepted.getAlternatives().stream().flatMap(java.util.Collection::stream)
                            .allMatch(name->new php.runtime.reflection.support.PhpTypeDeclaration(java.util.List.of(java.util.List.of(name))).isBuiltin());
                    if(builtinType && builtinParameter && !type.isSubtypeOf(accepted,entity,entity,compiler.getModule(),compiler.getEnvironment()))
                        compiler.getEnvironment().error(method.getTrace(),ErrorType.E_COMPILE_ERROR,
                                "Type of parameter $%s of hook %s::$%s::set must be compatible with property type",
                                parameter.getName(),entity.getName(),property.getName());
                }
                hooks.add(new php.runtime.reflection.support.PhpPropertyHooks.Hook(
                        hook.getKind()==org.develnext.jphp.core.tokenizer.token.stmt.PropertyHookStmtToken.Kind.GET
                                ?php.runtime.reflection.support.PhpPropertyHooks.Kind.GET:php.runtime.reflection.support.PhpPropertyHooks.Kind.SET,
                        property,method));
            }
            property.installPhpHookDeclaration(new php.runtime.reflection.support.PhpPropertyHooks(declaration.isVirtualProperty(),hooks));
        }
    }

    protected void writeConstant(ConstStmtToken constant) {
        MethodStmtCompiler methodStmtCompiler = new MethodStmtCompiler(this, (MethodStmtToken) null);
        methodStmtCompiler.setInitializer(true);
        ExpressionStmtCompiler expressionStmtCompiler = new ExpressionStmtCompiler(methodStmtCompiler, null);

        DocumentComment documentComment = null;
        if (constant.getDocComment() != null)
            documentComment = new DocumentComment(compiler.getModule().getPhpLanguageProfile() == php.runtime.common.PhpLanguageProfile.PHP_8_5
                    ? constant.getDocComment().getWord() + "*/" : constant.getDocComment().getComment());

        boolean modern = compiler.getModule().getPhpLanguageProfile() == php.runtime.common.PhpLanguageProfile.PHP_8_5;
        for (ConstStmtToken.Item el : constant.items) {
            if(entity.isEnum() && entity.findConstant(el.getFulledName())!=null)
                compiler.getEnvironment().error(constant.toTraceInfo(compiler.getContext()),ErrorType.E_COMPILE_ERROR,"Cannot redefine class constant %s::%s",entity.getName(),el.getFulledName());
            Memory value = constant instanceof EnumCaseStmtToken && el.value==null ? Memory.NULL : modern ? PhpConstantExpression.check(el.value, expressionStmtCompiler)
                    : expressionStmtCompiler.writeExpression(el.value, true, true, false);
            ConstantEntity constantEntity = new ConstantEntity(el.getFulledName(), value, true);
            constantEntity.setEnumCase(constant instanceof EnumCaseStmtToken);
            compilePhpAttributes(constantEntity,constant,php.runtime.reflection.support.PhpAttribute.CLASS_CONSTANT);
            constantEntity.setTrace(el.name.toTraceInfo(compiler.getContext()));
            constantEntity.setDocComment(documentComment);
            constantEntity.setModifier(constant.getModifier());
            constantEntity.setFinal(constant.isFinal());
            constantEntity.setDeclaredType(constant.getDeclaredType());
            if (value != null && constant.getDeclaredType() != null) {
                value = constantEntity.validateValue(compiler.getEnvironment(), value, entity, true);
                constantEntity.setValue(value);
            }

            if (value != null && !value.isArray()) {
                ConstantEntity c = entity.findConstant(el.getFulledName());
                if (c != null && c.getClazz().getId() == entity.getId()) {
                    compiler.getEnvironment().error(
                            constant.toTraceInfo(compiler.getContext()),
                            ErrorType.E_ERROR,
                            Messages.ERR_CANNOT_REDEFINE_CLASS_CONSTANT,
                            entity.getName() + "::" + el.getFulledName()
                    );
                    return;
                }
                entity.addConstant(constantEntity).check(compiler.getEnvironment());
            } else {
                if (modern || ValueExprToken.isConstable(el.value.getSingle(), false)) {
                    dynamicConstants.add(el);
                    entity.addConstant(constantEntity).check(compiler.getEnvironment());
                } else {
                    compiler.getEnvironment().error(
                            constant.toTraceInfo(compiler.getContext()),
                            Messages.ERR_EXPECTED_CONST_VALUE.fetch(entity.getName() + "::" + el.getFulledName())
                    );
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    protected void writeSystemInfo() {
        if(noDiscardClosureName!=null)node.fields.add(new FieldNode(ACC_PUBLIC|ACC_STATIC|ACC_FINAL,
                "__$noDiscardClosureName",Type.getDescriptor(String.class),null,noDiscardClosureName));
        if(deprecatedClosureName!=null)node.fields.add(new FieldNode(ACC_PUBLIC|ACC_STATIC|ACC_FINAL,
                "__$deprecatedClosureName",Type.getDescriptor(String.class),null,deprecatedClosureName));
        if(isSystem && classContext!=null) node.fields.add(new FieldNode(ACC_PUBLIC|ACC_STATIC|ACC_FINAL,
                "__$attributeLexicalScope",Type.getDescriptor(String.class),null,classContext.getFulledName()));
        node.fields.add(new FieldNode(
                ACC_PUBLIC + ACC_FINAL + ACC_STATIC, "$FN",
                Type.getDescriptor(String.class),
                null,
                compiler.getSourceFile()
        ));

        node.fields.add(new FieldNode(
                ACC_PUBLIC + ACC_STATIC, "$TRC",
                Type.getDescriptor(TraceInfo[].class),
                null,
                null
        ));

        node.fields.add(new FieldNode(
                ACC_PUBLIC + ACC_STATIC, "$MEM",
                Type.getDescriptor(Memory[].class),
                null,
                null
        ));

        node.fields.add(new FieldNode(
                ACC_PUBLIC + ACC_STATIC, "$AMEM",
                Type.getDescriptor(Memory[][].class),
                null,
                null
        ));

        node.fields.add(new FieldNode(
                ACC_PUBLIC + ACC_STATIC, "$CALL_FUNC_CACHE",
                Type.getDescriptor(FunctionCallCache.class),
                null,
                null
        ));

        node.fields.add(new FieldNode(
                ACC_PUBLIC + ACC_STATIC, "$CALL_METH_CACHE",
                Type.getDescriptor(MethodCallCache.class),
                null,
                null
        ));

        node.fields.add(new FieldNode(
                ACC_PUBLIC + ACC_STATIC, "$CALL_PROP_CACHE",
                Type.getDescriptor(PropertyCallCache.class),
                null,
                null
        ));

        node.fields.add(new FieldNode(
                ACC_PUBLIC + ACC_STATIC, "$CALL_CONST_CACHE",
                Type.getDescriptor(ConstantCallCache.class),
                null,
                null
        ));

        node.fields.add(new FieldNode(
                ACC_PUBLIC + ACC_STATIC, "$CALL_CLASS_CACHE",
                Type.getDescriptor(ClassCallCache.class),
                null,
                null
        ));

        if (functionName != null) {
            node.fields.add(new FieldNode(
                    ACC_PUBLIC + ACC_FINAL + ACC_STATIC, "$CL",
                    Type.getDescriptor(String.class),
                    null,
                    !functionName.isEmpty() ? functionName : entity.getName()
            ));
        }
    }

    @SuppressWarnings("unchecked")
    private void writeConstantInitializers() {
        int index = 0;
        for (ConstStmtToken.Item item : dynamicConstants) {
            ConstantEntity constant = entity.findConstant(item.getFulledName());
            if (constant.getStoredValue() != null) continue;
            MethodNode node = new MethodNodeImpl();
            node.access = ACC_PUBLIC | ACC_STATIC;
            node.name = "__$constant" + index++;
            node.desc = Type.getMethodDescriptor(Type.getType(Memory.class), Type.getType(Environment.class));
            constant.setInitializer(entity.getName(), node.name);
            MethodStmtCompiler methodCompiler = new MethodStmtCompiler(this, node);
            methodCompiler.setInitializer(true);
            ExpressionStmtCompiler expressionCompiler = new ExpressionStmtCompiler(methodCompiler, null);
            methodCompiler.writeHeader();
            LabelNode start = expressionCompiler.makeLabel();
            methodCompiler.addLocalVariable("~env", start, Environment.class);
            expressionCompiler.writeExpression(item.value, true, false, true);
            expressionCompiler.writePopBoxing(true);
            node.instructions.add(new InsnNode(ARETURN));
            expressionCompiler.stackPop();
            methodCompiler.writeFooter();
            this.node.methods.add(node);
        }
    }

    protected void writeInitEnvironment() {
        if (!dynamicConstants.isEmpty() || !dynamicProperties.isEmpty()) {
            initDynamicExists = true;
            MethodNode node = new MethodNodeImpl();
            node.access = ACC_STATIC + ACC_PUBLIC;
            node.name = "__$initEnvironment";
            node.desc = Type.getMethodDescriptor(
                    Type.getType(void.class), Type.getType(Environment.class)
            );
            if (entity.isTrait()) {
                node.desc = Type.getMethodDescriptor(
                        Type.getType(void.class), Type.getType(Environment.class), Type.getType(String.class)
                );
            }

            MethodStmtCompiler methodCompiler = new MethodStmtCompiler(this, node);
            methodCompiler.setInitializer(true);
            ExpressionStmtCompiler expressionCompiler = new ExpressionStmtCompiler(methodCompiler, null);
            methodCompiler.writeHeader();

            LabelNode l0 = expressionCompiler.makeLabel();
            methodCompiler.addLocalVariable("~env", l0, Environment.class);
            if (entity.isTrait())
                methodCompiler.addLocalVariable("~class_name", l0, String.class);

            LocalVariable l_class = methodCompiler.addLocalVariable("~class", l0, ClassEntity.class);

            expressionCompiler.writePushEnv();
            if (entity.isTrait()) {
                expressionCompiler.writeVarLoad("~class_name");
                expressionCompiler.writePushDupLowerCase();
            } else {
                expressionCompiler.writePushConstString(entity.getName());
                expressionCompiler.writePushConstString(entity.getLowerName());
            }
            expressionCompiler.writePushConstBoolean(true);
            expressionCompiler.writeSysDynamicCall(
                    Environment.class, "fetchClass", ClassEntity.class, String.class, String.class, Boolean.TYPE
            );
            expressionCompiler.writeVarStore(l_class, false, false);

            // corrects defination of constants
            final List<ConstStmtToken.Item> first = new ArrayList<ConstStmtToken.Item>();
            final Set<String> usedNames = new HashSet<String>();
            final List<ConstStmtToken.Item> other = new ArrayList<ConstStmtToken.Item>();
            for (ConstStmtToken.Item el : dynamicConstants) {
                Token tk = el.value.getSingle();
                if (tk instanceof StaticAccessExprToken) {
                    StaticAccessExprToken access = (StaticAccessExprToken) tk;
                    boolean self = false;
                    if (access.getClazz() instanceof SelfExprToken)
                        self = true;
                    else if (access.getClazz() instanceof FulledNameToken
                            && sameClassName(((FulledNameToken) access.getClazz()).getName(), entity.getName())) {
                        self = true;
                    }
                    if (self && access.getField() instanceof NameToken) {
                        String name = ((NameToken) access.getField()).getName();
                        if (usedNames.contains(el.getFulledName()))
                            first.add(0, el);
                        else
                            first.add(el);
                        usedNames.add(name);
                        continue;
                    }
                }

                if (usedNames.contains(el.getFulledName()))
                    first.add(0, el);
                else
                    other.add(el);
            }

            other.addAll(0, first);

            for (ConstStmtToken.Item el : other) {
                if (compiler.getModule().getPhpLanguageProfile() == php.runtime.common.PhpLanguageProfile.PHP_8_5) continue;
                expressionCompiler.writeVarLoad(l_class);
                expressionCompiler.writePushEnv();
                expressionCompiler.writePushConstString(el.getFulledName());
                expressionCompiler.writeExpression(el.value, true, false, true);
                expressionCompiler.writePopBoxing(true);

                expressionCompiler.writeSysDynamicCall(
                        ClassEntity.class, entity.isTrait() && compiler.getModule().getPhpLanguageProfile() == php.runtime.common.PhpLanguageProfile.PHP_8_5
                                ? "addTraitDynamicConstant" : "addDynamicConstant", void.class,
                        Environment.class, String.class, Memory.class
                );
            }

            for (ClassVarStmtToken el : dynamicProperties) {
                expressionCompiler.writeVarLoad(l_class);
                expressionCompiler.writePushEnv();
                expressionCompiler.writePushConstString(el.getVariable().getName());
                expressionCompiler.writeExpression(el.getValue(), true, false, true);
                expressionCompiler.writePopBoxing(true);

                expressionCompiler.writeSysDynamicCall(
                        ClassEntity.class,
                        el.isStatic() ? "addDynamicStaticProperty" : "addDynamicProperty",
                        void.class,
                        Environment.class, String.class, Memory.class
                );
            }

            node.instructions.add(new InsnNode(RETURN));
            methodCompiler.writeFooter();

            this.node.methods.add(node);
        }
    }

    @SuppressWarnings("unchecked")
    protected void writeInitStatic() {
        MethodNode node = new MethodNodeImpl();
        node.access = ACC_STATIC;
        node.name = Constants.STATIC_INIT_METHOD;
        node.desc = Type.getMethodDescriptor(Type.getType(void.class));

        MethodStmtCompiler methodCompiler = new MethodStmtCompiler(this, node);
        ExpressionStmtCompiler expressionCompiler = new ExpressionStmtCompiler(methodCompiler, null);
        methodCompiler.writeHeader();

        ClassArrayTableCompiler.write(this, expressionCompiler, "$TRC", TraceInfo[].class, TraceInfo.class,
                traceList, (expr, trace) -> {
                    var imported=importedHookTraces==null?null:importedHookTraces.get(trace);
                    if(imported==null) expr.writePushCreateTraceInfo(trace.getStartLine(), trace.getStartPosition());
                    else expr.writePushImportedHookTrace(imported.owner(),imported.index());
                });
        ClassArrayTableCompiler.write(this, expressionCompiler, "$MEM", Memory[].class, Memory.class,
                memoryConstants, (expr, memory) -> {expr.writePushMemory(memory);expr.writePopBoxing(true, false);});
        ClassArrayTableCompiler.write(this, expressionCompiler, "$AMEM", Memory[][].class, Memory[].class,
                memoryArrayConstants, (expr, memories) -> expr.writePushParameters(memories));

        // cached calls
        expressionCompiler.writePushNewObject(FunctionCallCache.class);
        expressionCompiler.writePutStatic("$CALL_FUNC_CACHE", FunctionCallCache.class);

        expressionCompiler.writePushNewObject(MethodCallCache.class);
        expressionCompiler.writePutStatic("$CALL_METH_CACHE", MethodCallCache.class);

        expressionCompiler.writePushNewObject(ConstantCallCache.class);
        expressionCompiler.writePutStatic("$CALL_CONST_CACHE", ConstantCallCache.class);

        expressionCompiler.writePushNewObject(PropertyCallCache.class);
        expressionCompiler.writePutStatic("$CALL_PROP_CACHE", PropertyCallCache.class);

        expressionCompiler.writePushNewObject(ClassCallCache.class);
        expressionCompiler.writePutStatic("$CALL_CLASS_CACHE", ClassCallCache.class);

        node.instructions.add(new InsnNode(RETURN));
        methodCompiler.writeFooter();

        this.node.methods.add(node);
    }

    protected void writeInterfaceMethod(MethodEntity method) {
        MethodNode node = new MethodNodeImpl();
        node.access = ACC_PUBLIC;
        node.name = method.getName();
        node.desc = Type.getMethodDescriptor(
                Type.getType(Memory.class),
                Type.getType(Environment.class),
                Type.getType(Memory[].class)
        );

        MethodStmtCompiler methodCompiler = new MethodStmtCompiler(this, node);
        ExpressionStmtCompiler expressionCompiler = new ExpressionStmtCompiler(methodCompiler, null);
        methodCompiler.writeHeader();

        LabelNode l0 = writeLabel(node, statement.getMeta().getStartLine());
        methodCompiler.addLocalVariable("~this", l0);
        methodCompiler.addLocalVariable("~env", l0);
        methodCompiler.addLocalVariable("~args", l0);

        expressionCompiler.writeVarLoad("~this");
        expressionCompiler.writeVarLoad("~env");
        expressionCompiler.writeVarLoad("~args");

        String internalName = entity.findMethod(method.getLowerName()).getInternalName();
        expressionCompiler.writeSysDynamicCall(null, internalName, Memory.class, Environment.class, Memory[].class);

        node.instructions.add(new InsnNode(ARETURN));
        methodCompiler.writeFooter();
        this.node.methods.add(node);
    }

    protected void writeInterfaceMethods(Collection<MethodEntity> methods) {
        for (MethodEntity method : methods) {
            writeInterfaceMethod(method);
        }
    }

    protected Set<ClassEntity> writeInterfaces(ClassEntity _interface) {
        Set<ClassEntity> result = new HashSet<ClassEntity>();
        writeInterfaces(_interface, result);
        return result;
    }

    protected void writeInterfaces(ClassEntity _interface, Set<ClassEntity> used) {
        if (used.add(_interface)) {
            if (_interface != null && _interface.isInternal())
                node.interfaces.add(_interface.getInternalName());

            if (_interface != null) {
                for (ClassEntity el : _interface.getInterfaces().values()) {
                    if (!_interface.isInternal() || !el.isInternal())
                        writeInterfaces(el, used);
                }
            }
        }
    }

    protected ClassEntity fetchClass(String name) {
        ClassEntity result = compiler.getModule().findClass(name);
        if (result == null)
            result = getCompiler().getEnvironment().fetchClass(name, true, compiler.getModule().getPhpLanguageProfile());

        return result;
    }

    protected ClassEntity fetchClassAndCheck(String name) {
        ClassEntity r = fetchClass(name);
        if (r == null)
            compiler.getEnvironment().error(
                    entity.getTrace(),
                    Messages.ERR_CLASS_NOT_FOUND.fetch(name)
            );
        return r;
    }

    @SuppressWarnings("unchecked")
    protected void writeImplements() {
        if (statement.getImplement() != null) {
            Environment env = compiler.getEnvironment();
            for (FulledNameToken name : statement.getImplement()) {
                ClassEntity implement = fetchClass(name.getName());
                Set<ClassEntity> needWriteInterfaceMethods = new HashSet<ClassEntity>();
                if (implement == null) {
                    env.error(
                            name.toTraceInfo(compiler.getContext()),
                            Messages.ERR_INTERFACE_NOT_FOUND.fetch(name.toName())
                    );
                } else {
                    if (implement.getType() != ClassEntity.Type.INTERFACE) {
                        env.error(
                                name.toTraceInfo(compiler.getContext()),
                                Messages.ERR_CANNOT_IMPLEMENT.fetch(entity.getName(), implement.getName())
                        );
                    }
                    if (!statement.isInterface())
                        needWriteInterfaceMethods = writeInterfaces(implement);
                }
                ClassEntity.ImplementsResult addResult = entity.addInterface(implement);
                if (!entity.isUnresolvedDeclaration()) addResult.check(env);

                for (ClassEntity el : needWriteInterfaceMethods) {
                    if (el.isInternal()) {
                        writeInterfaceMethods(el.getMethods().values());
                    }
                }
            }
        }
    }

    protected void writeCopiedMethod(ClassStmtToken.Alias alias, String methodName, ClassEntity trait) {
        writeCopiedMethod(alias,methodName,trait,
                fetchClassAndCheck(alias.getTrait()).findMethod(methodName.toLowerCase()),null);
    }
    private MethodEntity writeCopiedMethod(ClassStmtToken.Alias alias,String methodName,ClassEntity trait,
            final MethodEntity methodEntity,php.runtime.reflection.support.PhpPropertyHooks.Hook hook) {

        String name = alias.getName();
        if (name == null)
            name = methodName;

        MethodEntity origin = hook==null ? entity.findMethod(name.toLowerCase()) : null;
        if (origin != null) {
            if (origin.getClazz() == entity) {
                if (origin.getTrait() != null) {
                    compiler.getEnvironment().error(
                            entity.getTrace(),
                            Messages.ERR_TRAIT_METHOD_COLLISION.fetch(
                                    methodName, trait.getName(), origin.getTrait().getName(), entity.getName()
                            )
                    );
                }
                return null;
            }
        }

        if (methodEntity == null) {
            compiler.getEnvironment().error(
                    entity.getTrace(),
                    Messages.ERR_METHOD_NOT_FOUND.fetch(alias.getTrait(), methodName)
            );
            return null;
        }

        MethodEntity dup = methodEntity.duplicateForInject();
        dup.setClazz(entity);
        dup.setTrait(trait);
        if (alias.getName() != null)
            dup.setName(alias.getName());

        if (alias.getModifier() != null)
            dup.setModifier(alias.getModifier());
        if (alias.isMakeFinal()) dup.setFinal(true);
        importPhpAttributeHelpers(methodEntity,dup,methodEntity.getClazz());
        if(methodEntity.getParameters()!=null) {
            ParameterEntity[] parameters=new ParameterEntity[methodEntity.getParameters().length];
            for(int i=0;i<parameters.length;i++) {
                var from=methodEntity.getParameters()[i];var to=new ParameterEntity(from.getContext());
                to.setName(from.getName());to.setTrace(from.getTrace());to.setTypeChecker(from.getTypeChecker());
                to.setNullable(from.isNullable());to.setReference(from.isReference());to.setVariadic(from.isVariadic());
                to.setMutable(from.isMutable());to.setUsed(from.isUsed());to.setPromoted(from.isPromoted());
                to.setDefaultValue(from.getDefaultValue());to.setDefaultValueConstName(from.getDefaultValueConstName());
                to.setDefaultInitializer(from.getDefaultInitializer());
                importPhpAttributeHelpers(from,to,methodEntity.getClazz(),dup.getName()+"/P"+i);parameters[i]=to;
            }
            dup.setParameters(parameters);
        }

        if(hook!=null && methodEntity.isAbstractable()) {
            dup.setInternalName(dup.getName()+"$"+entity.nextMethodIndex());
            return dup;
        }
        MethodNodeImpl methodNode = hook!=null ? TraitHookBodyCompiler.copy(hook,compiler.getEnvironment())
                : MethodNodeImpl.duplicate(methodEntity.getAdditionalData("methodNode", MethodNode.class, new Function<MethodNode>() {
            @Override
            public MethodNode call() {
                ClassNode classNode = methodEntity.getClazz().getAdditionalData("classNode", ClassNode.class, new Function<ClassNode>() {
                    @Override
                    public ClassNode call() {
                        ClassReader classReader;
                        if (methodEntity.getClazz().getData() != null)
                            classReader = new ClassReader(methodEntity.getClazz().getData());
                        else {
                            try {
                                classReader = new ClassReader(methodEntity.getClazz().getName());
                            } catch (IOException e) {
                                throw new CriticalException(e);
                            }
                        }
                        ClassNode classNode = new ClassNode();
                        classReader.accept(classNode, new org.objectweb.asm.Attribute[]{
                                new TraitStaticSitesCompiler.SitesAttribute(new byte[0])}, 0);

                        return classNode;
                    }
                });

                for (Object m : classNode.methods) {
                    MethodNode method = (MethodNode) m;
                    if (method.name.equals(methodEntity.getInternalName())) {
                        return method;
                    }
                }

                throw new CriticalException("Cannot find MethodNode for method - " + methodEntity.getName() + "(" + methodEntity.getSignatureString(true) + ")");
            }
        }));

        if (origin != null) {
            dup.setPrototype(origin);
        }

        dup.setInternalName(dup.getName() + "$" + entity.nextMethodIndex());
        methodNode.name = dup.getInternalName();
        if(hook!=null) {
            var trace=TraitHookBodyCompiler.rebind(methodNode,hook,compiler.getEnvironment(),entity.getCompiledInternalName(),dup.getInternalName(),traceList.size());
            if(trace!=null) {
                TraceInfo identity=new TraceInfo(methodEntity.getTrace().getFileName(),methodEntity.getTrace().getStartLine(),0);
                traceList.add(identity);if(importedHookTraces==null)importedHookTraces=new IdentityHashMap<>();
                importedHookTraces.put(identity,trace);
            }
        }
        if(hook==null)TraitStaticSitesCompiler.rebindCopiedMethod(methodNode,methodEntity.getClazz().getCompiledInternalName(),
                methodEntity.getInternalName(),entity.getCompiledInternalName(),dup.getInternalName());
        if (alias.isMakeFinal()) methodNode.access |= ACC_FINAL;

        if(hook==null) {
            ClassEntity.SignatureResult result = entity.addMethod(dup, null);
            result.check(compiler.getEnvironment());
        }
        node.methods.add(methodNode);
        return dup;
    }

    protected void writeCopiedMethod(MethodEntity methodEntity, ClassEntity trait) {
        ClassStmtToken.Replacement replacement = statement.findReplacement(methodEntity.getName());
        if (replacement != null && replacement.hasTrait(trait.getName())) {
            return;
        }

        List<ClassStmtToken.Alias> aliases = statement.findAliases(methodEntity.getName());
        if (aliases == null)
            aliases = Arrays.asList(new ClassStmtToken.Alias(trait.getName(), null, methodEntity.getName()));
        else {
            boolean replaceExists = false;
            boolean replaceAlias = false;

            for (ClassStmtToken.Alias alias : aliases) {
                if (replacement != null && sameClassName(alias.getTrait(), replacement.getOrigin())) {
                    replaceExists = true;
                }
                if (alias.getName() == null || alias.getName().equalsIgnoreCase(methodEntity.getName()))
                    replaceAlias = true;
            }

            if (replacement != null && !replaceExists) {
                aliases.add(new ClassStmtToken.Alias(replacement.getOrigin(), null, methodEntity.getName()));
                replaceAlias = true;
            }

            if (!replaceAlias)
                aliases.add(new ClassStmtToken.Alias(trait.getName(), null, methodEntity.getName()));
        }

        for (ClassStmtToken.Alias alias : aliases) {
            writeCopiedMethod(alias, methodEntity.getName(), trait);
        }
    }

    protected void writeTraitProperties(ClassEntity trait, Collection<PropertyEntity> props) {
        for (PropertyEntity el : props) {
            if(entity.isPhpReadonly() && !el.isPhpReadonly()) compiler.getEnvironment().error(el.getTrace(),ErrorType.E_COMPILE_ERROR,
                    "Readonly class %s cannot use trait with a non-readonly property %s::$%s",entity.getName(),trait.getName(),el.getName());
            PropertyEntity origin = (el.isStatic() ? entity.staticProperties : entity.properties).get(el.getName());

            if (origin != null) {
                // doesn't work with parent private properties of non-traits  (see: the traits/property006.php test)
                boolean isSkip = origin.getTrait() == null && !origin.getClazz().equals(entity) && origin.isPrivate();
                if (origin.getTrait() != null && origin.getTrait().equals(trait))
                    isSkip = true;

                if (!isSkip && (origin.getClazz()==entity
                        || compiler.getModule().getPhpLanguageProfile()!=php.runtime.common.PhpLanguageProfile.PHP_8_5)) {
                    Environment env = compiler.getEnvironment();
                    String ownerName = origin.getClazz().getName();
                    if (origin.getTrait() != null)
                        ownerName = origin.getTrait().getName();

                    boolean modern = compiler.getModule().getPhpLanguageProfile() == php.runtime.common.PhpLanguageProfile.PHP_8_5;
                    boolean sameType = origin.getDeclaredType() == null ? el.getDeclaredType() == null
                            : el.getDeclaredType() != null && origin.getDeclaredType().equivalentTo(el.getDeclaredType());
                    boolean isFatal = modern && (origin.getPhpHooks()!=null || el.getPhpHooks()!=null) && origin!=el
                            || origin.getModifier() != el.getModifier()
                            || modern && (!sameType || origin.isStatic() != el.isStatic() || origin.isFinal() != el.isFinal() || origin.isPhpReadonly() != el.isPhpReadonly() || origin.effectiveSetModifier()!=el.effectiveSetModifier()
                                || origin.getDeclaredType() != null && origin.isDefault() != el.isDefault());
                    if (origin.getDefaultValue() != null && el.getDefaultValue() == null)
                        isFatal = true;
                    else if (origin.getDefaultValue() == null && el.getDefaultValue() != null)
                        isFatal = true;
                    else if (origin.getDefaultValue() == null) {
                        // nop
                    } else if (!origin.getDefaultValue().identical(el.getDefaultValue()))
                        isFatal = true;

                    if (isFatal) {
                        if (modern) env.error(entity.getTrace(), ErrorType.E_COMPILE_ERROR,
                                "%s and %s define the same property ($%s) in the composition of %s. However, the definition differs and is considered incompatible. Class was composed",
                                ownerName, trait.getName(), el.getName(), entity.getName());
                        env.error(
                                entity.getTrace(),
                                Messages.ERR_TRAIT_SAME_PROPERTY.fetch(
                                        ownerName,
                                        trait.getName(), el.getName(), entity.getName()
                                )
                        );
                    } else if (!modern) {
                        env.error(
                                entity.getTrace(), ErrorType.E_STRICT,
                                Messages.ERR_TRAIT_SAME_PROPERTY_STRICT.fetch(
                                        ownerName, trait.getName(), el.getName(), entity.getName()
                                )
                        );
                    }
                }
            }

            if (origin != null && origin.getClazz().getId() == entity.getId())
                continue;

            PropertyEntity p = el.duplicate();
            p.setPhpHooks(null);
            p.setClazz(entity);
            p.setTrait(trait);
            importPhpAttributeHelpers(el,p,el.getClazz());
            var catalog=el.getPhpHooks();
            if(catalog!=null) {
                var hooks=new ArrayList<php.runtime.reflection.support.PhpPropertyHooks.Hook>();
                for(var hook:catalog.declaredOrder()) {
                    if(hook.declaration()!=el || hook.method().getClazz()!=el.getClazz())
                        throw new CriticalException("Trait hook lacks its exact property declaration");
                    MethodEntity copied=writeCopiedMethod(new ClassStmtToken.Alias(trait.getName(),null,hook.method().getName()),
                            hook.method().getName(),trait,hook.method(),hook);
                    hooks.add(new php.runtime.reflection.support.PhpPropertyHooks.Hook(hook.kind(),p,copied));
                }
                if(!hooks.isEmpty())p.setPhpHooks(new php.runtime.reflection.support.PhpPropertyHooks(catalog.isDeclaredVirtual(),hooks));
            }
            ClassEntity.PropertyResult r = entity.addProperty(p);
            r.check(compiler.getEnvironment());
        }
    }

    protected void writeTraitProperties(ClassEntity trait) {
        writeTraitProperties(trait, trait.getProperties());
        writeTraitProperties(trait, trait.getStaticProperties());
    }

    protected void writeTraitConstants(ClassEntity trait) {
        for (ConstantEntity candidate : trait.getConstants()) {
            ConstantEntity origin = entity.findConstant(candidate.getName());
            if (origin != null && origin.getClazz() == entity) {
                boolean sameType = origin.getDeclaredType() == null ? candidate.getDeclaredType() == null
                        : candidate.getDeclaredType() != null && origin.getDeclaredType().forTraitComposition(entity)
                            .equivalentTo(candidate.getDeclaredType().forTraitComposition(entity));
                boolean compatible = sameType && origin.getModifier() == candidate.getModifier() && origin.isFinal() == candidate.isFinal();
                boolean deferred = deferredTraitConstants.contains(candidate.getName())
                        || origin.getValue() == null || candidate.getValue() == null;
                if (compatible && !deferred) compatible = origin.getValue().identical(candidate.getValue());
                if (!compatible) entity.traitConstantConflict(compiler.getEnvironment(), candidate.getName(), trait.getName());
                else if (deferred) {
                    deferredTraitConstants.add(candidate.getName());
                    entity.addTraitConstantCheck(candidate.getName(), trait.getName(), candidate.getValue(),
                            origin.getTraitName() == null ? entity.getName() : origin.getTraitName());
                }
                if (origin.getTraitName() == null) origin.setTraitName(trait.getName());
                continue;
            }
            var imported=candidate.duplicateForTrait(entity,trait.getName());
            importPhpAttributeHelpers(candidate,imported,candidate.getClazz());
            entity.addConstant(imported).check(compiler.getEnvironment());
        }
    }

    protected void writeTrait(ClassEntity trait) {
        entity.addTrait(trait);
        initDynamicExists = true;

        if (compiler.getModule().getPhpLanguageProfile() == php.runtime.common.PhpLanguageProfile.PHP_8_5)
            writeTraitConstants(trait);
        writeTraitProperties(trait);
        for (MethodEntity methodEntity : trait.getMethods().values()) {
            writeCopiedMethod(methodEntity, trait);
        }
    }

    private boolean sameClassName(String left, String right) {
        return compiler.getModule().getPhpLanguageProfile() == php.runtime.common.PhpLanguageProfile.PHP_8_5
                ? PhpClassNames.canonicalKey(left).equals(PhpClassNames.canonicalKey(right)) : left.equalsIgnoreCase(right);
    }

    protected void checkRequiredTrait(String trait) {
        if (!entity.hasTrait(PhpClassNames.key(trait, compiler.getModule().getPhpLanguageProfile()))) {
            compiler.getEnvironment().error(
                    entity.getTrace(),
                    Messages.ERR_TRAIT_WAS_NOT_ADDED.fetch(trait, entity.getName())
            );
        }
    }

    protected void checkAliasAndReplacementsTraits() {
        if (statement.getAliases() != null)
            for (List<ClassStmtToken.Alias> aliases : statement.getAliases().values()) {
                for (ClassStmtToken.Alias alias : aliases) {
                    checkRequiredTrait(alias.getTrait());
                }
            }

        if (statement.getReplacements() != null)
            for (ClassStmtToken.Replacement replacement : statement.getReplacements().values()) {
                checkRequiredTrait(replacement.getOrigin());
                for (String e : replacement.getTraits())
                    checkRequiredTrait(e);
            }
    }

    protected List<ClassEntity> fetchTraits() {
        List<ClassEntity> r = new ArrayList<ClassEntity>();
        for (NameToken one : statement.getUses()) {
            ClassEntity trait = fetchClass(one.getName());
            if (trait == null) {
                compiler.getEnvironment().error(
                        one.toTraceInfo(compiler.getContext()),
                        Messages.ERR_TRAIT_NOT_FOUND.fetch(one.getName())
                );
                return null;
            }

            if (!trait.isTrait()) {
                compiler.getEnvironment().error(
                        one.toTraceInfo(compiler.getContext()),
                        Messages.ERR_CANNOT_USE_NON_TRAIT.fetch(
                                entity.getName(), one.getName()
                        )
                );
            } else
                r.add(trait);
        }
        return r;
    }

    protected void writeTraits(Collection<ClassEntity> traits) {
        for (ClassEntity trait : traits) {
            writeTrait(trait);
        }
    }

    private PhpClassRelations.Name relationName(NameToken name) {
        return new PhpClassRelations.Name(name.getName(), name.toTraceInfo(compiler.getContext()));
    }

    /** Store names and directives only; conditional declarations resolve their relations when executed. */
    private PhpClassRelations declarationRelations() {
        PhpClassRelations.Name parent = statement.getExtend() == null ? null
                : relationName(statement.getExtend().getName());
        List<PhpClassRelations.InterfaceUse> interfaces = new ArrayList<>();
        if (statement.getImplement() != null) {
            PhpClassRelations.InterfaceKind kind = statement.isInterface()
                    ? PhpClassRelations.InterfaceKind.EXTENDS : PhpClassRelations.InterfaceKind.IMPLEMENTS;
            for (NameToken name : statement.getImplement().getNames())
                interfaces.add(new PhpClassRelations.InterfaceUse(relationName(name), kind));
        }
        List<PhpClassRelations.TraitGroup> groups = new ArrayList<>();
        for (ClassStmtToken.TraitUseGroup group : statement.getTraitUseGroups()) {
            List<PhpClassRelations.Name> traits = new ArrayList<>();
            for (NameToken name : group.traits()) traits.add(relationName(name));
            List<PhpClassRelations.Adaptation> adaptations = new ArrayList<>();
            for (ClassStmtToken.TraitAdaptation adaptation : group.adaptations()) {
                TraceInfo trace = adaptation.traceToken().toTraceInfo(compiler.getContext());
                if (adaptation instanceof ClassStmtToken.TraitAlias alias) {
                    adaptations.add(new PhpClassRelations.Alias(
                            alias.trait() == null ? null : alias.trait().getName(), alias.method().getName(),
                            alias.alias() == null ? null : alias.alias().getName(), alias.visibility(), alias.makeFinal(), trace));
                } else if (adaptation instanceof ClassStmtToken.TraitPrecedence precedence) {
                    List<PhpClassRelations.Name> excluded = new ArrayList<>();
                    for (NameToken name : precedence.excluded()) excluded.add(relationName(name));
                    adaptations.add(new PhpClassRelations.Precedence(precedence.preferredTrait().getName(),
                            precedence.method().getName(), excluded, trace));
                } else throw new IllegalArgumentException("Unknown trait adaptation");
            }
            groups.add(new PhpClassRelations.TraitGroup(traits, adaptations,
                    group.traceToken().toTraceInfo(compiler.getContext())));
        }
        return new PhpClassRelations(parent, interfaces, groups);
    }

    @Override
    public ClassEntity compile() {
        entity = new ClassEntity(compiler.getContext());
        entity.setModule(compiler.getModule());
        entity.setId(compiler.getScope().nextClassIndex());

        entity.setFinal(statement.isFinal());
        entity.setPhpReadonly(statement.isPhpReadonly());
        entity.setAbstract(statement.isAbstract());
        entity.setAnonymous(statement.isAnonymous());
        if (statement.isDeferredDeclaration()) entity.setDeclarationId(statement.getDeclarationId());
        entity.setName(statement.getFulledName());

        if (statement.getDocComment() != null) {
            entity.setDocComment(new DocumentComment(statement.getDocComment().getComment()));
        }

        entity.setTrace(statement.toTraceInfo(compiler.getContext()));
        entity.setType(statement.getClassType());
        if(statement.isEnum()) {
            entity.setEnumBackingType(statement.getEnumBackingType());entity.setFinal(true);
            if(!statement.getProperties().isEmpty())compiler.getEnvironment().error(entity.getTrace(),ErrorType.E_COMPILE_ERROR,"Enum %s cannot include properties",entity.getName());
        }

        boolean declarationTemplate = statement.isDeferredDeclaration()
                || !isSystem && statement.isAnonymous() && !statement.getUses().isEmpty()
                    && compiler.getModule().getPhpLanguageProfile()==php.runtime.common.PhpLanguageProfile.PHP_8_5;
        if (declarationTemplate) entity.setDeclarationRelations(declarationRelations());
        List<ClassEntity> traits = declarationTemplate ? Collections.emptyList() : fetchTraits();
        for (ClassEntity e : traits) {
            entity.addTrait(e);
        }

        if (!declarationTemplate) checkAliasAndReplacementsTraits();

        if (statement.getExtend() != null) {
            String parentName=statement.getExtend().getName().getName();
            boolean modern=compiler.getModule().getPhpLanguageProfile()==php.runtime.common.PhpLanguageProfile.PHP_8_5 && !isSystem;
            ClassEntity parent=declarationTemplate ? null : modern ? compiler.getModule().findClass(parentName) : fetchClass(parentName);
            if (modern && !declarationTemplate && parent==null) parent=compiler.getEnvironment().fetchClass(parentName,false,php.runtime.common.PhpLanguageProfile.PHP_8_5);
            if (modern && (declarationTemplate || parent==null || parent.isUnresolvedDeclaration())) entity.setDeferredParentName(parentName);
            if (entity.getDeferredParentName()==null) {
            if (parent == null)
                compiler.getEnvironment().error(
                        statement.getExtend().toTraceInfo(compiler.getContext()),
                        Messages.ERR_CLASS_NOT_FOUND.fetch(statement.getExtend().getName().toName())
                );

            ClassEntity.ExtendsResult result = entity.setParent(parent, false);

            if (isInterfaceCheck) {
                result.check(compiler.getEnvironment());
            }
        }

            }
        if (!isSystem) {
            if (statement.isDeferredDeclaration()) {
                entity.setInternalName(compiler.getModule().getInternalName() + "_declaration" + statement.getDeclarationId());
            } else if (statement.isAnonymous()) {
                entity.setInternalName(compiler.getModule().getInternalName() + "_anonymous" + entity.getId());
            } else if (entity.isUseJavaLikeNames()) {
                entity.setInternalName(
                        entity.getName().replace('\\', '/')
                );
            } else {
                entity.setInternalName(
                        compiler.getModule().getInternalName() + "_class" + compiler.getModule().getClasses().size()
                );
            }
        }

        if (!statement.isDeferredDeclaration() && (compiler.getModule().findClass(entity.getLowerName()) != null
                || compiler.getEnvironment().isLoadedClass(entity.getName(), compiler.getModule().getPhpLanguageProfile()))) {
            throw new FatalException(
                    Messages.ERR_CANNOT_REDECLARE_CLASS.fetch(entity.getName()),
                    statement.getName().toTraceInfo(compiler.getContext())
            );
        }

        boolean deferred = compiler.getModule().getPhpLanguageProfile() == php.runtime.common.PhpLanguageProfile.PHP_8_5;
        entity.setDeferredInitialization(deferred);
        if (!statement.isInterface()) {
            node.access = ACC_SUPER + ACC_PUBLIC;
            node.name = !isSystem /*&& !statement.isTrait()*/
                    ? entity.getCompiledInternalName()
                    : statement.getFulledName(Constants.NAME_DELIMITER);

            node.superName = entity.getParent() == null
                    ? Type.getInternalName(entity.isEnum()?php.runtime.lang.BaseEnum.class:BaseObject.class)
                    : entity.getParent().getInternalName();

            node.sourceFile = compiler.getSourceFile();

            /*if (!isSystem) {
                AnnotationNode annotationNode = new AnnotationNode(Type.getInternalName(Reflection.Name.class));
                annotationNode.values = Arrays.asList("value", entity.getName());

                node.visibleAnnotations.add(annotationNode);
            } */

            writeSystemInfo();
            if (!deferred) {
                writeConstructor();
                writeDefaultConstructors();
            }
        }

        if (statement.isInterface() && deferred) {
            node.access = ACC_SUPER | ACC_PUBLIC;
            node.name = entity.getCompiledInternalName();
            node.superName = Type.getInternalName(Object.class);
            node.sourceFile = compiler.getSourceFile();
            writeSystemInfo();
        }

        compilePhpAttributes(entity,statement,php.runtime.reflection.support.PhpAttribute.CLASS);
        if(external && isSystem) for(var constant:compiler.getAnalyzer().getConstants())
            for(var item:constant.items) {
                var declaration=compiler.getModule().getConstants().stream()
                        .filter(candidate->candidate.getName().equals(item.getFulledName())).findFirst().orElse(null);
                if(declaration==null) declaration=compiler.getModule().getDeferredGlobalConstantDeclaration(item.getFulledName());
                if(declaration!=null) compilePhpAttributes(declaration,constant,php.runtime.reflection.support.PhpAttribute.CONSTANT);
            }
        // constants
        if (statement.getConstants() != null)
            for (ConstStmtToken constant : statement.getConstants()) {
                writeConstant(constant);
            }

        if (deferred && !statement.isInterface()) {
            writeConstructor();
            writeDefaultConstructors();
        }

        if (statement.getMethods() != null) {
            for (MethodStmtToken method : statement.getMethods()) {
                ClassEntity.SignatureResult result = entity.addMethod(
                        compiler.compileMethod(this, method, external, generatorEntity), null);
                result.check(compiler.getEnvironment());
            }
        }

        if(entity.isEnum()) {
            php.runtime.PhpEnums.validateDeclaration(entity,compiler.getEnvironment());
            php.runtime.PhpEnums.addProperties(entity,compiler.getEnvironment());
            writeEnumMethod("cases");
            if(entity.getEnumBackingType()!=null){writeEnumMethod("from");writeEnumMethod("tryFrom");}
            if(!declarationTemplate) {
                ClassEntity marker=compiler.getEnvironment().fetchClass(entity.getEnumBackingType()==null?"UnitEnum":"BackedEnum",true,php.runtime.common.PhpLanguageProfile.PHP_8_5);
                entity.addInterface(marker).check(compiler.getEnvironment());
            }
        }
        writePropertyHooks();
        if (deferred) writeConstantInitializers();
        if (!declarationTemplate) {
            writeTraits(traits);
            ClassEntity.SignatureResult result = entity.updateParentBody();
            if (isInterfaceCheck) result.check(compiler.getEnvironment());
            writeImplements();
        }
        php.runtime.PhpEnums.validateDeclaration(entity,compiler.getEnvironment());
        entity.doneDeclare();

        if (!statement.isInterface() || deferred) {
            if (!statement.isInterface()) writeDestructor();

            if (entity.getType() != ClassEntity.Type.INTERFACE) {
                writeInitEnvironment();
            }

            writeInitStatic();
            if(entity.isEnum()) {
                MethodNode witness=new MethodNodeImpl();witness.access=ACC_PRIVATE|ACC_STATIC|ACC_SYNTHETIC;witness.name="__$enum$declaration";witness.desc="()Ljava/lang/String;";
                witness.instructions.add(new LdcInsnNode(php.runtime.loader.dump.PhpEnumMetadata.declarationWitness(entity,new java.util.ArrayList<>(entity.getConstants()))));
                witness.instructions.add(new InsnNode(ARETURN));node.methods.add(witness);
            }
            cw = new JPHPClassWriter(entity.isTrait());
            seedFrequentLdcConstants(node,cw);
            try {
                node.accept(cw);
            } catch (RuntimeException error) {
                TraceInfo trace = statement.toTraceInfo(compiler.getContext());
                throw new CriticalException("Invalid JVM bytecode for " + entity.getName() + " at "
                        + trace.getFileName() + ":" + (trace.getStartLine() + 1), error);
            }

            entity.setData(cw.toByteArray());
            if(entity.isEnum())try {
                php.runtime.loader.dump.PhpEnumMetadata.validate(entity,new java.util.ArrayList<>(entity.getConstants()),new java.util.ArrayList<>(entity.getProperties()),entity.getOwnedMethods());
            }catch(java.io.IOException invalid){throw new CriticalException(invalid);}
        }
        php.runtime.reflection.support.PhpOverrideValidation.validate(entity,compiler.getEnvironment());
        return entity;
    }

    private void writeEnumMethod(String name) {
        String lower=name.toLowerCase(java.util.Locale.ROOT);
        MethodEntity method=new MethodEntity(compiler.getContext());method.setClazz(entity);method.setName(name);method.setInternalName("__$enum$"+lower);
        method.setStatic(true);method.setModifier(php.runtime.common.Modifier.PUBLIC);method.setTrace(entity.getTrace());
        boolean listing=lower.equals("cases");
        if(listing)method.setParameters(new ParameterEntity[0]);
        else {
            ParameterEntity parameter=new ParameterEntity(compiler.getContext());parameter.setName("value");
            parameter.setTypeChecker(new php.runtime.reflection.support.PhpTypeDeclaration(java.util.List.of(java.util.List.of("string"),java.util.List.of("int"))));
            method.setParameters(new ParameterEntity[]{parameter});
        }
        method.setReturnTypeChecker(new php.runtime.reflection.support.PhpTypeDeclaration(listing?java.util.List.of(java.util.List.of("array")):
                lower.equals("tryfrom")?java.util.List.of(java.util.List.of("static"),java.util.List.of("null")):java.util.List.of(java.util.List.of("static"))));
        entity.addMethod(method,null).check(compiler.getEnvironment());
        MethodNode generated=new MethodNodeImpl();generated.access=ACC_PUBLIC|ACC_STATIC;generated.name=method.getInternalName();
        generated.desc=Type.getMethodDescriptor(Type.getType(Memory.class),Type.getType(Environment.class),Type.getType(Memory[].class));
        generated.instructions.add(new VarInsnNode(ALOAD,0));generated.instructions.add(new LdcInsnNode(entity.getName()));
        if(!listing){generated.instructions.add(new VarInsnNode(ALOAD,1));generated.instructions.add(new InsnNode(lower.equals("tryfrom")?ICONST_1:ICONST_0));}
        generated.instructions.add(new MethodInsnNode(INVOKESTATIC,Type.getInternalName(php.runtime.PhpEnums.class),listing?"cases":"from",
                listing?Type.getMethodDescriptor(Type.getType(Memory.class),Type.getType(Environment.class),Type.getType(String.class)):
                Type.getMethodDescriptor(Type.getType(Memory.class),Type.getType(Environment.class),Type.getType(String.class),Type.getType(Memory[].class),Type.BOOLEAN_TYPE),false));
        generated.instructions.add(new InsnNode(ARETURN));node.methods.add(generated);
    }
    public void setClassContext(ClassStmtToken classContext) {
        this.classContext = classContext;
    }

    public ClassStmtToken getClassContext() {
        return classContext;
    }
}
