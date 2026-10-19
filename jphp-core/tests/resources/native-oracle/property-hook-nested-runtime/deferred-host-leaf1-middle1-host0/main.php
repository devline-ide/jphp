<?php
declare(strict_types=0);
require_once __DIR__.'/legacy.php';require_once __DIR__.'/leaf.php';require_once __DIR__.'/middle.php';require_once __DIR__.'/consumer.php';
set_error_handler(static function($number,$message){echo "W:$number:$message;";return true;});
$o=new Host;
try{echo "get:".$o->p."\n";}catch(Throwable $e){echo get_class($e)."|".$e->getMessage()."\n";}
try{$o->p="9";echo "set:ok\n";}catch(Throwable $e){echo get_class($e)."|".$e->getMessage()."\n";}
$reference=&$o->q;$reference=8;echo "stored:".json_encode(get_mangled_object_vars($o))."\n";
$hook=(new ReflectionProperty(Host::class,'p'))->getHook(PropertyHookType::Get);echo "scope:".$hook->getDeclaringClass()->getName()."\n";
echo (new LegacyHelper)->label()."\n";
restore_error_handler();
