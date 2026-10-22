<?php
function mixedRow($name,$value){echo json_encode([$name,$value]),"\n";}
function missingMixed(){return static function($o)use(&$missing){return $missing;};}
function survivingMixed($v){$f=static function($o)use(&$v){return $v;};unset($v);return $f;}
function pairMixed($v){$a=static function($o)use(&$v){$v=9;return $v;};$b=static function($o)use(&$v){return $v;};return [$a,$b];}
function snapshotMixed($v){$anchor=static function($o)use(&$v){};$copy=static function($o)use($v){return $v;};$v=9;return $copy;}
function warningMixed($v){return static function($o)use(&$v,$absent){};}
$events=[];set_error_handler(function($n,$m)use(&$events){$events[]=$m;return true;});
$f=missingMixed();mixedRow('missing-null-no-warning',[$events,$f(null),$f(null)]);
$f=survivingMixed(7);mixedRow('survives-source-unset',$f(null));
[$a,$b]=pairMixed(7);mixedRow('shared-cell-write-after-return',[$b(null),$a(null),$b(null)]);
$f=snapshotMixed(7);mixedRow('value-from-cell-before-mutation',$f(null));
$events=[];$f=warningMixed(7);mixedRow('warning-after-cell',[$events,$f instanceof Closure]);
restore_error_handler();set_error_handler(function($n,$m){throw new RuntimeException('cell-warning');});
try{warningMixed(7);}catch(Throwable $e){mixedRow('throw-after-cell',[$e::class,$e->getMessage()]);}
restore_error_handler();
