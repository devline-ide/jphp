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

class RouteHooks {
    public int $value {
        #[Deprecated('hook-get')] get {global $routeEvents;$routeEvents[]=['get-hook-body'];return 7;}
        #[Deprecated('hook-set')] set {global $routeEvents;$routeEvents[]=['set-hook-body',$value];}
    }
}
$routeHooks=new RouteHooks;
route_case('hook-get',function()use($routeHooks){return $routeHooks->value;});
route_case('hook-set',function()use($routeHooks){$routeHooks->value=8;return 'after';});
route_case('hook-get-throws',function()use($routeHooks){global $routeThrow;$routeThrow=true;return $routeHooks->value;});
route_case('hook-set-throws',function()use($routeHooks){global $routeThrow;$routeThrow=true;$routeHooks->value=9;return 'after';});

restore_error_handler();
}
