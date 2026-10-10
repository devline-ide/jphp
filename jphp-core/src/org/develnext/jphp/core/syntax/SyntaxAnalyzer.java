package org.develnext.jphp.core.syntax;

import org.develnext.jphp.core.syntax.generators.*;
import org.develnext.jphp.core.syntax.generators.manually.BodyGenerator;
import org.develnext.jphp.core.syntax.generators.manually.SimpleExprGenerator;
import org.develnext.jphp.core.tokenizer.Tokenizer;
import org.develnext.jphp.core.tokenizer.TokenMeta;
import org.develnext.jphp.core.tokenizer.token.CommentToken;
import org.develnext.jphp.core.tokenizer.token.Token;
import org.develnext.jphp.core.tokenizer.token.SemicolonToken;
import org.develnext.jphp.core.tokenizer.token.expr.BraceExprToken;
import org.develnext.jphp.core.tokenizer.token.expr.ValueExprToken;
import org.develnext.jphp.core.tokenizer.token.expr.value.ClosureStmtToken;
import org.develnext.jphp.core.tokenizer.token.expr.value.FulledNameToken;
import org.develnext.jphp.core.tokenizer.token.expr.value.NameToken;
import org.develnext.jphp.core.tokenizer.token.stmt.*;
import php.runtime.env.Context;
import php.runtime.env.Environment;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.*;

import static org.develnext.jphp.core.tokenizer.token.stmt.NamespaceUseStmtToken.UseType.CONSTANT;

public class SyntaxAnalyzer {
    private Tokenizer tokenizer;
    private List<Token> tokens;
    private List<Token> tree;

    private List<Generator> generators;
    private final Map<Class<? extends Generator>, Generator> map = new HashMap<Class<? extends Generator>, Generator>();

    private NamespaceStmtToken namespace = NamespaceStmtToken.getDefault();
    private final Set<NamespaceStmtToken> seededNamespaces = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<NamespaceUseStmtToken> implicitUses = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Map<String, Set<String>> declaredClassNames = new HashMap<>();
    private ClassStmtToken clazz;
    private FunctionStmtToken function;
    private Stack<FunctionStmtToken> closureStack;

    private Stack<Scope> scopeStack;
    private Stack<Scope> rootScopeStack = new Stack<Scope>();

    //private Stack<Set<VariableExprToken>> localStack;
    //private Stack<Set<VariableExprToken>> rootLocalStack = new Stack<Set<VariableExprToken>>();

    private Map<String, ClassStmtToken> classes;
    private List<FunctionStmtToken> functions;
    private List<ConstStmtToken> constants;
    private List<ClosureStmtToken> closures;

    private Environment environment;

    private int generatorSize = 0;

    public SyntaxAnalyzer(Environment environment, Tokenizer tokenizer){
        this(environment, tokenizer, null);
    }

    public void reset(Environment environment, Tokenizer tokenizer){
        removeScope();
        //removeLocalScope();

        generatorSize = 0;

        tokenizer.reset();
        this.environment = environment;
        this.tokenizer = tokenizer;

        this.function = null;
        classes.clear();
        functions.clear();
        constants.clear();
        closures.clear();
        closureStack.clear();

        tokens.clear();
        tree.clear();
        //localStack.clear();

        scopeStack.clear();

        addScope(true);
        //addLocalScope(true);
        process();
    }

    public SyntaxAnalyzer(Environment environment, Tokenizer tokenizer, FunctionStmtToken function) {
        if (tokenizer != null)
            tokenizer.reset();
        this.environment = environment;
        this.tokenizer = tokenizer;

        this.function = function;
        classes = new LinkedHashMap<String, ClassStmtToken>();
        functions = new ArrayList<FunctionStmtToken>();
        constants = new ArrayList<ConstStmtToken>();
        closures = new ArrayList<ClosureStmtToken>();
        closureStack = new Stack<FunctionStmtToken>();

        tokens = new LinkedList<Token>();
        tree = new ArrayList<Token>();
        //localStack = new Stack<Set<VariableExprToken>>();
        scopeStack = new Stack<Scope>();

        generators = new ArrayList<Generator>(50);

        generators.add(new NamespaceGenerator(this));
        generators.add(new UseGenerator(this));
        generators.add(new DeclareGenerator(this));
        generators.add(new ClassGenerator(this));
        generators.add(new ConstGenerator(this));
        generators.add(new FunctionGenerator(this));
        generators.add(new LambdaGenerator(this));
        generators.add(new TryCatchGenerator(this));
        generators.add(new ThrowGenerator(this));
        generators.add(new NameGenerator(this));

        // non-automatic
        generators.add(new SimpleExprGenerator(this));
        generators.add(new BodyGenerator(this));

        generators.add(new ExprGenerator(this));
        for (Generator generator : generators)
            map.put(generator.getClass(), generator);

        addScope(true);
        //addLocalScope(true);
        if (tokenizer != null)
            process();
    }

    public Environment getEnvironment() {
        return environment;
    }

    public php.runtime.common.PhpLanguageProfile getPhpLanguageProfile() {
        Context context = tokenizer == null ? null : tokenizer.getContext();
        if (context != null) {
            return context.getPhpLanguageProfile(environment == null ? null : environment.getScope());
        }
        return environment == null ? php.runtime.common.PhpLanguageProfile.JPHP_LEGACY
                : environment.getScope().getPhpLanguageProfile();
    }

    public void registerClass(ClassStmtToken clazz){
        classes.put(php.runtime.common.PhpClassNames.key(clazz.getFulledName(), getPhpLanguageProfile()), clazz);

        for (MethodStmtToken method : clazz.getMethods()) {
            if (method.isGenerator()) {
                method.setGeneratorId(generatorSize++);
            }
        }
    }

    public void registerFunction(FunctionStmtToken function){
        if (function.getId() <= functions.size()){
            function.setId(functions.size());

            if (function.isGenerator()) {
                function.setGeneratorId(generatorSize++);
            }

            functions.add(function);
        }
    }

    public void registerConstant(ConstStmtToken constant){
        constants.add(constant);
    }

    public void registerClosure(ClosureStmtToken closure){
        closure.setId(closures.size());
        if (closure.getFunction().isGenerator()) {
            closure.getFunction().setGeneratorId(generatorSize++);
        }

        closures.add(closure);
    }

    public Collection<ClassStmtToken> getClasses() {
        return classes.values();
    }

    public Collection<FunctionStmtToken> getFunctions() {
        return functions;
    }

    public Collection<ConstStmtToken> getConstants(){
        return constants;
    }

    public Collection<ClosureStmtToken> getClosures(){
        return closures;
    }

    public ClassStmtToken findClass(String name){
        return classes.get(php.runtime.common.PhpClassNames.key(name, getPhpLanguageProfile()));
    }

    protected void process(){
        getContext().freezeImplicitClassImports();
        seededNamespaces.clear();
        implicitUses.clear();
        declaredClassNames.clear();
        tokenizer.reset();
        tokenizer.setPhpLanguageProfile(getPhpLanguageProfile());
        tree.clear();

        Token token;
        while ((token = tokenizer.nextToken()) != null){
            if (token instanceof CommentToken){
                if (((CommentToken) token).getKind() != CommentToken.Kind.DOCTYPE)
                    continue;
            }
            tokens.add(token);
        }

        /*if (tokenizer.hasDirective("mode")){
            Directive mode = tokenizer.getDirective("mode");
            try {
                this.langMode = LangMode.valueOf(mode.value.toUpperCase());
            } catch (IllegalArgumentException e){
                environment.warning(
                        mode.trace, "Invalid value '%s' for directive 'mode'", mode.value
                );
            }
        }*/

        collectDeclaredClassNames();
        namespace = newNamespace();
        ListIterator<Token> iterator = tokens.listIterator();
        tree = process(iterator);
    }

    protected void registerToken(Token token){
        if (token instanceof ClassStmtToken)
            registerClass((ClassStmtToken)token);
        else if (token instanceof FunctionStmtToken)
            registerFunction((FunctionStmtToken)token);
        else if (token instanceof ConstStmtToken)
            registerConstant((ConstStmtToken)token);
    }

    public List<Token> process(ListIterator<Token> iterator){
        List<Token> result = new ArrayList<Token>();
        while (iterator.hasNext()){
            Token gen = processNext(iterator);
            if (gen instanceof NamespaceStmtToken) {
                List<Token> tree = ((NamespaceStmtToken) gen).getTree();
                ((NamespaceStmtToken) gen).setTree(null);

                result.add(gen);
                registerToken(gen);

                result.addAll(tree);

                if (!((NamespaceStmtToken) gen).isTokenRegistered()) {
                    for (Token el : tree) {
                        registerToken(el);
                    }
                }
            } else {
                result.add(gen);
                registerToken(gen);
            }
        }
        return result;
    }

    public Token processNext(ListIterator<Token> iterator){
        Token current = iterator.next();
        Token gen = generateToken(current, iterator);
        return (gen == null ? current : gen);
    }

    public Scope addScope(boolean isRoot) {
        Scope scope = new Scope(scopeStack.empty() ? null : scopeStack.peek());

        scopeStack.push(scope);
        if (isRoot)
            rootScopeStack.push(scope);
        return scope;
    }

    public Scope addScope(){
        return addScope(false);
    }

    public Scope removeScope() {
        Scope scope = getScope();
        if (rootScopeStack.peek() == scope)
            rootScopeStack.pop();
        else
            rootScopeStack.peek().appendScope(scope);

        return scopeStack.pop();
    }

    public Scope getScope() {
        return scopeStack.peek();
    }

    /*
    public Set<VariableExprToken> addLocalScope(){
        return addLocalScope(false);
    }

    public Set<VariableExprToken> addLocalScope(boolean isRoot){
        Set<VariableExprToken> local = new HashSet<VariableExprToken>();
        localStack.push(local);
        if (isRoot)
            rootLocalStack.push(local);
        return local;
    }

    public Set<VariableExprToken> removeLocalScope(){
        Set<VariableExprToken> scope = getLocalScope();
        if (rootLocalStack.peek() == scope){
            rootLocalStack.pop();
        } else
            rootLocalStack.peek().addAll(getLocalScope());
        return localStack.pop();
    }

    public Set<VariableExprToken> getLocalScope(){
        return localStack.peek();
    }  */

    @SuppressWarnings("unchecked")
    public <T extends Generator> T generator(Class<T> clazz){
        Generator generator = map.get(clazz);
        if (generator == null)
            throw new AssertionError("Generator '"+clazz.getName()+"' not found");

        if (generator.isSingleton())
            return (T) generator;

        Constructor<T> constructor = null;
        try {
            constructor = clazz.getConstructor(SyntaxAnalyzer.class);
            return constructor.newInstance(this);
        } catch (NoSuchMethodException e) {
            throw new RuntimeException(e);
        } catch (InvocationTargetException e) {
            throw new RuntimeException(e);
        } catch (InstantiationException e) {
            throw new RuntimeException(e);
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }

    public Token generateToken(Token current, ListIterator<Token> iterator){
        Token gen = null;

        for(Generator generator : generators){
            if (!generator.isAutomatic())
                continue;

            gen = generator.getToken(current, iterator);
            if (gen != null)
                break;
        }

        return gen;
    }

    public List<Token> getTree(){
        return tree;
    }

    public Context getContext(){
        return tokenizer.getContext();
    }

    public NamespaceStmtToken getNamespace() {
        return namespace;
    }

    public NamespaceStmtToken newNamespace() {
        NamespaceStmtToken result = NamespaceStmtToken.getDefault();
        seedNamespace(result);
        return result;
    }

    private void seedNamespace(NamespaceStmtToken namespace) {
        if (!seededNamespaces.add(namespace) || tokenizer == null) return;
        String namespaceName = namespace.getName() == null ? "" : namespace.getName().toName();
        Set<String> declared = declaredClassNames.get(Context.normalizeClassImportAlias(namespaceName));
        for (String name : getContext().getImplicitClassImports().values()) {
            String alias = name.substring(name.lastIndexOf('\\') + 1);
            if (declared != null && declared.contains(Context.normalizeClassImportAlias(alias))) continue;
            NamespaceUseStmtToken use = new NamespaceUseStmtToken(TokenMeta.empty());
            use.setName(FulledNameToken.valueOf(name.split("\\\\")));
            use.setUseType(NamespaceUseStmtToken.UseType.CLASS);
            namespace.getUses().add(use);
            implicitUses.add(use);
        }
    }

    /**
     * Inventory named declarations before resolution so forward references also prefer
     * user classes/interfaces/traits. This reads lexical tokens, never PHP source text.
     * Declarations belong to their namespace across repeated namespace blocks; explicit
     * use declarations still belong to their individual block. Enums remain unsupported.
     */
    private void collectDeclaredClassNames() {
        if (getContext().getImplicitClassImports().isEmpty()) return;
        List<Token> source = new ArrayList<>(tokens); // tokens is a LinkedList; keep this pass linear.
        String namespaceName = "";
        int depth = 0;
        int namespaceDepth = -1;
        for (int i = 0; i < source.size(); i++) {
            Token token = source.get(i);
            if (token instanceof CommentToken) continue;
            int nextIndex = nextSignificantToken(source, i + 1);
            Token next = nextIndex < source.size() ? source.get(nextIndex) : null;
            if (token instanceof NamespaceStmtToken && depth == 0) {
                String candidate = "";
                Token delimiter = next;
                if (next instanceof NameToken) {
                    candidate = ((NameToken) next).getName();
                    int delimiterIndex = nextSignificantToken(source, nextIndex + 1);
                    delimiter = delimiterIndex < source.size() ? source.get(delimiterIndex) : null;
                }
                if (delimiter instanceof SemicolonToken || delimiter instanceof BraceExprToken
                        && ((BraceExprToken) delimiter).isBlockOpened()) {
                    namespaceName = candidate;
                    namespaceDepth = delimiter instanceof BraceExprToken ? depth + 1 : -1;
                }
            }
            if ((token instanceof ClassStmtToken || token instanceof InterfaceStmtToken || token instanceof TraitStmtToken)
                    && next instanceof NameToken && !(next instanceof FulledNameToken)) {
                declaredClassNames.computeIfAbsent(Context.normalizeClassImportAlias(namespaceName), ignored -> new HashSet<>())
                        .add(Context.normalizeClassImportAlias(((NameToken) next).getName()));
            }
            if (token instanceof BraceExprToken) {
                BraceExprToken brace = (BraceExprToken) token;
                if (brace.isBlockOpened()) depth++;
                else if (brace.isBlockClosed()) {
                    if (depth == namespaceDepth) {
                        namespaceName = "";
                        namespaceDepth = -1;
                    }
                    depth--;
                }
            }
        }
    }

    private static int nextSignificantToken(List<Token> source, int index) {
        while (index < source.size() && source.get(index) instanceof CommentToken) index++;
        return index;
    }

    /** Explicit CLASS use declarations override only the matching implicit alias. */
    public void registerNamespaceUse(NamespaceStmtToken namespace, NamespaceUseStmtToken use) {
        if (use.getUseType() == NamespaceUseStmtToken.UseType.CLASS) {
            String alias = use.getAs() == null ? use.getName().getLastName().getName() : use.getAs().getName();
            String normalizedAlias = Context.normalizeClassImportAlias(alias);
            namespace.getUses().removeIf(existing -> implicitUses.contains(existing)
                    && Context.normalizeClassImportAlias(existing.getName().getLastName().getName()).equals(normalizedAlias));
        }
        namespace.getUses().add(use);
    }

    public void setNamespace(NamespaceStmtToken namespace) {
        seedNamespace(namespace);
        this.namespace = namespace;
    }

    public ClassStmtToken getClazz() {
        return clazz;
    }

    public void setClazz(ClassStmtToken clazz) {
        this.clazz = clazz;
    }

    public FunctionStmtToken getFunction(boolean absolute) {
        if (!absolute){
            FunctionStmtToken func = peekClosure();
            if (func != null)
                return func;
        }
        return function;
    }

    public FunctionStmtToken getFunction() {
        return getFunction(false);
    }

    public void setFunction(FunctionStmtToken function) {
        this.function = function;
    }

    public void pushClosure(FunctionStmtToken closure) {
        closureStack.push(closure);
    }

    public FunctionStmtToken peekClosure() {
        if (closureStack.empty())
            return null;
        else
            return closureStack.peek();
    }

    public FunctionStmtToken popClosure(){
        return closureStack.pop();
    }

    public ValueExprToken getRealName(ValueExprToken value, NamespaceUseStmtToken.UseType useType){
        if (value == null)
            return null;

        if (value instanceof NameToken)
            return getRealName((NameToken)value, useType);
        else
            return value;
    }

    public FulledNameToken getRealName(NameToken what, NamespaceUseStmtToken.UseType useType) {
        return getRealName(what, namespace, useType);
    }

    public FulledNameToken getRealName(NameToken what) {
        return getRealName(what, NamespaceUseStmtToken.UseType.CLASS);
    }

    public static FulledNameToken getRealName(NameToken what, NamespaceStmtToken namespace) {
        return getRealName(what, namespace, NamespaceUseStmtToken.UseType.CLASS);
    }

    public static FulledNameToken getRealName(NameToken what, NamespaceStmtToken namespace, NamespaceUseStmtToken.UseType useType) {
        if (what instanceof FulledNameToken) {
            FulledNameToken fulledNameToken = (FulledNameToken) what;

            if (fulledNameToken.isProcessed(useType)) {
                return fulledNameToken;
            } else {
                if (fulledNameToken.isAnyProcessed()) {
                    what = (fulledNameToken).getLastName();
                }
            }
        }

        String name = what.getName();
        List<NameToken> classSuffix = Collections.emptyList();
        if (useType == NamespaceUseStmtToken.UseType.CLASS && what instanceof FulledNameToken) {
            List<NameToken> parts = ((FulledNameToken) what).getNames();
            if (parts.size() > 1) {
                name = parts.get(0).getName();
                classSuffix = parts.subList(1, parts.size());
                if (Context.normalizeClassImportAlias(name).equals("namespace")) {
                    List<NameToken> relative = namespace == null || namespace.getName() == null
                            ? new ArrayList<>() : new ArrayList<>(namespace.getName().getNames());
                    relative.addAll(classSuffix);
                    FulledNameToken result = new FulledNameToken(what.getMeta(), relative);
                    result.setProcessed(useType);
                    return result;
                }
            }
        }

        String normalizedClassAlias = useType == NamespaceUseStmtToken.UseType.CLASS
                ? Context.normalizeClassImportAlias(name) : null;

        // check name in uses
        if (namespace != null) {
            for (NamespaceUseStmtToken use : namespace.getUses()) {
                if (use.getUseType() != useType) {
                    continue;
                }

                if (useType == NamespaceUseStmtToken.UseType.CLASS) {
                    String alias = use.getAs() == null ? use.getName().getLastName().getName() : use.getAs().getName();
                    if (normalizedClassAlias.equals(Context.normalizeClassImportAlias(alias))) {
                        List<NameToken> resolved = new ArrayList<>(use.getName().getNames());
                        resolved.addAll(classSuffix);
                        FulledNameToken result = new FulledNameToken(what.getMeta(), resolved);
                        result.setProcessed(useType);
                        return result;
                    }
                    continue;
                }

                if (use.getAs() == null) {
                    String string = use.getName().getLastName().getName();

                    if ((useType == CONSTANT && name.equals(string)) || (useType != CONSTANT && name.equalsIgnoreCase(string))) {
                        FulledNameToken t = new FulledNameToken(use.getName());
                        t.setProcessed(useType);
                        return t;
                    }
                } else {
                    String string = use.getAs().getName();

                    if ((useType == CONSTANT && name.equals(string)) || (useType != CONSTANT && name.equalsIgnoreCase(string))) {
                        FulledNameToken t = new FulledNameToken(use.getName());
                        t.setProcessed(useType);
                        return t;
                    }
                }
            }
        }

        List<NameToken> names = namespace == null || namespace.getName() == null
                ? new ArrayList<NameToken>()
                : new ArrayList<NameToken>(namespace.getName().getNames());

        if (what instanceof FulledNameToken)
            names.addAll(((FulledNameToken) what).getNames());
        else
            names.add(what);

        FulledNameToken t = new FulledNameToken(what.getMeta(), names);
        t.setProcessed(useType);
        return t;
    }
}
