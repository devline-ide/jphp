package php.runtime.invoke;

import php.runtime.Memory;
import php.runtime.common.HintType;
import php.runtime.common.Messages;
import php.runtime.common.StringUtils;
import php.runtime.env.Context;
import php.runtime.env.Environment;
import php.runtime.env.TraceInfo;
import php.runtime.exceptions.support.ErrorType;
import php.runtime.ext.core.classes.WrapJavaExceptions;
import php.runtime.lang.ForeachIterator;
import php.runtime.lang.Generator;
import php.runtime.lang.exception.BaseTypeError;
import php.runtime.memory.ArrayMemory;
import php.runtime.memory.ObjectMemory;
import php.runtime.memory.ReferenceMemory;
import php.runtime.memory.helper.VariadicMemory;
import php.runtime.reflection.ClassEntity;
import php.runtime.reflection.MethodEntity;
import php.runtime.reflection.ModuleEntity;
import php.runtime.reflection.ParameterEntity;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

public class InvokeArgumentHelper {
    private static final String INVALID_TYPE_MESSAGE = "Only arrays and Traversables can be unpacked";

    public static String closureCallName(php.runtime.lang.Closure closure) {
        if (closure instanceof PhpCallableFactory.CapturedClosure)
            return ((PhpCallableFactory.CapturedClosure) closure).getCallName();
        TraceInfo declaration = closure.getReflection().getTrace();
        return declaration == null || declaration.isUnknown() ? "{closure}"
                : "{closure:" + declaration.getFileName() + ":" + (declaration.getStartLine() + 1) + "}";
    }

    public static Memory[] argumentsByValue(Memory[] args) {
        if (args == null || args.length == 0) return args;
        Memory[] values = new Memory[args.length];
        for (int i = 0; i < args.length; i++) values[i] = args[i].toImmutable();
        return values;
    }

    // Internal indirect calls warn and use a temporary for a non-reference argument.
    // Direct user calls keep their own validation; this never changes caller storage.
    public static Memory[] prepareIndirectArguments(Environment env, TraceInfo trace, Memory[] args,
                                                    ParameterEntity[] parameters, String name) {
        if (args == null || parameters == null || parameters.length == 0
                || env.getModuleManager().getPhpLanguageProfile(trace) != php.runtime.common.PhpLanguageProfile.PHP_8_5) {
            return args;
        }
        Memory[] passed = args;
        for (int i = 0; i < args.length; i++) {
            ParameterEntity parameter;
            if (i < parameters.length) parameter = parameters[i];
            else {
                parameter = parameters[parameters.length - 1];
                if (!parameter.isVariadic()) break;
            }
            if (parameter.isReference() && !args[i].isReference()) {
                if (parameter.isVariadic())
                    env.warning(trace, "%s(): Argument #%d must be passed by reference, value given", name, i + 1);
                else
                    env.warning(trace, "%s(): Argument #%d ($%s) must be passed by reference, value given",
                            name, i + 1, parameter.getName());
                if (passed == args) passed = args.clone();
                passed[i] = new ReferenceMemory(args[i].toImmutable());
            }
        }
        return passed;
    }

    private static Memory[] argsToPassed(Environment env, TraceInfo trace, Memory[] args, ParameterEntity[] parameters) {
        Memory[] passed = args;

        if ((args == null && parameters.length > 0) || (args != null && args.length < parameters.length)) {
            passed = new Memory[parameters.length];

            if (args != null && args.length > 0) {
                System.arraycopy(args, 0, passed, 0, args.length);
            }
        }

        // Argument slots may be cached by a compiled call site. Only Memory references,
        // not the caller's array itself, may be shared with parameter preparation.
        return passed == args && args != null ? args.clone() : passed;
    }

    private static Memory makeArg(Environment env, TraceInfo trace, ParameterEntity param,
                                  Memory arg, int i, String originClassName, String originMethodName, String staticClassName,
                                  boolean internalCall, int suppliedCount, ParameterEntity[] parameters) {
        if (arg == null) {
            if (env.getModuleManager().getPhpLanguageProfile(trace) == php.runtime.common.PhpLanguageProfile.PHP_8_5
                    && i < requiredCount(parameters)) {
                int required = requiredCount(parameters);
                int fixedCount = parameters.length - (parameters[parameters.length - 1].isVariadic() ? 1 : 0);
                boolean minimum = required < fixedCount;
                String location = internalCall || trace == null ? ""
                        : " in " + trace.getFileName() + " on line " + (trace.getStartLine() + 1);
                env.exception(trace, php.runtime.lang.exception.BaseArgumentCountError.class,
                        "Too few arguments to function %s(), %d passed%s and %s %d expected",
                        callName(originClassName, originMethodName), suppliedCount, location,
                        minimum ? "at least" : "exactly", required);
            }
            arg = makeDefaultArg(env, trace, param, i, originClassName, originMethodName);
        } else {
            arg = makeNormalArg(env, trace, param, arg, i, originClassName, originMethodName);
        }

        return typeHintArg(env, trace, param, arg, i, originClassName, originMethodName, staticClassName, internalCall);
    }

    private static int requiredCount(ParameterEntity[] parameters) {
        int required = 0;
        for (int i = 0; i < parameters.length; i++)
            if (!parameters[i].isOptional() && !parameters[i].isVariadic()) required = i + 1;
        return required;
    }

    private static String callName(String originClassName, String originMethodName) {
        return originMethodName == null ? originClassName : originClassName + "::" + originMethodName;
    }

    private static Memory makeNormalArg(Environment env, TraceInfo trace, ParameterEntity param, Memory arg,
                                        int index, String originClassName, String originMethodName) {

        if (param.isReference()) {
            if (!arg.isReference()
                    && env.getModuleManager().getPhpLanguageProfile(trace) == php.runtime.common.PhpLanguageProfile.PHP_8_5) {
                if (arg.isObject()) {
                    env.error(trace, ErrorType.E_NOTICE, "Only variables should be passed by reference");
                    arg = new ReferenceMemory(arg);
                } else {
                    referenceArgumentError(env, trace, param, index, originClassName, originMethodName);
                }
            } else if (!arg.isReference() && !arg.isObject()) {
                env.error(trace, ErrorType.E_ERROR, "Only variables can be passed by reference");
                return new ReferenceMemory(arg);
            }

            return env.getModuleManager().getPhpLanguageProfile(trace) == php.runtime.common.PhpLanguageProfile.PHP_8_5
                    ? ReferenceMemory.exportReference(arg) : arg;
        } else {
            return param.isMutable() ? arg.toImmutable() : arg.toValue();
        }
    }

    private static Memory makeDefaultArg(Environment env, TraceInfo trace, ParameterEntity param,
                                         int i, String originClassName, String originMethodName) {
        Memory def = param.evaluateDefault(env);

        if (def != null) {
            if (!param.isReference()) {
                return param.isMutable() ? def.toImmutable(env, trace) : def;
            } else {
                return new ReferenceMemory(param.isMutable() ? def.toImmutable(env, trace) : def);
            }
        } else {
            if (param.getTypeClass() != null) {
                invalidType(env, trace, param, i + 1, null, originClassName, originMethodName);
            }

            env.error(trace, ErrorType.E_ERROR,
                    Messages.ERR_MISSING_ARGUMENT, (i + 1) + " ($" + param.getName() + ")",
                    originMethodName == null ? originClassName : originClassName + "::" + originMethodName
            );

            return param.isReference() ? new ReferenceMemory() : Memory.NULL;
        }
    }

    private static Memory typeHintArg(Environment env, TraceInfo trace, ParameterEntity param,
                                      Memory arg, int i, String originClassName, String originMethodName, String staticClassName) {
        return typeHintArg(env, trace, param, arg, i, originClassName, originMethodName, staticClassName, false);
    }

    private static Memory typeHintArg(Environment env, TraceInfo trace, ParameterEntity param,
                                      Memory arg, int i, String originClassName, String originMethodName, String staticClassName,
                                      boolean internalCall) {
        if (i == 0 && "Closure".equals(originClassName) && "fromCallable".equals(originMethodName)
                && env.getModuleManager().getPhpLanguageProfile(trace) == php.runtime.common.PhpLanguageProfile.PHP_8_5
                && (arg.isArray() || arg.isString() || arg.isObject())) return arg;
        if (!param.checkTypeHinting(env, arg, staticClassName)) {
            ModuleEntity module = env.getModuleManager().findModule(trace);

            boolean modern = env.getModuleManager().getPhpLanguageProfile(trace) == php.runtime.common.PhpLanguageProfile.PHP_8_5;
            boolean strict = !internalCall && module != null && module.isStrictTypes();
            if (modern && param.isReference() && arg instanceof ReferenceMemory
                    && ((ReferenceMemory)arg).hasPropertySources()) {
                // A by-reference parameter may not implicitly convert a typed property's cell.
                invalidType(env, trace, param, i + 1, arg, originClassName, originMethodName, internalCall);
                return arg;
            }
            Memory memory = modern && param.getTypeChecker() != null
                    ? param.getTypeChecker().apply(env, arg, param.isNullableOrDefaultNull(), strict)
                    : param.applyTypeHinting(env, arg, strict);

            if (memory != null) {
                if (modern) memory = php.runtime.PhpNumericConversions.checkedScalarCoercion(arg, memory, env, trace);
                if (modern && memory.getRealType() == Memory.Type.INT && (param.getType() == HintType.INT
                        || param.getTypeChecker() instanceof php.runtime.reflection.support.PhpTypeDeclaration
                        && ((php.runtime.reflection.support.PhpTypeDeclaration) param.getTypeChecker()).has("int"))) {
                    Memory number = arg.isString() ? php.runtime.PhpNumericString.parseStrict(arg.toString()) : arg;
                    if (number != null) php.runtime.PhpNumericConversions.warnIntegerPrecisionLoss(arg, number, env, trace);
                }
                if (param.isReference() && arg.isReference()
                        && env.getModuleManager().getPhpLanguageProfile(trace) == php.runtime.common.PhpLanguageProfile.PHP_8_5) {
                    arg.assign(memory);
                    return arg;
                }
                return memory;
            } else {
                invalidType(env, trace, param, i + 1, arg, originClassName, originMethodName, internalCall);
            }
        }

        return arg;
    }

    public static Memory[] makeArguments(Environment env, Memory[] args,
                                         ParameterEntity[] parameters,
                                         String originClassName, String originMethodName, String staticClassName,
                                         TraceInfo trace) {
        return makeArguments(env, args, parameters, originClassName, originMethodName, staticClassName, trace, false);
    }

    public static Memory[] makeArguments(Environment env, Memory[] args, ParameterEntity[] parameters,
                                         String originClassName, String originMethodName, String staticClassName,
                                         TraceInfo trace, boolean internalCall) {
        if (parameters == null) {
            return args;
        }

        args = unpackArgs(env, trace, args, parameters);
        int suppliedCount = args == null ? 0 : args.length;
        Memory[] passed = argsToPassed(env, trace, args, parameters);

        int i = 0;

        if (passed != null) {
            boolean variadicMemoryExists = false;

            for (ParameterEntity param : parameters) {
                Memory arg = passed[i];

                if (param.isVariadic()) {
                    ArrayMemory variadicArgs = new ArrayMemory();

                    int _i = i;

                    while (arg != null) {
                        if (arg instanceof VariadicMemory) {
                            variadicMemoryExists = true;

                            ForeachIterator iterator = arg.getNewIterator(env, param.isReference(), false);

                            if (iterator == null) {
                                env.warning(trace, INVALID_TYPE_MESSAGE);
                            } else {
                                makeVariadic(iterator, variadicArgs, param, env, trace, _i, originClassName, originMethodName);
                            }
                        } else {
                            if (variadicMemoryExists) {
                                env.error(trace, "Cannot use positional argument after argument unpacking");
                            }

                            if (env.getModuleManager().getPhpLanguageProfile(trace) == php.runtime.common.PhpLanguageProfile.PHP_8_5) {
                                arg = makeNormalArg(env, trace, param, arg, i, originClassName, originMethodName);
                                arg = typeHintArg(env, trace, param, arg, i, originClassName, originMethodName, staticClassName, internalCall);
                            } else {
                                arg = typeHintArg(env, trace, param, arg, _i + 1, originClassName, originMethodName, staticClassName);
                            }

                            variadicArgs.add(makeValue(param, arg, env, trace));
                        }

                        i++;
                        if (i < passed.length) {
                            arg = passed[i];
                        } else {
                            break;
                        }
                    }

                    passed[_i] = variadicArgs;

                    break;
                }

                passed[i] = makeArg(env, trace, param, arg, i, originClassName, originMethodName, staticClassName,
                        internalCall, suppliedCount, parameters);
                i++;
            }

            if (!variadicMemoryExists) {
                makeImmutable(passed, parameters);
            }
        }

        return passed;
    }


    private static void makeImmutable(Memory[] passed, ParameterEntity[] parameters) {
        for (int j = parameters.length ; j < passed.length; j++) {
            passed[j] = passed[j].toImmutable();
        }
    }

    public static Memory[] unpackArgs(Environment env, TraceInfo trace, Memory[] passed, ParameterEntity[] parameters) {
        List<Memory> varPassed = null;

        if (passed == null) {
            return null;
        }

        int cnt = 0, paramCnt = 0;

        ParameterEntity parameterEntity = null;
        boolean variadicMemoryExists = false;

        for (Memory arg : passed) {
            if (arg instanceof VariadicMemory) {
                variadicMemoryExists = true;

                if (varPassed == null) {
                    varPassed = new ArrayList<Memory>();

                    for (int i = 0; i < cnt; i++) {
                        varPassed.add(passed[i]);
                    }
                }

                boolean isGenerator = arg.instanceOf(Generator.class);

                ForeachIterator foreachIterator = arg.getNewIterator(env, !isGenerator, false);

                if (foreachIterator == null || (!isGenerator && !arg.isTraversable())) {
                    env.warning(trace, INVALID_TYPE_MESSAGE);
                } else {
                    boolean isRef;

                    while (foreachIterator.next()) {
                        if (parameters != null) {
                            if (parameterEntity == null || !parameterEntity.isVariadic()) {
                                parameterEntity = paramCnt < parameters.length ? parameters[paramCnt] : null;
                            }
                        }

                        isRef = parameterEntity != null && parameterEntity.isReference();

                        Memory value = foreachIterator.getValue();
                        varPassed.add(isRef ? value : value.toImmutable());

                        paramCnt++;

                        if (parameterEntity != null && !parameterEntity.isVariadic()) {
                            parameterEntity = null;
                        }
                    }
                }
            } else {
                if (variadicMemoryExists) {
                    env.error(trace, "Cannot use positional argument after argument unpacking");
                }

                if (varPassed != null) {
                    varPassed.add(arg);
                }

                paramCnt++;
            }
            cnt++;
        }

        if (varPassed != null) {
            passed = varPassed.toArray(new Memory[varPassed.size()]);
        }

        return passed;
    }

    public static Memory[] checkType(Environment env, TraceInfo trace, MethodEntity methodEntity, Memory... args) {
        if (args == null) {
            return null;
        }

        ParameterEntity[] parameters = methodEntity.getParameters(args.length);

        int i = 0;

        for (Memory arg : args) {
            if (i > parameters.length - 1) {
                break;
            }

            args[i] = typeHintArg(
                    env, trace, parameters[i], arg, i, methodEntity.getClazzName(), methodEntity.getName(), null
            );

            i++;
        }

        return args;
    }

    public static void invalidType(Environment env, TraceInfo trace, ParameterEntity param, int index, Memory passed,
                                   String originClassName, String originMethodName) {
        invalidType(env, trace, param, index, passed, originClassName, originMethodName, false);
    }

    private static void invalidType(Environment env, TraceInfo trace, ParameterEntity param, int index, Memory passed,
                                    String originClassName, String originMethodName, boolean internalCall) {
        if (passed != null && param.getTypeChecker() != null && param.getTypeEnum() == null
                && param.getTypeHintingChecker() == null
                && env.getModuleManager().getPhpLanguageProfile(trace) == php.runtime.common.PhpLanguageProfile.PHP_8_5) {
            String needed = param.getTypeChecker() instanceof php.runtime.reflection.support.PhpTypeDeclaration
                    ? php.runtime.reflection.support.PhpTypeDeclaration.fromChecker(param.getTypeChecker(), param.isNullableOrDefaultNull()).getRuntimeErrorSignature(env, originMethodName == null ? null : originClassName)
                    : param.getType() == HintType.ITERABLE
                    ? "Traversable|array" + (param.isNullableOrDefaultNull() ? "|null" : "")
                    : (param.isNullableOrDefaultNull() ? "?" : "") + param.getTypeChecker().getSignature();
            String given = passed.isObject() ? passed.toValue(ObjectMemory.class).getReflection().getName()
                    : passed.isResource() ? "resource" : passed.isNull() ? "null"
                    : passed.getRealType() == Memory.Type.INT ? "int"
                    : passed.getRealType() == Memory.Type.BOOL ? (passed.toBoolean() ? "true" : "false") : passed.getRealType().toString();
            String location = internalCall || trace == null ? ""
                    : ", called in " + trace.getFileName() + " on line " + (trace.getStartLine() + 1);
            env.exception(trace, BaseTypeError.class, "%s(): Argument #%d%s must be of type %s, %s given%s",
                    callName(originClassName, originMethodName), index,
                    param.isVariadic() ? "" : " ($" + param.getName() + ")", needed, given, location);
            return;
        }
        String given;
        if (passed == null) {
            given = "none";
        } else if (passed.isObject()) {
            given = "instance of " + passed.toValue(ObjectMemory.class).getReflection().getName();
        } else {
            given = passed.getRealType().toString();
        }

        String method = originMethodName == null ? originClassName : originClassName + "::" + originMethodName;

        if (param.getTypeClass() == null) {
            if (param.getTypeEnum() != null && param.getType() == HintType.ANY) {
                Field[] fields = param.getTypeEnum().getFields();
                String[] names = new String[fields.length];

                for (int i = 0; i < names.length; i++) {
                    names[i] = fields[i].getName();
                }

                env.exception(param.getTrace(), WrapJavaExceptions.IllegalArgumentException.class,
                        "Argument %s passed to %s() must be a string belonging to the range [" + StringUtils.join(names, ", ") + "] as string, called in %s on line %d, position %d and defined",
                        index,
                        method,

                        trace.getFileName(),
                        trace.getStartLine() + 1,
                        trace.getStartPosition() + 1
                );
            } else if (param.getTypeHintingChecker() != null) {
                env.exception(
                        param.getTrace(),
                        BaseTypeError.class,
                        "Argument %s passed to %s() must be %s, called in %s on line %d, position %d and defined",
                        index,
                        method,
                        param.getTypeHintingChecker().getNeeded(env, passed),

                        trace.getFileName(),
                        trace.getStartLine() + 1,
                        trace.getStartPosition() + 1
                );
            } else {
                env.exception(
                        param.getTrace(),
                        BaseTypeError.class,
                        "Argument %s passed to %s() must be of the type %s, %s given, called in %s on line %d, position %d and defined",
                        index,
                        method,
                        param.getType().toString(), given,

                        trace.getFileName(),
                        trace.getStartLine() + 1,
                        trace.getStartPosition() + 1
                );
            }
        } else {
            ClassEntity need = env.fetchClass(param.getTypeClass(), false);
            String what = "";
            if (need == null || need.isClass()) {
                what = "be an instance of";
            } else if (need.isInterface()) {
                what = "implement interface";
            }

            what = what + " " + param.getTypeClass();

            if (param.isNullableOrDefaultNull()) {
                what += " or null";
            }

            env.exception(
                    param.getTrace(),
                    BaseTypeError.class,
                    "Argument %s passed to %s() must %s, %s given, called in %s on line %d, position %d and defined",
                    index,
                    method,

                    what, given,
                    trace.getFileName(),
                    trace.getStartLine() + 1,
                    trace.getStartPosition() + 1
            );
        }
    }

    static void referenceArgumentError(Environment env, TraceInfo trace, ParameterEntity param, int index,
                                                String originClassName, String originMethodName) {
        env.exception(trace, php.runtime.lang.exception.BaseError.class,
                "%s(): Argument #%d%s could not be passed by reference", callName(originClassName, originMethodName),
                index + 1, param.isVariadic() ? "" : " ($" + param.getName() + ")");
    }

    public static Memory makeValue(ParameterEntity param, Memory arg, Environment env, TraceInfo trace) {
        if (param.isReference()) {
            if (!arg.isReference() && !arg.isObject()) {
                env.error(trace, ErrorType.E_ERROR, "Only variables can be passed by reference");
                arg = new ReferenceMemory(arg);
            }
            if (env.getModuleManager().getPhpLanguageProfile(trace) == php.runtime.common.PhpLanguageProfile.PHP_8_5)
                arg = ReferenceMemory.exportReference(arg);
        } else {
            arg = param.isMutable() ? arg.toImmutable() : arg.toValue();
        }

        return arg;
    }


    public static void makeVariadic(ForeachIterator iterator, ArrayMemory variadicArray, ParameterEntity param,
                                    Environment env, TraceInfo trace, int index, String originClassName,
                                    String originMethodName) {
        while (iterator.next()) {
            Memory arg = iterator.getValue();

            if (!param.checkTypeHinting(env, arg)) {
                invalidType(env, trace, param, index + 1, arg, originClassName, originMethodName);
            }

            variadicArray.add(makeValue(param, iterator.getValue(), env, trace));
        }
    }
}
