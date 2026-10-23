<?php
namespace {

$routeEvents=[]; $routeThrow=false;
error_reporting(E_ALL);
set_error_handler(function($severity,$message)use(&$routeEvents,&$routeThrow){
    $routeEvents[]=['warning',$severity,str_replace(__FILE__,'{fixture}',$message)];
    if($routeThrow)throw new RuntimeException('route-handler');
    return true;
});
function route_case($id,$work){
    global $routeEvents,$routeThrow;
    $routeEvents=[];$routeThrow=false;
    try{$value=['ok',$work()];}
    catch(Throwable $error){$value=['throw',get_class($error),str_replace(__FILE__,'{fixture}',$error->getMessage())];}
    echo $id,':',json_encode([$value,$routeEvents]),"\n";
}

class RouteMagic {
    #[Deprecated('get')] public function __get($key){global $routeEvents;$routeEvents[]=['get-body',$key];return 4;}
    #[Deprecated('set')] public function __set($key,$value){global $routeEvents;$routeEvents[]=['set-body',$key,$value];}
    #[Deprecated('isset')] public function __isset($key){global $routeEvents;$routeEvents[]=['isset-body',$key];return true;}
    #[Deprecated('unset')] public function __unset($key){global $routeEvents;$routeEvents[]=['unset-body',$key];}
}
$routeMagic=new RouteMagic;
route_case('magic-get',function()use($routeMagic){return $routeMagic->value;});
route_case('magic-set',function()use($routeMagic){$routeMagic->value=5;return 'after';});
route_case('magic-isset',function()use($routeMagic){return isset($routeMagic->value);});
route_case('magic-unset',function()use($routeMagic){unset($routeMagic->value);return 'after';});
route_case('magic-get-throws',function()use($routeMagic){global $routeThrow;$routeThrow=true;return $routeMagic->value;});
route_case('magic-set-throws',function()use($routeMagic){global $routeThrow;$routeThrow=true;$routeMagic->value=9;return 'after';});

restore_error_handler();
}
