<?php
function emit($tag,$value){echo $tag.'='.bin2hex(serialize($value))."\n";}
class ADPSide {public static $calls=0;public function __construct(){self::$calls++;}}
#[AllowDynamicProperties(new ADPSide)] class ADPArgument {}
$x=new ADPArgument;$x->x=2;emit('before',[ADPSide::$calls,$x->x]);
$a=(new ReflectionClass('ADPArgument'))->getAttributes()[0];$a->getArguments();emit('arguments',ADPSide::$calls);
try{$a->newInstance();}catch(Throwable $e){emit('newInstance',[ADPSide::$calls,get_class($e),$e->getMessage()]);}
