package org.develnext.jphp.core.compiler.jvm;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import org.develnext.jphp.core.syntax.SyntaxAnalyzer;
import org.develnext.jphp.core.tokenizer.Tokenizer;
import org.develnext.jphp.core.tokenizer.token.stmt.*;
import php.runtime.common.HintType;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.env.Context;
import php.runtime.env.Environment;
import static org.junit.Assert.*;

/** Parser-only foundation controls. These do not claim generated or PHB hook execution. */
public class PhpPropertyHookParserTest {
    private List<ClassStmtToken> parse(String source) throws java.io.IOException {
        Environment env=new Environment(new ByteArrayOutputStream());
        Context context=new Context("<?php "+source,new java.io.File("property-hook-parser.php"));
        context.setPhpLanguageProfile(PhpLanguageProfile.PHP_8_5);
        try {return new ArrayList<>(new SyntaxAnalyzer(env,new Tokenizer(context)).getClasses());}
        finally {env.getScope().getClassLoader().close();}
    }
    private ClassVarStmtToken property(String source) throws java.io.IOException {return parse(source).get(0).getProperties().get(0);}
    @Test public void declarationOrderAndActualBodiesSurvive() throws java.io.IOException {
        ClassVarStmtToken p=property("class H {public int $x=1 {set {$this->x=$value;}get=>$this->x;}}");
        assertEquals(2,p.getPropertyHooks().size());
        assertEquals(PropertyHookStmtToken.Kind.SET,p.getPropertyHooks().get(0).getKind());
        PropertyHookStmtToken get=p.getPropertyHook(PropertyHookStmtToken.Kind.GET);
        assertEquals(PropertyHookStmtToken.BodyKind.EXPRESSION,get.getBodyKind());
        assertNotNull(get.getArrowExpression());
        assertTrue(get.getMethod().getBody().getInstructions().get(0).getSingle() instanceof ReturnStmtToken);
        assertSame(p.getClazz(),get.getMethod().getClazz());
        assertEquals("$x::get",get.getMethod().getName().getName());
        assertFalse(get.getBackingAccesses().isEmpty());
        try {p.getPropertyHooks().clear();fail("Mutable hook catalog");} catch(UnsupportedOperationException expected) {}
    }
    @Test public void setArrowIsNotMistakenForGetterReturn() throws java.io.IOException {
        ClassVarStmtToken p=property("class H {public int $x=1 {get=>$this->x;set=>$value+1;}}");
        PropertyHookStmtToken set=p.getPropertyHook(PropertyHookStmtToken.Kind.SET);
        assertEquals(PropertyHookStmtToken.BodyKind.EXPRESSION,set.getBodyKind());
        assertNotNull(set.getArrowExpression());
        assertEquals(HintType.VOID,set.getMethod().getReturnHintType());
        assertEquals("value",set.getMethod().getArguments().get(0).getName().getName());
        assertSame(p.getDeclaredType(),set.getMethod().getArguments().get(0).getDeclaredType());
    }
    @Test public void explicitSetTypeReferenceAndFinalArePreserved() throws java.io.IOException {
        ClassVarStmtToken p=property("class H {public int $x {final set(int|string $v) {} &get {return $this->other;}}private int $other=0;}");
        PropertyHookStmtToken set=p.getPropertyHook(PropertyHookStmtToken.Kind.SET);
        assertTrue(set.getMethod().isFinal());
        assertEquals("v",set.getMethod().getArguments().get(0).getName().getName());
        assertTrue(set.getMethod().getArguments().get(0).getDeclaredType().has("string"));
        assertTrue(p.getPropertyHook(PropertyHookStmtToken.Kind.GET).getMethod().isReturnReference());
    }
    @Test public void interfaceAndAbstractHookBodiesRemainAbsent() throws java.io.IOException {
        List<ClassStmtToken> classes=parse("interface I {public int $x {get;set;}} abstract class H {abstract public int $x {get;}}");
        for(ClassStmtToken c : classes) for(PropertyHookStmtToken hook : c.getProperties().get(0).getPropertyHooks()) {
            assertEquals(PropertyHookStmtToken.BodyKind.ABSTRACT,hook.getBodyKind());
            assertTrue(hook.getMethod().isAbstract());assertNull(hook.getMethod().getBody());
        }
    }
    @Test public void hookFunctionScopeDoesNotLeakIntoOrdinaryMethods() throws java.io.IOException {
        ClassStmtToken c=parse("class H {public int $x {get {$inside=7;return $inside;}}public function normal(){return 3;}}").get(0);
        MethodStmtToken hook=c.getProperties().get(0).getPropertyHook(PropertyHookStmtToken.Kind.GET).getMethod();
        assertTrue(hook.getLocal().stream().anyMatch(v->v.getName().equals("inside")));
        MethodStmtToken normal=c.getMethods().get(0);
        assertEquals("normal",normal.getName().getName());
        assertFalse(normal.getLocal().stream().anyMatch(v->v.getName().equals("inside")));
        assertSame(c,normal.getClazz());
    }
    @Test public void ordinaryTypedDeclarationsKeepEmptyCatalog() throws java.io.IOException {
        ClassStmtToken c=parse("class H {public int $x=1;public array $a=[1,2];public function get(){return 7;}}").get(0);
        assertEquals(2,c.getProperties().size());
        for(ClassVarStmtToken p : c.getProperties()) {assertFalse(p.hasPropertyHooks());assertNotNull(p.getValue());}
        assertEquals("get",c.getMethods().get(0).getName().getName());
    }
    @Test public void nestedFunctionsDoNotDeclareBackingForTheOuterHook() throws java.io.IOException {
        for(String body : List.of("$f=function(){return $this->x;};return $f();",
                "return (fn()=>$this->x)();",
                "$o=new class {public int $x=1;public function value(){return $this->x;}};return $o->value();")) {
            ClassVarStmtToken p=property("class H {public int $x {get {"+body+"}}}");
            assertTrue(p.isVirtualProperty());
        }
    }
    @Test public void promotedHooksUseTheSameExactPropertyCatalog() throws java.io.IOException {
        ClassStmtToken c=parse("class H {public function __construct(public int $x {get=>$this->x;set {$this->x=$value*2;}}){}}").get(0);
        ClassVarStmtToken p=c.getProperties().get(0);
        assertTrue(p.isPromoted());assertFalse(p.isVirtualProperty());
        assertEquals(2,p.getPropertyHooks().size());
        assertSame(p.getPropertyHooks().getFirst(),c.getMethods().get(0).getArguments().get(0).getPromotedPropertyHooks().getFirst());
    }
    @Test public void backingIsDetectedInOwnConditionalAndConstructorArgumentExpressions() throws java.io.IOException {
        ClassVarStmtToken p=property("class H {public int $x {get {if(false)return $this->x;return 2;}}}");
        assertFalse(p.isVirtualProperty());
    }

}
