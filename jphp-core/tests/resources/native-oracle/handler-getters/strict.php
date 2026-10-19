<?php
declare(strict_types=1);
$rows=[];$events=[];
$rows[]=['exists',function_exists('get_error_handler'),function_exists('get_exception_handler')];
$rows[]=['types',(string)(new ReflectionFunction('get_error_handler'))->getReturnType(),(string)(new ReflectionFunction('get_exception_handler'))->getReturnType()];
$rows[]=['default',get_error_handler(),get_exception_handler()];
$a=function($n,$m)use(&$events){$events[]=['A',$n,$m,get_error_handler()===null];return true;};
$b=function($n,$m)use(&$events){$events[]=['B',$n,$m,get_error_handler()===null];return true;};
$z=function($n,$m)use(&$events){$events[]=['Z'];return true;};
$rows[]=['set-a',set_error_handler($a,E_USER_NOTICE)===null,get_error_handler()===$a,get_error_handler()===$a];
$rows[]=['set-b',set_error_handler($b,E_USER_WARNING)===$a,get_error_handler()===$b];
$rows[]=['mask-zero',set_error_handler($z,0)===$b,get_error_handler()===$z];
@trigger_error('mask0',E_USER_WARNING);restore_error_handler();
@trigger_error('mask-wrong',E_USER_NOTICE);trigger_error('hit-b',E_USER_WARNING);
$rows[]=['mask-preserved',get_error_handler()===$b,$events];
$rows[]=['temporary-null',set_error_handler(null)===$b,get_error_handler()===null,restore_error_handler(),get_error_handler()===$b];
restore_error_handler();trigger_error('hit-a',E_USER_NOTICE);
$rows[]=['restore-a',get_error_handler()===$a,$events];
$rows[]=['restore-empty',restore_error_handler(),get_error_handler()===null,restore_error_handler(),get_error_handler()===null];
function HandlerFunction($n,$m){return true;}
class HandlerCallable {public static function stat($n,$m){return true;}public static function other($n,$m){return true;}public function method($n,$m){return true;}public function __invoke($n,$m){return true;}}
$obj=new HandlerCallable();
foreach(['HandlerFunction',['HandlerCallable','stat'],[$obj,'method'],$obj,$a] as $h) {
 set_error_handler($h);$r=get_error_handler();$same=$r===$h;
 if(is_array($r))$r[1]='other';
 $rows[]=['error-callable',get_debug_type($h),$same,get_error_handler()===$h];restore_error_handler();
}
$alias=$a;set_error_handler($alias);$alias=$b;$rows[]=['error-snapshot',get_error_handler()===$a];restore_error_handler();
$ea=function($e){};$eb=function($e){};$ec=function($e){};
$rows[]=['exception-set',set_exception_handler($ea)===null,set_exception_handler($eb)===$ea,set_exception_handler($ec)===$eb,get_exception_handler()===$ec,get_exception_handler()===$ec];
$rows[]=['exception-null',set_exception_handler(null)===$ec,get_exception_handler()===null,restore_exception_handler(),get_exception_handler()===$ec];
$rows[]=['exception-pop-c',restore_exception_handler(),get_exception_handler()===$eb];
$rows[]=['exception-pop-b',restore_exception_handler(),get_exception_handler()===$ea];
$rows[]=['exception-pop-a',restore_exception_handler(),get_exception_handler()===null];
$rows[]=['exception-empty',restore_exception_handler(),get_exception_handler()===null,restore_exception_handler(),get_exception_handler()===null];
foreach(['HandlerFunction',['HandlerCallable','stat'],[$obj,'method'],$obj,$ea] as $h) {
 set_exception_handler($h);$r=get_exception_handler();$same=$r===$h;
 if(is_array($r))$r[1]='other';
 $rows[]=['exception-callable',get_debug_type($h),$same,get_exception_handler()===$h];restore_exception_handler();
}
$alias=$ea;set_exception_handler($alias);$alias=$eb;$rows[]=['exception-snapshot',get_exception_handler()===$ea];restore_exception_handler();
foreach(['get_error_handler','get_exception_handler'] as $name) {try{$name(1);}catch(Throwable $e){$rows[]=['arity',$name,get_class($e),$e->getMessage()];}}
$rows[]=['final',get_error_handler(),get_exception_handler()];
echo json_encode($rows,JSON_UNESCAPED_SLASHES),"\n";
