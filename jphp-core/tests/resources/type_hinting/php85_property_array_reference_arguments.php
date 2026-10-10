<?php
class ARNullable {public ?array $v;}
class ARDefault {public ?array $v=null;}
class ARRequired {public array $v;}
class ARInt {public int $v;}
class ARScalar {public int $v=2;}
class ARString {public string $v='abc';}
class ARMixed {public mixed $v;}
class ARStatic {public static ?array $a;public static ?array $b;public static ?array $c;public static ?array $d;public static ?array $e;}
class ARMagic {public $log=[];function __get($n){$this->log[]='getter';return [3];}}
class ARMethods {function ref(&$v){$v=7;}function refs(&...$values){foreach($values as &$v)$v=9;}}
class ARKeyMagic {public $key;function __get($n){$this->key='';return ['x'=>3,''=>9];}}
function ar_ref(&$v){$v=7;}
function ar_value($v){return $v;}
function ar_receiver($o,&$log){$log[]='receiver';return $o;}
function ar_name(&$log){$log[]='property';return 'v';}
function ar_key(&$log){$log[]='key';return 0;}
function ar_key_state($o,&$log){$log[]=(new ReflectionProperty(get_class($o),'v'))->isInitialized($o)?'initialized':'undefined';return 0;}
function ar_initialize_key($o,&$log){$log[]='key';$o->v=[3];return 0;}
function ar_replace_receiver(&$o){$o=new ARDefault;$o->v=[9];return 0;}
function ar_return_object($o){return $o;}
function &ar_reference_object(&$o){return $o;}
function ar_initialize_static_key(){ARStatic::$e=[3];return 0;}
function ar_error($e){return [get_class($e),$e->getMessage()];}
function ar_case($label,$run){
    $diagnostics=[];
    set_error_handler(function($type,$message)use(&$diagnostics){$diagnostics[]=[$type,$message];return true;});
    try{return [$label,'ok',$run(),$diagnostics];}
    catch(Throwable $e){return [$label,get_class($e),$e->getMessage(),$diagnostics];}
    finally{restore_error_handler();}
}
$rows=[];
$rows[]=ar_case('nullable-uninitialized',function(){$o=new ARNullable;ar_ref($o->v[0]);return serialize($o->v);});
$rows[]=ar_case('nullable-default',function(){$o=new ARDefault;ar_ref($o->v[0]);return serialize($o->v);});
$rows[]=ar_case('required-array-uninitialized',function(){$o=new ARRequired;ar_ref($o->v[0]);return serialize($o->v);});
$rows[]=ar_case('mixed-uninitialized',function(){$o=new ARMixed;ar_ref($o->v[0]);return serialize($o->v);});
$rows[]=ar_case('value-uninitialized',function(){$o=new ARNullable;ar_value($o->v[0]);return 'NO_ERROR';});
$rows[]=ar_case('int-uninitialized',function(){$o=new ARInt;ar_ref($o->v[0]);return 'NO_ERROR';});
$rows[]=ar_case('scalar-initialized',function(){$o=new ARScalar;ar_ref($o->v[0]);return 'NO_ERROR';});
$rows[]=ar_case('string-offset',function(){$o=new ARString;ar_ref($o->v[0]);return 'NO_ERROR';});
$rows[]=ar_case('nested-string-offset',function(){$o=new ARString;ar_ref($o->v[0][0]);return 'NO_ERROR';});
$rows[]=ar_case('nested-nullable',function(){$o=new ARNullable;ar_ref($o->v[0][1]);return serialize($o->v);});
$rows[]=ar_case('append-nullable',function(){$o=new ARNullable;ar_ref($o->v[]);return serialize($o->v);});
$rows[]=ar_case('nested-append',function(){$o=new ARNullable;ar_ref($o->v[][1]);return serialize($o->v);});
$rows[]=ar_case('dynamic-value-append',function(){$o=new ARDefault;$fn='ar_value';$fn($o->v[]);return 'NO_ERROR';});
$rows[]=ar_case('static-nullable',function(){ar_ref(ARStatic::$a[0]);return serialize(ARStatic::$a);});
$rows[]=ar_case('static-append',function(){ar_ref(ARStatic::$b[]);return serialize(ARStatic::$b);});
$rows[]=ar_case('dynamic-static',function(){$c='ARStatic';$name='c';ar_ref($c::${$name}[0]);return serialize(ARStatic::$c);});
$rows[]=ar_case('static-value-uninitialized',function(){ar_value(ARStatic::$d[0]);return 'NO_ERROR';});
$rows[]=ar_case('dynamic-field-once',function(){$o=new ARNullable;$log=[];ar_ref(ar_receiver($o,$log)->{ar_name($log)}[ar_key($log)]);return [$log,serialize($o->v)];});
$rows[]=ar_case('uninitialized-until-key',function(){$o=new ARNullable;$log=[];ar_ref($o->v[ar_key_state($o,$log)]);return [$log,serialize($o->v)];});
$rows[]=ar_case('value-read-before-key',function(){$o=new ARNullable;$log=[];try{ar_value(ar_receiver($o,$log)->{ar_name($log)}[ar_key($log)]);}catch(Throwable $e){return [ar_error($e),$log];}return 'NO_ERROR';});
$rows[]=ar_case('method-reference',function(){$o=new ARNullable;(new ARMethods)->ref($o->v[0]);return serialize($o->v);});
$rows[]=ar_case('dynamic-function-reference',function(){$o=new ARNullable;$fn='ar_ref';$fn($o->v[0]);return serialize($o->v);});
$rows[]=ar_case('variadic-reference',function(){$o=new ARNullable;(new ARMethods)->refs($o->v[0],$o->v[1]);return serialize($o->v);});
$rows[]=ar_case('nested-reference-calls',function(){$o=new ARNullable;$other=new ARNullable;ar_ref($o->v[ar_value(ar_ref($other->v[0]))]);return [serialize($o->v),serialize($other->v)];});
$rows[]=ar_case('native-reference-array-element',function(){$o=new ARNullable;$o->v=[[]];array_push($o->v[0],7);return serialize($o->v);});
$rows[]=ar_case('borrowed-read-array-element',function(){$o=new ARNullable;$o->v=[[9]];return current($o->v[0]);});
$rows[]=ar_case('array-cow-reference',function(){$o=new ARNullable;$o->v=[[1]];$copy=$o->v;ar_ref($o->v[0][0]);return [serialize($o->v),serialize($copy)];});
$rows[]=ar_case('array-object-property',function(){$a=[new ARNullable];ar_ref($a[0]->v[0]);return serialize($a[0]->v);});
$rows[]=ar_case('missing-array-object-property',function(){$a=null;try{ar_ref($a[0]->x);}catch(Throwable $e){return [ar_error($e),serialize($a)];}return 'NO_ERROR';});
$rows[]=ar_case('invalid-array-key',function(){$o=new ARNullable;ar_ref($o->v[[]]);return 'NO_ERROR';});
$rows[]=ar_case('local-root-initialization',function(){$a=null;ar_ref($a[0]);return serialize($a);});
$rows[]=ar_case('local-root-nested',function(){$a=null;ar_ref($a[0][1]);return serialize($a);});
$rows[]=ar_case('local-append',function(){$a=null;ar_ref($a[]);return serialize($a);});
$rows[]=ar_case('local-skipped-branch',function(){$a=null;if(false)ar_ref($a[0]);$a=3;return $a;});
$rows[]=ar_case('local-value-skipped-branch',function(){$a=null;if(false)ar_value($a[0]);$a=3;return $a;});
$rows[]=ar_case('arithmetic-rvalue-remains-value',function(){$a=[1];ar_ref($a[0]+1);return 'NO_ERROR';});
$rows[]=ar_case('key-rvalue-read-remains-ordinary',function(){$o=new ARNullable;$key=new ARNullable;ar_ref($o->v[$key->v[0]]);return 'NO_ERROR';});
$rows[]=ar_case('magic-value-key-before-getter',function(){$o=new ARMagic;$v=ar_value($o->v[ar_key($o->log)]);return [$v,$o->log];});
$rows[]=ar_case('magic-reference-key-before-getter',function(){$o=new ARMagic;ar_ref($o->v[ar_key($o->log)]);return $o->log;});
$rows[]=ar_case('value-key-initializes-property',function(){$o=new ARNullable;$log=[];$v=ar_value($o->v[ar_initialize_key($o,$log)]);return [$v,$log];});
$rows[]=ar_case('receiver-variable-read-after-key',function(){$o=new ARDefault;$o->v=[3];$prior=$o;$v=ar_value($o->v[ar_replace_receiver($o)]);return [$v,serialize($o->v),serialize($prior->v)];});
$rows[]=ar_case('static-value-key-initializes-property',function(){return ar_value(ARStatic::$e[ar_initialize_static_key()]);});
$rows[]=ar_case('receiver-variable-reference-after-key',function(){$o=new ARDefault;$o->v=[3];$prior=$o;ar_ref($o->v[ar_replace_receiver($o)]);return [serialize($o->v),serialize($prior->v)];});
$rows[]=ar_case('receiver-value-call-preserves-object',function(){$o=new ARDefault;$o->v=[3];$prior=$o;$v=ar_value(ar_return_object($o)->v[ar_replace_receiver($o)]);return [$v,serialize($o->v),serialize($prior->v)];});
$rows[]=ar_case('receiver-value-call-reference-preserves-object',function(){$o=new ARDefault;$o->v=[3];$prior=$o;ar_ref(ar_return_object($o)->v[ar_replace_receiver($o)]);return [serialize($o->v),serialize($prior->v)];});
$rows[]=ar_case('receiver-reference-call-read-after-key',function(){$o=new ARDefault;$o->v=[3];$prior=$o;$v=ar_value(ar_reference_object($o)->v[ar_replace_receiver($o)]);return [$v,serialize($o->v),serialize($prior->v)];});
$rows[]=ar_case('receiver-reference-call-reference-after-key',function(){$o=new ARDefault;$o->v=[3];$prior=$o;ar_ref(ar_reference_object($o)->v[ar_replace_receiver($o)]);return [serialize($o->v),serialize($prior->v)];});
$rows[]=ar_case('null-receiver-variable-read-after-key',function(){$o=null;return ar_value($o->v[ar_replace_receiver($o)]);});
$rows[]=ar_case('null-receiver-variable-reference-after-key',function(){$o=null;ar_ref($o->v[ar_replace_receiver($o)]);return serialize($o->v);});
$rows[]=ar_case('null-key-reference-auto-initialization',function(){$o=new ARDefault;ar_ref($o->v[null]);return serialize($o->v);});
$rows[]=ar_case('null-key-value-existing',function(){$o=new ARDefault;$o->v=[''=>9];return ar_value($o->v[null]);});
$rows[]=ar_case('null-key-value-missing',function(){$o=new ARDefault;$o->v=[];return ar_value($o->v[null]);});
$rows[]=ar_case('null-key-reference-nested',function(){$o=new ARDefault;ar_ref($o->v[null][null]);return serialize($o->v);});
$rows[]=ar_case('null-key-value-null-container',function(){$o=new ARDefault;return ar_value($o->v[null]);});
$rows[]=ar_case('null-key-value-scalar-container',function(){$v=2;return ar_value($v[null]);});
$rows[]=ar_case('null-key-reference-scalar-container',function(){$v=2;ar_ref($v[null]);return 'NO_ERROR';});
$rows[]=ar_case('null-key-reference-typed-int',function(){$o=new ARInt;ar_ref($o->v[null]);return 'NO_ERROR';});
$rows[]=ar_case('null-key-value-uninitialized',function(){$o=new ARNullable;return ar_value($o->v[null]);});
$rows[]=ar_case('null-key-value-string-offset',function(){$o=new ARString;return ar_value($o->v[null]);});
$rows[]=ar_case('null-key-reference-string-offset',function(){$o=new ARString;ar_ref($o->v[null]);return 'NO_ERROR';});
$rows[]=ar_case('null-key-reference-false-container',function(){$v=false;ar_ref($v[null]);return serialize($v);});
$rows[]=ar_case('null-key-value-false-container',function(){$v=false;return ar_value($v[null]);});
$rows[]=ar_case('variable-key-read-after-magic-getter',function(){$key='x';$o=new ARKeyMagic;$o->key=&$key;return ar_value($o->v[$key]);});
return $rows;
