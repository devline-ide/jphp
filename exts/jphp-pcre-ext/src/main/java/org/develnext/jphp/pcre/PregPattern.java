package org.develnext.jphp.pcre;
import java.util.Arrays;
/** PHP's byte delimiter/modifier envelope; the regex grammar is parsed only by PCRE2. */
record PregPattern(byte[] bytes,int options,int extraOptions) {
    static final class Problem extends Exception {Problem(String message){super(message);}}
    static PregPattern parse(byte[] input)throws Problem {
        int first=0;while(first<input.length&&space(input[first]&255))first++;
        if(first==input.length)throw new Problem("Empty regular expression");
        int open=input[first]&255;
        if(open==0||open=='\\'||open>='a'&&open<='z'||open>='A'&&open<='Z'||open>='0'&&open<='9')
            throw new Problem("Delimiter must not be alphanumeric, backslash, or NUL byte");
        int close=switch(open){case '('->')';case '['->']';case '{'->'}';case '<'->'>';default->open;};
        int end=first+1,level=1;
        for(;end<input.length;end++) {
            int b=input[end]&255;
            if(b=='\\'&&end+1<input.length){end++;continue;}
            if(b==close){if(open==close||--level==0)break;}
            else if(b==open&&open!=close)level++;
        }
        if(end==input.length)throw new Problem((open==close?"No ending delimiter '":"No ending matching delimiter '")+(char)close+"' found");
        int options=0,extra=0;
        for(int i=end+1;i<input.length;i++)switch(input[i]&255){
            case 'i'->options|=0x00000008; case 'm'->options|=0x00000400; case 's'->options|=0x00000020;
            case 'x'->options|=0x00000080; case 'n'->options|=0x00002000; case 'A'->options|=0x80000000;
            case 'D'->options|=0x00000010; case 'U'->options|=0x00040000; case 'J'->options|=0x00000040;
            case 'u'->options|=0x00080000|0x00020000|0x00100000; // UTF + UCP + NEVER_BACKSLASH_C
            case 'r'->extra|=0x00000080; case 'S','X',' ','\r','\n'->{ }
            case 0->throw new Problem("NUL byte is not a valid modifier");
            default->throw new Problem("Unknown modifier '"+(char)(input[i]&255)+"'");
        }
        return new PregPattern(Arrays.copyOfRange(input,first+1,end),options,extra);
    }
    private static boolean space(int b){return b==' '||b=='\t'||b=='\n'||b=='\r'||b=='\f'||b==11;}
}
