package org.develnext.jphp.core.compiler.jvm;

import org.develnext.jphp.core.compiler.jvm.statement.ScalarCallResolver;
import org.develnext.jphp.core.syntax.SyntaxAnalyzer;
import org.develnext.jphp.core.tokenizer.TokenMeta;
import org.develnext.jphp.core.tokenizer.Tokenizer;
import org.develnext.jphp.core.tokenizer.token.expr.value.CallArgument;
import org.develnext.jphp.core.tokenizer.token.expr.value.CallExprToken;
import org.develnext.jphp.core.tokenizer.token.expr.value.NameToken;
import org.develnext.jphp.core.tokenizer.token.expr.value.SelfExprToken;
import org.develnext.jphp.core.tokenizer.token.expr.value.StaticAccessExprToken;
import org.develnext.jphp.core.tokenizer.token.expr.value.StaticExprToken;
import org.develnext.jphp.core.tokenizer.token.stmt.ClassStmtToken;
import org.develnext.jphp.core.tokenizer.token.stmt.ExprStmtToken;
import org.junit.Test;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.Context;
import php.runtime.env.Environment;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/** Resolver boundary controls; end-to-end primitive invocation is checked separately. */
public class ScalarCallResolverTest extends JvmCompilerCase {
    @Test public void lexicalSelfAndExactQualifiedNamesResolveWithClosedDispatch() throws Throwable {
        var env = new Environment(newScope());
        try {
            var clazz = parse(env, "namespace App; final class Math { public static function Add(int $a,int $b):int{return $a+$b;} }");
            var self = call(new SelfExprToken(TokenMeta.empty()), "aDd", 2);
            var selected = ScalarCallResolver.resolve(clazz, self);
            assertNotNull(selected);
            assertSame(clazz.getMethods().get(0), selected.method());
            assertEquals(2, selected.arguments().size());
            assertNotNull(ScalarCallResolver.resolve(clazz, call(NameToken.valueOf("\\APP\\MATH"), "Add", 2)));
            assertNull(ScalarCallResolver.resolve(clazz, call(NameToken.valueOf("Other\\Math"), "Add", 2)));
            assertNull(ScalarCallResolver.resolve(clazz, call(NameToken.valueOf("Math"), "Add", 2)));
            assertThrows(UnsupportedOperationException.class, () -> selected.arguments().clear());
        } finally { env.doFinal(); }
    }

    @Test public void lateStaticDynamicNamedUnpackAndArityRetainPhpCalls() throws Throwable {
        var env = new Environment(newScope());
        try {
            var clazz = parse(env, "final class Math { public static function add(int $a,int $b):int{return $a+$b;} }");
            assertNull(ScalarCallResolver.resolve(clazz, call(new StaticExprToken(TokenMeta.empty()), "add", 2)));
            assertNull(ScalarCallResolver.resolve(clazz, call(new SelfExprToken(TokenMeta.empty()), "missing", 2)));
            assertNull(ScalarCallResolver.resolve(clazz, call(new SelfExprToken(TokenMeta.empty()), "add", 1)));
            var callable = call(new SelfExprToken(TokenMeta.empty()), "add", 2);
            callable.setFirstClassCallable(true);
            assertNull(ScalarCallResolver.resolve(clazz, callable));
            var named = call(new SelfExprToken(TokenMeta.empty()), "add", 2);
            named.setArguments(List.of(new CallArgument(named.getParameters().get(0), "a", CallArgument.Kind.NAMED, TokenMeta.empty()),
                    new CallArgument(named.getParameters().get(1), "b", CallArgument.Kind.NAMED, TokenMeta.empty())));
            assertNull(ScalarCallResolver.resolve(clazz, named));
            var unpack = call(new SelfExprToken(TokenMeta.empty()), "add", 1);
            unpack.setArguments(List.of(new CallArgument(unpack.getParameters().get(0), null, CallArgument.Kind.UNPACK, TokenMeta.empty())));
            assertNull(ScalarCallResolver.resolve(clazz, unpack));
        } finally { env.doFinal(); }
    }

    @Test public void overridableReferenceVariadicDefaultAndInstanceTargetsFallBack() throws Throwable {
        var env = new Environment(newScope());
        try {
            var clazz = parse(env, "class Math { public static function open(int $a):int{return $a;} "
                    + "private static function closed(int $a):int{return $a;} "
                    + "final public static function sealed(int $a):int{return $a;} "
                    + "public static function ref(int &$a):int{return $a;} "
                    + "public static function rest(int ...$a):int{return 0;} "
                    + "public static function defaults(int $a=1):int{return $a;} "
                    + "#[\\Deprecated] final public static function marked(int $a):int{return $a;} "
                    + "public function instance(int $a):int{return $a;} }");
            assertNull(ScalarCallResolver.resolve(clazz, call(new SelfExprToken(TokenMeta.empty()), "open", 1)));
            assertNotNull(ScalarCallResolver.resolve(clazz, call(new SelfExprToken(TokenMeta.empty()), "closed", 1)));
            assertNotNull(ScalarCallResolver.resolve(clazz, call(new SelfExprToken(TokenMeta.empty()), "sealed", 1)));
            for (String method : List.of("ref", "rest", "defaults", "instance", "marked"))
                assertNull(ScalarCallResolver.resolve(clazz, call(new SelfExprToken(TokenMeta.empty()), method, 1)));
        } finally { env.doFinal(); }
    }

    private ClassStmtToken parse(Environment env, String source) throws Exception {
        var context = new Context(source); context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
        var syntax = new SyntaxAnalyzer(env, new Tokenizer(context));
        return !syntax.getClasses().isEmpty() ? syntax.getClasses().iterator().next() : syntax.getDeferredClasses().get(0);
    }

    private CallExprToken call(org.develnext.jphp.core.tokenizer.token.expr.ValueExprToken owner, String name, int arguments) {
        var access = new StaticAccessExprToken(TokenMeta.empty()); access.setClazz(owner); access.setField(NameToken.valueOf(name));
        var call = new CallExprToken(TokenMeta.empty()); call.setName(access);
        var params = new ArrayList<ExprStmtToken>();
        for (int index = 0; index < arguments; index++) params.add(new ExprStmtToken(null, null, NameToken.valueOf("argument" + index)));
        call.setParameters(params); return call;
    }
}
