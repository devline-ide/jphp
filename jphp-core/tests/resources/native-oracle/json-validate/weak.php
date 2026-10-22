<?php

$events=[];
set_error_handler(function($n,$s)use(&$events){$events[]=$n.':'.$s;return true;});
class JsonText {function __toString(): string {global $events;$events[]='toString';return 'null';}}
function row($id,$args) {global $events;json_decode('{');$events=[];$out=null;$exception=null;
 try {$out=json_validate(...$args);}catch(Throwable $e){$exception=[get_class($e),$e->getMessage()];}
 $code=json_last_error();$msg=json_last_error_msg();$ev=$events;
 echo json_encode([$id,$out,$exception,$code,$msg,$ev],JSON_UNESCAPED_UNICODE|JSON_UNESCAPED_SLASHES),"\n";
}
row("null",['null']);
row("scalar-number",['-12.5e+2']);
row("overflow-number-valid",['1e99999']);
row("containers",['{"a":[true,false,null],"a":{}}']);
row("nul-property-valid",['{"\u0000":1}']);
row("empty",['']);
row("whitespace",[" \t\r\n"]);
row("comments",['[/*x*/1]']);
row("single-quotes",["['x']"]);
row("unquoted-key",['{x:1}']);
row("trailing-comma",['[1,]']);
row("two-values",['true false']);
row("leading-zero",['01']);
row("incomplete-exponent",['1e+']);
row("nan",['NaN']);
row("mismatch",['[1}']);
row("control-in-string",["\"a\n\""]);
row("utf8-valid",[hex2bin('22e282acf09f988022')]);
row("utf8-invalid",[hex2bin('22c32822')]);
row("utf8-ignore",[hex2bin('22c32822'),512,JSON_INVALID_UTF8_IGNORE]);
row("utf8-outside-ignore",[hex2bin('ff'),512,JSON_INVALID_UTF8_IGNORE]);
row("utf16-high",['"\ud800"']);
row("utf16-low",['"\udc00"']);
row("utf16-pair",['"\ud83d\ude00"']);
row("empty-depth-zero",['',0]);
row("nonempty-depth-zero",['0',0]);
row("depth-one-array",['[]',1]);
row("depth-two-array",['[]',2]);
row("depth-nested",['[[]]',2]);
row("depth-too-large",['0',2147483648]);
row("flags-before-depth",['',0,1]);
row("flags-throw-invalid",['null',512,JSON_THROW_ON_ERROR]);
row("weak-string-number",[123]);
row("weak-null",[null]);
row("weak-depth-float-before-flag",['0',1.5,1]);
row("bad-depth-before-flags",['0',[],1]);
row("stringable-before-bad-depth",[new JsonText,[],1]);
row("named-default-hole",['json'=>'[]','flags'=>JSON_INVALID_UTF8_IGNORE]);
