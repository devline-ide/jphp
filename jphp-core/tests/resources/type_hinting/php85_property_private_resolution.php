<?php
class PSRParent {private int $hidden=1;function parentValue(){return $this->hidden;}}
class PSRChild extends PSRParent {function childWrite($v){$this->hidden=$v;}function childValue(){return $this->hidden;}}
class PSRMagicParent {private $hidden=7;public $events=[];function parentValue(){return $this->hidden;}function __get($n){$this->events[]='get:'.$n;return 11;}function __set($n,$v){$this->events[]='set:'.$n.':'.$v;}function __isset($n){$this->events[]='isset:'.$n;return true;}function __unset($n){$this->events[]='unset:'.$n;}}
class PSRMagicChild extends PSRMagicParent {}
class PSRMiddle extends PSRParent {function middleValue(){return $this->hidden;}}
class PSRPublicChild extends PSRMiddle {public $hidden=9;}
$rows=[];$events=[];set_error_handler(function($s,$m)use(&$events){$events[]=[$s,$m];return true;});
try {
$o=new PSRChild;$before=[isset($o->hidden),empty($o->hidden),$o->parentValue()];$o->childWrite('child');$ref=&$o->hidden;$ref='alias';$written=[$o->hidden,$o->childValue(),$o->parentValue(),isset($o->hidden),empty($o->hidden)];unset($o->hidden);$after=[isset($o->hidden),empty($o->hidden),$o->parentValue(),$ref,$o->hidden];unset($o->hidden);$rows[]=[$before,$written,$after,$events,serialize($o)];
$m=new PSRMagicChild;$read=$m->hidden;$isset=isset($m->hidden);$empty=empty($m->hidden);$m->hidden=12;unset($m->hidden);$rows[]=[$read,$isset,$empty,$m->parentValue(),$m->events,property_exists($m,'hidden')];
$p=new PSRPublicChild;$rows[]=[$p->hidden,$p->middleValue(),$p->parentValue()];
} finally {restore_error_handler();}
return $rows;
