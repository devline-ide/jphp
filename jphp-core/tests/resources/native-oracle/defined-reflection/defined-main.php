<?php
namespace DefCheck;
function row($id,$value){echo json_encode([$id,$value],JSON_THROW_ON_ERROR|JSON_UNESCAPED_SLASHES),"\n";}
function probe($id,$name){try{row($id,defined($name));}catch(\Throwable $e){row($id,[get_class($e),$e->getMessage()]);}}
const GLOBAL_NULL=null;
class A {public const PUB=null;protected const PRO=2;private const PRI=3;
 static function inside(){return [defined('self::PUB'),defined('self::PRO'),defined('self::PRI'),defined('static::PUB')];}}
class B extends A {static function inherited(){return [defined('parent::PUB'),defined('parent::PRO'),defined('parent::PRI'),defined('self::PRI')];}}
probe('global-null','DefCheck\\GLOBAL_NULL');probe('public-null','DefCheck\\A::PUB');probe('leading-slash','\\DefCheck\\A::PUB');
probe('class-case','defcheck\\a::PUB');probe('constant-case','DefCheck\\A::pub');probe('protected-outside','DefCheck\\A::PRO');probe('private-outside','DefCheck\\A::PRI');
row('inside',A::inside());row('inherited',B::inherited());probe('class-pseudo','DefCheck\\A::class');
probe('self-outside','self::PUB');probe('parent-outside','parent::PUB');probe('static-outside','static::PUB');
class Alone {static function test(){probe('parent-without-parent','parent::PUB');}} Alone::test();
$events=[];spl_autoload_register(function($name)use(&$events){$events[]=$name;if($name==='DefCheck\\Loaded')eval('namespace DefCheck;class Loaded {const X=null;}');if($name==='DefCheck\\Boom')throw new \RuntimeException('autoload failed');});
probe('autoload-present','DefCheck\\Loaded::X');probe('autoload-missing','DefCheck\\Absent::X');probe('autoload-throw','DefCheck\\Boom::X');row('autoload-order',$events);
probe('malformed-extra','DefCheck\\A::PUB::tail');probe('empty-member','DefCheck\\A::');probe('nul-class',"DefCheck\\A\0::PUB");
class Delayed {public const VALUE=UNKNOWN_DEFINITION;}
probe('deferred-existence','DefCheck\\Delayed::VALUE');
