<?php

namespace RouteCustom {
    #[\Attribute(\Attribute::TARGET_FUNCTION|\Attribute::TARGET_METHOD)] class Deprecated {}
    #[Deprecated] function work(){return 13;}
    class Owner {#[Deprecated] public function work(){return 17;}}
}
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

route_case('namespaced-custom-function',function(){return [\RouteCustom\work(),(new ReflectionFunction('RouteCustom\\work'))->isDeprecated()];});
route_case('namespaced-custom-method',function(){return [(new \RouteCustom\Owner)->work(),(new ReflectionMethod('RouteCustom\\Owner','work'))->isDeprecated()];});
trait RouteAliasTrait {#[Deprecated('trait-method')] public function original(){return 11;}}
class RouteAliasOwner {use RouteAliasTrait {original as renamed;}}
route_case('trait-method-alias-names',function(){$object=new RouteAliasOwner;return [$object->original(),$object->renamed()];});
route_case('trait-method-alias-reflection',function(){return [(new ReflectionMethod(RouteAliasOwner::class,'original'))->isDeprecated(),(new ReflectionMethod(RouteAliasOwner::class,'renamed'))->isDeprecated()];});

restore_error_handler();
}
