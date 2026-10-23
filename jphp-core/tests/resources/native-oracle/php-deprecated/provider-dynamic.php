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

route_case('provider-dynamic',function(){ $x=new Deprecated();$x->other=1;return get_object_vars($x); });

restore_error_handler();
}
