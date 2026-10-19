package org.develnext.jphp.core.compiler.jvm;

import org.develnext.jphp.core.tokenizer.token.expr.value.YieldExprToken;
import php.runtime.Memory;
import php.runtime.env.Context;
import php.runtime.env.Environment;
import php.runtime.loader.dump.ModuleDumper;
import php.runtime.reflection.*;
import php.runtime.reflection.helper.ClosureEntity;
import php.runtime.common.Messages;
import php.runtime.common.Modifier;
import php.runtime.common.AbstractCompiler;
import org.develnext.jphp.core.compiler.jvm.statement.*;
import php.runtime.exceptions.CompileException;
import php.runtime.exceptions.support.ErrorType;
import org.develnext.jphp.core.syntax.SyntaxAnalyzer;
import org.develnext.jphp.core.tokenizer.TokenMeta;
import org.develnext.jphp.core.tokenizer.TokenType;
import org.develnext.jphp.core.tokenizer.Tokenizer;
import org.develnext.jphp.core.tokenizer.token.Token;
import org.develnext.jphp.core.tokenizer.token.expr.value.ClosureStmtToken;
import org.develnext.jphp.core.tokenizer.token.expr.value.NameToken;
import org.develnext.jphp.core.tokenizer.token.stmt.*;
import php.runtime.reflection.helper.GeneratorEntity;

import java.io.IOException;
import java.util.*;

public class JvmCompiler extends AbstractCompiler {
    protected final ModuleEntity module;
    protected NamespaceStmtToken namespace;
    protected List<DeclareStmtToken> declareStmtTokens = new ArrayList<>();
    private List<ClassStmtCompiler> classes = new ArrayList<ClassStmtCompiler>();
    private Map<String, ConstantEntity> constants = new LinkedHashMap<String, ConstantEntity>();
    private long constantFetchSite;

    public long nextConstantFetchSite() {
        long site = constantFetchSite;
        constantFetchSite = Math.incrementExact(constantFetchSite);
        return site;
    }
    private Map<String, FunctionEntity> functions = new LinkedHashMap<String, FunctionEntity>();

    private final Map<FunctionEntity, Integer> declarationFunctionIds = new IdentityHashMap<>();

    protected final SyntaxAnalyzer analyzer;
    protected final List<Token> tokens;

    protected YieldExprToken lastYield;

    public JvmCompiler(Environment environment, Context context) throws IOException {
        this(environment, context, new SyntaxAnalyzer(environment, new Tokenizer(context)));
    }

    public JvmCompiler(Environment environment, Context context, SyntaxAnalyzer analyzer) {
        super(environment, context);
        this.classes = new ArrayList<>();
        this.module = new ModuleEntity(context);
        this.module.setPhpLanguageProfile(context.getPhpLanguageProfile(scope));
        this.module.setId( scope.nextModuleIndex() );

        this.tokens = analyzer.getTree();
        this.analyzer = analyzer;
    }

    public SyntaxAnalyzer getAnalyzer() {
        return analyzer;
    }

    public ConstantEntity findConstant(String fullName){
        return constants.get(fullName.toLowerCase());
    }

    public FunctionEntity findFunction(String name){
        return functions.get(name.toLowerCase());
    }

    public NamespaceStmtToken getNamespace() {
        return namespace;
    }

    public void setNamespace(NamespaceStmtToken namespace) {
        this.namespace = namespace;
    }

    public YieldExprToken getLastYield() {
        return lastYield;
    }

    public void setLastYield(YieldExprToken lastYield) {
        this.lastYield = lastYield;
    }

    public ClassEntity compileClass(ClassStmtToken clazz, boolean external) {
        ClassStmtCompiler cmp = new ClassStmtCompiler(this, clazz);
        cmp.setExternal(external);
        return cmp.compile();
    }

    public ClassEntity compileClass(ClassStmtToken clazz){
        return compileClass(clazz, false);
    }

    public FunctionEntity compileFunction(FunctionStmtToken function){
        FunctionStmtCompiler cmp = new FunctionStmtCompiler(this, function);
        FunctionEntity entity = cmp.compile();
        declarationFunctionIds.put(entity, function.getId());
        return entity;
    }

    private final java.util.Set<ConstStmtToken.Item> deferredGlobalConstants = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());

    public List<ConstantEntity> compileConstant(ConstStmtToken constant){
        List<ConstantEntity> result = new ArrayList<ConstantEntity>();
        for(ConstStmtToken.Item el : constant.items){
            ConstantEntity constantEntity = new ConstantEntity(getContext());
            constantEntity.setName(el.getFulledName());
            constantEntity.setModule(module);
            constantEntity.setTrace(el.name.toTraceInfo(context));

            ExpressionStmtCompiler expressionStmtCompiler = new ExpressionStmtCompiler(this);
            if (module.getPhpLanguageProfile() == php.runtime.common.PhpLanguageProfile.PHP_8_5) {
                org.develnext.jphp.core.compiler.jvm.statement.PhpConstantExpression.check(el.value, expressionStmtCompiler);
                deferredGlobalConstants.add(el);
                // Repeated declarations execute (and warn) at their source position.
                if (module.getDeferredGlobalConstantDeclaration(el.getFulledName()) == null)
                    module.addDeferredGlobalConstantDeclaration(constantEntity);
                continue;
            }
            Memory memory = expressionStmtCompiler.writeExpression(el.value, true, true, false);
            if (memory == null){
                throw new CompileException(
                        Messages.ERR_EXPECTED_CONST_VALUE.fetch(el.getFulledName()),
                        constant.toTraceInfo(context)
                );
            }

            constantEntity.setValue(memory);
            result.add(constantEntity);
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    public MethodEntity compileMethod(ClassStmtCompiler clazzCompiler, MethodStmtToken method, boolean external,
                                      GeneratorEntity generatorEntity) {
        MethodStmtCompiler compiler = new MethodStmtCompiler(clazzCompiler, method);
        compiler.setExternal(external);
        compiler.setGeneratorEntity(generatorEntity);
        if (!clazzCompiler.statement.isInterface()) clazzCompiler.node.methods.add(compiler.node);
        return compiler.compile();
    }

    public MethodEntity compileMethod(ClassStmtCompiler clazzCompiler, MethodStmtToken method){
        return compileMethod(clazzCompiler, method, false, null);
    }

    public void compileExpression(MethodStmtCompiler method, ExprStmtToken expression) {
        new ExpressionStmtCompiler(method, expression).compile();
    }

    public List<ExprStmtToken> process(List<Token> tokens, NamespaceStmtToken namespace){
        List<ExprStmtToken> externalCode = new ArrayList<ExprStmtToken>();

        // write constants
        for(ConstStmtToken constant : analyzer.getConstants()){
            List<ConstantEntity> items = compileConstant(constant);

            for(ConstantEntity el : items){
                if (!constants.containsKey(el.getLowerName())){
                    module.addConstant(el);

                    if (scope.findUserConstant(el.getName()) != null){
                        environment.error(el.getTrace(), ErrorType.E_ERROR,
                            Messages.ERR_CANNOT_REDECLARE_CONSTANT,
                            el.getName()
                        );
                    }

                    constants.put(el.getLowerName(), el);
                } else {
                    environment.error(el.getTrace(), ErrorType.E_ERROR,
                        Messages.ERR_CANNOT_REDECLARE_CONSTANT,
                        el.getName()
                    );
                }
            }
        }

        // write closures
        for(ClosureStmtToken closure : analyzer.getClosures()){
            ClosureEntity closureEntity = new ClosureStmtCompiler(this, closure).compile();
            module.addClosure(closureEntity);
        }

        // Deferred method bodies can emit closures only after their module slots exist.
        for (ClassStmtToken declaration : analyzer.getDeferredClasses()) {
            ClassEntity entity = compileClass(declaration);
            entity.setStatic(false);
            module.addDeferredClass(entity);
        }
        for (FunctionStmtToken function : analyzer.getFunctions()){
            if (!function.isStatic()) {
                FunctionEntity entity = compileFunction(function);
                entity.setStatic(function.isStatic());
                module.addFunction(entity);
            }
        }

        for(Token token : tokens) {
            if (token instanceof NamespaceStmtToken) {
                setNamespace((NamespaceStmtToken) token);
            } if (token instanceof ClassStmtToken){
                ClassStmtCompiler cmp = new ClassStmtCompiler(this, (ClassStmtToken)token);
                ClassEntity entity = cmp.compile();
                entity.setStatic(!entity.isUnresolvedDeclaration());
                module.addClass(entity);

                if (cmp.isInitDynamicExists() || entity.isUnresolvedDeclaration()){
                    externalCode.add(new ExprStmtToken(getEnvironment(), getContext(),
                        new ClassInitEnvironment((ClassStmtToken)token, entity)
                    ));
                }
            } else if (token instanceof FunctionStmtToken){
                FunctionEntity entity = compileFunction((FunctionStmtToken)token);
                entity.setStatic(true);
                module.addFunction(entity);
                functions.put(entity.getLowerName(), entity);
            } else if (token instanceof ConstStmtToken) {
                for (ConstStmtToken.Item item : ((ConstStmtToken) token).items) if (deferredGlobalConstants.contains(item))
                    externalCode.add(new ExprStmtToken(getEnvironment(), getContext(), new ConstantInitEnvironment(item)));
            } else if (token instanceof ExprStmtToken){
                externalCode.add((ExprStmtToken)token);
            } else
                externalCode.add(new ExprStmtToken(getEnvironment(), getContext(), token));
        }

        return externalCode;
    }

    @Override
    public ModuleEntity compile(boolean autoRegister) {
        this.classes = new ArrayList<>();
        module.setInternalName("$php_module_m" + UUID.randomUUID().toString().replace("-", ""));

        // Functions and closures must capture the module's strictness before emitting their bodies.
        declareStmtTokens.clear();
        for (Token token : tokens) if (token instanceof DeclareStmtToken) declareStmtTokens.add((DeclareStmtToken)token);
        for (DeclareStmtToken declare : declareStmtTokens) {
            String name = declare.getName().getName();

            ExpressionStmtCompiler expressionStmtCompiler = new ExpressionStmtCompiler(this);

            Memory value = expressionStmtCompiler.tryCalculateExpression(declare.getValue());

            switch (name.toLowerCase()) {
                case "strict_types":
                    module.setStrictTypes(value.toBoolean());
                    break;
                default:
                    environment.error(declare.getValue().getMeta().toTraceInfo(context), ErrorType.E_ERROR,
                            Messages.ERR_INVALID_DECLARE_CONSTANT,
                            name
                    );
            }
        }

        int anonymousIndex=0;
        for (ClassStmtToken anonymous : analyzer.getAnonymousClasses()) anonymous.setName(new NameToken(
                TokenMeta.of("class@anonymous\0" + module.getInternalName() + ":" + anonymousIndex++)));
        List<ExprStmtToken> externalCode = process(tokens, analyzer.newNamespace());
        // __defineFunction uses the declaration's AST id; PHB preserves this same positional table.
        // Dynamic declarations compile first, so neither compilation order nor PHP name is its identity.
        var declarationFunctions = new ArrayList<>(module.getFunctions());
        declarationFunctions.sort(Comparator.comparingInt(declarationFunctionIds::get));
        module.getFunctions().clear();
        module.getFunctions().addAll(declarationFunctions);
        for (ClassStmtToken anonymous : analyzer.getAnonymousClasses()) {
            ClassEntity entity = compileClass(anonymous);
            entity.setStatic(anonymous.getExtend() == null && anonymous.getImplement() == null && anonymous.getUses().isEmpty());
            module.addClass(entity);
        }
        NamespaceStmtToken namespace = analyzer.newNamespace();

        ClassStmtToken token = new ClassStmtToken(TokenMeta.of( module.getName() ));
        token.setFinal(true);
        token.setName(new NameToken(TokenMeta.of( module.getInternalName() )));
        token.setNamespace(namespace);

        MethodStmtToken methodToken = new MethodStmtToken( token.getMeta() );
        methodToken.setFinal(true);
        methodToken.setClazz(token);
        methodToken.setModifier(Modifier.PUBLIC);
        methodToken.setStatic(true);
        methodToken.setDynamicLocal(true);
        methodToken.setName((NameToken) Token.of("__include"));
        methodToken.setArguments(new ArrayList<ArgumentStmtToken>());
        methodToken.setLocal(analyzer.getScope().getVariables());
        methodToken.setLabels(analyzer.getScope().getLabels());

        methodToken.setBody(BodyStmtToken.of(externalCode));

        token.setMethods(Arrays.asList(methodToken));

        ClassStmtCompiler classStmtCompiler = new ClassStmtCompiler(this, token);
        classStmtCompiler.setExternal(true);
        classStmtCompiler.setSystem(true);

        module.setData(classStmtCompiler.compile().getData());
        // Factories embed lexical generator ids. Compilation visits closures and dynamic
        // declarations first; append order therefore cannot be the runtime lookup table.
        canonicalizeGeneratorCatalog();
        module.validateMethodSignatures(environment);
        module.recordSourceCompilerAbi(ModuleDumper.COMPILER_ABI_VERSION);
        // ..

        if (autoRegister)
            scope.addUserModule(module);

        return module;
    }

    /** Source-only: loaded PHB generator ids are global class ids, not lexical ordinals. */
    private void canonicalizeGeneratorCatalog() {
        if (module.isCompiledArtifact())
            throw new IllegalStateException("Cannot reorder a loaded generator catalog");
        var ordered = new TreeMap<Long, GeneratorEntity>();
        for (GeneratorEntity generator : module.getGenerators()) {
            long ordinal = generator.getId();
            if (ordinal < 0 || generator.getModule() != module
                    || !Objects.equals(generator.getCompiledInternalName(), module.getInternalName() + "_generator" + ordinal))
                throw new IllegalStateException("Invalid local generator identity");
            GeneratorEntity previous = ordered.putIfAbsent(ordinal, generator);
            if (previous != null && previous != generator)
                throw new IllegalStateException("Distinct generators share a lexical ordinal");
        }
        long ordinal = 0;
        for (long actual : ordered.keySet()) {
            if (actual != ordinal++) throw new IllegalStateException("Generator catalog has a lexical gap");
        }
        // Validate the entire replacement before changing the observable table.
        module.getGenerators().clear();
        module.getGenerators().addAll(ordered.values());
    }

    public ModuleEntity getModule() {
        return module;
    }

    public List<ClassStmtCompiler> getClasses() {
        return classes;
    }

    public String getSourceFile() {
        return context.getFileName();
    }

    public static class ConstantInitEnvironment extends StmtToken {
        public final ConstStmtToken.Item item;
        public ConstantInitEnvironment(ConstStmtToken.Item item) { super(item.name.getMeta(), TokenType.T_J_CUSTOM); this.item = item; }
    }

    public class ClassInitEnvironment extends StmtToken {
        protected final ClassStmtToken token;
        protected final ClassEntity entity;

        public ClassInitEnvironment(ClassStmtToken token, ClassEntity clazz) {
            super(token.getMeta(), TokenType.T_J_CUSTOM);
            this.token = token;
            this.entity = clazz;
        }

        public ClassStmtToken getToken() {
            return token;
        }

        public ClassEntity getEntity() {
            return entity;
        }
    }
}
