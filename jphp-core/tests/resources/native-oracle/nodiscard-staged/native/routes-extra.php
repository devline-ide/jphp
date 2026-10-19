<?php
require __DIR__.'/helper.php';
#[NoDiscard('consume')] function marked($v){emit('body',$v);return 7;}
class C{#[NoDiscard] function method($v){emit('method-body',$v);return 8;}#[NoDiscard] static function statik($v){emit('static-body',$v);return 9;}}
emit('route','method');$o=new C;$o->method(1);
emit('route','static');C::statik(2);
emit('route','captured-native-cuf');$cuf=call_user_func(...);$cuf('marked',3);
emit('route','dynamic-native-cuf');$cuf='call_user_func';$cuf('marked',4);
emit('route','dynamic-native-cufa');$cufa='call_user_func_array';$cufa('marked',[5]);
emit('route','named');marked(v:6);
emit('route','named-cufa');call_user_func_array('marked',['v'=>7]);
emit('route','reflection-args');(new ReflectionFunction('marked'))->invokeArgs([8]);
emit('route','used-cuf');emit('result',call_user_func('marked',9));
emit('route','used-cufa');emit('result',call_user_func_array('marked',[10]));
emit('route','reference');$GLOBALS['cell']=1;#[NoDiscard] function &cell(){emit('ref-body',true);return $GLOBALS['cell'];}cell();$r=&cell();$r=12;emit('cell',$GLOBALS['cell']);
emit('route','global-constructor-name');#[NoDiscard] function __construct(){emit('global-body',true);return 1;}__construct();
