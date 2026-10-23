package org.develnext.jphp.core.compiler.jvm.statement;

import php.runtime.common.Modifier;
import org.develnext.jphp.core.compiler.jvm.JvmCompiler;
import php.runtime.lang.Closure;
import php.runtime.reflection.ClassEntity;
import php.runtime.reflection.MethodEntity;
import php.runtime.reflection.ParameterEntity;
import php.runtime.common.PhpLanguageProfile;
import php.runtime.reflection.helper.ClosureEntity;
import org.develnext.jphp.core.tokenizer.token.expr.value.ClosureStmtToken;
import org.develnext.jphp.core.tokenizer.token.expr.value.NameToken;
import org.develnext.jphp.core.tokenizer.token.stmt.ClassStmtToken;
import org.develnext.jphp.core.tokenizer.token.stmt.ExtendsStmtToken;
import org.develnext.jphp.core.tokenizer.token.stmt.MethodStmtToken;
import org.develnext.jphp.core.tokenizer.token.stmt.NamespaceStmtToken;

import java.util.Arrays;

public class ClosureStmtCompiler extends StmtCompiler<ClosureEntity> {
    protected ClosureStmtToken statement;

    public ClosureStmtCompiler(JvmCompiler compiler, ClosureStmtToken statement) {
        super(compiler);
        this.statement = statement;
    }

    private String deprecatedClosureName(ClosureStmtToken closure) {
        var parent = closure.getParentFunction();
        String owner = closure.getOwnerClass() == null ? null : closure.getOwnerClass().getFulledName();
        String context = closure.toTraceInfo(compiler.getContext()).getFileName();
        if (parent != null && parent.getName() != null) {
            context = (owner == null ? parent.getFulledName() : owner + "::" + parent.getName().getName()) + "()";
        } else if (parent != null) {
            for (ClosureStmtToken candidate : compiler.getAnalyzer().getClosures()) {
                if (candidate.getFunction() == parent) { context = deprecatedClosureName(candidate); break; }
            }
        }
        return "{closure:" + context + ":" + (closure.toTraceInfo(compiler.getContext()).getStartLine() + 1) + "}";
    }

    @Override
    public ClosureEntity compile() {
        ClosureEntity entity = new ClosureEntity(getCompiler().getContext());
        entity.setReturnReference(statement.getFunction().isReturnReference());
        entity.setInternalName(compiler.getModule().getInternalName() + "_closure" + statement.getId());
        entity.setId(statement.getId());
        entity.setTrace(statement.toTraceInfo(compiler.getContext()));

        ClassStmtToken classStmtToken = new ClassStmtToken(statement.getMeta());
        classStmtToken.setNamespace(NamespaceStmtToken.getDefault());
        classStmtToken.setName(NameToken.valueOf(entity.getInternalName()));
        classStmtToken.setExtend(ExtendsStmtToken.valueOf(Closure.class.getSimpleName()));

        MethodStmtToken methodToken = new MethodStmtToken(statement.getFunction());
        methodToken.setClazz(classStmtToken);
        methodToken.setReturnReference(entity.isReturnReference());
        methodToken.setModifier(Modifier.PUBLIC);
        methodToken.setName(NameToken.valueOf("__invoke"));
        classStmtToken.setMethods(Arrays.asList(methodToken));

        ClassStmtCompiler classStmtCompiler = new ClassStmtCompiler(this.compiler, classStmtToken);
        classStmtCompiler.setSystem(true);
        classStmtCompiler.setInterfaceCheck(false);
        classStmtCompiler.setClassContext(statement.getOwnerClass());

        classStmtCompiler.setFunctionName(null);
        if(statement.getFunction().getPhpAttributes().stream().anyMatch(a->a.getName().getName().equalsIgnoreCase("NoDiscard"))){
            String owner=statement.getOwnerClass()==null?null:statement.getOwnerClass().getFulledName();
            var parent=statement.getParentFunction();
            String context=parent==null||parent.getName()==null?statement.toTraceInfo(compiler.getContext()).getFileName()
                    :(owner==null?parent.getFulledName():owner+"::"+parent.getName().getName())+"()";
            String closure="{closure:"+context+":"+(statement.toTraceInfo(compiler.getContext()).getStartLine()+1)+"}";
            classStmtCompiler.setNoDiscardClosureName((owner==null?"function ":"method "+owner+"::")+closure);
        }
        if (compiler.getModule().getPhpLanguageProfile() == PhpLanguageProfile.PHP_8_5
                && statement.getFunction().getPhpAttributes().stream().anyMatch(a -> a.getName().getName().equalsIgnoreCase("Deprecated"))) {
            String owner = statement.getOwnerClass() == null ? null : statement.getOwnerClass().getFulledName();
            classStmtCompiler.setDeprecatedClosureName((owner == null ? "Function " : "Method " + owner + "::")
                    + deprecatedClosureName(statement));
        }
        ClassEntity clazzEntity = classStmtCompiler.compile();
        clazzEntity.setType(ClassEntity.Type.CLOSURE);

        MethodEntity __invoke = clazzEntity.findMethod("__invoke");

        entity.getMethods().putAll(clazzEntity.getMethods());
        if (compiler.getModule().getPhpLanguageProfile() == PhpLanguageProfile.PHP_8_5)
            __invoke.setClazz(entity);
        if (clazzEntity.getParent() != null)
            entity.setParent(clazzEntity.getParent());

        entity.setData(clazzEntity.getData());
        entity.setPhpAttributes(__invoke.getPhpAttributes());
        entity.setParameters(__invoke.getParameters());
        entity.doneDeclare();

        entity.setGeneratorEntity(__invoke.getGeneratorEntity());
        var captures = statement.getFunction().getUses();
        ParameterEntity[] uses = new ParameterEntity[captures.size()];
        for (int i = 0; i < uses.length; i++) {
            var capture = captures.get(i);
            uses[i] = new ParameterEntity(compiler.getContext());
            uses[i].setName(capture.getName().getName());
            uses[i].setReference(capture.isReference());
            uses[i].setTrace(capture.toTraceInfo(compiler.getContext()));
        }
        entity.setUses(uses);
        if (compiler.getModule().getPhpLanguageProfile() == PhpLanguageProfile.PHP_8_5)
            entity.recordSourceCapturePlan(statement.isStaticClosure(), statement.getFunction().isThisExists());
        return entity;
    }
}
