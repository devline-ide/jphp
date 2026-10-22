package org.develnext.jphp.zend.ext.json;

import java.util.Arrays;
import java.io.ByteArrayOutputStream;

/** Shared byte lexer and iterative grammar; the validation-only path never builds decoded values. */
final class Php85JsonValidator {
    private static final int END=256,VALUE=257,STRING=258,ERROR=259;
    private static final int ROOT=0,DONE=1,ARRAY_FIRST=2,ARRAY_NEXT=3,ARRAY_END=4,
            OBJECT_FIRST=5,OBJECT_KEY=6,OBJECT_COLON=7,OBJECT_VALUE=8,OBJECT_END=9;
    /** Decoder hooks publish real PHP values only when their grammar production completes. */
    interface Sink {
        void begin(boolean object);
        void literal(int initial);
        boolean number(byte[] bytes,int from,int length);
        void string(byte[] bytes,int from,int length);
        void key();
        int value();
        int end();
    }
    private final byte[] bytes;
    private final boolean ignore,substitute;
    private final Sink sink;
    private int position,error;
    private Php85JsonValidator(byte[] bytes,boolean ignore,boolean substitute,Sink sink){
        this.bytes=bytes;this.ignore=ignore;this.substitute=substitute;this.sink=sink;
    }
    static int validate(byte[] bytes,int maxDepth,boolean ignore){return new Php85JsonValidator(bytes,ignore,false,null).parseValidation(maxDepth);}
    static int decode(byte[] bytes,int maxDepth,boolean ignore,boolean substitute,Sink sink){
        if(sink==null)throw new NullPointerException("sink");
        return new Php85JsonValidator(bytes,ignore,substitute,sink).parse(maxDepth);
    }
    // Keep validation grammar free of decoder publication; the byte lexer remains shared.
    private int parseValidation(int maxDepth){
        int[] states=new int[16];int level=0;
        while(true){
            int token=nextValidation();if(token==ERROR)return error;
            int state=states[level];
            if(state==DONE)return token==END?0:JsonConstants.JSON_ERROR_SYNTAX;
            if(state==ARRAY_FIRST&&token==']'||state==OBJECT_FIRST&&token=='}'){level--;continue;}
            if(state==ARRAY_FIRST&&token=='}'||state==ARRAY_END&&token=='}'
                    ||state==OBJECT_FIRST&&token==']'||state==OBJECT_END&&token==']')return JsonConstants.JSON_ERROR_STATE_MISMATCH;
            if(state==ARRAY_END||state==OBJECT_END){
                if(token==(state==ARRAY_END?']':'}')){level--;continue;}
                if(token!=',')return JsonConstants.JSON_ERROR_SYNTAX;
                states[level]=state==ARRAY_END?ARRAY_NEXT:OBJECT_KEY;continue;
            }
            if(state==OBJECT_FIRST||state==OBJECT_KEY){
                if(token!=STRING)return JsonConstants.JSON_ERROR_SYNTAX;
                states[level]=OBJECT_COLON;continue;
            }
            if(state==OBJECT_COLON){
                if(token!=':')return JsonConstants.JSON_ERROR_SYNTAX;
                states[level]=OBJECT_VALUE;continue;
            }
            // Only ROOT, array element, or object value states reach this value production.
            states[level]=state==ROOT?DONE:state==OBJECT_VALUE?OBJECT_END:ARRAY_END;
            if(token=='['||token=='{'){
                // PHP's parser starts depth at one, before opening the first container.
                if(level+1>=maxDepth)return JsonConstants.JSON_ERROR_DEPTH;
                if(++level==states.length)states=Arrays.copyOf(states,states.length*2);
                states[level]=token=='['?ARRAY_FIRST:OBJECT_FIRST;
            }else if(token!=VALUE&&token!=STRING)return JsonConstants.JSON_ERROR_SYNTAX;
        }
    }
    private int parse(int maxDepth){
        int[] states=new int[16];int level=0;
        while(true){
            int token=next(true);if(token==ERROR)return error;
            int state=states[level];
            if(state==DONE)return token==END?0:JsonConstants.JSON_ERROR_SYNTAX;
            if(state==ARRAY_FIRST&&token==']'||state==OBJECT_FIRST&&token=='}'){
                if(sink!=null&&(error=sink.end())!=0)return error;level--;continue;
            }
            if(state==ARRAY_FIRST&&token=='}'||state==ARRAY_END&&token=='}'
                    ||state==OBJECT_FIRST&&token==']'||state==OBJECT_END&&token==']')return JsonConstants.JSON_ERROR_STATE_MISMATCH;
            if(state==ARRAY_END||state==OBJECT_END){
                if(token==(state==ARRAY_END?']':'}')){
                    if(sink!=null&&(error=sink.end())!=0)return error;level--;continue;
                }
                if(token!=',')return JsonConstants.JSON_ERROR_SYNTAX;
                states[level]=state==ARRAY_END?ARRAY_NEXT:OBJECT_KEY;continue;
            }
            if(state==OBJECT_FIRST||state==OBJECT_KEY){
                if(token!=STRING)return JsonConstants.JSON_ERROR_SYNTAX;
                if(sink!=null)sink.key();
                states[level]=OBJECT_COLON;continue;
            }
            if(state==OBJECT_COLON){
                if(token!=':')return JsonConstants.JSON_ERROR_SYNTAX;
                states[level]=OBJECT_VALUE;continue;
            }
            // Only ROOT, array element, or object value states reach this value production.
            states[level]=state==ROOT?DONE:state==OBJECT_VALUE?OBJECT_END:ARRAY_END;
            if(token=='['||token=='{'){
                // PHP's parser starts depth at one, before opening the first container.
                if(level+1>=maxDepth)return JsonConstants.JSON_ERROR_DEPTH;
                if(sink!=null)sink.begin(token=='{');
                if(++level==states.length)states=Arrays.copyOf(states,states.length*2);
                states[level]=token=='['?ARRAY_FIRST:OBJECT_FIRST;
            }else{
                if(token!=VALUE&&token!=STRING)return JsonConstants.JSON_ERROR_SYNTAX;
                if(sink!=null&&(error=sink.value())!=0)return error;
            }
        }
    }
    private int nextValidation(){
        while(position<bytes.length){int c=at(position);if(c==' '||c=='\t'||c=='\r'||c=='\n')position++;else break;}
        if(position==bytes.length)return END;
        int c=at(position++);
        if(c=='"')return stringValidation();
        if(c=='{'||c=='}'||c=='['||c==']'||c==':'||c==',')return c;
        if(c=='n'&&literal("ull")||c=='t'&&literal("rue")||c=='f'&&literal("alse"))return VALUE;
        if(c=='-'||c>='0'&&c<='9'){
            position--;return number()?VALUE:fail(JsonConstants.JSON_ERROR_SYNTAX);
        }
        if(c<0x20)return fail(JsonConstants.JSON_ERROR_CTRL_CHAR);
        return fail(c>=0x80&&utf8(position-1)==0?JsonConstants.JSON_ERROR_UTF8:JsonConstants.JSON_ERROR_SYNTAX);
    }
    private int next(boolean decoding){
        while(position<bytes.length){int c=at(position);if(c==' '||c=='\t'||c=='\r'||c=='\n')position++;else break;}
        if(position==bytes.length)return END;
        int c=at(position++);
        if(c=='"')return string(decoding);
        if(c=='{'||c=='}'||c=='['||c==']'||c==':'||c==',')return c;
        if(c=='n'&&literal("ull")||c=='t'&&literal("rue")||c=='f'&&literal("alse")){
            if(decoding)sink.literal(c);return VALUE;
        }
        if(c=='-'||c>='0'&&c<='9'){
            int from=--position;if(!number())return fail(JsonConstants.JSON_ERROR_SYNTAX);
            return decoding&&sink.number(bytes,from,position-from)?STRING:VALUE;
        }
        if(c<0x20)return fail(JsonConstants.JSON_ERROR_CTRL_CHAR);
        return fail(c>=0x80&&utf8(position-1)==0?JsonConstants.JSON_ERROR_UTF8:JsonConstants.JSON_ERROR_SYNTAX);
    }
    private boolean literal(String tail){
        if(position+tail.length()>bytes.length)return false;
        for(int i=0;i<tail.length();i++)if(at(position+i)!=tail.charAt(i))return false;
        position+=tail.length();return true;
    }
    private boolean number(){
        if(at(position)=='-'){position++;if(position==bytes.length)return false;}
        if(at(position)=='0')position++;
        else{
            if(at(position)<'1'||at(position)>'9')return false;
            do{position++;}while(position<bytes.length&&digit(at(position)));
        }
        if(position<bytes.length&&at(position)=='.'){
            int start=position+1;if(start==bytes.length||!digit(at(start)))return true;
            position=start+1;while(position<bytes.length&&digit(at(position)))position++;
        }
        if(position<bytes.length&&(at(position)=='e'||at(position)=='E')){
            int start=position+1;
            if(start<bytes.length&&(at(start)=='+'||at(start)=='-'))start++;
            if(start==bytes.length||!digit(at(start)))return true;
            position=start+1;while(position<bytes.length&&digit(at(position)))position++;
        }
        return true;
    }
    private int stringValidation(){
        while(position<bytes.length){
            int c=at(position++);
            if(c=='"')return STRING;
            if(c<0x20)return fail(JsonConstants.JSON_ERROR_CTRL_CHAR);
            if(c=='\\'){
                if(position==bytes.length)return fail(JsonConstants.JSON_ERROR_SYNTAX);
                int escape=at(position++);
                if(escape=='u'){
                    int code=hex4();if(code<0)return fail(JsonConstants.JSON_ERROR_SYNTAX);
                    if(code>=0xdc00&&code<=0xdfff)return fail(JsonConstants.JSON_ERROR_UTF16);
                    if(code>=0xd800&&code<=0xdbff){
                        if(position+6>bytes.length||at(position)!='\\'||at(position+1)!='u')return fail(JsonConstants.JSON_ERROR_UTF16);
                        position+=2;int low=hex4();if(low<0xdc00||low>0xdfff)return fail(JsonConstants.JSON_ERROR_UTF16);
                    }
                }else if(escape!='"'&&escape!='\\'&&escape!='/'&&escape!='b'&&escape!='f'&&escape!='n'&&escape!='r'&&escape!='t')return fail(JsonConstants.JSON_ERROR_SYNTAX);
            }else if(c>=0x80){
                int length=utf8(position-1);
                if(length==0){if(!ignore)return fail(JsonConstants.JSON_ERROR_UTF8);}
                else position+=length-1;
            }
        }
        // The scanner's end sentinel occurs in the open quoted-string state.
        return fail(JsonConstants.JSON_ERROR_CTRL_CHAR);
    }
    private int string(boolean decoding){
        int segment=position;ByteArrayOutputStream decoded=null;
        while(position<bytes.length){
            int c=at(position++);
            if(c=='"'){
                if(decoding){
                    if(decoded==null)sink.string(bytes,segment,position-segment-1);
                    else{decoded.write(bytes,segment,position-segment-1);byte[] value=decoded.toByteArray();sink.string(value,0,value.length);}
                }
                return STRING;
            }
            if(c<0x20)return fail(JsonConstants.JSON_ERROR_CTRL_CHAR);
            if(c=='\\'){
                int escapeStart=position-1;
                if(position==bytes.length)return fail(JsonConstants.JSON_ERROR_SYNTAX);
                int escape=at(position++),code;
                if(escape=='u'){
                    code=hex4();if(code<0)return fail(JsonConstants.JSON_ERROR_SYNTAX);
                    if(code>=0xdc00&&code<=0xdfff)return fail(JsonConstants.JSON_ERROR_UTF16);
                    if(code>=0xd800&&code<=0xdbff){
                        if(position+6>bytes.length||at(position)!='\\'||at(position+1)!='u')return fail(JsonConstants.JSON_ERROR_UTF16);
                        position+=2;int low=hex4();if(low<0xdc00||low>0xdfff)return fail(JsonConstants.JSON_ERROR_UTF16);
                        code=0x10000+((code-0xd800)<<10)+(low-0xdc00);
                    }
                }else{
                    switch(escape){
                        case '"':case '\\':case '/':code=escape;break;
                        case 'b':code='\b';break;case 'f':code='\f';break;case 'n':code='\n';break;
                        case 'r':code='\r';break;case 't':code='\t';break;
                        default:return fail(JsonConstants.JSON_ERROR_SYNTAX);
                    }
                }
                if(decoding){
                    if(decoded==null)decoded=new ByteArrayOutputStream();
                    decoded.write(bytes,segment,escapeStart-segment);appendUtf8(decoded,code);segment=position;
                }
            }else if(c>=0x80){
                int length=utf8(position-1);
                if(length==0){
                    if(!ignore&&!substitute)return fail(JsonConstants.JSON_ERROR_UTF8);
                    if(decoding){
                        if(decoded==null)decoded=new ByteArrayOutputStream();
                        decoded.write(bytes,segment,position-1-segment);
                        if(substitute)appendUtf8(decoded,0xfffd);segment=position;
                    }
                }else position+=length-1;
            }
        }
        // The scanner's end sentinel occurs in the open quoted-string state.
        return fail(JsonConstants.JSON_ERROR_CTRL_CHAR);
    }
    private static void appendUtf8(ByteArrayOutputStream target,int code){
        if(code<=0x7f)target.write(code);
        else if(code<=0x7ff){target.write(0xc0|(code>>6));target.write(0x80|(code&0x3f));}
        else if(code<=0xffff){target.write(0xe0|(code>>12));target.write(0x80|((code>>6)&0x3f));target.write(0x80|(code&0x3f));}
        else{target.write(0xf0|(code>>18));target.write(0x80|((code>>12)&0x3f));target.write(0x80|((code>>6)&0x3f));target.write(0x80|(code&0x3f));}
    }
    private int hex4(){
        if(position+4>bytes.length)return -1;int value=0;
        for(int i=0;i<4;i++){
            int c=at(position++),digit=c>='0'&&c<='9'?c-'0':c>='a'&&c<='f'?c-'a'+10:c>='A'&&c<='F'?c-'A'+10:-1;
            if(digit<0)return -1;value=value*16+digit;
        }
        return value;
    }
    private int utf8(int offset){
        int first=at(offset),length=first>=0xc2&&first<=0xdf?2:first>=0xe0&&first<=0xef?3:first>=0xf0&&first<=0xf4?4:0;
        if(length==0||offset+length>bytes.length)return 0;
        int second=at(offset+1);
        if(second<0x80||second>0xbf||first==0xe0&&second<0xa0||first==0xed&&second>=0xa0
                ||first==0xf0&&second<0x90||first==0xf4&&second>=0x90)return 0;
        for(int i=2;i<length;i++)if(at(offset+i)<0x80||at(offset+i)>0xbf)return 0;
        return length;
    }
    private int at(int offset){return bytes[offset]&0xff;}
    private static boolean digit(int c){return c>='0'&&c<='9';}
    private int fail(int code){error=code;return ERROR;}
}
