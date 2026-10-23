<?php
$events = [];
set_error_handler(function($n,$m) use (&$events) { $events[] = [$n,str_replace(__FILE__,'{fixture}',$m)]; return true; });
#[Deprecated('anonymous trait')] trait DepAnonymousTrait {}
function dep_make_anonymous() { return new class { use DepAnonymousTrait; }; }
$one = dep_make_anonymous(); echo 'first:',json_encode($events),"\n";
$two = dep_make_anonymous(); echo 'second:',json_encode($events),"\n";
echo 'same-class:',json_encode(get_class($one) === get_class($two)),"\n";
