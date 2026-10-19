package php.runtime.memory.output;

import php.runtime.Memory;
import php.runtime.common.StringUtils;
import php.runtime.env.Environment;
import php.runtime.lang.Closure;
import php.runtime.lang.ForeachIterator;
import php.runtime.memory.*;
import php.runtime.reflection.ClassEntity;

import java.io.Writer;
import java.util.Set;

public class VarExport extends Printer {

    private final static int PRINT_INDENT = 2;

    public VarExport(Environment env, Writer writer) {
        super(env, writer);
    }

    @Override
    protected void printNull() {
        printer.append("NULL");
    }

    @Override
    protected void printFalse() {
        printer.append("false");
    }

    @Override
    protected void printTrue() {
        printer.append("true");
    }

    @Override
    protected void printLong(LongMemory value) {
        printer.print(value.toLong() + "");
    }

    @Override
    protected void printDouble(DoubleMemory value) {
        printer.print(value.toDouble() + "");
    }

    @Override
    protected void printString(StringMemory value) {
        String v = value.toString();
        int length = v.length();

        printer.append('\'');
        for(int i = 0; i < length; i++){
            char ch = v.charAt(i);
            if (ch == '\'')
                printer.append("\\'");
            else if (ch == '\0'){
                printer.append("' . \"\\0\" . '");
            } else
                printer.append(ch);
        }

        printer.append('\'');
    }

    @Override
    protected void printArray(ArrayMemory value, int level, Set<Integer> used) {
        printArray(value, level, used, false);
    }

    protected void printArray(ArrayMemory value, int level, Set<Integer> used, boolean stripNulls) {
        if (!used.add(value.getPointer())){
            recursionExists = true;
            printNull();
        } else {
            printer.write(StringUtils.repeat(' ', level * PRINT_INDENT));
            printer.write("array (\n");

            ForeachIterator iterator = value.foreachIterator(false, false);

            level += 1;
            while (iterator.next()){
                Memory el = iterator.getValue();
                if (el == Memory.UNDEFINED || value instanceof php.runtime.memory.PhpObjectProperties && el.isUndefined()) continue;

                printer.write(StringUtils.repeat(' ', level * PRINT_INDENT));
                Memory key = iterator.getMemoryKey();

                if (key.isString()){
                    String k = key.toString();
                    if (stripNulls){
                        int pos = k.lastIndexOf('\0');
                        if (pos > -1)
                            k = k.substring(pos + 1);
                    }

                    printString(new StringMemory(k));
                } else {
                    printer.write(key.toString());
                }

                printer.write(" =>");

                if (el.isArray() || el.isObject() && modernObject(el.toValue(ObjectMemory.class))) {
                    if (!used.contains(el.getPointer()))
                        printer.write("\n");
                    else
                        printer.write(" ");
                    print(el, level, used);
                } else {
                    printer.write(" ");
                    print(el, level + 1, used);
                }

                printer.append(",\n");
            }
            level -= 1;
            printer.write(StringUtils.repeat(' ', level * PRINT_INDENT));
            printer.append(")");
            used.remove(value.getPointer());
        }
    }

    @Override
    protected void printObject(ObjectMemory value, int level, Set<Integer> used) {
        if(value.value instanceof php.runtime.lang.BaseEnum actual) {
            printer.write("\\"+value.getReflection().getName()+"::"+actual.enumCaseName());return;
        }
        if (used.contains(value.getPointer())){
            recursionExists = true;
            printNull();
        } else {
            used.add(value.getPointer());

            if(modernObject(value)){try{printModernObject(value,level,used);}finally{used.remove(value.getPointer());}return;}
            ClassEntity entity = value.getReflection();
            printer.write(entity.getName());
            printer.write("::");
            printer.write("__set_state(");
            printArray(value.value.getProperties(), 0, used, true);
            printer.write(")");

            used.remove(value.getPointer());
        }
    }


    private boolean modernObject(ObjectMemory value) {
        var module=value.getReflection().getModule();
        return module!=null&&module.getPhpLanguageProfile()==php.runtime.common.PhpLanguageProfile.PHP_8_5;
    }

    private void printModernObject(ObjectMemory value,int level,Set<Integer> used) {
        printer.write(StringUtils.repeat(' ',level*PRINT_INDENT));
        printer.write("\\"+value.getReflection().getName()+"::__set_state(array(\n");
        ForeachIterator iterator=php.runtime.PhpPropertyHookAccess.consumerProperties(value.value,env,env.trace(),null,0);
        while(iterator.next()) {
            Memory element=iterator.getValue();if(element.isUndefined())continue;
            String key=iterator.getKey().toString();key=key.substring(key.lastIndexOf('\0')+1);
            printer.write(StringUtils.repeat(' ',level*PRINT_INDENT+3));printString(new StringMemory(key));printer.write(" => ");
            if(element.isArray()||element.isObject()) {
                printer.write("\n");print(element,level+1,used);
            }else print(element,level+1,used);
            printer.write(",\n");
        }
        printer.write(StringUtils.repeat(' ',level*PRINT_INDENT));printer.write("))");
    }

    @Override
    protected void printClosure(Closure value, int level, Set<Integer> used) {
        printObject(new ObjectMemory(value), level, used);
    }
}
