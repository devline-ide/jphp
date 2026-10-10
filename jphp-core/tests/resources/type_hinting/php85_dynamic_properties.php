<?php
class DPPlain {}
class DPChild extends DPPlain {}
class DPDeclared {public $x=1;public static $shared=9;private int $secret=0;protected int $protectedValue=0;}
class DPMagic {public $events=[];function __set($k,$v){$this->events[]=[$k,$v];}}
class DPRecursive {function __set($k,$v){$this->$k=$v;}}
class DPGetter {function __get($k){return 4;}}
class DPMagicCompoundGet {function __get($k){dp_event(['get',$k]);return 4;}}
class DPMagicCompoundBoth {function __get($k){dp_event(['get',$k]);return 4;}function __set($k,$v){dp_event(['set',$k,$v]);}}
class DPMagicCompoundThrow {function __get($k){dp_event(['get',$k]);throw new Exception('getter');}}
class DPStandard extends stdClass {}
class DPStandardChild extends DPStandard {}
class DPRestore {public $payload=[];function __unserialize($v){$this->payload=$v;}}
class DPRestoreWrite {function __unserialize($v){$this->x=$v['x'];}}
class DPPrivParent {private $x=1;protected $y=2;public $z=3;}
class DPPrivChild extends DPPrivParent {}
class DPÄ {private $x=1;}
class DPDestroy {function __destruct(){dp_event(['destructor',array_keys((array)$this)]);}}
function dp_generator(){yield 1;}
function dp_event($event){global $dp_events;$dp_events[]=$event;}
function dp_rhs($v){dp_event(['rhs',$v]);return $v;}
function dp_wire($class,$pairs){$wire='O:'.($class==='DPÄ'?4:strlen($class)).':"'.$class.'":'.count($pairs).':{';foreach($pairs as $pair)$wire.=serialize($pair[0]).serialize($pair[1]);return $wire.'}';}
function dp_throw_handler($object){set_error_handler(function($severity,$message)use($object){dp_event(['warning',$severity,$message]);dp_event(['seen',property_exists($object,'x'),isset($object->x),$object->x??'missing']);throw new Exception('stop');});}
function dp_compound_handler($o,$inner){set_error_handler(function($s,$m)use($o,$inner){dp_event(['warning',$s,$m]);if($s===8192){set_error_handler(function($s,$m){dp_event(['warning',$s,$m]);return true;});try{$o->x=$inner;}finally{restore_error_handler();}}return true;});}
function dp_case($label,$body){global $dp_events;$dp_events=[];$result=null;$error=null;set_error_handler(function($severity,$message){dp_event(['warning',$severity,$message]);return true;});try{$result=$body();}catch(Throwable $e){$error=[get_class($e),$e->getMessage()];}finally{restore_error_handler();}return [$label,$result,$error,$dp_events];}
$rows=[];
$rows[]=dp_case('repeat-unset-recreate',function(){$o=new DPPlain;$o->x=dp_rhs(1);$o->x=dp_rhs(2);unset($o->x);$o->x=dp_rhs(3);return serialize($o);});
$rows[]=dp_case('per-object-and-property',function(){$a=new DPPlain;$b=new DPPlain;$a->x=1;$a->y=2;$b->x=3;return serialize([$a,$b]);});
$rows[]=dp_case('inherited-runtime-name',function(){$o=new DPChild;$o->x=1;return serialize($o);});
$rows[]=dp_case('static-name-object-slot',function(){$o=new DPDeclared;$o->shared=3;return [serialize($o),DPDeclared::$shared];});
$rows[]=dp_case('stdclass-exempt',function(){$o=new stdClass;$o->x=1;unset($o->x);$o->x=2;return serialize($o);});
$rows[]=dp_case('stdclass-descendants-exempt',function(){$a=new DPStandard;$b=new DPStandardChild;$a->x=1;$b->x=2;return serialize([$a,$b]);});
$rows[]=dp_case('magic-no-actual-slot',function(){$o=new DPMagic;$o->x=1;$o->x=2;return [serialize($o),property_exists($o,'x')];});
$rows[]=dp_case('magic-recursive-actual-slot',function(){$o=new DPRecursive;$o->x=1;$o->x=2;return serialize($o);});
$rows[]=dp_case('getter-only-does-not-exempt',function(){$o=new DPGetter;$o->x=1;return serialize($o);});
$rows[]=dp_case('compound-magic-getter-actual-slot',function(){$o=new DPMagicCompoundGet;$value=($o->x+=1);return [$value,serialize($o)];});
$rows[]=dp_case('compound-magic-getter-setter-no-slot',function(){$o=new DPMagicCompoundBoth;$value=($o->x+=1);return [$value,serialize($o)];});
$rows[]=dp_case('compound-magic-getter-division-no-creation',function(){$o=new DPMagicCompoundGet;try{$o->x/=0;}catch(Throwable $e){return [[get_class($e),$e->getMessage()],serialize($o)];}return 'unreachable';});
$rows[]=dp_case('compound-magic-getter-throw-handler-stores',function(){$o=new DPMagicCompoundGet;set_error_handler(function($s,$m){dp_event(['warning',$s,$m]);throw new Exception('stop');});try{$o->x+=1;}catch(Throwable $e){dp_event(['caught',$e->getMessage()]);}finally{restore_error_handler();}return serialize($o);});
$rows[]=dp_case('compound-magic-getter-throws-no-creation',function(){$o=new DPMagicCompoundThrow;try{$o->x+=1;}catch(Throwable $e){return [[get_class($e),$e->getMessage()],serialize($o)];}return 'unreachable';});
$rows[]=dp_case('reference-preparation',function(){$o=new DPPlain;$r=&$o->x;$before=serialize($o);$r=8;return [$before,serialize($o),$r];});
$rows[]=dp_case('reference-assignment',function(){$o=new DPPlain;$r=2;$o->x=&$r;$r=8;return [serialize($o),$o->x];});
$rows[]=dp_case('array-preparation-and-repeat',function(){$o=new DPPlain;$o->x[]=1;$o->x['key']=2;return serialize($o);});
$rows[]=dp_case('coalesce-create-once',function(){$o=new DPPlain;$o->x??=dp_rhs(1);$o->x??=dp_rhs(2);return serialize($o);});
$rows[]=dp_case('null-existing-coalesce-no-recreate',function(){$o=new DPPlain;$o->x=null;$o->x??=2;return serialize($o);});
$rows[]=dp_case('read-isset-coalesce-no-create',function(){$o=new DPPlain;$a=isset($o->x);$b=$o->x??7;$c=empty($o->x);$d=$o->x;return [$a,$b,$c,$d,serialize($o)];});
$rows[]=dp_case('compound-creates-and-read-warning',function(){$o=new DPPlain;$o->x+=2;return serialize($o);});
$rows[]=dp_case('unserialize-plain',function(){return serialize(unserialize(dp_wire('DPPlain',[['x',1]])));});
$rows[]=dp_case('unserialize-duplicate-key-once',function(){return serialize(unserialize(dp_wire('DPPlain',[['x',1],['x',2]])));});
$rows[]=dp_case('unserialize-magic-bypassed',function(){return serialize(unserialize(dp_wire('DPMagic',[['x',1]])));});
$rows[]=dp_case('unserialize-private-plain-canonical',function(){return serialize(unserialize(dp_wire('DPDeclared',[['secret',3]])));});
$rows[]=dp_case('unserialize-private-mangled-canonical',function(){return serialize(unserialize(dp_wire('DPDeclared',[["\0DPDeclared\0secret",4]])));});
$rows[]=dp_case('unserialize-protected-canonical',function(){return serialize(unserialize(dp_wire('DPDeclared',[["\0*\0protectedValue",5]])));});
$rows[]=dp_case('unserialize-unknown-private',function(){return serialize(unserialize(dp_wire('DPPlain',[["\0Other\0x",1]])));});
$rows[]=dp_case('unserialize-unknown-protected',function(){return serialize(unserialize(dp_wire('DPPlain',[["\0*\0x",1]])));});
$rows[]=dp_case('unserialize-mangled-plain-distinct-slots',function(){return serialize(unserialize(dp_wire('DPPlain',[["\0Other\0x",1],['x',2]])));});
$rows[]=dp_case('unserialize-nonstatic-static-name',function(){$o=unserialize(dp_wire('DPDeclared',[['shared',3]]));return [serialize($o),DPDeclared::$shared];});
$rows[]=dp_case('unserialize-static-private-scope',function(){$o=unserialize(dp_wire('DPDeclared',[["\0DPDeclared\0shared",4]]));return [serialize($o),DPDeclared::$shared];});
$rows[]=dp_case('unserialize-static-protected-scope',function(){$o=unserialize(dp_wire('DPDeclared',[["\0*\0shared",5]]));return [serialize($o),DPDeclared::$shared];});
$rows[]=dp_case('unserialize-static-unknown-scope',function(){$o=unserialize(dp_wire('DPDeclared',[["\0Other\0shared",6]]));return [serialize($o),DPDeclared::$shared];});
$rows[]=dp_case('unserialize-stdclass-descendant',function(){return serialize(unserialize(dp_wire('DPStandardChild',[['x',1]])));});
$rows[]=dp_case('unserialize-hook-no-slot',function(){return serialize(unserialize(dp_wire('DPRestore',[['x',1]])));});
$rows[]=dp_case('unserialize-hook-actual-write',function(){return serialize(unserialize(dp_wire('DPRestoreWrite',[['x',1]])));});
$rows[]=dp_case('unserialize-parent-private-exact-raw-slot',function(){return serialize(unserialize(dp_wire('DPPrivChild',[["\0DPPrivParent\0x",4]])));});
$rows[]=dp_case('unserialize-parent-private-case-different-raw-slot',function(){return serialize(unserialize(dp_wire('DPPrivChild',[["\0dpprivparent\0x",4]])));});
$rows[]=dp_case('unserialize-parent-protected-scope-preserved',function(){return serialize(unserialize(dp_wire('DPPrivChild',[["\0DPPrivParent\0y",4]])));});
$rows[]=dp_case('unserialize-parent-public-scope-preserved',function(){return serialize(unserialize(dp_wire('DPPrivChild',[["\0DPPrivParent\0z",4]])));});
$rows[]=dp_case('unserialize-current-scope-ascii-folded',function(){return serialize(unserialize(dp_wire('DPPrivParent',[["\0dpprivparent\0x",4]])));});
$rows[]=dp_case('unserialize-unicode-current-scope-exact',function(){return serialize(unserialize(dp_wire('DPÄ',[["\0DPÄ\0x",4]])));});
$rows[]=dp_case('unserialize-unicode-other-scope-preserved',function(){return serialize(unserialize(dp_wire('DPÄ',[["\0DPä\0x",4]])));});
$rows[]=dp_case('unserialize-warning-before-value-autoload',function(){$loader=function($name){dp_event(['autoload',$name]);};spl_autoload_register($loader);try{$o=unserialize('O:7:"DPPlain":1:{s:1:"x";O:11:"DPAutoloadX":0:{}}');return property_exists($o,'x');}finally{spl_autoload_unregister($loader);}});
$rows[]=dp_case('throw-unserialize-before-value-autoload',function(){$loader=function($name){dp_event(['autoload',$name]);};spl_autoload_register($loader);set_error_handler(function($s,$m){dp_event(['warning',$s,$m]);throw new Exception('stop');});try{unserialize('O:7:"DPPlain":1:{s:1:"x";O:11:"DPAutoloadY":0:{}}');}catch(Throwable $e){dp_event(['caught',$e->getMessage()]);}finally{restore_error_handler();spl_autoload_unregister($loader);}return 'done';});
$rows[]=dp_case('throw-setter-rhs-survives',function(){$o=new DPPlain;dp_throw_handler($o);try{$o->x=dp_rhs(7);}catch(Throwable $e){dp_event(['caught',$e->getMessage()]);}finally{restore_error_handler();}return serialize($o);});
$rows[]=dp_case('throw-reference-null-survives',function(){$o=new DPPlain;dp_throw_handler($o);try{$r=&$o->x;}catch(Throwable $e){dp_event(['caught',$e->getMessage()]);}finally{restore_error_handler();}return serialize($o);});
$rows[]=dp_case('throw-array-null-survives',function(){$o=new DPPlain;dp_throw_handler($o);try{$o->x[]=dp_rhs(7);}catch(Throwable $e){dp_event(['caught',$e->getMessage()]);}finally{restore_error_handler();}return serialize($o);});
$rows[]=dp_case('throw-coalesce-rhs-survives',function(){$o=new DPPlain;dp_throw_handler($o);try{$o->x??=dp_rhs(7);}catch(Throwable $e){dp_event(['caught',$e->getMessage()]);}finally{restore_error_handler();}return serialize($o);});
$rows[]=dp_case('throw-compound-computed-value-survives',function(){$o=new DPPlain;set_error_handler(function($s,$m)use($o){dp_event(['warning',$s,$m]);if($s===8192){dp_event(['seen',property_exists($o,'x'),$o->x??'missing']);throw new Exception('stop');}return true;});try{$o->x+=dp_rhs(7);}catch(Throwable $e){dp_event(['caught',$e->getMessage()]);}finally{restore_error_handler();}return serialize($o);});
$rows[]=dp_case('compound-reentrant-inner-value-add',function(){$o=new DPPlain;dp_compound_handler($o,4);try{$o->x+=1;}finally{restore_error_handler();}return serialize($o);});
$rows[]=dp_case('compound-reentrant-inner-value-divide-error',function(){$o=new DPPlain;dp_compound_handler($o,4);try{$o->x/=0;}catch(Throwable $e){return [[get_class($e),$e->getMessage()],serialize($o)];}finally{restore_error_handler();}return 'unreachable';});
$rows[]=dp_case('compound-reentrant-inner-value-concat',function(){$o=new DPPlain;dp_compound_handler($o,4);try{$o->x.='a';}finally{restore_error_handler();}return serialize($o);});
$rows[]=dp_case('compound-reentrant-inner-array-type-error',function(){$o=new DPPlain;dp_compound_handler($o,[4]);try{$o->x+=1;}catch(Throwable $e){return [[get_class($e),$e->getMessage()],serialize($o)];}finally{restore_error_handler();}return 'unreachable';});
$rows[]=dp_case('compound-division-error-keeps-null',function(){$o=new DPPlain;try{$o->x/=0;}catch(Throwable $e){return [[get_class($e),$e->getMessage()],serialize($o)];}return 'unreachable';});
$rows[]=dp_case('compound-modulo-error-keeps-null',function(){$o=new DPPlain;try{$o->x%=0;}catch(Throwable $e){return [[get_class($e),$e->getMessage()],serialize($o)];}return 'unreachable';});
$rows[]=dp_case('throw-compound-division-error-wins',function(){$o=new DPPlain;dp_throw_handler($o);try{$o->x/=0;}catch(Throwable $e){return [[get_class($e),$e->getMessage()],serialize($o)];}finally{restore_error_handler();}return 'unreachable';});
$rows[]=dp_case('closure-prohibited-write',function(){$o=function(){};$o->x=dp_rhs(1);return 'unreachable';});
$rows[]=dp_case('closure-prohibited-reference',function(){$o=function(){};$r=&$o->x;return 'unreachable';});
$rows[]=dp_case('generator-prohibited-write',function(){$o=dp_generator();$o->x=dp_rhs(1);return 'unreachable';});
$rows[]=dp_case('generator-prohibited-array',function(){$o=dp_generator();$o->x[]=dp_rhs(1);return 'unreachable';});
$rows[]=dp_case('exception-deprecates',function(){$o=new Exception;$o->x=1;return $o->x;});
return $rows;
