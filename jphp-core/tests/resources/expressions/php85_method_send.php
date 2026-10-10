<?php
function ms_effect($tag = 'effect', $value = 2) { global $msEvents; $msEvents[]=$tag; return $value; }
function ms_factory($value = 1) { return ms_effect('factory', $value); }
function &ms_reference() { global $msCell; ms_effect('ref-factory'); return $msCell; }
function ms_ref(&$p, $other=0) { ms_effect('body'); $old=$p; $p=9; return [$old,$other]; }
function ms_name(&$object, $replacement, $name='m') { ms_effect('name'); $object=$replacement; return $name; }
function ms_receiver($object) { ms_effect('receiver'); return $object; }
function ms_capture($name,$fn,$throws=false) {
    global $msEvents,$msCell; $msEvents=[];$msCell=3;$value=null;
    set_error_handler(function($severity,$message) use($throws) {
        global $msEvents;
        $position=strpos($message,', called in ');if($position!==false)$message=substr($message,0,$position);
        $msEvents[]=[$severity,$message];if($throws)throw new Exception('handler-stop');return true;
    });
    try{$value=$fn();}catch(Throwable $error){$message=$error->getMessage();$position=strpos($message,', called in ');if($position!==false)$message=substr($message,0,$position);$msEvents[]=[get_class($error),$message];}
    restore_error_handler();return [$name,$value,$msCell,$msEvents];
}
class MsTarget {
    public $p=4;
    public function m(&$p,$other=0){return ms_ref($p,$other);}
    public static function s(&$p,$other=0){return ms_ref($p,$other);}
    public function value($p,$other=0){ms_effect('value-body');return [$p,$other];}
    public static function values($p,$other=0){ms_effect('value-body');return [$p,$other];}
    public function typed(int $p,$other=0){ms_effect('typed-body');return [$p,$other];}
    private function hidden(&$p,$other=0){return ms_ref($p,$other);}
    protected static function restricted(&$p,$other=0){return ms_ref($p,$other);}
}
class MsCtor { public $result=null;public $default=4;public function __construct(&$p,$other=0){$this->result=ms_ref($p,$other);} }
class MsCtorChild extends MsCtor {}
class MsPrivate {private function __construct($p=0){ms_effect('private-body');}public static function create(){return get_class(new self(ms_effect()));}}
class MsProtected {protected function __construct($p=0){ms_effect('protected-body');}}
class MsProtectedChild extends MsProtected {public static function create(){return get_class(new parent(ms_effect()));}}
abstract class MsAbstract {public function __construct($p=0){ms_effect('abstract-body');}}
interface MsInterface {}
trait MsTrait {}
class MsNoCtor {public $default=4;}
class MsOldCtor {public $p=4;public function MsOldCtor(&$p){ms_effect('old-body');$p=9;}}
class MsMagic {
    private function hidden(&$p,$other=0){return ms_ref($p,$other);}
    private static function restricted(&$p,$other=0){return ms_ref($p,$other);}
    public function __call($name,$args){ms_effect('magic-body');return [$name,$args];}
    public static function __callStatic($name,$args){ms_effect('magic-static-body');return [$name,$args];}
}
class MsBase {
    private function secret(&$p,$other=0){return ms_ref($p,$other);}
    public function m($p=0){return ['base',static::class,$p];}
    public static function s($p=0){return ['static',static::class,$p];}
    public function scoped($object){$x=3;$result=$object->secret($x,ms_effect());return [$result,$x];}
    public function makeClosure(){return function(){return get_class(new self);};}
}
class MsChild extends MsBase {
    public function m($p=0){return ['child',static::class,$p];}
    public function secret($p,$other=0){return ['child-secret',$p,$other];}
    public function forward(){return [parent::m(3),self::m(4),static::m(5),parent::s(6),self::s(7),static::s(8),MsBase::s(9),get_class(new parent),get_class(new self),get_class(new static)];}
}
class MsOther {public function secret($p,$other=0){return ['other-secret',$p,$other];}public function m($p=0){return ['other',$p];}}
class MsMagicChild extends MsMagic {
    public function forwardMissing(){return [parent::missing(ms_effect()),parent::restricted(ms_effect()),self::missing(ms_effect()),MsMagic::missing(ms_effect())];}
}
class MsCtorThrow {public function __construct($p){ms_effect('ctor-body');throw new Exception('constructor-stop');}}
abstract class MsAbstractMethod {abstract public function method($p=0);}
class MsConcreteMethod extends MsAbstractMethod {public function method($p=0) {return $p;}public function run(){return parent::method(ms_effect());}}
$rows=[];
$rows[]=ms_capture("object:literal",function(){$x=3;$a=[4];$box=new MsTarget;$name="m";$stat="s";$class="MsTarget";$result=$box->m(1,ms_effect());return [$result,$x,$a,$box->p];},false);
$rows[]=ms_capture("object:variable",function(){$x=3;$a=[4];$box=new MsTarget;$name="m";$stat="s";$class="MsTarget";$result=$box->m($x,ms_effect());return [$result,$x,$a,$box->p];},false);
$rows[]=ms_capture("object:array-cell",function(){$x=3;$a=[4];$box=new MsTarget;$name="m";$stat="s";$class="MsTarget";$result=$box->m($a[0],ms_effect());return [$result,$x,$a,$box->p];},false);
$rows[]=ms_capture("object:property",function(){$x=3;$a=[4];$box=new MsTarget;$name="m";$stat="s";$class="MsTarget";$result=$box->m($box->p,ms_effect());return [$result,$x,$a,$box->p];},false);
$rows[]=ms_capture("object:factory",function(){$x=3;$a=[4];$box=new MsTarget;$name="m";$stat="s";$class="MsTarget";$result=$box->m(ms_factory(),ms_effect());return [$result,$x,$a,$box->p];},false);
$rows[]=ms_capture("object:factory:throw",function(){$x=3;$a=[4];$box=new MsTarget;$name="m";$stat="s";$class="MsTarget";$result=$box->m(ms_factory(),ms_effect());return [$result,$x,$a,$box->p];},true);
$rows[]=ms_capture("object:reference",function(){$x=3;$a=[4];$box=new MsTarget;$name="m";$stat="s";$class="MsTarget";$result=$box->m(ms_reference(),ms_effect());return [$result,$x,$a,$box->p];},false);
$rows[]=ms_capture("object:new",function(){$x=3;$a=[4];$box=new MsTarget;$name="m";$stat="s";$class="MsTarget";$result=$box->m(new stdClass,ms_effect());return [$result,$x,$a,$box->p];},false);
$rows[]=ms_capture("object:new:throw",function(){$x=3;$a=[4];$box=new MsTarget;$name="m";$stat="s";$class="MsTarget";$result=$box->m(new stdClass,ms_effect());return [$result,$x,$a,$box->p];},true);
$rows[]=ms_capture("object:assignment",function(){$x=3;$a=[4];$box=new MsTarget;$name="m";$stat="s";$class="MsTarget";$result=$box->m(($x=7),ms_effect());return [$result,$x,$a,$box->p];},false);
$rows[]=ms_capture("object:array-assignment",function(){$x=3;$a=[4];$box=new MsTarget;$name="m";$stat="s";$class="MsTarget";$result=$box->m(($a[0]=7),ms_effect());return [$result,$x,$a,$box->p];},false);
$rows[]=ms_capture("object:conditional",function(){$x=3;$a=[4];$box=new MsTarget;$name="m";$stat="s";$class="MsTarget";$result=$box->m((true?$x:2),ms_effect());return [$result,$x,$a,$box->p];},false);
$rows[]=ms_capture("object:coalesce",function(){$x=3;$a=[4];$box=new MsTarget;$name="m";$stat="s";$class="MsTarget";$result=$box->m(($x??2),ms_effect());return [$result,$x,$a,$box->p];},false);
$rows[]=ms_capture("object:strlen",function(){$x=3;$a=[4];$box=new MsTarget;$name="m";$stat="s";$class="MsTarget";$result=$box->m(strlen("x"),ms_effect());return [$result,$x,$a,$box->p];},false);
$rows[]=ms_capture("object:count",function(){$x=3;$a=[4];$box=new MsTarget;$name="m";$stat="s";$class="MsTarget";$result=$box->m(count([1]),ms_effect());return [$result,$x,$a,$box->p];},false);
$rows[]=ms_capture("object:array",function(){$x=3;$a=[4];$box=new MsTarget;$name="m";$stat="s";$class="MsTarget";$result=$box->m([1,2],ms_effect());return [$result,$x,$a,$box->p];},false);
$rows[]=ms_capture("object:null",function(){$x=3;$a=[4];$box=new MsTarget;$name="m";$stat="s";$class="MsTarget";$result=$box->m(null,ms_effect());return [$result,$x,$a,$box->p];},false);
$rows[]=ms_capture("object-dynamic:literal",function(){$x=3;$a=[4];$box=new MsTarget;$name="m";$stat="s";$class="MsTarget";$result=$box->{$name}(1,ms_effect());return [$result,$x,$a,$box->p];},false);
$rows[]=ms_capture("object-dynamic:variable",function(){$x=3;$a=[4];$box=new MsTarget;$name="m";$stat="s";$class="MsTarget";$result=$box->{$name}($x,ms_effect());return [$result,$x,$a,$box->p];},false);
$rows[]=ms_capture("object-dynamic:array-cell",function(){$x=3;$a=[4];$box=new MsTarget;$name="m";$stat="s";$class="MsTarget";$result=$box->{$name}($a[0],ms_effect());return [$result,$x,$a,$box->p];},false);
$rows[]=ms_capture("object-dynamic:property",function(){$x=3;$a=[4];$box=new MsTarget;$name="m";$stat="s";$class="MsTarget";$result=$box->{$name}($box->p,ms_effect());return [$result,$x,$a,$box->p];},false);
$rows[]=ms_capture("object-dynamic:factory",function(){$x=3;$a=[4];$box=new MsTarget;$name="m";$stat="s";$class="MsTarget";$result=$box->{$name}(ms_factory(),ms_effect());return [$result,$x,$a,$box->p];},false);
$rows[]=ms_capture("object-dynamic:factory:throw",function(){$x=3;$a=[4];$box=new MsTarget;$name="m";$stat="s";$class="MsTarget";$result=$box->{$name}(ms_factory(),ms_effect());return [$result,$x,$a,$box->p];},true);
$rows[]=ms_capture("object-dynamic:reference",function(){$x=3;$a=[4];$box=new MsTarget;$name="m";$stat="s";$class="MsTarget";$result=$box->{$name}(ms_reference(),ms_effect());return [$result,$x,$a,$box->p];},false);
$rows[]=ms_capture("object-dynamic:new",function(){$x=3;$a=[4];$box=new MsTarget;$name="m";$stat="s";$class="MsTarget";$result=$box->{$name}(new stdClass,ms_effect());return [$result,$x,$a,$box->p];},false);
$rows[]=ms_capture("object-dynamic:new:throw",function(){$x=3;$a=[4];$box=new MsTarget;$name="m";$stat="s";$class="MsTarget";$result=$box->{$name}(new stdClass,ms_effect());return [$result,$x,$a,$box->p];},true);
$rows[]=ms_capture("object-dynamic:assignment",function(){$x=3;$a=[4];$box=new MsTarget;$name="m";$stat="s";$class="MsTarget";$result=$box->{$name}(($x=7),ms_effect());return [$result,$x,$a,$box->p];},false);
$rows[]=ms_capture("object-dynamic:array-assignment",function(){$x=3;$a=[4];$box=new MsTarget;$name="m";$stat="s";$class="MsTarget";$result=$box->{$name}(($a[0]=7),ms_effect());return [$result,$x,$a,$box->p];},false);
$rows[]=ms_capture("object-dynamic:conditional",function(){$x=3;$a=[4];$box=new MsTarget;$name="m";$stat="s";$class="MsTarget";$result=$box->{$name}((true?$x:2),ms_effect());return [$result,$x,$a,$box->p];},false);
$rows[]=ms_capture("object-dynamic:coalesce",function(){$x=3;$a=[4];$box=new MsTarget;$name="m";$stat="s";$class="MsTarget";$result=$box->{$name}(($x??2),ms_effect());return [$result,$x,$a,$box->p];},false);
$rows[]=ms_capture("object-dynamic:strlen",function(){$x=3;$a=[4];$box=new MsTarget;$name="m";$stat="s";$class="MsTarget";$result=$box->{$name}(strlen("x"),ms_effect());return [$result,$x,$a,$box->p];},false);
$rows[]=ms_capture("object-dynamic:count",function(){$x=3;$a=[4];$box=new MsTarget;$name="m";$stat="s";$class="MsTarget";$result=$box->{$name}(count([1]),ms_effect());return [$result,$x,$a,$box->p];},false);
$rows[]=ms_capture("object-dynamic:array",function(){$x=3;$a=[4];$box=new MsTarget;$name="m";$stat="s";$class="MsTarget";$result=$box->{$name}([1,2],ms_effect());return [$result,$x,$a,$box->p];},false);
$rows[]=ms_capture("object-dynamic:null",function(){$x=3;$a=[4];$box=new MsTarget;$name="m";$stat="s";$class="MsTarget";$result=$box->{$name}(null,ms_effect());return [$result,$x,$a,$box->p];},false);
$rows[]=ms_capture("static:literal",function(){$x=3;$a=[4];$box=new MsTarget;$name="m";$stat="s";$class="MsTarget";$result=MsTarget::s(1,ms_effect());return [$result,$x,$a,$box->p];},false);
$rows[]=ms_capture("static:variable",function(){$x=3;$a=[4];$box=new MsTarget;$name="m";$stat="s";$class="MsTarget";$result=MsTarget::s($x,ms_effect());return [$result,$x,$a,$box->p];},false);
$rows[]=ms_capture("static:array-cell",function(){$x=3;$a=[4];$box=new MsTarget;$name="m";$stat="s";$class="MsTarget";$result=MsTarget::s($a[0],ms_effect());return [$result,$x,$a,$box->p];},false);
$rows[]=ms_capture("static:property",function(){$x=3;$a=[4];$box=new MsTarget;$name="m";$stat="s";$class="MsTarget";$result=MsTarget::s($box->p,ms_effect());return [$result,$x,$a,$box->p];},false);
$rows[]=ms_capture("static:factory",function(){$x=3;$a=[4];$box=new MsTarget;$name="m";$stat="s";$class="MsTarget";$result=MsTarget::s(ms_factory(),ms_effect());return [$result,$x,$a,$box->p];},false);
$rows[]=ms_capture("static:factory:throw",function(){$x=3;$a=[4];$box=new MsTarget;$name="m";$stat="s";$class="MsTarget";$result=MsTarget::s(ms_factory(),ms_effect());return [$result,$x,$a,$box->p];},true);
$rows[]=ms_capture("static:reference",function(){$x=3;$a=[4];$box=new MsTarget;$name="m";$stat="s";$class="MsTarget";$result=MsTarget::s(ms_reference(),ms_effect());return [$result,$x,$a,$box->p];},false);
$rows[]=ms_capture("static:new",function(){$x=3;$a=[4];$box=new MsTarget;$name="m";$stat="s";$class="MsTarget";$result=MsTarget::s(new stdClass,ms_effect());return [$result,$x,$a,$box->p];},false);
$rows[]=ms_capture("static:new:throw",function(){$x=3;$a=[4];$box=new MsTarget;$name="m";$stat="s";$class="MsTarget";$result=MsTarget::s(new stdClass,ms_effect());return [$result,$x,$a,$box->p];},true);
$rows[]=ms_capture("static:assignment",function(){$x=3;$a=[4];$box=new MsTarget;$name="m";$stat="s";$class="MsTarget";$result=MsTarget::s(($x=7),ms_effect());return [$result,$x,$a,$box->p];},false);
$rows[]=ms_capture("static:array-assignment",function(){$x=3;$a=[4];$box=new MsTarget;$name="m";$stat="s";$class="MsTarget";$result=MsTarget::s(($a[0]=7),ms_effect());return [$result,$x,$a,$box->p];},false);
$rows[]=ms_capture("static:conditional",function(){$x=3;$a=[4];$box=new MsTarget;$name="m";$stat="s";$class="MsTarget";$result=MsTarget::s((true?$x:2),ms_effect());return [$result,$x,$a,$box->p];},false);
$rows[]=ms_capture("static:coalesce",function(){$x=3;$a=[4];$box=new MsTarget;$name="m";$stat="s";$class="MsTarget";$result=MsTarget::s(($x??2),ms_effect());return [$result,$x,$a,$box->p];},false);
$rows[]=ms_capture("static:strlen",function(){$x=3;$a=[4];$box=new MsTarget;$name="m";$stat="s";$class="MsTarget";$result=MsTarget::s(strlen("x"),ms_effect());return [$result,$x,$a,$box->p];},false);
$rows[]=ms_capture("static:count",function(){$x=3;$a=[4];$box=new MsTarget;$name="m";$stat="s";$class="MsTarget";$result=MsTarget::s(count([1]),ms_effect());return [$result,$x,$a,$box->p];},false);
$rows[]=ms_capture("static:array",function(){$x=3;$a=[4];$box=new MsTarget;$name="m";$stat="s";$class="MsTarget";$result=MsTarget::s([1,2],ms_effect());return [$result,$x,$a,$box->p];},false);
$rows[]=ms_capture("static:null",function(){$x=3;$a=[4];$box=new MsTarget;$name="m";$stat="s";$class="MsTarget";$result=MsTarget::s(null,ms_effect());return [$result,$x,$a,$box->p];},false);
$rows[]=ms_capture("static-dynamic:literal",function(){$x=3;$a=[4];$box=new MsTarget;$name="m";$stat="s";$class="MsTarget";$result=MsTarget::{$stat}(1,ms_effect());return [$result,$x,$a,$box->p];},false);
$rows[]=ms_capture("static-dynamic:variable",function(){$x=3;$a=[4];$box=new MsTarget;$name="m";$stat="s";$class="MsTarget";$result=MsTarget::{$stat}($x,ms_effect());return [$result,$x,$a,$box->p];},false);
$rows[]=ms_capture("static-dynamic:array-cell",function(){$x=3;$a=[4];$box=new MsTarget;$name="m";$stat="s";$class="MsTarget";$result=MsTarget::{$stat}($a[0],ms_effect());return [$result,$x,$a,$box->p];},false);
$rows[]=ms_capture("static-dynamic:property",function(){$x=3;$a=[4];$box=new MsTarget;$name="m";$stat="s";$class="MsTarget";$result=MsTarget::{$stat}($box->p,ms_effect());return [$result,$x,$a,$box->p];},false);
$rows[]=ms_capture("static-dynamic:factory",function(){$x=3;$a=[4];$box=new MsTarget;$name="m";$stat="s";$class="MsTarget";$result=MsTarget::{$stat}(ms_factory(),ms_effect());return [$result,$x,$a,$box->p];},false);
$rows[]=ms_capture("static-dynamic:factory:throw",function(){$x=3;$a=[4];$box=new MsTarget;$name="m";$stat="s";$class="MsTarget";$result=MsTarget::{$stat}(ms_factory(),ms_effect());return [$result,$x,$a,$box->p];},true);
$rows[]=ms_capture("static-dynamic:reference",function(){$x=3;$a=[4];$box=new MsTarget;$name="m";$stat="s";$class="MsTarget";$result=MsTarget::{$stat}(ms_reference(),ms_effect());return [$result,$x,$a,$box->p];},false);
$rows[]=ms_capture("static-dynamic:new",function(){$x=3;$a=[4];$box=new MsTarget;$name="m";$stat="s";$class="MsTarget";$result=MsTarget::{$stat}(new stdClass,ms_effect());return [$result,$x,$a,$box->p];},false);
$rows[]=ms_capture("static-dynamic:new:throw",function(){$x=3;$a=[4];$box=new MsTarget;$name="m";$stat="s";$class="MsTarget";$result=MsTarget::{$stat}(new stdClass,ms_effect());return [$result,$x,$a,$box->p];},true);
$rows[]=ms_capture("static-dynamic:assignment",function(){$x=3;$a=[4];$box=new MsTarget;$name="m";$stat="s";$class="MsTarget";$result=MsTarget::{$stat}(($x=7),ms_effect());return [$result,$x,$a,$box->p];},false);
$rows[]=ms_capture("static-dynamic:array-assignment",function(){$x=3;$a=[4];$box=new MsTarget;$name="m";$stat="s";$class="MsTarget";$result=MsTarget::{$stat}(($a[0]=7),ms_effect());return [$result,$x,$a,$box->p];},false);
$rows[]=ms_capture("static-dynamic:conditional",function(){$x=3;$a=[4];$box=new MsTarget;$name="m";$stat="s";$class="MsTarget";$result=MsTarget::{$stat}((true?$x:2),ms_effect());return [$result,$x,$a,$box->p];},false);
$rows[]=ms_capture("static-dynamic:coalesce",function(){$x=3;$a=[4];$box=new MsTarget;$name="m";$stat="s";$class="MsTarget";$result=MsTarget::{$stat}(($x??2),ms_effect());return [$result,$x,$a,$box->p];},false);
$rows[]=ms_capture("static-dynamic:strlen",function(){$x=3;$a=[4];$box=new MsTarget;$name="m";$stat="s";$class="MsTarget";$result=MsTarget::{$stat}(strlen("x"),ms_effect());return [$result,$x,$a,$box->p];},false);
$rows[]=ms_capture("static-dynamic:count",function(){$x=3;$a=[4];$box=new MsTarget;$name="m";$stat="s";$class="MsTarget";$result=MsTarget::{$stat}(count([1]),ms_effect());return [$result,$x,$a,$box->p];},false);
$rows[]=ms_capture("static-dynamic:array",function(){$x=3;$a=[4];$box=new MsTarget;$name="m";$stat="s";$class="MsTarget";$result=MsTarget::{$stat}([1,2],ms_effect());return [$result,$x,$a,$box->p];},false);
$rows[]=ms_capture("static-dynamic:null",function(){$x=3;$a=[4];$box=new MsTarget;$name="m";$stat="s";$class="MsTarget";$result=MsTarget::{$stat}(null,ms_effect());return [$result,$x,$a,$box->p];},false);
$rows[]=ms_capture("class-variable:literal",function(){$x=3;$a=[4];$box=new MsTarget;$name="m";$stat="s";$class="MsTarget";$result=$class::s(1,ms_effect());return [$result,$x,$a,$box->p];},false);
$rows[]=ms_capture("class-variable:variable",function(){$x=3;$a=[4];$box=new MsTarget;$name="m";$stat="s";$class="MsTarget";$result=$class::s($x,ms_effect());return [$result,$x,$a,$box->p];},false);
$rows[]=ms_capture("class-variable:array-cell",function(){$x=3;$a=[4];$box=new MsTarget;$name="m";$stat="s";$class="MsTarget";$result=$class::s($a[0],ms_effect());return [$result,$x,$a,$box->p];},false);
$rows[]=ms_capture("class-variable:property",function(){$x=3;$a=[4];$box=new MsTarget;$name="m";$stat="s";$class="MsTarget";$result=$class::s($box->p,ms_effect());return [$result,$x,$a,$box->p];},false);
$rows[]=ms_capture("class-variable:factory",function(){$x=3;$a=[4];$box=new MsTarget;$name="m";$stat="s";$class="MsTarget";$result=$class::s(ms_factory(),ms_effect());return [$result,$x,$a,$box->p];},false);
$rows[]=ms_capture("class-variable:factory:throw",function(){$x=3;$a=[4];$box=new MsTarget;$name="m";$stat="s";$class="MsTarget";$result=$class::s(ms_factory(),ms_effect());return [$result,$x,$a,$box->p];},true);
$rows[]=ms_capture("class-variable:reference",function(){$x=3;$a=[4];$box=new MsTarget;$name="m";$stat="s";$class="MsTarget";$result=$class::s(ms_reference(),ms_effect());return [$result,$x,$a,$box->p];},false);
$rows[]=ms_capture("class-variable:new",function(){$x=3;$a=[4];$box=new MsTarget;$name="m";$stat="s";$class="MsTarget";$result=$class::s(new stdClass,ms_effect());return [$result,$x,$a,$box->p];},false);
$rows[]=ms_capture("class-variable:new:throw",function(){$x=3;$a=[4];$box=new MsTarget;$name="m";$stat="s";$class="MsTarget";$result=$class::s(new stdClass,ms_effect());return [$result,$x,$a,$box->p];},true);
$rows[]=ms_capture("class-variable:assignment",function(){$x=3;$a=[4];$box=new MsTarget;$name="m";$stat="s";$class="MsTarget";$result=$class::s(($x=7),ms_effect());return [$result,$x,$a,$box->p];},false);
$rows[]=ms_capture("class-variable:array-assignment",function(){$x=3;$a=[4];$box=new MsTarget;$name="m";$stat="s";$class="MsTarget";$result=$class::s(($a[0]=7),ms_effect());return [$result,$x,$a,$box->p];},false);
$rows[]=ms_capture("class-variable:conditional",function(){$x=3;$a=[4];$box=new MsTarget;$name="m";$stat="s";$class="MsTarget";$result=$class::s((true?$x:2),ms_effect());return [$result,$x,$a,$box->p];},false);
$rows[]=ms_capture("class-variable:coalesce",function(){$x=3;$a=[4];$box=new MsTarget;$name="m";$stat="s";$class="MsTarget";$result=$class::s(($x??2),ms_effect());return [$result,$x,$a,$box->p];},false);
$rows[]=ms_capture("class-variable:strlen",function(){$x=3;$a=[4];$box=new MsTarget;$name="m";$stat="s";$class="MsTarget";$result=$class::s(strlen("x"),ms_effect());return [$result,$x,$a,$box->p];},false);
$rows[]=ms_capture("class-variable:count",function(){$x=3;$a=[4];$box=new MsTarget;$name="m";$stat="s";$class="MsTarget";$result=$class::s(count([1]),ms_effect());return [$result,$x,$a,$box->p];},false);
$rows[]=ms_capture("class-variable:array",function(){$x=3;$a=[4];$box=new MsTarget;$name="m";$stat="s";$class="MsTarget";$result=$class::s([1,2],ms_effect());return [$result,$x,$a,$box->p];},false);
$rows[]=ms_capture("class-variable:null",function(){$x=3;$a=[4];$box=new MsTarget;$name="m";$stat="s";$class="MsTarget";$result=$class::s(null,ms_effect());return [$result,$x,$a,$box->p];},false);
$rows[]=ms_capture("class-object:literal",function(){$x=3;$a=[4];$box=new MsTarget;$name="m";$stat="s";$class="MsTarget";$result=$box::s(1,ms_effect());return [$result,$x,$a,$box->p];},false);
$rows[]=ms_capture("class-object:variable",function(){$x=3;$a=[4];$box=new MsTarget;$name="m";$stat="s";$class="MsTarget";$result=$box::s($x,ms_effect());return [$result,$x,$a,$box->p];},false);
$rows[]=ms_capture("class-object:array-cell",function(){$x=3;$a=[4];$box=new MsTarget;$name="m";$stat="s";$class="MsTarget";$result=$box::s($a[0],ms_effect());return [$result,$x,$a,$box->p];},false);
$rows[]=ms_capture("class-object:property",function(){$x=3;$a=[4];$box=new MsTarget;$name="m";$stat="s";$class="MsTarget";$result=$box::s($box->p,ms_effect());return [$result,$x,$a,$box->p];},false);
$rows[]=ms_capture("class-object:factory",function(){$x=3;$a=[4];$box=new MsTarget;$name="m";$stat="s";$class="MsTarget";$result=$box::s(ms_factory(),ms_effect());return [$result,$x,$a,$box->p];},false);
$rows[]=ms_capture("class-object:factory:throw",function(){$x=3;$a=[4];$box=new MsTarget;$name="m";$stat="s";$class="MsTarget";$result=$box::s(ms_factory(),ms_effect());return [$result,$x,$a,$box->p];},true);
$rows[]=ms_capture("class-object:reference",function(){$x=3;$a=[4];$box=new MsTarget;$name="m";$stat="s";$class="MsTarget";$result=$box::s(ms_reference(),ms_effect());return [$result,$x,$a,$box->p];},false);
$rows[]=ms_capture("class-object:new",function(){$x=3;$a=[4];$box=new MsTarget;$name="m";$stat="s";$class="MsTarget";$result=$box::s(new stdClass,ms_effect());return [$result,$x,$a,$box->p];},false);
$rows[]=ms_capture("class-object:new:throw",function(){$x=3;$a=[4];$box=new MsTarget;$name="m";$stat="s";$class="MsTarget";$result=$box::s(new stdClass,ms_effect());return [$result,$x,$a,$box->p];},true);
$rows[]=ms_capture("class-object:assignment",function(){$x=3;$a=[4];$box=new MsTarget;$name="m";$stat="s";$class="MsTarget";$result=$box::s(($x=7),ms_effect());return [$result,$x,$a,$box->p];},false);
$rows[]=ms_capture("class-object:array-assignment",function(){$x=3;$a=[4];$box=new MsTarget;$name="m";$stat="s";$class="MsTarget";$result=$box::s(($a[0]=7),ms_effect());return [$result,$x,$a,$box->p];},false);
$rows[]=ms_capture("class-object:conditional",function(){$x=3;$a=[4];$box=new MsTarget;$name="m";$stat="s";$class="MsTarget";$result=$box::s((true?$x:2),ms_effect());return [$result,$x,$a,$box->p];},false);
$rows[]=ms_capture("class-object:coalesce",function(){$x=3;$a=[4];$box=new MsTarget;$name="m";$stat="s";$class="MsTarget";$result=$box::s(($x??2),ms_effect());return [$result,$x,$a,$box->p];},false);
$rows[]=ms_capture("class-object:strlen",function(){$x=3;$a=[4];$box=new MsTarget;$name="m";$stat="s";$class="MsTarget";$result=$box::s(strlen("x"),ms_effect());return [$result,$x,$a,$box->p];},false);
$rows[]=ms_capture("class-object:count",function(){$x=3;$a=[4];$box=new MsTarget;$name="m";$stat="s";$class="MsTarget";$result=$box::s(count([1]),ms_effect());return [$result,$x,$a,$box->p];},false);
$rows[]=ms_capture("class-object:array",function(){$x=3;$a=[4];$box=new MsTarget;$name="m";$stat="s";$class="MsTarget";$result=$box::s([1,2],ms_effect());return [$result,$x,$a,$box->p];},false);
$rows[]=ms_capture("class-object:null",function(){$x=3;$a=[4];$box=new MsTarget;$name="m";$stat="s";$class="MsTarget";$result=$box::s(null,ms_effect());return [$result,$x,$a,$box->p];},false);
$rows[]=ms_capture("ctor:MsCtor:literal",function(){$x=3;$a=[4];$box=new MsTarget;$class="MsCtor";$result=new MsCtor(1,ms_effect());return [$result->result,$result->default,$x,$a,$box->p];},false);
$rows[]=ms_capture("ctor:MsCtor:variable",function(){$x=3;$a=[4];$box=new MsTarget;$class="MsCtor";$result=new MsCtor($x,ms_effect());return [$result->result,$result->default,$x,$a,$box->p];},false);
$rows[]=ms_capture("ctor:MsCtor:array-cell",function(){$x=3;$a=[4];$box=new MsTarget;$class="MsCtor";$result=new MsCtor($a[0],ms_effect());return [$result->result,$result->default,$x,$a,$box->p];},false);
$rows[]=ms_capture("ctor:MsCtor:property",function(){$x=3;$a=[4];$box=new MsTarget;$class="MsCtor";$result=new MsCtor($box->p,ms_effect());return [$result->result,$result->default,$x,$a,$box->p];},false);
$rows[]=ms_capture("ctor:MsCtor:factory",function(){$x=3;$a=[4];$box=new MsTarget;$class="MsCtor";$result=new MsCtor(ms_factory(),ms_effect());return [$result->result,$result->default,$x,$a,$box->p];},false);
$rows[]=ms_capture("ctor:MsCtor:factory:throw",function(){$x=3;$a=[4];$box=new MsTarget;$class="MsCtor";$result=new MsCtor(ms_factory(),ms_effect());return [$result->result,$result->default,$x,$a,$box->p];},true);
$rows[]=ms_capture("ctor:MsCtor:reference",function(){$x=3;$a=[4];$box=new MsTarget;$class="MsCtor";$result=new MsCtor(ms_reference(),ms_effect());return [$result->result,$result->default,$x,$a,$box->p];},false);
$rows[]=ms_capture("ctor:MsCtor:new",function(){$x=3;$a=[4];$box=new MsTarget;$class="MsCtor";$result=new MsCtor(new stdClass,ms_effect());return [$result->result,$result->default,$x,$a,$box->p];},false);
$rows[]=ms_capture("ctor:MsCtor:new:throw",function(){$x=3;$a=[4];$box=new MsTarget;$class="MsCtor";$result=new MsCtor(new stdClass,ms_effect());return [$result->result,$result->default,$x,$a,$box->p];},true);
$rows[]=ms_capture("ctor:MsCtor:assignment",function(){$x=3;$a=[4];$box=new MsTarget;$class="MsCtor";$result=new MsCtor(($x=7),ms_effect());return [$result->result,$result->default,$x,$a,$box->p];},false);
$rows[]=ms_capture("ctor:MsCtor:array-assignment",function(){$x=3;$a=[4];$box=new MsTarget;$class="MsCtor";$result=new MsCtor(($a[0]=7),ms_effect());return [$result->result,$result->default,$x,$a,$box->p];},false);
$rows[]=ms_capture("ctor:MsCtor:conditional",function(){$x=3;$a=[4];$box=new MsTarget;$class="MsCtor";$result=new MsCtor((true?$x:2),ms_effect());return [$result->result,$result->default,$x,$a,$box->p];},false);
$rows[]=ms_capture("ctor:MsCtor:coalesce",function(){$x=3;$a=[4];$box=new MsTarget;$class="MsCtor";$result=new MsCtor(($x??2),ms_effect());return [$result->result,$result->default,$x,$a,$box->p];},false);
$rows[]=ms_capture("ctor:MsCtor:strlen",function(){$x=3;$a=[4];$box=new MsTarget;$class="MsCtor";$result=new MsCtor(strlen("x"),ms_effect());return [$result->result,$result->default,$x,$a,$box->p];},false);
$rows[]=ms_capture("ctor:MsCtor:count",function(){$x=3;$a=[4];$box=new MsTarget;$class="MsCtor";$result=new MsCtor(count([1]),ms_effect());return [$result->result,$result->default,$x,$a,$box->p];},false);
$rows[]=ms_capture("ctor:MsCtor:array",function(){$x=3;$a=[4];$box=new MsTarget;$class="MsCtor";$result=new MsCtor([1,2],ms_effect());return [$result->result,$result->default,$x,$a,$box->p];},false);
$rows[]=ms_capture("ctor:MsCtor:null",function(){$x=3;$a=[4];$box=new MsTarget;$class="MsCtor";$result=new MsCtor(null,ms_effect());return [$result->result,$result->default,$x,$a,$box->p];},false);
$rows[]=ms_capture("ctor:MsCtorChild:literal",function(){$x=3;$a=[4];$box=new MsTarget;$class="MsCtor";$result=new MsCtorChild(1,ms_effect());return [$result->result,$result->default,$x,$a,$box->p];},false);
$rows[]=ms_capture("ctor:MsCtorChild:variable",function(){$x=3;$a=[4];$box=new MsTarget;$class="MsCtor";$result=new MsCtorChild($x,ms_effect());return [$result->result,$result->default,$x,$a,$box->p];},false);
$rows[]=ms_capture("ctor:MsCtorChild:array-cell",function(){$x=3;$a=[4];$box=new MsTarget;$class="MsCtor";$result=new MsCtorChild($a[0],ms_effect());return [$result->result,$result->default,$x,$a,$box->p];},false);
$rows[]=ms_capture("ctor:MsCtorChild:property",function(){$x=3;$a=[4];$box=new MsTarget;$class="MsCtor";$result=new MsCtorChild($box->p,ms_effect());return [$result->result,$result->default,$x,$a,$box->p];},false);
$rows[]=ms_capture("ctor:MsCtorChild:factory",function(){$x=3;$a=[4];$box=new MsTarget;$class="MsCtor";$result=new MsCtorChild(ms_factory(),ms_effect());return [$result->result,$result->default,$x,$a,$box->p];},false);
$rows[]=ms_capture("ctor:MsCtorChild:factory:throw",function(){$x=3;$a=[4];$box=new MsTarget;$class="MsCtor";$result=new MsCtorChild(ms_factory(),ms_effect());return [$result->result,$result->default,$x,$a,$box->p];},true);
$rows[]=ms_capture("ctor:MsCtorChild:reference",function(){$x=3;$a=[4];$box=new MsTarget;$class="MsCtor";$result=new MsCtorChild(ms_reference(),ms_effect());return [$result->result,$result->default,$x,$a,$box->p];},false);
$rows[]=ms_capture("ctor:MsCtorChild:new",function(){$x=3;$a=[4];$box=new MsTarget;$class="MsCtor";$result=new MsCtorChild(new stdClass,ms_effect());return [$result->result,$result->default,$x,$a,$box->p];},false);
$rows[]=ms_capture("ctor:MsCtorChild:new:throw",function(){$x=3;$a=[4];$box=new MsTarget;$class="MsCtor";$result=new MsCtorChild(new stdClass,ms_effect());return [$result->result,$result->default,$x,$a,$box->p];},true);
$rows[]=ms_capture("ctor:MsCtorChild:assignment",function(){$x=3;$a=[4];$box=new MsTarget;$class="MsCtor";$result=new MsCtorChild(($x=7),ms_effect());return [$result->result,$result->default,$x,$a,$box->p];},false);
$rows[]=ms_capture("ctor:MsCtorChild:array-assignment",function(){$x=3;$a=[4];$box=new MsTarget;$class="MsCtor";$result=new MsCtorChild(($a[0]=7),ms_effect());return [$result->result,$result->default,$x,$a,$box->p];},false);
$rows[]=ms_capture("ctor:MsCtorChild:conditional",function(){$x=3;$a=[4];$box=new MsTarget;$class="MsCtor";$result=new MsCtorChild((true?$x:2),ms_effect());return [$result->result,$result->default,$x,$a,$box->p];},false);
$rows[]=ms_capture("ctor:MsCtorChild:coalesce",function(){$x=3;$a=[4];$box=new MsTarget;$class="MsCtor";$result=new MsCtorChild(($x??2),ms_effect());return [$result->result,$result->default,$x,$a,$box->p];},false);
$rows[]=ms_capture("ctor:MsCtorChild:strlen",function(){$x=3;$a=[4];$box=new MsTarget;$class="MsCtor";$result=new MsCtorChild(strlen("x"),ms_effect());return [$result->result,$result->default,$x,$a,$box->p];},false);
$rows[]=ms_capture("ctor:MsCtorChild:count",function(){$x=3;$a=[4];$box=new MsTarget;$class="MsCtor";$result=new MsCtorChild(count([1]),ms_effect());return [$result->result,$result->default,$x,$a,$box->p];},false);
$rows[]=ms_capture("ctor:MsCtorChild:array",function(){$x=3;$a=[4];$box=new MsTarget;$class="MsCtor";$result=new MsCtorChild([1,2],ms_effect());return [$result->result,$result->default,$x,$a,$box->p];},false);
$rows[]=ms_capture("ctor:MsCtorChild:null",function(){$x=3;$a=[4];$box=new MsTarget;$class="MsCtor";$result=new MsCtorChild(null,ms_effect());return [$result->result,$result->default,$x,$a,$box->p];},false);
$rows[]=ms_capture("ctor:class-variable:literal",function(){$x=3;$a=[4];$box=new MsTarget;$class="MsCtor";$result=new $class(1,ms_effect());return [$result->result,$result->default,$x,$a,$box->p];},false);
$rows[]=ms_capture("ctor:class-variable:variable",function(){$x=3;$a=[4];$box=new MsTarget;$class="MsCtor";$result=new $class($x,ms_effect());return [$result->result,$result->default,$x,$a,$box->p];},false);
$rows[]=ms_capture("ctor:class-variable:array-cell",function(){$x=3;$a=[4];$box=new MsTarget;$class="MsCtor";$result=new $class($a[0],ms_effect());return [$result->result,$result->default,$x,$a,$box->p];},false);
$rows[]=ms_capture("ctor:class-variable:property",function(){$x=3;$a=[4];$box=new MsTarget;$class="MsCtor";$result=new $class($box->p,ms_effect());return [$result->result,$result->default,$x,$a,$box->p];},false);
$rows[]=ms_capture("ctor:class-variable:factory",function(){$x=3;$a=[4];$box=new MsTarget;$class="MsCtor";$result=new $class(ms_factory(),ms_effect());return [$result->result,$result->default,$x,$a,$box->p];},false);
$rows[]=ms_capture("ctor:class-variable:factory:throw",function(){$x=3;$a=[4];$box=new MsTarget;$class="MsCtor";$result=new $class(ms_factory(),ms_effect());return [$result->result,$result->default,$x,$a,$box->p];},true);
$rows[]=ms_capture("ctor:class-variable:reference",function(){$x=3;$a=[4];$box=new MsTarget;$class="MsCtor";$result=new $class(ms_reference(),ms_effect());return [$result->result,$result->default,$x,$a,$box->p];},false);
$rows[]=ms_capture("ctor:class-variable:new",function(){$x=3;$a=[4];$box=new MsTarget;$class="MsCtor";$result=new $class(new stdClass,ms_effect());return [$result->result,$result->default,$x,$a,$box->p];},false);
$rows[]=ms_capture("ctor:class-variable:new:throw",function(){$x=3;$a=[4];$box=new MsTarget;$class="MsCtor";$result=new $class(new stdClass,ms_effect());return [$result->result,$result->default,$x,$a,$box->p];},true);
$rows[]=ms_capture("ctor:class-variable:assignment",function(){$x=3;$a=[4];$box=new MsTarget;$class="MsCtor";$result=new $class(($x=7),ms_effect());return [$result->result,$result->default,$x,$a,$box->p];},false);
$rows[]=ms_capture("ctor:class-variable:array-assignment",function(){$x=3;$a=[4];$box=new MsTarget;$class="MsCtor";$result=new $class(($a[0]=7),ms_effect());return [$result->result,$result->default,$x,$a,$box->p];},false);
$rows[]=ms_capture("ctor:class-variable:conditional",function(){$x=3;$a=[4];$box=new MsTarget;$class="MsCtor";$result=new $class((true?$x:2),ms_effect());return [$result->result,$result->default,$x,$a,$box->p];},false);
$rows[]=ms_capture("ctor:class-variable:coalesce",function(){$x=3;$a=[4];$box=new MsTarget;$class="MsCtor";$result=new $class(($x??2),ms_effect());return [$result->result,$result->default,$x,$a,$box->p];},false);
$rows[]=ms_capture("ctor:class-variable:strlen",function(){$x=3;$a=[4];$box=new MsTarget;$class="MsCtor";$result=new $class(strlen("x"),ms_effect());return [$result->result,$result->default,$x,$a,$box->p];},false);
$rows[]=ms_capture("ctor:class-variable:count",function(){$x=3;$a=[4];$box=new MsTarget;$class="MsCtor";$result=new $class(count([1]),ms_effect());return [$result->result,$result->default,$x,$a,$box->p];},false);
$rows[]=ms_capture("ctor:class-variable:array",function(){$x=3;$a=[4];$box=new MsTarget;$class="MsCtor";$result=new $class([1,2],ms_effect());return [$result->result,$result->default,$x,$a,$box->p];},false);
$rows[]=ms_capture("ctor:class-variable:null",function(){$x=3;$a=[4];$box=new MsTarget;$class="MsCtor";$result=new $class(null,ms_effect());return [$result->result,$result->default,$x,$a,$box->p];},false);
$rows[]=ms_capture("missing-object",function(){$o=new MsTarget;return $o->missing(ms_effect());});
$rows[]=ms_capture("private-object",function(){$o=new MsTarget;return $o->hidden(ms_effect());});
$rows[]=ms_capture("protected-static",function(){return MsTarget::restricted(ms_effect());});
$rows[]=ms_capture("non-static",function(){return MsTarget::m(ms_effect());});
$rows[]=ms_capture("missing-static",function(){return MsTarget::missing(ms_effect());});
$rows[]=ms_capture("missing-class",function(){return MsMissing::m(ms_effect());});
$rows[]=ms_capture("missing-class-name-effect",function(){return MsMissing::{ms_effect("name","m")}(ms_effect());});
$rows[]=ms_capture("missing-dynamic-class-name-effect",function(){$c="MsMissing";return $c::{ms_effect("name","m")}(ms_effect());});
$rows[]=ms_capture("invalid-dynamic-class-name-effect",function(){$c=2;return $c::{ms_effect("name","m")}(ms_effect());});
$rows[]=ms_capture("invalid-name-existing",function(){return MsTarget::{ms_effect("name",2)}(ms_effect());});
$rows[]=ms_capture("invalid-name-missing",function(){return MsMissing::{ms_effect("name",2)}(ms_effect());});
$rows[]=ms_capture("missing-ctor",function(){return new MsMissing(ms_effect());});
$rows[]=ms_capture("abstract-ctor",function(){return new MsAbstract(ms_effect());});
$rows[]=ms_capture("interface-ctor",function(){return new MsInterface(ms_effect());});
$rows[]=ms_capture("trait-ctor",function(){return new MsTrait(ms_effect());});
$rows[]=ms_capture("private-ctor",function(){return new MsPrivate(ms_effect());});
$rows[]=ms_capture("protected-ctor",function(){return new MsProtected(ms_effect());});
$rows[]=ms_capture("private-scope-ctor",function(){return MsPrivate::create();});
$rows[]=ms_capture("protected-child-ctor",function(){return MsProtectedChild::create();});
$rows[]=ms_capture("throwing-ctor",function(){return new MsCtorThrow(ms_effect());});
$rows[]=ms_capture("no-ctor",function(){$o=new MsNoCtor(ms_effect(),ms_effect());return $o->default;});
$rows[]=ms_capture("stdclass-ctor",function(){return get_class(new stdClass(ms_effect()));});
$rows[]=ms_capture("old-style-ctor",function(){$x=3;$o=new MsOldCtor($x);return [$o->p,$x];});
$rows[]=ms_capture("old-style-method",function(){$x=3;$o=new MsOldCtor;$o->MsOldCtor($x);return [$o->p,$x];});
$rows[]=ms_capture("scope-private-child",function(){$o=new MsChild;return $o->scoped($o);});
$rows[]=ms_capture("scope-unrelated",function(){$o=new MsBase;return $o->scoped(new MsOther);});
$rows[]=ms_capture("forwarding",function(){$o=new MsChild;return $o->forward();});
$rows[]=ms_capture("magic-forwarding",function(){$o=new MsMagicChild;return $o->forwardMissing();});
$rows[]=ms_capture("magic-object",function(){$o=new MsMagic;return $o->hidden(1,ms_effect());});
$rows[]=ms_capture("magic-static",function(){return MsMagic::restricted(1,ms_effect());});
$rows[]=ms_capture("receiver-variable-replaced-by-name",function(){$o=new MsBase;return $o->{ms_name($o,new MsChild)}(ms_effect());});
$rows[]=ms_capture("receiver-expression-captured",function(){$o=new MsBase;return ms_receiver($o)->{ms_name($o,new MsChild)}(ms_effect());});
$rows[]=ms_capture("receiver-array-captured",function(){$o=[new MsBase];return $o[0]->{ms_name($o[0],new MsChild)}(ms_effect());});
$rows[]=ms_capture("receiver-replaced-by-arg",function(){$o=new MsBase;return $o->m(ms_name($o,new MsChild,"arg"));});
$rows[]=ms_capture("closure-construction-scope",function(){$o=new MsBase;$f=$o->makeClosure();$bound=$f->bindTo(new MsOther,"MsOther");return [$f(),$bound()];});
$rows[]=ms_capture("method-value-snapshot",function(){$o=new MsTarget;$x=3;$r=$o->value($x,$x=7);return [$r,$x];});
$rows[]=ms_capture("static-value-snapshot",function(){$x=3;$r=MsTarget::values($x,$x=7);return [$r,$x];});
$rows[]=ms_capture("method-array-snapshot",function(){$o=new MsTarget;$a=[3];$r=$o->value($a,$a[0]=7);return [$r,$a];});
$rows[]=ms_capture("method-ref-slot-rebind",function(){$o=new MsTarget;$x=3;$y=11;$r=$o->m($x,$x=&$y);return [$r,$x,$y];});
$rows[]=ms_capture("method-unpack",function(){$o=new MsTarget;$a=[3,4];$r=$o->m(...$a);return [$r,$a];});
$rows[]=ms_capture("static-unpack",function(){$a=[3,4];$r=MsTarget::s(...$a);return [$r,$a];});
$rows[]=ms_capture("ctor-unpack",function(){$a=[3,4];$o=new MsCtor(...$a);return [$o->result,$a];});
$rows[]=ms_capture("typed-before-other",function(){$o=new MsTarget;return $o->typed("invalid",ms_effect());});
$rows[]=ms_capture("validation:null:\"m\"",function(){$o=null;$name="m";return $o->{$name}(ms_effect());});
$rows[]=ms_capture("validation:null:null",function(){$o=null;$name=null;return $o->{$name}(ms_effect());});
$rows[]=ms_capture("validation:null:false",function(){$o=null;$name=false;return $o->{$name}(ms_effect());});
$rows[]=ms_capture("validation:null:2",function(){$o=null;$name=2;return $o->{$name}(ms_effect());});
$rows[]=ms_capture("validation:null:[]",function(){$o=null;$name=[];return $o->{$name}(ms_effect());});
$rows[]=ms_capture("validation:null:new stdClass",function(){$o=null;$name=new stdClass;return $o->{$name}(ms_effect());});
$rows[]=ms_capture("validation:false:\"m\"",function(){$o=false;$name="m";return $o->{$name}(ms_effect());});
$rows[]=ms_capture("validation:false:null",function(){$o=false;$name=null;return $o->{$name}(ms_effect());});
$rows[]=ms_capture("validation:false:false",function(){$o=false;$name=false;return $o->{$name}(ms_effect());});
$rows[]=ms_capture("validation:false:2",function(){$o=false;$name=2;return $o->{$name}(ms_effect());});
$rows[]=ms_capture("validation:false:[]",function(){$o=false;$name=[];return $o->{$name}(ms_effect());});
$rows[]=ms_capture("validation:false:new stdClass",function(){$o=false;$name=new stdClass;return $o->{$name}(ms_effect());});
$rows[]=ms_capture("validation:2:\"m\"",function(){$o=2;$name="m";return $o->{$name}(ms_effect());});
$rows[]=ms_capture("validation:2:null",function(){$o=2;$name=null;return $o->{$name}(ms_effect());});
$rows[]=ms_capture("validation:2:false",function(){$o=2;$name=false;return $o->{$name}(ms_effect());});
$rows[]=ms_capture("validation:2:2",function(){$o=2;$name=2;return $o->{$name}(ms_effect());});
$rows[]=ms_capture("validation:2:[]",function(){$o=2;$name=[];return $o->{$name}(ms_effect());});
$rows[]=ms_capture("validation:2:new stdClass",function(){$o=2;$name=new stdClass;return $o->{$name}(ms_effect());});
$rows[]=ms_capture("validation:2.5:\"m\"",function(){$o=2.5;$name="m";return $o->{$name}(ms_effect());});
$rows[]=ms_capture("validation:2.5:null",function(){$o=2.5;$name=null;return $o->{$name}(ms_effect());});
$rows[]=ms_capture("validation:2.5:false",function(){$o=2.5;$name=false;return $o->{$name}(ms_effect());});
$rows[]=ms_capture("validation:2.5:2",function(){$o=2.5;$name=2;return $o->{$name}(ms_effect());});
$rows[]=ms_capture("validation:2.5:[]",function(){$o=2.5;$name=[];return $o->{$name}(ms_effect());});
$rows[]=ms_capture("validation:2.5:new stdClass",function(){$o=2.5;$name=new stdClass;return $o->{$name}(ms_effect());});
$rows[]=ms_capture("validation:\"s\":\"m\"",function(){$o="s";$name="m";return $o->{$name}(ms_effect());});
$rows[]=ms_capture("validation:\"s\":null",function(){$o="s";$name=null;return $o->{$name}(ms_effect());});
$rows[]=ms_capture("validation:\"s\":false",function(){$o="s";$name=false;return $o->{$name}(ms_effect());});
$rows[]=ms_capture("validation:\"s\":2",function(){$o="s";$name=2;return $o->{$name}(ms_effect());});
$rows[]=ms_capture("validation:\"s\":[]",function(){$o="s";$name=[];return $o->{$name}(ms_effect());});
$rows[]=ms_capture("validation:\"s\":new stdClass",function(){$o="s";$name=new stdClass;return $o->{$name}(ms_effect());});
$rows[]=ms_capture("validation:[]:\"m\"",function(){$o=[];$name="m";return $o->{$name}(ms_effect());});
$rows[]=ms_capture("validation:[]:null",function(){$o=[];$name=null;return $o->{$name}(ms_effect());});
$rows[]=ms_capture("validation:[]:false",function(){$o=[];$name=false;return $o->{$name}(ms_effect());});
$rows[]=ms_capture("validation:[]:2",function(){$o=[];$name=2;return $o->{$name}(ms_effect());});
$rows[]=ms_capture("validation:[]:[]",function(){$o=[];$name=[];return $o->{$name}(ms_effect());});
$rows[]=ms_capture("validation:[]:new stdClass",function(){$o=[];$name=new stdClass;return $o->{$name}(ms_effect());});
$rows[]=ms_capture("validation:new MsTarget:\"m\"",function(){$o=new MsTarget;$name="m";return $o->{$name}(ms_effect());});
$rows[]=ms_capture("validation:new MsTarget:null",function(){$o=new MsTarget;$name=null;return $o->{$name}(ms_effect());});
$rows[]=ms_capture("validation:new MsTarget:false",function(){$o=new MsTarget;$name=false;return $o->{$name}(ms_effect());});
$rows[]=ms_capture("validation:new MsTarget:2",function(){$o=new MsTarget;$name=2;return $o->{$name}(ms_effect());});
$rows[]=ms_capture("validation:new MsTarget:[]",function(){$o=new MsTarget;$name=[];return $o->{$name}(ms_effect());});
$rows[]=ms_capture("validation:new MsTarget:new stdClass",function(){$o=new MsTarget;$name=new stdClass;return $o->{$name}(ms_effect());});
foreach([0,1] as $round) {
    foreach(['MsBase','MsChild','MsMagic'] as $class) foreach(['s','missing','restricted'] as $name) {
        $rows[]=ms_capture('cache:'.$round.':'.$class.':'.$name,function()use($class,$name){return $class::{$name}(ms_effect());});
    }
}
$rows[]=ms_capture("abstract-parent-method",function(){$o=new MsConcreteMethod;return $o->run();});
$rows[]=ms_capture("new-self-no-scope",function(){return new self(ms_effect());});
$rows[]=ms_capture("new-static-no-scope",function(){return new static(ms_effect());});
$rows[]=ms_capture("new-parent-no-scope",function(){return new parent(ms_effect());});
$rows[]=ms_capture("self-no-scope",function(){return self::m(ms_effect());});
$rows[]=ms_capture("static-no-scope",function(){return static::m(ms_effect());});
$rows[]=ms_capture("parent-no-scope",function(){return parent::m(ms_effect());});
$rows[]=ms_capture("native-ctor-reflection",function(){$r=new ReflectionClass("MsTarget");return $r->getName();});
$rows[]=ms_capture("ctor-class-object",function(){$x=3;$class=new MsCtor($x);$y=4;$o=new $class($y);return [$o->result,$y];});
$rows[]=ms_capture("ctor-class-resolved-before-arg",function(){$class="MsCtor";$x=3;$o=new $class($x,$class="MsNoCtor");return [$o->result,$x,get_class($o)];});
$rows[]=ms_capture("dynamic-class-resolved-before-name",function(){$class="MsBase";return $class::{ms_name($class,"MsChild","s")}(ms_effect());});
$rows[]=ms_capture("class-object-resolved-before-name",function(){$class=new MsBase;return $class::{ms_name($class,new MsChild,"s")}(ms_effect());});
$rows[]=ms_capture("true-receiver",function(){$o=true;return $o->m(ms_effect());});
$rows[]=ms_capture("ctor-class-invalid-null",function(){$class=null;return new $class(ms_effect());});
$rows[]=ms_capture("ctor-class-invalid-false",function(){$class=false;return new $class(ms_effect());});
$rows[]=ms_capture("ctor-class-invalid-2",function(){$class=2;return new $class(ms_effect());});
$rows[]=ms_capture("ctor-class-invalid-2.5",function(){$class=2.5;return new $class(ms_effect());});
$rows[]=ms_capture("ctor-class-invalid-[]",function(){$class=[];return new $class(ms_effect());});
return $rows;
