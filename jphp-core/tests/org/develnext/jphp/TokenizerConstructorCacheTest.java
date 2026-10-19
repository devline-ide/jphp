package org.develnext.jphp;

import org.develnext.jphp.core.tokenizer.TokenMeta;
import org.develnext.jphp.core.tokenizer.Tokenizer;
import org.develnext.jphp.core.tokenizer.token.Token;
import org.develnext.jphp.core.tokenizer.token.expr.value.NameToken;
import org.junit.Test;
import php.runtime.env.Context;

import java.io.IOException;
import java.lang.reflect.Constructor;

import static org.junit.Assert.*;

public class TokenizerConstructorCacheTest {
    private static final class ExposedTokenizer extends Tokenizer {
        ExposedTokenizer() throws IOException { super(new Context("")); }
        Token construct(Class<? extends Token> type, TokenMeta meta) { return buildToken(type, meta); }
        Token previous() { return prevToken; }
        static Constructor<?> cached(Class<?> type) { return tokenConstructors.get(type); }
        static Constructor<?> override(Class<?> type, Constructor<?> constructor) {
            return tokenConstructors.put(type, constructor);
        }
        static void restore(Class<?> type, Constructor<?> constructor) {
            if (constructor == null) tokenConstructors.remove(type);
            else tokenConstructors.put(type, constructor);
        }
    }

    // Load only these fixtures in a disposable extension loader; JPHP itself is shared.
    private static ClassLoader extensionLoader() {
        return new ClassLoader(TokenizerConstructorCacheTest.class.getClassLoader()) {
            @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (!name.startsWith("org.develnext.jphp.tokenfixtures.Foreign")) {
                    return super.loadClass(name, resolve);
                }
                synchronized (getClassLoadingLock(name)) {
                    Class<?> type = findLoadedClass(name);
                    if (type == null) {
                        try (var input = getParent().getResourceAsStream(name.replace('.', '/') + ".class")) {
                            if (input == null) throw new ClassNotFoundException(name);
                            byte[] bytes = input.readAllBytes();
                            type = defineClass(name, bytes, 0, bytes.length);
                        } catch (IOException error) { throw new ClassNotFoundException(name, error); }
                    }
                    if (resolve) resolveClass(type);
                    return type;
                }
            }
        };
    }

    private static Class<? extends Token> foreign(ClassLoader loader, String suffix) throws ClassNotFoundException {
        return Class.forName("org.develnext.jphp.tokenfixtures.Foreign" + suffix, true, loader)
                .asSubclass(Token.class);
    }

    @Test public void builtinsKeepCachedConstructorAndFreshMetadata() throws Exception {
        var lexer = new ExposedTokenizer();
        var firstMeta = TokenMeta.of("first");
        Token first = lexer.construct(NameToken.class, firstMeta);
        Constructor<?> constructor = ExposedTokenizer.cached(NameToken.class);
        assertNotNull(constructor);
        assertEquals(NameToken.class, constructor.getDeclaringClass());
        var secondMeta = TokenMeta.of("second");
        Token second = lexer.construct(NameToken.class, secondMeta);
        assertNotSame(first, second);
        assertSame(firstMeta, first.getMeta());
        assertSame(secondMeta, second.getMeta());
        assertSame(second, lexer.previous());
        assertSame(constructor, ExposedTokenizer.cached(NameToken.class));
    }

    @Test public void automaticExtensionLookupDoesNotPinItsLoaderInTheGlobalMap() throws Exception {
        ClassLoader loader = extensionLoader();
        Class<? extends Token> type = foreign(loader, "Token");
        assertSame(loader, type.getClassLoader());
        var lexer = new ExposedTokenizer();
        try {
            Token first = lexer.construct(type, TokenMeta.of("first"));
            Token second = lexer.construct(type, TokenMeta.of("second"));
            assertEquals(type, first.getClass());
            assertEquals(type, second.getClass());
            assertNotSame(first, second);
            assertSame(second, lexer.previous());
            assertNull("Automatic constructor lookup must not globally retain an extension class", ExposedTokenizer.cached(type));
        } finally { ExposedTokenizer.restore(type, null); }
    }

    @Test public void explicitProtectedMapOverridesStillWinAfterAutomaticLookup() throws Exception {
        ClassLoader loader = extensionLoader();
        Class<? extends Token> type = foreign(loader, "Token");
        Class<? extends Token> replacement = foreign(loader, "OverrideToken");
        var lexer = new ExposedTokenizer();
        lexer.construct(type, TokenMeta.of("populate"));
        Constructor<?> previous = ExposedTokenizer.override(type, replacement.getConstructor(TokenMeta.class));
        try {
            var meta = TokenMeta.of("overridden");
            Token token = lexer.construct(type, meta);
            assertEquals(replacement, token.getClass());
            assertSame(meta, token.getMeta());
            assertSame(token, lexer.previous());
        } finally { ExposedTokenizer.restore(type, previous); ExposedTokenizer.restore(type, null); }
    }

    @Test public void extensionConstructorFailuresPreserveCauseAndPreviousToken() throws Exception {
        ClassLoader loader = extensionLoader();
        var lexer = new ExposedTokenizer();
        Token previous = lexer.construct(NameToken.class, TokenMeta.of("previous"));
        Class<? extends Token> throwing = foreign(loader, "ThrowingToken");
        try {
            for (int attempt = 0; attempt < 2; attempt++) {
                try { lexer.construct(foreign(loader, "MissingToken"), TokenMeta.of("missing")); fail(); }
                catch (RuntimeException failure) { assertTrue(failure.getCause() instanceof NoSuchMethodException); }
                assertSame(previous, lexer.previous());
                try { lexer.construct(throwing, TokenMeta.of("throwing")); fail(); }
                catch (RuntimeException failure) {
                    assertEquals("Unable build ForeignThrowingToken token: java.lang.IllegalStateException: constructor failure", failure.getMessage());
                    assertNull(failure.getCause());
                }
                assertSame(previous, lexer.previous());
            }
        } finally { ExposedTokenizer.restore(throwing, null); }
    }

}
