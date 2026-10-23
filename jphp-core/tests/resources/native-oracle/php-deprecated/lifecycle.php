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

class RouteConstruct {
    #[Deprecated('construct')] public function __construct(){global $routeEvents;$routeEvents[]=['constructor-body'];}
}
class RouteClone {
    #[Deprecated('clone')] public function __clone(){global $routeEvents;$routeEvents[]=['clone-body'];}
}
class RouteDestruct {
    #[Deprecated('destruct')] public function __destruct(){global $routeEvents;$routeEvents[]=['destructor-body'];}
}
route_case('constructor',function(){return get_class(new RouteConstruct);});
route_case('clone',function(){return get_class(clone new RouteClone);});
route_case('destructor-unset-native-lifetime',function(){$object=new RouteDestruct;unset($object);return 'after-unset';});
route_case('constructor-throws',function(){global $routeThrow;$routeThrow=true;return get_class(new RouteConstruct);});
route_case('clone-throws',function(){global $routeThrow;$object=new RouteClone;$routeThrow=true;return get_class(clone $object);});

restore_error_handler();
}
