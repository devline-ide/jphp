package org.develnext.jphp.core.syntax.generators;

import org.develnext.jphp.core.syntax.SyntaxAnalyzer;
import org.develnext.jphp.core.tokenizer.token.Token;
import org.develnext.jphp.core.tokenizer.token.expr.value.NameToken;
import php.runtime.exceptions.support.ErrorType;
import php.runtime.reflection.support.PhpTypeDeclaration;
import php.runtime.common.PhpClassNames;
import php.runtime.common.PhpLanguageProfile;
import java.util.*;

/** Declaration grammar and structural redundancy checks, without autoloading class names. */
final class PhpTypeDeclarationParser {
    private final SyntaxAnalyzer analyzer;
    private final List<Token> tokens;
    private int cursor;
    private final String constant;
    private boolean functionType, returnType, propertyType;
    private PhpTypeDeclarationParser(SyntaxAnalyzer analyzer, List<Token> tokens, String constant) {
        this.analyzer = analyzer; this.tokens = tokens; this.constant = constant;
    }
    static PhpTypeDeclaration parse(SyntaxAnalyzer analyzer, List<Token> tokens, String constant) {
        return new PhpTypeDeclarationParser(analyzer, tokens, constant).parse();
    }
    static PhpTypeDeclaration parseFunction(SyntaxAnalyzer analyzer, List<Token> tokens, boolean returns) {
        var parser = new PhpTypeDeclarationParser(analyzer, tokens, "");
        parser.functionType = true; parser.returnType = returns;
        return parser.parse();
    }
    static PhpTypeDeclaration parseProperty(SyntaxAnalyzer analyzer, List<Token> tokens, String name) {
        var parser = new PhpTypeDeclarationParser(analyzer, tokens, name); parser.propertyType = true;
        return parser.parse();
    }
    private void propertyVariable(String suffix) {
        analyzer.getEnvironment().error(at().toTraceInfo(analyzer.getContext()), ErrorType.E_PARSE,
                "syntax error, unexpected variable \"$" + constant.substring(constant.lastIndexOf("::$") + 3) + "\"" + suffix);
    }
    private Token at() {return tokens.get(Math.min(cursor, tokens.size()-1));}
    private String word() {return cursor < tokens.size() ? tokens.get(cursor).getWord() : "";}
    private void error(String message) {analyzer.getEnvironment().error(at().toTraceInfo(analyzer.getContext()), ErrorType.E_COMPILE_ERROR, message);}
    private void syntax(String token, String suffix) {
        analyzer.getEnvironment().error(at().toTraceInfo(analyzer.getContext()), ErrorType.E_PARSE,
                "syntax error, unexpected token \"" + token + "\"" + suffix);
    }
    private PhpTypeDeclaration parse() {
        if (propertyType && tokens.getLast().getWord().equals("&")) syntax("&", ", expecting variable");
        boolean nullable = word().equals("?"); if (nullable) cursor++;
        List<List<String>> arms = new ArrayList<>();
        arms.add(branch());
        while (word().equals("|")) {cursor++; arms.add(branch());}
        if (cursor != tokens.size()) syntax(word(), propertyType ? ", expecting variable" : "");
        if (nullable) {
            if (propertyType && arms.getFirst().size() > 1) syntax("&", ", expecting variable");
            if (arms.size() != 1 || arms.getFirst().size() != 1) syntax("|", "");
            String name = arms.getFirst().getFirst();
            if (name.equals("mixed")) error("Type mixed cannot be marked as nullable since mixed already includes null");
            if (name.equals("null")) error("null cannot be marked as nullable");
            if (functionType && Set.of("void", "never").contains(name)) error(name + " cannot be marked as nullable");
            if (propertyType && Set.of("void", "never").contains(name))
                error((name.equals("void") ? "Void" : name) + " can only be used as a standalone type");
            arms.add(List.of("null"));
        }
        if (tokens.getFirst().getWord().equals("(") && arms.size() == 1) {
            if (propertyType) propertyVariable(", expecting \"|\"");
            analyzer.getEnvironment().error(at().toTraceInfo(analyzer.getContext()), ErrorType.E_PARSE,
                    "syntax error, unexpected identifier \"" + (functionType ? (returnType ? "{" : "$x") : constant.substring(constant.lastIndexOf("::") + 2)) + "\", expecting \"|\"");
        }
        validate(arms);
        PhpTypeDeclaration type = new PhpTypeDeclaration(arms);
        for (String forbidden : functionType ? (returnType ? List.<String>of() : List.of("void", "never")) : List.of("void", "never", "callable")) if (type.has(forbidden))
            error(functionType ? forbidden + " cannot be used as a parameter type" : (propertyType ? "Property " : "Class constant ") + constant + " cannot have type " + forbidden);
        return type;
    }
    private List<String> branch() {
        boolean group = word().equals("("); if (group) cursor++;
        List<String> names = new ArrayList<>(); names.add(name());
        while (word().equals("&")) {cursor++; names.add(name());}
        if (group) {
            if (names.size() < 2) syntax(word(), ", expecting token \"&\"");
            if (!word().equals(")")) syntax(word(), ""); cursor++;
        } else if (names.size() > 1 && (!word().isEmpty() || cursor > 0 && tokens.stream().anyMatch(t -> t.getWord().equals("|"))))
            syntax(word().isEmpty() ? "&" : word(), propertyType ? ", expecting variable" : "");
        for (String name : names) if (names.size() > 1 && (PhpTypeDeclaration.BUILTINS.contains(name)
                || name.equals("self") || name.equals("parent") || name.equals("static"))) error("Type " + name + " cannot be part of an intersection type");
        return names;
    }
    private boolean modernClassNames() {
        return analyzer.getPhpLanguageProfile() == PhpLanguageProfile.PHP_8_5;
    }
    private String classKey(String name) {
        return modernClassNames() ? PhpClassNames.canonicalKey(name) : name.toLowerCase(Locale.ROOT);
    }
    private Set<String> typeNames(List<String> names) {
        if (!modernClassNames()) {
            var result = new TreeSet<String>(String.CASE_INSENSITIVE_ORDER); result.addAll(names); return result;
        }
        var result = new HashSet<String>();
        for (String name : names) result.add(PhpClassNames.canonicalKey(name));
        return result;
    }
    private boolean lowerStatic(String word) {
        return modernClassNames() ? PhpClassNames.canonicalKey(word).equals("static") : word.equalsIgnoreCase("static");
    }
    private String name() {
        Token token = at(); String word = word(); cursor++;
        if (propertyType && word.isEmpty()) propertyVariable("");
        if (propertyType && lowerStatic(word)) syntax(word, "");
        if (word.isEmpty() || word.equals("|") || word.equals("&") || word.equals("(") || word.equals(")") || word.equals("?")) {
            syntax(word.isEmpty() ? "=" : word, ""); return "";
        }
        String lower = classKey(word);
        if (lower.startsWith("\\") && lower.indexOf('\\', 1) < 0) {
            String simple = lower.substring(1);
            if (simple.equals("array") || simple.equals("callable")) error("Cannot use \"" + simple + "\" as a type name as it is reserved");
            if (PhpTypeDeclaration.BUILTINS.contains(simple)) error("Type declaration '" + simple + "' must be unqualified");
            if (Set.of("static", "self", "parent").contains(simple)) error("'" + word + "' is an invalid class name");
        }
        if (lower.indexOf('\\') >= 0) {
            String tail = lower.substring(lower.lastIndexOf('\\') + 1);
            if (PhpTypeDeclaration.BUILTINS.contains(tail)) error("Cannot use \"" + word.replaceFirst("^\\\\", "") + "\" as a type name as it is reserved");
            if (Set.of("static", "self", "parent").contains(tail)) error("'" + word + "' is an invalid class name");
        }
        if (!(token instanceof NameToken) && !token.isNamedToken() && !PhpTypeDeclaration.BUILTINS.contains(lower) && !Set.of("static", "self", "parent").contains(lower)) syntax(word, "");
        if (functionType && lower.equals("static")) {
            if (!returnType) syntax(word, "");
            if (analyzer.getClazz() == null && analyzer.peekClosure() == null) error("Cannot use \"static\" when no class scope is active");
        }
        if (functionType && Set.of("self", "parent").contains(lower)) {
            if (analyzer.peekClosure() != null) return lower;
            if (analyzer.getClazz() == null) error("Cannot use \"" + lower + "\" when no class scope is active");
        }
        if (PhpTypeDeclaration.BUILTINS.contains(lower) || lower.equals("static")) return lower;
        if (lower.equals("self")) return analyzer.getClazz().isTrait() ? "self" : analyzer.getClazz().getFulledName();
        if (lower.equals("parent")) {
            if (analyzer.getClazz().isTrait()) return "parent";
            if (analyzer.getClazz().getExtend() == null) error("Cannot use \"parent\" when current class scope has no parent");
            return analyzer.getClazz().getExtend().getName().getName();
        }
        NameToken name = token instanceof NameToken ? (NameToken) token : NameToken.valueOf(word);
        return analyzer.getRealName(name).getName();
    }
    private void validate(List<List<String>> arms) {
        for (List<String> arm : arms) {
            Set<String> names = new HashSet<>();
            for (String name : arm) if (!names.add(classKey(name))) error("Duplicate type " + name + " is redundant");
        }
        if (arms.size() == 1) return;
        for (List<String> arm : arms) if (arm.size() == 1 && Set.of("mixed", "void", "never").contains(arm.getFirst()))
            error("Type " + arm.getFirst() + " can only be used as a standalone type");
        Set<String> simple = new HashSet<>();
        for (var arm : arms) if (arm.size() == 1) simple.add(arm.getFirst());
        for (String literal : List.of("false", "true")) if (simple.contains("bool") && simple.contains(literal)) error("Duplicate type " + literal + " is redundant");
        if (simple.contains("false") && simple.contains("true")) error("Type contains both true and false, bool must be used instead");
        if (simple.contains("iterable")) {
            if (simple.contains("array")) error("Duplicate type array is redundant");
            if (simple.stream().anyMatch(n -> modernClassNames() ? PhpClassNames.canonicalKey(n).equals("traversable") : n.equalsIgnoreCase("Traversable"))) error("Duplicate type Traversable is redundant");
        }
        if (simple.contains("object") && arms.stream().anyMatch(a -> a.size() > 1 || !PhpTypeDeclaration.BUILTINS.contains(a.getFirst())))
            error("Type " + new PhpTypeDeclaration(arms).getSignature() + " contains both object and a class type, which is redundant");
        for (int i=0; i<arms.size(); i++) for (int j=0; j<i; j++) {
            var current = typeNames(arms.get(i));
            var previous = typeNames(arms.get(j));
            String a = String.join("&", arms.get(i)), b = String.join("&", arms.get(j));
            if (current.equals(previous)) {
                if (current.size() == 1) error("Duplicate type " + a + " is redundant");
                else error("Type " + a + " is redundant with type " + b);
            } else if (current.containsAll(previous)) error("Type " + a + " is redundant as it is more restrictive than type " + b);
            else if (previous.containsAll(current)) error("Type " + b + " is redundant as it is more restrictive than type " + a);
        }
    }
}
