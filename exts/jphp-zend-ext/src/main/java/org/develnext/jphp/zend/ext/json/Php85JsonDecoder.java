package org.develnext.jphp.zend.ext.json;

import php.runtime.Memory;
import php.runtime.env.Environment;
import php.runtime.lang.StdClass;
import php.runtime.memory.ArrayMemory;
import php.runtime.memory.DoubleMemory;
import php.runtime.memory.LongMemory;
import php.runtime.memory.ObjectMemory;
import php.runtime.memory.StringMemory;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Builds actual PHP values on completed productions of the shared iterative byte parser. */
final class Php85JsonDecoder implements Php85JsonValidator.Sink {
    record Result(Memory value,int error) {}

    private static final class Container {
        final Memory value;
        final ArrayMemory entries;
        final boolean object;
        String key;
        Container(Memory value,ArrayMemory entries,boolean object){this.value=value;this.entries=entries;this.object=object;}
    }
    private final Environment environment;
    private final boolean associative,bigintAsString;
    private Container[] stack=new Container[16];
    private int top=-1;
    private Memory pending,result=Memory.NULL;

    private Php85JsonDecoder(Environment environment,int flags,boolean associative){
        this.environment=environment;this.associative=associative;
        bigintAsString=(flags&JsonConstants.JSON_BIGINT_AS_STRING)!=0;
    }
    static Result decode(Environment env,byte[] bytes,int depth,int flags,boolean associative){
        Php85JsonDecoder decoder=new Php85JsonDecoder(env,flags,associative);
        int error=Php85JsonValidator.decode(bytes,depth,(flags&JsonConstants.JSON_INVALID_UTF8_IGNORE)!=0,
                (flags&JsonConstants.JSON_INVALID_UTF8_SUBSTITUTE)!=0,decoder);
        return new Result(error==0?decoder.result:Memory.NULL,error);
    }
    @Override public void begin(boolean object){
        if(++top==stack.length)stack=Arrays.copyOf(stack,stack.length*2);
        if(object&&!associative){
            StdClass value=new StdClass(environment);stack[top]=new Container(new ObjectMemory(value),value.getProperties(),true);
        }else{
            ArrayMemory value=new ArrayMemory();stack[top]=new Container(value,value,object);
        }
    }
    @Override public void literal(int initial){pending=initial=='n'?Memory.NULL:initial=='t'?Memory.TRUE:Memory.FALSE;}
    @Override public boolean number(byte[] bytes,int from,int length){
        // The shared byte lexer has already checked the complete ASCII number production.
        boolean negative=bytes[from]=='-';
        long value=0,limit=negative?Long.MIN_VALUE:-Long.MAX_VALUE,multiplyLimit=limit/10;
        for(int index=from+(negative?1:0),end=from+length;index<end;index++){
            int digit=bytes[index]-'0';
            if(digit<0||digit>9||value<multiplyLimit)break;
            long multiplied=value*10;
            if(multiplied<limit+digit)break;
            value=multiplied-digit;
            if(index+1==end){pending=LongMemory.valueOf(negative?value:-value);return false;}
        }
        String token=new String(bytes,from,length,StandardCharsets.US_ASCII);
        boolean integer=token.indexOf('.')<0&&token.indexOf('e')<0&&token.indexOf('E')<0;
        // Overflow integers retain the PHP string-token behavior, including unquoted object keys.
        if(integer&&bigintAsString){pending=StringMemory.valueOf(token);return true;}
        pending=new DoubleMemory(Double.parseDouble(token));return false;
    }
    @Override public void string(byte[] bytes,int from,int length){
        // The shared scanner has already checked raw UTF8 and decoded escapes exactly once.
        pending=StringMemory.valueOf(new String(bytes,from,length,StandardCharsets.UTF_8));
    }
    @Override public void key(){stack[top].key=pending.toString();pending=null;}
    @Override public int value(){return publish(pending);}
    @Override public int end(){
        Container completed=stack[top];stack[top--]=null;
        Memory value=completed.value;
        if(value.isArray())value=((ArrayMemory)value).toConstant();
        return publish(value);
    }
    private int publish(Memory value){
        pending=null;
        if(top<0){result=value;return 0;}
        Container parent=stack[top];
        if(parent.object){
            String key=parent.key;
            // This semantic check occurs after the full value, before reading the next token.
            if(!associative&&!key.isEmpty()&&key.charAt(0)==0)return JsonConstants.JSON_ERROR_INVALID_PROPERTY_NAME;
            Memory published=value.toImmutable();
            if(associative)parent.entries.put(ArrayMemory.toKey(StringMemory.valueOf(key)),published);
            else parent.entries.putAsKeyString(key,published);
            parent.key=null;
        }else parent.entries.add(value.toImmutable());
        return 0;
    }
}
