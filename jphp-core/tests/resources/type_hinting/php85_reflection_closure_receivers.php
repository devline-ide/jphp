<?php
class ReceiverBoundaryOwner {function value(){return 'owned';}}
class ReceiverBoundaryForeign {}
$r=new ReflectionMethod(ReceiverBoundaryOwner::class,'value');
foreach(['omitted'=>[], 'null'=>[null], 'foreign'=>[new ReceiverBoundaryForeign], 'scalar'=>[123]] as $name=>$args){
    try{$c=$r->getClosure(...$args);echo $name,':closure:', $c(),"\n";}
    catch(Throwable $e){echo $name,':',get_class($e),':',$e->getMessage(),"\n";}
}
