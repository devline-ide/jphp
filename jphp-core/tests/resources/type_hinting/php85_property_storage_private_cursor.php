<?php
class PSTPrivateScope {private $hidden=1;public $show=2;function inside(){return [property_exists($this,'hidden'),property_exists(PSTPrivateChild::class,'hidden'),property_exists(PSTPrivateScope::class,'hidden')];}function value(){return $this->hidden;}}
class PSTPrivateChild extends PSTPrivateScope {function child(){return property_exists($this,'hidden');}}
class PSTPointer {public $x=4;public $y=1;}
$o=new PSTPrivateChild;$before=[property_exists($o,'hidden'),$o->inside(),$o->child()];$events=[];set_error_handler(function($s,$m)use(&$events){$events[]=[$s,$m];return true;});try{$o->hidden=9;}finally{restore_error_handler();}$after=[property_exists($o,'hidden'),$o->inside(),$o->child(),$o->value(),$o->hidden];
$a=(array)new PSTPointer;$cursor=[reset($a),prev($a),current($a),next($a),current($a),end($a),next($a),prev($a),current($a),reset($a),next($a)];return [$before,$after,$events,$cursor];
