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

#[Deprecated('visible-trait')] trait RouteTrait {}
echo 'ordinary-before:',json_encode([class_exists('RouteC',false),class_exists('RouteD',false)]),"\n";
set_error_handler(function($severity,$message){
    global $routeEvents;
    $routeEvents[]=['warning',$severity,$message,class_exists('RouteC',false),class_exists('RouteD',false)];return true;
});
class RouteC {use RouteTrait;}
class RouteD extends RouteC {}
echo 'ordinary-after:',json_encode([class_exists('RouteC',false),class_exists('RouteD',false),$routeEvents]),"\n";
restore_error_handler();
function route_retry_declare(){class RouteRetryC {use RouteTrait;} class RouteRetryD extends RouteRetryC {}}
echo 'retry-before:',json_encode([class_exists('RouteRetryC',false),class_exists('RouteRetryD',false)]),"\n";
route_case('retry-first-throws',function(){global $routeThrow;$routeThrow=true;route_retry_declare();return 'after';});
echo 'retry-after-failure:',json_encode([class_exists('RouteRetryC',false),class_exists('RouteRetryD',false)]),"\n";
route_case('retry-second-publishes',function(){route_retry_declare();return [class_exists('RouteRetryC',false),class_exists('RouteRetryD',false)];});

restore_error_handler();
}
